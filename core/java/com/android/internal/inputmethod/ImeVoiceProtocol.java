/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.inputmethod;

/** Private, per-session protocol between the current IME and ArkAI's dictation service. */
public final class ImeVoiceProtocol {
    public static final String PACKAGE = "org.arkui.assistant";
    public static final String SERVICE = PACKAGE + ".ImeDictationService";
    public static final int START = 1;
    public static final int FINISH = 2;
    public static final int CANCEL = 3;
    public static final int STATE = 4;
    public static final int RESULT = 5;
    public static final int ERROR = 6;
    public static final int ACTIVATE = 7;
    public static final String ACTIVATION = "activation";
    public static final String TEXT = "text";
    public static final String LEVEL = "level";
    public static final String TRANSCRIBING = "transcribing";

    private ImeVoiceProtocol() { }
}
