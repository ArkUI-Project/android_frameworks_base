/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import static com.android.internal.arkui.SidebarContract.*;
import static org.arkui.sidebar.SidebarStyle.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.function.Consumer;

final class SidebarPanel {
    interface Actions {
        void launch(AppCatalog.App app, boolean smallWindow);
        void edit();
        void recognize();
    }
    private final Context context;
    private final SidebarPreferences prefs;
    private final List<AppCatalog.App> apps;
    private final List<AppCatalog.App> pinned;
    private final Actions actions;
    private final Runnable onClosed;
    private final boolean left;
    private final float touchY;
    private final Dialog dialog;
    private final FrameLayout root;
    private final LinearLayout rail, drawer;
    private final Glyph gridButton;
    private final LinearLayout recognitionButton;
    private EditText appSearch;
    private RecyclerView appGrid;
    private Insets insets = Insets.NONE;
    private boolean expanded, closing;
    private int iconSize, railWidth, panelHeight, drawerWidth;

    SidebarPanel(Context c, SidebarPreferences p, List<AppCatalog.App> all,
            List<AppCatalog.App> railApps, boolean isLeft, float y,
            Actions actions, Runnable onClosed) {
        context = c; prefs = p; apps = all; pinned = railApps; left = isLeft; touchY = y;
        this.actions = actions; this.onClosed = onClosed;
        iconSize = dp(c, prefs.get(ICON_SIZE, 44, 32, 56));
        railWidth = iconSize + dp(c, 20);
        dialog = new Dialog(c, R.style.SidebarOverlayTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dialog.getWindow();
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        // Without DRAWS_SYSTEM_BAR_BACKGROUNDS, DecorView falls back to a black bar
        // even when the overlay background itself is transparent.
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarDividerColor(Color.TRANSPARENT);
        w.setStatusBarContrastEnforced(false);
        w.setNavigationBarContrastEnforced(false);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        w.setDecorFitsSystemWindows(false);
        root = new FrameLayout(c);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) close();
            return true;
        });
        rail = new LinearLayout(c);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(c, 7), dp(c, 10), dp(c, 7), dp(c, 10));
        rail.setClipChildren(true);
        rail.setClipToPadding(true);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setOnTouchListener((v, e) -> true);
        drawer = new LinearLayout(c);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 8));
        // The Expressive activity theme disables clipping for its app bar. This overlay
        // must clip each scroll viewport so app tiles cannot cover the fixed header.
        drawer.setClipChildren(true);
        drawer.setClipToPadding(true);
        drawer.setVisibility(View.GONE);
        drawer.setOnTouchListener((v, e) -> true);
        root.addView(drawer);
        root.addView(rail);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        LinearLayout icons = new LinearLayout(c);
        icons.setOrientation(LinearLayout.VERTICAL);
        icons.setGravity(Gravity.CENTER_HORIZONTAL);
        for (AppCatalog.App app : pinned) {
            View tile = appTile(app, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    iconSize + dp(c, 6), iconSize + dp(c, 10));
            icons.addView(tile, lp);
        }
        scroll.addView(icons);
        rail.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        View divider = new View(c);
        divider.setBackgroundColor(c.getColor(com.android.internal.R.color.materialColorOutlineVariant));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(c, 26), dp(c, 1));
        dlp.topMargin = dp(c, 5); dlp.bottomMargin = dp(c, 5);
        rail.addView(divider, dlp);
        recognitionButton = new LinearLayout(c);
        recognitionButton.setOrientation(LinearLayout.VERTICAL);
        recognitionButton.setGravity(Gravity.CENTER);
        recognitionButton.setFocusable(true);
        recognitionButton.setContentDescription(c.getString(R.string.recognize_screen));
        recognitionButton.setBackground(buttonBackground(c, c.getColor(
                com.android.internal.R.color.materialColorPrimaryContainer), dp(c, 18)));
        Glyph recognize = new Glyph(c, Glyph.RECOGNIZE, c.getString(R.string.recognize_screen));
        recognize.setFocusable(false);
        recognize.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        recognitionButton.addView(recognize, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        TextView recognitionLabel = text(c, c.getString(R.string.recognize), 11,
                c.getColor(com.android.internal.R.color.materialColorOnPrimaryContainer));
        recognitionLabel.setGravity(Gravity.CENTER);
        recognitionButton.addView(recognitionLabel, new LinearLayout.LayoutParams(-1, -2));
        recognitionButton.setOnClickListener(v -> { close(); actions.recognize(); });
        int buttonSize = railWidth - rail.getPaddingLeft() - rail.getPaddingRight();
        LinearLayout.LayoutParams recognitionBounds = new LinearLayout.LayoutParams(buttonSize, dp(c, 56));
        recognitionBounds.bottomMargin = dp(c, 6);
        rail.addView(recognitionButton, recognitionBounds);
        gridButton = new Glyph(c, Glyph.GRID, c.getString(R.string.all_apps));
        gridButton.setBackground(buttonBackground(c, secondaryContainer(c), buttonSize / 2f));
        gridButton.setOnClickListener(v -> { if (expanded) collapse(); else showApps(); });
        rail.addView(gridButton, new LinearLayout.LayoutParams(buttonSize, buttonSize));
        root.setOnApplyWindowInsetsListener((v, wi) -> {
            insets = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            layout();
            return wi;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) layout();
        });
        dialog.setContentView(root);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (expanded) collapse(); else close();
            }
            return true;
        });
        dialog.setOnDismissListener(d -> {
            rail.animate().cancel(); drawer.animate().cancel();
            if (rail.getBackground() != null) rail.getBackground().setVisible(false, false);
            if (drawer.getBackground() != null) drawer.getBackground().setVisible(false, false);
            onClosed.run();
        });
    }

    void show() {
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { if (expanded) collapse(); else close(); });
        root.post(() -> {
            if (closing || !dialog.isShowing()) return;
            layout(); applySurface(rail);
            rail.setPivotX(left ? 0 : railWidth);
            rail.setPivotY(Math.max(0, Math.min(panelHeight, touchY - rail.getY())));
            rail.setScaleX(.16f); rail.setScaleY(.06f); rail.setAlpha(.2f);
            rail.animate().scaleX(1).scaleY(1).alpha(1).setDuration(duration(390))
                    .setInterpolator(new OvershootInterpolator(.45f)).start();
            if (prefs.enabled(HAPTICS)) rail.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
        });
    }

    private int duration(int base) {
        return base * prefs.get(ANIMATION, 100, 0, 200) / 100;
    }

    private void layout() {
        if (root.getWidth() == 0) return;
        int availableHeight = Math.max(dp(context, 80), root.getHeight() - insets.top - insets.bottom);
        panelHeight = Math.min(dp(context, 490), availableHeight - dp(context, 16));
        int contentHeight = Math.max(1, Math.min(7, pinned.size())) * (iconSize + dp(context, 10))
                + rail.getPaddingTop() + rail.getPaddingBottom()
                + gridButton.getLayoutParams().height + recognitionButton.getLayoutParams().height
                + dp(context, 17);
        panelHeight = Math.min(panelHeight, contentHeight);
        int margin = dp(context, 10);
        int top = Math.max(insets.top + dp(context, 8),
                Math.min((int) touchY - panelHeight / 2,
                        root.getHeight() - insets.bottom - panelHeight - dp(context, 8)));
        int railX = left ? insets.left + margin
                : root.getWidth() - insets.right - margin - railWidth;
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(railWidth, panelHeight);
        rlp.leftMargin = railX; rlp.topMargin = top;
        rail.setLayoutParams(rlp);
        drawerWidth = Math.min(dp(context, 278), root.getWidth() - insets.left - insets.right
                - railWidth - margin * 3);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(drawerWidth, panelHeight);
        dlp.leftMargin = left ? railX + railWidth + margin : railX - margin - drawerWidth;
        dlp.topMargin = top;
        drawer.setLayoutParams(dlp);
    }

    private void applySurface(View view) {
        if (!view.isAttachedToWindow() || view.getBackground() != null) return;
        float radius = view == rail ? railWidth / 2f : dp(context, 28);
        view.setBackground(rounded(surface(context), radius));
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setElevation(dp(context, 6));
    }

    private View appTile(AppCatalog.App app, boolean label) {
        LinearLayout tile = createAppTile(label);
        bindAppTile(tile, app);
        return tile;
    }

    private LinearLayout createAppTile(boolean label) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setBackground(buttonBackground(context, Color.TRANSPARENT, dp(context, 20)));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        if (label) {
            TextView title = text(context, "", 12, onSurface(context));
            title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(context, 4), 0, 0);
            tile.addView(title, new LinearLayout.LayoutParams(-1, -2));
        }
        return tile;
    }

    private void bindAppTile(LinearLayout tile, AppCatalog.App app) {
        tile.setContentDescription(app.label);
        ((ImageView) tile.getChildAt(0)).setImageDrawable(app.icon);
        if (tile.getChildCount() > 1) ((TextView) tile.getChildAt(1)).setText(app.label);
        tile.setOnClickListener(v -> {
            close();
            actions.launch(app, prefs.get(LAUNCH_MODE, 0, 0, 1) == 0);
        });
        tile.setOnLongClickListener(v -> {
            PopupMenu menu = new PopupMenu(context, v);
            menu.getMenu().add(R.string.small_window).setOnMenuItemClickListener(i -> {
                close(); actions.launch(app, true); return true;
            });
            menu.getMenu().add(R.string.fullscreen).setOnMenuItemClickListener(i -> {
                close(); actions.launch(app, false); return true;
            });
            List<String> keys = prefs.list(PINNED);
            if (!prefs.store.contains(PINNED)) for (AppCatalog.App a : pinned) keys.add(a.key());
            boolean contains = keys.contains(app.key());
            menu.getMenu().add(contains ? R.string.unpin : R.string.pin)
                    .setOnMenuItemClickListener(i -> {
                        if (contains) keys.remove(app.key()); else keys.add(app.key());
                        prefs.putList(PINNED, keys); close(); return true;
                    });
            menu.show(); return true;
        });
    }

    private void showApps() {
        if (closing) return;
        if (appSearch == null) createApps();
        else {
            // Preserve the existing reset-on-open behavior while retaining the grid's
            // holders and search controls for the lifetime of this panel.
            if (appSearch.length() != 0) appSearch.setText("");
            appGrid.scrollToPosition(0);
        }
        // Opening the drawer must not immediately summon the keyboard.
        drawer.setFocusableInTouchMode(true); drawer.requestFocus();
        expand();
    }

    private void createApps() {
        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(context);
        appSearch = search;
        search.setSingleLine(); search.setTextSize(14); search.setTextColor(onSurface(context));
        search.setHintTextColor(context.getColor(com.android.internal.R.color.materialColorOnSurfaceVariant));
        search.setHint(R.string.search);
        search.setPadding(0, 0, dp(context, 8), 0);
        search.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout searchBox = new LinearLayout(context);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(rounded(context.getColor(
                com.android.internal.R.color.materialColorSurfaceContainerHighest), dp(context, 24)));
        Glyph magnifier = new Glyph(context, Glyph.SEARCH, context.getString(R.string.search));
        magnifier.setFocusable(false);
        magnifier.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(magnifier, new LinearLayout.LayoutParams(dp(context, 38), dp(context, 40)));
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(context, 40), 1));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(context, 40), 1));
        Glyph edit = new Glyph(context, Glyph.EDIT, context.getString(R.string.edit));
        edit.setBackground(buttonBackground(context, secondaryContainer(context), dp(context, 24)));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40));
        elp.leftMargin = dp(context, 8); searchRow.addView(edit, elp);
        edit.setOnClickListener(v -> { close(); actions.edit(); });
        drawer.addView(searchRow);
        TextView title = text(context, context.getString(R.string.all_apps), 14, onSurface(context));
        title.setPadding(dp(context, 4), dp(context, 12), 0, dp(context, 10));
        drawer.addView(title);
        RecyclerView grid = new RecyclerView(context);
        appGrid = grid;
        grid.setClipChildren(true);
        grid.setClipToPadding(true);
        grid.setLayoutManager(new GridLayoutManager(context, 3));
        grid.setItemAnimator(null);
        AppGridAdapter adapter = new AppGridAdapter();
        grid.setAdapter(adapter);
        TextView empty = text(context, context.getString(R.string.no_apps), 14, onSurface(context));
        drawer.addView(empty);
        drawer.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        Consumer<String> populate = query -> {
            List<AppCatalog.App> entries = AppCatalog.filter(apps, query);
            if (!adapter.entries.equals(entries)) {
                adapter.entries = entries;
                adapter.notifyDataSetChanged();
            }
            grid.scrollToPosition(0);
            empty.setVisibility(adapter.entries.isEmpty() ? View.VISIBLE : View.GONE);
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { populate.accept(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
        populate.accept("");
    }

    private final class AppGridAdapter extends RecyclerView.Adapter<AppHolder> {
        private List<AppCatalog.App> entries = List.of();
        @Override public AppHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            LinearLayout tile = createAppTile(true);
            tile.setLayoutParams(new RecyclerView.LayoutParams(-1, iconSize + dp(context, 44)));
            return new AppHolder(tile);
        }
        @Override public void onBindViewHolder(AppHolder holder, int position) {
            bindAppTile((LinearLayout) holder.itemView, entries.get(position));
        }
        @Override public int getItemCount() { return entries.size(); }
    }

    private static final class AppHolder extends RecyclerView.ViewHolder {
        AppHolder(View tile) { super(tile); }
    }

    private void expand() {
        if (closing) return;
        expanded = true;
        drawer.animate().cancel();
        drawer.animate().withEndAction(null);
        gridButton.setKind(Glyph.BACK);
        gridButton.setScaleX(left ? 1 : -1);
        gridButton.setContentDescription(context.getString(R.string.collapse));
        drawer.setVisibility(View.VISIBLE); applySurface(drawer);
        drawer.setPivotX(left ? 0 : drawerWidth); drawer.setPivotY(panelHeight / 2f);
        drawer.setScaleX(.15f); drawer.setScaleY(.9f); drawer.setAlpha(0);
        drawer.animate().scaleX(1).scaleY(1).alpha(1).setDuration(duration(360))
                .setInterpolator(new OvershootInterpolator(.35f)).start();
    }

    private void collapse() {
        expanded = false;
        gridButton.setKind(Glyph.GRID);
        gridButton.setScaleX(1);
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        gridButton.setContentDescription(context.getString(R.string.all_apps));
        drawer.animate().cancel();
        drawer.animate().scaleX(.12f).scaleY(.92f).alpha(0).setDuration(duration(180))
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    void dismissImmediately() { closing = true; dialog.dismiss(); }

    void close() {
        if (closing) return;
        closing = true;
        drawer.animate().cancel(); rail.animate().cancel();
        drawer.animate().alpha(0).scaleX(.15f).setDuration(duration(150)).start();
        rail.animate().scaleX(.16f).scaleY(.06f).alpha(0).setDuration(duration(220))
                .setInterpolator(new PathInterpolator(.4f, 0, 1, 1))
                .withEndAction(dialog::dismiss).start();
    }
}
