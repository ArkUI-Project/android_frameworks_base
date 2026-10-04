/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.R;

public class PinnedWindowOverlayView extends FrameLayout {

    private static final String TAG = "PinnedWindowOverlayView";

    private PinnedWindowOverlayController mController;
    private Task mTask;

    private ImageView mScaleButton;
    private ImageView mMuteButton;
    private boolean mDocked;
    private boolean mDockedLeft;
    private final android.graphics.Paint mTabPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private View mMenuContainer;

    private boolean mIsMenuVisible = false;

    public PinnedWindowOverlayView(Context context) {
        super(context);
        init();
    }

    public PinnedWindowOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mScaleButton = findViewById(R.id.pinned_window_menu_scale_button);
        mMuteButton = findViewById(R.id.pinned_window_menu_mute_button);
        mMuteButton.setOnClickListener(v -> {
            if (mTask != null) {
                PopUpWindowController.getInstance().toggleTaskMute(mTask);
                updateMenuButtons();
            }
        });
        mMenuContainer = findViewById(R.id.pinned_window_menu_container);

        if (mScaleButton != null) {
            mScaleButton.setOnClickListener(v -> toggleScale());
        }

        hideMenu();
    }

    void setController(PinnedWindowOverlayController controller) {
        mController = controller;
    }

    void setTask(Task task) {
        if (mTask != task) {
            hideMenu();
        }
        mTask = task;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mMuteButton == null || mScaleButton == null || w <= 0 || h <= 0) return;
        final float density = getResources().getDisplayMetrics().density;
        final int side = Math.max(1, Math.min(Math.round(48 * density),
                Math.min(h, (w - Math.round(12 * density)) / 2)));
        final int padding = Math.max(0, (side - Math.round(24 * density)) / 2);
        resizeMenuButton(mMuteButton, side, padding);
        resizeMenuButton(mScaleButton, side, padding);
    }

    private void resizeMenuButton(ImageView button, int side, int padding) {
        final ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params.width == side && params.height == side) return;
        params.width = side;
        params.height = side;
        button.setLayoutParams(params);
        button.setPadding(padding, padding, padding, padding);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        PinnedWindowOverlayController.getInstance().onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isTouchOnButton(event)) {
            return false;
        }

        return true;
    }

    private boolean isTouchOnButton(MotionEvent event) {
        if (mMenuContainer != null && mMenuContainer.getVisibility() == View.VISIBLE
                && isTouchOnView(event, mMenuContainer)) {
            return true;
        }
        return false;
    }

    private boolean isTouchOnView(MotionEvent event, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x <= location[0] + view.getWidth()
                && y >= location[1] && y <= location[1] + view.getHeight();
    }

    void showMenu() {
        if (mDocked) return;
        updateMenuButtons();
        if (mMenuContainer != null) {
            mMenuContainer.setVisibility(View.VISIBLE);
            mIsMenuVisible = true;
        }
    }

    void hideMenu() {
        if (mMenuContainer != null) {
            mMenuContainer.setVisibility(View.GONE);
            mIsMenuVisible = false;
        }
    }

    private void toggleScale() {
        if (mTask == null) {
            return;
        }
        PopUpWindowController.getInstance().triggerPinnedWindowResize(mTask);
        hideMenu();
    }

    private void updateMenuButtons() {
        if (mTask == null) return;
        final boolean muted = PopUpWindowController.getInstance().isTaskMuted(mTask);
        mMuteButton.setImageResource(muted ? R.drawable.pinned_window_unmute
                : R.drawable.pinned_window_mute);
        mMuteButton.setContentDescription(getContext().getString(muted
                ? R.string.arkui_small_window_unmute : R.string.arkui_small_window_mute));
        final TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        mScaleButton.setImageResource(info != null && info.isPinnedWindowSmall()
                ? R.drawable.pinned_window_scale_large : R.drawable.pinned_window_scale_small);
    }

    void setDocked(boolean docked, boolean left) {
        mDocked = docked;
        mDockedLeft = left;
        if (docked) hideMenu();
        setContentDescription(docked ? getContext().getString(
                R.string.arkui_small_window_restore_preview) : null);
        invalidate();
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mDocked) return;
        final float density = getResources().getDisplayMetrics().density;
        final float width = 12 * density;
        final float left = mDockedLeft ? -width / 2 : getWidth() - width / 2;
        final float top = (getHeight() - 72 * density) / 2;
        mTabPaint.setColor(0xB36E757C);
        canvas.drawRoundRect(left, top, left + width, top + 72 * density,
                width / 2, width / 2, mTabPaint);
        mTabPaint.setColor(0xFFF5F5F5);
        final float center = mDockedLeft ? 3 * density : getWidth() - 3 * density;
        canvas.drawRoundRect(center - density, top + 22 * density, center + density,
                top + 50 * density, density, density, mTabPaint);
    }
}
