/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Region;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.android.systemui.shared.system.ArkWheelGestureGate;

import org.junit.Test;

/** Exercises the same stream gate used before both back and Quickstep navigation startup. */
public final class CornerGestureGateTests {
    private static final int TOUCH = InputDevice.SOURCE_TOUCHSCREEN;
    private final ArkWheelGestureGate mGate = new ArkWheelGestureGate();

    @Test public void downInsideOwnsEntireStreamOutsideCorner() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        assertTrue(touch(MotionEvent.ACTION_MOVE, 400, 900, 100));
        assertTrue(touch(MotionEvent.ACTION_UP, 400, 900, 100));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 980, 1980, 100));
    }

    @Test public void downOutsideNeverClaimsMovesIntoNewlyShownCorner() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        corner(0, 0);
        assertFalse(touch(MotionEvent.ACTION_MOVE, 980, 1980, 100));
        assertFalse(touch(MotionEvent.ACTION_UP, 980, 1980, 100));
    }

    @Test public void hidingTriggerKeepsOwnedStreamButAllowsNextDown() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        mGate.setRegion(0, -1, new Region());
        assertTrue(touch(MotionEvent.ACTION_MOVE, 500, 1000, 100));
        assertTrue(touch(MotionEvent.ACTION_UP, 500, 1000, 100));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 980, 1980, 200));
    }

    @Test public void lockingOrDisconnectingPreservesOwnedStreamUntilCancel() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        mGate.clearRegions();
        assertTrue(touch(MotionEvent.ACTION_CANCEL, 980, 1980, 100));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 980, 1980, 100));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 980, 1980, 200));
    }

    @Test public void replacementRegionDoesNotReleaseCurrentStream() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        mGate.setRegion(0, 1, new Region(0, 1900, 100, 2000));
        assertTrue(event(MotionEvent.ACTION_MOVE, 500, 1000, 100, TOUCH, 0, 0, 1));
        assertTrue(event(MotionEvent.ACTION_UP, 500, 1000, 100, TOUCH, 0, 0, 1));
        assertFalse(event(MotionEvent.ACTION_DOWN, 980, 1980, 200, TOUCH, 0, 0, 1));
        assertTrue(event(MotionEvent.ACTION_DOWN, 20, 1980, 300, TOUCH, 0, 0, 1));
    }

    @Test public void staleRotationCannotBlockNavigation() {
        corner(0, 0);
        assertFalse(event(MotionEvent.ACTION_DOWN, 980, 1980, 100, TOUCH, 0, 0, 1));
        // Returning to that rotation must not resurrect the invalidated old bounds.
        assertFalse(touch(MotionEvent.ACTION_DOWN, 980, 1980, 200));
    }

    @Test public void mouseAndTrackpadNeverClaimOrReleaseTouchscreenStream() {
        corner(0, 0);
        assertFalse(event(MotionEvent.ACTION_DOWN, 980, 1980, 100,
                InputDevice.SOURCE_MOUSE, 0, 0, 0));
        assertFalse(event(MotionEvent.ACTION_DOWN, 980, 1980, 100,
                InputDevice.SOURCE_TOUCHPAD, 0, 0, 0));
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 200));
        assertFalse(event(MotionEvent.ACTION_UP, 980, 1980, 200,
                InputDevice.SOURCE_MOUSE, 0, 0, 0));
        assertTrue(touch(MotionEvent.ACTION_UP, 500, 1000, 200));
    }

    @Test public void stylusAndHoverKeepTheirNormalNavigationPath() {
        corner(0, 0);
        assertFalse(event(MotionEvent.ACTION_DOWN, 980, 1980, 100,
                InputDevice.SOURCE_STYLUS, 0, 0, 0));
        assertFalse(touch(MotionEvent.ACTION_HOVER_ENTER, 980, 1980, 100));
    }

    @Test public void anotherDisplayCannotUseOrReleaseCornerGesture() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        assertFalse(event(MotionEvent.ACTION_DOWN, 980, 1980, 100, TOUCH, 1, 0, 0));
        assertFalse(event(MotionEvent.ACTION_UP, 980, 1980, 100, TOUCH, 1, 0, 0));
        assertTrue(touch(MotionEvent.ACTION_UP, 500, 1000, 100));
    }

    @Test public void anotherDeviceCannotReleaseOrReplaceOwnedStream() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        assertFalse(event(MotionEvent.ACTION_DOWN, 500, 1000, 100, TOUCH, 0, 8, 0));
        assertFalse(event(MotionEvent.ACTION_UP, 980, 1980, 100, TOUCH, 0, 8, 0));
        assertTrue(touch(MotionEvent.ACTION_UP, 500, 1000, 100));
    }

    @Test public void oldDownTimeCannotReleaseCurrentStream() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 200));
        assertFalse(touch(MotionEvent.ACTION_CANCEL, 980, 1980, 100));
        assertTrue(touch(MotionEvent.ACTION_UP, 500, 1000, 200));
    }

    @Test public void aNewDownOutsideReplacesMissingTerminalEvent() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 500, 1000, 200));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 980, 1980, 200));
    }

    @Test public void additionalPointersRemainOwnedUntilTerminalEvent() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        assertTrue(touch(MotionEvent.ACTION_POINTER_DOWN, 500, 1000, 100));
        assertTrue(touch(MotionEvent.ACTION_POINTER_UP, 500, 1000, 100));
        assertTrue(touch(MotionEvent.ACTION_MOVE, 500, 1000, 100));
        assertTrue(touch(MotionEvent.ACTION_UP, 500, 1000, 100));
    }

    @Test public void registeredRegionIsCopiedAndKeepsExactEdgesAndHoles() {
        Region region = new Region(0, 1900, 100, 2000);
        region.op(40, 1940, 60, 1960, Region.Op.DIFFERENCE);
        mGate.setRegion(0, 0, region);
        region.setEmpty();
        assertFalse(touch(MotionEvent.ACTION_DOWN, -.25f, 1980, 100));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 100, 1980, 200));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 50, 1950, 300));
        assertTrue(touch(MotionEvent.ACTION_DOWN, 0, 1999.75f, 400));
    }

    @Test public void monitorDestructionOrDisplayRemovalClearsAllState() {
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        mGate.removeDisplay(0);
        assertFalse(touch(MotionEvent.ACTION_UP, 500, 1000, 100));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 980, 1980, 200));
        corner(0, 0);
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 300));
        mGate.clear();
        assertFalse(touch(MotionEvent.ACTION_MOVE, 500, 1000, 300));
        assertFalse(touch(MotionEvent.ACTION_DOWN, 980, 1980, 400));
    }

    @Test public void observerMustRemainWhileHiddenTriggerStillOwnsStream() {
        assertFalse(mGate.hasOwnedGesture());
        corner(0, 0);
        assertFalse(mGate.hasOwnedGesture());
        assertTrue(touch(MotionEvent.ACTION_DOWN, 980, 1980, 100));
        assertTrue(mGate.hasOwnedGesture());
        mGate.clearRegions();
        assertTrue(mGate.hasOwnedGesture());
        assertTrue(touch(MotionEvent.ACTION_POINTER_DOWN, 500, 1000, 100));
        assertTrue(mGate.hasOwnedGesture());
        assertTrue(touch(MotionEvent.ACTION_CANCEL, 500, 1000, 100));
        assertFalse(mGate.hasOwnedGesture());
    }

    private void corner(int displayId, int rotation) {
        mGate.setRegion(displayId, rotation, new Region(900, 1900, 1000, 2000));
    }

    private boolean touch(int action, float x, float y, long downTime) {
        return event(action, x, y, downTime, TOUCH, 0, 0, 0);
    }

    private boolean event(int action, float x, float y, long downTime, int source,
            int displayId, int deviceId, int rotation) {
        MotionEvent event = MotionEvent.obtain(downTime, downTime + 10, action, x, y,
                1f, 1f, 0, 1f, 1f, deviceId, 0, source, 0);
        event.setDisplayId(displayId);
        try { return mGate.onMotionEvent(event, rotation); }
        finally { event.recycle(); }
    }
}
