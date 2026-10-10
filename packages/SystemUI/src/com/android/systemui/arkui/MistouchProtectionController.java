/*
 * Copyright (C) 2025-2026 The AviumUI Project
 * Copyright (C) 2025 The AxionAOSP Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Pocket detection adapted from AviumUI's MistouchPreventionWindowController.
 */
package com.android.systemui.arkui;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;

import javax.inject.Inject;

@SysUISingleton
public final class MistouchProtectionController implements CoreStartable, SensorEventListener {
    private static final String KEY = "arkui_mistouch_protection";
    private final Context mContext;
    private final Handler mMain;
    private final UserTracker mUsers;
    private final KeyguardStateController mKeyguard;
    private final KeyguardUpdateMonitor mMonitor;
    private final WakefulnessLifecycle mWakefulness;
    private final SensorManager mSensors;
    private final WindowManager mWindow;
    private final Sensor mProximity;
    private final Sensor mAcceleration;
    private boolean mAwake;
    private boolean mListening;
    private boolean mSwitchingUser;
    private boolean mSuppressed;
    private boolean mNear;
    private boolean mPocketPose;
    private ProtectionView mView;
    private final Runnable mShow = () -> {
        if (mListening && !mSuppressed && (mNear || mPocketPose)) show();
    };

    @Inject public MistouchProtectionController(Context context, @Main Handler main,
            UserTracker users, KeyguardStateController keyguard, KeyguardUpdateMonitor monitor,
            WakefulnessLifecycle wakefulness) {
        mContext = context;
        mMain = main;
        mUsers = users;
        mKeyguard = keyguard;
        mMonitor = monitor;
        mWakefulness = wakefulness;
        mSensors = context.getSystemService(SensorManager.class);
        mWindow = context.getSystemService(WindowManager.class);
        mProximity = mSensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mAcceleration = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mAwake = context.getSystemService(PowerManager.class).isInteractive();
    }

    @Override public void start() {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY),
                false, new ContentObserver(mMain) {
                    @Override public void onChange(boolean selfChange) { update(); }
                }, UserHandle.USER_ALL);
        mUsers.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanging(int user, @NonNull Context context) {
                mSwitchingUser = true;
                update();
            }
            @Override public void onUserChanged(int user, @NonNull Context context) {
                mSwitchingUser = false;
                mSuppressed = false;
                update();
            }
        }, mMain::post);
        mKeyguard.addCallback(new KeyguardStateController.Callback() {
            @Override public void onKeyguardShowingChanged() { update(); }
            @Override public void onKeyguardGoingAwayChanged() { update(); }
            @Override public void onKeyguardFadingAwayChanged() { update(); }
        });
        mMonitor.registerCallback(new KeyguardUpdateMonitorCallback() {
            @Override public void onKeyguardVisibilityChanged(boolean visible) { update(); }
            @Override public void onEmergencyCallAction() { suppress(); }
        });
        mWakefulness.addObserver(new WakefulnessLifecycle.Observer() {
            @Override public void onStartedWakingUp() {
                mAwake = true;
                mSuppressed = false;
                update();
            }
            @Override public void onStartedGoingToSleep() {
                mAwake = false;
                update();
            }
        });
        update();
    }

    private void update() {
        boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                KEY, 0, mUsers.getUserId()) != 0;
        boolean allowed = enabled && mAwake && !mSwitchingUser && !mSuppressed
                && mKeyguard.isShowing() && !mKeyguard.isOccluded()
                && !mKeyguard.isKeyguardGoingAway() && !mKeyguard.isKeyguardFadingAway()
                && !mUsers.getUserContext().getSystemService(UserManager.class).isManagedProfile();
        if (!mKeyguard.isShowing() || !enabled) mSuppressed = false;
        if (!allowed) {
            if (mListening) mSensors.unregisterListener(this);
            mListening = false;
            mNear = false;
            mPocketPose = false;
            hide();
            return;
        }
        if (!mListening) {
            if (mProximity != null) mListening = mSensors.registerListener(this, mProximity,
                    SensorManager.SENSOR_DELAY_NORMAL, mMain);
            if (mAcceleration != null) mListening |= mSensors.registerListener(this, mAcceleration,
                    SensorManager.SENSOR_DELAY_NORMAL, mMain);
        }
        updateVisibility();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!mListening) return;
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            mNear = event.values[0] < Math.min(1f, event.sensor.getMaximumRange());
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            double magnitude = Math.sqrt(x * x + y * y + z * z);
            double inclination = magnitude > 1
                    ? Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, z / magnitude)))) : 0;
            mPocketPose = magnitude > 1 && y / magnitude < -.7
                    && inclination >= 75 && inclination <= 100;
        }
        updateVisibility();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateVisibility() {
        if (!mNear && !mPocketPose) {
            hide();
        } else if (mView == null && !mMain.hasCallbacks(mShow)) {
            mMain.postDelayed(mShow, 150);
        }
    }

    private void suppress() {
        mSuppressed = true;
        update();
    }

    private void show() {
        if (mView != null) return;
        ProtectionView view = new ProtectionView(mContext);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        params.setTitle("ArkUI Mistouch Protection");
        params.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        try {
            mWindow.addView(view, params);
            mView = view;
            view.requestFocus();
            view.announceForAccessibility(mContext.getString(R.string.arkui_mistouch_message));
        } catch (RuntimeException e) {
            Log.w("ArkMistouch", "Cannot attach protection window", e);
            suppress();
        }
    }

    private void hide() {
        mMain.removeCallbacks(mShow);
        if (mView == null) return;
        mWindow.removeViewImmediate(mView);
        mView = null;
    }

    @Override public void dump(@NonNull PrintWriter writer, @NonNull String[] args) {
        writer.println("listening=" + mListening + " visible=" + (mView != null)
                + " near=" + mNear + " pocketPose=" + mPocketPose
                + " suppressed=" + mSuppressed + " awake=" + mAwake
                + " user=" + mUsers.getUserId());
    }

    private final class ProtectionView extends LinearLayout {
        ProtectionView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(32), dp(48), dp(32), dp(48));
            setBackgroundColor(Color.rgb(17, 19, 24));
            setFocusableInTouchMode(true);
            ImageView illustration = new ImageView(context);
            illustration.setImageResource(R.drawable.ic_arkui_mistouch);
            illustration.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(illustration, new LayoutParams(dp(160), dp(220)));
            addText(R.string.arkui_mistouch_title, 28, Color.WHITE, 32);
            addText(R.string.arkui_mistouch_message, 17, 0xffc3c7d0, 20);
            addText(R.string.arkui_mistouch_exit, 15, 0xffa8c7fa, 40);
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        private void addText(int resource, int size, int color, int margin) {
            TextView text = new TextView(getContext());
            text.setText(resource);
            text.setTextSize(size);
            text.setTextColor(color);
            text.setGravity(Gravity.CENTER);
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(margin);
            addView(text, params);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) { return true; }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) suppress();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
