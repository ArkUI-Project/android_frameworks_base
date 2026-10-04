/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;

public final class RecognitionTests {
    @Test public void tinySharedImagesRemainUsableAfterRegionDetection() {
        for (int size : new int[] {1, 2, 3, 8}) {
            Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            try {
                assertTrue(ImageRegions.detect(image, java.util.List.of()).isEmpty());
                assertFalse("Region detection must not recycle the shared source", image.isRecycled());
            } finally { image.recycle(); }
        }
    }
    @Test public void recognizesActualChineseAndEnglishPixelsWithSelectableBounds() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        Bitmap image = RecognitionFixtureActivity.fixture(false);
        try {
            RecognitionResult result = new RecognitionEngine(context).recognize(image);
            assertNotNull(result);
            assertTrue(result.text, result.text.replace(" ", "").contains("你好"));
            assertTrue(result.text, result.text.contains("offline"));
            assertTrue(result.text, result.text.contains("12345"));
            assertTrue("Picture region should be discovered", !result.images.isEmpty());
            assertTrue("Photo must be bounded near the fixture photo", result.images.stream().anyMatch(r -> Rect.intersects(r, new Rect(60, 540, 660, 1060)) && r.width() > 400 && r.height() > 300));
            for (var glyph : result.glyphs) assertTrue(new Rect(0, 0, 720, 1200).contains(glyph.bounds));
            instrumentation.runOnMainSync(() -> {
                RecognitionCanvas canvas = new RecognitionCanvas(context, image, new RecognitionCanvas.Listener() {
                    public void changed(boolean selected) { }
                    public void selectionChanged(Rect anchor) { }
                });
                canvas.layout(0, 0, 720, 1200); canvas.result(result);
                int start = result.text.indexOf("offline");
                Bundle args = new Bundle(); args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + 7);
                assertTrue(canvas.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args));
                assertEquals("offline", canvas.selectedText());
                canvas.dispose();
            });
            File dir = new File(context.getCacheDir(), "extracted"); dir.mkdirs();
            try (var out = new FileOutputStream(new File(dir, "recognition-test.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
            android.util.Log.i("ArkRecognitionTest", "Recognized " + result.glyphs.size() + " symbols and " + result.images.size() + " images in " + result.elapsed + " ms");
        } finally { image.recycle(); }
    }
    @Test public void recognizesDarkBackgroundAndCancelsBeforeStarting() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap image = RecognitionFixtureActivity.fixture(true);
        try {
            RecognitionResult result = new RecognitionEngine(context).recognize(image);
            assertTrue(result.text, result.text.contains("offline"));
            RecognitionEngine cancelled = new RecognitionEngine(context); cancelled.cancel();
            assertNull(cancelled.recognize(image));
        } finally { image.recycle(); }
    }
}
