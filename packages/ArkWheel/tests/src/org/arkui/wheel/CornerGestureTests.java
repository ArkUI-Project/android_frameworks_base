/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Injects display touch events through InputDispatcher, never directly into the fixture View. */
public final class CornerGestureTests {
    private Instrumentation mInstrumentation;
    private UiAutomation mAutomation;
    private CornerGestureFixtureActivity mActivity;
    private Rect mDisplay;
    private boolean mLeft;
    private int mTaskId;
    private float mDensity;
    private Gesture mGesture;

    @Before public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mAutomation = mInstrumentation.getUiAutomation();
        AccessibilityServiceInfo service = mAutomation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        mAutomation.setServiceInfo(service);
        assertEquals("Use the self-target runner", "org.arkui.wheel.tests",
                mInstrumentation.getTargetContext().getPackageName());
        String side = InstrumentationRegistry.getArguments().getString("side", "right");
        assertTrue("side must be left or right", side.equals("left") || side.equals("right"));
        mLeft = side.equals("left");
        closeWheel();
        mActivity = (CornerGestureFixtureActivity) mInstrumentation.startActivitySync(new Intent(
                mInstrumentation.getTargetContext(), CornerGestureFixtureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        mInstrumentation.waitForIdleSync();
        mDisplay = main(() -> mActivity.getWindowManager().getMaximumWindowMetrics().getBounds());
        mDensity = mActivity.getResources().getDisplayMetrics().density;
        waitWindow("ArkWheel:trigger", true);
        SystemClock.sleep(300);
        main(() -> { mActivity.reset(); return null; });
        mTaskId = main(() -> mActivity.getTaskId());
        assertTrue("Fixture did not resume", snapshot().resumed);
    }

    @After public void tearDown() throws Exception {
        try {
            if (mGesture != null && mGesture.active) mGesture.end(MotionEvent.ACTION_CANCEL);
            closeWheel();
        } finally {
            if (mActivity != null) main(() -> {
                mActivity.getWindow().setHideOverlayWindows(false);
                mActivity.finish();
                return null;
            });
        }
    }

    @Test public void diagonalFromPhysicalBottomCornerOpensWithoutAppOrNavigationGesture()
            throws Exception {
        beginCorner().move(inward(80), upward(80));
        waitWindow("ArkWheel:panel", true);
        mGesture.end(MotionEvent.ACTION_UP);
        assertApplicationUntouched();
        assertTrue("Releasing inside the arc must not launch a shortcut", hasWindow("ArkWheel:panel"));
    }

    @Test public void hidingNonSystemOverlaysPreservesSystemCornerWheel() throws Exception {
        try {
            main(() -> { mActivity.getWindow().setHideOverlayWindows(true); return null; });
            mInstrumentation.waitForIdleSync();
            SystemClock.sleep(350);
            beginCorner().move(inward(80), upward(80));
            waitWindow("ArkWheel:panel", true);
            mGesture.end(MotionEvent.ACTION_UP);
            assertApplicationUntouched();
            assertTrue("System wheel must remain usable while the app hides ordinary overlays",
                    hasWindow("ArkWheel:panel"));
        } finally {
            main(() -> { mActivity.getWindow().setHideOverlayWindows(false); return null; });
        }
    }

    @Test public void verticalCornerSwipeDoesNotOpenOrNavigateHome() throws Exception {
        beginCorner().move(mGesture.x, upward(160)).end(MotionEvent.ACTION_UP);
        assertRemainsClosedAndUntouched();
    }

    @Test public void horizontalCornerSwipeDoesNotOpenOrClickApplication() throws Exception {
        beginCorner().move(inward(160), mGesture.y).end(MotionEvent.ACTION_UP);
        assertRemainsClosedAndUntouched();
    }

    @Test public void tapInCornerDoesNotOpenOrClickApplication() throws Exception {
        beginCorner();
        SystemClock.sleep(80);
        mGesture.end(MotionEvent.ACTION_UP);
        assertRemainsClosedAndUntouched();
    }

    @Test public void longPressInCornerDoesNotOpenOrReachApplication() throws Exception {
        beginCorner();
        SystemClock.sleep(Math.max(1000, ViewConfiguration.getLongPressTimeout() + 250));
        assertFalse("Holding the corner must not open the wheel", hasWindow("ArkWheel:panel"));
        mGesture.end(MotionEvent.ACTION_UP);
        assertRemainsClosedAndUntouched();
    }

    @Test public void cancelBeforeThresholdDoesNotOpenAndNextGestureStillWorks() throws Exception {
        int slop = ViewConfiguration.get(mActivity).getScaledTouchSlop();
        beginCorner().move(mGesture.x + (mLeft ? 1 : -1) * slop / 3f,
                mGesture.y - slop / 3f).end(MotionEvent.ACTION_CANCEL);
        assertRemainsClosedAndUntouched();
        beginCorner().move(inward(80), upward(80));
        waitWindow("ArkWheel:panel", true);
        mGesture.end(MotionEvent.ACTION_UP);
        assertApplicationUntouched();
    }

    @Test public void cancelAfterOpeningKeepsArcWithoutActivatingShortcut() throws Exception {
        beginCorner().move(inward(80), upward(80));
        waitWindow("ArkWheel:panel", true);
        mGesture.end(MotionEvent.ACTION_CANCEL);
        SystemClock.sleep(300);
        assertTrue("Cancel removes selection but leaves the opened arc", hasWindow("ArkWheel:panel"));
        assertApplicationUntouched();
    }

    @Test public void secondPointerCancelsHoveredShortcutWithoutAppAction() throws Exception {
        beginCorner().move(inward(80), upward(80));
        waitWindow("ArkWheel:panel", true);
        SystemClock.sleep(350);
        Rect shortcut = shortcutBounds();
        assertNotNull("No visible wheel shortcut accessibility bounds", shortcut);
        mGesture.move(shortcut.exactCenterX(), shortcut.exactCenterY());
        mGesture.pointer(MotionEvent.ACTION_POINTER_DOWN);
        mGesture.pointer(MotionEvent.ACTION_POINTER_UP);
        SystemClock.sleep(350);
        assertTrue("First extra pointer must not activate a shortcut", hasWindow("ArkWheel:panel"));
        // Keep the original finger held while InputDispatcher starts another split DOWN
        // in the panel. Its later downTime must receive an updated cancellation cutoff.
        mGesture.pointer(MotionEvent.ACTION_POINTER_DOWN);
        mGesture.pointer(MotionEvent.ACTION_POINTER_UP);
        mGesture.end(MotionEvent.ACTION_UP);
        SystemClock.sleep(500);
        assertTrue("Repeated extra pointers must cancel the hovered shortcut before release",
                hasWindow("ArkWheel:panel"));
        assertApplicationUntouched();
    }

    @Test public void swipeOutsideCornerStillScrollsApplication() throws Exception {
        mGesture = new Gesture(mDisplay.width() / 2f, mDisplay.height() * .78f);
        mGesture.move(mDisplay.width() / 2f, mDisplay.height() * .38f)
                .end(MotionEvent.ACTION_UP);
        SystemClock.sleep(300);
        CornerGestureFixtureActivity.Snapshot result = snapshot();
        assertTrue("Ordinary app drag was intercepted: " + result, result.down > 0 && result.move > 0 && result.up > 0);
        assertTrue("Ordinary drag did not scroll the actual ScrollView: " + result, result.scrollY > dp(48));
        assertEquals("A drag must not click an app row", 0, result.clicks);
        assertEquals("Ordinary app scrolling must not open the wheel", false, hasWindow("ArkWheel:panel"));
        assertEquals(0, result.pauses);
    }

    @Test public void centralBottomSwipeStillNavigatesHome() throws Exception {
        mGesture = new Gesture(mDisplay.width() / 2f, mDisplay.height() - dp(4));
        mGesture.fling(mDisplay.width() / 2f, mDisplay.height() * .60f)
                .end(MotionEvent.ACTION_UP);
        long deadline = SystemClock.uptimeMillis() + 5000;
        CornerGestureFixtureActivity.Snapshot result;
        do {
            SystemClock.sleep(80);
            result = snapshot();
            if (result.pauses > 0 && !result.resumed) break;
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Corner isolation swallowed ordinary Home navigation: " + result,
                result.pauses > 0 && !result.resumed);
        assertFalse("Central Home gesture must not open the wheel", hasWindow("ArkWheel:panel"));
    }

    @Test public void realHelperAnchorsBothCornersAndRejectsNavigationLikeDirections() {
        assertEquals(new Rect(1032, 2352, 1080, 2400), WheelCornerGesture.bounds(1080, 2400, 48, false));
        assertEquals(new Rect(0, 2352, 48, 2400), WheelCornerGesture.bounds(1080, 2400, 48, true));
        assertEquals(new Rect(2352, 1032, 2400, 1080), WheelCornerGesture.bounds(2400, 1080, 48, false));
        assertEquals(new Rect(0, 0, 24, 36), WheelCornerGesture.bounds(24, 36, 96, false));
        assertFalse("Touch slop must not open", WheelCornerGesture.opens(8, 8, 8));
        assertFalse("An outward gesture must not open", WheelCornerGesture.opens(-40, 40, 8));
        assertFalse("A downward gesture must not open", WheelCornerGesture.opens(40, -40, 8));
        assertFalse("A nearly vertical home gesture must not open", WheelCornerGesture.opens(39, 100, 8));
        assertFalse("A nearly horizontal back gesture must not open", WheelCornerGesture.opens(100, 39, 8));
        assertTrue("The inclusive diagonal boundary must open", WheelCornerGesture.opens(40, 100, 8));
        assertTrue("The mirrored boundary must open", WheelCornerGesture.opens(100, 40, 8));
    }

    private Gesture beginCorner() {
        mGesture = new Gesture(mLeft ? dp(4) : mDisplay.width() - dp(4),
                mDisplay.height() - dp(4));
        return mGesture;
    }

    private float inward(int distance) {
        return mLeft ? dp(4 + distance) : mDisplay.width() - dp(4 + distance);
    }
    private float upward(int distance) { return mDisplay.height() - dp(4 + distance); }
    private int dp(int value) { return Math.round(value * mDensity); }

    private void assertRemainsClosedAndUntouched() throws Exception {
        SystemClock.sleep(350);
        assertFalse("A non-diagonal/cancelled corner gesture opened the wheel", hasWindow("ArkWheel:panel"));
        assertApplicationUntouched();
    }

    private void assertApplicationUntouched() throws Exception {
        mInstrumentation.waitForIdleSync();
        CornerGestureFixtureActivity.Snapshot result = snapshot();
        assertEquals("Corner DOWN escaped into app: " + result, 0, result.down);
        assertEquals("Corner MOVE escaped into app: " + result, 0, result.move);
        assertEquals("Corner UP escaped into app: " + result, 0, result.up);
        assertEquals("Corner CANCEL escaped into app: " + result, 0, result.cancel);
        assertEquals("Corner gesture clicked the app", 0, result.clicks);
        assertEquals("Corner gesture scrolled the app", 0, result.scrollY);
        assertEquals("Navigation paused the foreground app: " + result, 0, result.pauses);
        assertTrue("Foreground app is no longer resumed: " + result, result.resumed);
        assertEquals("Gesture changed the foreground task", mTaskId, result.taskId);
    }

    private CornerGestureFixtureActivity.Snapshot snapshot() throws Exception {
        return main(() -> mActivity.snapshot());
    }

    private Rect shortcutBounds() {
        Rect topmost = null;
        for (AccessibilityWindowInfo window : mAutomation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                try {
                    Rect found = shortcutBounds(root);
                    if (found != null && (topmost == null || found.top < topmost.top)) topmost = found;
                } finally { root.recycle(); }
            }
            window.recycle();
        }
        return topmost;
    }

