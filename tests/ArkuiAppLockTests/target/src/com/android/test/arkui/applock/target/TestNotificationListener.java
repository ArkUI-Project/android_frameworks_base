/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock.target;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;

/** Captures what an ordinary notification listener can actually observe. */
public class TestNotificationListener extends NotificationListenerService {
    static volatile TestNotificationListener instance;
    static final ArrayList<String> events = new ArrayList<>();

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification, RankingMap ranking) {
        record("posted", notification);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification, RankingMap ranking,
            int reason) {
        record("removed", notification);
    }

    private static void record(String action, StatusBarNotification notification) {
        synchronized (events) {
            events.add(action + "|" + describe(notification));
            if (events.size() > 100) events.remove(0);
        }
    }

    static String describe(StatusBarNotification notification) {
        Bundle extras = notification.getNotification().extras;
        return notification.getPackageName() + "|" + notification.getId() + "|"
                + extras.getCharSequence(Notification.EXTRA_TITLE, "") + "|"
                + extras.getCharSequence(Notification.EXTRA_TEXT, "");
    }

    static Bundle read() {
        Bundle result = new Bundle();
        TestNotificationListener listener = instance;
        result.putBoolean("connected", listener != null);
        ArrayList<String> active = new ArrayList<>();
        if (listener != null) {
            for (StatusBarNotification notification : listener.getActiveNotifications()) {
                active.add(describe(notification));
            }
            result.putStringArrayList("ranking",
                    new ArrayList<>(java.util.Arrays.asList(
                            listener.getCurrentRanking().getOrderedKeys())));
        }
        result.putStringArrayList("active", active);
        synchronized (events) {
            result.putStringArrayList("events", new ArrayList<>(events));
        }
        return result;
    }
}
