/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition.protocol;

/** SystemUI owns capture and sends one transient, independently owned recognition session. */
public final class RecognitionProtocol {
    private RecognitionProtocol() { }
    public static final String PACKAGE = "org.arkui.recognition";
    public static final String SERVICE = PACKAGE + ".RecognitionService";
    public static final String SETTINGS = PACKAGE + ".SETTINGS";
    public static final String PERMISSION = PACKAGE + ".permission.CONTROL_RECOGNITION";
    public static final int CAPTURE_RESULT = 1;
    public static final int CLOSED = 2;
    public static final String CONTENT = "content";
    public static final String BITMAP = "bitmap";
}
