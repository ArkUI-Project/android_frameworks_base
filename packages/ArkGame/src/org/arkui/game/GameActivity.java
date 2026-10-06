/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;
import android.os.Bundle;
import android.os.UserManager;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.ExpressiveDesignEnabledProvider;
public final class GameActivity extends CollapsingToolbarBaseActivity implements ExpressiveDesignEnabledProvider {
    java.util.List<GamePreferences.App> cachedApps = java.util.List.of();
    boolean appsLoaded;
    @Override public boolean isExpressiveDesignEnabled() { return true; }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getSystemService(UserManager.class).isManagedProfile()) { finish(); return; }
        setContentView(R.layout.game_settings);
        if (saved == null) navigate(false);
    }
    void navigate(boolean manage) {
        GameSettings fragment = new GameSettings();
        Bundle args = new Bundle(); args.putBoolean("manage", manage); fragment.setArguments(args);
        var transaction = getSupportFragmentManager().beginTransaction().setReorderingAllowed(true);
        if (manage) transaction.setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .addToBackStack("manage");
        transaction.replace(R.id.game_content, fragment).commit();
    }
    @Override public boolean onNavigateUp() { getOnBackPressedDispatcher().onBackPressed(); return true; }
}
