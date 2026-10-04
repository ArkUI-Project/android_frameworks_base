/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.arkui.wheel.WheelStyle.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
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
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recognition is transient: screenshots stay in memory until an explicit copy/save/share. */
final class RecognitionPanel extends FrameLayout implements RecognitionCanvas.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RecognitionEngine engine;
    private final Bitmap bitmap;
    private final RecognitionCanvas canvas;
    private final Runnable closed;
    private final TextView hint;
    private final LinearLayout actions, selection;
    private final View exitControl, bottomControls;
    private final OnBackInvokedCallback back = this::close;
    private final FrameLayout textSheet;
    private RecognitionResult result;
    private WindowManager windows;
    private Insets safe = Insets.NONE;
    private boolean disposed, exporting, started;

    RecognitionPanel(Context c, Bitmap bitmap, Runnable closed) {
        super(c); this.bitmap = bitmap; this.closed = closed; engine = new RecognitionEngine(c);
        setFocusableInTouchMode(true);
        canvas = new RecognitionCanvas(c, bitmap, this); addView(canvas, new LayoutParams(-1, -1));
        TextView exit = chip(R.string.recognition_close, this::close);
        exitControl = exit; exit.setAlpha(0);
        LayoutParams exitBounds = new LayoutParams(-2, dp(c, 48), Gravity.TOP | Gravity.END);
        exitBounds.setMargins(dp(c, 12), dp(c, 12), dp(c, 12), 0); addView(exit, exitBounds);
        LinearLayout bottom = new LinearLayout(c); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setGravity(Gravity.CENTER);
        bottomControls = bottom; bottom.setAlpha(0);
        hint = text(c, c.getString(R.string.recognition_running), 13); hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8)); hint.setBackground(round(surface(c), dp(c, 20)));
        bottom.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        HorizontalScrollView scroller = new HorizontalScrollView(c); scroller.setHorizontalScrollBarEnabled(false);
        actions = new LinearLayout(c); actions.setGravity(Gravity.CENTER); actions.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), 0);
        scroller.addView(actions); bottom.addView(scroller, new LinearLayout.LayoutParams(-2, dp(c, 62)));
        addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        selection = new LinearLayout(c); selection.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        selection.setBackground(round(surface(c), dp(c, 28))); selection.setElevation(dp(c, 16));
        selection.addView(chip(android.R.string.copy, () -> { canvas.copy(); canvas.clearSelection(); }));
        selection.addView(chip(android.R.string.selectAll, canvas::selectAll));
        selection.addView(chip(com.android.internal.R.string.share, () -> shareText(canvas.selectedText())));
        selection.setVisibility(GONE); addView(selection, new LayoutParams(-2, dp(c, 56)));
        textSheet = new FrameLayout(c); textSheet.setBackgroundColor(0x88000000); textSheet.setVisibility(GONE);
        addView(textSheet, new LayoutParams(-1, -1));
        setOnApplyWindowInsetsListener((v, insets) -> {
            safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            exitBounds.topMargin = safe.top + dp(c, 8); exit.setLayoutParams(exitBounds);
            LayoutParams bottomBounds = (LayoutParams) bottom.getLayoutParams(); bottomBounds.bottomMargin = safe.bottom + dp(c, 8); bottom.setLayoutParams(bottomBounds);
            return insets;
        });
        changed(false);
    }
    private TextView chip(int label, Runnable run) {
        TextView view = text(getContext(), getContext().getString(label), 14); view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(getContext(), 48)); view.setPadding(dp(getContext(), 16), 0, dp(getContext(), 16), 0);
        view.setTextColor(onContainer(getContext())); view.setBackground(button(getContext(), container(getContext()), dp(getContext(), 28)));
        view.setOnClickListener(v -> run.run()); return view;
    }
    void show(WindowManager windows) {
        this.windows = windows;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.setTitle("ArkWheel:recognition"); lp.setFitInsetsTypes(0); lp.gravity = Gravity.TOP | Gravity.LEFT;
        windows.addView(this, lp); requestFocus(); start();
    }
    void start() {
        if (started) return; started = true;
        post(() -> {
            if (disposed) return;
            if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
            canvas.startWave();
            long delay = android.animation.ValueAnimator.areAnimatorsEnabled() && new WheelPreferences(getContext()).on("recognition_wave", true) ? 720 : 0;
            main.postDelayed(() -> {
                if (!disposed) { exitControl.animate().alpha(1).setDuration(120).start(); bottomControls.animate().alpha(1).setDuration(120).start(); }
            }, delay);
        });
        worker.execute(() -> {
            try {
                RecognitionResult found = engine.recognize(bitmap);
                main.post(() -> {
                    if (disposed || found == null) return;
                    result = found; canvas.result(found);
                    if (!canvas.imageMode()) hint.setText(found.text.isEmpty() ? R.string.recognition_empty : R.string.recognition_ready);
                });
            } catch (IOException | RuntimeException | LinkageError e) {
                android.util.Log.w("ArkRecognition", "OCR failed", e);
                main.post(() -> { if (!disposed) hint.setText(R.string.recognition_failed); });
            }
        });
    }
    @Override public void changed(boolean imageSelected) {
        actions.removeAllViews();
        if (canvas.imageMode()) {
            addAction(R.string.select_text, () -> { canvas.imageMode(false); hint.setText(result == null ? R.string.recognition_running : R.string.recognition_ready); });
            addAction(R.string.crop_image, () -> { canvas.adjustCrop(); hint.setText(R.string.crop_hint); });
            if (imageSelected) {
                addAction(R.string.save_image, () -> export(0)); addAction(R.string.copy_image, () -> export(1)); addAction(R.string.share_image, () -> export(2));
            }
        } else {
            addAction(R.string.extract_images, () -> { canvas.imageMode(true); hint.setText(R.string.images_hint); });
            addAction(R.string.all_text, this::showText);
        }
    }
    private void addAction(int label, Runnable run) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(getContext(), 48)); lp.setMargins(dp(getContext(), 3), 0, dp(getContext(), 3), 0);
        actions.addView(chip(label, run), lp);
    }
    @Override public void selectionChanged(Rect anchor) {
        if (anchor == null) { selection.setVisibility(GONE); return; }
        selection.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(getContext(), 56), MeasureSpec.EXACTLY));
        selection.setX(Math.max(dp(getContext(), 8), Math.min(getWidth() - selection.getMeasuredWidth() - dp(getContext(), 8), anchor.centerX() - selection.getMeasuredWidth() / 2f)));
        int y = anchor.top - dp(getContext(), 68);
        if (y < safe.top + dp(getContext(), 56)) y = anchor.bottom + dp(getContext(), 24);
        selection.setY(Math.max(safe.top, Math.min(getHeight() - safe.bottom - dp(getContext(), 170), y)));
        selection.setVisibility(VISIBLE);
    }
    private void showText() {
        if (result == null) return;
        textSheet.removeAllViews(); textSheet.setVisibility(VISIBLE); canvas.clearSelection();
        LinearLayout card = new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(getContext(), 20), dp(getContext(), 16), dp(getContext(), 20), dp(getContext(), 16)); card.setBackground(round(surface(getContext()), dp(getContext(), 32)));
        LinearLayout bar = new LinearLayout(getContext());
        bar.addView(chip(android.R.string.copy, () -> { getContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getContext().getString(R.string.recognize), result.text)); toast(R.string.text_copied); }));
        bar.addView(chip(com.android.internal.R.string.share, () -> shareText(result.text)));
        bar.addView(chip(android.R.string.cancel, () -> textSheet.setVisibility(GONE))); card.addView(bar);
        ScrollView scroll = new ScrollView(getContext()); TextView content = text(getContext(), result.text, 17); content.setTextIsSelectable(true);
        content.setPadding(0, dp(getContext(), 16), 0, dp(getContext(), 16)); scroll.addView(content); card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LayoutParams lp = new LayoutParams(Math.min(getWidth() - dp(getContext(), 32), dp(getContext(), 550)), Math.round((getHeight() - safe.top - safe.bottom) * .78f), Gravity.CENTER);
        textSheet.addView(card, lp);
    }
    private void shareText(String text) {
        if (text.isBlank()) return;
        try {
            getContext().startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dismiss();
        }
        catch (RuntimeException e) { toast(R.string.unavailable); }
    }
    private void export(int operation) {
        Rect crop = canvas.cropBounds(); if (exporting || crop.isEmpty() || disposed) return;
        exporting = true;
        worker.execute(() -> {
            Uri uri = null; Bitmap image = null;
            try {
                image = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height());
                String name = "ArkUI_" + System.currentTimeMillis() + ".png";
                if (operation == 0) {
                    ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArkUI");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IOException("Cannot create image");
                    try (var out = getContext().getContentResolver().openOutputStream(uri)) {
                        if (out == null || !image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Cannot encode image");
                    }
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); getContext().getContentResolver().update(uri, values, null, null);
                } else {
                    File dir = new File(getContext().getCacheDir(), "extracted");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create export directory");
                    File[] old = dir.listFiles();
                    if (old != null) for (File file : old) if (System.currentTimeMillis() - file.lastModified() > 86400000) file.delete();
                    File file = new File(dir, name);
                    try (var out = new FileOutputStream(file)) { if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Cannot encode image"); }
                    uri = FileProvider.getUriForFile(getContext(), "org.arkui.wheel.images", file);
                }
                Uri saved = uri;
                main.post(() -> {
                    exporting = false; if (disposed) return;
                    try {
                        if (operation == 0) toast(R.string.image_saved);
                        else if (operation == 1) { getContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newUri(getContext().getContentResolver(), getContext().getString(R.string.recognize), saved)); toast(R.string.image_copied); }
                        else {
                            Intent share = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, saved)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            share.setClipData(ClipData.newRawUri("image", saved));
                            getContext().startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            dismiss();
                        }
                    } catch (RuntimeException e) { toast(R.string.image_failed); }
                });
            } catch (IOException | RuntimeException e) {
                if (operation == 0 && uri != null) {
                    try { getContext().getContentResolver().delete(uri, null, null); }
                    catch (RuntimeException cleanup) { android.util.Log.w("ArkRecognition", "Cannot remove incomplete image", cleanup); }
                }
                main.post(() -> { exporting = false; if (!disposed) toast(R.string.image_failed); });
            } finally { if (image != null && image != bitmap) image.recycle(); }
        });
    }
    private void toast(int text) { Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show(); }
    private void close() {
        if (textSheet.getVisibility() == VISIBLE) { textSheet.setVisibility(GONE); return; }
        dismiss();
    }
    private void dismiss() {
        dispose(); closed.run();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) close(); return true; }
        return super.dispatchKeyEvent(e);
    }
    void dispose() {
        if (disposed) return; disposed = true;
        canvas.dispose(); engine.cancel(); main.removeCallbacksAndMessages(null);
        exitControl.animate().cancel(); bottomControls.animate().cancel();
        if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        if (windows != null && isAttachedToWindow()) windows.removeViewImmediate(this);
        // Queue destruction behind recognition/export so native work never reads a recycled bitmap.
        worker.execute(bitmap::recycle); worker.shutdown();
    }
}
