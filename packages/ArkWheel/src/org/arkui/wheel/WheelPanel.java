/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.arkui.wheel.WheelPreferences.*;
import static org.arkui.wheel.WheelStyle.*;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** One overlay surface: the corner arc and its floating app/action library share their lifecycle. */
final class WheelPanel extends FrameLayout {
    interface Callback {
        void launch(WheelCatalog.Item item, boolean alternate);
        void manage();
        void closed(WheelPanel source);
    }
    private final WheelPreferences prefs;
    private final List<WheelCatalog.Item> apps, selected, arcItems;
    private final String[] recent;
    private final boolean left;
    private final Callback callback;
    private final List<Icon> icons = new ArrayList<>();
    private final FrameLayout arcLayer;
    private final TextView label;
    private final PathInterpolator easing = new PathInterpolator(.16f, 0, .2f, 1);
    private final OnBackInvokedCallback back = this::back;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private WheelGeometry geometry;
    private Insets safe = Insets.NONE;
    private ValueAnimator arcAnimator;
    private Runnable closingAction;
    private BooleanSupplier cornerReleaseAllowed;
    private LinearLayout card, tabs, pinnedGroup;
    private EditText search;
    private RecyclerView grid;
    private LibraryAdapter adapter;
    private IndexRail indexRail;
    private boolean actionsTab, arcVisible = true, closing, removed, gesture, gestureCancelled, edgeGesture, moved;
    private int hovered = -1;
    private float downX, downY;
    private long gestureDownTime, cancelledCornerUntil = -1;
    private long releaseDownTime = -1, closingTouchDownTime = -1;
    private int imeBottom;

