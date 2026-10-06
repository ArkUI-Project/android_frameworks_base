/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package android.hardware;

/** System-only device-motion restrictions, measured in elapsed realtime nanoseconds. @hide */
oneway interface IMotionSensorPrivacyListener {
    void onMotionSensorPrivacyChanged(int uid, long blockedUntil);
}
