/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * Copyright (C) 2026 The RisingOS Revived Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED_WINDOW_EXT;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_MINI_WINDOW_DIMMER;
import static android.view.WindowManager.LayoutParams.TYPE_PINNED_WINDOW_DISMISS_HINT;
import static android.window.TransitionInfo.FLAG_EXIT_POP_UP_VIEW_BY_DRAG;
import static android.window.TransitionInfo.FLAG_EXIT_POP_UP_VIEW_DISPLAY_ROTATION;
import static android.window.TransitionInfo.FLAG_LAUNCH_POP_UP_VIEW_FROM_RECENTS;
import static android.window.TransitionInfo.FLAG_SCHEDULE_POP_UP_VIEW;

import static com.android.server.wm.Transition.ChangeInfo.FLAG_CHANGE_SHOULD_SKIP_TRANSITIONS;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.IWindow;
import android.view.InsetsSource;
import android.annotation.IntDef;
import java.lang.annotation.Retention;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets.Type;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.window.TransitionInfo;
import android.window.TransitionInfo.Change;

import com.android.server.ServiceThread;
import com.android.server.wm.ActivityStarter.Request;
import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.Transition.ChangeInfo;

import com.google.android.collect.Sets;
import java.lang.annotation.RetentionPolicy;

import java.util.ArrayList;

import com.android.internal.arkui.PopUpViewManager;


public class PopUpWindowController {

    private static final String TAG = "PopUpWindowController";

    public static final int REORDER_KEEP_IN_PLACE = 0;
    public static final int REORDER_MOVE_TO_TOP = 1;
    public static final int REORDER_MOVE_TO_ORIGINAL_POSITION = 2;

    @IntDef(value = { REORDER_KEEP_IN_PLACE, REORDER_MOVE_TO_TOP, REORDER_MOVE_TO_ORIGINAL_POSITION })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReorderMode {}

    static final int MOVE_TO_BACK_TOUCH_OUTSIDE = 0;
    static final int MOVE_TO_BACK_FROM_LEAVE_BUTTON = 1;
    static final int MOVE_TO_BACK_NEW_MINI = 2;
    static final int MOVE_TO_BACK_NEW_PIN = 3;
    static final int MOVE_TO_BACK_NON_USER = 4;

    private static final long EXIT_POP_UP_DELAY = 200L;

    private static final int ID_DISPLAY_CUTOUT_LEFT = InsetsSource.createId(null, 0, Type.displayCutout());
    private static final int ID_DISPLAY_CUTOUT_TOP = InsetsSource.createId(null, 1, Type.displayCutout());
    private static final int ID_DISPLAY_CUTOUT_RIGHT = InsetsSource.createId(null, 2, Type.displayCutout());
    private static final int ID_DISPLAY_CUTOUT_BOTTOM = InsetsSource.createId(null, 3, Type.displayCutout());

    private final Handler mHandler;
    private final ServiceThread mServiceThread;

    private ActivityTaskManagerService mAtmService;
    private Context mContext;
    private Vibrator mVibrator;
    private WindowManagerService mService;

    private SurfaceControl.Transaction mTransaction;

    private boolean mSkipNextTransitionFreeze;
    private boolean mTryExitWindowingMode;
    private boolean mTryExitWindowingModeByDrag;
    private boolean mLaunchPopUpViewFromRecents;
    private boolean mNextRecentIsPin;

    // Guarded by the WM global lock; one UID can own more than one small-window task.
    private final android.util.SparseIntArray mMutedTasks = new android.util.SparseIntArray();

