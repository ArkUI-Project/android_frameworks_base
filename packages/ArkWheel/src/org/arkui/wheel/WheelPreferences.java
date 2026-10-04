/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class WheelPreferences {
    static final String ENABLED = "enabled", ITEMS = "items", EXCLUDED = "excluded",
            SIDE = "side", POSITION = "position", TRIGGER_SIZE = "trigger_size",
            TRIGGER_OPACITY = "trigger_opacity", INVOCATION = "invocation", HOLD = "hold",
            RADIUS = "radius", ICON_SIZE = "icon_size", COUNT = "count", ARC = "arc",
            ANIMATION = "animation", DIM = "dim", HAPTICS = "haptics", MONOCHROME = "monochrome",
            LANDSCAPE = "landscape", LAUNCH_MODE = "launch_mode", RECENT = "recent",
            DISMISSED_RECENT = "dismissed_recent";
    final SharedPreferences store;
    WheelPreferences(Context c) { store = c.getSharedPreferences("wheel", Context.MODE_PRIVATE); }
    int get(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, store.getInt(key, fallback)));
    }
    boolean on(String key, boolean fallback) { return get(key, fallback ? 1 : 0, 0, 1) == 1; }
    boolean enabled() { return on(ENABLED, false); }
    void put(String key, int value) { store.edit().putInt(key, value).apply(); }
    List<String> list(String key) {
        String raw = store.getString(key, "");
        return raw.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(raw.split(";")));
    }
    void putList(String key, List<String> items) {
        store.edit().putString(key, String.join(";", items)).apply();
    }
}
