/*
 * Copyright (C) 2025-2026 The AviumUI Project
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Derived from AviumUI OccludingMaskLayout (7d9112e58438f05f62a3a87af46c819eb5b7b85b).
 * ArkUI replaces polling and whole-screen overpainting with event-driven clock-only masking.
 */
package com.android.systemui.arkui.depth;

import static com.android.settingslib.arkui.DepthWallpaperContract.*;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.PathInterpolator;

import com.android.internal.os.BackgroundThread;
import com.android.settingslib.arkui.DepthWallpaperContract.Option;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Reveals the real wallpaper behind clock pixels; never covers notifications or security UI. */
public final class DepthWallpaperRenderer implements StatusBarStateController.StateListener {
    private static final String TAG = "ArkuiDepthWallpaper";
    private final View mRoot;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Paint mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final Matrix mInverse = new Matrix();
    private final RectF mClockBounds = new RectF();
    private final int[] mLocation = new int[2];
    private final Point mScreen = new Point();
    private final float[] mMatrixValues = new float[9];
    private float mLastScale, mLastTx, mLastTy;
    private final Map<String, Integer> mOptions = new HashMap<>();
    private final Map<View, Float> mCoverageCache = new HashMap<>();
    private final Map<View, RectF> mBoundsCache = new HashMap<>();
    private StatusBarStateController mStatusBar;
    private UserTracker mUserTracker;
    private Bitmap mMask;
    private ValueAnimator mAnimator;
    private float mReveal = 1f;
    private int mLoadGeneration;
    private boolean mAttached;
    private boolean mPowerSave;
    private final Runnable mReload = this::load;
    private final ContentObserver mObserver = new ContentObserver(mMain) {
        @Override public void onChange(boolean selfChange) { requestReload(); }
    };
    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override public void onUserChanging(int newUser, Context userContext) {
            ++mLoadGeneration;
            mMask = null;
            mRoot.invalidate();
        }
        @Override public void onUserChanged(int newUser, Context userContext) { requestReload(); }
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                ++mLoadGeneration;
                mMask = null; // A mask from an old photo must never hide the new clock.
                mRoot.invalidate();
                requestReload();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                reveal();
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (mAnimator != null) mAnimator.cancel();
                mReveal = 1f;
            } else {
                updatePowerSave();
                mRoot.invalidate();
            }
        }
    };

    public DepthWallpaperRenderer(View root) {
        mRoot = root;
        mContext = root.getContext();
        mMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    public void attach() {
        if (mAttached) return;
        mAttached = true;
        updatePowerSave();
        geometryChanged();
        mStatusBar = Dependency.get(StatusBarStateController.class);
        mUserTracker = Dependency.get(UserTracker.class);
        mStatusBar.addCallback(this);
        mUserTracker.addCallback(mUserCallback, mContext.getMainExecutor());
        for (Option option : OPTIONS) mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(option.key()), false, mObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(REVISION), false, mObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(URI, true, mObserver,
                UserHandle.USER_ALL);
        final IntentFilter filter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        requestReload();
    }

    public void detach() {
        if (!mAttached) return;
        mAttached = false;
        ++mLoadGeneration;
        mMain.removeCallbacks(mReload);
        mStatusBar.removeCallback(this);
        mUserTracker.removeCallback(mUserCallback);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mContext.unregisterReceiver(mReceiver);
        if (mAnimator != null) mAnimator.cancel();
        mMask = null;
        mCoverageCache.clear();
        mBoundsCache.clear();
    }

    private void requestReload() {
        mMain.removeCallbacks(mReload);
        mMain.postDelayed(mReload, 80);
    }

    private void load() {
        if (!mAttached) return;
        final int generation = ++mLoadGeneration;
        final Context userContext = mUserTracker.getUserContext();
        final int userId = mUserTracker.getUserId();
        BackgroundThread.getHandler().post(() -> {
            final Map<String, Integer> values = new HashMap<>();
            for (Option option : OPTIONS) values.put(option.key(),
                    option.read(userContext.getContentResolver(), userId));
            Bitmap mask = null;
            if (values.get(ENABLED.key()) != 0) {
                try {
                    // The signature-protected provider performs recognition in Settings' worker.
                    userContext.getContentResolver().call(URI, "refresh", null, null);
                    try (InputStream stream = userContext.getContentResolver()
                            .openInputStream(MASK_URI)) {
                        mask = BitmapFactory.decodeStream(stream);
                    }
                    if (mask != null) {
                        final int radius = Math.round(values.get(FEATHER.key())
                                * mContext.getResources().getDisplayMetrics().density
                                * mask.getWidth() / Math.max(1, mScreen.x));
                        mask = feather(mask, radius);
                    }
                } catch (Exception e) {
                    // Unsupported/live wallpapers and recognition failures keep the normal clock.
                    Log.d(TAG, "No matching depth mask for user " + userId);
                }
            }
            final Bitmap result = mask;
            mMain.post(() -> {
                if (!mAttached || generation != mLoadGeneration) return;
                mOptions.clear();
                mOptions.putAll(values);
                mMask = result;
                mCoverageCache.clear();
                mBoundsCache.clear();
                mRoot.invalidate();
            });
        });
    }

    private int value(Option option) {
        return mOptions.getOrDefault(option.key(), option.defaultValue());
    }

    private void updatePowerSave() {
        final PowerManager power = mContext.getSystemService(PowerManager.class);
        mPowerSave = power != null && power.isPowerSaveMode();
    }

    public void geometryChanged() {
        if (mRoot.getDisplay() != null) mRoot.getDisplay().getRealSize(mScreen);
        clocksChanged();
    }

    public void clocksChanged() {
        mCoverageCache.clear();
        mBoundsCache.clear();
    }

    private void reveal() {
        if (mAnimator != null) mAnimator.cancel();
        if (value(ANIMATE) == 0 || mPowerSave
                || !ValueAnimator.areAnimatorsEnabled()) {
            mReveal = 1f;
            mRoot.invalidate();
            return;
        }
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(value(DURATION));
        mAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        mAnimator.addUpdateListener(animation -> {
            mReveal = (float) animation.getAnimatedValue();
            mRoot.invalidate();
        });
        mAnimator.start();
    }

    /** Called immediately before super.drawChild; all original view IDs and constraints survive. */
    public int beginClock(Canvas canvas, View child, boolean large) {
        if (mMask == null || value(ENABLED) == 0 || (!large && value(LARGE_ONLY) != 0)
                || child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) return -1;
        if (value(BATTERY_SAVER) != 0 && mPowerSave) return -1;
        final float doze = mStatusBar.getDozeAmount();
        float alpha = value(OPACITY) / 100f * (1f - doze);
        if (value(AOD) != 0) alpha += value(AOD_OPACITY) / 100f * doze;
        alpha *= mReveal;
        if (alpha <= 0.001f) return -1;
        updateMatrix();
        mClockBounds.set(0, 0, child.getWidth(), child.getHeight());
        child.getMatrix().mapRect(mClockBounds);
        mClockBounds.offset(child.getLeft() - mRoot.getScrollX(),
                child.getTop() - mRoot.getScrollY());
        final RectF last = mBoundsCache.get(child);
        if (last == null || !last.equals(mClockBounds)) {
            mBoundsCache.put(child, new RectF(mClockBounds));
            mCoverageCache.put(child, estimateCoverage(mClockBounds));
        }
        final float coverage = mCoverageCache.getOrDefault(child, 0f);
        // Cap average clock occlusion even when a large subject covers its entire face.
        if (coverage > 0) alpha = Math.min(alpha, value(COVERAGE) / 100f / coverage);
        mMaskPaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        return canvas.saveLayer(mClockBounds, null);
    }

    public void endClock(Canvas canvas, int saveCount) {
        if (saveCount < 0) return;
        canvas.drawBitmap(mMask, mMatrix, mMaskPaint);
        canvas.restoreToCount(saveCount);
    }

    private void updateMatrix() {
        mRoot.getLocationOnScreen(mLocation);
        final float scale = Math.max((float) mScreen.x / mMask.getWidth(),
                (float) mScreen.y / mMask.getHeight()) * value(SCALE) / 100f;
        mMatrix.setScale(scale, scale);
        mMatrix.postTranslate((mScreen.x - mMask.getWidth() * scale) / 2f - mLocation[0]
                + mScreen.x * value(OFFSET_X) / 100f,
                (mScreen.y - mMask.getHeight() * scale) / 2f - mLocation[1]
                + mScreen.y * value(OFFSET_Y) / 100f);
        mMatrix.getValues(mMatrixValues);
        if (mLastScale != scale || mLastTx != mMatrixValues[Matrix.MTRANS_X]
                || mLastTy != mMatrixValues[Matrix.MTRANS_Y]) {
            mLastScale = scale;
            mLastTx = mMatrixValues[Matrix.MTRANS_X];
            mLastTy = mMatrixValues[Matrix.MTRANS_Y];
            clocksChanged();
        }
        mMatrix.invert(mInverse);
    }

    private float estimateCoverage(RectF bounds) {
        if (bounds.isEmpty()) return 0f;
        float sum = 0;
        final float[] point = new float[2];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            point[0] = bounds.left + bounds.width() * (x + 0.5f) / 16;
            point[1] = bounds.top + bounds.height() * (y + 0.5f) / 16;
            mInverse.mapPoints(point);
            final int bx = (int) point[0], by = (int) point[1];
            if (bx >= 0 && by >= 0 && bx < mMask.getWidth() && by < mMask.getHeight()) {
                sum += (mMask.getPixel(bx, by) >>> 24) / 255f;
            }
        }
        return sum / 256;
    }

    @Override public void onDozeAmountChanged(float linear, float eased) { mRoot.invalidate(); }
    @Override public void onStateChanged(int state) { mRoot.invalidate(); }
}
