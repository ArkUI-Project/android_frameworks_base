/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.arkui;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.ArrayMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Per-user access to device motion, independent of body/fitness sensor permissions. @hide */
public final class MotionSensorSettings {
    public static final String KEY = "arkui_motion_sensor_modes";
    public static final int ALLOW = 0;
    public static final int BLOCK_ON_OPEN = 1;
    public static final int DENY = 2;
    public static final long OPEN_BLOCK_NANOS = 6_000_000_000L;

    private MotionSensorSettings() { }

    public static Map<String, Integer> read(ContentResolver resolver, int userId) {
        final Map<String, Integer> modes = new ArrayMap<>();
        final String value = Settings.Secure.getStringForUser(resolver, KEY, userId);
        if (value == null || value.isEmpty()) return modes;
        try {
            final JSONObject object = new JSONObject(value);
            final Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                final String name = keys.next();
                final int mode = object.optInt(name, ALLOW);
                if (mode == BLOCK_ON_OPEN || mode == DENY) modes.put(name, mode);
            }
        } catch (JSONException ignored) { }
        return modes;
    }

    public static int getMode(ContentResolver resolver, String packageName, int userId) {
        return read(resolver, userId).getOrDefault(packageName, ALLOW);
    }

    public static boolean setMode(ContentResolver resolver, String packageName, int userId,
            int mode) {
        return setMode(resolver, List.of(packageName), userId, mode);
    }

    public static boolean setMode(ContentResolver resolver, List<String> packages, int userId,
            int mode) {
        if (mode < ALLOW || mode > DENY) throw new IllegalArgumentException("Invalid motion mode");
        final Map<String, Integer> modes = read(resolver, userId);
        for (String packageName : packages) {
            if (mode == ALLOW) modes.remove(packageName);
            else modes.put(packageName, mode);
        }
        return Settings.Secure.putStringForUser(resolver, KEY,
                new JSONObject(modes).toString(), userId);
    }
}
