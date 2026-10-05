/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds rectangular visual regions; the adjustable crop remains available for ambiguous layouts. */
final class ImageRegions {
    static List<Rect> detect(Bitmap bitmap, List<RecognitionResult.Glyph> text) {
        int step = Math.max(4, bitmap.getWidth() / 180);
        int w = Math.max(1, bitmap.getWidth() / step), h = Math.max(1, bitmap.getHeight() / step);
        Bitmap thumb = Bitmap.createScaledBitmap(bitmap, w, h, true);
        int[] pixels = new int[w * h]; thumb.getPixels(pixels, 0, w, 0, 0, w, h);
        if (thumb != bitmap) thumb.recycle();
        boolean[] ink = new boolean[pixels.length], visited = new boolean[pixels.length];
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x, color = pixels[i];
            ink[i] = distance(color, pixels[i - 1]) > 35 || distance(color, pixels[i - w]) > 35;
        }
        // Remove glyph strokes before grouping picture edges so paragraphs are not offered as photos.
        for (RecognitionResult.Glyph glyph : text) {
            Rect r = glyph.bounds;
            for (int y = Math.max(0, r.top / step - 1); y < Math.min(h, r.bottom / step + 2); y++)
                for (int x = Math.max(0, r.left / step - 1); x < Math.min(w, r.right / step + 2); x++) ink[y * w + x] = false;
        }
        boolean[] joined = new boolean[pixels.length];
        for (int y = 2; y < h - 2; y++) for (int x = 2; x < w - 2; x++) {
            int count = 0;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) if (ink[(y + dy) * w + x + dx]) count++;
            joined[y * w + x] = count >= 3;
        }
        List<Rect> regions = new ArrayList<>();
        int[] queue = new int[pixels.length];
        for (int seed = 0; seed < joined.length; seed++) {
            if (!joined[seed] || visited[seed]) continue;
            int head = 0, tail = 1, l = w, t = h, r = 0, b = 0;
            queue[0] = seed; visited[seed] = true;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                l = Math.min(l, x); r = Math.max(r, x); t = Math.min(t, y); b = Math.max(b, y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy, n = ny * w + nx;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h && joined[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; }
                }
            }
            if ((r - l) * step < 64 || (b - t) * step < 64 || tail < 65
                    || (r - l) * (b - t) > w * h * .80f) continue;
            Rect region = new Rect(Math.max(0, (l - 2) * step), Math.max(0, (t - 2) * step),
                    Math.min(bitmap.getWidth(), (r + 3) * step), Math.min(bitmap.getHeight(), (b + 3) * step));
            regions.add(region);
        }
        regions.sort(Comparator.comparingInt(r -> -r.width() * r.height()));
        return regions.subList(0, Math.min(12, regions.size()));
    }
    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
    }
}
