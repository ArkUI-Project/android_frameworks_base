/*
 * Modified by the ArkUI Project in 2026 for atomic launch-to-gesture handoff.
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.shared;

/**
 * General shell-related constants that are shared with users of the library.
 */
public class ShellSharedConstants {
    public static final String KEY_EXTRA_SHELL_CAN_HAND_OFF_ANIMATION =
            "extra_shell_can_hand_off_animation";

    /** Launcher can supply a first frame before Shell commits a launch-to-recents handoff. */
    public static final String KEY_EXTRA_RECENTS_START_HANDOFF = "arkui_recents_start_handoff";
    /** RemoteCallback in onAnimationStart extras, accepting the first-frame transaction. */
    public static final String KEY_EXTRA_RECENTS_START_CALLBACK = "arkui_recents_start_callback";
    /** SurfaceControl.Transaction sent to the start callback; Shell owns applying it. */
    public static final String KEY_EXTRA_RECENTS_START_TRANSACTION = "arkui_recents_start_transaction";
    /** RemoteCallback acknowledging whether the handoff was applied before cancellation. */
    public static final String KEY_EXTRA_RECENTS_START_ACK = "arkui_recents_start_ack";

    /**
     * Defines the max screen width or height in dp for a device to be considered a small tablet.
     *
     * @see android.view.WindowManager#LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP
     */
    public static final int SMALL_TABLET_MAX_EDGE_DP = 960;
}
