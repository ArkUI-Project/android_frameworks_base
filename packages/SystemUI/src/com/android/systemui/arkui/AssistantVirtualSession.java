/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.RemoteException;
import android.view.Display;

/** A rendered, independently focused display owned by one leased assistant session. */
final class AssistantVirtualSession {
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread drain;
    private PowerManager.WakeLock wake;

    int displayId() { return display == null ? Display.DEFAULT_DISPLAY : display.getDisplay().getDisplayId(); }

    void start(Context context, Handler main, ActivityManager.RunningTaskInfo task) {
        if (task == null || task.hasParentTask()
                || task.getWindowingMode() != WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                || task.getActivityType() != WindowConfiguration.ACTIVITY_TYPE_STANDARD) {
            throw new IllegalStateException("Background mode requires a fullscreen app");
        }
        try {
            boolean rootExists = false;
            for (var root : ActivityTaskManager.getService().getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)) {
                if (root.taskId == task.taskId) rootExists = true;
            }
            if (!rootExists) throw new IllegalStateException("App task disappeared");
            var bounds = task.configuration.windowConfiguration.getBounds();
            float scale = Math.min(1f, 720f / Math.max(320, Math.min(bounds.width(), bounds.height())));
            int width = Math.max(320, Math.round(bounds.width() * scale));
            int height = Math.max(320, Math.round(bounds.height() * scale));
            int density = Math.max(120, Math.round(context.getResources().getDisplayMetrics().densityDpi * scale));
            drain = new HandlerThread("ArkAssistantFrames"); drain.start();
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                try (var image = source.acquireLatestImage()) { }
                catch (IllegalStateException ignored) { }
            }, new Handler(drain.getLooper()));
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED;
            var config = new VirtualDisplayConfig.Builder("ArkAI background", width, height, density)
                    .setSurface(reader.getSurface()).setFlags(flags).setRequestedRefreshRate(30).build();
            display = context.getSystemService(DisplayManager.class).createVirtualDisplay(null, config, null, main);
            if (display == null) throw new IllegalStateException("No virtual display");
            wake = context.getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ArkAI:Background", displayId());
            wake.acquire(30 * 60 * 1000L);
            ActivityTaskManager.getService().moveRootTaskToDisplay(task.taskId, displayId());
        } catch (RemoteException | RuntimeException e) {
            finish(false);
            throw new IllegalStateException("Cannot start background mode", e);
        }
    }

    void finish(boolean takeOver) {
        if (display != null) {
            try {
                // Return every task, including apps opened by the assistant. Completion must not
                // interrupt whatever the user is doing on the physical display.
                var tasks = ActivityTaskManager.getService().getAllRootTaskInfosOnDisplay(displayId());
                // The list is top-first. When taking over, restore the current app last/on top.
                if (takeOver) java.util.Collections.reverse(tasks);
                for (var task : tasks) {
                    ActivityTaskManager.getService().moveRootTaskToDisplayOnTopOrBottom(
                            task.taskId, Display.DEFAULT_DISPLAY, takeOver);
                }
            } catch (RemoteException | RuntimeException ignored) { }
        }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        if (display != null) display.release();
        display = null;
        if (reader != null) reader.close();
        reader = null;
        if (drain != null) drain.quitSafely();
        drain = null;
    }
}
