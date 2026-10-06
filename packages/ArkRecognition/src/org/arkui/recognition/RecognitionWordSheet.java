/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.arkui.recognition.RecognitionStyle.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A bottom drawer of independently selectable words, with a persistent action row. */
final class RecognitionWordSheet extends LinearLayout {
    private final RecognitionWordFlow flow;
    private final TextView count, selectAll, copy, share, empty, help;
    private final List<TextView> chips = new ArrayList<>();
    private RecognitionWords words;

    RecognitionWordSheet(Context c, Runnable close, Consumer<String> shareText) {
        super(c); setId(R.id.recognition_word_drawer); setOrientation(VERTICAL);
        setPadding(dp(c, 20), 0, dp(c, 20), dp(c, 16));
        setBackground(round(surface(c), dp(c, 32))); setClickable(true);
        setClipChildren(true); setClipToPadding(true); setClipToOutline(true);
        setAccessibilityPaneTitle(c.getString(R.string.text_mode));
        setFocusableInTouchMode(true);

        android.widget.FrameLayout grip = new android.widget.FrameLayout(c);
        grip.setId(R.id.recognition_drawer_handle);
        View bar = new View(c); bar.setBackground(round(c.getColor(
                com.android.internal.R.color.materialColorOutlineVariant), dp(c, 3)));
        grip.addView(bar, new android.widget.FrameLayout.LayoutParams(dp(c, 36), dp(c, 4), Gravity.CENTER));
        addView(grip, new LayoutParams(-1, dp(c, 32)));

        LinearLayout header = new LinearLayout(c); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(c, c.getString(R.string.text_mode), 24); title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        header.addView(icon(c, R.string.close, false, close), new LayoutParams(dp(c, 48), dp(c, 48)));
        addView(header);
        help = text(c, c.getString(R.string.word_selection_hint), 14);
        help.setPadding(0, dp(c, 4), 0, dp(c, 16)); addView(help);

        ScrollView scroll = new ScrollView(c) {
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                // Settings' expressive theme allows child overflow. A scrolling word
                // viewport must clip its content so it cannot cover the fixed actions.
                setClipBounds(new android.graphics.Rect(0, 0, w, h));
            }
        };
        scroll.setClipChildren(true); scroll.setClipToPadding(true);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL);
        flow = new RecognitionWordFlow(c); flow.setId(R.id.recognition_word_flow);
        flow.setPadding(0, 0, 0, dp(c, 12)); body.addView(flow, new LayoutParams(-1, -2));
        empty = text(c, "", 16); empty.setPadding(0, dp(c, 16), 0, dp(c, 16)); body.addView(empty);
        scroll.addView(body); addView(scroll, new LayoutParams(-1, 0, 1));

        LinearLayout selection = new LinearLayout(c); selection.setGravity(Gravity.CENTER_VERTICAL);
        count = text(c, "", 14); count.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        selection.addView(count, new LayoutParams(0, -2, 1));
        selectAll = chip(c, android.R.string.selectAll, false, () -> {
            words.selectAll(words.count() != words.words.size()); updateSelection();
        });
        selectAll.setId(R.id.recognition_words_select_all); selection.addView(selectAll);
        LayoutParams selectionBounds = new LayoutParams(-1, -2);
        selectionBounds.topMargin = dp(c, 8); selectionBounds.bottomMargin = dp(c, 8);
        addView(selection, selectionBounds);
        LinearLayout actions = new LinearLayout(c);
        copy = chip(c, android.R.string.copy, true, () -> {
            String selected = words.selectedText(); if (selected.isEmpty()) return;
            c.getSystemService(ClipboardManager.class).setPrimaryClip(
                    ClipData.newPlainText(c.getString(R.string.recognize), selected));
            Toast.makeText(c, R.string.text_copied, Toast.LENGTH_SHORT).show();
        });
        copy.setId(R.id.recognition_words_copy);
        share = chip(c, com.android.internal.R.string.share, false, () -> shareText.accept(words.selectedText()));
        share.setId(R.id.recognition_words_share);
        actions.addView(copy, new LayoutParams(0, -2, 1));
        LayoutParams shareBounds = new LayoutParams(0, -2, 1); shareBounds.setMarginStart(dp(c, 12));
        actions.addView(share, shareBounds); addView(actions, new LayoutParams(-1, -2));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int wanted = MeasureSpec.getSize(heightSpec) < dp(getContext(), 400) ? GONE : VISIBLE;
        if (help.getVisibility() != wanted) help.setVisibility(wanted);
        super.onMeasure(widthSpec, heightSpec);
    }

    void update(String text, boolean finished) {
        RecognitionWords next = new RecognitionWords(text, getResources().getConfiguration().getLocales().get(0));
        if (words != null) next.keepSelection(words);
        words = next; chips.clear(); flow.removeAllViews();
        Context c = getContext();
        for (int index = 0; index < words.words.size(); index++) {
            int position = index;
            TextView chip = text(c, words.words.get(index).text(), 16);
            chip.setGravity(Gravity.CENTER); chip.setMinHeight(dp(c, 48)); chip.setMinWidth(dp(c, 48));
            chip.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8)); chip.setFocusable(true);
            chip.setOnClickListener(v -> { words.toggle(position); updateSelection(); });
            chip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(CheckBox.class.getName()); info.setCheckable(true);
                    info.setChecked(words.selected(position));
                }
            });
            chips.add(chip); flow.addView(chip, new android.view.ViewGroup.LayoutParams(-2, -2));
        }
        empty.setVisibility(words.words.isEmpty() ? VISIBLE : GONE);
        empty.setText(finished ? R.string.recognition_empty : R.string.recognition_running);
        updateSelection();
    }

    private void updateSelection() {
        Context c = getContext();
        for (int index = 0; index < chips.size(); index++) {
            boolean selected = words.selected(index); TextView chip = chips.get(index);
            chip.setSelected(selected); chip.setTextColor(selected ? onContainer(c) : onSurface(c));
            chip.setBackground(button(c, selected ? container(c)
                    : c.getColor(com.android.internal.R.color.materialColorSurfaceContainer), dp(c, selected ? 16 : 12)));
        }
        count.setText(c.getString(R.string.words_selected, words.count()));
        boolean all = !words.words.isEmpty() && words.count() == words.words.size();
        selectAll.setText(all ? R.string.clear_selection : android.R.string.selectAll);
        selectAll.setContentDescription(selectAll.getText()); selectAll.setEnabled(!words.words.isEmpty());
        copy.setEnabled(words.count() > 0); share.setEnabled(words.count() > 0);
        copy.setAlpha(copy.isEnabled() ? 1 : .38f); share.setAlpha(share.isEnabled() ? 1 : .38f);
    }
}
