/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.arkui.wheel.WheelStyle.*;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.text.BreakIterator;

/** A screenshot remains in its original coordinates; selection uses actual OCR symbol bounds. */
final class RecognitionCanvas extends View {
    interface Listener { void changed(boolean imageSelected); void selectionChanged(Rect anchor); }
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF imageBounds = new RectF();
    private final RectF crop = new RectF();
    private final Path clip = new Path();
    private final Listener listener;
    private final int dim;
    private RecognitionResult result;
    private ValueAnimator wave;
    private float progress, scale, offsetX, offsetY, downX, downY;
    private int first = -1, last = -1, handle = -1, corner = -1;
    private boolean images, adjusting, moved;
    private final Runnable longPress = () -> {
        int hit = hit(downX, downY, false);
        if (!images && hit >= 0) { selectWord(hit); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
    };
    RecognitionCanvas(Context c, Bitmap bitmap, Listener listener) {
        super(c); this.bitmap = bitmap; this.listener = listener;
        dim = new WheelPreferences(c).get("recognition_dim", 40, 10, 70);
        setFocusableInTouchMode(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(c.getString(R.string.recognition_running));
    }
    void startWave() {
        if (!ValueAnimator.areAnimatorsEnabled() || !new WheelPreferences(getContext()).on("recognition_wave", true)) {
            progress = 1; invalidate(); return;
        }
        wave = ValueAnimator.ofFloat(0, 1); wave.setDuration(720);
        wave.setInterpolator(new android.view.animation.LinearInterpolator());
        wave.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); }); wave.start();
    }
    void result(RecognitionResult result) {
        this.result = result; setContentDescription(result.text.isEmpty() ? getContext().getString(R.string.recognition_empty) : result.text); invalidate();
    }
    void imageMode(boolean value) {
        images = value; adjusting = false; clearSelection(); crop.setEmpty(); listener.changed(false); invalidate();
    }
    boolean imageMode() { return images; }
    void adjustCrop() {
        images = true; adjusting = true; clearSelection();
        if (crop.isEmpty()) crop.set(bitmap.getWidth() * .12f, bitmap.getHeight() * .20f,
                bitmap.getWidth() * .88f, bitmap.getHeight() * .72f);
        listener.changed(true); invalidate();
    }
    Rect cropBounds() {
        Rect r = new Rect(); crop.roundOut(r); r.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()); return r;
    }
    private RectF mapped(Rect bounds) {
        return new RectF(offsetX + bounds.left * scale, offsetY + bounds.top * scale,
                offsetX + bounds.right * scale, offsetY + bounds.bottom * scale);
    }
    private RectF mapped(RectF bounds) {
        return new RectF(offsetX + bounds.left * scale, offsetY + bounds.top * scale,
                offsetX + bounds.right * scale, offsetY + bounds.bottom * scale);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = Math.min(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        offsetX = (w - bitmap.getWidth() * scale) / 2; offsetY = (h - bitmap.getHeight() * scale) / 2;
        imageBounds.set(offsetX, offsetY, w - offsetX, h - offsetY);
    }
    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK); paint.setShader(null); paint.setAlpha(255);
        c.drawBitmap(bitmap, null, imageBounds, paint);
        paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255 * (dim / 100f) * (.45f + .55f * progress)));
        c.drawRect(imageBounds, paint); paint.setAlpha(255);
        if (progress < 1) {
            float cx = getWidth() * .83f, cy = getHeight() * .94f;
            float radius = dp(getContext(), 10) + (float) Math.hypot(getWidth(), getHeight()) * (float) Math.pow(progress, 1.8);
            paint.setShader(new RadialGradient(cx, cy, Math.max(1, radius),
                    new int[] {0x00ffffff, 0x04ffffff, 0x30ffffff, 0xaaffffff, 0x00ffffff},
                    new float[] {0, .82f, .97f, .993f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        }
        if (result != null && progress >= .98f && !images) {
            Rect lineBounds = new Rect(); int line = -1;
            for (int i = 0; i < result.glyphs.size(); i++) {
                RecognitionResult.Glyph glyph = result.glyphs.get(i);
                if (glyph.line != line && !lineBounds.isEmpty()) { restoreText(c, lineBounds); lineBounds.setEmpty(); }
                line = glyph.line; lineBounds.union(glyph.bounds);
            }
            if (!lineBounds.isEmpty()) restoreText(c, lineBounds);
            // Highlight the original pixels instead of painting OCR text over the source font.
            // A line union also avoids darker seams when character boxes overlap (common in CJK).
            Rect selectedBounds = new Rect(); line = -1;
            for (int i = first; i >= 0 && i <= last; i++) {
                RecognitionResult.Glyph glyph = result.glyphs.get(i);
                if (glyph.line != line && !selectedBounds.isEmpty()) { highlight(c, selectedBounds); selectedBounds.setEmpty(); }
                line = glyph.line; selectedBounds.union(glyph.bounds);
            }
            if (!selectedBounds.isEmpty()) highlight(c, selectedBounds);
            if (first >= 0) { drawHandle(c, mapped(result.glyphs.get(first).bounds), true); drawHandle(c, mapped(result.glyphs.get(last).bounds), false); }
        }
        if (images && result != null) {
            for (Rect region : result.images) {
                RectF r = mapped(region); paint.setColor(primary(getContext())); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(getContext(), 2));
                c.drawRoundRect(r, dp(getContext(), 10), dp(getContext(), 10), paint); paint.setStyle(Paint.Style.FILL);
            }
        }
        if (images && !crop.isEmpty()) {
            RectF destination = mapped(crop);
            paint.setColor(Color.WHITE); paint.setAlpha(255);
            c.save(); c.clipRect(destination); c.drawBitmap(bitmap, null, imageBounds, paint); c.restore();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(getContext(), 2)); paint.setColor(primary(getContext()));
            c.drawRect(destination, paint); paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) c.drawCircle((i & 1) == 0 ? destination.left : destination.right,
                    i < 2 ? destination.top : destination.bottom, dp(getContext(), 6), paint);
        }
    }
    private void restoreText(Canvas c, Rect bounds) {
        RectF r = mapped(bounds); r.inset(-dp(getContext(), 2), -dp(getContext(), 2));
        clip.rewind(); clip.addRoundRect(r, dp(getContext(), 3), dp(getContext(), 3), Path.Direction.CW);
        c.save(); c.clipPath(clip); paint.setAlpha(255); c.drawBitmap(bitmap, null, imageBounds, paint); c.restore();
    }
    private void highlight(Canvas c, Rect bounds) {
        RectF r = mapped(bounds); r.inset(-dp(getContext(), 2), -dp(getContext(), 2));
        paint.setColor(primary(getContext())); paint.setAlpha(90);
        c.drawRoundRect(r, dp(getContext(), 3), dp(getContext(), 3), paint); paint.setAlpha(255);
    }
    private void drawHandle(Canvas c, RectF r, boolean start) {
        paint.setColor(primary(getContext())); float x = start ? r.left : r.right;
        c.drawRect(x - dp(getContext(), 1), r.top, x + dp(getContext(), 1), r.bottom + dp(getContext(), 10), paint);
        c.drawCircle(x, r.bottom + dp(getContext(), 10), dp(getContext(), 7), paint);
    }
    private int hit(float x, float y, boolean nearest) {
        if (result == null) return -1;
        float best = Float.MAX_VALUE; int found = -1;
        for (int i = 0; i < result.glyphs.size(); i++) {
            RectF r = mapped(result.glyphs.get(i).bounds); r.inset(-dp(getContext(), 3), -dp(getContext(), 5));
            if (r.contains(x, y)) return i;
            if (nearest) {
                float dx = Math.max(r.left - x, Math.max(0, x - r.right));
                float dy = Math.max(r.top - y, Math.max(0, y - r.bottom));
                float distance = dx * dx + 4 * dy * dy;
                if (distance < best) { best = distance; found = i; }
            }
        }
        return found;
    }
    private void selectWord(int glyphIndex) {
        RecognitionResult.Glyph glyph = result.glyphs.get(glyphIndex);
        BreakIterator words = BreakIterator.getWordInstance(); words.setText(result.text);
        int a = words.isBoundary(glyph.start) ? glyph.start : words.preceding(glyph.start);
        int b = words.following(glyph.start);
        first = last = glyphIndex;
        while (first > 0 && result.glyphs.get(first - 1).start >= a) first--;
        while (last + 1 < result.glyphs.size() && result.glyphs.get(last + 1).end <= b) last++;
        showActions(); invalidate();
    }
    String selectedText() {
        return first < 0 || result == null ? "" : result.text.substring(result.glyphs.get(first).start, result.glyphs.get(last).end);
    }
    void selectAll() {
        if (result == null || result.glyphs.isEmpty()) return;
        first = 0; last = result.glyphs.size() - 1; showActions(); invalidate();
    }
    void copy() {
        if (selectedText().isEmpty()) return;
        getContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getContext().getString(R.string.recognize), selectedText()));
        Toast.makeText(getContext(), R.string.text_copied, Toast.LENGTH_SHORT).show();
    }
    private void showActions() {
        if (first < 0) return;
        Rect r = new Rect(); mapped(result.glyphs.get(first).bounds).roundOut(r); listener.selectionChanged(r);
    }
    void clearSelection() {
        first = last = -1; listener.selectionChanged(null); invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (e.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) { removeCallbacks(longPress); handle = corner = -1; return true; }
        if (action == MotionEvent.ACTION_DOWN) {
            requestFocus(); downX = e.getX(); downY = e.getY(); moved = false; handle = corner = -1;
            if (!images && first >= 0) {
                RectF a = mapped(result.glyphs.get(first).bounds), b = mapped(result.glyphs.get(last).bounds);
                if (Math.hypot(downX - a.left, downY - a.bottom - dp(getContext(), 10)) < dp(getContext(), 26)) handle = 0;
                else if (Math.hypot(downX - b.right, downY - b.bottom - dp(getContext(), 10)) < dp(getContext(), 26)) handle = 1;
                if (handle >= 0) listener.selectionChanged(null);
            }
            if (images && !crop.isEmpty()) {
                RectF r = mapped(crop);
                for (int i = 0; i < 4; i++) if (Math.hypot(downX - ((i & 1) == 0 ? r.left : r.right), downY - (i < 2 ? r.top : r.bottom)) < dp(getContext(), 30)) corner = i;
            }
            if (!images && handle < 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(e.getX() - downX, e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) { moved = true; removeCallbacks(longPress); }
            if (handle >= 0) {
                int i = hit(e.getX(), e.getY() - dp(getContext(), 10), true);
                if (i >= 0) { if (handle == 0) first = Math.min(i, last); else last = Math.max(i, first); invalidate(); }
            } else if (corner >= 0) {
                float x = Math.max(0, Math.min(bitmap.getWidth(), (e.getX() - offsetX) / scale));
                float y = Math.max(0, Math.min(bitmap.getHeight(), (e.getY() - offsetY) / scale));
                if ((corner & 1) == 0) crop.left = Math.min(x, crop.right - 16); else crop.right = Math.max(x, crop.left + 16);
                if (corner < 2) crop.top = Math.min(y, crop.bottom - 16); else crop.bottom = Math.max(y, crop.top + 16);
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(longPress);
            if (handle >= 0) showActions();
            else if (images && corner < 0 && !moved && !adjusting && result != null) {
                for (Rect region : result.images) if (mapped(region).contains(e.getX(), e.getY())) {
                    crop.set(region); listener.changed(true); performHapticFeedback(HapticFeedbackConstants.CONFIRM); invalidate(); break;
                }
            } else if (!images && !moved && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                int i = hit(e.getX(), e.getY(), false); if (i >= 0) selectWord(i); else clearSelection();
            }
            handle = corner = -1; performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed() && keyCode == KeyEvent.KEYCODE_A) { selectAll(); return true; }
        if (event.isCtrlPressed() && keyCode == KeyEvent.KEYCODE_C) { copy(); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (result != null) { info.setText(result.text); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY); }
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_COPY) { copy(); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION && result != null && args != null) {
            int start = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT), end = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT);
            first = last = -1;
            for (int i = 0; i < result.glyphs.size(); i++) if (result.glyphs.get(i).end > start && result.glyphs.get(i).start < end) { if (first < 0) first = i; last = i; }
            if (first >= 0) showActions(); invalidate(); return true;
        }
        return super.performAccessibilityAction(action, args);
    }
    void dispose() { removeCallbacks(longPress); if (wave != null) wave.cancel(); }
}
