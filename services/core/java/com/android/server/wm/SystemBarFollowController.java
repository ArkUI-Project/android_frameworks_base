/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.HandlerExecutor;
import android.provider.Settings;
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
    // A real bar can belong to only one transition. In particular, a queued transition must not
    // borrow it before the previous transition's finish and cleanup transactions have applied.
    private Session mStatusSession;
    private Session mPendingStatusSession;

    SystemBarFollowController(WindowManagerService service) { mService = service; }

    void systemReady() {
        // A recents transition remains alive while its card is idle. Only the trusted animation
        // owner can tell us when that card settles or starts expanding again.
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                synchronized (mService.mGlobalLock) {
                    if (getSendingUserId() == mService.mCurrentUserId) {
                        boolean running = intent.getBooleanExtra("running", false);
                        if (mActiveSession != null && mActiveSession.recents) {
                            mActiveSession.setAnimating(running);
                        }
                        if (mStatusSession != null && mStatusSession.recents) {
                            mStatusSession.setStatusAnimating(running);
                        }
                        if (mPendingStatusSession != null && mPendingStatusSession.recents) {
                            mPendingStatusSession.setStatusAnimating(running);
                        }
                    }
                }
            }
        }, new IntentFilter("org.arkui.action.SYSTEM_BAR_FOLLOW_MOTION"),
                android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                mService.mH, Context.RECEIVER_EXPORTED);
        IntentFilter resetFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        resetFilter.addAction(Intent.ACTION_USER_SWITCHED);
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                synchronized (mService.mGlobalLock) {
                    restoreStatusBar();
                    cancelFade();
                }
            }
        }, resetFilter, null, mService.mH, Context.RECEIVER_NOT_EXPORTED);
        ContentObserver observer = new ContentObserver(mService.mH) {
            @Override public void onChange(boolean selfChange) {
                synchronized (mService.mGlobalLock) {
                    if (mStatusSession != null && (mService.getAnimationScale(1) == 0
                            || !SystemBarFollowSettings.statusEnabled(
                                    mStatusSession.display.getDisplayUiContext(),
                                    mService.mCurrentUserId))) {
                        restoreStatusBar();
                    }
                }
            }
        };
        mService.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.ARKUI_STATUS_BAR_FOLLOW), false, observer,
                android.os.UserHandle.USER_ALL);
        mService.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer);
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
            boolean closing = mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK;
            WindowState window = findWindowForTransition(app, closing);
            if (window == null) continue;
            Rect bounds = closing ? change.getStartAbsBounds() : change.getEndAbsBounds();
            if (bounds.isEmpty()) continue;

            cancelFade();
            Session session = new Session(++mGeneration, user, display, navigation,
                    (window.mAttrs.insetsFlags.appearance & APPEARANCE_LIGHT_NAVIGATION_BARS) != 0,
                    (info.getFlags() & TRANSIT_FLAG_IS_RECENTS) != 0);
            mActiveSession = session;
            if (mPendingStatusSession != null) {
                mPendingStatusSession.statusRevoked = true;
                mPendingStatusSession = null;
            }
            try {
                WindowState statusWindow = display.getDisplayPolicy().getStatusBar();
                if (status && window.isRequestedVisible(WindowInsets.Type.statusBars())
                        && statusWindow != null
                        && statusWindow.mToken != null
                        && statusWindow.mToken.getAnimationLeash() == null
                        && statusWindow.mToken.getSurfaceControl() != null) {
                    session.attachStatusBar(statusWindow.mToken, change.getLeash(), bounds,
                            start, finish, cleanup);
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
                session.addStatusBarTarget(info, change, bounds);
                return session;
            } catch (RuntimeException e) {
                Slog.w("SystemBarFollow", "Cannot create transition visuals", e);
                // attachStatusBar may already have written its reparent into the start
                // transaction. Override it in that same transaction if a later allocation fails.
                if (mStatusSession == session) session.writeStatusBarRestore(start);
                session.remove();
                return null;
            }
        }
        return null;
    }

    static WindowState findWindowForTransition(Task task, boolean closing) {
        if (task == null) return null;
        if (closing) {
            // The outgoing activity still supplies the bars while its visible window closes.
            ActivityRecord top = task.getTopMostActivity();
            WindowState window = top == null ? null : top.findMainWindow(false);
            if (window != null && window.isVisibleNow()) return window;
        }

        // A launch alias can remain above the displayed activity until the transition finishes.
        ActivityRecord activity = task.getTopNonFinishingActivity();
        WindowState window = activity == null ? null : activity.findMainWindow(false);
        if (window == null && activity != null) window = activity.findMainWindow();
        if (window != null) return window;

        // A windowless launch activity must not obscure the app already drawing below it.
        activity = task.getActivity(
                r -> !r.finishing && r.findMainWindow(false) != null);
        if (activity != null) return activity.findMainWindow(false);

        if (closing) {
            // Finishing the last activity still needs the visible app's bar visibility/appearance.
            activity = task.getActivity(r -> {
                WindowState appWindow = r.findMainWindow(false);
                return r.finishing && appWindow != null && appWindow.isVisibleNow();
            });
            if (activity != null) return activity.findMainWindow(false);
        }

        // A cold launch may not have added its real app window yet.
        activity = task.getActivity(r -> !r.finishing && r.findMainWindow() != null);
        return activity == null ? null : activity.findMainWindow();
    }

    private void restoreStatusBar() {
        if (mPendingStatusSession != null) {
            mPendingStatusSession.statusRevoked = true;
            mPendingStatusSession = null;
        }
        if (mStatusSession == null) return;
        try (var transaction = new SurfaceControl.Transaction()) {
            mStatusSession.revokeStatusBar(transaction);
            transaction.apply();
        }
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
        final boolean dark, recents;
        final ArrayList<SurfaceControl> surfaces = new ArrayList<>();
        boolean navigation;
        SurfaceControl followingHandle;
        boolean followingHandleFading;
        WindowToken statusToken;
        SurfaceControl statusSurface;
        SurfaceControl statusAppLeash;
        SurfaceControl followingStatus;
        int statusRotation;
        boolean statusStarted;
        boolean statusRestored;
        boolean statusMotionRunning = true;
        boolean statusRevoked;

        Session(int generation, int user, DisplayContent display, boolean navigation, boolean dark,
                boolean recents) {
            this.generation = generation; this.user = user; this.display = display;
            this.navigation = navigation; this.dark = dark; this.recents = recents;
        }

        void attachStatusBar(WindowToken token, SurfaceControl appLeash, Rect bounds,
                SurfaceControl.Transaction start, SurfaceControl.Transaction finish,
                SurfaceControl.Transaction cleanup) {
            statusToken = token;
            statusSurface = token.getSurfaceControl();
            statusAppLeash = appLeash;
            statusRotation = display.getRotation();
            followingStatus = mService.makeSurfaceBuilder()
                    .setName("ArkUI following original status bar").setParent(appLeash)
                    .setContainerLayer().setCallsite("SystemBarFollowController.attachStatusBar")
                    .build();
            surfaces.add(followingStatus);
            // Only the existing status bar is moved. Its real window keeps drawing and receiving
            // the foreground app's contrast updates; no second set of glyphs stays on the display.
            start.setPosition(followingStatus, -bounds.left, -bounds.top)
                    .setLayer(followingStatus, Integer.MAX_VALUE - 1)
                    .setAlpha(followingStatus, 1).show(followingStatus);
            if (mStatusSession == null) {
                mStatusSession = this;
                mPendingStatusSession = null;
                start.reparent(statusSurface, followingStatus).setLayer(statusSurface, 0);
                writeStatusBarRestore(finish);
                writeStatusBarRestore(cleanup);
                finish.reparent(followingStatus, null);
                cleanup.reparent(followingStatus, null);
            } else {
                // Give the next animation a target immediately so it can fit the bar to its
                // first crop. Move the real bar only after the old owner's cleanup has run.
                // This pending session must not send a future restore through Shell: by then
                // the bar may already belong to a third transition.
                mPendingStatusSession = this;
                statusRestored = true;
            }
            // Motion can settle while this start waits in Shell's queue. Replay its latest intent
            // after presentation, rather than starting an independent, timed status-bar fade.
            start.addTransactionCommittedListener(new HandlerExecutor(mService.mH), () -> {
                synchronized (mService.mGlobalLock) {
                    if (followingStatus == null || !followingStatus.isValid()) return;
                    statusStarted = true;
                    try (var transaction = new SurfaceControl.Transaction()) {
                        if (mStatusSession == this) updateStatusBarMotion(transaction);
                        else acquirePendingStatusBar(transaction);
                        transaction.apply();
                    }
                }
            });
        }

        void addStatusBarTarget(TransitionInfo info, TransitionInfo.Change app, Rect bounds) {
            if (followingStatus == null) return;
            // The animation owner changes its crop every frame. A child non-app target lets it
            // fit the existing full-width bar into that crop without moving or copying pixels.
            // This is a second handle to the same container, not a mirrored Surface.
            TransitionInfo.Change bar = new TransitionInfo.Change(null,
                    new SurfaceControl(followingStatus, "ArkUI original status bar target"));
            bar.setParent(app.getContainer());
            // An opening target has its geometry ready but is still waiting for the real bar.
            // Let the animation owner avoid capturing that temporarily empty container.
            bar.setMode(mPendingStatusSession == this ? TRANSIT_OPEN : TRANSIT_CHANGE);
            bar.setFlags(TransitionInfo.FLAG_IS_SYSTEM_WINDOW | TransitionInfo.FLAG_NO_ANIMATION
                    | TransitionInfo.FLAG_ARKUI_STATUS_BAR_FOLLOW);
            Rect source = new Rect(0, 0, bounds.width(), bounds.height());
            bar.setStartAbsBounds(source);
            bar.setEndAbsBounds(source);
            bar.setDisplayId(DEFAULT_DISPLAY, DEFAULT_DISPLAY);
            bar.setRotation(app.getStartRotation(), app.getEndRotation());
            info.addChange(bar);
        }

        private void writeStatusBarRestore(SurfaceControl.Transaction transaction) {
            if (statusToken == null || statusSurface == null || !statusSurface.isValid()) return;
            SurfaceControl animationLeash = statusToken.getAnimationLeash();
            if (animationLeash != null && animationLeash.isValid()) {
                // Rotation or keyguard can take control after this session started. Preserve that
                // newer animator's ownership rather than pulling its real window out of its leash.
                transaction.reparent(statusSurface, animationLeash).setLayer(statusSurface, 0);
                return;
            }
            SurfaceControl parent = statusToken.getParentSurfaceControl();
            if (parent == null || !parent.isValid()) return;
            transaction.reparent(statusSurface, parent);
            SurfaceControl relative = statusToken.getLastRelativeLayer();
            if (relative != null && relative.isValid()) {
                transaction.setRelativeLayer(statusSurface, relative, statusToken.getLastLayer());
            } else {
                transaction.setLayer(statusSurface, statusToken.getLastLayer());
            }
        }

        void restoreStatusBar(SurfaceControl.Transaction transaction) {
            if (followingStatus == null || mStatusSession != this) return;
            mService.mSurfaceAnimationRunner.onAnimationCancelled(followingStatus);
            writeStatusBarRestore(transaction);
            if (followingStatus.isValid()) transaction.hide(followingStatus);
            statusRestored = true;
            // Keep ownership until this transition finishes. Its already-dispatched finish
            // transaction still restores the original token and must never steal a newer owner.
        }

        void revokeStatusBar(SurfaceControl.Transaction transaction) {
            statusRevoked = true;
            statusMotionRunning = false;
            restoreStatusBar(transaction);
        }

        private boolean canFollowStatusBar() {
            return mStatusSession == this && isStatusBarValid();
        }

        private boolean isStatusBarValid() {
            return !statusRevoked
                    && followingStatus != null && followingStatus.isValid()
                    && statusSurface != null && statusSurface.isValid()
                    && statusAppLeash != null && statusAppLeash.isValid()
                    && statusToken != null && statusToken.getSurfaceControl() == statusSurface
                    && statusToken.getParent() != null && statusToken.getDisplayContent() == display
                    && statusToken.getAnimationLeash() == null
                    && !display.isKeyguardLocked() && !display.isRemoved()
                    && display.getAsyncRotationController() == null
                    && display.getRotation() == statusRotation
                    && user == mService.mCurrentUserId && mService.getAnimationScale(1) != 0
                    && SystemBarFollowSettings.statusEnabled(display.getDisplayUiContext(), user);
        }

        private void acquirePendingStatusBar(SurfaceControl.Transaction transaction) {
            if (mStatusSession != null || mPendingStatusSession != this || !statusStarted) return;
            mPendingStatusSession = null;
            if (!isStatusBarValid()) return;
            mStatusSession = this;
            statusRestored = true;
            updateStatusBarMotion(transaction);
        }

        private void updateStatusBarMotion(SurfaceControl.Transaction transaction) {
            if (!canFollowStatusBar()) {
                revokeStatusBar(transaction);
                return;
            }
            if (!statusMotionRunning) {
                restoreStatusBar(transaction);
                return;
            }
            // The same recents session can resume after an idle card. Reattach the real bar to
            // the existing fitted container; Launcher continues to own its matrix and position.
            if (statusRestored) {
                transaction.reparent(statusSurface, followingStatus).setLayer(statusSurface, 0);
            }
            transaction.setAlpha(followingStatus, 1).show(followingStatus);
            statusRestored = false;
        }

        void remove() {
            if (mActiveSession == this) mActiveSession = null;
            if (followingHandle != null) {
                mService.mSurfaceAnimationRunner.onAnimationCancelled(followingHandle);
                followingHandle = null;
            }
            try (var transaction = new SurfaceControl.Transaction()) {
                revokeStatusBar(transaction);
                if (mPendingStatusSession == this) mPendingStatusSession = null;
                if (mStatusSession == this) mStatusSession = null;
                for (SurfaceControl surface : surfaces) if (surface.isValid()) transaction.remove(surface);
                // Restore/remove the old container and attach the bar to the already fitted new
                // one in a single transaction. The old finish/cleanup can no longer steal it.
                if (mPendingStatusSession != null) {
                    mPendingStatusSession.acquirePendingStatusBar(transaction);
                }
                transaction.apply();
            }
            followingStatus = null;
            statusSurface = null;
            statusAppLeash = null;
            statusToken = null;
            for (SurfaceControl surface : surfaces) surface.release();
            surfaces.clear();
        }

        void setStatusAnimating(boolean running) {
            statusMotionRunning = running;
            if (!statusStarted || mStatusSession != this) return;
            try (var transaction = new SurfaceControl.Transaction()) {
                updateStatusBarMotion(transaction);
                transaction.apply();
            }
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
