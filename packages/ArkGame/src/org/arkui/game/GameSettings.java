/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import com.android.settingslib.widget.AppSwitchPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.TopIntroPreference;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameSettings extends SettingsBasePreferenceFragment {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private GamePreferences prefs;
    private List<GamePreferences.App> apps = List.of();
    private boolean loaded, manage;
    private String query = "";
    private int loadGeneration;
    private final Runnable reload=this::loadApps;
    private final BroadcastReceiver packages=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING,false)) return;
            main.removeCallbacks(reload); main.postDelayed(reload,200);
        }
    };
    @Override public void onCreatePreferences(Bundle saved, String key) {
        prefs = new GamePreferences(requireContext()); manage = requireArguments().getBoolean("manage");
        if (saved != null) query = saved.getString("query", "");
        build();
    }
    @Override public void onResume() {
        super.onResume(); requireActivity().setTitle(manage ? R.string.manage : R.string.app_name);
        IntentFilter filter=new IntentFilter(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addDataScheme("package");
        requireContext().registerReceiver(packages,filter,Context.RECEIVER_NOT_EXPORTED);
        loadApps();
    }
    @Override public void onPause() {
        requireContext().unregisterReceiver(packages); main.removeCallbacks(reload); ++loadGeneration; super.onPause();
    }
    private void loadApps() {
        if (!isAdded()) return;
        int generation=++loadGeneration;
        Context c = requireContext().getApplicationContext();
        loader.execute(() -> {
            var list = GamePreferences.apps(c);
            main.post(() -> { if (isAdded() && generation==loadGeneration) { apps = list; loaded = true; build(); } });
        });
    }
    @Override public void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("query", query); }
    @Override public void onDestroy() { loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private PreferenceCategory category(int title) {
        var p = new PreferenceCategory(requireContext()); p.setTitle(title); getPreferenceScreen().addPreference(p); return p;
    }
    private Preference action(int title, int summary, Runnable action) {
        var p = new Preference(requireContext()); p.setTitle(title); if (summary != 0) p.setSummary(summary);
        p.setOnPreferenceClickListener(v -> { action.run(); return true; }); return p;
    }
    private void build() {
        var c = requireContext(); var screen = getPreferenceManager().createPreferenceScreen(c); setPreferenceScreen(screen);
        if (!manage) {
            var intro = new TopIntroPreference(c); intro.setTitle(R.string.intro); screen.addPreference(intro);
            var enabled = new MainSwitchPreference(c); enabled.setTitle(R.string.enabled); enabled.setPersistent(false);
            enabled.setChecked(prefs.enabled()); enabled.setOnPreferenceChangeListener((p,v) -> {
                prefs.store.edit().putBoolean("enabled", (boolean)v).apply(); return true; }); screen.addPreference(enabled);
            var library = category(R.string.library);
            library.addPreference(action(R.string.manage, 0, () -> ((GameActivity)requireActivity()).navigate(true)));
            var auto = new SwitchPreferenceCompat(c); auto.setTitle(R.string.auto); auto.setSummary(R.string.auto_summary);
            auto.setPersistent(false); auto.setChecked(prefs.store.getBoolean("auto", true));
            auto.setOnPreferenceChangeListener((p,v) -> { prefs.store.edit().putBoolean("auto", (boolean)v).apply(); main.post(this::build); return true; });
            library.addPreference(auto);
            for (var app : apps) if (prefs.selected(app.pkg(), app.category())) {
                var p = new Preference(c); p.setTitle(app.label()); p.setIcon(app.icon());
                p.setSummary(prefs.store.contains("game:" + app.pkg()) ? R.string.manual : R.string.automatic);
                p.setOnPreferenceClickListener(v -> { Intent intent = c.getPackageManager().getLaunchIntentForPackage(app.pkg());
                    if (intent != null) {
                        var options=android.app.ActivityOptions.makeBasic(); options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
                        startActivity(intent,options.toBundle());
                    }
                    return true; }); library.addPreference(p);
            }
            var tools = category(R.string.tools);
            var notifications = new ListPreference(c); notifications.setTitle(R.string.notifications);
            notifications.setKey("notification_mode");
            notifications.setEntries(new CharSequence[]{getString(R.string.normal),getString(R.string.danmaku),getString(R.string.block)});
            notifications.setEntryValues(new String[]{"0","1","2"}); notifications.setPersistent(false);
            notifications.setValue(Integer.toString(prefs.store.getInt("mode", 0)));
            notifications.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            notifications.setOnPreferenceChangeListener((p,v) -> { prefs.store.edit().putInt("mode", Integer.parseInt((String)v)).apply(); return true; });
            tools.addPreference(notifications);
            var repeat = new ListPreference(c); repeat.setTitle(R.string.repeat); repeat.setPersistent(false);
            repeat.setKey("combo_repeat");
            repeat.setEntries(new String[]{"1","3","5","10"}); repeat.setEntryValues(new String[]{"1","3","5","10"});
            repeat.setValue(Integer.toString(prefs.store.getInt("repeat", 1))); repeat.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            repeat.setOnPreferenceChangeListener((p,v) -> { prefs.store.edit().putInt("repeat", Integer.parseInt((String)v)).apply(); return true; }); tools.addPreference(repeat);
            tools.addPreference(action(R.string.sidebar, R.string.sidebar_summary, () -> startActivity(new Intent("org.arkui.sidebar.SETTINGS").setPackage("org.arkui.sidebar"))));
        } else {
            var search = new EditTextPreference(c); search.setTitle(R.string.search); search.setPersistent(false);
            search.setKey("app_search");
            search.setText(query); search.setSummary(query); search.setOnPreferenceChangeListener((p,v) -> {
                query = v.toString(); main.post(this::build); return true; }); screen.addPreference(search);
            var library = category(R.string.library);
            for (var app : apps) if (app.label().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                    || app.pkg().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                var choice = new AppSwitchPreference(c); choice.setTitle(app.label()); choice.setIcon(app.icon()); choice.setPersistent(false);
                choice.setSummary(app.category() ? getString(R.string.automatic) : app.pkg());
                choice.setChecked(prefs.selected(app.pkg(), app.category()));
                choice.setOnPreferenceChangeListener((p,v) -> { prefs.store.edit().putBoolean("game:" + app.pkg(), (boolean)v).apply(); return true; });
                library.addPreference(choice);
            }
        }
        if (!loaded || apps.stream().noneMatch(a -> prefs.selected(a.pkg(), a.category()))) {
            var hint = new TopIntroPreference(c); hint.setTitle(loaded ? R.string.empty : R.string.loading); screen.addPreference(hint);
        }
    }
}
