/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.arkui.recognition.RecognitionStyle.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The frozen screen is kept in memory until an explicit copy, save or share action. */
final class RecognitionPanel extends FrameLayout implements RecognitionCanvas.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RecognitionEngine engine;
    private final Bitmap bitmap;
    private final ScreenContent content;
    private final RecognitionContent data;
    private final RecognitionCanvas canvas;
    private final Runnable closed;
    private final TextView hint;
    private final LinearLayout modes, selection;
    private final View detailsControl, bottomControls;
    private final FrameLayout mediaControls, sheet;
    private final OnBackInvokedCallback back = this::close;
    private RecognitionResult result;
    private WindowManager windows;
    private Insets safe = Insets.NONE;
    private volatile boolean disposed;
    private boolean exporting, started;

    RecognitionPanel(Context c, Bitmap bitmap, Runnable closed) { this(c, bitmap, null, closed); }
    RecognitionPanel(Context c, Bitmap bitmap, ScreenContent content, Runnable closed) {
        super(c); this.bitmap = bitmap; this.content = content; this.closed = closed;
        engine = new RecognitionEngine(c); data = new RecognitionContent(content, bitmap);
        setFocusableInTouchMode(true);
        canvas = new RecognitionCanvas(c, bitmap, this); addView(canvas, new LayoutParams(-1, -1));
        mediaControls = new FrameLayout(c); addView(mediaControls, new LayoutParams(-1, -1));
        detailsControl = icon(c, R.string.details, false, this::showDetails);
        LayoutParams detailBounds = new LayoutParams(dp(c, 48), dp(c, 48), Gravity.TOP | Gravity.END);
        detailBounds.setMargins(dp(c, 12), dp(c, 12), dp(c, 12), 0); addView(detailsControl, detailBounds);
        LinearLayout bottom = new LinearLayout(c); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setGravity(Gravity.CENTER);
        bottomControls = bottom;
        hint = text(c, c.getString(R.string.recognition_running), 12); hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8)); hint.setBackground(round(surface(c), dp(c, 20)));
        bottom.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        HorizontalScrollView scroller = new HorizontalScrollView(c); scroller.setHorizontalScrollBarEnabled(false);
        modes = new LinearLayout(c); modes.setGravity(Gravity.CENTER_VERTICAL); modes.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), 0);
        scroller.addView(modes, new HorizontalScrollView.LayoutParams(-2, -2));
        bottom.addView(scroller, new LinearLayout.LayoutParams(-2, -2));
        addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        selection = new LinearLayout(c); selection.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        selection.setBackground(round(surface(c), dp(c, 28))); selection.setElevation(dp(c, 16));
        selection.addView(chip(c, android.R.string.copy, true, () -> { canvas.copy(); canvas.clearSelection(); }));
        selection.addView(chip(c, android.R.string.selectAll, false, canvas::selectAll));
        selection.addView(chip(c, com.android.internal.R.string.share, false, () -> shareText(canvas.selectedText())));
        HorizontalScrollView selectedScroll = new HorizontalScrollView(c); selectedScroll.setHorizontalScrollBarEnabled(false);
        selectedScroll.addView(selection); selectedScroll.setVisibility(GONE);
        addView(selectedScroll, new LayoutParams(-2, -2));
        sheet = new FrameLayout(c); sheet.setBackgroundColor(0x88000000); sheet.setVisibility(GONE); addView(sheet, new LayoutParams(-1, -1));
        sheet.setOnClickListener(v -> sheet.setVisibility(GONE));
        setOnApplyWindowInsetsListener((v, insets) -> {
            safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            detailBounds.topMargin = safe.top + dp(c, 8); detailsControl.setLayoutParams(detailBounds);
            LayoutParams bottomBounds = (LayoutParams) bottom.getLayoutParams(); bottomBounds.bottomMargin = safe.bottom + dp(c, 8); bottom.setLayoutParams(bottomBounds);
            updateMediaControls(); return insets;
        });
        result = data.original; canvas.result(result); changed(false);
        addOnLayoutChangeListener((v, left, top, right, bottomEdge, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottomEdge - top != oldBottom - oldTop) {
                updateMediaControls();
            }
        });
    }
    void show(WindowManager windows) {
        this.windows = windows;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.setTitle("ArkRecognition:recognition"); lp.setFitInsetsTypes(0); lp.gravity = Gravity.TOP | Gravity.LEFT;
        windows.addView(this, lp); requestFocus(); start();
    }
    void start() {
        if (started) return; started = true;
        post(() -> {
            if (disposed) return;
            if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
            canvas.startWave(); updateMediaControls();
            if (!result.text.isEmpty()) hint.setText(R.string.recognition_ready);
        });
        worker.execute(() -> {
            Bitmap supplement = null;
            try {
                if (disposed) return;
                supplement = data.supplementalImage(bitmap);
                RecognitionResult found = data.merge(engine.recognize(supplement));
                main.post(() -> {
                    if (disposed) return;
                    result = found; canvas.result(found); updateMediaControls();
                    if (!canvas.imageMode()) hint.setText(found.text.isEmpty() ? R.string.recognition_empty : R.string.recognition_ready);
                });
            } catch (IOException | RuntimeException | LinkageError e) {
                android.util.Log.w("ArkRecognition", "Supplemental recognition failed", e);
                main.post(() -> { if (!disposed && result.text.isEmpty()) hint.setText(R.string.recognition_failed); });
            } finally { if (supplement != null && supplement != bitmap) supplement.recycle(); }
        });
    }
    @Override public void changed(boolean imageSelected) {
        modes.removeAllViews();
        addMode(chip(getContext(), R.string.text_mode, !canvas.imageMode(), () -> {
            canvas.imageMode(false); hint.setText(result.text.isEmpty() ? R.string.recognition_empty : R.string.recognition_ready);
        }));
        addMode(chip(getContext(), R.string.image_mode, canvas.imageMode(), () -> { canvas.imageMode(true); hint.setText(R.string.images_hint); }));
        addMode(icon(getContext(), R.string.more, false, this::showMore));
        addMode(icon(getContext(), R.string.close, false, this::dismiss));
        updateMediaControls();
    }
    private void addMode(View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(getContext(), 3), 0, dp(getContext(), 3), 0);
        modes.addView(view, lp);
    }
    private void updateMediaControls() {
        if (mediaControls == null || result == null || disposed) return;
        mediaControls.removeAllViews();
        if (getWidth() == 0) return;
        int size = dp(getContext(), 48), margin = dp(getContext(), 8);
        for (Rect region : result.images) {
            ScreenContentItem source = data.mediaFor(region);
            if (source != null && source.protectedContent) continue;
            boolean video = source != null && source.type == ScreenContentItem.TYPE_VIDEO;
            int saveLabel = video ? source.descriptor != null ? R.string.save_video : R.string.save_frame : R.string.save_image;
            LinearLayout controls = new LinearLayout(getContext()); controls.setBackground(round(surface(getContext()), dp(getContext(), 28)));
            controls.addView(icon(getContext(), video ? R.string.select_video : R.string.select_image, false,
                    () -> { canvas.selectImage(region); showMore(); }), new LinearLayout.LayoutParams(size, size));
            controls.addView(icon(getContext(), saveLabel, true, () -> { canvas.selectImage(region); export(0); }), new LinearLayout.LayoutParams(size, size));
            RectF target = canvas.mappedBounds(region);
            LayoutParams lp = new LayoutParams(size * 2, size);
            lp.leftMargin = Math.round(Math.max(margin, Math.min(getWidth() - size * 2 - margin, target.right - size * 2 - margin)));
            int bottomLimit = getHeight() - safe.bottom - bottomControls.getHeight() - size - margin * 2;
            lp.topMargin = Math.round(Math.max(safe.top + margin, Math.min(bottomLimit, target.bottom - size - margin)));
            mediaControls.addView(controls, lp);
        }
    }
    @Override public void selectionChanged(Rect anchor) {
        View toolbar = (View) selection.getParent();
        if (anchor == null) { toolbar.setVisibility(GONE); return; }
        int margin = dp(getContext(), 8);
        toolbar.measure(MeasureSpec.makeMeasureSpec(Math.max(0, getWidth() - margin * 2), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
        LayoutParams lp = (LayoutParams) toolbar.getLayoutParams(); lp.width = Math.min(toolbar.getMeasuredWidth(), Math.max(0, getWidth() - margin * 2)); toolbar.setLayoutParams(lp);
        toolbar.setX(Math.max(margin, Math.min(getWidth() - toolbar.getMeasuredWidth() - margin, anchor.centerX() - toolbar.getMeasuredWidth() / 2f)));
        int y = anchor.top - toolbar.getMeasuredHeight() - dp(getContext(), 12);
        if (y < safe.top + dp(getContext(), 56)) y = anchor.bottom + dp(getContext(), 24);
        toolbar.setY(Math.max(safe.top, Math.min(getHeight() - safe.bottom - bottomControls.getHeight() - toolbar.getMeasuredHeight(), y)));
        toolbar.setVisibility(VISIBLE);
    }
    private LinearLayout sheetCard(int title) {
        sheet.removeAllViews(); sheet.setVisibility(VISIBLE); canvas.clearSelection();
        LinearLayout card = new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(getContext(), 20), dp(getContext(), 20), dp(getContext(), 20), dp(getContext(), 16));
        card.setBackground(round(surface(getContext()), dp(getContext(), 32)));
        TextView heading = text(getContext(), getContext().getString(title), 24); heading.setAccessibilityHeading(true); card.addView(heading);
        return card;
    }
    private void attachSheet(LinearLayout card, boolean fill) {
        ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(fill); scroll.addView(card);
        int width = Math.max(1, Math.min(getWidth() - dp(getContext(), 32), dp(getContext(), 550)));
        LayoutParams lp = new LayoutParams(width, Math.max(1, Math.round((getHeight() - safe.top - safe.bottom) * .78f)), Gravity.CENTER);
        sheet.addView(scroll, lp); card.requestFocus();
    }
    private void sheetAction(LinearLayout card, int label, Runnable action) {
        TextView view = chip(getContext(), label, false, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(getContext(), 8); card.addView(view, lp);
    }
    private void showMore() {
        LinearLayout card = sheetCard(R.string.more);
        if (canvas.imageMode()) {
            Rect crop = canvas.cropBounds(); ScreenContentItem media = data.mediaFor(crop);
            boolean video = media != null && media.type == ScreenContentItem.TYPE_VIDEO && media.descriptor != null;
            if (!crop.isEmpty()) {
                sheetAction(card, video ? R.string.save_video : media != null && media.type == ScreenContentItem.TYPE_VIDEO ? R.string.save_frame : R.string.save_image, () -> { sheet.setVisibility(GONE); export(0); });
                if (video) sheetAction(card, R.string.save_frame, () -> { sheet.setVisibility(GONE); export(0, true); });
                if (!video) sheetAction(card, R.string.copy_image, () -> { sheet.setVisibility(GONE); export(1); });
                sheetAction(card, video ? R.string.share_video : R.string.share_image, () -> { sheet.setVisibility(GONE); export(2); });
            }
            sheetAction(card, R.string.crop_image, () -> { sheet.setVisibility(GONE); canvas.adjustCrop(); hint.setText(R.string.crop_hint); });
        }
        sheetAction(card, R.string.all_text, this::showText);
        sheetAction(card, R.string.details, this::showDetails);
        sheetAction(card, android.R.string.cancel, () -> sheet.setVisibility(GONE)); attachSheet(card, false);
    }
    private void showDetails() {
        LinearLayout card = sheetCard(R.string.details);
        TextView detail = text(getContext(), getContext().getString(R.string.recognition_details, result.glyphs.size(), result.images.size()), 16);
        detail.setPadding(0, dp(getContext(), 16), 0, dp(getContext(), 16)); card.addView(detail);
        sheetAction(card, R.string.all_text, this::showText);
        sheetAction(card, android.R.string.cancel, () -> sheet.setVisibility(GONE)); attachSheet(card, false);
    }
    private void showText() {
        LinearLayout card = sheetCard(R.string.all_text);
        TextView text = text(getContext(), result.text, 17); text.setTextIsSelectable(true);
        text.setPadding(0, dp(getContext(), 16), 0, dp(getContext(), 16)); card.addView(text);
        sheetAction(card, android.R.string.copy, () -> {
            getContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getContext().getString(R.string.recognize), result.text)); toast(R.string.text_copied);
        });
        sheetAction(card, com.android.internal.R.string.share, () -> shareText(result.text));
        sheetAction(card, android.R.string.cancel, () -> sheet.setVisibility(GONE)); attachSheet(card, true);
    }
    private void shareText(String text) {
        if (text.isBlank()) return;
        try {
            getContext().startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); dismiss();
        } catch (RuntimeException e) { toast(R.string.unavailable); }
    }
    private void export(int operation) {
        export(operation, false);
    }
    private void export(int operation, boolean frame) {
        Rect crop = canvas.cropBounds(); if (exporting || crop.isEmpty() || disposed) return;
        ScreenContentItem media = frame ? null : data.mediaFor(crop); exporting = true; hint.setText(R.string.saving_media);
        worker.execute(() -> {
            try {
                RecognitionExport.Result saved = RecognitionExport.write(getContext(), bitmap, crop, media, operation, () -> disposed);
                main.post(() -> {
                    exporting = false; if (disposed) return; hint.setText(R.string.images_hint);
                    try {
                        if (operation == 0) toast(saved.video ? R.string.video_saved : R.string.image_saved);
                        else if (operation == 1) {
                            getContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newUri(getContext().getContentResolver(), getContext().getString(R.string.recognize), saved.uri)); toast(R.string.image_copied);
                        } else {
                            Intent share = new Intent(Intent.ACTION_SEND).setType(saved.mime).putExtra(Intent.EXTRA_STREAM, saved.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            share.setClipData(ClipData.newRawUri("media", saved.uri));
                            getContext().startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); dismiss();
                        }
                    } catch (RuntimeException e) { toast(R.string.media_failed); }
                });
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("ArkRecognition", "Media export failed", e);
                main.post(() -> { exporting = false; if (!disposed) { hint.setText(R.string.images_hint); toast(R.string.media_failed); } });
            }
        });
    }
    private void toast(int text) { Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show(); }
    private void close() { if (sheet.getVisibility() == VISIBLE) sheet.setVisibility(GONE); else dismiss(); }
    private void dismiss() { dispose(); closed.run(); }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) close(); return true; }
        return super.dispatchKeyEvent(e);
    }
    void dispose() {
        if (disposed) return; disposed = true;
        canvas.dispose(); engine.cancel(); main.removeCallbacksAndMessages(null);
        if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        if (windows != null && isAttachedToWindow()) windows.removeViewImmediate(this);
        // Destruction follows native recognition and export, so no worker sees recycled resources.
        worker.execute(() -> { if (content != null) content.close(); else bitmap.recycle(); }); worker.shutdown();
    }
}
