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
import static android.view.WindowManager.TRANSIT_TO_FRONT;
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
import android.util.ArrayMap;
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
    private boolean mLastHyperOsMode;

    // WM lock protects pending closes until queued transitions commit task invisibility.
    private final ArrayMap<Task, Integer> mPendingHyperOsCloses = new ArrayMap<>();

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
        mLastHyperOsMode = PopUpSettingsConfig.getInstance().isHyperOsMode();
        HyperOsWindowDecor.getInstance().init(mService);
        // Aborting an unstarted Shell transition may not dispatch activity callbacks.
        // Sync-engine idle also covers that case without timing-based close delays.
        synchronized (mService.mGlobalLock) {
            mService.mSyncEngine.addOnIdleListener(this::schedulePendingHyperOsCloses);
        }
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
        if (HyperOsWindowDecor.getInstance().assignLayer(token, t)) {
            return true;
        }
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
        if (task.isRootTask() && task.getWindowConfiguration().isPopUpWindowMode()
                && PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            HyperOsWindowDecor.getInstance().update(task);
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
        mPendingHyperOsCloses.remove(task);
        HyperOsWindowDecor.getInstance().remove(task);
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
                && !PopUpSettingsConfig.getInstance().isHyperOsMode()
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
        if (newTask != null && PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            HyperOsWindowDecor.getInstance().update(newTask.getRootTask());
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
            HyperOsWindowDecor.getInstance().clear();
        }
    }

    // ArkUI: immediately reconcile open windows when the user changes policy.
    void onSettingsChanged() {
        if (mService == null || !mService.mSystemReady) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
            if (mLastHyperOsMode != hyperOs) {
                // Reconcile every task before changing decoration ownership. Flyme only
                // supports one interactive window and one minimized window.
                findAndExitAllPopUp();
                DimmerWindow.getInstance().setTask(null);
                PinnedWindowOverlayController.getInstance().setTask(null);
                mLastHyperOsMode = hyperOs;
            }
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
                if (hyperOs) HyperOsWindowDecor.getInstance().update(task);
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
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                if (reason != MOVE_TO_BACK_TOUCH_OUTSIDE) moveHyperOsTaskToBack(task, reason);
                return;
            }
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "moveActivityTaskToBack, task=" + (task != null ? task : "null")
                        + ", reason=" + reasonToString(reason));
            }
            if (task != null && task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
                if (reason == MOVE_TO_BACK_TOUCH_OUTSIDE) {
                    TopActivityRecorder.getInstance().removeMiniWindowTask(task);
                } else if (reason == MOVE_TO_BACK_FROM_LEAVE_BUTTON) {
                    TopActivityRecorder.getInstance().removeMiniWindowTask(task);
                    TopActivityRecorder.getInstance().removePinnedWindowTask(task);
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

    private void moveHyperOsTaskToBack(Task task, int reason) {
        final Task root = task != null ? task.getRootTask() : null;
        if (root == null || !root.isAttached()
                || !root.getWindowConfiguration().isPopUpWindowMode()) return;
        final TaskWindowSurfaceInfo info = root.mWindowContainerExt.getTaskWindowSurfaceInfo();
        if (info == null || info.isHyperOsClosing()) return;
        final int generation = info.getHyperOsCloseGeneration() + 1;
        TopActivityRecorder.getInstance().removeMiniWindowTask(root);
        TopActivityRecorder.getInstance().removePinnedWindowTask(root);
        info.playExitAnimation(reason == MOVE_TO_BACK_FROM_LEAVE_BUTTON,
                info.getWindowSurfaceRealScale(), () -> {
                    synchronized (mAtmService.mGlobalLock) {
                        if (!isPendingHyperOsClose(root, info, generation)) return;
                        root.setAlwaysOnTop(false);
                        // This retains the normal LockTask/watcher checks and Shell's task
                        // history, visibility and focus handling. Success can mean queued.
                        if (!root.moveTaskToBack(root)) {
                            cancelHyperOsTaskClose(root, info);
                            return;
                        }
                        mPendingHyperOsCloses.put(root, generation);
                        finishHyperOsTaskClose(root, info, generation);
                    }
                });
    }

    private boolean isPendingHyperOsClose(Task task, TaskWindowSurfaceInfo info, int generation) {
        return task.isAttached() && task.getWindowConfiguration().isPopUpWindowMode()
                && info.isHyperOsClosing() && info.getHyperOsCloseGeneration() == generation;
    }

    private void finishHyperOsTaskClose(Task task, TaskWindowSurfaceInfo info, int generation) {
        if (!isPendingHyperOsClose(task, info, generation)) {
            mPendingHyperOsCloses.remove(task);
            return;
        }
        // Move-to-back can still be queued. Finish/idle callbacks retry after WM commits
        // visibility; never restore fullscreen merely because moveTaskToBack returned true.
        if (task.mTransitionController.inTransition()) return;
        mPendingHyperOsCloses.remove(task);
        if (task.isVisibleRequested()) {
            // A deferred move can be rejected, or this task can be opened again meanwhile.
            cancelHyperOsTaskClose(task, info);
            return;
        }
        mAtmService.deferWindowLayout();
        try {
            if (task.mSurfaceControl != null && task.mSurfaceControl.isValid()) {
                task.getSyncTransaction().hide(task.mSurfaceControl);
            }
            // Restore launch geometry only once the task is in the background. Keep it
            // hidden while resetting alpha so a later normal launch can display it again.
            tryExitPopUpView(task, true, true, true);
            if (task.mSurfaceControl != null && task.mSurfaceControl.isValid()) {
                task.getSyncTransaction().setAlpha(task.mSurfaceControl, 1f)
                        .hide(task.mSurfaceControl);
            }
        } finally {
            mAtmService.continueWindowLayout();
        }
    }

    private void schedulePendingHyperOsCloses() {
        if (mPendingHyperOsCloses.isEmpty()) return;
        mHandler.post(() -> {
            synchronized (mAtmService.mGlobalLock) {
                final ArrayList<Task> tasks = new ArrayList<>(mPendingHyperOsCloses.keySet());
                for (Task task : tasks) {
                    final Integer generation = mPendingHyperOsCloses.get(task);
                    if (generation == null) continue;
                    final TaskWindowSurfaceInfo info = task.mWindowContainerExt
                            .getTaskWindowSurfaceInfo();
                    if (info == null) {
                        mPendingHyperOsCloses.remove(task);
                    } else {
                        finishHyperOsTaskClose(task, info, generation);
                    }
                }
            }
        });
    }

    private void cancelHyperOsTaskClose(Task task, TaskWindowSurfaceInfo info) {
        mPendingHyperOsCloses.remove(task);
        info.cancelHyperOsClose();
        task.setAlwaysOnTop(true);
        if (task.getWindowConfiguration().isMiniExtWindowMode()) {
            TopActivityRecorder.getInstance().updateTopMiniWindowTask(task);
        } else {
            final ActivityRecord top = task.getTopNonFinishingActivity();
            if (top != null) TopActivityRecorder.getInstance().updateTopPinnedWindowActivity(top);
        }
        HyperOsWindowDecor.getInstance().update(task);
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
                        mPendingHyperOsCloses.remove(rootTask);
                        HyperOsWindowDecor.getInstance().remove(rootTask);
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
                            TopActivityRecorder.getInstance().removeMiniWindowTask(rootTask);
                        }
                        if (removePin && !wasMiniWindow) {
                            TopActivityRecorder.getInstance().removePinnedWindowTask(rootTask);
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

    private void makeRoomForPinnedWindow(Task rootTask) {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) return;
        final Task existing = TopActivityRecorder.getInstance().getTopPinnedWindowTask();
        final Task existingRoot = existing != null ? existing.getRootTask() : null;
        if (existingRoot != null && existingRoot != rootTask) {
            TopActivityRecorder.getInstance().removePinnedWindowTask(existingRoot);
            if (existingRoot.isAttached()
                    && existingRoot.getWindowConfiguration().isPinnedExtWindowMode()) {
                tryExitPopUpView(existingRoot, true, false, true);
                existingRoot.moveTaskToBack(existingRoot);
            }
        }
    }

    private void makeRoomForMiniWindow(Task task) {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) return;
        final Task existing = TopActivityRecorder.getInstance()
                .getTopMiniWindowExceptTask(task);
        if (existing != null) {
            // Opening a second interactive window keeps the previous one as the mini.
            // Collect the incoming task first so both changes join its transition.
            task.mWindowContainerExt.prepareTransition();
            enterPinnedWindowingMode(existing);
        }
    }

    private boolean swapPopUpWindows(Task pinnedTask) {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) return false;
        if (pinnedTask == null
                || !pinnedTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            return false;
        }
        final TopActivityRecorder recorder = TopActivityRecorder.getInstance();
        final Task miniTask = recorder.getTopMiniWindowExceptTask(pinnedTask);
        if (miniTask == null || !pinnedTask.isAttached()
                || !miniTask.showToCurrentUser() || !pinnedTask.showToCurrentUser()
                || miniTask.getDisplayContent() != pinnedTask.getDisplayContent()
                || miniTask.getTopNonFinishingActivity() == null
                || pinnedTask.getTopNonFinishingActivity() == null) {
            return false;
        }
        final TaskWindowSurfaceInfo miniInfo =
                miniTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        final TaskWindowSurfaceInfo pinnedInfo =
                pinnedTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        if (miniInfo == null || pinnedInfo == null) {
            return false;
        }
        miniInfo.cancelPopUpViewAnimation();
        pinnedInfo.cancelPopUpViewAnimation();
        final TaskWindowSurfaceInfo miniPlacement =
                new TaskWindowSurfaceInfo(miniInfo, WINDOWING_MODE_MINI_WINDOW_EXT);
        miniPlacement.setFrozenTaskBounds(miniTask.getBounds());
        final TaskWindowSurfaceInfo pinnedPlacement =
                new TaskWindowSurfaceInfo(pinnedInfo, WINDOWING_MODE_PINNED_WINDOW_EXT);
        pinnedPlacement.setFrozenTaskBounds(pinnedTask.getBounds());

        mAtmService.deferWindowLayout();
        try {
            // MINI_WINDOW_EXT is the interactive window; PINNED_WINDOW_EXT is its mini.
            // Freeze both before changing either mode, then start one shared transition.
            pinnedTask.mWindowContainerExt.setPreFreezedWindowingMode(
                    WINDOWING_MODE_PINNED_WINDOW_EXT);
            miniTask.mWindowContainerExt.setPreFreezedWindowingMode(
                    WINDOWING_MODE_MINI_WINDOW_EXT);
            pinnedTask.mWindowContainerExt.prepareTransition();
            miniTask.mWindowContainerExt.prepareTransition();
            pinnedTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
            miniTask.setWindowingMode(WINDOWING_MODE_PINNED_WINDOW_EXT);
            pinnedInfo.setWindowPlacementFrom(miniPlacement);
            miniInfo.setWindowPlacementFrom(pinnedPlacement);
            pinnedTask.setAlwaysOnTop(true);
            miniTask.setAlwaysOnTop(true);
            recorder.setPopUpWindowTasks(pinnedTask, miniTask);
            mAtmService.setFocusedTask(pinnedTask.mTaskId);
            if (pinnedTask.mTransitionController.isShellTransitionsEnabled()) {
                // The joining task's geometry must be ready before the owner starts.
                miniTask.mWindowContainerExt.scheduleTransition();
                pinnedTask.mWindowContainerExt.scheduleTransition();
            } else {
                miniInfo.scheduleTransition(miniPlacement,
                        miniTask.getDisplayContent().getDisplayInfo());
                pinnedInfo.scheduleTransition(pinnedPlacement,
                        pinnedTask.getDisplayContent().getDisplayInfo());
            }
        } finally {
            mAtmService.continueWindowLayout();
        }
        return true;
    }

    void enterMiniWindowingMode(WindowState win) {
        synchronized (mAtmService.mGlobalLock) {
            if (win != null) {
                if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                    setHyperOsWindowingMode(win.getTask(), WINDOWING_MODE_MINI_WINDOW_EXT);
                    return;
                }
                final Task task = win.getTask();
                final Task rootTask = task != null ? task.getRootTask() : null;
                if (rootTask == null) {
                    Slog.e(TAG, "enterMiniWindowingMode: the windowState doesn't have a root task. rootTask=" + rootTask);
                    return;
                }
                if (swapPopUpWindows(rootTask)) {
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
                    makeRoomForMiniWindow(rootTask);
                }
                rootTask.mWindowContainerExt.prepareTransition();
                rootTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
                TopActivityRecorder.getInstance().updateTopMiniWindowTask(rootTask);
                mAtmService.setFocusedTask(rootTask.mTaskId);
                rootTask.mWindowContainerExt.scheduleTransition();
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
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                setHyperOsWindowingMode(rootTask, WINDOWING_MODE_PINNED_WINDOW_EXT);
                return;
            }
            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info != null) {
                info.cancelPopUpViewAnimation();
            }
            makeRoomForPinnedWindow(rootTask);
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
            TopActivityRecorder.getInstance().removeMiniWindowTask(rootTask);
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
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                setHyperOsWindowingMode(task, WINDOWING_MODE_MINI_WINDOW_EXT);
                return;
            }
            if (task == null) {
                return;
            }
            final Task rootTask = task.getRootTask();
            if (rootTask == null) {
                return;
            }
            if (swapPopUpWindows(rootTask)) {
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
                makeRoomForMiniWindow(rootTask);
            }
            rootTask.mWindowContainerExt.prepareTransition();
            rootTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
            TopActivityRecorder.getInstance().updateTopMiniWindowTask(rootTask);
            rootTask.mWindowContainerExt.scheduleTransition();
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
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            setHyperOsWindowingMode(task, windowingMode);
            return;
        }
        if (task != null) {
            final Task rootTask = task.getRootTask();
            if (rootTask == null) {
                return;
            }

            boolean skipPrepareTransition = false;
            if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                if (swapPopUpWindows(rootTask)) {
                    return;
                }
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
                    makeRoomForMiniWindow(task);
                }
            } else if (WindowConfiguration.isPinnedExtWindowMode(windowingMode)) {
                makeRoomForPinnedWindow(rootTask);
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
                    TopActivityRecorder.getInstance().updateTopMiniWindowTask(rootTask);
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
                    TopActivityRecorder.getInstance().removeMiniWindowTask(rootTask);
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

    /** Every HyperOS window owns its mode, placement and decoration independently. */
    private void setHyperOsWindowingMode(Task task, int windowingMode) {
        synchronized (mAtmService.mGlobalLock) {
            if (task == null || !task.isAttached()) return;
            final Task root = task.getRootTask();
            final TaskWindowSurfaceInfo info = root.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info == null || root.getDisplayContent() == null) return;
            final ActivityRecord activity = root.getTopNonFinishingActivity();
            if (!PopUpSettingsConfig.getInstance().isEnabled()
                    || (activity != null && !PopUpSettingsConfig.getInstance()
                            .isPackageAllowed(activity.packageName))) return;
            mPendingHyperOsCloses.remove(root);
            info.cancelHyperOsClose();
            if (root.getWindowingMode() == windowingMode
                    && !info.getTaskWindowSurfaceBounds().isEmpty()
                    && root.isAlwaysOnTop() && info.hasHyperOsPlacement()) {
                if (windowingMode == WINDOWING_MODE_MINI_WINDOW_EXT) focusHyperOsTask(root);
                HyperOsWindowDecor.getInstance().update(root);
                return;
            }
            final boolean firstEntry = !root.getWindowConfiguration().isPopUpWindowMode()
                    || !root.isAlwaysOnTop() || !info.hasHyperOsPlacement();
            // Both popup modes keep the same app layout. Animate their surface and chrome
            // together on the existing frame path instead of hiding chrome for a Shell resize.
            final boolean animateDecor = !firstEntry && !info.isDockedToEdge()
                    && !root.mTransitionController.isCollecting()
                    && !root.mTransitionController.inPlayingTransition(root);
            final Rect startBounds = info.getHyperOsDecorBounds();
            final float startScale = (float) startBounds.width()
                    / Math.max(1, root.getBounds().width());
            final float startExpansion = info.getHyperOsDecorExpansion();
            if (info.isDockedToEdge()) info.restoreFromDocked();
            info.cancelPopUpViewAnimation();
            final Rect bounds = new Rect(root.getBounds());
            WindowResizingAlgorithm.getPopUpViewDefalutBounds(bounds);
            mAtmService.deferWindowLayout();
            try {
                if (!animateDecor) root.mWindowContainerExt.prepareTransition();
                // A fullscreen root discards override bounds. Enter popup mode first so
                // the app is laid out at the popup aspect ratio, including direct previews.
                root.setWindowingMode(windowingMode);
                root.setAlwaysOnTop(true);
                root.setBounds(bounds);
                if (firstEntry) {
                    info.setHyperOsInitialPlacement(getHyperOsInitialCenter(root, bounds));
                }
                if (windowingMode == WINDOWING_MODE_MINI_WINDOW_EXT) {
                    TopActivityRecorder.getInstance().removePinnedWindowTask(root);
                    TopActivityRecorder.getInstance().updateTopMiniWindowTask(root);
                    focusHyperOsTask(root);
                } else {
                    TopActivityRecorder.getInstance().removeMiniWindowTask(root);
                    final ActivityRecord top = root.getTopNonFinishingActivity();
                    if (top != null) {
                        TopActivityRecorder.getInstance().updateTopPinnedWindowActivity(top);
                    }
                    updateFocusedApp();
                }
                if (animateDecor) {
                    info.playHyperOsModeAnimation(startBounds, startScale, startExpansion);
                } else {
                    root.mWindowContainerExt.scheduleTransition();
                }
            } finally {
                mAtmService.continueWindowLayout();
            }
            HyperOsWindowDecor.getInstance().update(root);
        }
    }

    private Point getHyperOsInitialCenter(Task task, Rect bounds) {
        final Rect display = task.getDisplayContent().getBounds();
        final Point center = WindowResizingAlgorithm.getDefaultMiniWindowCenter(
                display, bounds, task.getConfiguration().orientation);
        final int[] count = {0};
        task.getDisplayContent().forAllRootTasks((Task other) -> {
            if (other != task && other.showToCurrentUser()
                    && other.getWindowConfiguration().isPopUpWindowMode()) count[0]++;
        });
        final int offset = Math.round(28 * mContext.getResources().getDisplayMetrics().density)
                * (count[0] % 5);
        center.offset((count[0] % 2 == 0 ? -1 : 1) * offset / 2, offset);
        return center;
    }

    void focusHyperOsTask(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            if (!PopUpSettingsConfig.getInstance().isHyperOsMode() || task == null) return;
            final Task root = task.getRootTask();
            if (!root.isAttached() || !root.showToCurrentUser()
                    || !root.getWindowConfiguration().isPopUpWindowMode()) return;
            final TransitionController controller = root.mTransitionController;
            final boolean needsReorder = root.getParent().getTopChild() != root;
            // Shell owns root-task layers. Keep an order-only transition even when the app
            // is already resumed: setFocusedTask may otherwise return without committing
            // the new surface order. Recording the display also updates decoration layers.
            final Transition transition = needsReorder && controller.isShellTransitionsEnabled()
                    && !controller.isCollecting()
                    ? controller.createTransition(TRANSIT_TO_FRONT) : null;
            root.moveToFront("hyperos-small-window-touch");
            if (root.getWindowConfiguration().isMiniExtWindowMode()) {
                mAtmService.setFocusedTask(root.mTaskId);
                TopActivityRecorder.getInstance().updateTopMiniWindowTask(root);
            }
            if (transition != null) {
                controller.requestStartTransition(transition, null /* startTask */,
                        null /* remote */, null /* display */);
                transition.setAllReady();
            }
            HyperOsWindowDecor.getInstance().update(root);
        }
    }

    void closeHyperOsTask(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            if (task == null || !task.isAttached()
                    || !task.getWindowConfiguration().isPopUpWindowMode()) return;
            final TaskWindowSurfaceInfo info = task.getRootTask().mWindowContainerExt
                    .getTaskWindowSurfaceInfo();
            if (info == null || info.isHyperOsClosing()) return;
            moveActivityTaskToBack(task.getRootTask(), MOVE_TO_BACK_FROM_LEAVE_BUTTON);
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
        // The notification precedes Shell's final visibility commit; posting exits that stack.
        schedulePendingHyperOsCloses();
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

        final TopActivityRecorder recorder = TopActivityRecorder.getInstance();
        final int targetUser = request.activityInfo != null
                ? UserHandle.getUserId(request.activityInfo.applicationInfo.uid) : request.userId;
        final boolean fullscreenTarget = isTaskApplication(
                recorder.getTopFullscreenTask(), targetPackage, targetUser);
        final boolean miniTarget = isTaskApplication(
                recorder.getTopMiniWindowTask(), targetPackage, targetUser);
        final boolean pinnedTarget = isTaskApplication(
                recorder.getTopPinnedWindowTask(), targetPackage, targetUser);

        // Keep explicit sidebar/recents requests even when this instance is visible:
        // selecting its icon can restore a preview or convert fullscreen to a popup.
        // SafeActivityOptions still enforces the trusted caller and package policy.

        if (request.activityOptions != null && request.activityOptions.isFromNotification()
                && request.activityOptions.isMiniWindowingMode()) {
            if (miniTarget) {
                return;
            }

            if (!request.activityOptions.isSmallWindowGesture()
                    && (fullscreenTarget || pinnedTarget)) {
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

        if (isTaskApplication(recorder.getTopMiniWindowTask(), callerPackage,
                UserHandle.getUserId(request.callingUid))) {
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

    private boolean isTaskApplication(Task task, String packageName, int userId) {
        synchronized (mService.mGlobalLock) {
            final ActivityRecord activity = task != null && task.isAttached()
                    ? task.getTopNonFinishingActivity() : null;
            return activity != null && activity.mUserId == userId
                    && activity.packageName.equals(packageName);
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
        final Task miniTask = getMiniWindowTask();
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
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            return TopActivityRecorder.getInstance().getTopMiniWindowTask();
        }
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
            exitMiniWindowingMode(getMiniWindowTask());
        }
    }

    void exitMiniWindowingMode(Task task) {
        synchronized (mAtmService.mGlobalLock) {
            final Task rootTask = task != null ? task.getRootTask() : null;
            final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
            if (rootTask != null && (rootTask.getWindowConfiguration().isMiniExtWindowMode()
                    || (hyperOs && rootTask.getWindowConfiguration().isPopUpWindowMode()))) {
                if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                    TopActivityRecorder.getInstance().removeMiniWindowTask(rootTask);
                    TopActivityRecorder.getInstance().removePinnedWindowTask(rootTask);
                } else {
                    TopActivityRecorder.getInstance().moveTopMiniToFull();
                }
                setTryExitWindowingMode(true);
                try {
                    tryExitPopUpView(rootTask, false, true, true);
                    if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                        rootTask.moveToFront("hyperos-small-window-fullscreen");
                        mAtmService.setFocusedTask(rootTask.mTaskId);
                    }
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
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                TopActivityRecorder.getInstance().removePinnedWindowTask(rootTask);
            } else {
                TopActivityRecorder.getInstance().moveTopPinnedToFull();
            }
            setTryExitWindowingMode(true);
            try {
                tryExitPopUpView(rootTask, false, false, true);
            } finally {
                setTryExitWindowingMode(false);
            }
        }
    }

    void enterMiniWindowingModeFromPinned(WindowState win) {
        synchronized (mAtmService.mGlobalLock) {
            if (win != null && PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                setHyperOsWindowingMode(win.getTask(), WINDOWING_MODE_MINI_WINDOW_EXT);
                return;
            }
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
            if (swapPopUpWindows(rootTask)) {
                return;
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
            TopActivityRecorder.getInstance().updateTopMiniWindowTask(rootTask);
            mAtmService.setFocusedTask(rootTask.mTaskId);
            rootTask.mWindowContainerExt.scheduleTransition();
        }
    }

    private boolean mMiniWindowHasInputFocus = false;

    boolean shouldMiniWindowHandleInput() {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) return isMiniWindowFocused();
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

            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                synchronized (mService.mGlobalLock) {
                    final DisplayContent dc = getDefaultDisplayContent();
                    final int x = (int) event.getRawX();
                    final int y = (int) event.getRawY();
                    if (dc == null || isShadeExpanded(dc) || isInExcludeArea(dc, x, y)) return;
                    final HyperOsWindowDecor decor = HyperOsWindowDecor.getInstance();
                    final Task touched = dc.getRootTask(root -> {
                        final TaskWindowSurfaceInfo info = root.mWindowContainerExt
                                .getTaskWindowSurfaceInfo();
                        return root.getWindowConfiguration().isPopUpWindowMode()
                                && root.isVisible() && root.showToCurrentUser() && info != null
                                && (info.getHyperOsDecorBounds().contains(x, y)
                                        || decor.containsInputPoint(root, x, y));
                    });
                    // Let the decor own focus and gesture capture for its handles/menu.
                    // Starting a transition here can race its ACTION_DOWN handling.
                    if (touched != null
                            && touched.getWindowConfiguration().isMiniExtWindowMode()
                            && !decor.containsInputPoint(touched, x, y)) {
                        final TaskWindowSurfaceInfo info = touched.mWindowContainerExt
                                .getTaskWindowSurfaceInfo();
                        // An animating top window still occludes the windows behind it.
                        // Do not let its touch fall through and raise a lower task.
                        if (!info.isSurfaceAnimating() && !info.isHyperOsClosing()) {
                            focusHyperOsTask(touched);
                        }
                    }
                }
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
