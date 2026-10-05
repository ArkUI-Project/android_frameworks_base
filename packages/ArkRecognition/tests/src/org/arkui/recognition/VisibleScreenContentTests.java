/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.junit.Assert.*;

import android.graphics.Color;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import android.view.IScreenContentCallback;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import android.view.WindowManagerGlobal;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** End-to-end WM -> application ViewRoot -> system app capture, without an OCR substitute. */
public final class VisibleScreenContentTests {
    private static final String FIXTURE = "org.arkui.recognition.tests/org.arkui.recognition.VisibleContentFixtureActivity";

    @After public void closeFixture() throws Exception { shell("am force-stop org.arkui.recognition.tests"); }

    @Test public void returnsActualVisibleCharactersAndOriginalImagePixels() throws Exception {
        launch(false);
        try (ScreenContent content = captureStable()) {
            assertEquals(ScreenContent.STATUS_OK, content.status);
            assertNotNull(content.screenshot);
            android.util.Log.i("VisibleContentFixture", "Capture partial=" + content.partial + " items=" + content.items.size() + " bounds=" + content.displayBounds);
            StringBuilder text = new StringBuilder();
            for (ScreenContentItem item : content.items) {
                if (item.type != ScreenContentItem.TYPE_TEXT) continue;
                text.append(item.text).append('\n');
                assertEquals(item.text.length(), item.characterBounds.size());
                for (var bounds : item.characterBounds) {
                    assertFalse(bounds.isEmpty()); assertTrue(item.bounds.contains(bounds));
                    assertTrue(content.displayBounds.contains(bounds));
                }
            }
            assertTrue("Framework must return the original Unicode string: " + text,
                    text.toString().contains("Visible 原文😀 2026"));
            for (String secret : new String[] {"PASSWORD_SECRET", "GONE_SECRET", "OFFSCREEN_SECRET", "HIDDEN_ELLIPSIS_SECRET", "TRANSPARENT_SECRET", "SPAN_SECRET"}) {
                assertFalse("Invisible content leaked: " + secret + " / " + text, text.toString().contains(secret));
            }
            ScreenContentItem image = content.items.stream()
                    .filter(item -> item.type == ScreenContentItem.TYPE_IMAGE && item.image != null)
                    .filter(item -> item.image.getPixel(item.image.getWidth() / 2, item.image.getHeight() / 2) == Color.BLUE)
                    .findFirst().orElse(null);
            assertNotNull("Native ImageView must supply image pixels", image);
            assertEquals(640, image.image.getWidth()); assertEquals(400, image.image.getHeight());
            assertTrue(content.displayBounds.contains(image.bounds));
            assertFalse("Transparent ImageView must not expose its invisible source",
                    content.items.stream().anyMatch(item -> item.image != null
                            && item.image.getPixel(item.image.getWidth() / 2, item.image.getHeight() / 2) == Color.GREEN));
        }
    }

    @Test public void secureWindowReturnsNoPixelsTextOrMedia() throws Exception {
        launch(true);
        try (ScreenContent content = captureStable()) {
            assertEquals(ScreenContent.STATUS_SECURE, content.status);
            assertNull(content.screenshot); assertTrue(content.items.isEmpty());
        }
    }

    @Test public void visibleVideoSuppliesCompleteLocalFileThroughFramework() throws Exception {
        shell("am force-stop org.arkui.recognition.tests");
        shell("am start -W -n org.arkui.recognition.tests/org.arkui.recognition.VideoFixtureActivity --ez play true --ez debug true");
        SystemClock.sleep(2000);
        try (ScreenContent content = captureStable()) {
            assertEquals(ScreenContent.STATUS_OK, content.status);
            ScreenContentItem video = content.items.stream()
                    .filter(item -> item.type == ScreenContentItem.TYPE_VIDEO && item.descriptor != null)
                    .findFirst().orElse(null);
            assertNotNull("Visible local MediaPlayer must return an original video descriptor", video);
            byte[] original;
            try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("visible_media_fixture.mp4")) { original = input.readAllBytes(); }
            assertEquals(original.length, video.length);
            byte[] copied = new byte[original.length]; int read = 0;
            while (read < copied.length) {
                int count = android.system.Os.pread(video.descriptor.getFileDescriptor(), copied,
                        read, copied.length - read, video.offset + read);
                assertTrue(count > 0); read += count;
            }
            assertArrayEquals(original, copied);
        }
    }

    private static void launch(boolean secure) throws Exception {
        shell("am force-stop org.arkui.recognition.tests");
        shell("am start -W -n " + FIXTURE + " --ez debug true --ez secure " + secure);
        SystemClock.sleep(900);
    }
    private static ScreenContent captureStable() throws Exception {
        ScreenContent result = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (result != null) result.close();
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<ScreenContent> reference = new AtomicReference<>();
            var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            WindowManagerGlobal.getWindowManagerService().requestVisibleScreenContent(
                    Display.DEFAULT_DISPLAY, context.getPackageName(), new IScreenContentCallback.Stub() {
                        @Override public void onScreenContent(ScreenContent content) {
                            reference.set(content); received.countDown();
                        }
                    });
            assertTrue("Framework capture timed out", received.await(6, TimeUnit.SECONDS));
            result = reference.get(); assertNotNull(result);
            if (result.status != ScreenContent.STATUS_CHANGED && result.status != ScreenContent.STATUS_UNAVAILABLE) return result;
            SystemClock.sleep(350);
        }
        return result;
    }
    private static void shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (var input = new FileInputStream(descriptor.getFileDescriptor())) { input.readAllBytes(); }
        finally { descriptor.close(); }
    }
}
