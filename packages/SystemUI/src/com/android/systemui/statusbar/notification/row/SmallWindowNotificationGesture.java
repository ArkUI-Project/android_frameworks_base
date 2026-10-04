/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.notification.row;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.arkui.PopUpViewManager;
import com.android.internal.arkui.SmallWindowBounds;
import com.android.internal.arkui.SmallWindowSettings;

/** Owns only drags beginning on the bottom handle, leaving notification scrolling intact. */
final class SmallWindowNotificationGesture {
    private final ExpandableNotificationRow mRow;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float mDensity;
    private final int mTouchSlop;
    private final ContentObserver mObserver;
    private StatusBarNotification mNotification;
    private boolean mBubble;
    private boolean mEligible;
    private boolean mRegistered;
    private boolean mTracking;
    private boolean mOwnsTouchSequence;
    private boolean mCapturing;
    private boolean mHapticsEnabled;
    private boolean mThresholdReached;
    private float mDownX;
    private float mDownY;
    private float mProgress;
    private float mOriginalAlpha;
    private Rect mLaunchBounds;
    private ValueAnimator mAnimator;
    private Bitmap mSnapshot;
    private Preview mPreview;
    private View mRoot;
    private final RectF mStart = new RectF();
    private final RectF mEnd = new RectF();
    private final RectF mCurrent = new RectF();
    private final int[] mRootLocation = new int[2];

