/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.graphics.Rect;
import java.util.List;

final class RecognitionResult {
    static final class Glyph {
        final Rect bounds;
        final int start, end, line;
        Glyph(Rect bounds, int start, int end, int line) {
            this.bounds = new Rect(bounds); this.start = start; this.end = end; this.line = line;
        }
    }
    final String text;
    final List<Glyph> glyphs;
    final List<Rect> images;
    final long elapsed;
    RecognitionResult(String text, List<Glyph> glyphs, List<Rect> images, long elapsed) {
        this.text = text; this.glyphs = List.copyOf(glyphs); this.images = List.copyOf(images); this.elapsed = elapsed;
    }
}
