/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.content.Context;
import android.content.SharedPreferences;

final class RecognitionPreferences {
    final SharedPreferences store;
    RecognitionPreferences(Context c) { store = c.getSharedPreferences("recognition", Context.MODE_PRIVATE); }
    int get(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, store.getInt(key, fallback)));
    }
    boolean on(String key, boolean fallback) { return get(key, fallback ? 1 : 0, 0, 1) == 1; }
    void put(String key, int value) { store.edit().putInt(key, value).apply(); }
}
