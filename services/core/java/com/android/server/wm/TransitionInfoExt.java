/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.WindowInsets;

import com.android.server.wm.Transition.ChangeInfo.PopUpViewInfo;

public class TransitionInfoExt {

    private static final String TAG = "TransitionInfoExt";

    private final PopUpViewInfo mPopUpViewInfo = new PopUpViewInfo();

    private float getPositionAndScaleFormInfo(TaskWindowSurfaceInfo info, Point out) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "getPositionAndScaleFormInfo, info=" + info);
        }
        out.set(0, 0);
        final Task task = info.mTask;
        final int windowingMode = info.mFreezedWindowingMode != WINDOWING_MODE_UNDEFINED
                ? info.mFreezedWindowingMode
                : task.getConfiguration().windowConfiguration.getWindowingMode();

        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            final Rect bounds = info.getTaskWindowSurfaceBounds();
            out.set(bounds.left, bounds.top);
            return WindowConfiguration.isPinnedExtWindowMode(windowingMode)
                    ? info.getWindowSurfaceScale() : info.getWindowSurfaceRealScale();
        }
        final Rect bounds = info.getSurfaceTaskBounds();
        out.set(bounds.left, bounds.top);
        return 1.0f;
    }

    private float getCornerRadiusFromInfo(TaskWindowSurfaceInfo info) {
        final int windowingMode = info.mFreezedWindowingMode != 0 ? info.mFreezedWindowingMode
                : info.mTask.getConfiguration().windowConfiguration.getWindowingMode();
        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return info.getCornerRadius();
        }
        return 0.0f;
    }

    PopUpViewInfo getPopUpViewInfo() {
        return mPopUpViewInfo;
    }

    void setupPopUpViewInfo(TaskWindowSurfaceInfo freezeInfo, TaskWindowSurfaceInfo info, DisplayInfo displayInfo) {
        mPopUpViewInfo.mStartScale = getPositionAndScaleFormInfo(freezeInfo, mPopUpViewInfo.mStartPos);
        mPopUpViewInfo.mEndScale = getPositionAndScaleFormInfo(info, mPopUpViewInfo.mEndPos);
        mPopUpViewInfo.mStartCornerRadius = getCornerRadiusFromInfo(freezeInfo);
        mPopUpViewInfo.mEndCornerRadius = getCornerRadiusFromInfo(info);
        mPopUpViewInfo.mAppBounds.set(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        if (info.mTask != null) {
            info.mTask.getBounds(mPopUpViewInfo.mWindowCrop);
            mPopUpViewInfo.mWindowCrop.offsetTo(0, 0);

            final Rect freezeBounds = info.mTask.mWindowContainerExt
                    .getFreezerExt().getFreezeBounds();
            if (freezeBounds.isEmpty()) {
                info.mTask.getBounds(mPopUpViewInfo.mStartDragBounds);
            } else {
                mPopUpViewInfo.mStartDragBounds.set(freezeBounds);
            }
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setupPopUpViewInfo, info=" + mPopUpViewInfo);
        }
    }
}
