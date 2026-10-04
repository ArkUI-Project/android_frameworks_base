/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;

import static com.android.internal.arkui.SidebarContract.*;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.internal.arkui.IArkSidebar;
import com.android.internal.arkui.IArkSidebarHost;
import com.android.internal.arkui.SmallWindowSettings;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Lifecycle/permission bridge. All sidebar windows and preferences live in the independent APK. */
@SysUISingleton
public final class SidebarManager implements CoreStartable {
    private static final String TAG = "ArkSidebar";
    private final Context context;
    private final UserTracker users;
    private final Handler main;
    private final Executor background;
    private final KeyguardStateController keyguard;
    private volatile IArkSidebar service;
    private volatile Bundle configuration = Bundle.EMPTY;
    private volatile boolean available;
    private ServiceConnection connection;
    private boolean bound;
    private int generation, stateGeneration;
    private String foreground = "";
    private boolean foregroundUserMatches;
    private String[] recent = new String[0];
    private final Runnable retry = this::bind;
    private final Runnable taskRefresh = this::refreshTasks;
    private boolean taskQueryRunning, taskQueryPending;
    private IArkSidebar lastEnvironmentTarget;
    private boolean lastAvailable;
    private String lastForeground;
    private String[] lastRecent;

    @Inject public SidebarManager(Context context, UserTracker users, @Main Handler handler,
            @Background Executor background, KeyguardStateController keyguard) {
        this.context = context; this.users = users; main = handler;
        this.background = background; this.keyguard = keyguard;
    }

