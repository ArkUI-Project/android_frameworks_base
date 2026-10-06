/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.junit.Assert.*;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises the actual system overlay, including back handling and original media ownership. */
public final class RecognitionPanelTests {
    private static final String TEXT = "重音落在 e 上时，e 变为 viste vestirse\n原文 😀 ArkUI";
    private RecognitionPanel panel;
    private ScreenContent content;
    private Context context;
    private android.content.ClipData clipboard;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Before public void openOverlay() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Context target = instrumentation.getTargetContext();
            var display = target.getSystemService(WindowManager.class).getDefaultDisplay();
            context = new android.view.ContextThemeWrapper(target.createWindowContext(display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.RecognitionTheme);
            content = new ScreenContent(); content.status = ScreenContent.STATUS_OK;
            content.displayBounds.set(0, 0, 720, 1200);
            content.screenshot = Bitmap.createBitmap(720, 1200, Bitmap.Config.ARGB_8888);
            content.screenshot.eraseColor(Color.WHITE);
            ScreenContentItem text = new ScreenContentItem(ScreenContentItem.TYPE_TEXT, new Rect(30, 100, 690, 260));
            text.text = TEXT; content.items.add(text);
            ScreenContentItem image = new ScreenContentItem(ScreenContentItem.TYPE_IMAGE, new Rect(60, 400, 660, 900));
            image.image = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888); image.image.eraseColor(Color.BLUE);
            content.items.add(image);
            panel = new RecognitionPanel(context, content.screenshot, content, () -> closed.set(true));
            panel.show(context.getSystemService(WindowManager.class));
        });
        instrumentation.waitForIdleSync();
        onMain(() -> clipboard = context.getSystemService(ClipboardManager.class).getPrimaryClip());
    }

    @After public void closeOverlay() {
        if (panel != null) onMain(() -> {
            ClipboardManager manager = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) manager.clearPrimaryClip(); else manager.setPrimaryClip(clipboard);
            panel.dispose();
        });
    }

    @Test public void wordButtonOpensBottomDrawerAndCopiesSelectedOriginalText() {
        click(R.id.recognition_text_mode); settle();
        onMain(() -> {
            View drawer = panel.findViewById(R.id.recognition_word_drawer);
            assertNotNull(drawer); assertTrue(drawer.isShown());
            assertTrue("Drawer is aligned near the bottom", drawer.getBottom() > panel.getHeight() * .85f);
            assertFalse(panel.findViewById(R.id.recognition_words_copy).isEnabled());
            panel.findViewById(R.id.recognition_words_select_all).performClick();
            panel.findViewById(R.id.recognition_words_copy).performClick();
            assertEquals(TEXT, context.getSystemService(ClipboardManager.class)
                    .getPrimaryClip().getItemAt(0).getText().toString());
            panel.findViewById(R.id.recognition_words_select_all).performClick();
            assertFalse(panel.findViewById(R.id.recognition_words_copy).isEnabled());
        });
    }

    @Test public void backClosesDrawerBeforeDismissingRecognition() {
        click(R.id.recognition_text_mode); settle(); back(); settle();
        onMain(() -> {
            assertNull(panel.findViewById(R.id.recognition_word_drawer));
            assertFalse(closed.get()); assertTrue(panel.isAttachedToWindow());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                    panel.findViewById(R.id.recognition_canvas).getImportantForAccessibility());
        });
        back(); assertTrue(closed.get());
    }

    @Test public void tappingAnImageOpensOriginalBitmapPreviewAndBackKeepsSourceAlive() {
        tapImage(); settle();
        onMain(() -> {
            ImageView image = panel.findViewById(R.id.recognition_preview_image);
            assertNotNull(image); Bitmap pixels = ((BitmapDrawable) image.getDrawable()).getBitmap();
            assertSame(content.items.get(1).image, pixels);
            assertEquals(320, pixels.getWidth()); assertEquals(Color.BLUE, pixels.getPixel(0, 0));
        });
        back(); settle();
        onMain(() -> {
            assertNull(panel.findViewById(R.id.recognition_image_preview));
            assertFalse(content.items.get(1).image.isRecycled()); assertFalse(content.screenshot.isRecycled());
            assertFalse(closed.get());
        });
    }

    @Test public void imagePreviewUsesNormalAnchoredDropdownAndFirstBackOnlyClosesMenu() {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        var info = automation.getServiceInfo(); int previousFlags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        tapImage(); settle(); click(R.id.recognition_preview_more);
        try {
            assertTrue("Native popup must expose save", awaitMenuItem(automation, R.string.save_image, true));
            assertTrue("Native popup must expose copy", awaitMenuItem(automation, R.string.copy_image, true));
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            assertTrue("One back closes the menu", awaitMenuItem(automation, R.string.save_image, false));
            settle();
            onMain(() -> {
                assertNotNull(panel.findViewById(R.id.recognition_image_preview)); assertFalse(closed.get());
            });
        } finally {
            info.flags = previousFlags; automation.setServiceInfo(info);
        }
    }

    private boolean awaitMenuItem(android.app.UiAutomation automation, int label, boolean present) {
        long deadline = SystemClock.uptimeMillis() + 3000;
        do {
            // A programmatically opened system overlay has no last-touched active
            // application window. Retrieve its actual interactive popup window.
            boolean found = automation.getWindows().stream().map(window -> window.getRoot())
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(root -> root.findAccessibilityNodeInfosByText(context.getString(label)).stream()
                            .anyMatch(node -> context.getString(label).contentEquals(node.getText() == null ? "" : node.getText())));
            if (found == present) return true;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    @Test public void disabledAnimationsLeaveDrawerAtItsFinalPosition() {
        float scale = android.animation.ValueAnimator.getDurationScale();
        onMain(() -> android.animation.ValueAnimator.setDurationScale(0));
        try {
            click(R.id.recognition_text_mode);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onMain(() -> {
                View drawer = panel.findViewById(R.id.recognition_word_drawer);
                assertNotNull(drawer); assertEquals(0, drawer.getTranslationY(), .01);
                assertEquals(1, drawer.getAlpha(), .01);
            });
            back();
            onMain(() -> assertNull(panel.findViewById(R.id.recognition_word_drawer)));
        } finally { onMain(() -> android.animation.ValueAnimator.setDurationScale(scale)); }
    }

    @Test public void screenshotFallbackIsReleasedWithoutRecyclingTheSource() {
        onMain(() -> { content.items.get(1).image.recycle(); content.items.get(1).image = null; });
        tapImage(); settle();
        java.util.concurrent.atomic.AtomicReference<Bitmap> preview = new java.util.concurrent.atomic.AtomicReference<>();
        onMain(() -> {
            ImageView image = panel.findViewById(R.id.recognition_preview_image);
            preview.set(((BitmapDrawable) image.getDrawable()).getBitmap());
            assertNotSame(content.screenshot, preview.get());
            assertEquals(600, preview.get().getWidth()); assertEquals(500, preview.get().getHeight());
            assertEquals(Color.WHITE, preview.get().getPixel(0, 0));
        });
        back(); settle(); assertTrue(preview.get().isRecycled()); assertFalse(content.screenshot.isRecycled());
    }

    @Test public void importedPictureWithoutDetectedPhotoRegionsStillOpensPreview() {
        java.util.concurrent.atomic.AtomicReference<Bitmap> source = new java.util.concurrent.atomic.AtomicReference<>();
        onMain(() -> {
            panel.dispose(); source.set(Bitmap.createBitmap(720, 1200, Bitmap.Config.ARGB_8888));
            source.get().eraseColor(Color.WHITE);
            panel = new RecognitionPanel(context, source.get(), () -> closed.set(true));
            panel.show(context.getSystemService(WindowManager.class));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        click(R.id.recognition_image_mode); tapImage(); settle();
        onMain(() -> {
            ImageView image = panel.findViewById(R.id.recognition_preview_image);
            assertNotNull(image); assertSame(source.get(), ((BitmapDrawable) image.getDrawable()).getBitmap());
        });
        back(); settle(); assertFalse(source.get().isRecycled());
    }

    private void tapImage() {
        onMain(() -> {
            RecognitionCanvas canvas = panel.findViewById(R.id.recognition_canvas);
            var bounds = canvas.mappedBounds(new Rect(60, 400, 660, 900));
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
            MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
            try { canvas.dispatchTouchEvent(down); canvas.dispatchTouchEvent(up); }
            finally { down.recycle(); up.recycle(); }
        });
    }
    private void click(int id) { onMain(() -> panel.findViewById(id).performClick()); }
    private void back() { onMain(() -> {
        panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    }); }
    private void onMain(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private void settle() { SystemClock.sleep(550); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
}
