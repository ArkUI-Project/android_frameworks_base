/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock.target;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A separate UID with recognizable private content for device security tests. */
public class TargetActivity extends Activity {
    static volatile int lastResultCode = Integer.MIN_VALUE;
    static volatile String lastResult = "";
    static volatile String receivedGrant = "";
    static volatile String receivedLaunchMarker = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        receivedLaunchMarker = getIntent().getStringExtra("launch_marker");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 96, 32, 32);
        TextView secret = new TextView(this);
        secret.setText("Private app content: " + getPackageName());
        secret.setTextSize(24);
        content.addView(secret);
        String documentMarker = getIntent().getStringExtra("document_marker");
        if (documentMarker != null) {
            TextView document = new TextView(this);
            document.setText("Private document: " + documentMarker);
            document.setTextSize(20);
            content.addView(document);
        }
        Button next = new Button(this);
        next.setAllCaps(false);
        next.setText("Open second activity");
        next.setOnClickListener(v -> startActivity(new Intent(this, SecondActivity.class)));
        content.addView(next);
        Button result = new Button(this);
        result.setAllCaps(false);
        result.setText("Open protected app for result");
        result.setOnClickListener(v -> {
            lastResultCode = Integer.MIN_VALUE;
            lastResult = "";
            startActivityForResult(new Intent().setComponent(new ComponentName(
                    "com.android.test.arkui.applock.second", TargetActivity.class.getName()))
                    .setData(Uri.parse("content://" + getPackageName() + ".private/secret"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 51);
        });
        content.addView(result);
        Button finish = new Button(this);
        finish.setAllCaps(false);
        finish.setText("Return protected result");
        finish.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra("answer", "protected-result"));
            finish();
        });
        content.addView(finish);
        if (getIntent().getData() != null
                && "content".equals(getIntent().getData().getScheme())) {
            try (var stream = getContentResolver().openInputStream(getIntent().getData())) {
                receivedGrant = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception error) {
                receivedGrant = error.toString();
            }
        }
        setContentView(content);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 51) {
            lastResultCode = result;
            lastResult = data == null ? "" : data.getStringExtra("answer");
        }
    }
}
