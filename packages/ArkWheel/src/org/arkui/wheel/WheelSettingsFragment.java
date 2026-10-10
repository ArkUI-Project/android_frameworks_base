/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.arkui.wheel.WheelPreferences.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.android.settingslib.widget.AppSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;
import com.android.settingslib.widget.TopIntroPreference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WheelSettingsFragment extends SettingsBasePreferenceFragment {
    private WheelPreferences prefs;
    private String page, query = "";
    private List<WheelCatalog.Item> apps = List.of();
    private boolean loaded;
    private WheelShortcutsAdapter shortcutsAdapter;
    private ItemTouchHelper shortcutsTouchHelper;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    static WheelSettingsFragment create(String page) {
        WheelSettingsFragment f = new WheelSettingsFragment();
        Bundle args = new Bundle(); args.putString("page", page); f.setArguments(args); return f;
    }
    @Override public void onCreatePreferences(Bundle state, String key) {
        prefs = new WheelPreferences(requireContext()); page = requireArguments().getString("page", "main");
        if (state != null) query = state.getString("query", "");
        build();
        if (appPage()) {
            Context app = requireContext().getApplicationContext();
            loader.execute(() -> {
                List<WheelCatalog.Item> result;
                try { result = WheelCatalog.load(app); } catch (RuntimeException e) { result = List.of(); }
                final List<WheelCatalog.Item> found = result;
                main.post(() -> { if (isAdded()) { apps = found; loaded = true; build(); } });
            });
        }
    }
    @Override public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state); view.setBackgroundColor(WheelStyle.surface(requireContext()));
    }
    @Override public void onResume() { super.onResume(); build(); requireActivity().setTitle(title()); }
    @Override public void onPause() {
        if (shortcutsAdapter != null) shortcutsAdapter.persist();
        super.onPause();
    }
    @Override public RecyclerView.Adapter<?> onCreateAdapter(PreferenceScreen screen) {
        releaseShortcuts();
        RecyclerView.Adapter<?> settings = super.onCreateAdapter(screen);
        if (!ITEMS.equals(page) || !loaded) return settings;
        Preference empty = screen.findPreference("empty_shortcuts");
        shortcutsAdapter = new WheelShortcutsAdapter(prefs, WheelCatalog.selected(c(), prefs, apps),
                empty::setVisible);
        return new ConcatAdapter(settings, shortcutsAdapter);
    }
    @Override protected void onBindPreferences() {
        super.onBindPreferences();
        if (shortcutsAdapter != null) {
            shortcutsTouchHelper = shortcutsAdapter.touchHelper();
            shortcutsTouchHelper.attachToRecyclerView(getListView());
        }
    }
    @Override protected void onUnbindPreferences() {
        releaseShortcuts();
        super.onUnbindPreferences();
    }
    private void releaseShortcuts() {
        if (shortcutsAdapter != null) shortcutsAdapter.persist();
        if (shortcutsTouchHelper != null) shortcutsTouchHelper.attachToRecyclerView(null);
        shortcutsTouchHelper = null;
        shortcutsAdapter = null;
    }
    @Override public void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("query", query); }
    @Override public void onDestroy() { loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private boolean appPage() { return ITEMS.equals(page) || EXCLUDED.equals(page) || "add".equals(page); }
    private Context c() { return getPreferenceManager().getContext(); }
    private int title() {
        switch (page) {
            case ITEMS: return R.string.items;
            case EXCLUDED: return R.string.excluded;
            case "add": return R.string.add;
            case "appearance": return R.string.appearance;
            case "invocation": return R.string.invocation_title;
            case "behavior": return R.string.behavior;
            default: return R.string.app_name;
        }
    }
    private void configure(Preference p, String key, int title) {
        p.setKey(key); p.setTitle(title); p.setPersistent(false); p.setIconSpaceReserved(false);
    }
    private PreferenceCategory group(PreferenceGroup root, int title) {
        PreferenceCategory g = new PreferenceCategory(c()); g.setTitle(title); g.setIconSpaceReserved(false); root.addPreference(g); return g;
    }
    private void help(PreferenceGroup root, int text) {
        TopIntroPreference p = new TopIntroPreference(c()); p.setTitle(text); root.addPreference(p);
    }
    private Preference link(PreferenceGroup root, int title, int summary, Runnable run) {
        Preference p = new Preference(c()); configure(p, "link_" + title, title);
        if (summary != 0) p.setSummary(summary);
        p.setOnPreferenceClickListener(v -> { run.run(); return true; }); root.addPreference(p); return p;
    }
    private void navigate(String page) { ((WheelSettingsActivity) requireActivity()).navigate(page, true); }
    private void toggle(PreferenceGroup root, String key, int title, int summary, boolean fallback) {
        AppSwitchPreference p = new AppSwitchPreference(c()); configure(p, key, title);
        if (summary != 0) p.setSummary(summary); p.setChecked(prefs.on(key, fallback));
        p.setOnPreferenceChangeListener((v, value) -> { prefs.put(key, (Boolean) value ? 1 : 0); return true; }); root.addPreference(p);
    }
    private void slider(PreferenceGroup root, String key, int title, int fallback, int min, int max, String unit) {
        SliderPreference p = new SliderPreference(c()); configure(p, key, title);
        p.setMax(max); p.setMin(min); p.setSliderIncrement(1); p.setShowSliderValue(true);
        p.setValue(prefs.get(key, fallback, min, max)); p.setUpdatesContinuously(false);
        p.setLabelFormater(value -> Math.round(value) + unit); p.setSliderContentDescription(getString(title));
        p.setSummary(prefs.get(key, fallback, min, max) + unit);
        p.setOnPreferenceChangeListener((v, value) -> { prefs.put(key, (Integer) value); p.setSummary(value + unit); return true; }); root.addPreference(p);
    }
    private void choice(PreferenceGroup root, String key, int title, int fallback, int... entries) {
        ListPreference p = new ListPreference(c()); configure(p, key, title);
        String[] labels = new String[entries.length], values = new String[entries.length];
        for (int i = 0; i < entries.length; i++) { labels[i] = getString(entries[i]); values[i] = Integer.toString(i); }
        p.setEntries(labels); p.setEntryValues(values); p.setValue(values[prefs.get(key, fallback, 0, values.length - 1)]);
        p.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        p.setOnPreferenceChangeListener((v, value) -> { prefs.put(key, Integer.parseInt((String) value)); return true; }); root.addPreference(p);
    }
    private void build() {
        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(c()); setPreferenceScreen(root);
        if (appPage()) { buildApps(root); return; }
        switch (page) {
            case "appearance":
                slider(root, RADIUS, R.string.radius, 280, 180, 360, " dp");
                slider(root, ICON_SIZE, R.string.icon_size, 44, 32, 64, " dp");
                slider(root, COUNT, R.string.count, 6, 3, 10, "");
                slider(root, ARC, R.string.arc, 74, 50, 80, "°");
                slider(root, ANIMATION, R.string.animation, 100, 0, 200, "%");
                help(root, R.string.animation_help);
                slider(root, DIM, R.string.dim, 12, 0, 50, "%");
                toggle(root, MONOCHROME, R.string.monochrome, R.string.monochrome_summary, true);
                break;
            case "invocation":
                help(root, R.string.corner_gesture_help);
                choice(root, SIDE, R.string.side, 0, R.string.right, R.string.left, R.string.both);
                slider(root, CORNER_SIZE, R.string.corner_size, CORNER_SIZE_DEFAULT, CORNER_SIZE_MIN, CORNER_SIZE_MAX, " dp");
                help(root, R.string.corner_size_help);
                break;
            case "behavior":
                choice(root, LAUNCH_MODE, R.string.launch_mode, 0, R.string.small_window, R.string.fullscreen);
                help(root, R.string.launch_help);
                toggle(root, HAPTICS, R.string.haptics, 0, true);
                toggle(root, RECENT, R.string.recent, 0, true);
                toggle(root, LANDSCAPE, R.string.landscape, 0, false);
                link(root, R.string.excluded, R.string.excluded_summary, () -> navigate(EXCLUDED));
                break;
            default:
                help(root, R.string.intro);
                link(root, R.string.preview, R.string.preview_summary, () -> requireContext().sendBroadcast(
                        new Intent(WheelService.PREVIEW).setPackage(requireContext().getPackageName())));
                link(root, R.string.items, R.string.items_summary, () -> navigate(ITEMS));
                link(root, R.string.invocation_title, R.string.invocation_summary, () -> navigate("invocation"));
                link(root, R.string.appearance, R.string.appearance_summary, () -> navigate("appearance"));
                link(root, R.string.behavior, R.string.behavior_summary, () -> navigate("behavior"));
                link(root, R.string.reset, 0, () -> new AlertDialog.Builder(c()).setTitle(R.string.reset)
                        .setMessage(R.string.reset_message).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.reset, (d, which) -> {
                            String excluded = prefs.store.getString(EXCLUDED, "");
                            prefs.store.edit().clear().putString(EXCLUDED, excluded).apply(); build();
                        }).show());
        }
    }
    private List<String> selectedIds() {
        List<String> ids = new ArrayList<>();
        for (WheelCatalog.Item item : WheelCatalog.selected(c(), prefs, apps)) ids.add(item.id);
        return ids;
    }
    private void buildApps(PreferenceScreen root) {
        if (!loaded) { help(root, R.string.loading); return; }
        if (ITEMS.equals(page)) {
            help(root, R.string.order_hint);
            link(root, R.string.add, 0, () -> navigate("add"));
            TopIntroPreference empty = new TopIntroPreference(c());
            empty.setKey("empty_shortcuts");
            empty.setTitle(R.string.empty_pinned);
            empty.setVisible(WheelCatalog.selected(c(), prefs, apps).isEmpty());
            root.addPreference(empty);
            return;
        }
        EditTextPreference search = new EditTextPreference(c()); configure(search, "search", R.string.search);
        search.setText(query); search.setSummary(query);
        search.setOnPreferenceChangeListener((p, value) -> { query = (String) value; main.post(this::build); return true; }); root.addPreference(search);
        boolean exclude = EXCLUDED.equals(page);
        if (!exclude) {
            PreferenceCategory actions = group(root, R.string.actions);
            for (WheelCatalog.Item item : WheelCatalog.actions(c())) appToggle(actions, item, false);
        }
        List<WheelCatalog.Item> filtered = WheelCatalog.filter(apps, query);
        if (filtered.isEmpty()) help(root, R.string.empty);
        PreferenceCategory applications = group(root, R.string.apps);
        for (WheelCatalog.Item item : filtered) appToggle(applications, item, exclude);
    }
    private void appToggle(PreferenceGroup root, WheelCatalog.Item item, boolean excluded) {
        String key = excluded ? EXCLUDED : ITEMS, id = excluded ? item.pkg() : item.id;
        // Multiple launcher activities share one exclusion switch.
        if (root.findPreference(id) != null) return;
        List<String> ids = excluded ? prefs.list(key) : selectedIds();
        AppSwitchPreference p = new AppSwitchPreference(c()); p.setKey(id); p.setPersistent(false);
        p.setTitle(item.label); p.setIcon(item.icon); p.setChecked(ids.contains(id));
        p.setOnPreferenceChangeListener((v, value) -> {
            List<String> current = excluded ? prefs.list(key) : selectedIds();
            if ((Boolean) value) { if (!current.contains(id)) current.add(id); } else current.remove(id);
            prefs.putList(key, current); return true;
        }); root.addPreference(p);
    }
}
