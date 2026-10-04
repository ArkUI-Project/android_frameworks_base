/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.barfollow.tests;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowInsets;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.android.internal.arkui.SystemBarFollowSettings;
import org.junit.Rule;
import org.junit.Test;
import java.io.FileInputStream;

public class BarFollowInsetsTests {
    @Rule public final ActivityTestRule<BarFollowFixtureActivity> activity =
            new ActivityTestRule<>(BarFollowFixtureActivity.class);

    @Test public void transientHandleReservesNoSpaceAndKeepsMandatoryGestures() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = activity.getActivity();
        assertTrue("Run in gesture navigation", SystemBarFollowSettings.isGestureNavigation(context));
        var resolver = context.getContentResolver();
        String key = Settings.System.ARKUI_NAVIGATION_BAR_FOLLOW;
        String previous = Settings.System.getString(resolver, key);
        try {
            putSetting("1");
            awaitInsets(true);
            SystemClock.sleep(1500);
            var image = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(image);
            try {
                int y = image.getHeight() - Math.round(12 * context.getResources().getDisplayMetrics().density);
                assertEquals("Idle handle and its reserved strip must both disappear",
                        BarFollowFixtureActivity.BACKGROUND, image.getPixel(image.getWidth() / 2, y));
            } finally { image.recycle(); }
            putSetting("0");
            awaitInsets(false);
            putSetting("1");
            awaitInsets(true);
        } finally { putSetting(previous); }
    }

    private void putSetting(String value) throws Exception {
        String key = Settings.System.ARKUI_NAVIGATION_BAR_FOLLOW;
        String command = value == null ? "settings delete system " + key
                : "settings put system " + key + " " + Integer.parseInt(value);
        try (var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                var stream = new FileInputStream(descriptor.getFileDescriptor())) {
            stream.readAllBytes();
        }
    }

    private void awaitInsets(boolean follow) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        long deadline = SystemClock.uptimeMillis() + 5000;
        int[] values = {-1, -1};
        do {
            instrumentation.runOnMainSync(() -> {
                WindowInsets insets = activity.getActivity().getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    values[0] = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
                    values[1] = insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom;
                }
            });
            if ((follow ? values[0] == 0 : values[0] > 0) && values[1] > 0) {
                android.util.Log.i("ArkBarFollowTest", "follow=" + follow + " nav=" + values[0] + " mandatory=" + values[1]);
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("follow=" + follow + " navigation inset=" + values[0] + " mandatory gesture inset=" + values[1]);
    }
}
