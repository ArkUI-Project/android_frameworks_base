/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import static com.android.internal.arkui.SidebarContract.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Process;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AppCatalog {
    static final class App {
        final ComponentName component;
        final String label;
        final Drawable icon;
        final boolean recognition;
        private final String key, packageName, searchLabel, searchPackage;
        App(LauncherActivityInfo info, int density) {
            component = info.getComponentName();
            label = info.getLabel().toString();
            icon = info.getBadgedIcon(density);
            recognition = false;
            key = component.flattenToString();
            packageName = component.getPackageName();
            searchLabel = label.toLowerCase(Locale.getDefault());
            searchPackage = packageName.toLowerCase(Locale.ROOT);
        }
        App(Context context) {
            // This stable catalog identity is persisted like an installed app, but is
            // dispatched locally rather than sent to LauncherApps as an activity.
            component = new ComponentName(PACKAGE, PACKAGE + ".ScreenRecognition");
            label = context.getString(R.string.recognize_screen);
            icon = recognitionIcon(context);
            recognition = true;
            key = component.flattenToString();
            packageName = PACKAGE;
            searchLabel = label.toLowerCase(Locale.getDefault());
            searchPackage = "ocr screen recognition image text 识图 图片 文字";
        }
        String key() { return key; }
        String pkg() { return packageName; }
    }

    static List<App> load(Context context) {
        ArrayList<App> result = new ArrayList<>();
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        int density = context.getResources().getDisplayMetrics().densityDpi;
        for (LauncherActivityInfo info : launcher.getActivityList(null, Process.myUserHandle())) {
            // shutdownNow() cannot cancel a package call already in progress, but it can
            // prevent decoding the remaining icons after this loader loses its owner.
            if (Thread.currentThread().isInterrupted()) return List.of();
            ApplicationInfo app = info.getApplicationInfo();
            if (PACKAGE.equals(app.packageName)
                    || (app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) continue;
            try { result.add(new App(info, density)); }
            catch (RuntimeException ignored) { /* Package may disappear while its icon loads. */ }
        }
        result.add(new App(context));
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    private static Drawable recognitionIcon(Context context) {
        GradientDrawable background = SidebarStyle.rounded(context.getColor(
                com.android.internal.R.color.materialColorPrimaryContainer),
                SidebarStyle.dp(context, 16));
        background.setSize(SidebarStyle.dp(context, 48), SidebarStyle.dp(context, 48));
        Drawable glyph = context.getDrawable(R.drawable.ic_screen_recognition).mutate();
        glyph.setTint(context.getColor(
                com.android.internal.R.color.materialColorOnPrimaryContainer));
        return new LayerDrawable(new Drawable[] {background,
                new InsetDrawable(glyph, SidebarStyle.dp(context, 12))});
    }

    static List<App> rail(List<App> all, SidebarPreferences prefs, String[] recent) {
        ArrayList<App> result = new ArrayList<>();
        List<String> pinned = prefs.list(PINNED);
        if (!prefs.store.contains(PINNED)) {
            // Seed useful installed apps only; never hard-code icons or unavailable packages.
            for (App app : all) {
                if (result.size() == 4) break;
                if (!app.recognition && !"com.android.settings".equals(app.pkg())) result.add(app);
            }
        } else {
            for (String key : pinned) {
                for (App app : all) if (app.key().equals(key)) result.add(app);
            }
        }
        if (prefs.enabled(RECOMMEND)) {
            for (String pkg : recent) {
                for (App app : all) {
                    if (result.size() >= Math.max(7, pinned.size())) break;
                    if (!app.recognition && app.pkg().equals(pkg) && !result.contains(app)) result.add(app);
                }
            }
            if (!prefs.store.contains(PINNED)) {
                for (App app : all) {
                    if (result.size() >= 7) break;
                    if (!app.recognition && !result.contains(app)) result.add(app);
                }
            }
        }
        return result;
    }

    static List<App> filter(List<App> all, String query) {
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return all;
        String labelNeedle = trimmed.toLowerCase(Locale.getDefault());
        String packageNeedle = trimmed.toLowerCase(Locale.ROOT);
        ArrayList<App> result = new ArrayList<>();
        for (App app : all) {
            if (app.searchLabel.contains(labelNeedle)
                    || app.searchPackage.contains(packageNeedle)) result.add(app);
        }
        return result;
    }
}
