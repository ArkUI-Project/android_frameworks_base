/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.junit.Assert.*;
import android.content.res.Configuration;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import org.junit.Test;

public final class RecognitionWordTests {
    @Test public void segmentsChineseWordsAndMixedLatinWithoutRewritingTheSource() {
        String source = "重音落在 e 上时，e 变为 viste vestirse\n你好，世界";
        RecognitionWords words = new RecognitionWords(source, Locale.SIMPLIFIED_CHINESE);
        assertTrue("Chinese must have local word boundaries", words.words.stream()
                .filter(word -> word.end() <= source.indexOf('e')).count() > 1);
        assertTrue(words.words.stream().anyMatch(word -> word.text().equals("viste")));
        assertTrue(words.words.stream().anyMatch(word -> word.text().equals("vestirse")));
        assertTrue(words.words.stream().anyMatch(word -> word.text().equals("，")));
        for (RecognitionWords.Word word : words.words) {
            assertEquals(word.text(), source.substring(word.start(), word.end()));
        }
        words.selectAll(true); assertEquals(source, words.selectedText());
    }

    @Test public void allSelectionRetainsLeadingTrailingSpacesAndLineBreaks() {
        String source = "  Copy\tthis text\r\n原文 😀\n  ";
        RecognitionWords words = new RecognitionWords(source, Locale.SIMPLIFIED_CHINESE);
        assertEquals("", words.selectedText()); words.selectAll(true);
        assertEquals(source, words.selectedText());
        words.selectAll(false); assertEquals(0, words.count()); assertEquals("", words.selectedText());
    }

    @Test public void independentSelectionsRetainOrderAndAdjacentSourceSpacing() {
        RecognitionWords words = new RecognitionWords("Copy  this\ntext, please", Locale.ENGLISH);
        words.toggle(0); words.toggle(1); assertEquals("Copy  this", words.selectedText());
        words.toggle(1); words.toggle(2); assertEquals("Copy text", words.selectedText());
        words.toggle(3); assertEquals("Copy text,", words.selectedText());
    }

    @Test public void graphemesAndEmojiAreNeverSplitIntoBrokenSurrogates() {
        String source = "Cafe\u0301 👨‍👩‍👧‍👦 🇨🇳 原文😀";
        RecognitionWords words = new RecognitionWords(source, Locale.SIMPLIFIED_CHINESE);
        assertTrue(words.words.stream().anyMatch(word -> word.text().equals("👨‍👩‍👧‍👦")));
        assertTrue(words.words.stream().anyMatch(word -> word.text().equals("🇨🇳")));
        assertTrue(words.words.stream().anyMatch(word -> word.text().equals("Cafe\u0301")));
        for (RecognitionWords.Word word : words.words) {
            assertFalse(Character.isLowSurrogate(source.charAt(word.start())));
            if (word.end() < source.length()) assertFalse(Character.isLowSurrogate(source.charAt(word.end())));
        }
        words.selectAll(true); assertEquals(source, words.selectedText());
    }

    @Test public void supplementalRecognitionKeepsAlreadySelectedWords() {
        RecognitionWords old = new RecognitionWords("原文 ArkUI", Locale.SIMPLIFIED_CHINESE);
        old.selectAll(true);
        RecognitionWords next = new RecognitionWords("原文 ArkUI\n新增文字", Locale.SIMPLIFIED_CHINESE);
        next.keepSelection(old); assertEquals(old.words.size(), next.count());
        assertEquals("原文 ArkUI", next.selectedText());
        assertFalse(next.selected(next.words.size() - 1));
    }

    @Test public void whitespaceOnlyHasNoSelectableChips() {
        RecognitionWords words = new RecognitionWords(" \t\n\u00a0\u3000", Locale.ENGLISH);
        assertTrue(words.words.isEmpty()); words.selectAll(true); assertEquals("", words.selectedText());
    }

    @Test public void longWordsWrapAndStayInsideNarrowRtlLayoutsAtLargeFontScale() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Configuration config = new Configuration(instrumentation.getTargetContext().getResources().getConfiguration());
            config.fontScale = 2; config.setLocale(Locale.forLanguageTag("ar"));
            var context = new android.view.ContextThemeWrapper(
                    instrumentation.getTargetContext().createConfigurationContext(config), R.style.RecognitionTheme);
            RecognitionWordSheet sheet = new RecognitionWordSheet(context, () -> { }, text -> { });
            sheet.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            sheet.update("مرحبا بالعالم Supercalifragilisticexpialidocious 你好 ArkUI", true);
            int width = RecognitionStyle.dp(context, 280), height = RecognitionStyle.dp(context, 700);
            sheet.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            sheet.layout(0, 0, width, height);
            RecognitionWordFlow flow = sheet.findViewById(R.id.recognition_word_flow);
            assertTrue(flow.getChildCount() >= 5);
            for (int index = 0; index < flow.getChildCount(); index++) {
                TextView chip = (TextView) flow.getChildAt(index);
                assertTrue(chip.getLeft() >= 0); assertTrue(chip.getRight() <= flow.getWidth());
                assertTrue(chip.getBottom() <= flow.getHeight());
                assertTrue(chip.getHeight() >= RecognitionStyle.dp(context, 48));
            }
            assertTrue(((TextView) flow.getChildAt(2)).getLineCount() > 1);
            assertFalse(sheet.findViewById(R.id.recognition_words_copy).isEnabled());
            sheet.findViewById(R.id.recognition_words_select_all).performClick();
            assertTrue(sheet.findViewById(R.id.recognition_words_copy).isEnabled());
        });
    }
}