    SmallWindowNotificationGesture(ExpandableNotificationRow row) {
        mRow = row;
        mContext = row.getContext();
        mDensity = mContext.getResources().getDisplayMetrics().density;
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
                if (!mEligible) cancel();
            }
        };
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { attach(); }
            @Override public void onViewDetachedFromWindow(View view) { detach(); }
        });
        if (row.isAttachedToWindow()) attach();
    }

    void bind(StatusBarNotification notification, boolean bubble) {
        cancel();
        mLaunchBounds = null;
        mNotification = notification;
        mBubble = bubble;
        refresh();
    }

    private void attach() {
        if (mRegistered) return;
        for (String key : new String[]{Settings.System.ARKUI_SMALL_WINDOW_ENABLED,
                Settings.System.ARKUI_SMALL_WINDOW_NOTIFICATION_DRAG,
                Settings.System.ARKUI_SMALL_WINDOW_HAPTICS,
                Settings.System.ARKUI_SMALL_WINDOW_BLACKLIST,
                Settings.System.POP_UP_NOTIFICATION_BLACKLIST}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
        mRegistered = true;
        refresh();
    }

    private void detach() {
        cancel();
        mOwnsTouchSequence = false;
        if (mRegistered) mContext.getContentResolver().unregisterContentObserver(mObserver);
        mRegistered = false;
        mLaunchBounds = null;
    }

    private void refresh() {
        mHapticsEnabled = SmallWindowSettings.getBoolean(mContext,
                Settings.System.ARKUI_SMALL_WINDOW_HAPTICS, true, UserHandle.USER_CURRENT);
        final PendingIntent intent = mNotification == null ? null
                : mNotification.getNotification().contentIntent;
        mEligible = SmallWindowSettings.isNotificationDragEnabled(mContext)
                && intent != null && intent.isActivity() && !mBubble
                && mNotification.getUserId() == android.app.ActivityManager.getCurrentUser()
                && intent.getCreatorUserHandle().getIdentifier() == mNotification.getUserId()
                && mContext.getDisplayId() == android.view.Display.DEFAULT_DISPLAY
                && SmallWindowSettings.isPackageAllowed(mContext, mNotification.getPackageName())
                && !PopUpViewManager.inSystemNotificationBlacklist(mNotification.getPackageName())
                && !SmallWindowSettings.containsPackage(Settings.System.getStringForUser(
                        mContext.getContentResolver(), Settings.System.POP_UP_NOTIFICATION_BLACKLIST,
                        mNotification.getUserId()), mNotification.getPackageName());
        mRow.invalidate();
    }

    boolean isAvailable() {
        return mEligible && mRow.canShowSmallWindowHandle()
                && !mContext.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    void drawHandle(Canvas canvas) {
        if (mCapturing || !mEligible || !mRow.canShowSmallWindowHandle()) return;
        final float y = mRow.getActualHeight() - 8 * mDensity;
        mPaint.setColor(0xE6FFFFFF);
        canvas.drawRoundRect(mRow.getWidth() / 2f - 16 * mDensity, y,
                mRow.getWidth() / 2f + 16 * mDensity, y + 3 * mDensity,
                1.5f * mDensity, 1.5f * mDensity, mPaint);
    }

    boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mOwnsTouchSequence = false;
                if (mAnimator != null
                        || Math.abs(event.getX() - mRow.getWidth() / 2f) > 48 * mDensity
                        || event.getY() < mRow.getActualHeight() - 24 * mDensity
                        || event.getY() > mRow.getActualHeight() - mRow.getClipBottomAmount()) {
                    return false;
                }
                // Recheck the user and policy at the boundary, not on every animation frame.
                refresh();
                if (!isAvailable()) return false;
                mTracking = true;
                mOwnsTouchSequence = true;
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mRow.cancelLongPress();
                disallowParentIntercept(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mTracking) return mOwnsTouchSequence;
                if (event.getPointerCount() != 1 || !mRow.canShowSmallWindowHandle()) {
                    cancel();
                    return true;
                }
                final float dx = event.getRawX() - mDownX;
                final float dy = event.getRawY() - mDownY;
                if (mPreview == null) {
                    if (dy < -mTouchSlop || Math.abs(dx) > Math.max(mTouchSlop, dy)) {
                        // Give scrolling/swiping back to the parent, but swallow a stray UP.
                        cancel();
                        return true;
                    }
                    if (dy <= mTouchSlop || Math.abs(dx) > dy) return true;
                    createPreview();
                }
                mProgress = Math.max(0f, Math.min(1f, (dy - mTouchSlop) / (180 * mDensity)));
                if (mProgress >= .65f && !mThresholdReached && mHapticsEnabled) {
                    mRow.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END);
                }
                mThresholdReached = mProgress >= .65f;
                updatePreview();
                return true;
            case MotionEvent.ACTION_UP:
                if (!mOwnsTouchSequence) return false;
                mOwnsTouchSequence = false;
                if (!mTracking) return true;
                mTracking = false;
                mRow.setLastActionUpTime(event.getEventTime());
                disallowParentIntercept(false);
                refresh();
                if (mPreview == null) {
                    // A tap on the handle keeps the notification's normal click behavior.
                    if (Math.abs(event.getRawX() - mDownX) <= mTouchSlop
                            && Math.abs(event.getRawY() - mDownY) <= mTouchSlop
                            && isAvailable() && mRow.isSmallWindowHandleTapValid()) {
                        mRow.performClick();
                    }
                    cancel();
                    return true;
                }
                settle(mProgress >= .65f && isAvailable() && mRow.isSmallWindowDragValid());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!mOwnsTouchSequence) return false;
                cancel();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!mOwnsTouchSequence) return false;
                mOwnsTouchSequence = false;
                cancel();
                return true;
            default:
                return mOwnsTouchSequence;
        }
    }

    private void createPreview() {
        mRoot = mRow.getRootView();
        mRoot.getLocationOnScreen(mRootLocation);
        final int[] location = new int[2];
        mRow.getLocationOnScreen(location);
        mStart.set(location[0] - mRootLocation[0], location[1] - mRootLocation[1],
                location[0] - mRootLocation[0] + mRow.getWidth(),
                location[1] - mRootLocation[1] + mRow.getActualHeight());
        mEnd.set(SmallWindowBounds.getDestination(mContext, false, false));
        mEnd.offset(-mRootLocation[0], -mRootLocation[1]);
        mSnapshot = Bitmap.createBitmap(Math.max(1, mRow.getWidth()),
                Math.max(1, mRow.getActualHeight()), Bitmap.Config.ARGB_8888);
        mCapturing = true;
        try {
            mRow.draw(new Canvas(mSnapshot));
        } finally {
            mCapturing = false;
        }
        mOriginalAlpha = mRow.getAlpha();
        mRow.setAlpha(0f);
        mPreview = new Preview();
        mPreview.setBounds(0, 0, mRoot.getWidth(), mRoot.getHeight());
        mRoot.getOverlay().add(mPreview);
    }

    private void updatePreview() {
        if (mPreview == null) return;
        final float p = mProgress;
        mCurrent.set(mStart.left + (mEnd.left - mStart.left) * p,
                mStart.top + (mEnd.top - mStart.top) * p,
                mStart.right + (mEnd.right - mStart.right) * p,
                mStart.bottom + (mEnd.bottom - mStart.bottom) * p);
        mPreview.invalidateSelf();
    }

    private void settle(boolean launch) {
        if (mPreview == null) { cancel(); return; }
        mAnimator = ValueAnimator.ofFloat(mProgress, launch ? 1f : 0f);
        mAnimator.setDuration(180);
        mAnimator.addUpdateListener(value -> {
            mProgress = (float) value.getAnimatedValue();
            updatePreview();
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;
            @Override public void onAnimationCancel(Animator animation) { mCancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                mAnimator = null;
                if (!mCancelled && launch) refresh();
                if (!mCancelled && launch && isAvailable()) {
                    mLaunchBounds = new Rect();
                    mCurrent.roundOut(mLaunchBounds);
                    mLaunchBounds.offset(mRootLocation[0], mRootLocation[1]);
                    mRow.performClick();
                    // A click can be declined (for example while replying).
                    mLaunchBounds = null;
                }
                clearPreview();
            }
        });
        mAnimator.start();
    }

    boolean launchFromAccessibility() {
        refresh();
        if (!isAvailable()) return false;
        mLaunchBounds = SmallWindowBounds.getDestination(mContext, false, false);
        final boolean clicked = mRow.performClick();
        mLaunchBounds = null;
        return clicked;
    }

    Rect consumeLaunchBounds() {
        final Rect result = mLaunchBounds;
        mLaunchBounds = null;
        return result;
    }

    void cancel() {
        mTracking = false;
        disallowParentIntercept(false);
        if (mAnimator != null) mAnimator.cancel();
        clearPreview();
    }

    private void disallowParentIntercept(boolean disallow) {
        if (mRow.getParent() != null) {
            mRow.getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void clearPreview() {
        if (mPreview != null && mRoot != null) mRoot.getOverlay().remove(mPreview);
        if (mSnapshot != null) {
            mRow.setAlpha(mOriginalAlpha);
            // RenderThread may still reference the bitmap from the previous frame.
            mSnapshot = null;
        }
        mPreview = null;
        mRoot = null;
        mProgress = 0;
        mThresholdReached = false;
    }

    private final class Preview extends Drawable {
        private final android.graphics.Path mClip = new android.graphics.Path();
        private final RectF mImageBounds = new RectF();
        @Override public void draw(Canvas canvas) {
            if (mSnapshot == null) return;
            mClip.reset();
            mClip.addRoundRect(mCurrent, 20 * mDensity, 20 * mDensity,
                    android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClip);
            mPaint.setColor(0xFF252A2E);
            canvas.drawRect(mCurrent, mPaint);
            final float height = mSnapshot.getHeight() * mCurrent.width() / mSnapshot.getWidth();
            mImageBounds.set(mCurrent.left, mCurrent.top, mCurrent.right, mCurrent.top + height);
            canvas.drawBitmap(mSnapshot, null, mImageBounds, mPaint);
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
