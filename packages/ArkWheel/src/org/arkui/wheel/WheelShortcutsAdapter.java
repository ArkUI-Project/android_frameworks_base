/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.arkui.wheel.WheelStyle.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Editable shortcut rows, kept separate from the non-draggable settings preferences. */
final class WheelShortcutsAdapter extends RecyclerView.Adapter<WheelShortcutsAdapter.Holder> {
    private static final Object ORDER_CHANGED = new Object();
    private final WheelPreferences prefs;
    private final List<WheelCatalog.Item> items;
    private final Consumer<Boolean> emptyChanged;
    private boolean dirty;

    WheelShortcutsAdapter(WheelPreferences prefs, List<WheelCatalog.Item> items,
            Consumer<Boolean> emptyChanged) {
        this.prefs = prefs;
        this.items = new ArrayList<>(items);
        this.emptyChanged = emptyChanged;
    }

    @Override public int getItemCount() { return items.size(); }

    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        row.setMinimumHeight(dp(c, 72));
        row.setFocusable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setBackground(button(c, c.getColor(com.android.internal.R.color.materialColorSurfaceContainer), dp(c, 28)));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
        params.setMargins(dp(c, 16), 0, dp(c, 16), 0);
        row.setLayoutParams(params);
        ImageView icon = new ImageView(c);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        TextView title = text(c, "", 16);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(c, 16));
        labelParams.setMarginEnd(dp(c, 16));
        row.addView(title, labelParams);
        ImageView handle = new ImageView(c);
        handle.setImageResource(R.drawable.ic_drag_handle);
        handle.setImageTintList(android.content.res.ColorStateList.valueOf(onSurface(c)));
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(handle, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        return new Holder(row, icon, title);
    }

    @Override public void onBindViewHolder(Holder holder, int position) {
        WheelCatalog.Item item = items.get(position);
        holder.icon.setImageDrawable(item.icon);
        holder.title.setText((position + 1) + " · " + item.label);
        holder.itemView.setContentDescription(holder.title.getText());
    }

    @Override public void onBindViewHolder(Holder holder, int position, List<Object> payloads) {
        onBindViewHolder(holder, position);
    }

    private boolean move(int from, int to) {
        if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) return false;
        items.add(to, items.remove(from));
        dirty = true;
        notifyItemMoved(from, to);
        // A payload keeps the dragged holder in place while updating the displayed numbers.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1, ORDER_CHANGED);
        return true;
    }

    private void remove(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        dirty = true;
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, items.size() - position, ORDER_CHANGED);
        persist();
        emptyChanged.accept(items.isEmpty());
    }

    void persist() {
        if (!dirty) return;
        List<String> ids = new ArrayList<>();
        for (WheelCatalog.Item item : items) ids.add(item.id);
        prefs.putList(WheelPreferences.ITEMS, ids);
        dirty = false;
    }

    ItemTouchHelper touchHelper() {
        return new ItemTouchHelper(new ItemTouchHelper.Callback() {
            private Drawable swipeBackground;
            private Drawable deleteIcon;

            private boolean shortcut(RecyclerView.ViewHolder holder) {
                return holder.getBindingAdapter() == WheelShortcutsAdapter.this
                        && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION;
            }

            @Override public int getMovementFlags(RecyclerView list, RecyclerView.ViewHolder holder) {
                return shortcut(holder) ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                        ItemTouchHelper.LEFT) : 0;
            }

            @Override public boolean canDropOver(RecyclerView list, RecyclerView.ViewHolder current,
                    RecyclerView.ViewHolder target) {
                return shortcut(current) && shortcut(target);
            }

            @Override public boolean onMove(RecyclerView list, RecyclerView.ViewHolder source,
                    RecyclerView.ViewHolder target) {
                return shortcut(source) && shortcut(target)
                        && move(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
                if (shortcut(holder)) remove(holder.getBindingAdapterPosition());
            }

            @Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int state) {
                super.onSelectedChanged(holder, state);
                if (state == ItemTouchHelper.ACTION_STATE_IDLE) persist();
            }

            @Override public void onChildDraw(Canvas canvas, RecyclerView list,
                    RecyclerView.ViewHolder holder, float dx, float dy, int state, boolean active) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dx < 0) {
                    View row = holder.itemView;
                    Context c = row.getContext();
                    if (swipeBackground == null) {
                        swipeBackground = round(c.getColor(com.android.internal.R.color.materialColorErrorContainer), dp(c, 28));
                        deleteIcon = c.getDrawable(android.R.drawable.ic_menu_delete).mutate();
                        deleteIcon.setTint(c.getColor(com.android.internal.R.color.materialColorOnErrorContainer));
                    }
                    swipeBackground.setBounds(row.getLeft(), row.getTop(), row.getRight(), row.getBottom());
                    swipeBackground.draw(canvas);
                    int right = row.getRight() - dp(c, 20);
                    int top = row.getTop() + (row.getHeight() - dp(c, 24)) / 2;
                    int save = canvas.save();
                    canvas.clipRect(row.getRight() + dx, row.getTop(), row.getRight(), row.getBottom());
                    deleteIcon.setBounds(right - dp(c, 24), top, right, top + dp(c, 24));
                    deleteIcon.draw(canvas);
                    canvas.restoreToCount(save);
                }
                super.onChildDraw(canvas, list, holder, dx, dy, state, active);
            }
        });
    }

    final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;

        Holder(View row, ImageView icon, TextView title) {
            super(row);
            this.icon = icon;
            this.title = title;
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    int position = getBindingAdapterPosition();
                    if (position == RecyclerView.NO_POSITION) return;
                    Context c = host.getContext();
                    if (position > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.shortcut_move_up, c.getString(R.string.move_up)));
                    if (position < items.size() - 1) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.shortcut_move_down, c.getString(R.string.move_down)));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.shortcut_remove, c.getString(R.string.remove)));
                }

                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    int position = getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        if (action == R.id.shortcut_remove) { remove(position); return true; }
                        int to = action == R.id.shortcut_move_up ? position - 1
                                : action == R.id.shortcut_move_down ? position + 1 : position;
                        if (move(position, to)) { persist(); return true; }
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
    }
}
