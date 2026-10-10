/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.os.Bundle;
import android.os.UserManager;
import android.view.View;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.AppSwitchPreference;
import com.android.settingslib.widget.ExpressiveDesignEnabledProvider;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;
import com.android.settingslib.widget.TopIntroPreference;

public final class RecognitionSettingsActivity extends CollapsingToolbarBaseActivity
        implements ExpressiveDesignEnabledProvider {
    @Override public boolean isExpressiveDesignEnabled() { return true; }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getSystemService(UserManager.class).isManagedProfile()) { finish(); return; }
        setTitle(R.string.app_name); setContentView(R.layout.recognition_settings);
        if (state == null) getSupportFragmentManager().beginTransaction()
                .replace(R.id.recognition_settings_content, new SettingsFragment()).commit();
    }
    @Override public boolean onNavigateUp() { getOnBackPressedDispatcher().onBackPressed(); return true; }

    public static final class SettingsFragment extends SettingsBasePreferenceFragment {
        @Override public void onCreatePreferences(Bundle state, String key) {
            var context = getPreferenceManager().getContext();
            RecognitionPreferences prefs = new RecognitionPreferences(context);
            var root = getPreferenceManager().createPreferenceScreen(context); setPreferenceScreen(root);
            TopIntroPreference intro = new TopIntroPreference(context); intro.setTitle(R.string.recognition_intro); root.addPreference(intro);
            ListPreference language = new ListPreference(context); configure(language, "ocr_language", R.string.ocr_language);
            language.setEntries(new CharSequence[] {getString(R.string.ocr_simplified), getString(R.string.ocr_traditional), getString(R.string.ocr_english)});
            language.setEntryValues(new CharSequence[] {"0", "1", "2"}); language.setValue(Integer.toString(prefs.get("ocr_language", 0, 0, 2)));
            language.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            language.setOnPreferenceChangeListener((p, value) -> { prefs.put("ocr_language", Integer.parseInt((String) value)); return true; }); root.addPreference(language);
            AppSwitchPreference wave = new AppSwitchPreference(context); configure(wave, "recognition_wave", R.string.recognition_wave);
            wave.setChecked(prefs.on("recognition_wave", true));
            wave.setOnPreferenceChangeListener((p, value) -> { prefs.put("recognition_wave", (Boolean) value ? 1 : 0); return true; }); root.addPreference(wave);
            SliderPreference dim = new SliderPreference(context); configure(dim, "recognition_dim", R.string.recognition_dim);
            dim.setMin(10); dim.setMax(70); dim.setSliderIncrement(1); dim.setShowSliderValue(true);
            dim.setValue(prefs.get("recognition_dim", 40, 10, 70)); dim.setUpdatesContinuously(false);
            dim.setLabelFormater(value -> Math.round(value) + "%"); dim.setSliderContentDescription(getString(R.string.recognition_dim));
            dim.setOnPreferenceChangeListener((p, value) -> { prefs.put("recognition_dim", (Integer) value); return true; }); root.addPreference(dim);
        }
        private void configure(Preference preference, String key, int title) {
            preference.setKey(key); preference.setTitle(title); preference.setPersistent(false); preference.setIconSpaceReserved(false);
        }
        @Override public void onViewCreated(View view, Bundle state) {
            super.onViewCreated(view, state); view.setBackgroundColor(RecognitionStyle.surface(requireContext()));
        }
    }
}
