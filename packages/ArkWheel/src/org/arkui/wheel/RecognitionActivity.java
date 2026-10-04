/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared pictures use URI grants; this entry point never captures another app's screen. */
public final class RecognitionActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private RecognitionPanel panel;
    private Uri imageUri;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setDecorFitsSystemWindows(false);
        if (getSystemService(UserManager.class).isManagedProfile()) { finish(); return; }
        Uri uri = state == null ? getIntent().getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : state.getParcelable("image", Uri.class);
        if (uri != null) load(uri);
        else if (state == null) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 1);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null && data.getData() != null) load(data.getData()); else finish();
    }
    private void load(Uri uri) {
        if (!"content".equals(uri.getScheme())) { finish(); return; }
        imageUri = uri;
        worker.execute(() -> {
            try {
                Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float scale = Math.min(1f, 2400f / Math.max(w, h));
                    decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { bitmap.recycle(); return; }
                    panel = new RecognitionPanel(this, bitmap, this::finish); setContentView(panel); panel.start();
                });
            } catch (IOException | RuntimeException e) { runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) { Toast.makeText(this, R.string.recognition_failed, Toast.LENGTH_SHORT).show(); finish(); }
            }); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelable("image", imageUri); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { if (panel != null) panel.dispose(); worker.shutdown(); super.onDestroy(); }
}
