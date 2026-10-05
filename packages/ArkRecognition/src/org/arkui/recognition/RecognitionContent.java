/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Maps display coordinates once and keeps original text and media attached to the selection. */
final class RecognitionContent {
    static final class Media {
        final Rect bounds;
        final ScreenContentItem source;
        Media(Rect bounds, ScreenContentItem source) { this.bounds = new Rect(bounds); this.source = source; }
    }
    final RecognitionResult original;
    final List<Media> media;
    private final List<Rect> covered;
    RecognitionContent(ScreenContent content, Bitmap screenshot) {
        StringBuilder text = new StringBuilder();
        List<RecognitionResult.Glyph> glyphs = new ArrayList<>();
        List<Rect> images = new ArrayList<>(); media = new ArrayList<>(); covered = new ArrayList<>();
        List<ScreenContentItem> items = content == null || content.items == null ? new ArrayList<>() : new ArrayList<>(content.items);
        items.removeIf(item -> item == null);
        items.sort(Comparator.comparingInt((ScreenContentItem item) -> item.bounds == null ? 0 : item.bounds.top)
                .thenComparingInt(item -> item.bounds == null ? 0 : item.bounds.left));
        int line = 0;
        for (ScreenContentItem item : items) {
            Rect bounds = map(content, screenshot, item.bounds);
            if (bounds.isEmpty() || item.protectedContent) continue;
            if (item.type == ScreenContentItem.TYPE_IMAGE || item.type == ScreenContentItem.TYPE_VIDEO) {
                images.add(bounds); media.add(new Media(bounds, item)); continue;
            }
            if (item.type != ScreenContentItem.TYPE_TEXT || item.text == null || item.text.isBlank()) continue;
            if (!text.isEmpty()) text.append('\n');
            int base = text.length(); text.append(item.text);
            Rect previous = null;
            int lineStart = 0;
            for (int index = 0; index < item.text.length();) {
                int count = Character.charCount(item.text.codePointAt(index));
                if (item.text.charAt(index) == '\n') { line++; lineStart = index + count; previous = null; index += count; continue; }
                Rect character = new Rect();
                if (item.characterBounds != null && item.characterBounds.size() == item.text.length()) {
                    for (int unit = index; unit < index + count; unit++) character.union(map(content, screenshot, item.characterBounds.get(unit)));
                } else {
                    int lineEnd = item.text.indexOf('\n', index); if (lineEnd < 0) lineEnd = item.text.length();
                    int lines = 1; for (int i = 0; i < item.text.length(); i++) if (item.text.charAt(i) == '\n') lines++;
                    int row = 0; for (int i = 0; i < lineStart; i++) if (item.text.charAt(i) == '\n') row++;
                    int length = Math.max(1, lineEnd - lineStart);
                    character.set(bounds.left + bounds.width() * (index - lineStart) / length,
                            bounds.top + bounds.height() * row / lines,
                            bounds.left + bounds.width() * (index + count - lineStart) / length,
                            bounds.top + bounds.height() * (row + 1) / lines);
                }
                if (!character.isEmpty() && !Character.isWhitespace(item.text.charAt(index))) {
                    if (previous != null && Math.abs(previous.centerY() - character.centerY()) > Math.max(previous.height(), character.height()) * .55f) line++;
                    glyphs.add(new RecognitionResult.Glyph(character, base + index, base + index + count, line));
                    covered.add(new Rect(character)); previous = character;
                }
                index += count;
            }
            line++;
        }
        original = new RecognitionResult(text.toString(), glyphs, images, 0);
    }
    static Rect map(ScreenContent content, Bitmap screenshot, Rect source) {
        if (source == null) return new Rect();
        Rect display = content == null ? null : content.displayBounds;
        Rect result = new Rect(source);
        if (display != null && !display.isEmpty()) {
            float x = screenshot.getWidth() / (float) display.width(), y = screenshot.getHeight() / (float) display.height();
            result.set(Math.round((source.left - display.left) * x), Math.round((source.top - display.top) * y),
                    Math.round((source.right - display.left) * x), Math.round((source.bottom - display.top) * y));
        }
        if (!result.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight())) result.setEmpty();
        return result;
    }
    /** Known text is removed before the supplemental pass and is never replaced by its output. */
    Bitmap supplementalImage(Bitmap screenshot) {
        if (covered.isEmpty()) return screenshot;
        Bitmap copy = screenshot.copy(Bitmap.Config.ARGB_8888, true);
        if (copy == null) throw new IllegalStateException("Cannot prepare supplemental image");
        Canvas canvas = new Canvas(copy); Paint paint = new Paint();
        for (Rect bounds : covered) {
            int x = Math.max(0, Math.min(screenshot.getWidth() - 1, bounds.left - 2));
            int y = Math.max(0, Math.min(screenshot.getHeight() - 1, bounds.top - 2));
            paint.setColor(screenshot.getPixel(x, y));
            Rect mask = new Rect(bounds); mask.inset(-2, -2); canvas.drawRect(mask, paint);
        }
        return copy;
    }
    RecognitionResult merge(RecognitionResult supplement) {
        if (supplement == null) return original;
        StringBuilder text = new StringBuilder(original.text);
        List<RecognitionResult.Glyph> glyphs = new ArrayList<>(original.glyphs);
        List<Rect> images = new ArrayList<>(original.images);
        int line = original.glyphs.isEmpty() ? 0 : original.glyphs.get(original.glyphs.size() - 1).line + 1;
        int previousLine = -1, previousEnd = -1;
        for (RecognitionResult.Glyph glyph : supplement.glyphs) {
            if (isCovered(glyph.bounds)) { previousEnd = -1; continue; }
            if (previousLine != glyph.line) {
                if (!text.isEmpty()) text.append('\n'); line++; previousLine = glyph.line; previousEnd = -1;
            } else if (previousEnd >= 0 && previousEnd < glyph.start) {
                String gap = supplement.text.substring(previousEnd, glyph.start);
                if (gap.isBlank() && !gap.isEmpty()) text.append(' ');
            }
            int start = text.length(); text.append(supplement.text, glyph.start, glyph.end);
            glyphs.add(new RecognitionResult.Glyph(glyph.bounds, start, text.length(), line)); previousEnd = glyph.end;
        }
        for (Rect region : supplement.images) {
            boolean duplicate = false;
            for (Rect known : original.images) {
                Rect intersection = new Rect(region);
                if (intersection.intersect(known) && (long) intersection.width() * intersection.height()
                        > .7 * Math.min((long) region.width() * region.height(), (long) known.width() * known.height())) { duplicate = true; break; }
            }
            if (!duplicate) images.add(new Rect(region));
        }
        return new RecognitionResult(text.toString(), glyphs, images, supplement.elapsed);
    }
    private boolean isCovered(Rect bounds) {
        for (Rect known : covered) {
            if (known.contains(bounds.centerX(), bounds.centerY())) return true;
            Rect overlap = new Rect(bounds);
            if (overlap.intersect(known) && (long) overlap.width() * overlap.height() > .15 * bounds.width() * bounds.height()) return true;
        }
        return false;
    }
    ScreenContentItem mediaFor(Rect bounds) {
        for (Media item : media) if (item.bounds.equals(bounds)) return item.source;
        return null;
    }
}
