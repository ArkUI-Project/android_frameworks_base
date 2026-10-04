/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.icu.text.AlphabeticIndex;
import android.os.Process;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WheelCatalog {
    static final class Item {
        final String id, label, section;
        final Drawable icon;
        final ComponentName component;
        final int action;
        Item(String id, String label, Drawable icon, ComponentName component, int action) {
            this(id, label, icon, component, action, "#");
        }
        Item(String id, String label, Drawable icon, ComponentName component, int action, String section) {
            this.id = id; this.label = label; this.icon = icon;
            this.component = component; this.action = action; this.section = section;
        }
        String pkg() { return component == null ? "" : component.getPackageName(); }
    }
    static List<Item> load(Context context) {
        List<Item> result = new ArrayList<>();
        AlphabeticIndex.ImmutableIndex<Object> index = new AlphabeticIndex<Object>(
                context.getResources().getConfiguration().getLocales().get(0))
                .addLabels(Locale.ENGLISH).buildImmutableIndex();
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo app : launcher.getActivityList(null, Process.myUserHandle())) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            if ((app.getApplicationInfo().flags & ApplicationInfo.FLAG_SUSPENDED) != 0) continue;
            try {
                ComponentName component = app.getComponentName();
                String label = app.getLabel().toString();
                String section = index.getBucket(index.getBucketIndex(label)).getLabel();
                if (section.isBlank() || "…".equals(section)) section = "#";
                result.add(new Item(component.flattenToString(), label,
                        app.getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi), component, 0, section));
            } catch (RuntimeException ignored) { /* A package can disappear during enumeration. */ }
        }
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> {
            int bucket = Integer.compare(index.getBucketIndex(a.label), index.getBucketIndex(b.label));
            return bucket != 0 ? bucket : collator.compare(a.label, b.label);
        });
        return result;
    }
    static List<Item> actions(Context c) {
        int[] labels = {R.string.action_back, R.string.action_home, R.string.action_recents,
                R.string.action_notifications, R.string.action_quick_settings, R.string.action_screenshot,
                R.string.action_lock, R.string.action_flashlight, R.string.recognize};
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            result.add(new Item("action:" + (i + 1), c.getString(labels[i]),
                    new WheelStyle.Symbol(c, i + 1), null, i + 1));
        }
        return result;
    }
    static List<Item> selected(Context c, WheelPreferences prefs, List<Item> apps) {
        List<Item> all = new ArrayList<>(apps); all.addAll(actions(c));
        List<Item> result = new ArrayList<>();
        if (prefs.store.contains(WheelPreferences.ITEMS)) {
            for (String id : prefs.list(WheelPreferences.ITEMS)) {
                for (Item item : all) if (id.equals(item.id) && !result.contains(item)) result.add(item);
            }
        } else {
            for (Item item : apps) { if (result.size() == 6) break; result.add(item); }
            for (Item item : actions(c)) { if (result.size() == 6) break; result.add(item); }
        }
        return result;
    }
    static List<Item> recent(List<Item> apps, String[] packages) {
        List<Item> result = new ArrayList<>();
        for (String pkg : packages) {
            for (Item item : apps) if (pkg.equals(item.pkg())) { result.add(item); break; }
        }
        return result;
    }
    static List<Item> filter(List<Item> apps, String query) {
        String value = query.strip().toLowerCase(Locale.getDefault());
        if (value.isEmpty()) return apps;
        List<Item> result = new ArrayList<>();
        for (Item app : apps) if (app.label.toLowerCase(Locale.getDefault()).contains(value)
                || app.pkg().toLowerCase(Locale.ROOT).contains(value)) result.add(app);
        return result;
    }
}
