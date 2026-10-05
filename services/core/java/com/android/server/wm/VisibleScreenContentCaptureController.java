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
package com.android.server.wm;

import android.app.admin.DevicePolicyCache;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.IScreenContentCallback;
import android.view.ScreenContent;
import android.view.ScreenContentItem;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ScreenCapture.ScreenCaptureParams;
import android.window.ScreenCaptureInternal;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single-shot current-display capture. App IPC and readback never run under the WM lock. */
final class VisibleScreenContentCaptureController {
    private static final String TAG = "VisibleScreenContent";
    private static final long REQUEST_TIMEOUT_MILLIS = 2500;
    private static final int MAX_REQUESTS = 2;
    private static final int MAX_WINDOWS = 8;
    private static final int MAX_ITEMS = 256;
    private static final int MAX_METADATA_BYTES = 180 * 1024;
    private static final int MAX_IMAGES = 12;
    private static final int MAX_IMAGE_EDGE = 4096;
    private static final long MAX_IMAGE_PIXELS = 4 * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_PIXELS = 16 * 1024 * 1024;
    private static final int MAX_VIDEOS = 4;
    private static final int MAX_SCREENSHOT_EDGE = 4096;
    private static final int MAX_SCREENSHOT_PIXELS = 4 * 1024 * 1024;

