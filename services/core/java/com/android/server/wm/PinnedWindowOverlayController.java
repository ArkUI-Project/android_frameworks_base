/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.ActivityThread;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.IWindow;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowInsets;

import com.android.internal.R;

class PinnedWindowOverlayController {

    private static final String TAG = "PinnedWindowOverlayController";
    static final String WIN_TITLE = "PinnedWindowOverlayView";

    private static final int MSG_ADD_OVERLAY_WINDOW = 0;
    private static final int MSG_UPDATE_OVERLAY_WINDOW = 1;
    private static final int MSG_HIDE_OVERLAY_WINDOW = 2;
    private static final int MSG_REMOVE_OVERLAY_WINDOW = 3;
    private static final int MSG_SHOW_DISMISS_VIEW = 4;
    private static final int MSG_HIDE_DISMISS_VIEW = 5;
    private static final int MSG_UPDATE_OVERLAY_POSITION = 6;

    private final Rect mBound;
    private final Rect mRemoveBound;
    private final float[] mVels;

    private final H mHandler;
    private final HandlerThread mHandlerThread;

    private GestureDetector mGestureDetector;

    private LayoutParams mParams;

    private int mDragSlop;
    private boolean mCheckForDragging;
    private boolean mDragging;
    private boolean mIsTouchInWindow;
    private int mTouchDownX;
    private int mTouchDownY;

    private VelocityTracker mVelocityTracker;
    private PinnedWindowOverlayView mView;
    private WindowManager mWindowManager;
    private WindowManagerService mWmService;
    private Context mContext;

    private Task mTask;
    private TaskWindowSurfaceInfo mTaskWindowSurfaceInfo;
    private PinnedWindowDismissView mDismissView;
    private boolean mIsInRemoveBound;
    private boolean mIsDockedToEdge = false;
    private volatile int mOverlayGeneration;

    private static class OverlayPosition {
        int generation;
        int left;
        int top;
        int taskWidth;
        int taskHeight;
        float scale;
    }

    private static class InstanceHolder {
        private static final PinnedWindowOverlayController INSTANCE = new PinnedWindowOverlayController();
    }

