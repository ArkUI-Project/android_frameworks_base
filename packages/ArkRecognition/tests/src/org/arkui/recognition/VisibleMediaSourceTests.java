/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.media.VisibleMediaSource;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.view.ScreenContentItem;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnitRunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real playback regression tests; run with the self-target Runner in the test APK. */
public final class VisibleMediaSourceTests {
    public static final class Runner extends AndroidJUnitRunner { }

    private Instrumentation mInstrumentation;
    private VideoFixtureActivity mActivity;
    private MediaPlayer mPlayer;
    private byte[] mVideo;

    @Before public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        assertEquals("Use VisibleMediaSourceTests$Runner so the fixture shares this process",
                "org.arkui.recognition.tests", mInstrumentation.getTargetContext().getPackageName());
        try (var in = mInstrumentation.getContext().getAssets()
                .open("visible_media_fixture.mp4")) {
            mVideo = in.readAllBytes();
        }
        assertTrue(mVideo.length > 0);
        mActivity = (VideoFixtureActivity) mInstrumentation.startActivitySync(new Intent(
                mInstrumentation.getTargetContext(), VideoFixtureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("Fixture SurfaceView was not created", mActivity.awaitSurface());
    }

    @After public void tearDown() throws Exception {
        onMain(() -> {
            if (mPlayer != null) { mPlayer.release(); mPlayer = null; }
            if (mActivity != null) { mActivity.finish(); mActivity = null; }
            return null;
        });
    }

    @Test public void exportsOnlyAssetRangeWithoutChangingPlayerFilePosition() throws Exception {
        File padded = paddedVideo(4096);
        AtomicReference<ParcelFileDescriptor> probe = new AtomicReference<>();
        try {
            prepare(player -> {
                try (FileInputStream in = new FileInputStream(padded)) {
                    probe.set(ParcelFileDescriptor.dup(in.getFD()));
                    player.setDataSource(in.getFD(), 4096, mVideo.length);
                }
            });
            long position = Os.lseek(probe.get().getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            try (ScreenContentItem item = copy()) {
                assertNotNull(item);
                assertEquals(ScreenContentItem.TYPE_VIDEO, item.type);
                assertEquals(4096, item.offset);
                assertEquals(mVideo.length, item.length);
                assertFalse(item.protectedContent);
                assertEquals(OsConstants.O_RDONLY, Os.fcntlInt(item.descriptor.getFileDescriptor(),
                        OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE);
                assertArrayEquals(mVideo, readSource(item));
                assertEquals(position, Os.lseek(probe.get().getFileDescriptor(), 0,
                        OsConstants.SEEK_CUR));
            }
        } finally {
            if (probe.get() != null) probe.get().close();
            padded.delete();
        }
    }

    @Test public void resetAndReplacementKeepDeliveredCopiesIndependent() throws Exception {
        File first = paddedVideo(4096);
        File second = paddedVideo(8192);
        try {
            prepare(player -> setRange(player, first, 4096));
            try (ScreenContentItem deliveredFirst = copy()) {
                assertNotNull(deliveredFirst);
                onMain(() -> { mPlayer.reset(); return null; });
                assertNull(copy());
                assertArrayEquals(mVideo, readSource(deliveredFirst));
                prepare(player -> setRange(player, second, 8192));
                try (ScreenContentItem deliveredSecond = copy()) {
                    assertNotNull(deliveredSecond);
                    assertEquals(8192, deliveredSecond.offset);
                    assertArrayEquals(mVideo, readSource(deliveredSecond));
                    onMain(() -> { mPlayer.setDisplay(null); return null; });
                    assertNull(copy());
                    onMain(() -> { mPlayer.release(); mPlayer = null; return null; });
                    assertNull(copy());
                    assertArrayEquals(mVideo, readSource(deliveredFirst));
                    assertArrayEquals(mVideo, readSource(deliveredSecond));
                }
            }
        } finally { first.delete(); second.delete(); }
    }

    @Test public void unpreparedWritableAndCallbackSourcesAreNotExported() throws Exception {
        File file = paddedVideo(0);
        try {
            onMain(() -> {
                mPlayer = new MediaPlayer();
                setRange(mPlayer, file, 0);
                mPlayer.setDisplay(mActivity.holder());
                return null;
            });
            assertNull("An unprepared source has unknown DRM state", copy());
            onMain(() -> { mPlayer.reset(); return null; });
            prepare(player -> {
                try (RandomAccessFile writable = new RandomAccessFile(file, "rw")) {
                    player.setDataSource(writable.getFD(), 0, mVideo.length);
                }
            });
            assertNull("A dup must not expose a writable source FD", copy());
            onMain(() -> { mPlayer.reset(); return null; });
            prepare(player -> player.setDataSource(new MediaDataSource() {
                @Override public int readAt(long position, byte[] buffer, int offset, int size) {
                    if (position >= mVideo.length) return -1;
                    int count = (int) Math.min(size, mVideo.length - position);
                    System.arraycopy(mVideo, (int) position, buffer, offset, count);
                    return count;
                }
                @Override public long getSize() { return mVideo.length; }
                @Override public void close() { }
            }));
            assertNull("Callback input is not a complete file descriptor", copy());
        } finally { file.delete(); }
    }

    private void setRange(MediaPlayer player, File file, long offset) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            player.setDataSource(in.getFD(), offset, mVideo.length);
        }
    }

    private void prepare(SourceSetter setter) throws Exception {
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch videoSize = new CountDownLatch(1);
        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        onMain(() -> {
            if (mPlayer == null) mPlayer = new MediaPlayer();
            mPlayer.setOnPreparedListener(player -> {
                player.setLooping(true);
                player.start();
                prepared.countDown();
            });
            mPlayer.setOnVideoSizeChangedListener((player, width, height) -> {
                if (width > 0 && height > 0) videoSize.countDown();
            });
            mPlayer.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered.countDown();
                return false;
            });
            mPlayer.setOnErrorListener((player, what, extra) -> {
                error.set(what + ":" + extra);
                prepared.countDown(); videoSize.countDown(); rendered.countDown(); return true;
            });
            setter.set(mPlayer);
            mPlayer.setDisplay(mActivity.holder());
            mPlayer.prepareAsync();
            return null;
        });
        assertTrue("MediaPlayer was not prepared", prepared.await(15, TimeUnit.SECONDS));
        assertTrue("No real video dimensions were reported", videoSize.await(15, TimeUnit.SECONDS));
        assertTrue("No video frame reached the visible Surface", rendered.await(15, TimeUnit.SECONDS));
        assertNull("MediaPlayer error", error.get());
        onMain(() -> { mPlayer.pause(); return null; });
    }

    private ScreenContentItem copy() throws Exception {
        return onMain(() -> VisibleMediaSource.copyForSurface(mActivity.holder().getSurface()));
    }

    private File paddedVideo(int prefix) throws Exception {
        File file = File.createTempFile("visible-video-", ".mp4",
                mInstrumentation.getTargetContext().getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[prefix]); out.write(mVideo); out.write(new byte[2048]);
        }
        return file;
    }

    private static byte[] readSource(ScreenContentItem item) throws Exception {
        assertTrue(item.length > 0 && item.length <= Integer.MAX_VALUE);
        byte[] bytes = new byte[(int) item.length];
        int read = 0;
        while (read < bytes.length) {
            int count = Os.pread(item.descriptor.getFileDescriptor(), bytes, read,
                    bytes.length - read, item.offset + read);
            assertTrue("Exported source ended early", count > 0);
            read += count;
        }
        return bytes;
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        mInstrumentation.runOnMainSync(task);
        return task.get();
    }

    private interface SourceSetter { void set(MediaPlayer player) throws Exception; }
}
