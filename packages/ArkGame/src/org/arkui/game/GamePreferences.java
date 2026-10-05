/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GamePreferences {
    final SharedPreferences store;
    GamePreferences(Context c) { store = c.getSharedPreferences("games", Context.MODE_PRIVATE); }
    boolean enabled() { return store.getBoolean("enabled", true); }
    boolean selected(String pkg, boolean category) {
        if (store.contains("game:" + pkg)) return store.getBoolean("game:" + pkg, false);
        return category && store.getBoolean("auto", true);
    }
    int mode(String pkg) { return store.getInt("mode:" + pkg, store.getInt("mode", 0)); }
    boolean guard(String pkg) { return store.getBoolean("guard:" + pkg, false); }
    record App(String pkg, String label, Drawable icon, boolean category) { }
    static List<App> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<App> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (var resolve : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            ApplicationInfo info = resolve.activityInfo.applicationInfo;
            if (!seen.add(info.packageName) || info.packageName.equals(c.getPackageName())) continue;
            result.add(new App(info.packageName, info.loadLabel(pm).toString(), info.loadIcon(pm),
                    info.category == ApplicationInfo.CATEGORY_GAME
                            || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0));
        }
        result.sort(Comparator.comparing(App::label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}
