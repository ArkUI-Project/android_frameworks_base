/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransition;
import android.window.ScreenCaptureInternal;
import android.window.TransitionInfo;
import android.window.WindowAnimationState;

import com.android.internal.arkui.SmallWindowSettings;

import java.util.function.ObjIntConsumer;

/** The direct-launch transition shared by Sidebar and Wheel; never used by Recents gestures. */
final class SmallWindowLaunchAnimation extends IRemoteTransition.Stub {
    private static final String TAG = "SmallWindowLaunch";
    private static final PathInterpolator WIDTH = new PathInterpolator(.18f, .90f, .25f, 1f);
    private static final PathInterpolator HEIGHT = new PathInterpolator(.30f, .70f, .18f, 1f);
    private final Context mContext;
    private final Handler mMain;
    private final String mPackageName;
    private TransitionInfo mInfo;
    private TransitionInfo.Change mChange;
    private IBinder mToken;
    private IRemoteTransitionFinishedCallback mFinish;
    private ValueAnimator mAnimator;
    private SurfaceControl.Transaction mFrame;
    private WindowManager mWindowManager;
    private CardView mCard;
    private final Rect mCrop = new Rect();
    private final RectF mBounds = new RectF();
    private float mRootX, mRootY, mProgress, mFirstCardFrame;
    private boolean mFinished;
    // ScreenCapture keeps weak callbacks. Retain the listener until the single capture returns.
    private ScreenCaptureInternal.ScreenCaptureListener mCapture;
    private ObjIntConsumer<ScreenCaptureInternal.ScreenshotHardwareBuffer> mCaptureReply;

    static void apply(Context context, Handler main, ActivityOptions options, String packageName) {
        if (!WindowConfiguration.isMiniExtWindowMode(options.getLaunchWindowingMode())
                || !SmallWindowSettings.isHyperOsMode(context)) return;
        options.setRemoteTransition(new RemoteTransition(
                new SmallWindowLaunchAnimation(context, main, packageName),
                "ArkUI direct small-window launch"));
    }

    private SmallWindowLaunchAnimation(Context context, Handler main, String packageName) {
        mContext = context;
        mMain = main;
        mPackageName = packageName;
    }

