/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.view;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.util.Slog;

/** @hide */
class ViewRootImplExt {

    private static final String TAG = "ViewRootImplExt";
    private final ViewRootImpl mImpl;
    private int mLastWindowingMode = WINDOWING_MODE_UNDEFINED;
    private final float[] mPopUpViewOffsets = {0.0f, 0.0f, 1.0f, 1.0f};

    ViewRootImplExt(ViewRootImpl impl) {
        mImpl = impl;
    }

    void handleAppVisibility(boolean visible) {
        return;
    }

    void performConfigurationChange(Configuration overrideConfig) {
        mLastWindowingMode = overrideConfig.windowConfiguration.getWindowingMode();
        if (WindowConfiguration.isMiniExtWindowMode(mLastWindowingMode)) {
            try {
                mImpl.mWindowSession.getPopUpViewTouchOffset(mImpl.mWindow, mPopUpViewOffsets);
            } catch (Exception e) {
                Slog.e(TAG, "Unable get Pop-Up View Touch Offset");
            }
        } else {
            mPopUpViewOffsets[0] = 0.0f;
            mPopUpViewOffsets[1] = 0.0f;
            mPopUpViewOffsets[2] = 1.0f;
            mPopUpViewOffsets[3] = 1.0f;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "mPopUpViewOffsets: offset=(" + mPopUpViewOffsets[0] + ", " +
                    mPopUpViewOffsets[1] + "), scale=(" + mPopUpViewOffsets[2] + ", " +
                    mPopUpViewOffsets[3] + ")");
        }
    }

    void onMotionEvent(MotionEvent event) {
        if (WindowConfiguration.isMiniExtWindowMode(mLastWindowingMode)) {
            // Surface-only dragging/resizing does not deliver a new Configuration.
            // Refresh before dispatching the next gesture using this window's session.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                try {
                    mImpl.mWindowSession.getPopUpViewTouchOffset(mImpl.mWindow, mPopUpViewOffsets);
                } catch (android.os.RemoteException e) {
                    Slog.w(TAG, "Unable to refresh small-window touch offsets", e);
                }
            }
            event.mEventExt.setRawDisplayOffset(mPopUpViewOffsets[0], mPopUpViewOffsets[1]);
            event.mEventExt.setRawDisplayScale(mPopUpViewOffsets[2], mPopUpViewOffsets[3]);
        } else {
            event.mEventExt.reset();
        }
    }
}
