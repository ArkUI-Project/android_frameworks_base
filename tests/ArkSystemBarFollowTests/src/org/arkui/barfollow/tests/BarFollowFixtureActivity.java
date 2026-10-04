/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.barfollow.tests;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class BarFollowFixtureActivity extends Activity {
    static final int BACKGROUND = 0xffd9eef4;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(Color.BLACK);
        TextView text = new TextView(this); text.setBackgroundColor(BACKGROUND);
        text.setTextColor(Color.BLACK); text.setTextSize(24); text.setGravity(Gravity.CENTER);
        text.setText("ArkUI\n系统栏跟随\n\n底部不占位 · 手势仍可用");
        setContentView(text);
    }
}