    boolean isTaskMuted(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            return mMutedTasks.indexOfKey(task.getRootTask().mTaskId) >= 0;
        }
    }

    void toggleTaskMute(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            final Task root = task.getRootTask();
            if (!root.isAttached() || !root.getWindowConfiguration().isPopUpWindowMode()) {
                return;
            }
            if (mMutedTasks.indexOfKey(root.mTaskId) >= 0) {
                releaseTaskMute(root);
            } else {
                final ActivityRecord activity = root.topRunningActivity();
                if (activity == null) return;
                final int uid = activity.getUid();
                mMutedTasks.put(root.mTaskId, uid);
                setUidMuted(uid, true);
            }
        }
    }

    void releaseTaskMute(Task task) {
        if (mAtmService == null) return;
        synchronized (mAtmService.mGlobalLock) {
            final int index = mMutedTasks.indexOfKey(task.mTaskId);
            if (index < 0) return;
            final int uid = mMutedTasks.valueAt(index);
            mMutedTasks.removeAt(index);
            if (mMutedTasks.indexOfValue(uid) < 0) setUidMuted(uid, false);
        }
    }

    private void setUidMuted(int uid, boolean muted) {
        final android.media.AudioManagerInternal audio = com.android.server.LocalServices
                .getService(android.media.AudioManagerInternal.class);
        if (audio != null) audio.setSmallWindowUidMuted(uid, muted);
    }

    private WindowState mDimWinState = null;

    private PointerEventListener mMiniWindowPointerListener;

    private static class InstanceHolder {
        private static final PopUpWindowController INSTANCE = new PopUpWindowController();
    }

    public static PopUpWindowController getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private PopUpWindowController() {
        mServiceThread = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        mServiceThread.start();
        mHandler = new Handler(mServiceThread.getLooper());
    }

    public void init(Context context, WindowManagerService wms) {
        mContext = context;
        mService = wms;
        mAtmService = mService.mAtmService;
        mTransaction = mService.mTransactionFactory.get();
        mVibrator = mContext.getSystemService(Vibrator.class);
    }

    void systemReady() {
        PopUpSettingsConfig.getInstance().init(mContext, mHandler);
        PinnedWindowOverlayController.getInstance().init(mContext, mHandler.getLooper(), mService);
        PinnedWindowOverlayController.getInstance().systemReady();

        setupMiniWindowPointerListener();
    }

    void onWindowAdd(ConfigurationContainer newParent, WindowState win) {
        if (newParent == null) {
            return;
        }
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent.getDisplayId() == DEFAULT_DISPLAY &&
                win.mAttrs.type == TYPE_MINI_WINDOW_DIMMER) {
            mDimWinState = win;
            displayContent.assignWindowLayers(false);
        }
    }

    void onWindowRemove(WindowState win) {
        if (win.mAttrs.type == TYPE_MINI_WINDOW_DIMMER) {
            mDimWinState = null;
        }
    }

    boolean onWindowTokenAssignLayer(WindowToken token, SurfaceControl.Transaction t, int layer) {
        if (token.windowType != TYPE_MINI_WINDOW_DIMMER &&
                token.windowType != TYPE_PINNED_WINDOW_DISMISS_HINT) {
            return false;
        }
        if (token.mSurfaceControl == null) {
            return true;
        }
        if (mAtmService.isSleepingOrShuttingDownLocked()) {
            t.hide(token.mSurfaceControl);
        } else {
            t.show(token.mSurfaceControl);
            final DisplayContent displayContent = token.getDisplayContent();
            if (displayContent != null) {
                final Task targetTask = token.windowType == TYPE_PINNED_WINDOW_DISMISS_HINT
                        ? PinnedWindowOverlayController.getInstance().getTask()
                        : DimmerWindow.getInstance().getTask();
                if (targetTask != null && targetTask.mSurfaceControl != null) {
                    if ((token.windowType == TYPE_PINNED_WINDOW_DISMISS_HINT ||
                            !targetTask.mWindowContainerExt.isFinishTopTask()) &&
                            targetTask.isAlwaysOnTop()) {
                        t.setRelativeLayer(token.mSurfaceControl, targetTask.mSurfaceControl, -1);
                    } else {
                        mTransaction.setLayer(token.mSurfaceControl, 1);
                        mTransaction.apply();
                    }
                }
            }
        }
        return true;
    }


    void onRotationChanged(Task task) {
        if (task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            task.mWindowContainerExt.getTaskWindowSurfaceInfo().onRotationChanged();
        }
    }

    void onPrepareSurfaces(Task task, SurfaceControl.Transaction t) {
        if (task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            task.mWindowContainerExt.getTaskWindowSurfaceInfo().onPrepareSurfaces(t);
        }
    }

    void onUserSwitched() {
        synchronized (mAtmService.mGlobalLock) {
            for (int i = 0; i < mMutedTasks.size(); i++) setUidMuted(mMutedTasks.valueAt(i), false);
            mMutedTasks.clear();
        }
        PopUpSettingsConfig.getInstance().updateAll();
        // Only proceed if service is properly initialized
        if (mService != null && mService.mSystemReady) {
            findAndExitAllPopUp();
        }
    }

    int getChangeFlags(ChangeInfo info, int flags) {
        if (shouldStartChangeTransition(info.mWindowingMode, info.mContainer.getWindowingMode())) {
            flags |= FLAG_SCHEDULE_POP_UP_VIEW;
            if (mLaunchPopUpViewFromRecents) {
                flags |= FLAG_LAUNCH_POP_UP_VIEW_FROM_RECENTS;
            }
            if (mTryExitWindowingModeByDrag) {
                flags |= FLAG_EXIT_POP_UP_VIEW_BY_DRAG;
            }
        }
        if (WindowConfiguration.isPopUpWindowMode(info.mContainer.getWindowingMode())
                && (info.mIsKeyguardGoingAway || info.mIsMoveTaskToBack)) {
            info.mFlags |= FLAG_CHANGE_SHOULD_SKIP_TRANSITIONS;
        }
        return flags;
    }

    DisplayContent getDefaultDisplayContent() {
        if (mService == null) {
            Slog.w(TAG, "getDefaultDisplayContent: mService is null");
            return null;
        }
        return mService.getDefaultDisplayContentLocked();
    }

    WindowState getDimWinState() {
        return mDimWinState;
    }

    void getPopUpViewTouchOffset(Session session, IWindow window, float[] offsets) {
        synchronized (mService.mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final WindowState win = mService.windowForClient(session, window);
                if (offsets != null && offsets.length == 4) {
                    offsets[0] = 0.0f;
                    offsets[1] = 0.0f;
                    offsets[2] = 1.0f;
                    offsets[3] = 1.0f;
                    if (win != null && win.getWindowConfiguration().isPopUpWindowMode() &&
                            win.mActivityRecord != null) {
                        final Task rootTask = win.mActivityRecord.getRootTask(task -> task != null);
                        if (rootTask != null && rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
                            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                            final Rect rect = info.getTaskWindowSurfaceBounds();
                            offsets[0] = rect.left;
                            offsets[1] = rect.top;
                            final float scale = info.getWindowSurfaceRealScale();
                            offsets[2] = scale;
                            offsets[3] = scale;
                        }
                    }
                }
            } catch (IllegalStateException | NullPointerException e) {
                Slog.e(TAG, "Failed to get popup-view touch offset: ", e);
                return;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void resetBounds(Task task, int currentWindowingMode, int preferredWindowingMode) {
        if (WindowConfiguration.isPopUpWindowMode(currentWindowingMode) &&
                !WindowConfiguration.isPopUpWindowMode(preferredWindowingMode)) {
            task.setBounds(null);
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "setBounds to null for windowing mode change: currentMode="
                        + currentWindowingMode + "->" + preferredWindowingMode);
            }
        }
    }

    void removeChild(Task task) {
        releaseTaskMute(task);
        if (tryExitPopUpView(task, true, true, true)) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "removeChild: exit PopUpView window");
            }
        }
    }

    void anyTaskForId(Task targetRootTask, Task task) {
        return;
    }

    void ensureActivityConfiguration(ActivityRecord r) {
        return;
    }

    boolean shouldSkipAppFocusChanged(Task newTask) {
        if (newTask != null && !newTask.getWindowConfiguration().isPopUpWindowMode()
                && TopActivityRecorder.getInstance().hasMiniWindow()) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "skip fullscreen app focus change due to mini-window showing");
            }
            return true;
        }
        if (newTask != null && newTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "pinned window should not be focusable");
            }
            return true;
        }
        if (newTask != null && newTask.mWindowContainerExt.getFreezerSkipAnim()) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "task is under NoWindowModeAnim, should not be focusable");
            }
            return true;
        }
        return false;
    }

    boolean shouldSkipRemoteAnimation(boolean isChanging) {
        return isChanging && mTryExitWindowingMode;
    }

    void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "onAppFocusChanged: newTask=" + newTask);
        }
        if (newFocus != null && newTask != null &&
                newTask.getWindowConfiguration().isPopUpWindowMode()) {
            final Task rootTask = newTask.getRootTask();
            if (rootTask != null) {
                newFocus.mWindowContainerExt.setOrientation(rootTask);
            }
        }
        if (newTask != null && newTask.getWindowConfiguration().isMiniExtWindowMode()) {
            newTask.mWindowContainerExt.setFinishTopTask(false);
        }
    }

    void findAndExitAllPopUp() {
        if (mService == null || !mService.mSystemReady) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            final ArrayList<Task> tasks = new ArrayList<>();
            mService.mRoot.forAllRootTasks((Task task) -> {
                if (task.getWindowConfiguration().isPopUpWindowMode()) {
                    tasks.add(task);
                }
            });
            for (Task task : tasks) {
                tryExitPopUpView(task, true, true, true);
            }
            TopActivityRecorder.getInstance().clearMiniWindow();
            TopActivityRecorder.getInstance().clearPinnedWindow();
        }
    }

    // ArkUI: immediately reconcile open windows when the user changes policy.
    void onSettingsChanged() {
        if (mService == null || !mService.mSystemReady) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            if (!PopUpSettingsConfig.getInstance().isEnabled()) {
                findAndExitAllPopUp();
                return;
            }
            final ArrayList<Task> tasks = new ArrayList<>();
            mService.mRoot.forAllRootTasks((Task task) -> {
                if (task.getWindowConfiguration().isPopUpWindowMode()) {
                    tasks.add(task);
                }
            });
            for (Task task : tasks) {
                final ActivityRecord top = task.getTopNonFinishingActivity();
                if (top != null && !PopUpSettingsConfig.getInstance()
                        .isPackageAllowed(top.packageName)) {
                    tryExitPopUpView(task, true, true, true);
                    continue;
                }
                final TaskWindowSurfaceInfo info = task.mWindowContainerExt
                        .getTaskWindowSurfaceInfo();
                if (info != null) {
                    info.updateDockedForegroundState();
                }
            }
        }
    }

    private void moveActivityTaskToBackInner(Task task, Task fullTask) {
        ActivityRecord fullTaskActivity = fullTask != null ? fullTask.getResumedActivity() : null;
        if (fullTaskActivity == null && fullTask != null) {
            fullTaskActivity = fullTask.getTopActivity(true, true);
        }
        ActivityRecord taskActivity = task.getResumedActivity();
        if (taskActivity == null) {
            taskActivity = task.getTopActivity(true, true);
        }
        task.startPausing(true, false, fullTaskActivity, "PopUpWindowController.moveActivityTaskToBackInner");
        if (taskActivity != null && task.getDisplayContent() != null) {
            if (taskActivity.getTask() != null &&
                    (taskActivity.getTask() == task || taskActivity.getTask().getParent() == task)) {
                task.moveTaskToBack(taskActivity.getTask());
            }
            final ActivityRecord resumedActivity = task.mRootWindowContainer.getTopResumedActivity();
            if (resumedActivity != null && !resumedActivity.isSleeping()) {
                mAtmService.setLastResumedActivityUncheckLocked(
                        resumedActivity, "PopUpWindowController.moveActivityTaskToBackInner");
            }
        }
    }

    void moveActivityTaskToBack(Task task, int reason) {
        synchronized (mAtmService.mGlobalLock) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "moveActivityTaskToBack, task=" + (task != null ? task : "null")
                        + ", reason=" + reasonToString(reason));
            }
            if (task != null && task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
                if (reason == MOVE_TO_BACK_TOUCH_OUTSIDE) {
                    TopActivityRecorder.getInstance().clearMiniWindow();
                } else if (reason == MOVE_TO_BACK_FROM_LEAVE_BUTTON) {
                    TopActivityRecorder.getInstance().clearPinnedWindow();
                    DimmerWindow.getInstance().setTask(null);
                }
                final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
                info.playExitAnimation(reason == MOVE_TO_BACK_FROM_LEAVE_BUTTON,
                        info.getWindowSurfaceRealScale(),
                        () -> {
                            synchronized (mAtmService.mGlobalLock) {
                                if (task.mDisplayContent == null) {
                                    task.mDisplayContent = mService.getDefaultDisplayContentLocked();
                                }
                                final Task fullTask = TopActivityRecorder.getInstance().getTopFullscreenTask();
                                // Closing also works above Home, where no fullscreen app exists.
                                task.setAlwaysOnTop(false);
                                task.mDisplayContent.assignWindowLayers(true);
                                moveActivityTaskToBackInner(task, fullTask);
                                mHandler.postDelayed(()-> {
                                    tryExitPopUpView(task, true, reason != MOVE_TO_BACK_NEW_MINI, reason != MOVE_TO_BACK_NEW_PIN);
                                }, EXIT_POP_UP_DELAY);
                            }
                        }
                );
            }
        }
    }

    boolean getOrCreateRootTask(Task candidateTask, DisplayContent displayContent, int windowingMode) {
        if (!WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return false;
        }
        setWindowingModePopUpView(candidateTask, windowingMode);
        return true;
    }

    void setUpRootTask(Task rootTask, DisplayContent displayContent, int windowingMode) {
        if (!WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return;
        }
        setWindowingModePopUpView(rootTask, windowingMode);
    }

    boolean startActivityFromRecents(Task task, ActivityOptions activityOptions) {
        if (activityOptions != null && WindowConfiguration.isPopUpWindowMode(
                activityOptions.getLaunchWindowingMode())) {
            final ActivityRecord top = task != null ? task.getTopNonFinishingActivity() : null;
            if (top == null || !PopUpSettingsConfig.getInstance().isPackageAllowed(top.packageName)) {
                activityOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
            }
        }
        setGestureStartBounds(task, activityOptions);
        if (tryExitPinnedWindow(task, activityOptions, true)) {
            if (activityOptions == null) {
                return true;
            }
            try {
                if (activityOptions.getRemoteAnimationAdapter() != null) {
                    activityOptions.getRemoteAnimationAdapter().getRunner().onAnimationCancelled();
                }
            } catch (Exception e) {
                Slog.e(TAG, "StartActivityFromRecents failed cancel task=" + task, e);
            }
            return true;
        }
        return false;
    }

    void setGestureStartBounds(Task task, ActivityOptions options) {
        if (task == null || options == null
                || !WindowConfiguration.isPopUpWindowMode(options.getLaunchWindowingMode())
                || options.getAnimationType() != ActivityOptions.ANIM_SCALE_UP
                || options.getWidth() <= 0 || options.getHeight() <= 0) return;
        final Task root = task.getRootTask();
        final TaskWindowSurfaceInfo info = root.mWindowContainerExt.getTaskWindowSurfaceInfo();
        if (info != null) {
            info.setGestureStartBounds(new Rect(options.getStartX(), options.getStartY(),
                    options.getStartX() + options.getWidth(),
                    options.getStartY() + options.getHeight()));
        }
    }

    void startLockTaskMode(Task task) {
        findAndExitAllPopUp();
    }

    void notifyNextRecentIsPin() {
        mNextRecentIsPin = true;
    }

    private boolean tryExitPinnedWindow(Task task, ActivityOptions activityOptions, boolean isFromRecents) {
        if (isFromRecents && activityOptions == null) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "tryExitPinnedWindow skip exit for null options task: " + task);
            }
            return false;
        }
        if (task == null) {
            return false;
        }
        if (task.getWindowConfiguration().isPinnedExtWindowMode() &&
                (activityOptions == null || activityOptions.getLaunchWindowingMode() == WINDOWING_MODE_UNDEFINED)) {
            final Task rootTask = task.getRootTask();
            if (rootTask != null) {
                if (task.mTransitionController.isShellTransitionsEnabled()
                        && task.mTransitionController.isCollecting()) {
                    final Transition t = task.mTransitionController.getCollectingTransition();
                    t.abort();
                    if (DEBUG_POP_UP) {
                        Slog.d(TAG, "abort transition=" + t);
                    }
                }
                mTryExitWindowingMode = true;
                tryExitPopUpView(rootTask, false, false, true);
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "tryExitPinnedWindow targetTask=" + rootTask +
                            " activityOptions=" + activityOptions);
                }
                return true;
            }
        } else if (isFromRecents && task != null &&
                task.getWindowConfiguration().isMiniExtWindowMode()) {
            try {
                if (activityOptions.getRemoteAnimationAdapter() != null) {
                    activityOptions.getRemoteAnimationAdapter().getRunner().onAnimationCancelled();
                }
            } catch (Exception e) {
                Slog.w(TAG, "startActivityFromRecents failed cancel task=" + task, e);
            }
        } else if (isFromRecents &&
                activityOptions.getLaunchWindowingMode() == WINDOWING_MODE_PINNED_WINDOW_EXT) {
            if (mNextRecentIsPin) {
                mNextRecentIsPin = false;
                mAtmService.focusTopTask(DEFAULT_DISPLAY);
            } else {
                mLaunchPopUpViewFromRecents = true;
            }
        }
        return false;
    }

    boolean tryExitPopUpView(Task task, boolean skipAnim, boolean removeMini, boolean removePin) {
        if (task != null && task.getWindowConfiguration().isPopUpWindowMode()) {
            synchronized (mAtmService.mGlobalLock) {
                mAtmService.deferWindowLayout();
                try {
                    final boolean wasMiniWindow = task.getWindowConfiguration().isMiniExtWindowMode();
                    final Task rootTask = task.getRootTask();
                    if (rootTask != null) {
                        if (DEBUG_POP_UP) {
                            Slog.d(TAG, "tryExitPopUpView task=" + task +
                                    " skipAnim=" + skipAnim +
                                    " removeMini=" + removeMini +
                                    ", removePin=" + removePin +
                                    ", wasMiniWindow=" + wasMiniWindow);
                        }
                        rootTask.mWindowContainerExt.setFreezerSkipAnim(skipAnim);
                        if (!skipAnim) {
                            rootTask.mWindowContainerExt.prepareTransition();
                        }
                        rootTask.setAlwaysOnTop(false);
                        rootTask.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                        rootTask.setBounds(null);
                        rootTask.mWindowContainerExt.setFreezerSkipAnim(false);
                        if (skipAnim) {
                            rootTask.mTaskSupervisor.mNoAnimActivities.clear();
                            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                            if (info != null) {
                                info.cancelPopUpViewAnimation();
                            }
                            rootTask.resetSurfaceControlTransforms();
                        }
                        if (removeMini && wasMiniWindow) {
                            TopActivityRecorder.getInstance().removeMiniWindowTask(task);
                        }
                        if (removePin && !wasMiniWindow) {
                            TopActivityRecorder.getInstance().clearPinnedWindow();
                            DimmerWindow.getInstance().setTask(null);
                        }
                        if (!skipAnim) {
                            rootTask.mWindowContainerExt.scheduleTransition();
                        }
                        return true;
                    }
                } catch (IllegalStateException | NullPointerException e) {
                    Slog.e(TAG, "Failed exit pop-up window: ", e);
                    return false;
                } finally {
                    mAtmService.continueWindowLayout();
                }
            }
        }
        return false;
    }

    void updateFocusedApp() {
        final DisplayContent defaultDisplay = mService.getDefaultDisplayContentLocked();
        defaultDisplay.mFocusedApp = null;
        final WindowState win = defaultDisplay.findFocusedWindow();
        if (win != null && win.getTask() != null) {
            mAtmService.setFocusedTask(win.getTask().mTaskId);
        }
    }

    void enterMiniWindowingMode(WindowState win) {
        synchronized (mAtmService.mGlobalLock) {
            if (win != null) {
                final Task task = win.getTask();
                final Task rootTask = task != null ? task.getRootTask() : null;
                if (rootTask == null) {
                    Slog.e(TAG, "enterMiniWindowingMode: the windowState doesn't have a root task. rootTask=" + rootTask);
                    return;
                }
                final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (info != null) {
                    info.cancelPopUpViewAnimation();
                }
                if (rootTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                    final Task prevMiniTask = TopActivityRecorder.getInstance().moveTopPinnedToMini();
                    if (prevMiniTask != null) {
                        moveActivityTaskToBack(prevMiniTask, MOVE_TO_BACK_NEW_MINI);
                    }
                } else {
                    final Task existingMiniTask = TopActivityRecorder.getInstance()
                            .getTopMiniWindowExceptTask(task);
                    if (existingMiniTask != null) {
                        moveActivityTaskToBack(existingMiniTask, MOVE_TO_BACK_NEW_MINI);
                    }
                }
                rootTask.mWindowContainerExt.prepareTransition();
                rootTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
                mAtmService.setFocusedTask(rootTask.mTaskId);
                rootTask.mWindowContainerExt.scheduleTransition();
                DimmerWindow.getInstance().setTask(rootTask);
            }
        }
    }

    void enterPinnedWindowingMode(Task task) {
        enterPinnedWindowingModeInternal(task, false);
    }

    void enterPinnedWindowingModeFromDimmer(Task rootTask) {
        enterPinnedWindowingModeInternal(rootTask, true);
    }

    private void enterPinnedWindowingModeInternal(Task taskOrRootTask, boolean isFromDimmer) {
        synchronized (mAtmService.mGlobalLock) {
            if (taskOrRootTask == null) {
                Slog.e(TAG, "enterPinnedWindowingModeInternal: task is null");
                return;
            }
            final Task rootTask = isFromDimmer ? taskOrRootTask : taskOrRootTask.getRootTask();
            if (rootTask == null) {
                Slog.e(TAG, "enterPinnedWindowingModeInternal: rootTask is null");
                return;
            }
            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.cancelPopUpViewAnimation();
            }
            if (!isFromDimmer) {
                final Task existingPinnedTask = TopActivityRecorder.getInstance().getTopPinnedWindowTask();
                if (existingPinnedTask != null && existingPinnedTask != taskOrRootTask) {
                    TopActivityRecorder.getInstance().clearPinnedWindow();
                    PinnedWindowOverlayController.getInstance().setTask(null);
                    final Task existingRootTask = existingPinnedTask.getRootTask();
                    if (existingRootTask != null) {
                        tryExitPopUpView(existingRootTask, true, false, true);
                        existingRootTask.moveTaskToBack(existingPinnedTask);
                    }
                }
            }
            if (rootTask.getWindowConfiguration().isMiniExtWindowMode()) {
                TopActivityRecorder.getInstance().moveTopMiniToPinned(
                        isFromDimmer ? rootTask : taskOrRootTask);
            }
            DimmerWindow.getInstance().hide();
            PinnedWindowOverlayController.getInstance().setTask(null);
            rootTask.mWindowContainerExt.prepareTransition();
            rootTask.mWindowContainerExt.setPreFreezedWindowingMode(
                    rootTask.getWindowConfiguration().getWindowingMode());
            final Rect startBounds = new Rect();
            rootTask.getBounds(startBounds);
            rootTask.mWindowContainerExt.getFreezerExt().transitionFreeze(startBounds, info);
            if (info != null) {
                info.resetWindowBoundaryGapToOrigin();
            }
            final Rect bounds = new Rect();
            rootTask.getBounds(bounds);
            WindowResizingAlgorithm.getPopUpViewDefalutBounds(bounds);
            rootTask.setAlwaysOnTop(true);
            rootTask.setBounds(bounds);
            if (info != null) {
                info.setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                        rootTask.getConfiguration().orientation, info.isPinnedWindowSmall()));
            }
            final Rect displayBound = new Rect();
            if (rootTask.mDisplayContent != null) {
                rootTask.mDisplayContent.getBounds(displayBound);
            }
            if (!displayBound.isEmpty() && info != null) {
                final Point pos = new Point();
                WindowResizingAlgorithm.getCenterByBoundaryGap(
                        bounds, displayBound, info.getWindowBoundaryGap(),
                        info.getPinnedWindowVerticalPosRatio(displayBound),
                        info.getWindowCenterPosition(),
                        info.getWindowSurfaceScale(), pos);
                info.setWindowCenterPosition(pos);
                info.setWindowSurfaceScaleFactor(1.0f);
            }

            rootTask.setWindowingMode(WINDOWING_MODE_PINNED_WINDOW_EXT);
            final ActivityRecord topActivity = rootTask.topRunningActivity();
            if (topActivity != null) {
                TopActivityRecorder.getInstance().updateTopPinnedWindowActivity(topActivity);
            }
            updateFocusedApp();
            rootTask.mWindowContainerExt.scheduleTransition();
            PinnedWindowOverlayController.getInstance().setTask(rootTask);
        }
    }
    void startMovingTask(Task task, float x, float y) {
        if (task == null) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.startMoving(x, y);
            }
        }
    }

    void finishMovingTask(Task task) {
        if (task == null) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.finishMoving();
            }
        }
    }

    void updateMovingTask(Task task, float x, float y) {
        if (task == null) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.updateMoving(x, y);
            }
        }
    }

    void cancelMovingTask(Task task) {
        if (task == null) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.cancelMoving();
            }
        }
    }

    void enterMiniWindowingMode(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            if (task == null) {
                return;
            }
            final Task rootTask = task.getRootTask();
            if (rootTask == null) {
                return;
            }
            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.cancelPopUpViewAnimation();
            }
            if (rootTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                final Task prevMiniTask = TopActivityRecorder.getInstance().moveTopPinnedToMini();
                if (prevMiniTask != null) {
                    moveActivityTaskToBack(prevMiniTask, MOVE_TO_BACK_NEW_MINI);
                }
            } else {
                final Task existingMiniTask = TopActivityRecorder.getInstance()
                        .getTopMiniWindowExceptTask(task);
                if (existingMiniTask != null) {
                    moveActivityTaskToBack(existingMiniTask, MOVE_TO_BACK_NEW_MINI);
                }
            }
            rootTask.mWindowContainerExt.prepareTransition();
            rootTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
            rootTask.mWindowContainerExt.scheduleTransition();
            DimmerWindow.getInstance().setTask(rootTask);
        }
    }

    void triggerPinnedWindowResize(Task task) {
        if (task == null) {
            return;
        }
        synchronized (mAtmService.mGlobalLock) {
            final Task rootTask = task.getRootTask();
            if (rootTask == null) {
                return;
            }
            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.toggleResize();
            }
        }
    }

    private void capturePopUpViewTaskSnapshot(Task task) {
        if (task != null) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "captureTaskSnapshot for task=" + task);
            }
            final ArraySet<Task> tasks = Sets.newArraySet(new Task[] {task});
            mService.mTaskSnapshotController.snapshotTasks(tasks);
        }
    }

    private void setWindowingModePopUpView(Task task, int windowingMode) {
        if (task != null) {
            final Task rootTask = task.getRootTask();

            boolean skipPrepareTransition = false;
            if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                final Task existingPinnedTask = TopActivityRecorder.getInstance().getTopPinnedWindowTask();
                final Task existingPinnedRootTask = existingPinnedTask != null
                        ? existingPinnedTask.getRootTask() : null;
                if (existingPinnedTask != null
                        && (existingPinnedTask == task || existingPinnedRootTask == rootTask)) {
                    final Task prevMiniTask = TopActivityRecorder.getInstance().moveTopPinnedToMini();
                    if (prevMiniTask != null) {
                        moveActivityTaskToBack(prevMiniTask, MOVE_TO_BACK_NEW_MINI);
                    }
                    PinnedWindowOverlayController.getInstance().setTask(null);
                } else {
                    final Task existingMiniTask = TopActivityRecorder.getInstance()
                            .getTopMiniWindowExceptTask(task);
                    if (existingMiniTask != null) {
                        moveActivityTaskToBack(existingMiniTask, MOVE_TO_BACK_NEW_MINI);
                    }
                }
            } else if (WindowConfiguration.isPinnedExtWindowMode(windowingMode)) {
                final Task existingPinnedTask = TopActivityRecorder.getInstance().getTopPinnedWindowTask();
                if (existingPinnedTask != null && existingPinnedTask != task) {
                    TopActivityRecorder.getInstance().clearPinnedWindow();
                    PinnedWindowOverlayController.getInstance().setTask(null);
                    final Task existingRootTask = existingPinnedTask.getRootTask();
                    if (existingRootTask != null) {
                        tryExitPopUpView(existingRootTask, true, false, true);
                        existingRootTask.moveTaskToBack(existingPinnedTask);
                    }
                }
            }

            if (rootTask != null && !task.getWindowConfiguration().isPopUpWindowMode()) {
                final TaskWindowSurfaceInfo surfaceInfo = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                final Rect displayBound = new Rect();
                if (rootTask.mDisplayContent != null) {
                    rootTask.mDisplayContent.getBounds(displayBound);
                    if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                        final Point pos = new Point(displayBound.centerX(), displayBound.centerY());
                        surfaceInfo.resetWindowBoundaryGap();
                        surfaceInfo.setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                                rootTask.getConfiguration().orientation, rootTask.mDisplayContent.getRotation()));
                        surfaceInfo.setWindowCenterPosition(pos);
                        surfaceInfo.setCornerRadius(surfaceInfo.getMiniWindowCornerRadius());
                    }
                }
            }

            if (!task.getWindowConfiguration().isPopUpWindowMode()) {
                // Shell collects its own transition snapshot. A second synchronous
                // GPU readback under the WM lock stalls every small-window entry.
                if (!task.mTransitionController.isShellTransitionsEnabled()) {
                    capturePopUpViewTaskSnapshot(task);
                }
                if (!skipPrepareTransition) {
                    task.mWindowContainerExt.prepareTransition();
                }
                task.setWindowingMode(windowingMode);
            } else if (task.getWindowConfiguration().getWindowingMode() != windowingMode) {
                if (!skipPrepareTransition) {
                    task.mWindowContainerExt.prepareTransition();
                }
                task.setWindowingMode(windowingMode);
            }
            if (rootTask != null) {
                final Rect bounds = new Rect();
                final TaskWindowSurfaceInfo surfaceInfo = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                    surfaceInfo.resetWindowBoundaryGap();
                } else {
                    surfaceInfo.resetWindowBoundaryGapToOrigin();
                }
                rootTask.getBounds(bounds);
                WindowResizingAlgorithm.getPopUpViewDefalutBounds(bounds);
                rootTask.setAlwaysOnTop(true);
                rootTask.setBounds(bounds);

                if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                    DimmerWindow.getInstance().setTask(rootTask);
                    if (rootTask.mSurfaceControl != null && rootTask.mSurfaceControl.isValid()) {
                        final Rect displayBound = new Rect();
                        if (rootTask.mDisplayContent != null) {
                            rootTask.mDisplayContent.getBounds(displayBound);
                            final Point pos = new Point(displayBound.centerX(), displayBound.centerY());
                            surfaceInfo.setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                                    rootTask.getConfiguration().orientation, rootTask.mDisplayContent.getRotation()));
                            surfaceInfo.setWindowCenterPosition(pos);

                            final Point windowPos = new Point();
                            final float scaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                                    bounds, displayBound, pos, surfaceInfo.getWindowSurfaceScale(),
                                    false, windowPos);
                            surfaceInfo.setWindowSurfaceScaleFactor(scaleFactor);
                            final float realScale = surfaceInfo.getWindowSurfaceRealScale();

                            if (DEBUG_POP_UP) {
                                Slog.d(TAG, "Force update mini window surface: pos=" + windowPos
                                        + " scale=" + realScale + " bounds=" + bounds);
                            }

                            rootTask.getSyncTransaction()
                                    .setPosition(rootTask.mSurfaceControl, windowPos.x, windowPos.y)
                                    .setWindowCrop(rootTask.mSurfaceControl, bounds.width(), bounds.height())
                                    .setScale(rootTask.mSurfaceControl, realScale, realScale)
                                    .setCornerRadius(rootTask.mSurfaceControl, surfaceInfo.getCornerRadius())
                                    .setAlpha(rootTask.mSurfaceControl, 1.0f)
                                    .show(rootTask.mSurfaceControl);
                        } else if (DEBUG_POP_UP) {
                            Slog.w(TAG, "Cannot force update mini window surface: mDisplayContent is null");
                        }
                    } else if (DEBUG_POP_UP) {
                        Slog.w(TAG, "Cannot force update mini window surface: mSurfaceControl is null or invalid");
                    }
                } else if (WindowConfiguration.isPinnedExtWindowMode(windowingMode)) {
                    DimmerWindow.getInstance().setTask(null);
                    final ActivityRecord topActivity = rootTask.topRunningActivity();
                    if (topActivity != null) {
                        TopActivityRecorder.getInstance().updateTopPinnedWindowActivity(topActivity);
                    }
                    PinnedWindowOverlayController.getInstance().setTask(rootTask);
                }
            }
            task.mWindowContainerExt.scheduleTransition();
        }
    }

    void triggerVibrate() {
        if (!PopUpSettingsConfig.getInstance().isHapticsEnabled()
                || android.provider.Settings.System.getIntForUser(mContext.getContentResolver(),
                        android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
                        1, UserHandle.USER_CURRENT) == 0) {
            return;
        }
        mHandler.post(() -> {
            if (mVibrator != null) {
                VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
                effect = effect.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG);
                mVibrator.vibrate(effect);
            }
        });
    }

    boolean isTryExitWindowingModeByDrag() {
        return mTryExitWindowingModeByDrag;
    }

    void setTryExitWindowingModeByDrag(boolean isExit) {
        if (mAtmService.getTransitionController().isShellTransitionsEnabled() && !isExit) {
            return;
        }
        mTryExitWindowingModeByDrag = isExit;
    }

    boolean isTryExitWindowingMode() {
        return mTryExitWindowingMode;
    }

    void setTryExitWindowingMode(boolean isExit) {
        mTryExitWindowingMode = isExit;
    }

    boolean isLaunchPopUpViewFromRecents() {
        return mLaunchPopUpViewFromRecents;
    }

    boolean shouldInitializeChangeTransition(Task task, int prevWinMode) {
        if (task.mWindowContainerExt.setPreFreezedWindowingMode(prevWinMode)) {
            if (task.mWindowContainerExt.getFreezerSkipAnim()) {
                return false;
            }
            if (mTryExitWindowingModeByDrag) {
                final TaskWindowSurfaceInfo info = new TaskWindowSurfaceInfo(
                        task.mWindowContainerExt.getTaskWindowSurfaceInfo(), prevWinMode);
                task.mTmpPrevBounds.set(info.getTaskWindowSurfaceBounds());
            }
        }
        return true;
    }

    boolean shouldSkipNextTransitionFreeze() {
        if (!mSkipNextTransitionFreeze) {
            return false;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "entering multi-window, skip transition freeze");
        }
        mSkipNextTransitionFreeze = false;
        return true;
    }

    boolean shouldStartChangeTransition(int prevWinMode, int newWinMode) {
        if (WindowConfiguration.isMiniExtWindowMode(prevWinMode) !=
                WindowConfiguration.isMiniExtWindowMode(newWinMode)) {
            return true;
        }
        if (WindowConfiguration.isPinnedExtWindowMode(prevWinMode) !=
                WindowConfiguration.isPinnedExtWindowMode(newWinMode)) {
            return true;
        }
        return false;
    }

    void notifyFinishTransition() {
        mTryExitWindowingModeByDrag = false;
        mLaunchPopUpViewFromRecents = false;
    }

    InsetsState adjustInsetsForWindow(WindowState target, InsetsState state) {
        if (target != null && target.mActivityRecord != null &&
                target.mActivityRecord.getWindowConfiguration().isPopUpWindowMode()) {
            state = new InsetsState(state);
            state.removeSource(ID_DISPLAY_CUTOUT_LEFT);
            state.removeSource(ID_DISPLAY_CUTOUT_TOP);
            state.removeSource(ID_DISPLAY_CUTOUT_RIGHT);
            state.removeSource(ID_DISPLAY_CUTOUT_BOTTOM);
            handleImeInsetsForPopUpView(target, state);
        }
        return state;
    }

    private void handleImeInsetsForPopUpView(WindowState target, InsetsState state) {
        final InsetsSource imeSource = state.peekSource(InsetsSource.ID_IME);
        if (imeSource == null) {
            return;
        }
        final Task task = target.getTask();
        final Task rootTask = task != null ? task.getRootTask() : null;
        final InsetsSource newImeSource = new InsetsSource(imeSource);
        if (rootTask != null && rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            final TaskWindowSurfaceInfo windowInfo = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            final Rect displayFrame = target.getDisplayFrame();
            final Rect displayRect = windowInfo.getTaskWindowSurfaceBounds();
            final Rect frame = newImeSource.getFrame();
            final Rect visibleFrame = newImeSource.getVisibleFrame();
            final float scale = windowInfo.getWindowSurfaceRealScale();
            if (frame != null && !frame.isEmpty()) {
                final int frameHeight = Math.max(0, displayRect.bottom - frame.top);
                newImeSource.setFrame(displayFrame.left, displayFrame.bottom - Math.round(
                        (frameHeight * 1.0f) / scale), displayFrame.right, displayFrame.bottom);
            }
            if (visibleFrame != null && !visibleFrame.isEmpty()) {
                final int vfHeight = Math.max(0, displayRect.bottom - visibleFrame.top);
                newImeSource.setVisibleFrame(new Rect(displayFrame.left, displayFrame.bottom - Math.round(
                        (vfHeight * 1.0f) / scale), displayFrame.right, displayFrame.bottom));
            }
        } else {
            newImeSource.setVisible(false);
            newImeSource.setFrame(0, 0, 0, 0);
        }
        state.addSource(newImeSource);
    }

    void calculateTransitionInfo(ArrayList<ChangeInfo> sortedTargets, TransitionInfo out) {
        boolean rotated = false;
        for (int i = 0; i < sortedTargets.size(); i++) {
            final ChangeInfo info = sortedTargets.get(i);
            if (info.mContainer instanceof DisplayContent &&
                    info.mRotation != info.mContainer.getWindowConfiguration().getRotation()) {
                rotated = true;
            }
        }
        if (!rotated) {
            return;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Abort pop-up view flags for display ratated");
        }
        for (int i = 0; i < out.getChanges().size(); i++) {
            final Change change = out.getChanges().get(i);
            change.setFlags(change.getFlags() | FLAG_EXIT_POP_UP_VIEW_DISPLAY_ROTATION);
        }
        for (int i = 0; i < sortedTargets.size(); i++) {
            final ChangeInfo info = sortedTargets.get(i);
            info.mReadyFlags = info.mReadyFlags | FLAG_EXIT_POP_UP_VIEW_DISPLAY_ROTATION;
        }
    }

    void computeLaunchParams(LaunchParams params, ActivityOptions options, Task task) {
        if (!WindowConfiguration.isPinnedExtWindowMode(params.mWindowingMode)
                && tryExitPinnedWindow(task, options, false)) {
            params.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
            if (options != null) {
                options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
            }
        }
    }

    void computeBeforeExecuteRequest(Request request) {
        if (request.intent == null) {
            return;
        }
        if (!PopUpSettingsConfig.getInstance().isEnabled()) {
            if (request.activityOptions != null && request.activityOptions.isPopUpWindowMode()) {
                request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_UNDEFINED);
            }
            return;
        }

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "computeBeforeExecuteRequest, caller=" + request.callingPackage
                    + ", intent=" + request.intent);
        }

        if ((request.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "computeBeforeExecuteRequest, skip: original intent requires clear task");
            }
            return;
        }

        final String callerPackage = request.callingPackage;
        final ComponentName component = request.intent.getComponent();
        final String targetPackage = component != null ? component.getPackageName()
                : request.activityInfo != null ? request.activityInfo.packageName : "";

        if (!PopUpSettingsConfig.getInstance().isPackageAllowed(targetPackage)) {
            if (request.activityOptions != null && request.activityOptions.isPopUpWindowMode()) {
                request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
            }
            return;
        }

        final String currentTopFullscreenPackage = TopActivityRecorder.getInstance().getTopFullscreenPackage();
        final String currentTopMiniPackage = TopActivityRecorder.getInstance().getTopMiniWindowPackage();
        final String currentTopPinnedPackage = TopActivityRecorder.getInstance().getTopPinnedWindowPackage();

        if (!TextUtils.isEmpty(targetPackage)
                && request.activityOptions != null
                && request.activityOptions.isPopUpWindowMode()
                && !request.activityOptions.isFromNotification()
                && (targetPackage.equals(currentTopFullscreenPackage)
                        || targetPackage.equals(currentTopMiniPackage)
                        || targetPackage.equals(currentTopPinnedPackage))) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "computeBeforeExecuteRequest, skip: " + targetPackage
                        + " already visible, refusing to open itself in pop-up");
            }
            request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_UNDEFINED);
            return;
        }

        if (request.activityOptions != null && request.activityOptions.isFromNotification()
                && request.activityOptions.isMiniWindowingMode()) {
            if (currentTopMiniPackage.equals(targetPackage)) {
                return;
            }

            if (!request.activityOptions.isSmallWindowGesture()
                    && (currentTopFullscreenPackage.equals(targetPackage)
                            || currentTopPinnedPackage.equals(targetPackage))) {
                request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_UNDEFINED);
                return;
            }

            if (PopUpSettingsConfig.getInstance().inNotificationBlacklist(targetPackage)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "computeBeforeExecuteRequest, skip: in Notification target blacklist");
                }
                request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
                return;
            }
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "computeBeforeExecuteRequest, configure: enter Notification");
            }
            return;
        }

        if (currentTopMiniPackage.equals(callerPackage)) {
            if (!TextUtils.isEmpty(targetPackage) && targetPackage.equals(callerPackage)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "computeBeforeExecuteRequest, skip: in-mini caller "
                            + callerPackage + " launching itself");
                }
                if (request.activityOptions != null
                        && request.activityOptions.isPopUpWindowMode()) {
                    request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_UNDEFINED);
                }
                return;
            }
            // Let normal task/intent rules handle launches into other applications.
            // Only privileged system entry points may request the custom window modes.
        }
    }

    private String reasonToString(int reason) {
        switch (reason) {
            case MOVE_TO_BACK_TOUCH_OUTSIDE:
                return "TOUCH_OUTSIDE";
            case MOVE_TO_BACK_FROM_LEAVE_BUTTON:
                return "FROM_LEAVY_BUTTON";
            case MOVE_TO_BACK_NEW_MINI:
                return "NEW_MINI";
            case MOVE_TO_BACK_NEW_PIN:
                return "NEW_PIN";
            case MOVE_TO_BACK_NON_USER:
                return "NON_USER";
            default:
                return "UNKNOWN";
        }
    }

    boolean isMiniWindowFocused() {
        final Task miniTask = DimmerWindow.getInstance().getTask();
        if (miniTask == null || !miniTask.getWindowConfiguration().isMiniExtWindowMode()) {
            return false;
        }

        synchronized (mService.mGlobalLock) {
            final DisplayContent dc = getDefaultDisplayContent();
            if (dc != null && dc.mCurrentFocus != null) {
                final Task focusedWindowTask = dc.mCurrentFocus.getTask();
                if (focusedWindowTask == miniTask || focusedWindowTask == miniTask.getRootTask()) {
                    return true;
                }
            }
            final Task focusedTask = mAtmService.getTopDisplayFocusedRootTask();
            if (focusedTask != null) {
                if (focusedTask == miniTask || focusedTask == miniTask.getRootTask()) {
                    return true;
                }
            }
            if (dc != null && dc.mFocusedApp != null) {
                final Task focusedAppTask = dc.mFocusedApp.getTask();
                if (focusedAppTask == miniTask || focusedAppTask == miniTask.getRootTask()) {
                    return true;
                }
            }

            return false;
        }
    }
    Task getMiniWindowTask() {
        return DimmerWindow.getInstance().getTask();
    }
    Task getTopFullscreenTaskBelowMini() {
        synchronized (mService.mGlobalLock) {
            return TopActivityRecorder.getInstance().getTopFullscreenTask();
        }
    }
    void closeMiniWindow() {
        synchronized (mAtmService.mGlobalLock) {
            final Task task = DimmerWindow.getInstance().getTask();
            if (task != null) moveActivityTaskToBack(task, MOVE_TO_BACK_TOUCH_OUTSIDE);
        }
    }

    public void exitMiniWindowingMode() {
        synchronized (mAtmService.mGlobalLock) {
            exitMiniWindowingMode(DimmerWindow.getInstance().getTask());
        }
    }

    void exitMiniWindowingMode(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            final Task rootTask = task != null ? task.getRootTask() : null;
            if (rootTask != null && rootTask.getWindowConfiguration().isMiniExtWindowMode()) {
                TopActivityRecorder.getInstance().moveTopMiniToFull();
                setTryExitWindowingMode(true);
                try {
                    tryExitPopUpView(rootTask, false, true, true);
                } finally {
                    setTryExitWindowingMode(false);
                }
            }
        }
    }

    void exitPinnedWindowingMode(WindowState win) {
        synchronized (mAtmService.mGlobalLock) {
            if (win == null) {
                Slog.e(TAG, "exitPinnedWindowingMode: win is null");
                return;
            }
            final Task task = win.getTask();
            final Task rootTask = task != null ? task.getRootTask() : null;
            if (rootTask == null) {
                Slog.e(TAG, "exitPinnedWindowingMode: rootTask is null");
                return;
            }
            if (!rootTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                Slog.e(TAG, "exitPinnedWindowingMode: not in pinned mode");
                return;
            }
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "exitPinnedWindowingMode: task=" + task);
            }
            TopActivityRecorder.getInstance().moveTopPinnedToFull();
            DimmerWindow.getInstance().setTask(null);
            setTryExitWindowingMode(true);
            tryExitPopUpView(rootTask, false, false, true);
            setTryExitWindowingMode(false);
        }
    }

    void enterMiniWindowingModeFromPinned(WindowState win) {
        synchronized (mAtmService.mGlobalLock) {
            if (win == null) {
                Slog.e(TAG, "enterMiniWindowingModeFromPinned: win is null");
                return;
            }
            final Task task = win.getTask();
            final Task rootTask = task != null ? task.getRootTask() : null;
            if (rootTask == null) {
                Slog.e(TAG, "enterMiniWindowingModeFromPinned: rootTask is null");
                return;
            }
            if (!rootTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                Slog.e(TAG, "enterMiniWindowingModeFromPinned: not in pinned mode");
                return;
            }
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "enterMiniWindowingModeFromPinned: task=" + task);
            }
            final TaskWindowSurfaceInfo surfaceInfo = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            final Task prevMiniTask = TopActivityRecorder.getInstance().moveTopPinnedToMini();
            if (prevMiniTask != null) {
                moveActivityTaskToBack(prevMiniTask, MOVE_TO_BACK_NEW_MINI);
            }
            rootTask.mWindowContainerExt.prepareTransition();
            rootTask.mWindowContainerExt.setPreFreezedWindowingMode(
                    rootTask.getWindowConfiguration().getWindowingMode());
            final Rect startBounds = new Rect();
            rootTask.getBounds(startBounds);
            rootTask.mWindowContainerExt.getFreezerExt().transitionFreeze(startBounds, surfaceInfo);
            final Rect displayBound = new Rect();
            if (rootTask.mDisplayContent != null) {
                rootTask.mDisplayContent.getBounds(displayBound);
                final Point pos = new Point(displayBound.centerX(), displayBound.centerY());
                surfaceInfo.resetWindowBoundaryGap();
                float defaultScale = WindowResizingAlgorithm.getDefaultMiniWindowScale(
                        rootTask.getConfiguration().orientation, rootTask.mDisplayContent.getRotation());
                surfaceInfo.setWindowSurfaceScale(defaultScale);
                surfaceInfo.setWindowCenterPosition(pos);
                surfaceInfo.setCornerRadius(surfaceInfo.getMiniWindowCornerRadius());
            }

            rootTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
            mAtmService.setFocusedTask(rootTask.mTaskId);
            rootTask.mWindowContainerExt.scheduleTransition();
            DimmerWindow.getInstance().setTask(rootTask);
        }
    }

    private boolean mMiniWindowHasInputFocus = false;

    boolean shouldMiniWindowHandleInput() {
        final Task miniTask = DimmerWindow.getInstance().getTask();
        if (miniTask == null || !miniTask.getWindowConfiguration().isMiniExtWindowMode()) {
            return false;
        }
        return mMiniWindowHasInputFocus;
    }

    private void setMiniWindowInputFocus(boolean hasFocus) {
        if (mMiniWindowHasInputFocus != hasFocus) {
            mMiniWindowHasInputFocus = hasFocus;
            Slog.e(TAG, "Mini window input focus changed: " + hasFocus);
            DimmerWindow.getInstance().notifyFocusChanged();
        }
    }

    private void setupMiniWindowPointerListener() {
        mMiniWindowPointerListener = event -> {
            if (event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
                return;
            }

            final Task miniTask = DimmerWindow.getInstance().getTask();
            if (miniTask == null || !miniTask.getWindowConfiguration().isMiniExtWindowMode()) {
                return;
            }

            final DisplayContent dc = miniTask.getDisplayContent();
            if (dc == null) return;

            final int x = (int) event.getRawX();
            final int y = (int) event.getRawY();

            final DisplayPolicy policy = dc.getDisplayPolicy();
            final int leftGestureInset = policy.getLeftGestureInset();
            final int rightGestureInset = policy.getRightGestureInset();
            final int displayWidth = dc.mBaseDisplayWidth;

            final boolean inLeftGestureArea = x < leftGestureInset;
            final boolean inRightGestureArea = x > (displayWidth - rightGestureInset);

            if (inLeftGestureArea || inRightGestureArea) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Touch in gesture area at (" + x + "," + y + "), ignoring");
                }
                return;
            }

            if (isShadeExpanded(dc)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Shade/QuickSettings expanded, ignoring touch");
                }
                return;
            }

            if (isInExcludeArea(dc, x, y)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Touch in system UI area at (" + x + "," + y + "), ignoring");
                }
                return;
            }

            final Rect dimmerBounds = DimmerWindow.getInstance().getBounds();
            final boolean inMiniWindow = dimmerBounds != null && dimmerBounds.contains(x, y);

            if (DEBUG_POP_UP) {
                Slog.d(TAG, "Touch at (" + x + "," + y + "), dimmerBounds=" + dimmerBounds +
                    ", inMiniWindow=" + inMiniWindow);
            }


            DimmerWindow.getInstance().hideMenu();

            if (inMiniWindow) {
                setMiniWindowInputFocus(true);
            } else {
                setMiniWindowInputFocus(false);
            }
        };

        mService.registerPointerEventListener(mMiniWindowPointerListener, DEFAULT_DISPLAY);
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Registered mini window pointer listener");
        }
    }

    private boolean isInExcludeArea(DisplayContent dc, int x, int y) {
        final DisplayPolicy policy = dc.getDisplayPolicy();
        final WindowState statusBar = policy.getStatusBar();
        if (statusBar != null && statusBar.getFrame().contains(x, y)) {
            return true;
        }
        final WindowState navBar = policy.getNavigationBar();
        if (navBar != null && navBar.getFrame().contains(x, y)) {
            return true;
        }

        return false;
    }

    private boolean isShadeExpanded(DisplayContent dc) {
        final WindowState shadeWindow = dc.getWindow(w -> {
            final String windowName = w.toString();
            return windowName.contains("NotificationShade");
        });

        if (shadeWindow != null) {
            final boolean visible = shadeWindow.isVisible();
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "NotificationShade window found, isVisible=" + visible);
            }
            return visible;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "NotificationShade window not found");
        }
        return false;
    }
}
