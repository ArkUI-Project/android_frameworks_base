/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Display-overlay approach adapted from crDroid's AxionOS SmartPixelOverlay and
 * SmartPixelView, revision 68d3d80589e4cc8dd34c470db3aa9c027f610674 (16.0):
 * https://github.com/crdroidandroid/android_frameworks_base/tree/16.0/packages/SystemUI/src/com/android/systemui/smartpixel
 * ArkUI adds per-user lifecycle, battery-saver integration, periodic pattern shifts,
 * capture exclusion, and suspension while locked or showing biometric authentication.
 */
package com.android.systemui.arkui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.settingslib.arkui.SmartPixelsPattern;
import com.android.settingslib.arkui.SmartPixelsSettings;
import com.android.systemui.CoreStartable;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;

import javax.inject.Inject;

@SysUISingleton
public final class SmartPixelsController implements CoreStartable {
    private static final long SHIFT_MS = 60_000;
    private final Context mContext;
    private final Handler mMain;
    private final UserTracker mUsers;
    private final KeyguardStateController mKeyguard;
    private final AuthController mAuth;
    private final PowerManager mPower;
    private final WindowManager mWindow;
    private PixelView mView;
    private boolean mBiometricPrompt;
    private int mPercent;
    private final Runnable mShift = () -> {
        if (mView != null) {
            mView.update(mPercent);
            scheduleShift();
        }
    };

    @Inject public SmartPixelsController(Context context, @Main Handler main, UserTracker users,
            KeyguardStateController keyguard, AuthController auth) {
        mContext = context;
        mMain = main;
        mUsers = users;
        mKeyguard = keyguard;
        mAuth = auth;
        mPower = context.getSystemService(PowerManager.class);
        mWindow = context.getSystemService(WindowManager.class);
    }

    @Override public void start() {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        ContentObserver observer = new ContentObserver(mMain) {
            @Override public void onChange(boolean selfChange) { update(); }
        };
        for (String key : SmartPixelsSettings.KEYS) {
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),
                    false, observer, UserHandle.USER_ALL);
        }
        mUsers.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanged(int user, @NonNull Context context) {
                hide();
                update();
            }
        }, mMain::post);
        mKeyguard.addCallback(new KeyguardStateController.Callback() {
            @Override public void onKeyguardShowingChanged() { mMain.post(SmartPixelsController.this::update); }
        });
        mAuth.addCallback(new AuthController.Callback() {
            @Override public void onBiometricPromptShown() {
                mBiometricPrompt = true;
                update();
            }
            @Override public void onBiometricPromptDismissed() {
                mBiometricPrompt = false;
                update();
            }
        });
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { update(); }
        }, filter, null, mMain, Context.RECEIVER_NOT_EXPORTED);
        update();
    }

    private void update() {
        var resolver = mContext.getContentResolver();
        int user = mUsers.getUserId();
        UserManager manager = mUsers.getUserContext().getSystemService(UserManager.class);
        boolean enabled = SmartPixelsSettings.enabled(resolver, user) && mPower.isInteractive()
                && manager.isUserUnlocked() && !manager.isManagedProfile()
                && !mKeyguard.isShowing() && !mBiometricPrompt
                && (!SmartPixelsSettings.powerSaveOnly(resolver, user) || mPower.isPowerSaveMode());
        if (!enabled) { hide(); return; }
        int percent = SmartPixelsSettings.percent(resolver, user);
        if (mView == null) {
            PixelView view = new PixelView(mContext);
            view.update(percent);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setTitle("ArkUI Smart Pixels");
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                    | WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
                    | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
                    | WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
            try {
                mWindow.addView(view, params);
                mView = view;
            } catch (RuntimeException e) {
                Log.w("ArkSmartPixels", "Cannot attach pixel filter", e);
                return;
            }
            mPercent = percent;
            scheduleShift();
        } else if (mPercent != percent) {
            mPercent = percent;
            mView.update(percent);
        }
    }

    private void scheduleShift() {
        mMain.removeCallbacks(mShift);
        mMain.postDelayed(mShift, SHIFT_MS - SystemClock.elapsedRealtime() % SHIFT_MS);
    }

    private void hide() {
        mMain.removeCallbacks(mShift);
        if (mView == null) return;
        mWindow.removeViewImmediate(mView);
        mView = null;
    }

    @Override public void dump(@NonNull PrintWriter writer, @NonNull String[] args) {
        writer.println("visible=" + (mView != null) + " percent=" + mPercent
                + " user=" + mUsers.getUserId() + " biometricPrompt=" + mBiometricPrompt);
    }

    private static final class PixelView extends View {
        private final Paint mPaint = new Paint();
        PixelView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mPaint.setAntiAlias(false);
            mPaint.setFilterBitmap(false);
        }
        void update(int percent) {
            int phase = (int) ((SystemClock.elapsedRealtime() / SHIFT_MS) % 64);
            Bitmap tile = Bitmap.createBitmap(SmartPixelsPattern.create(percent, phase),
                    SmartPixelsPattern.SIZE, SmartPixelsPattern.SIZE, Bitmap.Config.ARGB_8888);
            mPaint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        }
    }
}
