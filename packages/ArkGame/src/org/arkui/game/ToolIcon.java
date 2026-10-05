/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
final class ToolIcon extends Drawable {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    ToolIcon(Context c,int kind,boolean selected) {
        this.kind=kind; p.setColor(c.getColor(selected?com.android.internal.R.color.materialColorOnPrimaryContainer:com.android.internal.R.color.materialColorOnSurface));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        int size=GameStyle.dp(c,24); setBounds(0,0,size,size);
    }
    @Override public void draw(Canvas c) {
        c.save(); c.translate(getBounds().left,getBounds().top); c.scale(getBounds().width()/24f,getBounds().height()/24f);
        if(kind==R.string.guard) {
            Path path=new Path(); path.moveTo(12,2);path.lineTo(21,6);path.lineTo(19,15);path.quadTo(17,20,12,22);path.quadTo(7,20,5,15);path.lineTo(3,6);path.close();c.drawPath(path,p);
            c.drawLine(8,12,11,15,p);c.drawLine(11,15,17,9,p);
        } else if(kind==R.string.combo) {
            c.drawCircle(5,6,2,p);c.drawCircle(19,18,2,p);c.drawLine(7,6,17,6,p);c.drawLine(17,6,17,12,p);c.drawLine(17,12,7,12,p);c.drawLine(7,12,7,18,p);c.drawLine(7,18,17,18,p);
        } else if(kind==R.string.screenshot) {
            c.drawRoundRect(4,5,20,19,3,3,p);c.drawCircle(12,12,4,p);c.drawLine(2,2,7,2,p);c.drawLine(2,2,2,7,p);c.drawLine(17,22,22,22,p);c.drawLine(22,17,22,22,p);
        } else if(kind==R.string.record) {
            c.drawCircle(12,12,9,p);c.drawRoundRect(8,8,16,16,2,2,p);
        } else if(kind==R.string.screen_idle) {
            Path path=new Path();path.moveTo(16,3);path.cubicTo(3,0,0,17,12,21);path.cubicTo(17,22,21,18,21,15);path.cubicTo(9,19,7,9,16,3);c.drawPath(path,p);
        } else if(kind==R.string.background_idle) {
            c.drawRoundRect(3,3,17,15,3,3,p);c.drawRoundRect(8,9,22,21,3,3,p);c.drawLine(12,15,18,15,p);c.drawLine(15,12,18,15,p);c.drawLine(15,18,18,15,p);
        } else if(kind==R.string.timer) {
            c.drawCircle(12,14,8,p);c.drawLine(12,14,12,9,p);c.drawLine(9,2,15,2,p);c.drawLine(12,2,12,6,p);c.drawLine(18,5,20,7,p);
        } else {
            c.drawRoundRect(5,5,19,19,3,3,p);c.drawRect(9,9,15,15,p);
            for(int i=8;i<=16;i+=4){c.drawLine(i,2,i,5,p);c.drawLine(i,19,i,22,p);c.drawLine(2,i,5,i,p);c.drawLine(19,i,22,i,p);}
        } c.restore();
    }
    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