    WheelPanel(Context c, WheelPreferences prefs, List<WheelCatalog.Item> apps,
            String[] recent, boolean left, Callback callback) {
        super(c); this.prefs = prefs; this.apps = apps; this.recent = recent;
        this.left = left; this.callback = callback;
        selected = WheelCatalog.selected(c, prefs, apps);
        arcItems = new ArrayList<>(selected.subList(0, Math.min(selected.size(), prefs.get(COUNT, 6, 3, 10))));
        arcItems.add(new WheelCatalog.Item("more", c.getString(R.string.more), new Symbol(c, 0), null, 0));
        setFocusableInTouchMode(true); setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        arcLayer = new FrameLayout(c); arcLayer.setClipChildren(false); arcLayer.setClipToPadding(false);
        arcLayer.setElevation(dp(c, 24));
        addView(arcLayer, new LayoutParams(-1, -1));
        for (int i = 0; i < arcItems.size(); i++) {
            final int index = i;
            Icon icon = new Icon(c, arcItems.get(i), prefs.on(MONOCHROME, true));
            icon.setElevation(dp(c, 6));
            icon.setOnClickListener(v -> activate(index, false));
            icon.setOnLongClickListener(v -> { activate(index, true); return true; });
            arcLayer.addView(icon, new LayoutParams(dp(c, 44), dp(c, 44))); icons.add(icon);
        }
        label = text(c, "", 14); label.setGravity(Gravity.CENTER);
        label.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        label.setBackground(round(surface(c), dp(c, 24))); label.setVisibility(INVISIBLE);
        label.setElevation(dp(c, 32));
        addView(label, new LayoutParams(-2, -2));
        setOnApplyWindowInsetsListener((v, insets) -> {
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            configureCard(); return insets;
        });
        setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, c.getString(R.string.close)));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { close(null); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }
    void show(WindowManager windows) {
        this.windows = windows;
        safe = windows.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        params = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.setTitle("ArkWheel:panel"); params.setFitInsetsTypes(0);
        params.setSystemApplicationOverlay(true);
        params.dimAmount = prefs.get(DIM, 12, 0, 50) / 100f;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windows.addView(this, params); requestFocus();
        post(() -> {
            if (removed) return;
            if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
            animateArc(true);
        });
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        geometry = new WheelGeometry(w, h, safe.left, safe.top + dp(getContext(), 8), safe.right,
                safe.bottom + dp(getContext(), 8), left, dp(getContext(), prefs.get(RADIUS, 280, 180, 360)),
                dp(getContext(), prefs.get(ICON_SIZE, 44, 32, 64)), icons.size(), prefs.get(ARC, 74, 50, 80));
        for (int i = 0; i < icons.size(); i++) {
            Icon icon = icons.get(i); icon.setLayoutParams(new LayoutParams(Math.round(geometry.iconSize), Math.round(geometry.iconSize)));
            icon.setX(geometry.x[i] - geometry.iconSize / 2); icon.setY(geometry.y[i] - geometry.iconSize / 2);
        }
        setSystemGestureExclusionRects(List.of(WheelCornerGesture.bounds(w, h,
                dp(getContext(), prefs.cornerSize()), left)));
    }
    private long duration(long ms) {
        return ValueAnimator.areAnimatorsEnabled() ? ms * prefs.get(ANIMATION, 100, 0, 200) / 100 : 0;
    }
    private void animateArc(boolean opening) {
        if (geometry == null || removed) return;
        if (arcAnimator != null) { arcAnimator.cancel(); arcAnimator = null; }
        arcVisible = opening; hover(-1); arcLayer.setVisibility(VISIBLE);
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1); arcAnimator = animator;
        animator.setDuration(duration(opening ? 210 : 150));
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(a -> {
            float time = (float) a.getAnimatedValue();
            for (int i = 0; i < icons.size(); i++) {
                // A short stagger follows the arc, while the radius and angle unfurl together.
                float delay = opening ? (icons.size() - 1 - i) * .018f : i * .012f;
                float t = Math.max(0, Math.min(1, (time - delay) / (1 - delay)));
                float progress = opening ? easing.getInterpolation(t) : 1 - easing.getInterpolation(t);
                float angle = geometry.angle[i] - (1 - progress) * .20f;
                float radius = geometry.radius * (.58f + .42f * progress);
                float targetX = geometry.originX + (left ? 1 : -1) * (float) Math.cos(angle) * radius;
                float targetY = geometry.originY - (float) Math.sin(angle) * radius;
                Icon icon = icons.get(i);
                icon.setX(targetX + (geometry.x[i] - targetX) * progress - geometry.iconSize / 2);
                icon.setY(targetY + (geometry.y[i] - targetY) * progress - geometry.iconSize / 2);
                icon.setAlpha(progress); icon.setScaleX(.65f + .35f * progress); icon.setScaleY(.65f + .35f * progress);
                icon.setRotation((left ? -70 : 70) * (1 - progress));
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (arcAnimator != a) return;
                arcAnimator = null; if (!opening) arcLayer.setVisibility(INVISIBLE);
            }
        }); animator.start();
    }
    private void hover(int index) {
        if (hovered == index) return;
        if (hovered >= 0) icons.get(hovered).highlight(false);
        hovered = index;
        if (index < 0) { label.setVisibility(INVISIBLE); return; }
        icons.get(index).highlight(true);
        if (prefs.on(HAPTICS, true)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        label.setText(arcItems.get(index).label); label.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(getContext(), 60), MeasureSpec.AT_MOST));
        label.setX(Math.max(safe.left + dp(getContext(), 12), Math.min(getWidth() - safe.right - label.getMeasuredWidth() - dp(getContext(), 12), geometry.x[index] - label.getMeasuredWidth() / 2f)));
        label.setY(Math.max(safe.top, geometry.y[0] - geometry.iconSize - dp(getContext(), 48)));
        label.setVisibility(VISIBLE);
    }
    void dragTo(float x, float y) {
        if (closing || geometry == null || !arcVisible) return;
        hover(geometry.hit(x, y, dp(getContext(), 14)));
    }
    void releaseDrag(float x, float y, BooleanSupplier allowed) {
        if (closing || geometry == null) return;
        int index = geometry.hit(x, y, dp(getContext(), 14));
        hover(-1); if (index >= 0 && allowed.getAsBoolean()) activate(index, false, allowed);
    }
    void cancelDrag() {
        if (removed) return;
        gestureCancelled = true; hover(-1);
        if (closing && cornerReleaseAllowed != null && !cornerReleaseAllowed.getAsBoolean()) {
            restoreCancelledRelease();
        }
    }
    private void restoreCancelledRelease() {
        removeCallbacks(closingAction); closingAction = null; cornerReleaseAllowed = null;
        closingTouchDownTime = -1; closing = false;
        if (card != null) {
            card.animate().cancel(); card.setAlpha(1); card.setScaleX(1); card.setScaleY(1);
        }
        animateArc(true);
    }
    void cancelCornerDrag(long cancelTime) {
        cancelledCornerUntil = Math.max(cancelledCornerUntil, cancelTime);
        if (gestureDownTime <= cancelTime
                || (cornerReleaseAllowed != null && !cornerReleaseAllowed.getAsBoolean())) {
            cancelDrag();
        }
        // A split second pointer can arrive as a separate single-pointer tap on this panel.
        if (closing && closingTouchDownTime >= 0 && closingTouchDownTime <= cancelTime) {
            restoreCancelledRelease();
        }
    }
    private boolean edge(float x, float y) {
        return WheelCornerGesture.bounds(getWidth(), getHeight(),
                dp(getContext(), prefs.cornerSize()), left).contains((int) x, (int) y);
    }
    private void revealArc() {
        arcLayer.bringToFront(); label.bringToFront(); animateArc(true);
        if (prefs.on(HAPTICS, true)) performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (closing) return true;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY(); gestureDownTime = e.getDownTime();
            gestureCancelled = gestureDownTime <= cancelledCornerUntil; moved = false;
            boolean onCard = card != null && downX >= card.getX() && downX <= card.getX() + card.getWidth()
                    && downY >= card.getY() && downY <= card.getY() + card.getHeight();
            edgeGesture = edge(downX, downY);
            gesture = edgeGesture || (arcVisible && geometry != null && geometry.hit(downX, downY, dp(getContext(), 12)) >= 0) || !onCard;
        }
        if (!gesture) return super.dispatchTouchEvent(e);
        if (e.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) {
            gestureCancelled = true; hover(-1); return true;
        }
        if (gestureCancelled) return true;
        float dx = (e.getX() - downX) * (left ? 1 : -1), upward = downY - e.getY();
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (action == MotionEvent.ACTION_MOVE && Math.hypot(dx, upward) > slop) {
            moved = true;
            if (edgeGesture && !arcVisible && WheelCornerGesture.opens(dx, upward, slop)) revealArc();
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) dragTo(e.getX(), e.getY());
        if (action == MotionEvent.ACTION_UP) {
            releaseDownTime = e.getDownTime();
            int index = arcVisible && geometry != null ? geometry.hit(e.getX(), e.getY(), dp(getContext(), 14)) : -1;
            hover(-1);
            if (index >= 0) activate(index, !edgeGesture && !moved && e.getEventTime() - e.getDownTime() > 500);
            else if (!edge(downX, downY)) { if (card != null && arcVisible) animateArc(false); else close(null); }
            gesture = false;
            releaseDownTime = -1;
        }
        return true;
    }
    private void activate(int index, boolean alternate) {
        activate(index, alternate, null);
    }
    private void activate(int index, boolean alternate, BooleanSupplier allowed) {
        if (closing || index < 0 || index >= arcItems.size()) return;
        if (prefs.on(HAPTICS, true)) performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        if (index == arcItems.size() - 1) { showLibrary(); animateArc(false); }
        else {
            cornerReleaseAllowed = allowed;
            close(() -> callback.launch(arcItems.get(index), alternate));
        }
    }
    private TextView pill(String text, Runnable action) {
        TextView p = text(getContext(), text, 14); p.setGravity(Gravity.CENTER);
        p.setPadding(dp(getContext(), 12), dp(getContext(), 8), dp(getContext(), 12), dp(getContext(), 8));
        p.setBackground(button(getContext(), container(getContext()), dp(getContext(), 24)));
        p.setTextColor(onContainer(getContext())); p.setOnClickListener(v -> action.run()); return p;
    }
    private void showLibrary() {
        if (card != null) return;
        Context c = getContext();
        card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        card.setBackground(round(surface(c), dp(c, 32))); card.setElevation(dp(c, 12));
        card.setClipToOutline(true);
        addView(card, 0, new LayoutParams(1, 1));
        LinearLayout header = new LinearLayout(c); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(c, c.getString(R.string.more), 22); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(c, 52), 1));
        header.addView(pill(c.getString(R.string.manage), () -> close(callback::manage))); card.addView(header);
        pinnedGroup = new LinearLayout(c); pinnedGroup.setOrientation(LinearLayout.VERTICAL);
        pinnedGroup.addView(text(c, c.getString(R.string.pinned), 12));
        LinearLayout pinnedRow = null;
        for (int i = 0; i < Math.min(8, selected.size()); i++) {
            if (i % 4 == 0) { pinnedRow = new LinearLayout(c); pinnedGroup.addView(pinnedRow); }
            LinearLayout tile = new LinearLayout(c); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER);
            bindTile(tile, selected.get(i)); pinnedRow.addView(tile, new LinearLayout.LayoutParams(0, dp(c, 80), 1));
            if (i == Math.min(8, selected.size()) - 1) {
                for (int j = i % 4 + 1; j < 4; j++) pinnedRow.addView(new View(c), new LinearLayout.LayoutParams(0, dp(c, 80), 1));
            }
        }
        card.addView(pinnedGroup);
        tabs = new LinearLayout(c); tabs.setPadding(0, dp(c, 4), 0, dp(c, 8)); card.addView(tabs);
        search = new EditText(c); search.setSingleLine(true); search.setTextSize(14); search.setHint(R.string.search);
        search.setTextColor(onSurface(c)); search.setHintTextColor(onSurface(c));
        search.setBackground(round(c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest), dp(c, 24)));
        search.setPadding(dp(c, 16), 0, dp(c, 16), 0); search.setSelectAllOnFocus(true);
        search.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO); card.addView(search, new LinearLayout.LayoutParams(-1, dp(c, 44)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { populate(); }
            public void afterTextChanged(Editable s) { }
        });
        grid = new RecyclerView(c); grid.setClipToPadding(false); grid.setPadding(0, dp(c, 8), 0, dp(c, 8));
        GridLayoutManager layout = new GridLayoutManager(c, 4); grid.setLayoutManager(layout);
        adapter = new LibraryAdapter(); grid.setAdapter(adapter);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter.rows.get(position) instanceof String ? 4 : 1; }
        });
        LinearLayout libraryBody = new LinearLayout(c);
        libraryBody.addView(grid, new LinearLayout.LayoutParams(0, -1, 1));
        indexRail = new IndexRail(c); libraryBody.addView(indexRail, new LinearLayout.LayoutParams(dp(c, 24), -1));
        card.addView(libraryBody, new LinearLayout.LayoutParams(-1, 0, 1));
        search.setOnFocusChangeListener((v, focused) -> {
            if (focused) search.post(() -> c.getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
        });
        configureCard(); refreshTabs(); populate();
        card.setAlpha(0); card.setScaleX(.92f); card.setScaleY(.92f);
        card.animate().alpha(1).scaleX(1).scaleY(1).setDuration(duration(200)).setInterpolator(easing).start();
    }
    private void configureCard() {
        if (card == null) return;
        Context c = getContext();
        int available = getHeight() - safe.top - Math.max(safe.bottom, imeBottom);
        boolean compact = available < dp(c, 650);
        int height = Math.min(dp(c, 600), Math.round(available * (imeBottom > 0 || compact ? .94f : .68f)));
        LayoutParams bounds = new LayoutParams(Math.min(dp(c, 400), Math.round((getWidth() - safe.left - safe.right) * .84f)), height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bounds.topMargin = safe.top + Math.max(0, (available - height) / 2 - dp(c, 22)); card.setLayoutParams(bounds);
        if (pinnedGroup != null) pinnedGroup.setVisibility(height >= dp(c, 480) && !actionsTab && !selected.isEmpty()
                && (search == null || search.getText().length() == 0) ? VISIBLE : GONE);
    }
    private void refreshTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < 2; i++) {
            boolean actions = i == 1;
            TextView tab = pill(getContext().getString(actions ? R.string.actions : R.string.apps), () -> {
                actionsTab = actions; refreshTabs(); populate();
            });
            tab.setSelected(actions == actionsTab);
            tab.setBackground(button(getContext(), actions == actionsTab ? container(getContext()) : surface(getContext()), dp(getContext(), 24)));
            tab.setTextColor(actions == actionsTab ? onContainer(getContext()) : onSurface(getContext()));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(getContext(), 48), 1));
        }
    }
    private void populate() {
        if (adapter == null) return;
        configureCard();
        List<Object> rows = new ArrayList<>();
        String query = search.getText().toString();
        if (actionsTab) rows.addAll(WheelCatalog.filter(WheelCatalog.actions(getContext()), query));
        else if (!query.isBlank()) rows.addAll(WheelCatalog.filter(apps, query));
        else {
            if (!selected.isEmpty() && (pinnedGroup.getVisibility() != VISIBLE || selected.size() > 8)) {
                rows.add(getContext().getString(R.string.pinned)); rows.addAll(selected);
            }
            List<WheelCatalog.Item> recently = WheelCatalog.recent(apps, recent);
            List<String> dismissed = prefs.list(DISMISSED_RECENT);
            recently.removeIf(item -> dismissed.contains(item.pkg()));
            if (prefs.on(RECENT, true) && !recently.isEmpty()) { rows.add(getContext().getString(R.string.recent_apps)); rows.addAll(recently.subList(0, Math.min(8, recently.size()))); }
            rows.add(getContext().getString(R.string.all_apps)); rows.addAll(apps);
        }
        if (rows.isEmpty()) rows.add(getContext().getString(R.string.empty));
        adapter.rows = rows; adapter.notifyDataSetChanged(); grid.scrollToPosition(0);
        LinkedHashMap<String, Integer> sections = new LinkedHashMap<>();
        boolean inAll = false;
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (getContext().getString(R.string.all_apps).equals(row)) inAll = true;
            else if (inAll && row instanceof WheelCatalog.Item) sections.putIfAbsent(((WheelCatalog.Item) row).section, i);
        }
        indexRail.sections = sections; indexRail.labels = new ArrayList<>(sections.keySet());
        indexRail.setVisibility(sections.size() > 1 ? VISIBLE : GONE); indexRail.invalidate();
    }
    private final class LibraryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Object> rows = List.of();
        @Override public int getItemViewType(int index) { return rows.get(index) instanceof String ? 0 : 1; }
        @Override public int getItemCount() { return rows.size(); }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER);
            row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(getContext(), 2), dp(getContext(), 8), dp(getContext(), 2), 0);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(getContext(), type == 0 ? 40 : 86)));
            return new RecyclerView.ViewHolder(row) { };
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LinearLayout row = (LinearLayout) holder.itemView; row.removeAllViews(); row.setOnClickListener(null); row.setOnLongClickListener(null);
            Object data = rows.get(position);
            if (data instanceof String) {
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView heading = text(getContext(), (String) data, 13); heading.setTypeface(null, Typeface.BOLD);
                row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
                if (getContext().getString(R.string.recent_apps).equals(data)) {
                    row.addView(pill(getContext().getString(R.string.clear_recent), () -> {
                        prefs.putList(DISMISSED_RECENT, java.util.Arrays.asList(recent)); populate();
                    }));
                }
                return;
            }
            bindTile(row, (WheelCatalog.Item) data);
        }
    }
    private final class IndexRail extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LinkedHashMap<String, Integer> sections = new LinkedHashMap<>();
        private List<String> labels = List.of();
        private int current = -1;
        IndexRail(Context c) {
            super(c); setFocusable(true); setContentDescription(c.getString(R.string.alphabet_index));
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
            setOnClickListener(v -> select((current + 1) % Math.max(1, labels.size())));
            setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        select(current + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1)); return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
        private void select(int index) {
            if (labels.isEmpty()) return;
            int next = Math.max(0, Math.min(labels.size() - 1, index));
            if (current != next && prefs.on(HAPTICS, true)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            current = next;
            ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(sections.get(labels.get(current)), 0);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float step = getHeight() / (float) Math.max(1, labels.size());
            int stride = Math.max(1, (int) Math.ceil(paint.getFontSpacing() / Math.max(1, step)));
            for (int i = 0; i < labels.size(); i++) {
                if (i % stride != 0 && i != labels.size() - 1) continue;
                paint.setColor(i == current ? primary(getContext()) : onSurface(getContext()));
                canvas.drawText(labels.get(i), getWidth() / 2f, step * (i + .5f) - (paint.ascent() + paint.descent()) / 2, paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getPointerCount() != 1 || e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) select((int) (e.getY() * labels.size() / Math.max(1, getHeight())));
            return true;
        }
    }
    private void bindTile(LinearLayout row, WheelCatalog.Item item) {
            Icon icon = new Icon(getContext(), item, prefs.on(MONOCHROME, true));
            icon.setClickable(false); icon.setFocusable(false); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(getContext(), 40), dp(getContext(), 40)));
            TextView title = text(getContext(), item.label, 11); title.setMaxLines(2); title.setGravity(Gravity.CENTER);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(title, new LinearLayout.LayoutParams(-1, -2));
            row.setContentDescription(item.label); row.setFocusable(true);
            row.setBackground(button(getContext(), android.graphics.Color.TRANSPARENT, dp(getContext(), 20)));
            row.setOnClickListener(v -> close(() -> callback.launch(item, false)));
            row.setOnLongClickListener(v -> { close(() -> callback.launch(item, true)); return true; });
    }
    private void back() {
        if (arcVisible && card != null) animateArc(false); else close(null);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (event.getAction() == KeyEvent.ACTION_UP) back(); return true; }
        return super.dispatchKeyEvent(event);
    }
    private void close(Runnable after) {
        if (closing || removed) return;
        closing = true; closingTouchDownTime = releaseDownTime; hover(-1);
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
        if (arcVisible) animateArc(false);
        if (card != null) card.animate().alpha(0).scaleX(.94f).scaleY(.94f).setDuration(duration(140)).start();
        closingAction = () -> {
            if (removed) return;
            if (cornerReleaseAllowed != null && !cornerReleaseAllowed.getAsBoolean()) {
                cancelDrag(); return;
            }
            if (closingTouchDownTime >= 0 && closingTouchDownTime <= cancelledCornerUntil) {
                restoreCancelledRelease(); return;
            }
            dismissImmediately(); callback.closed(this); if (after != null) after.run();
        };
        // Retain the release until the host's full-pointer cancellation can cross processes,
        // including when visual animations are disabled.
        boolean pointerRelease = cornerReleaseAllowed != null || closingTouchDownTime >= 0;
        postDelayed(closingAction, Math.max(pointerRelease ? 160 : 0, duration(160)));
    }
    void dismissImmediately() {
        if (removed) return; removed = true;
        if (closingAction != null) removeCallbacks(closingAction);
        closingAction = null; cornerReleaseAllowed = null; closingTouchDownTime = -1;
        if (arcAnimator != null) { ValueAnimator old = arcAnimator; arcAnimator = null; old.cancel(); }
        if (card != null) card.animate().cancel();
        if (findOnBackInvokedDispatcher() != null) findOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        if (isAttachedToWindow()) windows.removeViewImmediate(this);
    }
}
