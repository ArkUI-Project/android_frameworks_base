/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.os.Bundle;
import android.content.Intent;
import android.os.UserManager;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.ExpressiveDesignEnabledProvider;

public final class WheelSettingsActivity extends CollapsingToolbarBaseActivity
        implements ExpressiveDesignEnabledProvider {
    @Override public boolean isExpressiveDesignEnabled() { return true; }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getSystemService(UserManager.class).isManagedProfile()) { finish(); return; }
        setContentView(R.layout.wheel_settings);
        if (state == null) navigate(getIntent().getBooleanExtra("edit_items", false) ? "items" : "main", false);
    }
    void navigate(String page, boolean stack) {
        var transaction = getSupportFragmentManager().beginTransaction().setReorderingAllowed(true);
        if (stack) transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_MATCH_ACTIVITY_OPEN).addToBackStack(page);
        transaction.replace(R.id.wheel_settings_content, WheelSettingsFragment.create(page)).commit();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        navigate(intent.getBooleanExtra("edit_items", false) ? "items" : "main", false);
    }
    @Override public boolean onNavigateUp() { getOnBackPressedDispatcher().onBackPressed(); return true; }
}
