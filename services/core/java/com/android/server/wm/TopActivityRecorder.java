/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import static com.android.internal.arkui.DebugConstants.DEBUG_WMS_TOP_APP;

import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.util.Slog;

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

    private ActivityInfo mTopFullscreenActivity = null;
    private ArrayList<ActivityInfo> mTopMiniWindowActivity = new ArrayList<>();
    private ActivityInfo mTopPinnedWindowActivity = null;
    // HyperOS keeps every preview independently; the last entry is most recently focused.
    private final ArrayList<ActivityInfo> mPinnedWindowActivities = new ArrayList<>();

    private WindowManagerService mWms;

    private TopActivityRecorder() {}

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
            final Task focusedTask = task != null ? task : newFocus.getTask();
            if (focusedTask == null) {
                return;
            }
            final Task newTask = focusedTask.getWindowConfiguration().isPopUpWindowMode()
                    ? focusedTask.getRootTask() : focusedTask;
            final int windowingMode = newTask.getWindowConfiguration().getWindowingMode();
            if (WindowConfiguration.isMiniExtWindowMode(windowingMode)) {
                updateTopMiniWindowTask(newTask);
            } else if (WindowConfiguration.isPinnedExtWindowMode(windowingMode)) {
                if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                    updateTopPinnedWindowActivity(newFocus);
                } else {
                    mTopPinnedWindowActivity = new ActivityInfo(newFocus, newTask);
                    logD("Top pinned-window activity changed to " + newFocus);
                    PinnedWindowOverlayController.getInstance().setTask(newTask);
                }
            } else if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                    || windowingMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                final ComponentName oldComponent = getTopFullscreenComponentLocked();
                final ComponentName newComponent = newFocus.mActivityComponent;
                if (!newComponent.equals(oldComponent)
                        || mTopFullscreenActivity.task != newTask) {
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

    void updateTopMiniWindowTask(Task task) {
        synchronized (mFocusLock) {
            final Task rootTask = task.getRootTask();
            final ActivityRecord activity = rootTask.getTopNonFinishingActivity();
            final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
            if (hyperOs) {
                removeTaskFromList(mTopMiniWindowActivity, rootTask);
            } else {
                mTopMiniWindowActivity.clear();
            }
            if (activity != null) {
                mTopMiniWindowActivity.add(new ActivityInfo(activity, rootTask));
            }
            if (!hyperOs) {
                DimmerWindow.getInstance().setTask(rootTask);
            }
        }
    }

    void updateTopPinnedWindowActivity(ActivityRecord newActivity) {
        synchronized (mFocusLock) {
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                if (newActivity != null && newActivity.getTask() != null) {
                    final Task rootTask = newActivity.getRootTask();
                    removeTaskFromList(mPinnedWindowActivities, rootTask);
                    mPinnedWindowActivities.add(new ActivityInfo(newActivity, rootTask));
                }
                return;
            }
            if (mTopPinnedWindowActivity != null &&
                    newActivity != null &&
                    isSameRootTask(mTopPinnedWindowActivity.task, newActivity.getTask())) {
                mTopPinnedWindowActivity.componentName = newActivity.mActivityComponent;
                mTopPinnedWindowActivity.packageName = newActivity.packageName;
            } else if (newActivity != null) {
                mTopPinnedWindowActivity = new ActivityInfo(newActivity, newActivity.getRootTask());
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
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                final ActivityInfo activity = getTopActivityLocked(mTopMiniWindowActivity, false);
                return activity != null ? activity.packageName : "";
            }
            final int n = mTopMiniWindowActivity.size();
            if (n == 0) {
                return "";
            }
            return mTopMiniWindowActivity.get(n - 1).packageName;
        }
    }

    private Task getTopMiniWindowTaskLocked() {
        for (int i = mTopMiniWindowActivity.size() - 1; i >= 0; --i) {
            final Task task = mTopMiniWindowActivity.get(i).task;
            if (task.isAttached() && task.getWindowConfiguration().isMiniExtWindowMode()) {
                return task.getRootTask();
            }
        }
        return null;
    }

    Task getTopMiniWindowTask() {
        synchronized (mFocusLock) {
            return getTopMiniWindowTaskLocked();
        }
    }

    private ActivityInfo getTopActivityLocked(ArrayList<ActivityInfo> activities, boolean pinned) {
        for (int i = activities.size() - 1; i >= 0; --i) {
            final ActivityInfo activity = activities.get(i);
            final Task task = activity.task;
            if (task.isAttached() && (pinned
                    ? task.getWindowConfiguration().isPinnedExtWindowMode()
                    : task.getWindowConfiguration().isMiniExtWindowMode())) {
                return activity;
            }
            activities.remove(i);
        }
        return null;
    }

    private void removeTaskFromList(ArrayList<ActivityInfo> activities, Task task) {
        for (int i = activities.size() - 1; i >= 0; --i) {
            if (isSameRootTask(activities.get(i).task, task)
                    || !activities.get(i).task.isAttached()) {
                activities.remove(i);
            }
        }
    }

    private static boolean isSameRootTask(Task first, Task second) {
        return first != null && second != null
                && (first == second || first.getRootTask() == second.getRootTask());
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
            return PopUpSettingsConfig.getInstance().isHyperOsMode()
                    ? getTopMiniWindowTaskLocked() != null : !mTopMiniWindowActivity.isEmpty();
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
            boolean removed = false;
            for (int i = mTopMiniWindowActivity.size() - 1; i >= 0; --i) {
                if (isSameRootTask(mTopMiniWindowActivity.get(i).task, task)) {
                    final ActivityInfo ai = mTopMiniWindowActivity.remove(i);
                    logD("removeMiniWindowTask: " + ai);
                    removed = true;
                }
            }
            if (!PopUpSettingsConfig.getInstance().isHyperOsMode()
                    && (removed || isSameRootTask(DimmerWindow.getInstance().getTask(), task))) {
                DimmerWindow.getInstance().setTask(getTopMiniWindowTaskLocked());
            }
        }
    }

    void removePinnedWindowTask(Task task) {
        synchronized (mFocusLock) {
            removeTaskFromList(mPinnedWindowActivities, task);
            if (mTopPinnedWindowActivity != null
                    && isSameRootTask(mTopPinnedWindowActivity.task, task)) {
                mTopPinnedWindowActivity = null;
            }
            if (!PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                final PinnedWindowOverlayController overlay =
                        PinnedWindowOverlayController.getInstance();
                if (isSameRootTask(overlay.getTask(), task)) {
                    overlay.setTask(null);
                }
            }
        }
    }

    void setPopUpWindowTasks(Task miniTask, Task pinnedTask) {
        synchronized (mFocusLock) {
            // Publish both owners together after their modes and geometry have changed.
            updateTopMiniWindowTask(miniTask);
            final ActivityRecord pinnedActivity = pinnedTask.getTopNonFinishingActivity();
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                updateTopPinnedWindowActivity(pinnedActivity);
                return;
            }
            mTopPinnedWindowActivity = pinnedActivity != null
                    ? new ActivityInfo(pinnedActivity, pinnedTask) : null;
            PinnedWindowOverlayController.getInstance().setTask(pinnedTask);
        }
    }

    void moveTopMiniToFull() {
        synchronized (mFocusLock) {
            logD("moveTopMiniToFull");
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                // The caller may already have changed the task's windowing mode.
                final ActivityInfo activity = mTopMiniWindowActivity.isEmpty() ? null
                        : mTopMiniWindowActivity.get(mTopMiniWindowActivity.size() - 1);
                if (activity != null) {
                    mTopFullscreenActivity = new ActivityInfo(activity);
                    removeTaskFromList(mTopMiniWindowActivity, activity.task);
                }
                return;
            }
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
            if (!PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                DimmerWindow.getInstance().setTask(null);
            }
        }
    }

    Task moveTopPinnedToMini() {
        synchronized (mFocusLock) {
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                final ActivityInfo activity = mPinnedWindowActivities.isEmpty() ? null
                        : mPinnedWindowActivities.get(mPinnedWindowActivities.size() - 1);
                if (activity != null) {
                    removeTaskFromList(mPinnedWindowActivities, activity.task);
                    removeTaskFromList(mTopMiniWindowActivity, activity.task);
                    mTopMiniWindowActivity.add(new ActivityInfo(activity));
                }
                // Restoring one preview must not replace another interactive window.
                return null;
            }
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
            mPinnedWindowActivities.clear();
            if (!PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                PinnedWindowOverlayController.getInstance().setTask(null);
            }
        }
    }

    void moveTopPinnedToFull() {
        synchronized (mFocusLock) {
            logD("moveTopPinnedToFull");
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                final ActivityInfo activity = mPinnedWindowActivities.isEmpty() ? null
                        : mPinnedWindowActivities.get(mPinnedWindowActivities.size() - 1);
                if (activity != null) {
                    mTopFullscreenActivity = new ActivityInfo(activity);
                    removeTaskFromList(mPinnedWindowActivities, activity.task);
                }
                return;
            }
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
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                final ActivityInfo activity = getTopActivityLocked(mPinnedWindowActivities, true);
                return activity != null ? activity.task.getRootTask() : null;
            }
            return mTopPinnedWindowActivity != null ? mTopPinnedWindowActivity.task : null;
        }
    }

    String getTopPinnedWindowPackage() {
        synchronized (mFocusLock) {
            if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
                final ActivityInfo activity = getTopActivityLocked(mPinnedWindowActivities, true);
                return activity != null ? activity.packageName : "";
            }
            return mTopPinnedWindowActivity != null ? mTopPinnedWindowActivity.packageName : "";
        }
    }

    boolean isTaskTopMini(Task task) {
        synchronized (mFocusLock) {
            return isSameRootTask(getTopMiniWindowTaskLocked(), task);
        }
    }

    Task getTopMiniWindowExceptTask(Task task) {
        synchronized (mFocusLock) {
            final int n = mTopMiniWindowActivity.size();
            for (int i = n - 1; i >= 0; --i) {
                final Task candidate = mTopMiniWindowActivity.get(i).task;
                if (!isSameRootTask(candidate, task) && candidate.isAttached()
                        && candidate.getWindowConfiguration().isMiniExtWindowMode()) {
                    return candidate.getRootTask();
                }
            }
            // A newly created root may get its handle before its first activity attaches.
            final Task candidate = PopUpSettingsConfig.getInstance().isHyperOsMode()
                    ? null : DimmerWindow.getInstance().getTask();
            if (candidate != null && !isSameRootTask(candidate, task) && candidate.isAttached()
                    && candidate.getWindowConfiguration().isMiniExtWindowMode()) {
                return candidate.getRootTask();
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
            for (int i = mPinnedWindowActivities.size() - 1; i >= 0; --i) {
                if (packageName.equals(mPinnedWindowActivities.get(i).packageName)) {
                    mPinnedWindowActivities.remove(i);
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