    @Override public void start() {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        users.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanged(int id, @NonNull Context userContext) {
                unbind(); foreground = ""; foregroundUserMatches = false;
                recent = new String[0]; bind();
            }
        }, main::post);
        keyguard.addCallback(new KeyguardStateController.Callback() {
            @Override public void onKeyguardShowingChanged() { main.post(SidebarManager.this::sendEnvironment); }
        });
        TaskStackChangeListeners.getInstance().registerTaskStackListener(new TaskStackChangeListener() {
            @Override public void onTaskStackChanged() {
                if (service != null && configuration.getInt(ENABLED, 0) == 1) {
                    main.post(SidebarManager.this::requestTaskRefresh);
                }
            }
            @Override public void onLockTaskModeChanged(int mode) { main.post(SidebarManager.this::sendEnvironment); }
        });
        IntentFilter state = new IntentFilter();
        state.addAction(Intent.ACTION_USER_UNLOCKED); state.addAction(Intent.ACTION_SCREEN_OFF);
        state.addAction(Intent.ACTION_SCREEN_ON); state.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!bound) bind(); sendEnvironment();
            }
        }, state, null, main, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED); packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED); packages.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getData() != null && PACKAGE.equals(i.getData().getSchemeSpecificPart())) {
                    unbind(); main.postDelayed(retry, 500);
                }
            }
        }, packages, null, main, Context.RECEIVER_NOT_EXPORTED);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean self) { sendEnvironment(); }
                }, UserHandle.USER_ALL);
        bind();
    }

    private boolean environmentAllowed() {
        Context user = users.getUserContext();
        return user.getSystemService(UserManager.class).isUserUnlocked()
                && !user.getSystemService(UserManager.class).isManagedProfile()
                && context.getSystemService(PowerManager.class).isInteractive()
                && !keyguard.isShowing()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && context.getSystemService(ActivityManager.class).getLockTaskModeState()
                        == ActivityManager.LOCK_TASK_MODE_NONE
                && Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 0, users.getUserId()) != 0;
    }

    private void bind() {
        main.removeCallbacks(retry);
        if (bound || !users.getUserContext().getSystemService(UserManager.class).isUserUnlocked()) return;
        int user = users.getUserId(), token = ++generation;
        final int uid;
        try {
            uid = context.getPackageManager().getPackageUidAsUser(PACKAGE, user);
            if (context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
                    != PackageManager.SIGNATURE_MATCH) return;
        } catch (PackageManager.NameNotFoundException e) { return; }
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (token != generation || user != users.getUserId()) return;
                service = IArkSidebar.Stub.asInterface(binder);
                try { service.attachHost(host(uid, token)); }
                catch (RemoteException e) { reconnect(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (token == generation) {
                    service = null; available = false; configuration = Bundle.EMPTY;
                    lastEnvironmentTarget = null; ++stateGeneration;
                    main.removeCallbacks(taskRefresh);
                }
            }
            @Override public void onBindingDied(ComponentName name) { if (token == generation) reconnect(); }
            @Override public void onNullBinding(ComponentName name) { if (token == generation) unbind(); }
        };
        try {
            bound = context.bindServiceAsUser(new Intent().setComponent(SERVICE), connection,
                    Context.BIND_AUTO_CREATE, main, UserHandle.of(user));
        } catch (SecurityException e) { Log.w(TAG, "Cannot bind sidebar", e); }
    }

    private void reconnect() { unbind(); main.postDelayed(retry, 1500); }
    private void unbind() {
        ++generation; ++stateGeneration; available = false; configuration = Bundle.EMPTY;
        main.removeCallbacks(taskRefresh); taskQueryPending = false; lastEnvironmentTarget = null;
        if (service != null) try { service.updateEnvironment(false, "", new String[0]); }
        catch (RemoteException ignored) { }
        service = null;
        if (bound) context.unbindService(connection);
        bound = false; connection = null;
    }

    private IArkSidebarHost host(int uid, int token) {
        return new IArkSidebarHost.Stub() {
            private void checkCaller() {
                if (Binder.getCallingUid() != uid) throw new SecurityException("Unexpected sidebar caller");
            }
            @Override public void configurationChanged(Bundle config) {
                checkCaller();
                if (config == null) return;
                // Copy only protocol keys. Never accept arbitrary intents, user IDs or options.
                Bundle clean = new Bundle();
                clean.putInt(READY, integer(config, READY, 0, 0, 1));
                clean.putInt(ENABLED, integer(config, ENABLED, 0, 0, 1));
                clean.putInt(MODE, integer(config, MODE, HANDLE, 0, 2));
                clean.putInt(HOLD_DELAY, integer(config, HOLD_DELAY, 300, 150, 800));
                clean.putInt(HOLD_DISTANCE, integer(config, HOLD_DISTANCE, 48, 24, 120));
                clean.putInt(HAPTICS, integer(config, HAPTICS, 1, 0, 1));
                clean.putInt(RECOMMEND, integer(config, RECOMMEND, 1, 0, 1));
                main.post(() -> {
                    if (token != generation) return;
                    boolean changed = configuration.getInt(ENABLED, 0) != clean.getInt(ENABLED);
                    boolean recommendChanged = configuration.getInt(RECOMMEND, 1) != clean.getInt(RECOMMEND);
                    configuration = clean;
                    if (changed) {
                        foregroundUserMatches = false; foreground = ""; recent = new String[0];
                        ++stateGeneration;
                        main.removeCallbacks(taskRefresh); taskQueryPending = false;
                    }
                    if (changed || recommendChanged || lastEnvironmentTarget == null) requestTaskRefresh();
                    // READY and gesture tuning do not change the foreground/lock-screen environment.
                    if (changed || lastEnvironmentTarget == null) sendEnvironment();
                });
            }
            @Override public void launchApplication(ComponentName component, boolean small) {
                checkCaller();
                main.post(() -> { if (token == generation && environmentAllowed()) launch(component, small); });
            }
        };
    }

    private void requestTaskRefresh() {
        if (service == null || configuration.getInt(ENABLED, 0) == 0) return;
        ++stateGeneration;
        main.removeCallbacks(taskRefresh);
        main.postDelayed(taskRefresh, 100);
    }

    private void refreshTasks() {
        if (service == null || configuration.getInt(ENABLED, 0) == 0) return;
        if (taskQueryRunning) { taskQueryPending = true; return; }
        taskQueryRunning = true;
        final int user = users.getUserId(), sequence = stateGeneration;
        final boolean recommend = configuration.getInt(RECOMMEND, 1) == 1;
        final String knownForeground = foreground;
        final String[] previousRecent = recent;
        background.execute(() -> {
            String pkg = "";
            boolean userMatches = false;
            List<String> list = new ArrayList<>();
            try {
                ActivityManager.RunningTaskInfo top = ActivityManagerWrapper.getInstance().getRunningTask();
                userMatches = top != null && top.userId == user
                        && top.displayId == Display.DEFAULT_DISPLAY && top.topActivity != null;
                if (userMatches) pkg = top.topActivity.getPackageName();
                if (recommend && (!pkg.equals(knownForeground) || previousRecent.length == 0)) {
                    for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getInstance()
                            .getRecentTasks(24, ActivityManager.RECENT_IGNORE_UNAVAILABLE, user)) {
                        if (task.userId == user && task.realActivity != null) {
                            String name = task.realActivity.getPackageName();
                            if (!name.equals(pkg) && !list.contains(name)) list.add(name);
                        }
                    }
                } else if (recommend) list.addAll(Arrays.asList(previousRecent));
            } catch (RuntimeException e) { Log.w(TAG, "Cannot query recent sidebar apps", e); }
            String topPackage = pkg;
            boolean ownsForeground = userMatches;
            main.post(() -> {
                taskQueryRunning = false;
                if (sequence == stateGeneration && user == users.getUserId()) {
                    foreground = topPackage; foregroundUserMatches = ownsForeground;
                    recent = list.toArray(new String[0]); sendEnvironment();
                }
                if (taskQueryPending) {
                    taskQueryPending = false;
                    main.removeCallbacks(taskRefresh); main.postDelayed(taskRefresh, 100);
                }
            });
        });
    }

    private void sendEnvironment() {
        IArkSidebar target = service;
        available = target != null && configuration.getInt(ENABLED, 0) == 1
                && foregroundUserMatches && environmentAllowed();
        if (target == null || (target == lastEnvironmentTarget && available == lastAvailable
                && foreground.equals(lastForeground) && Arrays.equals(recent, lastRecent))) return;
        try {
            target.updateEnvironment(available, foreground, recent);
            lastEnvironmentTarget = target; lastAvailable = available;
            lastForeground = foreground; lastRecent = recent;
        } catch (RemoteException e) { available = false; lastEnvironmentTarget = null; }
    }

    public Bundle getConfiguration() { return new Bundle(configuration); }
    public boolean isBackHoldEnabled() {
        return available && configuration.getInt(ENABLED, 0) == 1
                && configuration.getInt(READY, 0) == 1
                && configuration.getInt(MODE, HANDLE) != HANDLE;
    }
    public void openFromGesture(boolean left, float y) {
        // The input thread never waits for the app's main thread or a Binder reply.
        main.post(() -> {
            IArkSidebar target = service;
            if (!isBackHoldEnabled() || target == null) return;
            try { target.openFromGesture(left, y); }
            catch (RemoteException ignored) { }
        });
    }

    private void launch(ComponentName component, boolean small) {
        if (component == null || configuration.getInt(ENABLED, 0) != 1) return;
        try {
            if (SETTINGS.equals(component)) {
                context.startActivityAsUser(new Intent().setComponent(SETTINGS)
                        .putExtra("edit_apps", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), users.getUserHandle());
                return;
            }
            LauncherApps launcher = context.getSystemService(LauncherApps.class);
            boolean valid = false;
            for (LauncherActivityInfo item : launcher.getActivityList(component.getPackageName(), users.getUserHandle())) {
                if (component.equals(item.getComponentName())) { valid = true; break; }
            }
            if (!valid || !launcher.isActivityEnabled(component, users.getUserHandle())) return;
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            boolean allowed = SmallWindowSettings.isPackageAllowed(users.getUserContext(), component.getPackageName());
            options.setLaunchWindowingMode(small && allowed
                    ? WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT
                    : WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
            if (small && !allowed) Toast.makeText(context, R.string.arkui_sidebar_fullscreen_fallback, Toast.LENGTH_SHORT).show();
            launcher.startMainActivity(component, users.getUserHandle(), null, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot launch sidebar application", e);
            Toast.makeText(context, R.string.arkui_sidebar_app_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
