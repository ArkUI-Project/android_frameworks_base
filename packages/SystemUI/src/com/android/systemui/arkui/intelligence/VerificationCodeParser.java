/* Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui.intelligence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, bounded local extraction; no logging or persistence of message contents. */
public final class VerificationCodeParser {
    private static final Pattern CONTEXT = Pattern.compile(
            "验证码|校验码|动态码|随机码|一次性密码|\\botp\\b|verification\\s*code|"
                    + "security\\s*code|one[- ]time\\s*(?:password|code)|\\bcode\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z0-9]{4,8}(?![A-Za-z0-9])");
    private VerificationCodeParser() { }

    public static String extract(String text) {
        if (text == null || text.length() > 8192) return null;
        String result = null;
        int best = Integer.MAX_VALUE;
        boolean ambiguous = false;
        final Matcher context = CONTEXT.matcher(text);
        while (context.find()) {
            final int start = Math.max(0, context.start() - 32);
            final int end = Math.min(text.length(), context.end() + 40);
            final Matcher codes = CODE.matcher(text).region(start, end).useTransparentBounds(true);
            while (codes.find()) {
                final String candidate = codes.group();
                // Require a digit; do not mistake nearby ordinary words or dates for a code.
                if (candidate.chars().noneMatch(Character::isDigit)
                        || dateBoundary(text, codes.start(), codes.end())) continue;
                final int score = codes.start() >= context.end()
                        ? codes.start() - context.end() : context.start() - codes.end() + 4;
                if (score < 0) continue;
                if (score < best) {
                    best = score;
                    result = candidate;
                    ambiguous = false;
                } else if (score == best && !candidate.equals(result)) {
                    ambiguous = true;
                }
            }
        }
        return ambiguous ? null : result;
    }
    private static boolean dateBoundary(String text, int start, int end) {
        final String separators = "-./:";
        return (start > 1 && separators.indexOf(text.charAt(start - 1)) >= 0
                && Character.isDigit(text.charAt(start - 2)))
                || (end + 1 < text.length() && separators.indexOf(text.charAt(end)) >= 0
                && Character.isDigit(text.charAt(end + 1)));
    }
}
