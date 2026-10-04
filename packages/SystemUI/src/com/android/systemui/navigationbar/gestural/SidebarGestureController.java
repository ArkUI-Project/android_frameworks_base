/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.navigationbar.gestural;

import static com.android.internal.arkui.SidebarContract.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.Display;
import android.view.MotionEvent;

import com.android.systemui.arkui.SidebarManager;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A stationary pause arms the three-dot hint; release opens, retreat cancels. */
final class SidebarGestureController {
    private final SidebarManager sidebar;
    private final Context context;
    private final Handler handler;
    private final BooleanSupplier backAllowed;
    private final Consumer<MotionEvent> cancelBack;
    private final Predicate<Boolean> showHint;
    private final Runnable hideHint;
    private float density;
    private MotionEvent lastEvent;
    private float downX, downY, anchorX, anchorY, lastX, lastY;
    private float minimumDistance, stationarySlopSquared;
    private int delay;
    private boolean tracking, left, pending, consumed, armed, haptic, hintExpiryPending;

    SidebarGestureController(Context context, Handler handler, SidebarManager sidebar,
            BooleanSupplier backAllowed, Consumer<MotionEvent> cancelBack,
            Predicate<Boolean> showHint, Runnable hideHint) {
        this.context = context; this.sidebar = sidebar; this.handler = handler;
        this.backAllowed = backAllowed; this.cancelBack = cancelBack;
        this.showHint = showHint; this.hideHint = hideHint;
    }

    boolean isEnabled() { return sidebar.isBackHoldEnabled(); }
    boolean isTracking() { return tracking; }

    void onDown(MotionEvent event, boolean allowed, boolean isLeft) {
        reset();
        if (!allowed || !isEnabled() || event.getDisplayId() != Display.DEFAULT_DISPLAY
                || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                || event.getPointerCount() != 1) return;
        Bundle config = sidebar.getConfiguration();
        density = context.getResources().getDisplayMetrics().density;
        delay = integer(config, HOLD_DELAY, 300, 150, 800);
        minimumDistance = density * integer(config, HOLD_DISTANCE, 48, 24, 120);
        float stationarySlop = 5 * density;
        stationarySlopSquared = stationarySlop * stationarySlop;
        haptic = integer(config, HAPTICS, 1, 0, 1) == 1;
        tracking = true; left = isLeft;
        downX = anchorX = event.getX(); downY = anchorY = event.getY();
    }

    /** Called before the ordinary back handler, including after its stream has been cancelled. */
    boolean onMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { reset(); return false; }
        if (!tracking && !consumed) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean intercepted = consumed;
            if (consumed && action == MotionEvent.ACTION_UP && armed && isEnabled()) {
                sidebar.openFromGesture(left, anchorY);
            }
            clear();
            // Multi-touch after takeover must consume the rest of this cancelled back stream.
            consumed = intercepted && action == MotionEvent.ACTION_POINTER_DOWN;
            return intercepted;
        }
        if (action == MotionEvent.ACTION_MOVE && consumed) {
            float inward = (event.getX() - downX) * (left ? 1 : -1);
            if (armed && (inward < minimumDistance * .45f
                    || Math.abs(event.getY() - downY) > 120 * density || !isEnabled())) {
                armed = false; hideHint.run();
            }
            return true;
        }
        return consumed;
    }

    /** Called after the ordinary back handler crosses its touch-slop threshold. */
    void onBackProgress(MotionEvent event) {
        if (!tracking || consumed || event.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        float inward = (event.getX() - downX) * (left ? 1 : -1);
        if (!isEnabled() || !backAllowed.getAsBoolean() || event.getPointerCount() != 1
                || inward < minimumDistance || Math.abs(event.getY() - downY) > 96 * density) {
            cancelPendingArm(); releaseEvent(); return;
        }
        remember(event);
        float dx = event.getX() - anchorX, dy = event.getY() - anchorY;
        if (!pending || dx * dx + dy * dy > stationarySlopSquared) {
            anchorX = event.getX(); anchorY = event.getY();
            cancelPendingArm();
            pending = handler.postDelayed(arm, delay);
        }
    }

    private final Runnable arm = this::armGesture;
    private final Runnable expireHint = this::expireHint;
    private void expireHint() {
        hintExpiryPending = false;
        armed = false; hideHint.run();
    }
    private void armGesture() {
        pending = false;
        if (!tracking || lastEvent == null || !backAllowed.getAsBoolean() || !isEnabled()) {
            releaseEvent(); return;
        }
        if (showHint.test(haptic)) {
            // Keep the Back bubble, cancel predictive Back, and consume the eventual ACTION_UP.
            armed = consumed = true;
            anchorY = lastY;
            hintExpiryPending = handler.postDelayed(expireHint, 5000);
            lastEvent.setLocation(lastX, lastY);
            cancelBack.accept(lastEvent);
        }
        releaseEvent();
    }

    void onBackCancelled() { if (!consumed) reset(); }
    void reset() {
        clear();
    }
    private void clear() {
        if (!tracking && !pending && !consumed && !armed && !hintExpiryPending
                && lastEvent == null) return;
        cancelPendingArm();
        if (hintExpiryPending) {
            handler.removeCallbacks(expireHint);
            hintExpiryPending = false;
        }
        if (armed || consumed) hideHint.run();
        tracking = consumed = armed = false;
        releaseEvent();
    }
    private void cancelPendingArm() {
        if (!pending) return;
        handler.removeCallbacks(arm);
        pending = false;
    }
    private void remember(MotionEvent event) {
        // The delayed cancel uses only the current position and display, never event history.
        if (lastEvent == null) lastEvent = MotionEvent.obtainNoHistory(event);
        lastX = event.getX(); lastY = event.getY();
    }
    private void releaseEvent() {
        if (lastEvent != null) { lastEvent.recycle(); lastEvent = null; }
    }
}
