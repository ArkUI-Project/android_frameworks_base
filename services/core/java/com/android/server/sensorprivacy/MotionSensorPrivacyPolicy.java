/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.sensorprivacy;

import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TOP_SLEEPING;
import static com.android.internal.arkui.MotionSensorSettings.ALLOW;
import static com.android.internal.arkui.MotionSensorSettings.BLOCK_ON_OPEN;
import static com.android.internal.arkui.MotionSensorSettings.DENY;
import static com.android.internal.arkui.MotionSensorSettings.OPEN_BLOCK_NANOS;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.IMotionSensorPrivacyListener;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.arkui.MotionSensorSettings;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.util.Map;

/** Tracks actual application foreground entries; activity transitions do not restart the window. */
final class MotionSensorPrivacyPolicy {
    private final Context mContext;
    private final Handler mHandler = new Handler(BackgroundThread.get().getLooper());
    private final Object mLock = new Object();
    private final SparseIntArray mModes = new SparseIntArray();
    private final SparseLongArray mOpenedAt = new SparseLongArray();
    private final SparseLongArray mBlockedUntil = new SparseLongArray();
    private final ArraySet<Integer> mForeground = new ArraySet<>();
    private final RemoteCallbackList<IMotionSensorPrivacyListener> mListeners =
            new RemoteCallbackList<>();
    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int state, long seq, int capability) {
            // Capture the transition time before dispatching, so a busy handler cannot extend it.
            final long now = SystemClock.elapsedRealtimeNanos();
            synchronized (mLock) { updateForegroundLocked(uid, state, now); }
        }
        @Override public void onUidGone(int uid, boolean disabled) {
            synchronized (mLock) {
                mForeground.remove(uid);
                mOpenedAt.delete(uid);
            }
        }
        @Override public void onUidActive(int uid) { }
        @Override public void onUidIdle(int uid, boolean disabled) { }
        @Override public void onUidCachedChanged(int uid, boolean cached) { }
        @Override public void onUidProcAdjChanged(int uid, int adj) { }
    };

    MotionSensorPrivacyPolicy(Context context) { mContext = context; }

    void start() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(MotionSensorSettings.KEY), false,
                new ContentObserver(mHandler) {
                    @Override public void onChange(boolean selfChange) { reload(); }
                }, UserHandle.USER_ALL);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        && intent.getData() != null) {
                    final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid >= 0) MotionSensorSettings.setMode(context.getContentResolver(),
                            intent.getData().getSchemeSpecificPart(), UserHandle.getUserId(uid),
                            ALLOW);
                }
                reload();
            }
        }, UserHandle.ALL, filter, null, mHandler);
        reload();
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, "android");
        } catch (RemoteException e) {
            Slog.wtf("MotionSensorPrivacy", "Cannot observe foreground applications", e);
        }
    }

    void reloadAsync() { mHandler.post(this::reload); }

    private void reload() {
        final SparseIntArray modes = new SparseIntArray();
        final PackageManager pm = mContext.getPackageManager();
        for (int userId : LocalServices.getService(UserManagerInternal.class).getUserIds()) {
            for (Map.Entry<String, Integer> entry : MotionSensorSettings.read(
                    mContext.getContentResolver(), userId).entrySet()) {
                try {
                    final ApplicationInfo app = pm.getApplicationInfoAsUser(entry.getKey(), 0,
                            userId);
                    if (UserHandle.getAppId(app.uid) < Process.FIRST_APPLICATION_UID) continue;
                    // A shared UID has one sensor identity: apply its most restrictive choice.
                    modes.put(app.uid, Math.max(modes.get(app.uid, ALLOW), entry.getValue()));
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
        }
        synchronized (mLock) {
            final ArraySet<Integer> changed = new ArraySet<>();
            for (int i = 0; i < mModes.size(); i++) changed.add(mModes.keyAt(i));
            for (int i = 0; i < modes.size(); i++) changed.add(modes.keyAt(i));
            for (int uid : changed) {
                final int mode = modes.get(uid, ALLOW);
                if (mode == mModes.get(uid, ALLOW)) continue;
                mModes.put(uid, mode);
                final long until = mode == DENY ? Long.MAX_VALUE : mode == BLOCK_ON_OPEN
                        && mOpenedAt.indexOfKey(uid) >= 0
                        ? mOpenedAt.get(uid) + OPEN_BLOCK_NANOS : 0;
                setBlockedUntilLocked(uid, until);
                if (mode == ALLOW) mModes.delete(uid);
            }
        }
    }

    private void updateForegroundLocked(int uid, int state, long now) {
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID
                || Process.isSdkSandboxUid(uid)) return;
        // Unlocking the same top application is not a new application opening.
        if (state == PROCESS_STATE_TOP_SLEEPING) return;
        if (state == PROCESS_STATE_TOP) {
            if (mForeground.add(uid)) {
                mOpenedAt.put(uid, now);
                if (mModes.get(uid, ALLOW) == BLOCK_ON_OPEN) {
                    setBlockedUntilLocked(uid, now + OPEN_BLOCK_NANOS);
                }
            }
        } else {
            mForeground.remove(uid);
        }
    }

    long getBlockedUntil(int uid) {
        if (Process.isSdkSandboxUid(uid)) uid = Process.getAppUidForSdkSandboxUid(uid);
        final int state = LocalServices.getService(ActivityManagerInternal.class)
                .getUidProcessState(uid);
        synchronized (mLock) {
            // A new sensor connection can arrive before the asynchronous UID observer callback.
            updateForegroundLocked(uid, state, SystemClock.elapsedRealtimeNanos());
            return mBlockedUntil.get(uid);
        }
    }

    void addListener(IMotionSensorPrivacyListener listener) {
        synchronized (mLock) {
            mListeners.register(listener);
            for (int i = 0; i < mBlockedUntil.size(); i++) {
                notifyListener(listener, mBlockedUntil.keyAt(i), mBlockedUntil.valueAt(i));
            }
        }
    }

    void removeListener(IMotionSensorPrivacyListener listener) { mListeners.unregister(listener); }

    private void setBlockedUntilLocked(int uid, long until) {
        if (mBlockedUntil.get(uid) == until) return;
        mBlockedUntil.put(uid, until);
        final int count = mListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                notifyListener(mListeners.getBroadcastItem(i), uid, until);
            }
        } finally { mListeners.finishBroadcast(); }
    }

    private static void notifyListener(IMotionSensorPrivacyListener listener, int uid, long until) {
        try { listener.onMotionSensorPrivacyChanged(uid, until); }
        catch (RemoteException ignored) { }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Device motion access (elapsed realtime ns):");
            for (int i = 0; i < mModes.size(); i++) {
                final int uid = mModes.keyAt(i);
                pw.println("  uid=" + uid + " mode=" + mModes.valueAt(i)
                        + " opened=" + mOpenedAt.get(uid) + " blockedUntil="
                        + mBlockedUntil.get(uid));
            }
        }
    }
}
