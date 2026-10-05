/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.pm.LauncherApps;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;

public final class RecognitionTests {
    @Test public void frameworkTextPreservesOriginalCharactersAndRejectsDuplicateOcr() {
        ScreenContent content = new ScreenContent(); content.status = ScreenContent.STATUS_OK;
        content.displayBounds.set(10, 20, 210, 220);
        content.screenshot = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); content.screenshot.eraseColor(Color.WHITE);
        ScreenContentItem item = new ScreenContentItem(ScreenContentItem.TYPE_TEXT, new Rect(30, 40, 110, 60));
        item.text = "原文😀";
        item.characterBounds.add(new Rect(30, 40, 50, 60)); item.characterBounds.add(new Rect(50, 40, 70, 60));
        item.characterBounds.add(new Rect(70, 40, 110, 60)); item.characterBounds.add(new Rect(70, 40, 110, 60));
        content.items.add(item);
        try {
            RecognitionContent data = new RecognitionContent(content, content.screenshot);
            assertEquals("原文😀", data.original.text); assertEquals(3, data.original.glyphs.size());
            assertEquals(new Rect(30, 10, 50, 20), data.original.glyphs.get(2).bounds);
            assertEquals(2, data.original.glyphs.get(2).start); assertEquals(4, data.original.glyphs.get(2).end);
            RecognitionResult supplement = new RecognitionResult("错新", java.util.List.of(
                    new RecognitionResult.Glyph(new Rect(10, 10, 20, 20), 0, 1, 0),
                    new RecognitionResult.Glyph(new Rect(10, 60, 20, 70), 1, 2, 1)), java.util.List.of(), 2);
            RecognitionResult merged = data.merge(supplement);
            assertEquals("原文😀\n新", merged.text); assertEquals(4, merged.glyphs.size());
            assertEquals(data.original.glyphs.get(0).bounds, merged.glyphs.get(0).bounds);
            assertFalse("Supplement must not rewrite original text", merged.text.contains("错"));
            Bitmap masked = data.supplementalImage(content.screenshot);
            try { assertNotSame(content.screenshot, masked); assertFalse(content.screenshot.isRecycled()); }
            finally { masked.recycle(); }
        } finally { content.close(); }
    }

    @Test public void imageExportUsesOriginalBitmapInsteadOfScreenshotCrop() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap screenshot = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888); screenshot.eraseColor(Color.RED);
        ScreenContentItem image = new ScreenContentItem(ScreenContentItem.TYPE_IMAGE, new Rect(0, 0, 10, 10));
        image.image = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888); image.image.eraseColor(Color.BLUE);
        Uri exported = null;
        try {
            RecognitionExport.Result result = RecognitionExport.write(context, screenshot, image.bounds, image, 2, () -> false);
            exported = result.uri;
            assertFalse(result.video); assertEquals("image/png", result.mime);
            try (var input = context.getContentResolver().openInputStream(result.uri)) {
                Bitmap saved = BitmapFactory.decodeStream(input);
                try { assertEquals(8, saved.getWidth()); assertEquals(6, saved.getHeight()); assertEquals(Color.BLUE, saved.getPixel(0, 0)); }
                finally { saved.recycle(); }
            }
            assertFalse(image.image.isRecycled()); assertFalse(screenshot.isRecycled());
        } finally {
            image.close(); screenshot.recycle();
            if (exported != null) assertEquals("Remove this test's image export", 1,
                    context.getContentResolver().delete(exported, null, null));
        }
    }

    @Test public void videoExportCopiesOnlyBoundedRangeAndPreservesPlaybackPosition() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("recognition-video", ".bin", context.getCacheDir());
        byte[] movie = new byte[32]; movie[3] = 16;
        System.arraycopy("ftypisom".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, movie, 4, 8);
        for (int i = 12; i < movie.length; i++) movie[i] = (byte) i;
        try (var output = new FileOutputStream(file)) { output.write(new byte[7]); output.write(movie); output.write(new byte[9]); }
        Bitmap screenshot = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        ScreenContentItem video = new ScreenContentItem(ScreenContentItem.TYPE_VIDEO, new Rect(0, 0, 10, 10));
        video.descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); video.offset = 7; video.length = movie.length;
        Uri exported = null;
        try {
            Os.lseek(video.descriptor.getFileDescriptor(), 3, OsConstants.SEEK_SET);
            RecognitionExport.Result result = RecognitionExport.write(context, screenshot, video.bounds, video, 2, () -> false);
            exported = result.uri;
            assertTrue(result.video); assertEquals("video/mp4", result.mime);
            try (var input = context.getContentResolver().openInputStream(result.uri)) { assertArrayEquals(movie, input.readAllBytes()); }
            assertEquals(3L, Os.lseek(video.descriptor.getFileDescriptor(), 0, OsConstants.SEEK_CUR));
            video.protectedContent = true;
            try { RecognitionExport.write(context, screenshot, video.bounds, video, 2, () -> false); fail("Protected video must be rejected"); }
            catch (java.io.IOException expected) { }
            video.protectedContent = false; video.length = movie.length + 100;
            try { RecognitionExport.write(context, screenshot, video.bounds, video, 2, () -> false); fail("Out-of-file range must be rejected"); }
            catch (java.io.IOException expected) { }
        } finally {
            video.close(); screenshot.recycle(); file.delete();
            if (exported != null) assertEquals("Remove this test's video export", 1,
                    context.getContentResolver().delete(exported, null, null));
        }
    }

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
        File debugImage = null;
        try {
            RecognitionResult result = new RecognitionEngine(context).recognize(image);
            assertNotNull(result);
            assertTrue(result.text, result.text.replace(" ", "").contains("你好"));
            assertTrue(result.text, result.text.contains("offline"));
            assertTrue("A brand name must not be discarded with its entire low-confidence word: " + result.text,
                    result.text.contains("ArkUI"));
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
            debugImage = new File(dir, "recognition-test.png");
            try (var out = new FileOutputStream(debugImage)) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
            android.util.Log.i("ArkRecognitionTest", "Recognized " + result.glyphs.size()
                    + " symbols and " + result.images.size() + " images in " + result.elapsed
                    + " ms; text=" + result.text.replace('\n', '|'));
        } finally {
            image.recycle();
            if (debugImage != null) assertTrue("Remove this test's debug image",
                    !debugImage.exists() || debugImage.delete());
        }
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

    @Test public void recognizesSmallMixedLabelsAndKeepsSelectionInOriginalCoordinates() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        for (boolean dark : new boolean[] {false, true}) {
            Bitmap image = RecognitionFixtureActivity.smallMixedFixture(dark);
            try {
                RecognitionResult result = new RecognitionEngine(context).recognize(image);
                assertNotNull(result);
                android.util.Log.i("ArkRecognitionTest", "Small mixed " + (dark ? "dark" : "light")
                        + " in " + result.elapsed + " ms; text=" + result.text.replace('\n', '|'));
                assertTrue(result.text, result.text.contains("ArkUI offline OCR"));
                assertTrue(result.text, result.text.replace(" ", "").contains("你好ArkUI12345"));
                assertTrue(result.text, result.text.contains("67890"));
                assertTrue(result.text, result.text.contains("support@example.com"));
                Rect labels = new Rect(30, 65, 400, 385);
                for (var glyph : result.glyphs) {
                    assertTrue("Upscaled OCR bounds must map back to the original bitmap: " + glyph.bounds,
                            labels.contains(glyph.bounds));
                }
                instrumentation.runOnMainSync(() -> {
                    RecognitionCanvas canvas = new RecognitionCanvas(context, image,
                            new RecognitionCanvas.Listener() {
                                public void changed(boolean selected) { }
                                public void selectionChanged(Rect anchor) { }
                            });
                    canvas.layout(0, 0, 720, 520); canvas.result(result);
                    int start = result.text.indexOf("ArkUI");
                    Bundle args = new Bundle();
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + 5);
                    assertTrue(canvas.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args));
                    assertEquals("ArkUI", canvas.selectedText());
                    canvas.dispose();
                });
                assertFalse("Preprocessing must retain the shared screenshot", image.isRecycled());
            } finally { image.recycle(); }
        }
    }

    @Test public void localVocabularyRecognizesAppNamesWithoutRewritingOrdinaryText() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // This name comes from a real installed launchable activity, not the screenshot result.
        assertTrue(context.getSystemService(LauncherApps.class)
                .getActivityList("org.arkui.recognition.tests", android.os.Process.myUserHandle())
                .stream().anyMatch(app -> "IllyNote".contentEquals(app.getLabel())));
        Bitmap image = RecognitionFixtureActivity.lexiconFixture();
        try {
            RecognitionResult guided = new RecognitionEngine(context, true, true).recognize(image);
            RecognitionResult noLexicon = new RecognitionEngine(context, false, true).recognize(image);
            RecognitionResult fast = new RecognitionEngine(context, true, false).recognize(image);
            String[] names = {"IllyNote", "GitHub", "WiFi"};
            RecognitionResult[] variants = {guided, noLexicon, fast};
            String[] descriptions = {"lexicon+best", "no-lexicon+best", "lexicon+fast"};
            for (int i = 0; i < variants.length; i++) {
                RecognitionResult result = variants[i];
                assertNotNull(result);
                int correct = 0;
                for (String name : names) if (result.text.contains(name)) correct++;
                android.util.Log.i("ArkRecognitionTest", descriptions[i] + ": exact names="
                        + correct + "/" + names.length + "; " + result.elapsed
                        + " ms; text=" + result.text.replace('\n', '|'));
            }
            for (RecognitionResult result : new RecognitionResult[] {guided, noLexicon, fast}) {
                String compact = result.text.replaceAll("\\s+", "");
                assertTrue("Common text must retain its original spelling: " + result.text,
                        compact.contains("Thequickbrownfox24680"));
                assertTrue("Unknown text must not become a dictionary word: " + result.text,
                        compact.contains("QZXV47291"));
                assertTrue(result.text, result.text.contains("Copy this text"));
            }
            for (String name : names) assertTrue(guided.text, guided.text.contains(name));
        } finally { image.recycle(); }
    }

    @Test public void comparesBestAndFastWeightsOnIdenticalLightAndDarkPixels() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (int round = 0; round < 2; round++) {
            for (boolean small : new boolean[] {false, true}) {
                for (boolean dark : new boolean[] {false, true}) {
                    Bitmap image = small ? RecognitionFixtureActivity.smallMixedFixture(dark)
                            : RecognitionFixtureActivity.fixture(dark);
                    String expected = small
                            ? "ArkUI offline OCR\n你好 ArkUI 12345\nCopy this text 67890\nsupport@example.com"
                            : "你好，世界\nArkUI offline OCR\nCopy this text 12345";
                    String[] terms = small
                            ? new String[] {"ArkUIofflineOCR", "你好ArkUI12345", "67890", "support@example.com"}
                            : new String[] {"你好", "ArkUI", "offline", "12345"};
                    try {
                        // Reverse the order in round two to expose initialization/cache bias.
                        for (boolean best : round == 0 ? new boolean[] {true, false}
                                : new boolean[] {false, true}) {
                            RecognitionResult result = new RecognitionEngine(context, true, best).recognize(image);
                            assertNotNull(result);
                            String compact = result.text.replaceAll("\\s+", "");
                            int correct = 0;
                            for (String term : terms) if (compact.contains(term)) correct++;
                            android.util.Log.i("ArkRecognitionTest", "weight-pair round=" + (round + 1)
                                    + " fixture=" + (small ? "small" : "original") + "/" + (dark ? "dark" : "light")
                                    + " model=" + (best ? "best" : "fast")
                                    + " editDistance=" + editDistance(expected.replaceAll("\\s+", ""), compact)
                                    + " exactTerms=" + correct + "/" + terms.length
                                    + " elapsed=" + result.elapsed + " ms; text=" + result.text.replace('\n', '|'));
                            assertFalse("Both models must return usable text", result.text.isBlank());
                            assertFalse("Both models must return selectable glyphs", result.glyphs.isEmpty());
                            Rect source = new Rect(0, 0, image.getWidth(), image.getHeight());
                            for (var glyph : result.glyphs) {
                                assertTrue("Glyph coordinates must remain in the source image: " + glyph.bounds,
                                        source.contains(glyph.bounds));
                                assertTrue(glyph.start >= 0 && glyph.end > glyph.start && glyph.end <= result.text.length());
                            }
                            if (best) {
                                for (String term : terms) assertTrue(result.text, compact.contains(term));
                                int start = result.text.indexOf("ArkUI");
                                Rect brandLine = small ? new Rect(30, 65, 400, 100) : new Rect(60, 210, 660, 290);
                                for (var glyph : result.glyphs) {
                                    if (glyph.start < start + 5 && glyph.end > start)
                                        assertTrue("Brand selection must cover the original line: " + glyph.bounds,
                                                brandLine.contains(glyph.bounds));
                                }
                            }
                        }
                        assertFalse("Model comparison must preserve the shared screenshot", image.isRecycled());
                    } finally { image.recycle(); }
                }
            }
        }
    }

    private static int editDistance(String expected, String actual) {
        int[] previous = new int[actual.length() + 1], current = new int[actual.length() + 1];
        for (int j = 0; j <= actual.length(); j++) previous[j] = j;
        for (int i = 1; i <= expected.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= actual.length(); j++) {
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + (expected.charAt(i - 1) == actual.charAt(j - 1) ? 0 : 1));
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[actual.length()];
    }
}
