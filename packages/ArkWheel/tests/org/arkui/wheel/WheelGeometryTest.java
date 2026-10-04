/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

/** Host regression checks for notch-safe mirrored arcs and deterministic selection boundaries. */
public final class WheelGeometryTest {
    public static void main(String[] args) {
        int checked = 0;
        for (int width : new int[] {320, 411, 600, 915}) {
            for (int height : new int[] {320, 600, 915}) {
                for (int count = 1; count <= 11; count++) {
                    for (int sweep : new int[] {50, 74, 80}) {
                        WheelGeometry right = new WheelGeometry(width, height, 12, 32, 12, 24, false, 280, 44, count, sweep);
                        WheelGeometry left = new WheelGeometry(width, height, 12, 32, 12, 24, true, 280, 44, count, sweep);
                        for (int i = 0; i < count; i++) {
                            require(Math.abs(left.x[i] + right.x[i] - width) < .001, "not mirrored");
                            require(Math.abs(left.y[i] - right.y[i]) < .001, "vertical mirror drift");
                            require(right.x[i] - right.iconSize / 2 >= 11.999, "left inset");
                            require(right.x[i] + right.iconSize / 2 <= width - 11.999, "right inset");
                            require(right.y[i] - right.iconSize / 2 >= 31.999, "top inset");
                            require(right.y[i] + right.iconSize / 2 <= height - 23.999, "bottom inset");
                            require(right.hit(right.x[i], right.y[i], 0) == i, "ambiguous selection");
                            if (i > 0) require(Math.hypot(right.x[i] - right.x[i-1], right.y[i] - right.y[i-1]) >= right.iconSize - .01, "overlapping shortcuts");
                        }
                        require(right.hit(right.originX, right.originY, 0) == -1, "corner is not a shortcut");
                        checked++;
                    }
                }
            }
        }
        System.out.println("Passed " + checked + " wheel layouts: insets, mirroring, non-overlap, and hit targets");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
