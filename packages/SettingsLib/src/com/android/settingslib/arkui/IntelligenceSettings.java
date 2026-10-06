/* Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.settingslib.arkui;

import android.content.ContentResolver;
import android.provider.Settings;

/** Per-user controls shared by native Settings and SystemUI. */
public final class IntelligenceSettings {
    private IntelligenceSettings() { }

    public record Option(String key, int defaultValue, int min, int max) {
        public int read(ContentResolver resolver, int user) {
            return Math.max(min, Math.min(max,
                    Settings.Secure.getIntForUser(resolver, key, defaultValue, user)));
        }
        public boolean enabled(ContentResolver resolver, int user) { return read(resolver, user) != 0; }
        public boolean write(ContentResolver resolver, int user, int value) {
            return Settings.Secure.putIntForUser(resolver, key,
                    Math.max(min, Math.min(max, value)), user);
        }
    }
    public static final Option ENABLED = toggle("arkui_intelligence_enabled", 1);
    public static final Option APP_LINKS = toggle("arkui_intelligence_app_links", 1);
    public static final Option WEB_LINKS = toggle("arkui_intelligence_web_links", 1);
    public static final Option DELIVERY = toggle("arkui_intelligence_delivery", 1);
    public static final Option HEADPHONES = toggle("arkui_intelligence_headphones", 1);
    public static final Option TORCH = toggle("arkui_intelligence_torch", 1);
    public static final Option KEEP_AWAKE = toggle("arkui_intelligence_keep_awake", 1);
    public static final Option SUGGESTION_SECONDS = new Option("arkui_intelligence_duration", 10, 5, 30);
    public static final String MUSIC_APP = "arkui_intelligence_music_app";

    public static final Option SMS_ENABLED = toggle("arkui_sms_code_enabled", 1);
    public static final Option SMS_SECONDS = new Option("arkui_sms_code_duration", 60, 15, 180);
    public static final Option SMS_CLEAR = toggle("arkui_sms_code_clear_clipboard", 1);

    public static final Option LYRICS_ENABLED = toggle("arkui_status_bar_lyrics", 1);
    public static final Option LYRICS_FALLBACK = toggle("arkui_lyrics_title_fallback", 1);
    public static final Option LYRICS_MARQUEE = toggle("arkui_lyrics_marquee", 1);
    public static final Option LYRICS_ANIMATE = toggle("arkui_lyrics_animate", 1);
    public static final Option LYRICS_WIDTH = new Option("arkui_lyrics_width", 110, 60, 160);
    public static final Option LYRICS_TEXT_SIZE = new Option("arkui_lyrics_text_size", 12, 10, 16);
    public static final Option LYRICS_OFFSET = new Option("arkui_lyrics_offset", 0, -5000, 5000);
    public static final Option[] OPTIONS = { ENABLED, APP_LINKS, WEB_LINKS, DELIVERY,
            HEADPHONES, TORCH, KEEP_AWAKE, SUGGESTION_SECONDS, SMS_ENABLED, SMS_SECONDS,
            SMS_CLEAR, LYRICS_ENABLED, LYRICS_FALLBACK, LYRICS_MARQUEE, LYRICS_ANIMATE,
            LYRICS_WIDTH, LYRICS_TEXT_SIZE, LYRICS_OFFSET };
    private static Option toggle(String key, int value) { return new Option(key, value, 0, 1); }
}
