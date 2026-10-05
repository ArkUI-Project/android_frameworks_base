/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileOutputStream;

/** A real visible SurfaceView for exercising MediaPlayer's output association. */
public final class VideoFixtureActivity extends Activity {
    private final CountDownLatch mCreated = new CountDownLatch(1);
    private SurfaceView mVideo;
    private MediaPlayer mPreview;
    private File mPreviewFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        mVideo = new SurfaceView(this);
        mVideo.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                mCreated.countDown();
                if (getIntent().getBooleanExtra("play", false)) startPreview(holder);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { releasePreview(); }
        });
        setContentView(mVideo);
        if (getIntent().getBooleanExtra("debug", false)) mVideo.postDelayed(() -> VisibleContentFixtureActivity.logTree(mVideo.getRootView(), 0), 700);
    }

    boolean awaitSurface() throws InterruptedException {
        return mCreated.await(15, TimeUnit.SECONDS);
    }

    SurfaceHolder holder() { return mVideo.getHolder(); }

    private void startPreview(SurfaceHolder holder) {
        try {
            mPreviewFile = File.createTempFile("visible-preview", ".mp4", getCacheDir());
            try (var input = getAssets().open("visible_media_fixture.mp4");
                    var output = new FileOutputStream(mPreviewFile)) { input.transferTo(output); }
            mPreview = new MediaPlayer(); mPreview.setDisplay(holder);
            mPreview.setDataSource(mPreviewFile.getAbsolutePath());
            mPreview.setOnPreparedListener(player -> { player.setLooping(true); player.start(); });
            mPreview.prepareAsync();
        } catch (Exception e) { android.util.Log.e("VisibleVideoFixture", "Cannot play local fixture", e); releasePreview(); }
    }
    private void releasePreview() {
        if (mPreview != null) { mPreview.release(); mPreview = null; }
        if (mPreviewFile != null) { mPreviewFile.delete(); mPreviewFile = null; }
    }
    @Override protected void onDestroy() { releasePreview(); super.onDestroy(); }
}