    private final WindowManagerService mWm;
    private final Handler mHandler;
    private final ArrayMap<Integer, Request> mRequests = new ArrayMap<>();
    private final ExecutorService mReadback = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VisibleScreenReadback");
        thread.setDaemon(true);
        return thread;
    });

    VisibleScreenContentCaptureController(WindowManagerService wm) {
        mWm = wm;
        mHandler = wm.mH;
    }

    void request(int displayId, String callingPackage, int uid, int targetUser,
            IScreenContentCallback callback) {
        mHandler.post(() -> begin(displayId, callingPackage, uid, targetUser, callback));
    }

    private void begin(int displayId, String packageName, int uid, int targetUser,
            IScreenContentCallback callback) {
        if (mRequests.containsKey(uid) || mRequests.size() >= MAX_REQUESTS) {
            deliver(callback, new ScreenContent());
            return;
        }
        Request request = new Request(displayId, packageName, uid, targetUser, callback);
        if (!isUserUnlocked(request.userId)) {
            request.result.status = ScreenContent.STATUS_SECURE;
        } else {
            synchronized (mWm.mGlobalLock) {
                int gate = securityGate(displayId, request.userId);
                if (gate != ScreenContent.STATUS_OK) {
                    request.result.status = gate;
                } else {
                    try { snapshotLocked(request); }
                    catch (RuntimeException e) {
                        Slog.w(TAG, "Window snapshot failed", e);
                        request.result.status = ScreenContent.STATUS_UNAVAILABLE;
                    }
                }
            }
        }
        if (request.result.status != ScreenContent.STATUS_OK) {
            request.releaseCaptureSurfaces();
            deliver(callback, request.result);
            return;
        }
        try {
            callback.asBinder().linkToDeath(request.death, 0);
        } catch (RemoteException e) {
            request.result.close();
            request.releaseCaptureSurfaces();
            return;
        }
        mRequests.put(uid, request);
        mHandler.postDelayed(request.timeout, REQUEST_TIMEOUT_MILLIS);
        for (WindowSnapshot snapshot : request.windows) {
            if (!snapshot.collect) continue;
            request.pendingWindows++;
            IScreenContentCallback receiver = new IScreenContentCallback.Stub() {
                @Override public void onScreenContent(ScreenContent content) {
                    if (Binder.getCallingUid() != snapshot.uid) {
                        if (content != null) content.close();
                        return;
                    }
                    mHandler.post(() -> receiveWindow(request, snapshot, content));
                }
            };
            final long identity = Binder.clearCallingIdentity();
            try {
                snapshot.window.mClient.requestVisibleScreenContent(displayId,
                        request.result.rotation, snapshot.frame, snapshot.visibleRegion, receiver);
            } catch (RemoteException | RuntimeException e) {
                snapshot.finished = true;
                request.pendingWindows--;
                request.result.partial = true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        mReadback.execute(() -> capture(request));
    }

    private int securityGate(int displayId, int userId) {
        DisplayContent display = mWm.mRoot.getDisplayContent(displayId);
        if (display == null || display.isPrivate() || !display.isTrusted()
                || userId != mWm.mCurrentUserId || !mWm.mPolicy.isScreenOn(displayId)) {
            return ScreenContent.STATUS_UNAVAILABLE;
        }
        if (mWm.mPolicy.isKeyguardLocked()
                || !DevicePolicyCache.getInstance().isScreenCaptureAllowed(userId)) {
            return ScreenContent.STATUS_SECURE;
        }
        final boolean[] restricted = {false};
        display.forAllWindows(window -> {
            if (window.isVisibleNow() && window.isDrawn()
                    && (window.isSecureLocked()
                    || (window.mAttrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0
                    || !DevicePolicyCache.getInstance().isScreenCaptureAllowed(window.mShowUserId))) {
                restricted[0] = true;
            }
        }, true);
        return restricted[0] ? ScreenContent.STATUS_SECURE : ScreenContent.STATUS_OK;
    }

    private boolean isUserUnlocked(int userId) {
        return mWm.mContext.getSystemService(UserManager.class).isUserUnlocked(userId);
    }

    private void snapshotLocked(Request request) {
        DisplayContent display = mWm.mRoot.getDisplayContent(request.result.displayId);
        request.result.status = ScreenContent.STATUS_OK;
        request.result.rotation = display.getRotation();
        request.result.displayBounds = new Rect(display.getBounds());
        request.result.displayBounds.offsetTo(0, 0);
        request.result.capturedAt = SystemClock.uptimeMillis();
        Region covered = new Region();
        int[] count = {0};
        request.captureRoot = new SurfaceControl(display.getSurfaceControl(), TAG);
        display.forAllWindows(window -> {
            if (!window.isVisibleNow() || !window.isDrawn()) return;
            WindowSnapshot snapshot = new WindowSnapshot(window);
            request.windows.add(snapshot);
            snapshot.visibleRegion.op(request.result.displayBounds, Region.Op.INTERSECT);
            snapshot.visibleRegion.op(covered, Region.Op.DIFFERENCE);
            boolean currentUser = UserHandle.getUserId(snapshot.uid) == request.userId
                    || UserHandle.getAppId(snapshot.uid) < Process.FIRST_APPLICATION_UID;
            boolean stable = !display.inTransition() && !window.inTransition()
                    && !window.isAnimatingLw() && window.mGlobalScale == 1f
                    && !window.inFreeformWindowingMode() && !window.inPinnedWindowingMode();
            snapshot.collect = currentUser && stable && !snapshot.visibleRegion.isEmpty()
                    && count[0] < MAX_WINDOWS;
            if (snapshot.collect) count[0]++;
            else if (!snapshot.visibleRegion.isEmpty()) request.result.partial = true;
            if (window.mAttrs.format != PixelFormat.OPAQUE || window.mAttrs.alpha != 1f) {
                request.result.partial = true;
            }
            // Bounds of transparent windows conservatively block structure behind them.
            // Capture preserves the actual composited result for OCR in these cases.
            covered.op(snapshot.frame, Region.Op.UNION);
        }, true);
        float scale = Math.min(1f, MAX_SCREENSHOT_EDGE / (float) Math.max(
                request.result.displayBounds.width(), request.result.displayBounds.height()));
        scale = Math.min(scale, (float) Math.sqrt(MAX_SCREENSHOT_PIXELS / (double) (
                (long) request.result.displayBounds.width()
                        * request.result.displayBounds.height())));
        request.captureArgs = new ScreenCaptureInternal.LayerCaptureArgs.Builder(request.captureRoot)
                .setSourceCrop(request.result.displayBounds)
                .setFrameScale(scale)
                .setSecureContentPolicy(ScreenCaptureParams.SECURE_CONTENT_POLICY_THROW_EXCEPTION)
                .setProtectedContentPolicy(
                        ScreenCaptureParams.PROTECTED_CONTENT_POLICY_THROW_EXCEPTION)
                .build();
    }

    private void capture(Request request) {
        Bitmap shared = null;
        ScreenCaptureInternal.ScreenshotHardwareBuffer buffer = null;
        Bitmap hardware = null;
        Bitmap software = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            buffer = ScreenCaptureInternal.captureLayers(request.captureArgs);
            if (buffer != null && !buffer.containsSecureLayers()) {
                hardware = buffer.asBitmap();
                if (hardware != null) {
                    software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (software != null) shared = software.asShared();
                }
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            Slog.w(TAG, "Display capture failed", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
            if (hardware != null) hardware.recycle();
            if (software != null && software != shared) software.recycle();
            if (buffer != null && buffer.getHardwareBuffer() != null) {
                buffer.getHardwareBuffer().close();
            }
            request.releaseCaptureSurfaces();
        }
        Bitmap screenshot = shared;
        mHandler.post(() -> {
            if (request.done) {
                if (screenshot != null) screenshot.recycle();
                return;
            }
            request.captureFinished = true;
            request.result.screenshot = screenshot;
            if (screenshot == null) request.result.status = ScreenContent.STATUS_UNAVAILABLE;
            maybeFinish(request);
        });
    }

    private void receiveWindow(Request request, WindowSnapshot snapshot, ScreenContent content) {
        if (content == null) {
            if (!request.done && !snapshot.finished) {
                snapshot.finished = true;
                request.pendingWindows--;
                request.result.partial = true;
                maybeFinish(request);
            }
            return;
        }
        try {
            if (request.done || snapshot.finished) return;
            snapshot.finished = true;
            request.pendingWindows--;
            if (content.status == ScreenContent.STATUS_SECURE) {
                request.result.status = ScreenContent.STATUS_SECURE;
                finish(request, true);
                return;
            }
            if (content.status != ScreenContent.STATUS_OK
                    || content.displayId != request.result.displayId
                    || content.rotation != request.result.rotation
                    || !snapshot.frame.equals(content.displayBounds)) {
                request.result.partial = true;
            } else {
                request.result.partial |= content.partial;
                for (ScreenContentItem item : content.items) {
                    if (acceptItem(request, snapshot, item)) request.result.items.add(item);
                    else {
                        if (item != null) item.close();
                        request.result.partial = true;
                    }
                }
                content.items.clear();
            }
            maybeFinish(request);
        } finally {
            content.close();
        }
    }

    private boolean acceptItem(Request request, WindowSnapshot snapshot, ScreenContentItem item) {
        if (item == null || item.protectedContent || item.bounds == null || item.bounds.isEmpty()
                || !fullyContains(snapshot.visibleRegion, item.bounds)
                || request.result.items.size() >= MAX_ITEMS) return false;
        int bytes = 256;
        if (item.type != ScreenContentItem.TYPE_TEXT
                && (item.text != null || item.characterBounds == null
                || !item.characterBounds.isEmpty())) return false;
        if (item.type == ScreenContentItem.TYPE_TEXT) {
            if (item.text == null || item.text.length() > 1536 || item.text.isEmpty()
                    || item.characterBounds == null
                    || item.characterBounds.size() != item.text.length()
                    || item.image != null || item.descriptor != null) return false;
            for (Rect character : item.characterBounds) {
                if (character == null || character.isEmpty() || !item.bounds.contains(character)
                        || !fullyContains(snapshot.visibleRegion, character)) return false;
            }
            bytes += item.text.length() * 26;
        } else if (item.type == ScreenContentItem.TYPE_IMAGE) {
            if (item.image == null || item.image.isRecycled()
                    || item.image.getWidth() > MAX_IMAGE_EDGE
                    || item.image.getHeight() > MAX_IMAGE_EDGE || item.descriptor != null
                    || request.images >= MAX_IMAGES) return false;
            long pixels = (long) item.image.getWidth() * item.image.getHeight();
            if (pixels > MAX_IMAGE_PIXELS
                    || pixels > MAX_TOTAL_IMAGE_PIXELS - request.imagePixels) return false;
        } else if (item.type == ScreenContentItem.TYPE_VIDEO) {
            if (item.descriptor == null || item.image != null || item.offset < 0
                    || item.length <= 0 || request.videos >= MAX_VIDEOS) return false;
            try {
                StructStat stat = Os.fstat(item.descriptor.getFileDescriptor());
                if (!OsConstants.S_ISREG(stat.st_mode) || item.offset > stat.st_size
                        || item.length > stat.st_size - item.offset) return false;
            } catch (ErrnoException e) { return false; }
        } else return false;
        if (item.mimeType != null && item.mimeType.length() > 128) return false;
        bytes += (item.mimeType == null ? 0 : item.mimeType.length() * 2)
                + snapshot.packageName.length() * 2;
        if (request.metadataBytes + bytes > MAX_METADATA_BYTES) return false;
        if (item.type == ScreenContentItem.TYPE_IMAGE) {
            try {
                Bitmap shared = item.image.asShared();
                if (shared != item.image) item.image.recycle();
                item.image = shared;
            } catch (RuntimeException | OutOfMemoryError e) { return false; }
            request.images++;
            request.imagePixels += (long) item.image.getWidth() * item.image.getHeight();
        } else if (item.type == ScreenContentItem.TYPE_VIDEO) request.videos++;
        item.packageName = snapshot.packageName;
        request.metadataBytes += bytes;
        return true;
    }

    private static boolean fullyContains(Region region, Rect rect) {
        Region remainder = new Region(rect);
        remainder.op(region, Region.Op.DIFFERENCE);
        return remainder.isEmpty();
    }

    private void maybeFinish(Request request) {
        if (request.captureFinished && request.pendingWindows == 0) finish(request, true);
    }

    private void finish(Request request, boolean sendResult) {
        if (request.done) return;
        request.done = true;
        mRequests.remove(request.uid);
        mHandler.removeCallbacks(request.timeout);
        try { request.callback.asBinder().unlinkToDeath(request.death, 0); }
        catch (java.util.NoSuchElementException ignored) { }
        boolean unlocked = isUserUnlocked(request.userId);
        synchronized (mWm.mGlobalLock) {
            int gate = securityGate(request.result.displayId, request.userId);
            if (!unlocked) request.result.status = ScreenContent.STATUS_SECURE;
            else if (gate != ScreenContent.STATUS_OK) request.result.status = gate;
            else if (!sameGeometryLocked(request)) request.result.status = ScreenContent.STATUS_CHANGED;
        }
        if (request.pendingWindows != 0) request.result.partial = true;
        if (!request.captureFinished || request.result.screenshot == null) {
            if (request.result.status == ScreenContent.STATUS_OK) {
                request.result.status = ScreenContent.STATUS_UNAVAILABLE;
            }
        }
        if (request.result.status != ScreenContent.STATUS_OK) {
            request.result.close();
            request.result.partial = true;
        }
        if (sendResult) deliver(request.callback, request.result);
        else request.result.close();
    }

    private boolean sameGeometryLocked(Request request) {
        DisplayContent display = mWm.mRoot.getDisplayContent(request.result.displayId);
        if (display == null || display.getRotation() != request.result.rotation) return false;
        Rect bounds = new Rect(display.getBounds());
        bounds.offsetTo(0, 0);
        if (!bounds.equals(request.result.displayBounds)) return false;
        ArrayList<WindowState> current = new ArrayList<>();
        display.forAllWindows(window -> {
            if (window.isVisibleNow() && window.isDrawn()) current.add(window);
        }, true);
        if (current.size() != request.windows.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            WindowSnapshot old = request.windows.get(i);
            WindowState window = current.get(i);
            if (window != old.window || !window.getFrame().equals(old.frame)
                    || (old.collect && (display.inTransition()
                    || window.isAnimatingLw() || window.inTransition()))) return false;
        }
        return true;
    }

    private static void deliver(IScreenContentCallback callback, ScreenContent content) {
        boolean delivered = false;
        try {
            callback.onScreenContent(content);
            delivered = true;
        }
        catch (RemoteException | RuntimeException ignored) { }
        finally {
            if (!delivered || !(callback.asBinder() instanceof Binder)) content.close();
        }
    }

    private static final class WindowSnapshot {
        final WindowState window;
        final int uid;
        final String packageName;
        final Rect frame;
        final Region visibleRegion;
        boolean collect;
        boolean finished;

        WindowSnapshot(WindowState window) {
            this.window = window;
            uid = window.getOwningUid();
            String owner = window.getOwningPackage();
            packageName = owner == null ? "" : owner;
            frame = new Rect(window.getFrame());
            visibleRegion = new Region(frame);
        }
    }

    private final class Request {
        final int uid;
        final int userId;
        final IScreenContentCallback callback;
        final ScreenContent result = new ScreenContent();
        final ArrayList<WindowSnapshot> windows = new ArrayList<>();
        final Runnable timeout = () -> finish(this, true);
        final IBinder.DeathRecipient death = () -> mHandler.post(() -> finish(this, false));
        SurfaceControl captureRoot;
        ScreenCaptureInternal.LayerCaptureArgs captureArgs;
        boolean done;
        boolean captureFinished;
        int pendingWindows;
        int metadataBytes;
        int images;
        long imagePixels;
        int videos;

        Request(int displayId, String packageName, int uid, int targetUser,
                IScreenContentCallback callback) {
            this.uid = uid;
            userId = targetUser;
            this.callback = callback;
            result.displayId = displayId;
        }

        void releaseCaptureSurfaces() {
            if (captureRoot != null) {
                captureRoot.release();
                captureRoot = null;
            }
        }
    }
}
