/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import static com.android.internal.arkui.SidebarContract.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SidebarPreferences {
    private static final String POSITION_FRACTION = "position_fraction";
    final SharedPreferences store;
    SidebarPreferences(Context context) {
        store = context.getSharedPreferences("sidebar", Context.MODE_PRIVATE);
    }
    int get(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, store.getInt(key, fallback)));
    }
    boolean enabled(String key) { return get(key, 1, 0, 1) == 1; }
    void put(String key, int value) {
        SharedPreferences.Editor edit = store.edit().putInt(key, value);
        if (POSITION.equals(key)) edit.remove(POSITION_FRACTION);
        edit.apply();
    }
    float position() {
        return Math.max(0, Math.min(1, store.getFloat(POSITION_FRACTION,
                get(POSITION, 35, 0, 100) / 100f)));
    }
    void putPosition(float fraction) {
        float position = Math.max(0, Math.min(1, fraction));
        store.edit().putFloat(POSITION_FRACTION, position)
                .putInt(POSITION, Math.round(position * 100)).apply();
    }
    static boolean isPositionKey(String key) {
        return POSITION.equals(key) || POSITION_FRACTION.equals(key);
    }
    List<String> list(String key) {
        String value = store.getString(key, "");
        return value.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(value.split(";")));
    }
    void putList(String key, List<String> values) {
        store.edit().putString(key, String.join(";", values)).apply();
    }
    Bundle configuration() {
        Bundle b = new Bundle();
        b.putInt(ENABLED, enabled(ENABLED) ? 1 : 0);
        b.putInt(MODE, get(MODE, HANDLE, HANDLE, BOTH));
        b.putInt(HOLD_DELAY, get(HOLD_DELAY, 300, 150, 800));
        b.putInt(HOLD_DISTANCE, get(HOLD_DISTANCE, 48, 24, 120));
        b.putInt(HAPTICS, enabled(HAPTICS) ? 1 : 0);
        b.putInt(RECOMMEND, enabled(RECOMMEND) ? 1 : 0);
        return b;
    }
}
