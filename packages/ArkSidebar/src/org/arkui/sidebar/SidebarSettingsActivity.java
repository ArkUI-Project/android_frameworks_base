/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import static com.android.internal.arkui.SidebarContract.PINNED;

import android.os.Bundle;
import android.os.UserManager;

import androidx.fragment.app.FragmentTransaction;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.ExpressiveDesignEnabledProvider;

/** Uses the same MD3 Expressive toolbar and preference components as system Settings. */
public final class SidebarSettingsActivity extends CollapsingToolbarBaseActivity
        implements ExpressiveDesignEnabledProvider {
    @Override public boolean isExpressiveDesignEnabled() { return true; }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getSystemService(UserManager.class).isManagedProfile()) { finish(); return; }
        setContentView(R.layout.sidebar_settings);
        if (saved == null) {
            navigate(getIntent().getBooleanExtra("edit_apps", false) ? PINNED : "main", false);
        }
    }

    void navigate(String page, boolean addToBackStack) {
        var transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true);
        if (addToBackStack) {
            // AndroidX reverses this to MATCH_ACTIVITY_CLOSE when the back stack is popped.
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_MATCH_ACTIVITY_OPEN)
                    .addToBackStack(page);
        }
        transaction.replace(R.id.sidebar_settings_content, SidebarSettingsFragment.create(page));
        transaction.commit();
    }

    @Override public boolean onNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
