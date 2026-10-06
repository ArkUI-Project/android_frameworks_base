/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded LRC parsing, including multiple timestamps per line and file offsets. */
public final class LyricsTimeline {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d{1,6})\\]", Pattern.CASE_INSENSITIVE);
    public record Line(long time, String text) { }
    private final List<Line> mLines;
    private LyricsTimeline(List<Line> lines) { mLines = lines; }

    public static LyricsTimeline parse(String input) {
        final TreeMap<Long, String> lines = new TreeMap<>();
        if (input == null || input.length() > 65536) return new LyricsTimeline(List.of());
        int offset = 0;
        final Matcher offsetTag = OFFSET.matcher(input);
        if (offsetTag.find()) offset = Integer.parseInt(offsetTag.group(1));
        int count = 0;
        for (String row : input.split("\\r?\\n", 513)) {
            if (++count > 512) break;
            final Matcher times = TIME.matcher(row);
            final ArrayList<Long> stamps = new ArrayList<>();
            int end = 0;
            while (times.find() && stamps.size() < 16) {
                final int seconds = Integer.parseInt(times.group(2));
                if (seconds >= 60) continue;
                final String fraction = times.group(3);
                final int millis = fraction == null ? 0 : Integer.parseInt(fraction)
                        * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                stamps.add(Long.parseLong(times.group(1)) * 60000 + seconds * 1000L + millis + offset);
                end = times.end();
            }
            if (!stamps.isEmpty()) {
                final String text = row.substring(end).trim();
                for (long stamp : stamps) lines.put(Math.max(0, stamp), text.length() > 512 ? text.substring(0, 512) : text);
            }
        }
        final ArrayList<Line> result = new ArrayList<>();
        lines.forEach((time, text) -> result.add(new Line(time, text)));
        return new LyricsTimeline(result);
    }
    public boolean isEmpty() { return mLines.isEmpty(); }
    public String at(long position) {
        int i = index(position);
        return i < 0 ? "" : mLines.get(i).text;
    }
    public long next(long position) {
        final int i = index(position) + 1;
        return i >= mLines.size() ? -1 : mLines.get(i).time;
    }
    private int index(long position) {
        int lo = 0, hi = mLines.size() - 1, answer = -1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (mLines.get(mid).time <= position) { answer = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return answer;
    }
}
