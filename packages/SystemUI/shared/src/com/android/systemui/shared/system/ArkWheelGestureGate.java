/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.shared.system;

import android.graphics.Region;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Keeps navigation monitors out of streams owned by a visible wheel corner window. */
public final class ArkWheelGestureGate {
    private final SparseArray<Corner> mCorners = new SparseArray<>();
    private final SparseArray<SparseArray<Gesture>> mGestures = new SparseArray<>();

    public synchronized void setRegion(int displayId, int rotation, Region region) {
        if (region == null || region.isEmpty() || rotation < 0 || rotation > 3) {
            mCorners.remove(displayId);
        } else {
            mCorners.put(displayId, new Corner(rotation, new Region(region)));
        }
    }

    /** Hiding or rotating a window must not hand the remainder of its stream to navigation. */
    public synchronized void clearRegions() {
        mCorners.clear();
    }

    /** An observer may outlive a hidden trigger only while finishing a stream it already owns. */
    public synchronized boolean hasOwnedGesture() {
        for (int i = 0; i < mGestures.size(); ++i) {
            if (mGestures.valueAt(i).size() != 0) return true;
        }
        return false;
    }

    /** Used only when the input monitor is destroyed or its display is removed. */
    public synchronized void clear() {
        mCorners.clear();
        mGestures.clear();
    }

    public synchronized void removeDisplay(int displayId) {
        mCorners.remove(displayId);
        mGestures.remove(displayId);
    }

    /** Call before transforming the event into a navigation orientation. */
    public synchronized boolean onMotionEvent(MotionEvent event, int currentRotation) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false;
        int displayId = event.getDisplayId();
        int deviceId = event.getDeviceId();
        int action = event.getActionMasked();
        SparseArray<Gesture> gestures = mGestures.get(displayId);
        if (action == MotionEvent.ACTION_DOWN) {
            if (gestures != null) gestures.remove(deviceId);
            Corner corner = mCorners.get(displayId);
            if (corner == null) return false;
            if (corner.rotation != currentRotation) {
                mCorners.remove(displayId);
                return false;
            }
            if (corner.region.contains((int) Math.floor(event.getX()),
                    (int) Math.floor(event.getY()))) {
                if (gestures == null) {
                    gestures = new SparseArray<>(); mGestures.put(displayId, gestures);
                }
                gestures.put(deviceId, new Gesture(event.getDownTime()));
                return true;
            }
            return false;
        }
        Gesture gesture = gestures == null ? null : gestures.get(deviceId);
        if (gesture == null || gesture.downTime != event.getDownTime()) return false;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            gestures.remove(deviceId);
            if (gestures.size() == 0) mGestures.remove(displayId);
        }
        return true;
    }

    private record Corner(int rotation, Region region) { }
    private record Gesture(long downTime) { }
}