    private Rect shortcutBounds(AccessibilityNodeInfo node) {
        Rect result = null;
        if ("org.arkui.wheel".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                && node.isVisibleToUser() && node.isClickable() && node.getContentDescription() != null) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && bounds.width() <= dp(96) && bounds.height() <= dp(96)
                    && bounds.centerY() < mDisplay.height() - dp(96)) result = bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                Rect found = shortcutBounds(child);
                if (found != null && (result == null || found.top < result.top)) result = found;
            } finally { child.recycle(); }
        }
        return result;
    }

    private boolean hasWindow(String title) throws Exception {
        try (ParcelFileDescriptor fd = mAutomation.executeShellCommand("dumpsys window windows");
                FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).contains(title);
        }
    }

    private void waitWindow(String title, boolean expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 6000;
        do {
            if (hasWindow(title) == expected) return;
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Window " + title + " did not become " + (expected ? "visible" : "absent")
                + "; enable Wheel and its requested side before running");
    }

    private void closeWheel() throws Exception {
        if (mAutomation == null || !hasWindow("ArkWheel:panel")) return;
        long downTime = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        down.setDisplayId(Display.DEFAULT_DISPLAY);
        assertTrue("InputDispatcher rejected Back DOWN", mAutomation.injectInputEvent(down, true));
        KeyEvent up = new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        up.setDisplayId(Display.DEFAULT_DISPLAY);
        assertTrue("InputDispatcher rejected Back UP", mAutomation.injectInputEvent(up, true));
        waitWindow("ArkWheel:panel", false);
    }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        mInstrumentation.runOnMainSync(task);
        return task.get();
    }

    private final class Gesture {
        final long downTime = SystemClock.uptimeMillis();
        float x, y;
        boolean active = true;
        Gesture(float x, float y) { this.x = x; this.y = y; inject(MotionEvent.ACTION_DOWN); }
        Gesture move(float endX, float endY) {
            float startX = x, startY = y;
            for (int i = 1; i <= 12; i++) {
                SystemClock.sleep(20);
                x = startX + (endX - startX) * i / 12;
                y = startY + (endY - startY) * i / 12;
                inject(MotionEvent.ACTION_MOVE);
            }
            return this;
        }
        Gesture fling(float endX, float endY) {
            float startX = x, startY = y;
            for (int i = 1; i <= 6; i++) {
                SystemClock.sleep(5);
                x = startX + (endX - startX) * i / 6;
                y = startY + (endY - startY) * i / 6;
                inject(MotionEvent.ACTION_MOVE, false);
            }
            return this;
        }
        void end(int action) { inject(action); active = false; }
        void pointer(int action) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = x + i * (mLeft ? dp(8) : -dp(8));
                coords[i].y = y;
                coords[i].pressure = 1;
                coords[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords,
                    0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { assertTrue("InputDispatcher rejected second pointer", mAutomation.injectInputEvent(event, true)); }
            finally { event.recycle(); }
        }
        void inject(int action) {
            inject(action, true);
        }
        void inject(int action, boolean sync) {
            MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
            pointer.id = 0;
            pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = x; coords.y = y; coords.pressure = 1; coords.size = 1;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    1, new MotionEvent.PointerProperties[] {pointer}, new MotionEvent.PointerCoords[] {coords},
                    0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { assertTrue("InputDispatcher rejected touch event", mAutomation.injectInputEvent(event, sync)); }
            finally { event.recycle(); }
        }
    }
}
