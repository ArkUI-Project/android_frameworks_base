/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Named recordings scoped to one game and orientation, including the original single slot. */
final class ComboStore {
    record Combo(String id, String name, int[] actions, int repeat) {
        int count() { return actions.length / 6; }
    }
    private final SharedPreferences preferences;

    ComboStore(SharedPreferences preferences) { this.preferences = preferences; }

    List<Combo> list(String game, int orientation, String legacyName) {
        String key = key(game, orientation);
        if (!preferences.contains(key)) {
            String oldKey = "combo:" + game + ":" + orientation;
            try {
                int[] actions = decode(new JSONArray(preferences.getString(oldKey, "[]")));
                var migrated = new Combo("legacy", legacyName, actions, defaultRepeat());
                preferences.edit().putString(key, encode(List.of(migrated)))
                        .remove(oldKey).apply();
            } catch (JSONException | IllegalArgumentException ignored) {
                // Preserve an invalid legacy value rather than replacing it with an empty slot.
            }
        }
        var result = new ArrayList<Combo>();
        try {
            var items = new JSONArray(preferences.getString(key, "[]"));
            for (int i = 0; i < items.length(); i++) {
                try {
                    var item = items.getJSONObject(i);
                    String id = item.getString("id"), name = item.getString("name");
                    int repeat = item.getInt("repeat");
                    if (id.isBlank() || name.isBlank() || repeat < 0
                            || result.stream().anyMatch(c -> c.id().equals(id))) continue;
                    result.add(new Combo(id, name, decode(item.getJSONArray("actions")), repeat));
                } catch (JSONException | IllegalArgumentException ignored) { }
            }
        } catch (JSONException ignored) { }
        return result;
    }

    Combo save(String game, int orientation, String name, JSONArray actions, String legacyName) {
        if (name.isBlank()) throw new IllegalArgumentException("Empty combo name");
        int[] recorded;
        try { recorded = decode(actions); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        List<Combo> items = list(game, orientation, legacyName);
        var combo = new Combo(UUID.randomUUID().toString(), name.strip(), recorded, defaultRepeat());
        items.add(combo);
        write(game, orientation, items);
        return combo;
    }

    boolean update(String game, int orientation, String id, String name, int repeat, String legacyName) {
        if (name.isBlank() || repeat < 0) return false;
        List<Combo> items = list(game, orientation, legacyName);
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) {
            var original = items.get(i);
            items.set(i, new Combo(id, name.strip(), original.actions(), repeat));
            write(game, orientation, items);
            return true;
        }
        return false;
    }

    void delete(String game, int orientation, String id, String legacyName) {
        List<Combo> items = list(game, orientation, legacyName);
        if (items.removeIf(c -> c.id().equals(id))) write(game, orientation, items);
    }

    private int defaultRepeat() { return Math.max(0, preferences.getInt("repeat", 1)); }
    private static String key(String game, int orientation) { return "combos:" + game + ":" + orientation; }
    private void write(String game, int orientation, List<Combo> items) {
        preferences.edit().putString(key(game, orientation), encode(items)).apply();
    }
    private static String encode(List<Combo> items) {
        var encoded = new JSONArray();
        for (Combo combo : items) {
            var actions = new JSONArray();
            for (int i = 0; i < combo.actions().length; i += 6) {
                var action = new JSONArray();
                for (int j = 0; j < 6; j++) action.put(combo.actions()[i + j]);
                actions.put(action);
            }
            try {
                encoded.put(new JSONObject().put("id", combo.id()).put("name", combo.name())
                        .put("repeat", combo.repeat()).put("actions", actions));
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        return encoded.toString();
    }
    private static int[] decode(JSONArray recorded) throws JSONException {
        if (recorded.length() == 0) throw new IllegalArgumentException("Empty recording");
        int[] actions = new int[recorded.length() * 6];
        for (int i = 0; i < recorded.length(); i++) {
            JSONArray action = recorded.getJSONArray(i);
            if (action.length() != 6) throw new IllegalArgumentException("Invalid action");
            for (int j = 0; j < 6; j++) {
                int value = action.getInt(j);
                if (value < 0 || (j < 4 && value > 10000)) {
                    throw new IllegalArgumentException("Invalid action value");
                }
                actions[i * 6 + j] = value;
            }
        }
        return actions;
    }
}
