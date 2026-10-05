/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import static com.android.internal.arkui.SidebarContract.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.PopupMenu;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.AppSwitchPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;
import com.android.settingslib.widget.TopIntroPreference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SidebarSettingsFragment extends SettingsBasePreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Runnable rebuild = () -> {
        if (!isAdded()) return;
        if (isAppPage()) updateAppChoices(); else buildPreferences();
    };
    private SidebarPreferences prefs;
    private String page, query = "";
    private List<AppCatalog.App> apps = List.of();
    private boolean loaded;
    private final List<Runnable> preferenceBindings = new ArrayList<>();
    private final Map<String, AppChoice> appChoices = new LinkedHashMap<>();
    private TopIntroPreference loadingHint, emptyHint;

    static SidebarSettingsFragment create(String page) {
        SidebarSettingsFragment fragment = new SidebarSettingsFragment();
        Bundle args = new Bundle(); args.putString("page", page); fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreatePreferences(Bundle saved, String rootKey) {
        prefs = new SidebarPreferences(requireContext());
        page = requireArguments().getString("page", "main");
        if (saved != null) query = saved.getString("query", "");
        buildPreferences();
        // Load icons only on app selection pages, never on the main settings page.
        if (isAppPage()) {
            Context application = requireContext().getApplicationContext();
            loader.execute(() -> {
                List<AppCatalog.App> result;
                try { result = AppCatalog.load(application); }
                catch (RuntimeException e) { result = List.of(); }
                final List<AppCatalog.App> loadedApps = result;
                main.post(() -> {
                    if (!isAdded()) return;
                    apps = loadedApps; loaded = true;
                    if (PINNED.equals(page) && !prefs.store.contains(PINNED)) {
                        List<String> seed = new ArrayList<>();
                        for (AppCatalog.App app : AppCatalog.rail(apps, prefs, new String[0])) {
                            seed.add(app.key());
                        }
                        prefs.putList(PINNED, seed);
                    }
                    refresh();
                });
            });
        }
    }

    @Override public void onViewCreated(View view, Bundle saved) {
        super.onViewCreated(view, saved);
        // Each page needs its own surface while the outgoing page animates underneath it.
        TypedArray colors = requireContext().obtainStyledAttributes(
                new int[] { android.R.attr.colorBackground });
        view.setBackgroundColor(colors.getColor(0,
                requireContext().getColor(com.android.internal.R.color.materialColorSurfaceContainer)));
        colors.recycle();
    }

    @Override public void onResume() {
        super.onResume();
        requireActivity().setTitle(pageTitle());
        prefs.store.registerOnSharedPreferenceChangeListener(this);
        for (Runnable binding : preferenceBindings) binding.run();
        if (isAppPage()) refresh();
    }

    @Override public void onPause() {
        prefs.store.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("query", query);
    }

    @Override public void onDestroy() {
        loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences shared, String key) {
        if (SidebarPreferences.isPositionKey(key)) updatePosition();
        else if (key == null || (isAppPage() && page.equals(key))) refresh();
    }

    private void refresh() { main.removeCallbacks(rebuild); main.post(rebuild); }

    private void updatePosition() {
        SliderPreference position = findPreference(POSITION);
        if (position != null) {
            int value = prefs.get(POSITION, 35, 0, 100);
            position.setValue(value); position.setSummary(value + "%");
        }
    }

    private boolean isAppPage() { return PINNED.equals(page) || EXCLUDED.equals(page); }

    private int pageTitle() {
        switch (page) {
            case "appearance": return R.string.appearance;
            case "gestures": return R.string.gestures;
            case "rules": return R.string.advanced;
            case PINNED: return R.string.apps;
            case EXCLUDED: return R.string.excluded;
            default: return R.string.app_name;
        }
    }

    private void buildPreferences() {
        preferenceBindings.clear();
        appChoices.clear(); loadingHint = null; emptyHint = null;
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(preferenceContext());
        setPreferenceScreen(screen);
        if (isAppPage()) { appPage(screen); return; }
        switch (page) {
            case "appearance": appearance(screen); break;
            case "gestures": gestures(screen); break;
            case "rules": rules(screen); break;
            default: mainPage(screen);
        }
    }

    private Context preferenceContext() { return getPreferenceManager().getContext(); }

    private PreferenceCategory category(PreferenceGroup parent, int title) {
        PreferenceCategory group = new PreferenceCategory(preferenceContext());
        group.setTitle(title); group.setIconSpaceReserved(false); parent.addPreference(group);
        return group;
    }

    private void configure(Preference item, String key, int title) {
        item.setKey(key); item.setTitle(title); item.setPersistent(false);
        item.setIconSpaceReserved(false);
    }

    private void toggle(PreferenceGroup group, String key, int title, int summary) {
        AppSwitchPreference item = new AppSwitchPreference(preferenceContext());
        configure(item, key, title); if (summary != 0) item.setSummary(summary);
        item.setChecked(prefs.enabled(key));
        preferenceBindings.add(() -> item.setChecked(prefs.enabled(key)));
        item.setOnPreferenceChangeListener((p, value) -> {
            prefs.put(key, (Boolean) value ? 1 : 0); return true;
        });
        group.addPreference(item);
    }

    private void link(PreferenceGroup group, int title, int summary, Runnable action) {
        Preference item = new Preference(preferenceContext());
        configure(item, "link_" + title, title); if (summary != 0) item.setSummary(summary);
        item.setOnPreferenceClickListener(p -> { action.run(); return true; });
        group.addPreference(item);
    }

    private TopIntroPreference help(PreferenceGroup group, int... texts) {
        StringBuilder description = new StringBuilder();
        for (int text : texts) {
            if (description.length() > 0) description.append("\n\n");
            description.append(getString(text));
        }
        // This SettingsLib component is excluded from the rounded preference groups.
        TopIntroPreference help = new TopIntroPreference(preferenceContext());
        help.setTitle(description);
        group.addPreference(help);
        return help;
    }

    private void navigate(String destination) {
        ((SidebarSettingsActivity) requireActivity()).navigate(destination, true);
    }

    private void choice(PreferenceGroup group, String key, int title, int fallback, int... entries) {
        ListPreference item = new ListPreference(preferenceContext());
        configure(item, key, title);
        String[] labels = new String[entries.length], values = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            labels[i] = getString(entries[i]); values[i] = Integer.toString(i);
        }
        item.setEntries(labels); item.setEntryValues(values);
        item.setValue(values[prefs.get(key, fallback, 0, entries.length - 1)]);
        preferenceBindings.add(() -> item.setValue(values[prefs.get(key, fallback, 0, entries.length - 1)]));
        item.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        item.setOnPreferenceChangeListener((p, value) -> {
            prefs.put(key, Integer.parseInt((String) value)); return true;
        });
        group.addPreference(item);
    }

    private void slider(PreferenceGroup group, String key, int title,
            int fallback, int min, int max, String unit) {
        SliderPreference item = new SliderPreference(preferenceContext());
        configure(item, key, title);
        item.setMax(max); item.setMin(min); item.setSliderIncrement(1);
        item.setValue(prefs.get(key, fallback, min, max));
        item.setShowSliderValue(true); item.setUpdatesContinuously(false);
        item.setLabelFormater(value -> Math.round(value) + unit);
        item.setSliderContentDescription(getString(title));
        item.setSummary(prefs.get(key, fallback, min, max) + unit);
        preferenceBindings.add(() -> {
            int value = prefs.get(key, fallback, min, max);
            item.setValue(value); item.setSummary(value + unit);
        });
        item.setOnPreferenceChangeListener((p, value) -> {
            prefs.put(key, (Integer) value); item.setSummary(value + unit); return true;
        });
        group.addPreference(item);
    }

    private void mainPage(PreferenceScreen screen) {
        MainSwitchPreference enabled = new MainSwitchPreference(preferenceContext());
        configure(enabled, ENABLED, R.string.enabled); enabled.setSummary(R.string.enabled_summary);
        enabled.setChecked(prefs.enabled(ENABLED));
        preferenceBindings.add(() -> enabled.setChecked(prefs.enabled(ENABLED)));
        enabled.setOnPreferenceChangeListener((p, value) -> {
            prefs.put(ENABLED, (Boolean) value ? 1 : 0); return true;
        });
        screen.addPreference(enabled);
        PreferenceCategory invocation = category(screen, R.string.invocation);
        choice(invocation, MODE, R.string.invocation, HANDLE,
                R.string.mode_handle, R.string.mode_hold, R.string.mode_both);
        help(invocation, R.string.handle_reposition_help, R.string.hold_help);
        PreferenceCategory apps = category(screen, R.string.apps);
        toggle(apps, RECOMMEND, R.string.recommend, 0);
        link(apps, R.string.apps, R.string.apps_summary, () -> navigate(PINNED));
        PreferenceCategory options = category(screen, R.string.sidebar_options);
        link(options, R.string.appearance, 0, () -> navigate("appearance"));
        link(options, R.string.gestures, 0, () -> navigate("gestures"));
        link(options, R.string.advanced, 0, () -> navigate("rules"));
    }

    private void appearance(PreferenceScreen screen) {
        help(screen, R.string.handle_reposition_help);
        PreferenceCategory handle = category(screen, R.string.side);
        choice(handle, SIDE, R.string.side, 0, R.string.left, R.string.right, R.string.both_edges);
        slider(handle, POSITION, R.string.position, 35, 0, 100, "%");
        slider(handle, HANDLE_HEIGHT, R.string.height, 68, 36, 160, " dp");
        slider(handle, HANDLE_OPACITY, R.string.handle_opacity, 45, 10, 100, "%");
        PreferenceCategory panel = category(screen, R.string.appearance);
        slider(panel, ICON_SIZE, R.string.icon_size, 44, 32, 56, " dp");
        toggle(panel, LANDSCAPE, R.string.landscape, 0);
    }

    private void gestures(PreferenceScreen screen) {
        choice(screen, MODE, R.string.invocation, HANDLE,
                R.string.mode_handle, R.string.mode_hold, R.string.mode_both);
        help(screen, R.string.hold_help);
        slider(screen, HOLD_DELAY, R.string.hold_delay, 300, 150, 800, " ms");
        slider(screen, HOLD_DISTANCE, R.string.hold_distance, 48, 24, 120, " dp");
        slider(screen, ANIMATION, R.string.animation, 100, 0, 200, "%");
        toggle(screen, HAPTICS, R.string.haptics, 0);
        link(screen, R.string.configure_navigation, R.string.gesture_navigation_required, () -> {
            Intent intent = new Intent("android.settings.GESTURE_NAVIGATION_SETTINGS");
            if (intent.resolveActivity(requireContext().getPackageManager()) == null) {
                intent = new Intent(Settings.ACTION_SETTINGS);
            }
            startActivity(intent);
        });
    }

    private void rules(PreferenceScreen screen) {
        choice(screen, LAUNCH_MODE, R.string.launch_mode, 0, R.string.small_window, R.string.fullscreen);
        link(screen, R.string.excluded, 0, () -> navigate(EXCLUDED));
    }

    private void appPage(PreferenceScreen screen) {
        EditTextPreference search = new EditTextPreference(preferenceContext());
        configure(search, "search", R.string.search); search.setText(query);
        search.setOrder(0);
        search.setSummary(query.isEmpty() ? getString(R.string.search) : query);
        search.setOnPreferenceChangeListener((p, value) -> {
            query = (String) value;
            search.setSummary(query.isEmpty() ? getString(R.string.search) : query);
            refresh(); return true;
        });
        screen.addPreference(search);
        if (PINNED.equals(page)) help(screen, R.string.reorder_hint).setOrder(1);
        loadingHint = help(screen, R.string.loading); loadingHint.setOrder(2);
        emptyHint = help(screen, R.string.no_apps); emptyHint.setOrder(3);
        updateAppChoices();
    }

    private void updateAppChoices() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || loadingHint == null || emptyHint == null) return;
        loadingHint.setVisible(!loaded);
        if (!loaded) { emptyHint.setVisible(false); return; }
        // Keep preference identities stable so checking or reordering one app does
        // not recreate the screen, search dialog, or every application's row.
        for (AppCatalog.App app : apps) {
            if (EXCLUDED.equals(page) && app.recognition) continue;
            String id = PINNED.equals(page) ? app.key() : app.pkg();
            if (appChoices.containsKey(id)) continue;
            AppChoice item = new AppChoice(preferenceContext(), id);
            item.setKey(id); item.setTitle(app.label); item.setIcon(app.icon);
            item.setPersistent(false);
            item.setOnPreferenceChangeListener((p, value) -> {
                List<String> values = prefs.list(page);
                if ((Boolean) value && !values.contains(id)) values.add(id);
                else if (!(Boolean) value) values.remove(id);
                prefs.putList(page, values); return true;
            });
            appChoices.put(id, item);
            screen.addPreference(item);
        }
        Map<String, Integer> selectedOrder = new HashMap<>();
        List<String> selected = prefs.list(page);
        for (int i = 0; i < selected.size(); i++) selectedOrder.putIfAbsent(selected.get(i), i);
        Map<String, AppCatalog.App> matches = new LinkedHashMap<>();
        for (AppCatalog.App app : AppCatalog.filter(apps, query)) {
            if (EXCLUDED.equals(page) && app.recognition) continue;
            matches.putIfAbsent(PINNED.equals(page) ? app.key() : app.pkg(), app);
        }
        Map<String, Integer> matchOrder = new HashMap<>();
        int index = 0;
        for (String id : matches.keySet()) matchOrder.put(id, index++);
        for (Map.Entry<String, AppChoice> entry : appChoices.entrySet()) {
            String id = entry.getKey();
            AppChoice item = entry.getValue();
            AppCatalog.App app = matches.get(id);
            item.setVisible(app != null);
            item.setChecked(selectedOrder.containsKey(id));
            if (app != null) {
                item.setTitle(app.label); item.setIcon(app.icon);
                int order = PINNED.equals(page)
                        ? selectedOrder.getOrDefault(id, selected.size() + matchOrder.get(id))
                        : matchOrder.get(id);
                item.setOrder(4 + order);
            }
        }
        emptyHint.setVisible(matches.isEmpty());
    }

    private boolean move(String id, int direction) {
        List<String> values = prefs.list(PINNED);
        int from = values.indexOf(id), to = from + direction;
        if (from < 0 || to < 0 || to >= values.size()) return false;
        Collections.swap(values, from, to); prefs.putList(PINNED, values); return true;
    }

    private final class AppChoice extends AppSwitchPreference {
        private final String id;
        AppChoice(Context context, String id) { super(context); this.id = id; }
        @Override public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            boolean reorderable = PINNED.equals(page) && isChecked();
            holder.itemView.setOnLongClickListener(reorderable ? view -> {
                PopupMenu menu = new PopupMenu(requireContext(), view);
                List<String> values = prefs.list(PINNED); int index = values.indexOf(id);
                menu.getMenu().add(R.string.move_up).setEnabled(index > 0)
                        .setOnMenuItemClickListener(item -> move(id, -1));
                menu.getMenu().add(R.string.move_down).setEnabled(index >= 0 && index < values.size() - 1)
                        .setOnMenuItemClickListener(item -> move(id, 1));
                menu.show(); return true;
            } : null);
            ViewCompat.removeAccessibilityAction(holder.itemView, R.id.sidebar_move_up);
            ViewCompat.removeAccessibilityAction(holder.itemView, R.id.sidebar_move_down);
            if (reorderable) {
                ViewCompat.replaceAccessibilityAction(holder.itemView,
                        new AccessibilityActionCompat(R.id.sidebar_move_up, getString(R.string.move_up)),
                        getString(R.string.move_up), (view, args) -> move(id, -1));
                ViewCompat.replaceAccessibilityAction(holder.itemView,
                        new AccessibilityActionCompat(R.id.sidebar_move_down, getString(R.string.move_down)),
                        getString(R.string.move_down), (view, args) -> move(id, 1));
            }
        }
    }
}
