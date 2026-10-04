/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

/** Pixel geometry kept independent of views so mirroring and edge clamping are testable. */
final class WheelGeometry {
    final float originX, originY, radius, iconSize;
    final float[] x, y, angle;
    WheelGeometry(float width, float height, float left, float top, float right, float bottom,
            boolean fromLeft, float requestedRadius, float requestedIcon, int count, float sweep) {
        count = Math.max(1, Math.min(11, count));
        originX = fromLeft ? left : width - right;
        originY = height - bottom;
        float usable = Math.min(width - left - right, height - top - bottom);
        radius = Math.max(1, Math.min(requestedRadius, usable - requestedIcon * .65f));
        float step = Math.max(30, Math.min(80, sweep)) / Math.max(1, count - 1);
        iconSize = Math.max(1, Math.min(requestedIcon,
                (float) (2 * radius * Math.sin(Math.toRadians(step / 2))) * .88f));
        x = new float[count]; y = new float[count]; angle = new float[count];
        float start = (90 - Math.max(30, Math.min(80, sweep))) / 2;
        for (int i = 0; i < count; i++) {
            angle[i] = (float) Math.toRadians(90 - start - step * i);
            float dx = (float) Math.cos(angle[i]) * radius;
            x[i] = clamp(originX + (fromLeft ? dx : -dx), left + iconSize / 2,
                    width - right - iconSize / 2);
            y[i] = clamp(originY - (float) Math.sin(angle[i]) * radius,
                    top + iconSize / 2, height - bottom - iconSize / 2);
        }
    }
    int hit(float px, float py, float extra) {
        int best = -1;
        float distance = iconSize / 2 + extra;
        for (int i = 0; i < x.length; i++) {
            float d = (float) Math.hypot(px - x[i], py - y[i]);
            if (d <= distance) { distance = d; best = i; }
        }
        return best;
    }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
