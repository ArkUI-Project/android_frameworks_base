/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
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
import android.os.SystemClock;
import android.view.Display;

/** An explicit idle session has a rendered, resumed task on its own display group.
 * The public display's default removal policy returns content to the physical display even
 * after host death. User switch, service death and the return button all release resources. */
final class GameIdleSession {
    private final Context context;
    private final Handler main;
    private final Runnable changed;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread drain;
    private PowerManager.WakeLock wake;
    private int rootTask=-1;
    GameIdleSession(Context c,Handler h,Runnable changed) { context=c; main=h; this.changed=changed; }
    boolean isRunning() { return display!=null; }
    void start(ActivityManager.RunningTaskInfo task,boolean screenOff) {
        if (display!=null) return;
        if (task.hasParentTask() || task.getWindowingMode()!=android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
            throw new IllegalStateException("Idle requires a standalone fullscreen game");
        }
        try {
            for (var root:ActivityTaskManager.getService().getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)) {
                if (root.taskId==task.taskId) rootTask=root.taskId;
            }
            if (rootTask<0) throw new IllegalStateException("Game task disappeared");
            var bounds=task.configuration.windowConfiguration.getBounds();
            int width=Math.max(320,bounds.width()),height=Math.max(320,bounds.height());
            float scale=Math.min(1,720f/Math.min(width,height)); width=Math.round(width*scale); height=Math.round(height*scale);
            int density=Math.max(120,Math.round(context.getResources().getDisplayMetrics().densityDpi*scale));
            drain=new HandlerThread("ArkGameIdleFrames"); drain.start();
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
            reader.setOnImageAvailableListener(source -> { try (var image=source.acquireLatestImage()) { } catch (IllegalStateException ignored) { } },new Handler(drain.getLooper()));
            int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    |DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    |DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS|DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED;
            if (context.checkSelfPermission(android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                flags|=DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
            } else if (screenOff) throw new SecurityException("Screen-off idle is not enabled by this system");
            var config=new VirtualDisplayConfig.Builder("ArkUI game idle",width,height,density)
                    .setSurface(reader.getSurface()).setFlags(flags).setRequestedRefreshRate(30).build();
            display=context.getSystemService(DisplayManager.class).createVirtualDisplay(null,config,null,main);
            if (display==null) throw new IllegalStateException("No idle display");
            int displayId=display.getDisplay().getDisplayId();
            wake=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"ArkUI:GameIdle",displayId);
            wake.acquire(2*60*60*1000L);
            ActivityTaskManager.getService().moveRootTaskToDisplay(rootTask,displayId);
            changed.run(); main.postDelayed(timeout,2*60*60*1000L);
            if (screenOff) main.postDelayed(sleep,400);
        } catch (RemoteException|RuntimeException e) { finish(); throw new IllegalStateException("Cannot start game idle",e); }
    }
    private final Runnable sleep=this::sleepDisplay;
    private void sleepDisplay() {
        if (display!=null) context.getSystemService(PowerManager.class).goToSleep(Display.DEFAULT_DISPLAY,SystemClock.uptimeMillis(),PowerManager.GO_TO_SLEEP_REASON_APPLICATION,0);
    }
    private final Runnable timeout=this::finish;
    void reconcile() {
        if (display==null) return;
        try {
            for (var task:ActivityTaskManager.getService().getAllRootTaskInfosOnDisplay(display.getDisplay().getDisplayId())) {
                if (task.taskId==rootTask) return;
            }
            // Launching the game from the launcher can already have returned it. Release the
            // empty display and its wake lock without moving another task or stealing focus.
            rootTask=-1;
            finish();
        } catch (RemoteException e) { finish(); }
    }
    void finish() {
        main.removeCallbacks(sleep); main.removeCallbacks(timeout);
        if (display!=null && rootTask>=0) {
            try { ActivityTaskManager.getService().moveRootTaskToDisplay(rootTask,Display.DEFAULT_DISPLAY); }
            catch (RemoteException|RuntimeException ignored) { }
        }
        rootTask=-1;
        if (wake!=null && wake.isHeld()) wake.release(); wake=null;
        if (display!=null) display.release(); display=null;
        if (reader!=null) reader.close(); reader=null;
        if (drain!=null) drain.quitSafely(); drain=null;
        changed.run();
    }
}
