/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import static org.arkui.recognition.RecognitionStyle.dp;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Wraps native selectable chips without fixing the number of columns or the font size. */
final class RecognitionWordFlow extends ViewGroup {
    private int[] left = new int[0], top = new int[0];
    RecognitionWordFlow(Context context) { super(context); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = Math.max(0, MeasureSpec.getSize(widthSpec)
                - getPaddingLeft() - getPaddingRight());
        if (left.length != getChildCount()) {
            left = new int[getChildCount()]; top = new int[getChildCount()];
        }
        int gap = dp(getContext(), 8), x = 0, y = 0, rowHeight = 0, widest = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            if (x > 0 && x + width > available) { x = 0; y += rowHeight + gap; rowHeight = 0; }
            left[index] = x; top[index] = y;
            widest = Math.max(widest, x + width); x += width + gap;
            rowHeight = Math.max(rowHeight, height);
        }
        setMeasuredDimension(resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(y + rowHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            int x = rtl ? getWidth() - getPaddingRight() - left[index] - child.getMeasuredWidth()
                    : getPaddingLeft() + left[index];
            int y = getPaddingTop() + top[index];
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }
}
