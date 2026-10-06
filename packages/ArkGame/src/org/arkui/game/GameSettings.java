/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import com.android.settingslib.widget.AppSwitchPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.TopIntroPreference;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameSettings extends SettingsBasePreferenceFragment {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Map<String,Preference> appRows = new LinkedHashMap<>();
    private GamePreferences prefs;
    private List<GamePreferences.App> apps = List.of();
    private PreferenceCategory library;
    private TopIntroPreference hint;
    private boolean loaded, manage;
    private String query = "";
    private int loadGeneration;
    private final Runnable reload = this::loadApps;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING,false)) return;
            main.removeCallbacks(reload); main.postDelayed(reload,200);
        }
    };
    @Override public void onCreatePreferences(Bundle saved,String key) {
        prefs = new GamePreferences(requireContext());
        manage = requireArguments().getBoolean("manage");
        var activity = (GameActivity) requireActivity();
        apps = activity.cachedApps; loaded = activity.appsLoaded;
        if (saved != null) query = saved.getString("query","");
        build();
    }
    @Override public void onResume() {
        super.onResume(); requireActivity().setTitle(manage ? R.string.manage : R.string.app_name);
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED); filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        requireContext().registerReceiver(packages,filter,Context.RECEIVER_NOT_EXPORTED);
        refreshLibrary(); loadApps();
    }
    @Override public void onPause() {
        requireContext().unregisterReceiver(packages); main.removeCallbacks(reload); ++loadGeneration; super.onPause();
    }
    private void loadApps() {
        if (!isAdded()) return;
        int generation = ++loadGeneration;
        Context c = requireContext().getApplicationContext();
        loader.execute(() -> {
            var list = GamePreferences.apps(c);
            main.post(() -> {
                if (!isAdded() || generation != loadGeneration) return;
                apps = list; loaded = true;
                var activity = (GameActivity) requireActivity();
                activity.cachedApps = list; activity.appsLoaded = true;
                // Retain the preference screen and its controls while app rows update.
                refreshLibrary();
            });
        });
    }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("query",query);
    }
    @Override public void onDestroy() {
        loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
    @Override public Animation onCreateAnimation(int transit,boolean enter,int nextAnim) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return null;
        if (transit != FragmentTransaction.TRANSIT_FRAGMENT_OPEN
                && transit != FragmentTransaction.TRANSIT_FRAGMENT_CLOSE) return null;
        float distance = GameStyle.dp(requireContext(),24);
        int direction = transit == FragmentTransaction.TRANSIT_FRAGMENT_OPEN ? 1 : -1;
        if (getResources().getConfiguration().getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL) direction *= -1;
        var motion = new AnimationSet(true);
        motion.addAnimation(new AlphaAnimation(enter ? 0 : 1,enter ? 1 : 0));
        motion.addAnimation(new TranslateAnimation(enter ? distance * direction : 0,
                enter ? 0 : -distance * direction * .5f,0,0));
        motion.setDuration(360);
        // Sample a critically damped spring for the FragmentManager's retained-view animation.
        motion.setInterpolator(value -> (float) ((1 - (1 + 9 * value) * Math.exp(-9 * value))
                / (1 - 10 * Math.exp(-9))));
        return motion;
    }
    private PreferenceCategory category(int title) {
        var p = new PreferenceCategory(requireContext()); p.setTitle(title);
        getPreferenceScreen().addPreference(p); return p;
    }
    private Preference action(int title,int summary,Runnable action) {
        var p = new Preference(requireContext()); p.setTitle(title);
        if (summary != 0) p.setSummary(summary);
        p.setOnPreferenceClickListener(v -> { action.run(); return true; }); return p;
    }
    private void build() {
        var c = requireContext();
        var screen = getPreferenceManager().createPreferenceScreen(c); setPreferenceScreen(screen);
        if (!manage) {
            var intro = new TopIntroPreference(c); intro.setTitle(R.string.intro); screen.addPreference(intro);
            var enabled = new MainSwitchPreference(c); enabled.setTitle(R.string.enabled);
            enabled.setPersistent(false); enabled.setChecked(prefs.enabled());
            enabled.setOnPreferenceChangeListener((p,v) -> {
                prefs.store.edit().putBoolean("enabled",(boolean) v).apply(); return true;
            }); screen.addPreference(enabled);
            library = category(R.string.library);
            var manageGames = action(R.string.manage,0,() -> ((GameActivity) requireActivity()).navigate(true));
            manageGames.setOrder(0); library.addPreference(manageGames);
            var auto = new SwitchPreferenceCompat(c); auto.setTitle(R.string.auto); auto.setSummary(R.string.auto_summary);
            auto.setPersistent(false); auto.setChecked(prefs.store.getBoolean("auto",true)); auto.setOrder(1);
            auto.setOnPreferenceChangeListener((p,v) -> {
                prefs.store.edit().putBoolean("auto",(boolean) v).apply();
                main.post(this::refreshLibrary); return true;
            }); library.addPreference(auto);
        } else {
            var search = new EditTextPreference(c); search.setTitle(R.string.search); search.setPersistent(false);
            search.setKey("app_search"); search.setText(query); search.setSummary(query);
            search.setOnPreferenceChangeListener((p,v) -> {
                query = v.toString(); search.setSummary(query); main.post(this::refreshLibrary); return true;
            }); screen.addPreference(search);
            library = category(R.string.library);
        }
        hint = new TopIntroPreference(c); screen.addPreference(hint);
        refreshLibrary();
    }
    private void refreshLibrary() {
        if (library == null) return;
        if (!manage) { hint.setVisible(false); return; }
        var visible = new HashSet<String>();
        int order = 10;
        for (var app : apps) {
            if (!(app.label().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                    || app.pkg().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))) continue;
            visible.add(app.pkg());
            Preference row = appRows.get(app.pkg());
            if (row == null) {
                row = new AppSwitchPreference(requireContext());
                row.setKey("app:" + app.pkg()); row.setPersistent(false);
                row.setOnPreferenceChangeListener((p,v) -> {
                    prefs.store.edit().putBoolean("game:" + app.pkg(),(boolean) v).apply(); return true;
                });
                library.addPreference(row); appRows.put(app.pkg(),row);
            }
            row.setOrder(order++); row.setTitle(app.label()); row.setIcon(app.icon());
            row.setSummary(app.category() ? getString(R.string.automatic) : app.pkg());
            ((AppSwitchPreference) row).setChecked(prefs.selected(app.pkg(),app.category()));
        }
        var iterator = appRows.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!visible.contains(entry.getKey())) { library.removePreference(entry.getValue()); iterator.remove(); }
        }
        hint.setTitle(!loaded ? R.string.loading : manage && !query.isEmpty() ? R.string.no_results : R.string.empty);
        hint.setVisible(!loaded || visible.isEmpty());
    }
}
