/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded, user-recorded gestures. Every event is targeted to the captured game's UID. */
final class GameComboPlayer {
    private final Context context;
    private final Handler handler;
    private final Supplier<ActivityManager.RunningTaskInfo> task;
    private final Consumer<Boolean> state;
    private int[] actions;
    private int index,repeat,uid,taskId,rotation;
    private long down,start;
    private boolean touching;
    private float x,y;
    private Rect bounds;
    private final Runnable tick=this::step;
    GameComboPlayer(Context c,Handler h,Supplier<ActivityManager.RunningTaskInfo> t,Consumer<Boolean> s) { context=c; handler=h; task=t; state=s; }
    boolean play(ActivityManager.RunningTaskInfo target,int[] recorded,int repeats) {
        stop();
        if (recorded==null || recorded.length==0 || recorded.length>180 || recorded.length%6!=0 || target.topActivityInfo==null) return false;
        long duration=0;
        for (int i=0;i<recorded.length;i+=6) {
            for (int j=0;j<4;j++) if (recorded[i+j]<0 || recorded[i+j]>10000) return false;
            if (recorded[i+4]<40 || recorded[i+4]>2000 || recorded[i+5]<100 || recorded[i+5]>3000) return false;
            duration+=recorded[i+4]+recorded[i+5];
        }
        if (duration*repeats>120_000) return false;
        actions=recorded.clone(); repeat=repeats; index=0; uid=target.topActivityInfo.applicationInfo.uid; taskId=target.taskId;
        var metrics=context.getSystemService(WindowManager.class).getCurrentWindowMetrics(); bounds=metrics.getBounds();
        Rect safe=new Rect(bounds); safe.inset(metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()));
        for (int i=0;i<actions.length;i+=6) {
            if (!safe.contains(px(actions[i]),py(actions[i+1])) || !safe.contains(px(actions[i+2]),py(actions[i+3]))) { actions=null; return false; }
        }
        rotation=rotation(); start=SystemClock.uptimeMillis(); state.accept(true); handler.postDelayed(tick,500); return true;
    }
    private int rotation() { return context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRotation(); }
    private int px(int value) { return bounds.left+Math.round(value*bounds.width()/10000f); }
    private int py(int value) { return bounds.top+Math.round(value*bounds.height()/10000f); }
    private void step() {
        if (actions==null) return;
        var current=task.get();
        if (current==null || current.taskId!=taskId || rotation()!=rotation || SystemClock.uptimeMillis()-start>120_000) { stop(); return; }
        int offset=index*6; long now=SystemClock.uptimeMillis();
        if (!touching) {
            down=now; x=px(actions[offset]); y=py(actions[offset+1]); touching=true;
            if (!inject(MotionEvent.ACTION_DOWN)) { stop(); return; }
        } else {
            float fraction=Math.min(1,(now-down)/(float)actions[offset+4]);
            x=px(actions[offset])+(px(actions[offset+2])-px(actions[offset]))*fraction;
            y=py(actions[offset+1])+(py(actions[offset+3])-py(actions[offset+1]))*fraction;
            if (fraction>=1) {
                inject(MotionEvent.ACTION_UP); touching=false;
                index++;
                if (index==actions.length/6) { index=0; if (--repeat==0) { stop(); return; } }
                handler.postDelayed(tick,actions[index*6+5]); return;
            }
            if (!inject(MotionEvent.ACTION_MOVE)) { stop(); return; }
        }
        handler.postDelayed(tick,32);
    }
    private boolean inject(int action) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN); event.setDisplayId(Display.DEFAULT_DISPLAY);
        try { return context.getSystemService(InputManager.class).injectInputEvent(event,InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,uid); }
        catch (RuntimeException e) { return false; } finally { event.recycle(); }
    }
    void stop() {
        handler.removeCallbacks(tick); boolean running=actions!=null;
        if (touching) inject(MotionEvent.ACTION_CANCEL);
        touching=false; actions=null; if (running) state.accept(false);
    }
}