    private class H extends Handler {
        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_OVERLAY_WINDOW:
                    if (DEBUG_POP_UP) {
                        Slog.d(TAG, "updateWindowState: handle add");
                    }
                    addView();
                    break;
                case MSG_UPDATE_OVERLAY_WINDOW:
                    updateView();
                    break;
                case MSG_HIDE_OVERLAY_WINDOW:
                    if (DEBUG_POP_UP) {
                        Slog.d(TAG, "hide overlay window view");
                    }
                    if (mView != null) {
                        mView.setVisibility(View.GONE);
                    }
                    break;
                case MSG_REMOVE_OVERLAY_WINDOW:
                    if (DEBUG_POP_UP) {
                        Slog.d(TAG, "remove overlay window view");
                    }
                    removeOverlayViewInternal();
                    break;
                case MSG_SHOW_DISMISS_VIEW:
                    showDismissViewInternal();
                    break;
                case MSG_HIDE_DISMISS_VIEW:
                    hideDismissViewInternal(msg.arg1 == 1);
                    break;
                case MSG_UPDATE_OVERLAY_POSITION:
                    updateOverlayPositionInternal((OverlayPosition) msg.obj);
                    break;
            }
        }
    }

    static PinnedWindowOverlayController getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private PinnedWindowOverlayController() {
        mDragging = false;
        mIsTouchInWindow = false;
        mIsInRemoveBound = false;
        mVels = new float[2];
        mBound = new Rect();
        mRemoveBound = new Rect();
        mHandlerThread = new HandlerThread("PinnedWindowOverlayHandler");
        mHandlerThread.start();
        mHandler = new H(mHandlerThread.getLooper());
        mContext = ActivityThread.currentActivityThread().getSystemUiContext();
    }

    void init(Context context, Looper looper, WindowManagerService wms) {
        if (context != null) {
            mContext = context;
        }
        mWmService = wms;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mDragSlop = mContext.getResources().getDimensionPixelSize(R.dimen.config_viewConfigurationTouchSlop);
    }

    void systemReady() {
        mGestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent event) {
                mCheckForDragging = false;
                if (mView != null && !mDragging) {
                    mView.showMenu();
                }
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "onSingleTapConfirmed: event=" + event);
                }
                enterMiniWindowingMode();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "onDoubleTap: event=" + event);
                }
                exitPinnedWindowingMode();
                return true;
            }
        }, mHandler);
    }

    Task getTask() {
        return mTask;
    }

    void setTask(Task task) {
        // The mini preview must use the same surface owner as the restored window's handle.
        task = task != null ? task.getRootTask() : null;
        // Delayed animation callbacks must not resurrect an obsolete overlay.
        if (task != null && (mWmService == null
                || !PopUpSettingsConfig.getInstance().isEnabled()
                || !task.isAttached() || !task.showToCurrentUser()
                || !task.getWindowConfiguration().isPinnedExtWindowMode()
                || mWmService.isKeyguardLocked())) {
            task = null;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setTask: " + (task != null ? task : "null"));
        }
        if (mTask == task) {
            return;
        }
        mOverlayGeneration++;
        mHandler.removeMessages(MSG_UPDATE_OVERLAY_POSITION);
        if (task == null && mTask != null) {
            mHandler.removeMessages(MSG_ADD_OVERLAY_WINDOW);
            mHandler.removeMessages(MSG_UPDATE_OVERLAY_WINDOW);
            mHandler.removeMessages(MSG_HIDE_OVERLAY_WINDOW);
            mHandler.sendEmptyMessage(MSG_REMOVE_OVERLAY_WINDOW);
            hideDismissView(false);
        } else if (task != null) {
            mHandler.removeMessages(MSG_REMOVE_OVERLAY_WINDOW);
            mHandler.removeMessages(MSG_HIDE_OVERLAY_WINDOW);
        }
        mTask = task;
        mTaskWindowSurfaceInfo = getTaskWindowSurfaceInfo();
        mBound.setEmpty();
        mIsDockedToEdge = false;
        updateWindowState(mTask != null);
    }

    void show(Task task) {
        setTask(task);
    }

    void hide() {
        setTask(null);
    }

    boolean onTouchEvent(MotionEvent event) {
        if (mTask == null || !PopUpSettingsConfig.getInstance().isEnabled()) {
            return false;
        }
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        final boolean fromMouse = event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE;
        final int actionMasked = event.getActionMasked();

        if (mIsDockedToEdge && mTaskWindowSurfaceInfo != null) {
            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_UP:
                    mTaskWindowSurfaceInfo.restoreFromDocked();
                    return true;
                default:
                    return true;
            }
        }

        mGestureDetector.onTouchEvent(event);

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                if (!fromMouse || event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) {
                    if (DEBUG_POP_UP) {
                        Slog.d(TAG, "onTouchEvent: Down: " + event);
                    }
                    mCheckForDragging = true;
                    mTouchDownX = x;
                    mTouchDownY = y;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    if (actionMasked == MotionEvent.ACTION_CANCEL) {
                        PopUpWindowController.getInstance().cancelMovingTask(mTask);
                    }
                    if (actionMasked == MotionEvent.ACTION_UP && mWmService != null) {
                        final IWindow window = getIWindow();
                        if (window != null) {
                            if (mIsInRemoveBound) {
                                hideDismissView(true);
                                moveActivityTaskToBack();
                            } else {
                                finishTaskPositioning(window);
                            }
                        }
                    }
                    mDragging = false;
                    hideDismissView(false);
                }
                mCheckForDragging = false;
                mIsInRemoveBound = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!mDragging && mCheckForDragging && ((fromMouse || passedSlop(x, y)))) {
                    mCheckForDragging = false;
                    mDragging = true;
                    if (mWmService != null) {
                        final IWindow window = getIWindow();
                        if (window != null) {
                            startMovingTask(window, event.getRawX(), event.getRawY());
                        }
                    }
                    if (mTaskWindowSurfaceInfo != null) {
                        mTaskWindowSurfaceInfo.cancelPopUpViewAnimation();
                    }
                    showDismissView();
                }
                if (mDragging) {
                    updateMovingTask(event.getRawX(), event.getRawY());
                    checkRemoveBound(event.getRawX(), event.getRawY());
                }
                if (mTask != null && mTask.getWindowConfiguration().isPinnedExtWindowMode()
                        && mTask.mSurfaceAnimator.hasLeash()) {
                    mTask.cancelAnimation();
                }
                return true;

            case MotionEvent.ACTION_OUTSIDE:
                if (mView != null) {
                    mHandler.post(() -> mView.hideMenu());
                }
                return true;
        }
        return true;
    }

    private void startMovingTask(IWindow window, float rawX, float rawY) {
        try {
            PopUpWindowController.getInstance().startMovingTask(mTask, rawX, rawY);
        } catch (Exception e) {
            Slog.e(TAG, "startMovingTask failed", e);
        }
    }

    private void finishTaskPositioning(IWindow window) {
        try {
            PopUpWindowController.getInstance().finishMovingTask(mTask);
        } catch (Exception e) {
            Slog.e(TAG, "finishTaskPositioning failed", e);
        }
    }

    private void updateMovingTask(float rawX, float rawY) {
        try {
            PopUpWindowController.getInstance().updateMovingTask(mTask, rawX, rawY);
        } catch (Exception e) {
            Slog.e(TAG, "updateMovingTask failed", e);
        }
    }

    void updateWindowState(boolean show) {
        if (show && mView == null) {
            mHandler.sendEmptyMessage(MSG_ADD_OVERLAY_WINDOW);
        } else if (!show) {
            mHandler.sendEmptyMessage(MSG_HIDE_OVERLAY_WINDOW);
        } else {
            mHandler.sendEmptyMessage(MSG_UPDATE_OVERLAY_WINDOW);
        }
    }

    private TaskWindowSurfaceInfo getTaskWindowSurfaceInfo() {
        if (mTask != null) {
            return mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        }
        return null;
    }

    private void addView() {
        if (mContext == null) {
            return;
        }
        if (mTaskWindowSurfaceInfo != null && mView == null) {
            final PinnedWindowOverlayView view = (PinnedWindowOverlayView) LayoutInflater.from(mContext)
                    .inflate(R.layout.pinned_window_overlay, null);
            view.setController(this);
            view.setTask(mTask);
            view.setDocked(mIsDockedToEdge, mTaskWindowSurfaceInfo.isDockedLeft());
            final Rect taskBound = mTaskWindowSurfaceInfo.getTaskWindowSurfaceBounds();
            setUpWindow(view, taskBound);
            mView = view;
            mBound.set(taskBound);
        }
        if (mView != null) {
            mView.setVisibility(View.VISIBLE);
        }
    }

    private void setUpWindow(PinnedWindowOverlayView view, Rect taskBound) {
        final LayoutParams params = new LayoutParams();
        params.type = LayoutParams.TYPE_APPLICATION_OVERLAY;
        params.format = PixelFormat.RGBA_8888;
        params.flags = LayoutParams.FLAG_NOT_FOCUSABLE |
                        LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        params.privateFlags = LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS |
                               LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        params.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.setFitInsetsTypes(0);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.setTitle(WIN_TITLE);

        if (mWindowManager != null) {
            params.x = taskBound.left;
            params.y = taskBound.top;
            params.width = Math.max(1, taskBound.width());
            params.height = Math.max(1, taskBound.height());
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "addView: bound = " + taskBound);
            }
            mWindowManager.addView(view, params);
            mParams = params;
            final WindowInsetsController insetsController = view.getWindowInsetsController();
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars());
                insetsController.hide(WindowInsets.Type.navigationBars());
            }
        }
    }

    private void updateView() {
        if (mView != null) {
            mView.setTask(mTask);
            mView.setDocked(mIsDockedToEdge, mTaskWindowSurfaceInfo != null
                    && mTaskWindowSurfaceInfo.isDockedLeft());
        }
        if (mTaskWindowSurfaceInfo == null || mParams == null) {
            return;
        }
        final Rect taskBound = mTaskWindowSurfaceInfo.getTaskWindowSurfaceBounds();
        if (mView != null && mView.isAttachedToWindow() && !mBound.equals(taskBound)
                && mWindowManager != null) {
            mBound.set(taskBound);
            mParams.x = taskBound.left;
            mParams.y = taskBound.top;
            mParams.width = Math.max(1, taskBound.width());
            mParams.height = Math.max(1, taskBound.height());
            try {
                mWindowManager.updateViewLayout(mView, mParams);
            } catch (Exception e) {
                if (DEBUG_POP_UP) {
                    Slog.w(TAG, "updateView failed", e);
                }
            }
        }
        if (mView != null) {
            mView.setVisibility(View.VISIBLE);
        }
    }

    private void removeOverlayViewInternal() {
        final View view = mView;
        mView = null;
        mParams = null;
        mHandler.removeMessages(MSG_UPDATE_OVERLAY_POSITION);
        if (view != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(view);
            } catch (Exception e) {
                if (DEBUG_POP_UP) {
                    Slog.w(TAG, "removeOverlayViewInternal failed", e);
                }
            }
        }
        mBound.setEmpty();
    }

    void updateOverlayPosition(int left, int top, int taskWidth, int taskHeight, float scale) {
        if (mTask == null) {
            return;
        }
        final OverlayPosition position = new OverlayPosition();
        position.generation = mOverlayGeneration;
        position.left = left;
        position.top = top;
        position.taskWidth = taskWidth;
        position.taskHeight = taskHeight;
        position.scale = scale;
        mHandler.removeMessages(MSG_UPDATE_OVERLAY_POSITION);
        mHandler.obtainMessage(MSG_UPDATE_OVERLAY_POSITION, position).sendToTarget();
    }

    private void updateOverlayPositionInternal(OverlayPosition position) {
        if (position == null || position.generation != mOverlayGeneration
                || mView == null || mParams == null || mWindowManager == null
                || !mView.isAttachedToWindow()) {
            return;
        }
        final int newWidth = Math.max(1, (int) (position.taskWidth * position.scale));
        final int newHeight = Math.max(1, (int) (position.taskHeight * position.scale));
        if (mParams.x == position.left && mParams.y == position.top
                && mParams.width == newWidth && mParams.height == newHeight) {
            return;
        }
        mParams.x = position.left;
        mParams.y = position.top;
        mParams.width = newWidth;
        mParams.height = newHeight;
        try {
            mWindowManager.updateViewLayout(mView, mParams);
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "updateOverlayPosition failed", e);
            }
        }
    }

    private IWindow getIWindow() {
        if (mWmService == null) {
            return null;
        }
        synchronized (mWmService.mAtmService.mGlobalLock) {
            if (mTask != null && mTask.getTopVisibleAppMainWindow() != null) {
                return mTask.getTopVisibleAppMainWindow().getIWindow();
            }
            return null;
        }
    }

    private void exitPinnedWindowingMode() {
        if (mTask != null) {
            PopUpWindowController.getInstance().exitPinnedWindowingMode(
                    mTask.getTopVisibleAppMainWindow());
        }
    }

    private void enterMiniWindowingMode() {
        if (mTask != null) {
            PopUpWindowController.getInstance().enterMiniWindowingModeFromPinned(
                    mTask.getTopVisibleAppMainWindow());
        }
    }

    float[] computeCurrentVelocity() {
        synchronized (this) {
            if (mVelocityTracker != null && mIsTouchInWindow) {
                mVelocityTracker.computeCurrentVelocity(1000,
                        ViewConfiguration.get(mWmService.mContext).getScaledMaximumFlingVelocity());
                mVels[0] = mVelocityTracker.getXVelocity();
                mVels[1] = mVelocityTracker.getYVelocity();
            }
            mIsTouchInWindow = false;
        }
        return mVels;
    }

    private boolean passedSlop(int x, int y) {
        return Math.abs(x - mTouchDownX) > mDragSlop || Math.abs(y - mTouchDownY) > mDragSlop;
    }

    boolean isOverlayViewShowing() {
        return mView != null && mView.getVisibility() == View.VISIBLE;
    }

    void enterMiniWindowMode() {
        enterMiniWindowingMode();
    }

    void exitPinnedWindowMode() {
        exitPinnedWindowingMode();
    }

    void setRemoveBound(Rect rect) {
        mRemoveBound.set(rect);
    }

    void setDockedState(boolean docked) {
        mIsDockedToEdge = docked;
        mHandler.post(() -> {
            if (mView != null) {
                mView.setDocked(docked, mTaskWindowSurfaceInfo != null
                        && mTaskWindowSurfaceInfo.isDockedLeft());
            }
        });
    }

    void showDismissView() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_SHOW_DISMISS_VIEW);
        }
    }

    void hideDismissView(boolean isDismiss) {
        if (mHandler != null) {
            Message msg = mHandler.obtainMessage(MSG_HIDE_DISMISS_VIEW);
            msg.arg1 = isDismiss ? 1 : 0;
            mHandler.sendMessage(msg);
        }
    }

    private void showDismissViewInternal() {
        if (mDismissView == null && mContext != null) {
            mDismissView = (PinnedWindowDismissView) LayoutInflater.from(mContext)
                    .inflate(R.layout.pinned_window_dismiss, null);
        }
        if (mDismissView != null) {
            mDismissView.showOnScreen(mHandlerThread.getLooper());
        }
    }

    private void hideDismissViewInternal(boolean isDismiss) {
        if (mDismissView != null) {
            mDismissView.hideOnScreen(isDismiss);
            if (isDismiss) {
                mDismissView.reset();
                mDismissView = null;
            }
        }
    }

    private void checkRemoveBound(float x, float y) {
        if (mDismissView != null) {
            final boolean inThreshold = mDismissView.isInRemoveThreshold(y);
            mDismissView.setButtonSelected(inThreshold);
            mIsInRemoveBound = inThreshold;
        }
    }

    private void moveActivityTaskToBack() {
        if (mTask != null) {
            PopUpWindowController.getInstance().moveActivityTaskToBack(mTask,
                    PopUpWindowController.MOVE_TO_BACK_FROM_LEAVE_BUTTON);
        }
    }
}
