/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;

import static com.android.internal.arkui.DebugConstants.DEBUG_WMS_TOP_APP;

import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.os.Handler;
import android.util.Slog;

import com.android.server.ServiceThread;
import com.android.server.wm.PinnedWindowOverlayController;

import java.util.ArrayList;

public class TopActivityRecorder {

    private static final String TAG = "TopActivityRecorder";

    private static class InstanceHolder {
        private static TopActivityRecorder INSTANCE = new TopActivityRecorder();
    }

    public static TopActivityRecorder getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final Object mFocusLock = new Object();

    private final Handler mHandler;
    private final ServiceThread mServiceThread;

    private ActivityInfo mTopFullscreenActivity = null;
    private ArrayList<ActivityInfo> mTopMiniWindowActivity = new ArrayList<>();
    private ActivityInfo mTopPinnedWindowActivity = null;

    private WindowManagerService mWms;

    private TopActivityRecorder() {
        mServiceThread = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        mServiceThread.start();
        mHandler = new Handler(mServiceThread.getLooper());
    }

    void initWms(WindowManagerService wms) {
        mWms = wms;
    }

    void onAppFocusChanged(ActivityRecord focus, Task task) {
        synchronized (mFocusLock) {
            if (mWms == null) {
                logE("onAppFocusChanged called before initWms - mWms is null");
                return;
            }

            final DisplayContent dc = mWms.getDefaultDisplayContentLocked();
            final ActivityRecord newFocus = focus != null ? focus : dc.topRunningActivity();
            if (newFocus == null) {
                return;
            }
            final Task newTask = task != null ? task : newFocus.getTask();
            if (newTask == null) {
                return;
            }
            final int windowingMode = newTask.getWindowConfiguration().getWindowingMode();
            if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                boolean hasTask = false;
                for (ActivityInfo ai : mTopMiniWindowActivity) {
                    if (ai.task == newTask) {
                        hasTask = true;
                        ai.componentName = newFocus.mActivityComponent;
                        ai.packageName = newFocus.packageName;
                        break;
                    }
                }
                if (!hasTask) {
                    mTopMiniWindowActivity.add(new ActivityInfo(newFocus, newTask));
                }
                logD("Top mini-window activity changed to " + newFocus
                        + ", addedTaskBefore=" + hasTask);
                DimmerWindow.getInstance().setTask(newTask);
            } else if (WindowConfiguration.isPinnedExtWindowMode(windowingMode)) {
                mTopPinnedWindowActivity = new ActivityInfo(newFocus, newTask);
                logD("Top pinned-window activity changed to " + newFocus);
                PinnedWindowOverlayController.getInstance().setTask(newTask);
            } else if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                    || windowingMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                final ComponentName oldComponent = getTopFullscreenComponentLocked();
                final ComponentName newComponent = newFocus.mActivityComponent;
                if (!newComponent.equals(oldComponent)) {
                    if (mTopFullscreenActivity != null &&
                            mTopFullscreenActivity.task == newTask) {
                        mTopFullscreenActivity.componentName = newFocus.mActivityComponent;
                        mTopFullscreenActivity.packageName = newFocus.packageName;
                    } else {
                        mTopFullscreenActivity = new ActivityInfo(newFocus, newTask);
                    }
                    logD("Top fullscreen window activity changed to " + newFocus);
                }
            }
        }
    }

    void updateTopPinnedWindowActivity(ActivityRecord newActivity) {
        synchronized (mFocusLock) {
            if (mTopPinnedWindowActivity != null &&
                    newActivity != null &&
                    mTopPinnedWindowActivity.task == newActivity.getTask()) {
                mTopPinnedWindowActivity.componentName = newActivity.mActivityComponent;
                mTopPinnedWindowActivity.packageName = newActivity.packageName;
            } else if (newActivity != null) {
                mTopPinnedWindowActivity = new ActivityInfo(newActivity, newActivity.getTask());
            }
            if (mTopPinnedWindowActivity != null && mTopPinnedWindowActivity.task != null) {
                PinnedWindowOverlayController.getInstance().setTask(mTopPinnedWindowActivity.task);
                logD("Top pinned-window activity changed to " + newActivity);
            } else {
                PinnedWindowOverlayController.getInstance().setTask(null);
                logD("Top pinned-window activity changed to null");
            }
        }
    }

    private ComponentName getTopFullscreenComponentLocked() {
        if (mTopFullscreenActivity == null) {
            return null;
        }
        return mTopFullscreenActivity.componentName;
    }

    String getTopFullscreenPackage() {
        synchronized (mFocusLock) {
            if (mTopFullscreenActivity == null) {
                return "";
            }
            return mTopFullscreenActivity.packageName;
        }
    }

    Task getTopFullscreenTask() {
        synchronized (mFocusLock) {
            if (mTopFullscreenActivity == null) {
                return null;
            }
            return mTopFullscreenActivity.task;
        }
    }

    int getTopFullscreenTaskId() {
        synchronized (mFocusLock) {
            return getTopFullscreenTaskIdLocked();
        }
    }

    int getTopFullscreenTaskIdLocked() {
        if (mTopFullscreenActivity == null) {
            return INVALID_TASK_ID;
        }
        if (mTopFullscreenActivity.task == null) {
            return INVALID_TASK_ID;
        }
        return mTopFullscreenActivity.task.mTaskId;
    }

    String getTopMiniWindowPackage() {
        synchronized (mFocusLock) {
            final int n = mTopMiniWindowActivity.size();
            if (n == 0) {
                return "";
            }
            return mTopMiniWindowActivity.get(n - 1).packageName;
        }
    }

    private Task getTopMiniWindowTaskLocked() {
        final int n = mTopMiniWindowActivity.size();
        if (n == 0) {
            return null;
        }
        return mTopMiniWindowActivity.get(n - 1).task;
    }

    boolean isTopFullscreenActivityHome() {
        synchronized (mFocusLock) {
            if (mTopFullscreenActivity == null) {
                return false;
            }
            return mTopFullscreenActivity.isHome;
        }
    }

    boolean isPackageAtTop(String packageName) {
        return getTopFullscreenPackage().equals(packageName) ||
                getTopMiniWindowPackage().equals(packageName) ||
                getTopPinnedWindowPackage().equals(packageName);
    }

    public boolean hasMiniWindow() {
        synchronized (mFocusLock) {
            return mTopMiniWindowActivity.size() > 0;
        }
    }

    private String getPackageNameFromTask(Task task) {
        final ActivityRecord taskActivity = task.getActivity((r) -> true);
        if (taskActivity != null) {
            return taskActivity.packageName;
        }
        return "";
    }

    void removeMiniWindowTask(Task task) {
        synchronized (mFocusLock) {
            final int n = mTopMiniWindowActivity.size();
            for (int i = n - 1; i >= 0; --i) {
                if (mTopMiniWindowActivity.get(i).task == task) {
                    final ActivityInfo ai = mTopMiniWindowActivity.remove(i);
                    logD("removeMiniWindowTask: " + ai);
                    if (n == 1) {
                        DimmerWindow.getInstance().setTask(null);
                    } else {
                        DimmerWindow.getInstance().setTask(mTopMiniWindowActivity.get(n - 2).task);
                    }
                    return;
                }
            }
            logD("removeMiniWindowTask, unable to find task: " + task);
        }
    }

    void moveTopMiniToFull() {
        synchronized (mFocusLock) {
            logD("moveTopMiniToFull");
            final int n = mTopMiniWindowActivity.size();
            if (n > 0) {
                final ComponentName oldComponent = getTopFullscreenComponentLocked();
                mTopFullscreenActivity = new ActivityInfo(mTopMiniWindowActivity.get(n - 1));
                logD("Top fullscreen window activity changed to " + mTopFullscreenActivity);
            }
            mTopMiniWindowActivity.clear();
            DimmerWindow.getInstance().setTask(null);
        }
    }

    void clearMiniWindow() {
        synchronized (mFocusLock) {
            logD("clearMiniWindow");
            mTopMiniWindowActivity.clear();
            DimmerWindow.getInstance().setTask(null);
        }
    }

    void moveTopMiniToPinned(Task task) {
        synchronized (mFocusLock) {
            final int n = mTopMiniWindowActivity.size();
            if (n == 0) {
                return;
            }
            final Task prevPinnedTask = PinnedWindowOverlayController.getInstance().getTask();
            if (prevPinnedTask != null && prevPinnedTask != task) {
                prevPinnedTask.setAlwaysOnTop(false);
            }
            int targetIndex = n - 1;
            final Task rootTask = task != null ? task.getRootTask() : null;
            for (int i = n - 1; i >= 0; --i) {
                final Task miniTask = mTopMiniWindowActivity.get(i).task;
                final Task miniRootTask = miniTask != null ? miniTask.getRootTask() : null;
                if (miniTask == task || miniRootTask == rootTask) {
                    targetIndex = i;
                    break;
                }
            }
            mTopPinnedWindowActivity = new ActivityInfo(mTopMiniWindowActivity.get(targetIndex));
            logD("moveTopMiniToPinned: " + mTopPinnedWindowActivity);
            mTopMiniWindowActivity.clear();
            DimmerWindow.getInstance().setTask(null);
            mHandler.postDelayed(() -> {
                PinnedWindowOverlayController.getInstance().setTask(task);
                if (prevPinnedTask != null && prevPinnedTask != task) {
                    PopUpWindowController.getInstance().moveActivityTaskToBack(prevPinnedTask,
                            PopUpWindowController.MOVE_TO_BACK_NEW_PIN);
                }
            }, WindowChangeAnimationSpecExt.ANIMATION_DURATION_MODE_CHANGING);
        }
    }

    Task moveTopPinnedToMini() {
        synchronized (mFocusLock) {
            if (mTopPinnedWindowActivity == null) {
                return null;
            }
            final Task prevMiniTask = getTopMiniWindowTaskLocked();
            final Task pinnedTask = mTopPinnedWindowActivity.task;
            mTopMiniWindowActivity.clear();
            mTopMiniWindowActivity.add(new ActivityInfo(mTopPinnedWindowActivity));
            logD("moveTopPinnedToMini: " + mTopPinnedWindowActivity);
            DimmerWindow.getInstance().setTask(pinnedTask);
            mTopPinnedWindowActivity = null;
            PinnedWindowOverlayController.getInstance().setTask(null);
            if (prevMiniTask == pinnedTask) {
                return null;
            }
            return prevMiniTask;
        }
    }

    void clearPinnedWindow() {
        synchronized (mFocusLock) {
            logD("clearPinnedWindow");
            mTopPinnedWindowActivity = null;
            PinnedWindowOverlayController.getInstance().setTask(null);
        }
    }

    void moveTopPinnedToFull() {
        synchronized (mFocusLock) {
            logD("moveTopPinnedToFull");
            if (mTopPinnedWindowActivity != null) {
                mTopFullscreenActivity = new ActivityInfo(mTopPinnedWindowActivity);
                logD("Top fullscreen window activity changed to " + mTopFullscreenActivity);
            }
            mTopPinnedWindowActivity = null;
            PinnedWindowOverlayController.getInstance().setTask(null);
        }
    }

    Task getTopPinnedWindowTask() {
        synchronized (mFocusLock) {
            return mTopPinnedWindowActivity != null ? mTopPinnedWindowActivity.task : null;
        }
    }

    String getTopPinnedWindowPackage() {
        synchronized (mFocusLock) {
            return mTopPinnedWindowActivity != null ? mTopPinnedWindowActivity.packageName : "";
        }
    }

    boolean isTaskTopMini(Task task) {
        synchronized (mFocusLock) {
            final int n = mTopMiniWindowActivity.size();
            return n > 0 && mTopMiniWindowActivity.get(n - 1).task == task;
        }
    }

    Task getTopMiniWindowExceptTask(Task task) {
        synchronized (mFocusLock) {
            final int n = mTopMiniWindowActivity.size();
            for (int i = n - 1; i >= 0; --i) {
                if (mTopMiniWindowActivity.get(i).task != task) {
                    return mTopMiniWindowActivity.get(i).task;
                }
            }
            return null;
        }
    }

    void onForceStopPackage(String packageName) {
        synchronized (mFocusLock) {
            for (int i = mTopMiniWindowActivity.size() - 1; i >= 0; --i) {
                if (packageName.equals(mTopMiniWindowActivity.get(i).packageName)) {
                    mTopMiniWindowActivity.remove(i);
                }
            }
        }
    }

    private static final class ActivityInfo {
        ComponentName componentName;
        String packageName;
        Task task;
        boolean isHome;

        ActivityInfo(ActivityRecord r, Task task) {
            this.componentName = r.mActivityComponent;
            this.packageName = r.packageName;
            this.task = task;
            this.isHome = r.isActivityTypeHome();
        }

        ActivityInfo(ActivityInfo other) {
            this.componentName = other.componentName;
            this.packageName = other.packageName;
            this.task = other.task;
            this.isHome = other.isHome;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("componentName=").append(componentName);
            sb.append(", task=").append(task);
            sb.append(", isHome=").append(isHome);
            return sb.toString();
        }
    }

    private static void logD(String msg) {
        if (DEBUG_WMS_TOP_APP) {
            Slog.d(TAG, msg);
        }
    }

    private static void logE(String msg) {
        Slog.e(TAG, msg);
    }
}
