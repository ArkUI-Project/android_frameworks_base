/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Local spelling priors for LSTM decoding, never replacements of the recognized text. */
final class RecognitionLexicon {
    private static final int MAX_WORDS = 512;
    private static final int MAX_BYTES = 32 * 1024;
    private static final Pattern WORD = Pattern.compile("[\\p{IsLatin}0-9][\\p{IsLatin}0-9_'-]*");

    static File create(Context context, BooleanSupplier cancelled) throws IOException {
        TreeSet<String> words = new TreeSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("tessdata/system.user-words"), StandardCharsets.UTF_8))) {
            String value;
            while ((value = reader.readLine()) != null) add(words, value);
        }
        if (cancelled.getAsBoolean()) return null;
        // Read labels directly: the application catalog also loads icons, which OCR does not need.
        long deadline = SystemClock.uptimeMillis() + 150;
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        if (launcher != null) {
            try {
                for (var app : launcher.getActivityList(null, Process.myUserHandle())) {
                    if (cancelled.getAsBoolean()) return null;
                    if (words.size() >= MAX_WORDS || SystemClock.uptimeMillis() >= deadline) break;
                    try { add(words, app.getLabel().toString()); }
                    catch (RuntimeException ignored) { /* An app can disappear during enumeration. */ }
                }
            } catch (SecurityException ignored) { /* Formal system spellings remain available. */ }
        }
        if (cancelled.getAsBoolean()) return null;
        File file = File.createTempFile("ocr-lexicon-", ".txt", context.getCacheDir());
        try (var writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            int bytes = 0;
            for (String word : words) {
                if (cancelled.getAsBoolean()) { file.delete(); return null; }
                int count = word.getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes + count > MAX_BYTES) break;
                writer.write(word); writer.write('\n'); bytes += count;
            }
        } catch (IOException | RuntimeException error) {
            file.delete(); throw error;
        }
        return file;
    }

    private static void add(TreeSet<String> words, String label) {
        var matcher = WORD.matcher(label);
        while (matcher.find() && words.size() < MAX_WORDS) {
            String word = matcher.group();
            int length = word.codePointCount(0, word.length());
            if (length >= 2 && length <= 48) words.add(word);
        }
    }
}
