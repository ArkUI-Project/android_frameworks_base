/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.icu.text.BreakIterator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/** Word boundaries are local; selections always refer to the unchanged source string. */
final class RecognitionWords {
    record Word(int start, int end, String text) { }
    final String text;
    final List<Word> words;
    private final BitSet selected = new BitSet();

    RecognitionWords(String text, Locale locale) {
        this.text = text;
        List<Word> found = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getWordInstance(locale);
        iterator.setText(text);
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE;
                start = end, end = iterator.next()) {
            String word = text.substring(start, end);
            if (!word.codePoints().allMatch(c -> Character.isWhitespace(c)
                    || Character.isSpaceChar(c))) found.add(new Word(start, end, word));
        }
        words = List.copyOf(found);
    }

    boolean selected(int index) { return selected.get(index); }
    void toggle(int index) { selected.flip(index); }
    int count() { return selected.cardinality(); }
    void selectAll(boolean value) { selected.set(0, words.size(), value); }

    void keepSelection(RecognitionWords previous) {
        for (int index = 0; index < words.size(); index++) {
            Word word = words.get(index);
            int old = java.util.Collections.binarySearch(previous.words, word,
                    java.util.Comparator.comparingInt(Word::start));
            if (old >= 0 && word.equals(previous.words.get(old)) && previous.selected(old)) {
                selected.set(index);
            }
        }
    }

    String selectedText() {
        if (words.isEmpty() || selected.isEmpty()) return "";
        if (count() == words.size()) return text;
        StringBuilder result = new StringBuilder();
        int previous = -1;
        for (int index = selected.nextSetBit(0); index >= 0;
                index = selected.nextSetBit(index + 1)) {
            Word word = words.get(index);
            if (previous >= 0) {
                // Adjacent chips retain punctuation and the original spaces/newlines.
                if (index == previous + 1) {
                    result.append(text, words.get(previous).end(), word.start());
                } else result.append(' ');
            }
            result.append(word.text()); previous = index;
        }
        return result.toString();
    }
}
