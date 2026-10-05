/*
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.LruCache;
import android.view.View;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;

/** Adds a colored application icon to an existing standard notification icon slot. */
final class ArkDualNotificationIcons {
    private static final LruCache<String, Drawable.ConstantState> ICONS = new LruCache<>(32);
    private final ExpandableNotificationRow mRow;
    private final Context mUserContext;
    private final Rect mIconBounds = new Rect();
    private final Paint mContainerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String mRequestedKey;
    private String mPackageName;
    private int mDensityDpi;
    private Drawable mAppIcon;

    ArkDualNotificationIcons(ExpandableNotificationRow row, Context userContext) {
        mRow = row;
        mUserContext = userContext;
    }

    void draw(Canvas canvas) {
        if (NotificationBundleUi.isEnabled() || mRow.isChildInGroup()
                || mRow.isSummaryWithChildren() || mRow.isExpanded() || mRow.isUserLocked()
                || mRow.isHeadsUpState()
                || mRow.getShowingLayout() != mRow.getPrivateLayout()) return;
        NotificationEntry entry = mRow.getEntryLegacy();
        if (entry == null || entry.containsCustomViews() || entry.isMediaNotification()) return;
        View template = mRow.getPrivateLayout().getContractedChild();
        if (template == null || template.getVisibility() != View.VISIBLE
                || template.findViewById(
                        com.android.internal.R.id.notification_headerless_view_column) == null) return;
        View avatar = template.findViewById(com.android.internal.R.id.left_icon);
        if (avatar != null && avatar.getVisibility() == View.VISIBLE) return;
        View icon = template.findViewById(com.android.internal.R.id.icon);
        if (icon == null || icon.getVisibility() != View.VISIBLE || icon.getAlpha() <= 0f
                || !icon.getMatrix().isIdentity()) return;

        String packageName = entry.getSbn().getPackageName();
        int densityDpi = mUserContext.getResources().getConfiguration().densityDpi;
        if (!packageName.equals(mPackageName) || densityDpi != mDensityDpi) {
            mPackageName = packageName;
            mDensityDpi = densityDpi;
            requestIcon(mUserContext.getUserId() + ":" + packageName + ":" + densityDpi,
                    packageName);
        }
        if (mAppIcon == null) return;

        icon.getDrawingRect(mIconBounds);
        mRow.offsetDescendantRectToMyCoords(icon, mIconBounds);
        float alpha = icon.getAlpha();
        View ancestor = (View) icon.getParent();
        while (ancestor != mRow) {
            if (!ancestor.getMatrix().isIdentity()) return;
            alpha *= ancestor.getAlpha();
            if (!(ancestor.getParent() instanceof View next)) return;
            ancestor = next;
        }
        if (alpha <= 0f) return;
        int save = alpha < 1f ? canvas.saveLayerAlpha(mIconBounds.left, mIconBounds.top,
                mIconBounds.right, mIconBounds.bottom, Math.round(alpha * 255f)) : canvas.save();
        mContainerPaint.setColor(mRow.getContext().getColor(
                com.android.internal.R.color.materialColorSecondaryContainer));
        float radius = mIconBounds.width() * .3f;
        canvas.drawRoundRect(mIconBounds.left, mIconBounds.top, mIconBounds.right,
                mIconBounds.bottom, radius, radius, mContainerPaint);
        mAppIcon.setBounds(mIconBounds);
        mAppIcon.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void requestIcon(String key, String packageName) {
        mRequestedKey = key;
        mAppIcon = null;
        Drawable.ConstantState cached = ICONS.get(key);
        if (cached != null) {
            mAppIcon = cached.newDrawable(mUserContext.getResources()).mutate();
            return;
        }
        // Package lookups and icon decoding never run on a draw or animation callback.
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Drawable icon;
            try {
                icon = mUserContext.getPackageManager().getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                return; // Keep the original notification icon if the package disappeared.
            }
            Drawable.ConstantState state = icon.getConstantState();
            if (state != null) ICONS.put(key, state);
            mRow.post(() -> {
                if (!key.equals(mRequestedKey)) return;
                mAppIcon = state != null
                        ? state.newDrawable(mUserContext.getResources()).mutate() : icon.mutate();
                mRow.invalidate();
            });
        });
    }
}
