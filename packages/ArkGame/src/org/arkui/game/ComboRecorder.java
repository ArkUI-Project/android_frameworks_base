/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;
import static org.arkui.game.GameStyle.*;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import org.json.JSONArray;

/** Explicit, non-interactive recording layer; never observes another app's live input stream. */
final class ComboRecorder {
    private final Context context;
    private final WindowManager windows;
    private final Consumer<JSONArray> save;
    private final Runnable cancel;
    private final JSONArray actions=new JSONArray();
    private FrameLayout root;
    private TextView count;
    private TextView saveButton;
    ComboRecorder(Context c,WindowManager w,Consumer<JSONArray> save,Runnable cancel) {
        context=c; windows=w; this.save=save; this.cancel=cancel;
    }
    void show() {
        root=new FrameLayout(context); root.setBackgroundColor(0x18000000);
        root.addView(new CanvasView(context),new FrameLayout.LayoutParams(-1,-1));
        var controls=new LinearLayout(context); controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(context,8),dp(context,4),dp(context,8),dp(context,4));
        controls.setBackground(shape(surface(context),dp(context,28)));
        count=text(context,context.getString(R.string.touch_hint,0),14);
        controls.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button(context,context.getString(R.string.cancel),false,() -> { close(); cancel.run(); }));
        saveButton=button(context,context.getString(R.string.done),true,() -> { close(); save.accept(actions); });
        saveButton.setEnabled(false); saveButton.setAlpha(.4f); controls.addView(saveButton);
        var top=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP); top.leftMargin=dp(context,12); top.rightMargin=dp(context,12);
        top.topMargin=windows.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()).top+dp(context,8);
        root.addView(controls,top);
        var lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(0); lp.setTitle("ArkGame:recorder"); windows.addView(root,lp);
    }
    void close() {
        if (root!=null) try { windows.removeViewImmediate(root); } catch (IllegalArgumentException ignored) { }
        root=null;
    }
    private final class CanvasView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x,y,endX,endY;
        private long down,lastUp;
        private boolean pressed;
        CanvasView(Context c) { super(c); paint.setColor(c.getColor(com.android.internal.R.color.materialColorPrimary)); paint.setStrokeWidth(dp(c,3)); }
        @Override protected void onDraw(Canvas c) {
            if (actions.length()==0 && !pressed) return;
            c.drawLine(x,y,endX,endY,paint); c.drawCircle(x,y,dp(context,12),paint); c.drawCircle(endX,endY,dp(context,8),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getPointerCount()>1 || e.getActionMasked()==MotionEvent.ACTION_CANCEL) { pressed=false; return true; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x=endX=e.getX(); y=endY=e.getY(); down=SystemClock.uptimeMillis(); pressed=true; invalidate(); break;
                case MotionEvent.ACTION_MOVE:
                    if (pressed) { endX=e.getX(); endY=e.getY(); invalidate(); } break;
                case MotionEvent.ACTION_UP:
                    if (!pressed) break; pressed=false; endX=e.getX(); endY=e.getY();
                    int duration=Math.toIntExact(SystemClock.uptimeMillis()-down);
                    int delay=lastUp==0?500:Math.toIntExact(down-lastUp); lastUp=SystemClock.uptimeMillis();
                    var action=new JSONArray(); action.put(norm(x,getWidth())).put(norm(y,getHeight()))
                            .put(norm(endX,getWidth())).put(norm(endY,getHeight())).put(duration).put(delay);
                    actions.put(action); count.setText(context.getString(R.string.touch_hint,actions.length()));
                    saveButton.setEnabled(true); saveButton.setAlpha(1); invalidate(); break;
            } return true;
        }
        private int norm(float value,int size) { return Math.max(0,Math.min(10000,Math.round(value*10000/Math.max(1,size)))); }
    }
}
