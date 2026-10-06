/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.arkui.recognition.RecognitionStyle.*;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import android.view.View;
import android.view.VelocityTracker;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
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
    private final View scrim;
    private final OnBackAnimationCallback back = new OnBackAnimationCallback() {
        @Override public void onBackStarted(BackEvent event) { trackingBackInvoked = true; }
        @Override public void onBackProgressed(BackEvent event) { }
        @Override public void onBackCancelled() { trackingBackInvoked = false; }
        @Override public void onBackInvoked() {
            boolean startedHere = trackingBackInvoked;
            trackingBackInvoked = trackingBack = false;
            // A popup owns the start of its back gesture. Focus can return before
            // the final key event reaches this window's predictive-back dispatcher.
            if (startedHere) close();
        }
    };
    private RecognitionResult result;
    private WindowManager windows;
    private Insets safe = Insets.NONE;
    private View sheetContent;
    private RecognitionWordSheet wordSheet;
    private Bitmap previewBitmap;
    private boolean ownsPreview, bottomSheet, hidingSheet, recognitionFinished;
    private float sheetProgress;
    private ValueAnimator sheetMotion;
    private PopupMenu popup;
    private final java.util.Map<View, Integer> accessibility = new java.util.IdentityHashMap<>();
    private volatile boolean disposed;
    private boolean exporting, started, trackingBack, trackingBackInvoked;

    RecognitionPanel(Context c, Bitmap bitmap, Runnable closed) { this(c, bitmap, null, closed); }
    RecognitionPanel(Context c, Bitmap bitmap, ScreenContent content, Runnable closed) {
        super(c); this.bitmap = bitmap; this.content = content; this.closed = closed;
        engine = new RecognitionEngine(c); data = new RecognitionContent(content, bitmap);
        setFocusableInTouchMode(true);
        canvas = new RecognitionCanvas(c, bitmap, this, content == null); canvas.setId(R.id.recognition_canvas);
        addView(canvas, new LayoutParams(-1, -1));
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
        sheet = new FrameLayout(c); sheet.setVisibility(GONE); addView(sheet, new LayoutParams(-1, -1));
        scrim = new View(c); scrim.setBackgroundColor(0x88000000);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(v -> hideSheet(true)); sheet.addView(scrim, new LayoutParams(-1, -1));
        setOnApplyWindowInsetsListener((v, insets) -> {
            safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            detailBounds.topMargin = safe.top + dp(c, 8); detailsControl.setLayoutParams(detailBounds);
            LayoutParams bottomBounds = (LayoutParams) bottom.getLayoutParams(); bottomBounds.bottomMargin = safe.bottom + dp(c, 8); bottom.setLayoutParams(bottomBounds);
            updateMediaControls(); layoutSheet(); return insets;
        });
        result = data.original; canvas.result(result); changed(false);
        addOnLayoutChangeListener((v, left, top, right, bottomEdge, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottomEdge - top != oldBottom - oldTop) {
                updateMediaControls();
                layoutSheet();
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
                    result = found; recognitionFinished = true; canvas.result(found); updateMediaControls();
                    if (wordSheet != null) wordSheet.update(found.text, true);
                    if (!canvas.imageMode()) hint.setText(found.text.isEmpty() ? R.string.recognition_empty : R.string.recognition_ready);
                });
            } catch (IOException | RuntimeException | LinkageError e) {
                android.util.Log.w("ArkRecognition", "Supplemental recognition failed", e);
                main.post(() -> {
                    if (disposed) return; recognitionFinished = true;
                    if (result.text.isEmpty()) hint.setText(R.string.recognition_failed);
                    if (wordSheet != null) wordSheet.update(result.text, true);
                });
            } finally { if (supplement != null && supplement != bitmap) supplement.recycle(); }
        });
    }
    @Override public void changed(boolean imageSelected) {
        modes.removeAllViews();
        View textMode = chip(getContext(), R.string.text_mode, !canvas.imageMode(), () -> {
            canvas.imageMode(false); hint.setText(result.text.isEmpty() ? R.string.recognition_empty : R.string.recognition_ready);
            showWords();
        });
        textMode.setId(R.id.recognition_text_mode); addMode(textMode);
        View imageMode = chip(getContext(), R.string.image_mode, canvas.imageMode(), () -> { canvas.imageMode(true); hint.setText(R.string.images_hint); });
        imageMode.setId(R.id.recognition_image_mode); addMode(imageMode);
        View more = icon(getContext(), R.string.more, false, () -> { });
        more.setId(R.id.recognition_more); more.setOnClickListener(v -> showMore(v, false)); addMode(more);
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
                    () -> { canvas.selectImage(region); showPreview(region); }), new LinearLayout.LayoutParams(size, size));
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
    private void menuAction(PopupMenu menu, int label, Runnable action) {
        menu.getMenu().add(Menu.NONE, label, Menu.NONE, label)
                .setOnMenuItemClickListener(item -> { action.run(); return true; });
    }
    private PopupMenu menu(View anchor) {
        if (popup != null) popup.dismiss();
        PopupMenu menu = new PopupMenu(getContext(), anchor, Gravity.END);
        menu.setOnDismissListener(closed -> { if (popup == closed) popup = null; });
        popup = menu; return menu;
    }
    private void showMore(View anchor, boolean mediaOnly) {
        PopupMenu menu = menu(anchor);
        if (canvas.imageMode()) {
            Rect crop = canvas.cropBounds(); ScreenContentItem media = data.mediaFor(crop);
            boolean video = media != null && media.type == ScreenContentItem.TYPE_VIDEO && media.descriptor != null;
            if (!crop.isEmpty() && (media == null || !media.protectedContent)) {
                menuAction(menu, video ? R.string.save_video : media != null && media.type == ScreenContentItem.TYPE_VIDEO ? R.string.save_frame : R.string.save_image, () -> export(0));
                if (video) menuAction(menu, R.string.save_frame, () -> export(0, true));
                if (!video) menuAction(menu, R.string.copy_image, () -> export(1));
                menuAction(menu, video ? R.string.share_video : R.string.share_image, () -> export(2));
            }
            menuAction(menu, R.string.crop_image, () -> { hideSheet(true); canvas.adjustCrop(); hint.setText(R.string.crop_hint); });
        }
        if (!mediaOnly) {
            menuAction(menu, R.string.text_mode, this::showWords);
            menu.getMenu().add(getContext().getString(R.string.recognition_details,
                    result.glyphs.size(), result.images.size())).setEnabled(false);
        }
        menu.show();
    }
    private void showDetails() {
        PopupMenu menu = menu(detailsControl);
        menu.getMenu().add(getContext().getString(R.string.recognition_details,
                result.glyphs.size(), result.images.size())).setEnabled(false);
        menuAction(menu, R.string.text_mode, this::showWords); menu.show();
    }
    private void showWords() {
        if (popup != null) popup.dismiss();
        if (canvas.imageMode()) canvas.imageMode(false);
        RecognitionWordSheet words = new RecognitionWordSheet(getContext(), () -> hideSheet(true), this::shareText);
        words.update(result.text, recognitionFinished);
        showSheet(words, true); wordSheet = words;
        installDrawerDrag(words.findViewById(R.id.recognition_drawer_handle));
    }
    @Override public void imageClicked(Rect bounds) { showPreview(bounds); }
    private void showPreview(Rect bounds) {
        ScreenContentItem media = data.mediaFor(bounds);
        if (bounds.isEmpty() || media != null && media.protectedContent) return;
        hideSheet(false);
        if (media != null && media.image != null && !media.image.isRecycled()) {
            previewBitmap = media.image; ownsPreview = false;
        } else {
            Rect crop = new Rect(bounds);
            if (!crop.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) return;
            previewBitmap = crop.equals(new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())) ? bitmap
                    : Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height());
            ownsPreview = previewBitmap != bitmap;
        }
        Context c = getContext();
        LinearLayout card = new LinearLayout(c); card.setId(R.id.recognition_image_preview);
        card.setOrientation(LinearLayout.VERTICAL); card.setClickable(true); card.setFocusableInTouchMode(true);
        card.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 16));
        card.setBackground(round(surface(c), dp(c, 32)));
        int title = media != null && media.type == ScreenContentItem.TYPE_VIDEO ? R.string.video_preview : R.string.image_preview;
        card.setAccessibilityPaneTitle(c.getString(title));
        LinearLayout header = new LinearLayout(c); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(c, c.getString(title), 22); heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        View more = icon(c, R.string.more, false, () -> { }); more.setId(R.id.recognition_preview_more);
        more.setOnClickListener(v -> showMore(v, true)); header.addView(more);
        header.addView(icon(c, R.string.close, false, () -> hideSheet(true)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));
        ImageView image = new ImageView(c); image.setId(R.id.recognition_preview_image);
        image.setImageBitmap(previewBitmap); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(c.getString(title));
        image.setBackground(round(c.getColor(com.android.internal.R.color.materialColorSurfaceContainerLowest), dp(c, 20)));
        image.setClipToOutline(true);
        LinearLayout.LayoutParams imageBounds = new LinearLayout.LayoutParams(-1, 0, 1);
        imageBounds.topMargin = dp(c, 12); card.addView(image, imageBounds);
        showSheet(card, false);
    }
    private void showSheet(View card, boolean bottom) {
        if (sheetContent != null) hideSheet(false);
        if (popup != null) popup.dismiss();
        canvas.clearSelection(); sheetContent = card; bottomSheet = bottom; hidingSheet = false;
        sheetProgress = 0; scrim.setAlpha(0); card.setAlpha(0);
        sheet.addView(card); layoutSheet(); sheet.setVisibility(VISIBLE);
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index); if (child == sheet) continue;
            accessibility.put(child, child.getImportantForAccessibility());
            child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        card.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                card.removeOnLayoutChangeListener(this);
                if (sheetContent == card && !hidingSheet && !disposed) animateSheet(1);
            }
        });
        card.requestFocus();
    }
    private void layoutSheet() {
        if (sheetContent == null || getWidth() == 0 || getHeight() == 0) return;
        int margin = dp(getContext(), 8);
        int available = Math.max(1, getHeight() - safe.top - safe.bottom - margin * 2);
        boolean landscape = getWidth() > getHeight();
        LayoutParams lp = new LayoutParams(Math.max(1, Math.min(getWidth() - margin * 2, dp(getContext(), 600))),
                Math.max(1, Math.round(available * (landscape ? .94f : bottomSheet ? .74f : .82f))),
                (bottomSheet ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL) | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = safe.top + margin; lp.bottomMargin = safe.bottom + margin;
        sheetContent.setLayoutParams(lp); applySheetProgress(sheetProgress);
    }
    private void applySheetProgress(float progress) {
        sheetProgress = progress; scrim.setAlpha(progress);
        if (sheetContent == null) return;
        sheetContent.setAlpha(bottomSheet ? 1 : progress);
        sheetContent.setTranslationY((1 - progress) * (bottomSheet
                ? Math.max(1, sheetContent.getHeight()) + safe.bottom + dp(getContext(), 8) : dp(getContext(), 36)));
        sheetContent.setScaleX(bottomSheet ? 1 : .96f + .04f * progress);
        sheetContent.setScaleY(bottomSheet ? 1 : .96f + .04f * progress);
    }
    private void animateSheet(float target) {
        if (sheetMotion != null) sheetMotion.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            applySheetProgress(target); if (target == 0) finishHidingSheet(); return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(sheetProgress, target); sheetMotion = motion;
        motion.setDuration(target == 1 ? 400 : 200);
        motion.setInterpolator(target == 1 ? new android.view.animation.PathInterpolator(.2f, 0, 0, 1)
                : new android.view.animation.PathInterpolator(.3f, 0, .8f, .15f));
        motion.addUpdateListener(a -> applySheetProgress((float) a.getAnimatedValue()));
        motion.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (sheetMotion == a) sheetMotion = null;
                if (!cancelled && target == 0) finishHidingSheet();
            }
        }); motion.start();
    }
    private void hideSheet(boolean animated) {
        if (sheetContent == null) return;
        if (popup != null) popup.dismiss(); hidingSheet = true;
        if (animated) animateSheet(0);
        else { if (sheetMotion != null) sheetMotion.cancel(); finishHidingSheet(); }
    }
    private void finishHidingSheet() {
        sheet.setVisibility(GONE);
        if (sheetContent != null) sheet.removeView(sheetContent);
        sheetContent = null; wordSheet = null; hidingSheet = false;
        if (ownsPreview && previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
        previewBitmap = null; ownsPreview = false;
        accessibility.forEach(View::setImportantForAccessibility); accessibility.clear();
        canvas.requestFocus();
    }
    private void installDrawerDrag(View handle) {
        handle.setOnTouchListener(new OnTouchListener() {
            private float down, start;
            private VelocityTracker velocity;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (hidingSheet) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (sheetMotion != null) sheetMotion.cancel();
                    down = event.getRawY(); start = sheetProgress;
                    velocity = VelocityTracker.obtain(); velocity.addMovement(event); return true;
                }
                if (velocity == null || sheetContent == null) return false;
                velocity.addMovement(event);
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    applySheetProgress(Math.max(0, Math.min(1, start
                            - (event.getRawY() - down) / Math.max(1, sheetContent.getHeight()))));
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    velocity.computeCurrentVelocity(1000);
                    boolean dismiss = event.getActionMasked() == MotionEvent.ACTION_UP
                            && (sheetProgress < .8f || velocity.getYVelocity() > dp(getContext(), 1200));
                    velocity.recycle(); velocity = null;
                    if (dismiss) hideSheet(true); else animateSheet(1);
                }
                return true;
            }
        });
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
    private void close() {
        if (popup != null) popup.dismiss();
        else if (sheetContent != null) hideSheet(true);
        else dismiss();
    }
    private void dismiss() { dispose(); closed.run(); }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) trackingBack = true;
            else if (e.getAction() == KeyEvent.ACTION_UP) {
                boolean handledDown = trackingBack; trackingBack = false;
                // A popup can consume DOWN, dismiss itself, then return focus before UP.
                // That UP belongs to the popup and must not close the preview as well.
                if (handledDown && !e.isCanceled()) close();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) trackingBack = trackingBackInvoked = false;
    }
    void dispose() {
        if (disposed) return; disposed = true;
        if (popup != null) popup.dismiss(); hideSheet(false);
        canvas.dispose(); engine.cancel(); main.removeCallbacksAndMessages(null);
        if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        if (windows != null && isAttachedToWindow()) windows.removeViewImmediate(this);
        // Destruction follows native recognition and export, so no worker sees recycled resources.
        worker.execute(() -> { if (content != null) content.close(); else bitmap.recycle(); }); worker.shutdown();
    }
}
