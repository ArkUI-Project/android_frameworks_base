/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.graphics.Rect;

/** The invisible bottom corner and its inward, upward diagonal activation gesture. */
final class WheelCornerGesture {
    static Rect bounds(int width, int height, int size, boolean left) {
        int cornerWidth = Math.min(Math.max(0, width), Math.max(0, size));
        int cornerHeight = Math.min(Math.max(0, height), Math.max(0, size));
        int x = left ? 0 : width - cornerWidth;
        return new Rect(x, height - cornerHeight, x + cornerWidth, height);
    }

    static boolean opens(float inward, float upward, int slop) {
        return inward > slop && upward > slop
                && Math.min(inward, upward) >= Math.max(inward, upward) * .4f;
    }

    private WheelCornerGesture() { }
}
