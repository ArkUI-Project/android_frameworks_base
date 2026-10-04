/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.TransitionInfo;

import com.android.internal.arkui.SystemBarFollowSettings;

import java.io.PrintWriter;
import java.util.ArrayList;

/** Bar visuals inherit the app leash transform; input windows always stay on the display. */
final class SystemBarFollowController {
    private final WindowManagerService mService;
    private int mGeneration;
    private SurfaceControl mFadingHandle;
    private Session mActiveSession;

    SystemBarFollowController(WindowManagerService service) { mService = service; }

    void systemReady() {
        // A recents transition remains alive while its card is idle. Only the trusted animation
        // owner can tell us when that card settles or starts expanding again.
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                synchronized (mService.mGlobalLock) {
                    if (getSendingUserId() == mService.mCurrentUserId && mActiveSession != null) {
                        mActiveSession.setAnimating(intent.getBooleanExtra("running", false));
                    }
                }
            }
        }, new IntentFilter("org.arkui.action.SYSTEM_BAR_FOLLOW_MOTION"),
                android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                mService.mH, Context.RECEIVER_EXPORTED);
    }

    Session start(TransitionInfo info, SurfaceControl.Transaction start,
            SurfaceControl.Transaction finish, SurfaceControl.Transaction cleanup) {
        DisplayContent display = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (display == null || display.isKeyguardLocked()
                || display.getAsyncRotationController() != null
                || mService.getAnimationScale(1) == 0) return null;
        Context context = display.getDisplayUiContext();
        int user = mService.mCurrentUserId;
        boolean navigation = SystemBarFollowSettings.navigationEnabled(context, user);
        boolean status = SystemBarFollowSettings.statusEnabled(context, user);
        if (!navigation && !status) return null;

        for (TransitionInfo.Change change : info.getChanges()) {
            var task = change.getTaskInfo();
            int mode = change.getMode();
            if (task == null || task.displayId != DEFAULT_DISPLAY
                    || task.getActivityType() != ACTIVITY_TYPE_STANDARD
                    || task.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                    || (mode != TRANSIT_OPEN && mode != TRANSIT_TO_FRONT
                            && mode != TRANSIT_CLOSE && mode != TRANSIT_TO_BACK)
                    || change.getStartRotation() != change.getEndRotation()) continue;
            WindowContainer<?> container = WindowContainer.fromBinder(
                    change.getContainer().asBinder());
            Task app = container == null ? null : container.asTask();
            ActivityRecord activity = app == null ? null : app.getTopMostActivity();
            WindowState window = activity == null ? null : activity.findMainWindow();
            if (window == null) continue;
            Rect bounds = mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK
                    ? change.getStartAbsBounds() : change.getEndAbsBounds();
            if (bounds.isEmpty()) continue;

            cancelFade();
            Session session = new Session(++mGeneration, user, display, navigation,
                    (window.mAttrs.insetsFlags.appearance & APPEARANCE_LIGHT_NAVIGATION_BARS) != 0);
            mActiveSession = session;
            try {
                WindowState statusWindow = display.getDisplayPolicy().getStatusBar();
                if (status && window.isRequestedVisible(WindowInsets.Type.statusBars())
                        && statusWindow != null && statusWindow.getSurfaceControl() != null) {
                    SurfaceControl mirror = SurfaceControl.mirrorSurface(
                            statusWindow.getSurfaceControl());
                    session.surfaces.add(mirror);
                    start.reparent(mirror, change.getLeash()).setPosition(mirror, 0, 0)
                            .setLayer(mirror, Integer.MAX_VALUE - 1)
                            .setWindowCrop(mirror, bounds.width(), statusWindow.getFrame().height())
                            .show(mirror);
                    // The original status bar switches to the desktop's contrast during a home
                    // gesture. Keep this transparent mirror's glyphs in the app's foreground tone.
                    float tone = (window.mAttrs.insetsFlags.appearance
                            & APPEARANCE_LIGHT_STATUS_BARS) != 0 ? .08f : .95f;
                    start.setColorTransform(mirror, new float[9], new float[] {tone, tone, tone});
                    // remove() releases the local handle immediately, before either transaction
                    // is applied. Keep it alive for both completion and failure cleanup paths.
                    finish.reparent(mirror, null);
                    cleanup.reparent(mirror, null);
                }
                if (navigation && window.isRequestedVisible(WindowInsets.Type.navigationBars())) {
                    SurfaceControl handle = makeHandle(display, change.getLeash(), bounds.width(),
                            bounds.height(), session.dark, start, "ArkUI following gesture handle");
                    session.surfaces.add(handle);
                    session.followingHandle = handle;
                    finish.reparent(handle, null);
                    cleanup.reparent(handle, null);
                } else {
                    session.navigation = false;
                }
                return session;
            } catch (RuntimeException e) {
                Slog.w("SystemBarFollow", "Cannot create transition visuals", e);
                session.remove();
                return null;
            }
        }
        return null;
    }

    private SurfaceControl makeHandle(DisplayContent display, SurfaceControl parent,
            int width, int height, boolean dark, SurfaceControl.Transaction transaction,
            String name) {
        float density = display.getDisplayUiContext().getResources().getDisplayMetrics().density;
        int handleWidth = Math.min(width, Math.round(108 * density));
        int handleHeight = Math.max(1, Math.round(4 * density));
        SurfaceControl handle = mService.makeSurfaceBuilder().setName(name).setParent(parent)
                .setColorLayer().setCallsite("SystemBarFollowController.makeHandle").build();
        float color = dark ? .08f : .95f;
        transaction.setColor(handle, new float[] {color, color, color})
                .setWindowCrop(handle, handleWidth, handleHeight)
                .setCornerRadius(handle, handleHeight / 2f)
                .setPosition(handle, (width - handleWidth) / 2f,
                        Math.max(0, height - Math.round(10 * density) - handleHeight))
                .setLayer(handle, Integer.MAX_VALUE).show(handle);
        return handle;
    }

    private void cancelFade() {
        if (mFadingHandle == null) return;
        SurfaceControl handle = mFadingHandle; mFadingHandle = null;
        mService.mSurfaceAnimationRunner.onAnimationCancelled(handle);
        try (var transaction = new SurfaceControl.Transaction()) {
            transaction.remove(handle).apply();
        }
        handle.release();
    }

    private void startFade(SurfaceControl handle, DisplayContent display, int user,
            SurfaceControl.Transaction transaction, Runnable finished) {
        long duration = Math.round(SystemBarFollowSettings.fadeDuration(
                display.getDisplayUiContext(), user) * mService.getAnimationScale(1));
        mService.mSurfaceAnimationRunner.startAnimation(new LocalAnimationAdapter.AnimationSpec() {
            @Override public long getDuration() { return duration; }
            @Override public void apply(SurfaceControl.Transaction t, SurfaceControl leash, long time) {
                float progress = Math.min(1f, time / (float) Math.max(1, duration));
                t.setAlpha(leash, (1 - progress) * (1 - progress));
            }
            @Override public void dump(PrintWriter pw, String prefix) {
                pw.println(prefix + "ArkUI gesture handle fade");
            }
            @Override public void dumpDebugInner(ProtoOutputStream proto) { }
        }, handle, transaction, finished);
    }

    final class Session {
        final int generation, user;
        final DisplayContent display;
        final boolean dark;
        final ArrayList<SurfaceControl> surfaces = new ArrayList<>();
        boolean navigation;
        SurfaceControl followingHandle;
        boolean followingHandleFading;

        Session(int generation, int user, DisplayContent display, boolean navigation, boolean dark) {
            this.generation = generation; this.user = user; this.display = display;
            this.navigation = navigation; this.dark = dark;
        }

        void remove() {
            if (mActiveSession == this) mActiveSession = null;
            if (followingHandle != null) {
                mService.mSurfaceAnimationRunner.onAnimationCancelled(followingHandle);
                followingHandle = null;
            }
            try (var transaction = new SurfaceControl.Transaction()) {
                for (SurfaceControl surface : surfaces) if (surface.isValid()) transaction.remove(surface);
                transaction.apply();
            }
            for (SurfaceControl surface : surfaces) surface.release();
            surfaces.clear();
        }

        void setAnimating(boolean running) {
            if (followingHandle == null || !followingHandle.isValid()) return;
            if (!running && followingHandleFading) return;
            mService.mSurfaceAnimationRunner.onAnimationCancelled(followingHandle);
            followingHandleFading = !running;
            try (var transaction = new SurfaceControl.Transaction()) {
                if (running) transaction.setAlpha(followingHandle, 1);
                else startFade(followingHandle, display, user, transaction, () -> { });
                transaction.apply();
            }
        }

        void finish() {
            remove();
            if (!navigation || generation != mGeneration || user != mService.mCurrentUserId
                    || display.isKeyguardLocked() || display.isRemoved()
                    || !SystemBarFollowSettings.navigationEnabled(display.getDisplayUiContext(), user)
                    || mService.getAnimationScale(1) == 0) return;
            WindowState nav = display.getDisplayPolicy().getNavigationBar();
            if (nav == null || !nav.isVisible() || nav.getSurfaceControl() == null) return;
            cancelFade();
            try (var transaction = new SurfaceControl.Transaction()) {
                SurfaceControl handle = makeHandle(display, nav.getSurfaceControl(),
                        nav.getFrame().width(), nav.getFrame().height(), dark, transaction,
                        "ArkUI fading gesture handle");
                mFadingHandle = handle;
                startFade(handle, display, user, transaction, () -> mService.mH.post(() -> {
                    synchronized (mService.mGlobalLock) {
                        if (mFadingHandle == handle) cancelFade();
                    }
                }));
                transaction.apply();
            } catch (RuntimeException e) {
                Slog.w("SystemBarFollow", "Cannot finish gesture handle fade", e);
                cancelFade();
            }
        }
    }
}