    @Override
    public void startAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finish) {
        mMain.post(() -> {
            try {
                start(token, info, t, finish);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot animate direct small-window launch", e);
                t.close();
                finish(true);
            }
        });
    }

    private void start(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finish) {
        mToken = token;
        mInfo = info;
        mFinish = finish;
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() != null
                    && change.getLeash().isValid()
                    && change.getTaskInfo().userId == mContext.getUserId()
                    && change.getTaskInfo().topActivity != null
                    && mPackageName.equals(change.getTaskInfo().topActivity.getPackageName())
                    && (change.getMode() == WindowManager.TRANSIT_OPEN
                            || change.getMode() == WindowManager.TRANSIT_TO_FRONT
                            || change.getMode() == WindowManager.TRANSIT_CHANGE)
                    && change.getEndDisplayId() == Display.DEFAULT_DISPLAY
                    && WindowConfiguration.isMiniExtWindowMode(
                            change.getTaskInfo().getWindowingMode())
                    && change.getStartRotation() == change.getEndRotation()
                    && change.mPopUpView.mEndScale > 0f
                    && !change.mPopUpView.mWindowCrop.isEmpty()) {
                mChange = change;
                break;
            }
        }
        if (mChange == null || info.findRootIndex(Display.DEFAULT_DISPLAY) < 0) {
            mChange = null;
            t.apply();
            t.close();
            finish(true);
            return;
        }
        mCrop.set(mChange.mPopUpView.mWindowCrop);
        if (mChange.getParent() == null) {
            TransitionInfo.Root root = info.getRoot(info.findRootIndex(Display.DEFAULT_DISPLAY));
            mRootX = -root.getOffset().x;
            mRootY = -root.getOffset().y;
        } else {
            mRootX = mChange.getEndRelOffset().x - mChange.getEndAbsBounds().left;
            mRootY = mChange.getEndRelOffset().y - mChange.getEndAbsBounds().top;
        }
        float durationScale = Math.max(0f, Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, 1f));
        applyFrame(t, durationScale == 0f ? 1f : 0f);
        t.apply();
        t.close();
        if (durationScale == 0f) {
            finish(true);
            return;
        }
        capture();
        mFrame = new SurfaceControl.Transaction();
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(Math.round(420 * durationScale));
        mAnimator.overrideDurationScale(1f);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(value -> {
            if (mFinished || !mChange.getLeash().isValid()) return;
            applyFrame(mFrame, (float) value.getAnimatedValue());
            if (mCard != null) mCard.queueFrame(mFrame);
            else mFrame.apply();
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finish(true); }
        });
        mAnimator.start();
    }

    private void applyFrame(SurfaceControl.Transaction t, float progress) {
        mProgress = progress;
        float scale = mChange.mPopUpView.mEndScale;
        float sx = scale * (.78f + .22f * WIDTH.getInterpolation(progress));
        float sy = scale * (.65f + .35f * HEIGHT.getInterpolation(progress));
        float centerX = mChange.mPopUpView.mEndPos.x + mCrop.width() * scale / 2f;
        float centerY = mChange.mPopUpView.mEndPos.y + mCrop.height() * scale / 2f
                + 20f * mContext.getResources().getDisplayMetrics().density
                        * (1f - WIDTH.getInterpolation(progress));
        float x = centerX - mCrop.width() * sx / 2f;
        float y = centerY - mCrop.height() * sy / 2f;
        mBounds.set(x, y, x + mCrop.width() * sx, y + mCrop.height() * sy);
        float alpha = smooth(progress / .12f);
        float blend = mCard == null ? 0f : 1f - smooth((progress - .68f) / .20f);
        float cardAlpha = alpha * blend;
        // The bitmap window is above the live task. Preserve total opacity while swapping.
        float liveAlpha = cardAlpha >= 1f ? 0f : (alpha - cardAlpha) / (1f - cardAlpha);
        t.setScale(mChange.getLeash(), sx, sy)
                .setPosition(mChange.getLeash(), x + mRootX, y + mRootY)
                .setWindowCrop(mChange.getLeash(), mCrop)
                .setCornerRadius(mChange.getLeash(), mChange.mPopUpView.mEndCornerRadius)
                .setAlpha(mChange.getLeash(), liveAlpha).show(mChange.getLeash());
        if (mCard != null) {
            float phase = Math.min(1f, progress / .68f);
            float bend = (float) Math.sin(Math.PI * phase)
                    * smooth((progress - mFirstCardFrame) / .07f);
            mCard.update(mBounds, bend, cardAlpha,
                    mChange.mPopUpView.mEndCornerRadius);
        }
    }

    private void capture() {
        if ((mChange.getFlags() & TransitionInfo.FLAG_APP_LOCK_PROTECTED) != 0) return;
        mCaptureReply = (buffer, status) -> mMain.post(() -> {
            mCapture = null;
            mCaptureReply = null;
            if (buffer == null) return;
            try {
                if (!mFinished && status == 0 && !buffer.containsSecureLayers()
                        && mProgress < .55f) attachCard(buffer.asBitmap());
            } finally {
                if (buffer.getHardwareBuffer() != null) buffer.getHardwareBuffer().close();
            }
        });
        mCapture = new ScreenCaptureInternal.ScreenCaptureListener(mCaptureReply);
        try {
            int result = ScreenCaptureInternal.captureLayers(
                    new ScreenCaptureInternal.LayerCaptureArgs.Builder(mChange.getLeash())
                            .setSourceCrop(mCrop).setChildrenOnly(true)
                            .setSecureContentPolicy(android.window.ScreenCapture.ScreenCaptureParams
                                    .SECURE_CONTENT_POLICY_THROW_EXCEPTION)
                            .setProtectedContentPolicy(android.window.ScreenCapture.ScreenCaptureParams
                                    .PROTECTED_CONTENT_POLICY_THROW_EXCEPTION)
                            .setFrameScale(Math.min(1f, 1440f
                                    / Math.max(mCrop.width(), mCrop.height())))
                            .build(), mCapture);
            if (result != 0) { mCapture = null; mCaptureReply = null; }
        } catch (RuntimeException e) {
            mCapture = null;
            mCaptureReply = null;
            Log.w(TAG, "Small-window capture unavailable", e);
        }
    }

    private void attachCard(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return;
            Context context = mContext.createWindowContext(display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            mWindowManager = context.getSystemService(WindowManager.class);
            CardView card = new CardView(context, bitmap, mCrop.width(), mCrop.height());
            card.update(mBounds, 0f, 0f, mChange.mPopUpView.mEndCornerRadius);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setFitInsetsTypes(0);
            params.setTitle("ArkUI direct small-window perspective");
            params.setTrustedOverlay();
            mWindowManager.addView(card, params);
            mCard = card;
            mFirstCardFrame = mProgress;
        } catch (RuntimeException e) {
            Log.w(TAG, "Small-window perspective unavailable", e);
        }
    }

    private void finish(boolean notify) {
        if (mFinished) return;
        mFinished = true;
        SurfaceControl.Transaction end = new SurfaceControl.Transaction();
        try {
            if (mAnimator != null) mAnimator.cancel();
            if (mCard != null) {
                mCard.release();
                if (mCard.isAttachedToWindow()) mWindowManager.removeViewImmediate(mCard);
                mCard = null;
            }
            if (notify && mChange != null && mChange.getLeash().isValid()) {
                applyFrame(end, 1f);
                end.apply();
                // Shell reparents the task when finishing; use the resting parent's space.
                end.setScale(mChange.getLeash(), mChange.mPopUpView.mEndScale,
                                mChange.mPopUpView.mEndScale)
                        .setPosition(mChange.getLeash(),
                                mChange.mPopUpView.mEndPos.x + mChange.getEndRelOffset().x
                                        - mChange.getEndAbsBounds().left,
                                mChange.mPopUpView.mEndPos.y + mChange.getEndRelOffset().y
                                        - mChange.getEndAbsBounds().top)
                        .setWindowCrop(mChange.getLeash(), mCrop)
                        .setCornerRadius(mChange.getLeash(), mChange.mPopUpView.mEndCornerRadius)
                        .setAlpha(mChange.getLeash(), 1f).show(mChange.getLeash());
            }
        } catch (RuntimeException e) {
            // Still return ownership to Shell if the task or display vanished while animating.
            Log.w(TAG, "Small-window surface already removed", e);
        }
        if (mFrame != null) { mFrame.close(); mFrame = null; }
        try {
            if (notify && mFinish != null) mFinish.onTransitionFinished(null, end);
        } catch (RemoteException e) {
            Log.w(TAG, "Small-window transition already finished", e);
        } finally {
            end.close();
            if (mInfo != null) mInfo.releaseAllSurfaces();
            mInfo = null;
            mFinish = null;
        }
    }

    @Override
    public void mergeAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
            IBinder mergeTarget, IRemoteTransitionFinishedCallback finish) {
        mMain.post(() -> {
            if (mergeTarget.equals(mToken)) finish(true);
            // Do not consume the incoming transition; Shell will run its own handler next.
            t.close();
            info.releaseAllSurfaces();
        });
    }

    @Override
    public void takeOverAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finish, WindowAnimationState[] states) {
        startAnimation(token, info, t, finish);
    }

    @Override
    public void onTransitionConsumed(IBinder token, boolean aborted) {
        mMain.post(() -> { if (token.equals(mToken)) finish(false); });
    }

    private static float smooth(float value) {
        float p = Math.max(0f, Math.min(1f, value));
        return p * p * (3f - 2f * p);
    }

    private static final class CardView extends View
            implements ViewTreeObserver.OnPreDrawListener {
        private final Bitmap mBitmap;
        private final RectF mSource;
        private final float[] mSourcePoints;
        private final float[] mPoints = new float[8];
        private final Matrix mMatrix = new Matrix();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path mClip = new Path();
        private final SurfaceControl.Transaction mPendingFrame = new SurfaceControl.Transaction();
        private boolean mHasPendingFrame;
        private boolean mReleased;

        CardView(Context context, Bitmap bitmap, int width, int height) {
            super(context);
            mBitmap = bitmap;
            mSource = new RectF(0, 0, width, height);
            mSourcePoints = new float[]{0, 0, width, 0, width, height, 0, height};
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void queueFrame(SurfaceControl.Transaction transaction) {
            mPendingFrame.merge(transaction);
            mHasPendingFrame = true;
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override public boolean onPreDraw() {
            if (mReleased || !mHasPendingFrame) return true;
            if (!getViewRootImpl().isHardwareEnabled()) {
                postInvalidateOnAnimation();
                return false;
            }
            // Latch the live-task alpha/matrix with the exact perspective bitmap frame.
            getViewRootImpl().applyTransactionOnDraw(mPendingFrame);
            mHasPendingFrame = false;
            return true;
        }

        void release() {
            if (mReleased) return;
            mReleased = true;
            getViewTreeObserver().removeOnPreDrawListener(this);
            mPendingFrame.close();
        }

        @Override protected void onDetachedFromWindow() {
            release();
            super.onDetachedFromWindow();
        }

        void update(RectF bounds, float bend, float alpha, float radius) {
            float inset = bounds.width() * .15f * bend;
            float skew = bounds.width() * .045f * bend;
            float lift = bounds.height() * .025f * bend;
            mPoints[0] = bounds.left + skew; mPoints[1] = bounds.top;
            mPoints[2] = bounds.right + skew; mPoints[3] = bounds.top + lift;
            mPoints[4] = bounds.right - inset - skew; mPoints[5] = bounds.bottom - lift;
            mPoints[6] = bounds.left + inset - skew; mPoints[7] = bounds.bottom;
            mMatrix.setPolyToPoly(mSourcePoints, 0, mPoints, 0, 4);
            mClip.rewind();
            mClip.addRoundRect(mSource, radius, radius, Path.Direction.CW);
            mPaint.setAlpha(Math.round(255f * alpha));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save();
            canvas.concat(mMatrix);
            canvas.clipPath(mClip);
            canvas.drawBitmap(mBitmap, null, mSource, mPaint);
            canvas.restoreToCount(save);
        }
    }
}
