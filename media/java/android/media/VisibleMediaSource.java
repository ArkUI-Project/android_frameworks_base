/*
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media;

import android.annotation.Nullable;
import android.graphics.SurfaceTexture;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.view.ScreenContentItem;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Local, unprotected MediaPlayer sources associated with their current output in this process.
 * Callers must check the window's capture policy and use positional reads on returned descriptors.
 * @hide
 */
public final class VisibleMediaSource {
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static final WeakHashMap<Surface, Binding> sSurfaces = new WeakHashMap<>();
    @GuardedBy("sLock")
    private static final WeakHashMap<SurfaceTexture, Binding> sTextures = new WeakHashMap<>();

    @GuardedBy("sLock") private ParcelFileDescriptor mDescriptor;
    @GuardedBy("sLock") private long mOffset;
    @GuardedBy("sLock") private long mLength;
    @GuardedBy("sLock") private boolean mPrepared;
    @GuardedBy("sLock") private boolean mProtected;
    @GuardedBy("sLock") private boolean mHasVideo;
    @GuardedBy("sLock") private Binding mBinding;

    VisibleMediaSource() {}

    /** Returns a caller-owned descriptor for the source currently bound to this Surface. */
    @Nullable
    public static ScreenContentItem copyForSurface(@Nullable Surface surface) {
        synchronized (sLock) {
            return surface == null ? null : copyLocked(sSurfaces.get(surface));
        }
    }

    /** Returns a caller-owned descriptor for the source feeding this local SurfaceTexture. */
    @Nullable
    public static ScreenContentItem copyForTexture(@Nullable SurfaceTexture texture) {
        synchronized (sLock) {
            if (texture == null || texture.isReleased()) return null;
            Binding binding = sTextures.get(texture);
            Surface surface = binding == null ? null : binding.surface.get();
            if (surface == null || surface.getSurfaceTexture() != texture) return null;
            return copyLocked(binding);
        }
    }

    @Nullable
    @GuardedBy("sLock")
    private static ScreenContentItem copyLocked(@Nullable Binding binding) {
        if (binding == null) return null;
        VisibleMediaSource source = binding.source.get();
        Surface surface = binding.surface.get();
        if (source == null || source.mBinding != binding || surface == null
                || !surface.isValid() || surface.getGenerationId() != binding.generation
                || !source.mPrepared || source.mProtected || !source.mHasVideo
                || source.mDescriptor == null) {
            return null;
        }
        try {
            // A truncated file can no longer supply the complete recorded source range.
            StructStat stat = Os.fstat(source.mDescriptor.getFileDescriptor());
            if (stat.st_size < source.mOffset
                    || stat.st_size - source.mOffset < source.mLength) return null;
            ScreenContentItem item = new ScreenContentItem();
            item.type = ScreenContentItem.TYPE_VIDEO;
            item.descriptor = source.mDescriptor.dup();
            item.offset = source.mOffset;
            item.length = source.mLength;
            item.mimeType = "video/*";
            return item;
        } catch (ErrnoException | IOException ignored) {
            return null;
        }
    }

    void setSurface(@Nullable Surface surface) {
        synchronized (sLock) {
            removeBindingLocked();
            if (surface == null || !surface.isValid()) return;
            SurfaceTexture texture = surface.getSurfaceTexture();
            mBinding = new Binding(this, surface, texture);
            sSurfaces.put(surface, mBinding);
            if (texture != null) sTextures.put(texture, mBinding);
        }
    }

    void setDataSource(FileDescriptor descriptor, long offset, long length) {
        synchronized (sLock) {
            clearSourceLocked();
            if (offset < 0 || length <= 0) return;
            ParcelFileDescriptor copy = null;
            try {
                copy = ParcelFileDescriptor.dup(descriptor);
                FileDescriptor fd = copy.getFileDescriptor();
                StructStat stat = Os.fstat(fd);
                // Dup preserves access mode. Do not hand an app a writable descriptor.
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= offset
                        || (Os.fcntlInt(fd, OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE)
                        != OsConstants.O_RDONLY) return;
                long available = Math.min(length, stat.st_size - offset);
                // pread verifies seekability without changing the player's shared file position.
                if (Os.pread(fd, new byte[1], 0, 1, offset) != 1) return;
                mDescriptor = copy;
                copy = null;
                mOffset = offset;
                mLength = available;
            } catch (ErrnoException | IOException ignored) {
                // Export support must never change playback success or failure.
            } finally {
                closeQuietly(copy);
            }
        }
    }

    void setPrepared(boolean unprotected) {
        synchronized (sLock) {
            mPrepared = unprotected && !mProtected;
        }
    }

    void setVideoSize(int width, int height) {
        synchronized (sLock) {
            mHasVideo = width > 0 && height > 0;
        }
    }

    void markProtected() {
        synchronized (sLock) {
            mProtected = true;
            mPrepared = false;
            closeQuietly(mDescriptor);
            mDescriptor = null;
        }
    }

    void clearSource() {
        synchronized (sLock) {
            clearSourceLocked();
        }
    }

    void release() {
        synchronized (sLock) {
            clearSourceLocked();
            removeBindingLocked();
        }
    }

    @GuardedBy("sLock")
    private void clearSourceLocked() {
        closeQuietly(mDescriptor);
        mDescriptor = null;
        mOffset = mLength = 0;
        mPrepared = mProtected = mHasVideo = false;
    }

    @GuardedBy("sLock")
    private void removeBindingLocked() {
        if (mBinding == null) return;
        Surface surface = mBinding.surface.get();
        SurfaceTexture texture = mBinding.texture.get();
        if (surface != null && sSurfaces.get(surface) == mBinding) sSurfaces.remove(surface);
        if (texture != null && sTextures.get(texture) == mBinding) sTextures.remove(texture);
        mBinding = null;
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Binding {
        final WeakReference<VisibleMediaSource> source;
        final WeakReference<Surface> surface;
        final WeakReference<SurfaceTexture> texture;
        final int generation;

        Binding(VisibleMediaSource source, Surface surface, @Nullable SurfaceTexture texture) {
            this.source = new WeakReference<>(source);
            this.surface = new WeakReference<>(surface);
            this.texture = new WeakReference<>(texture);
            generation = surface.getGenerationId();
        }
    }
}
