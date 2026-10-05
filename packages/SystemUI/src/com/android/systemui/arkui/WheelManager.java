/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;

import static org.arkui.wheel.protocol.WheelProtocol.*;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.graphics.Bitmap;
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
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.ScreenCapture;
import android.window.ScreenCaptureInternal;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.android.internal.arkui.SmallWindowSettings;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.recents.Recents;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;

/** Validates the standalone wheel's narrow, current-user-only launcher/action protocol. */
@SysUISingleton
public final class WheelManager implements CoreStartable {
    private final Context context;
    private final UserTracker users;
    private final Handler main;
    private final Executor background;
    private final KeyguardStateController keyguard;
    private final ShadeController shade;
    private final Optional<Recents> recentsController;
    private final FlashlightController flashlight;
    private final ScreenshotHelper screenshot;
    private ServiceConnection connection;
    private Messenger service;
    private boolean bound, enabled, foregroundMatches, querying, queryPending;
    private int generation, stateGeneration;
    private long lastAction;
    private String foreground = "";
    private String[] recent = new String[0];
    private Bundle lastEnvironment;
    private ServiceConnection recognitionConnection;
    private Messenger recognitionService;
    private boolean recognitionBound;
    private int recognitionGeneration, recognitionTaskId = -1, recognitionTaskUser = -1;
    private ComponentName recognitionActivity;
    private Runnable recognitionCaptureFinished;
    private final Runnable recognitionTimeout = this::closeSidebarRecognition;
    private final Runnable retry = this::bind;
    private final Runnable refresh = this::queryTasks;

    @Inject public WheelManager(Context context, UserTracker users, @Main Handler main,
            @Background Executor background, KeyguardStateController keyguard, ShadeController shade,
            Optional<Recents> recents, FlashlightController flashlight) {
        this.context = context; this.users = users; this.main = main; this.background = background;
        this.keyguard = keyguard; this.shade = shade; recentsController = recents;
        this.flashlight = flashlight; screenshot = new ScreenshotHelper(context);
    }
    @Override public void start() {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        users.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanged(int id, @NonNull Context user) {
                closeSidebarRecognition(); unbind(); bind();
            }
        }, main::post);
        keyguard.addCallback(new KeyguardStateController.Callback() {
            @Override public void onKeyguardShowingChanged() {
                main.post(() -> { if (!allowed()) closeSidebarRecognition(); environment(); });
            }
        });
        TaskStackChangeListeners.getInstance().registerTaskStackListener(new TaskStackChangeListener() {
            @Override public void onTaskStackChanged() {
                main.post(() -> { checkRecognitionForeground(); scheduleQuery(); });
            }
            @Override public void onLockTaskModeChanged(int mode) {
                main.post(() -> { if (!allowed()) closeSidebarRecognition(); environment(); });
            }
            @Override public void onTaskDisplayChanged(int taskId,int displayId) {
                main.post(() -> { checkRecognitionForeground(); scheduleQuery(); });
            }
        });
        IntentFilter state = new IntentFilter(); state.addAction(Intent.ACTION_USER_UNLOCKED);
        state.addAction(Intent.ACTION_SCREEN_ON); state.addAction(Intent.ACTION_SCREEN_OFF); state.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!allowed()) closeSidebarRecognition();
                if (!bound) bind(); environment();
            }
        }, state, null, main, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED); packages.addAction(Intent.ACTION_PACKAGE_REMOVED); packages.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getData() != null && PACKAGE.equals(i.getData().getSchemeSpecificPart())) {
                    closeSidebarRecognition(); reconnect();
                }
            }
        }, packages, null, main, Context.RECEIVER_NOT_EXPORTED);
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), false,
                new ContentObserver(main) { @Override public void onChange(boolean self) {
                    if (!allowed()) closeSidebarRecognition(); environment();
                } }, UserHandle.USER_ALL);
        bind();
    }
    private boolean allowed() {
        Context user = users.getUserContext();
        return user.getSystemService(UserManager.class).isUserUnlocked()
                && !user.getSystemService(UserManager.class).isManagedProfile()
                && context.getSystemService(PowerManager.class).isInteractive()
                && !keyguard.isShowing() && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && context.getSystemService(ActivityManager.class).getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE
                && Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0, users.getUserId()) != 0;
    }
    private void bind() {
        main.removeCallbacks(retry);
        if (bound || !users.getUserContext().getSystemService(UserManager.class).isUserUnlocked()) return;
        int user = users.getUserId(), token = ++generation;
        final int uid;
        try {
            uid = context.getPackageManager().getPackageUidAsUser(PACKAGE, user);
            if (context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE) != PackageManager.SIGNATURE_MATCH) return;
        } catch (PackageManager.NameNotFoundException e) { return; }
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (token != generation || user != users.getUserId()) return;
                service = new Messenger(binder);
                Messenger host = new Messenger(new Handler(main.getLooper(), message -> receive(message, uid, token)));
                Message attach = Message.obtain(null, ATTACH); attach.replyTo = host;
                try { service.send(attach); } catch (RemoteException e) { reconnect(); }
                if (recognitionBound) recognitionVisibility(true);
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (token == generation) { service = null; enabled = false; lastEnvironment = null; ++stateGeneration; }
            }
            @Override public void onBindingDied(ComponentName name) { if (token == generation) reconnect(); }
            @Override public void onNullBinding(ComponentName name) { if (token == generation) unbind(); }
        };
        try { bound = context.bindServiceAsUser(new Intent().setComponent(SERVICE), connection, Context.BIND_AUTO_CREATE, main, UserHandle.of(user)); }
        catch (SecurityException e) { Log.w("ArkWheel", "Cannot bind wheel", e); }
    }
    private boolean receive(Message message, int uid, int token) {
        if (message.sendingUid != uid || token != generation || service == null) return true;
        Bundle data = message.getData();
        if (message.what == CONFIGURATION) {
            boolean value = data.getBoolean(ENABLED);
            if (value != enabled) {
                enabled = value; foregroundMatches = false; foreground = ""; recent = new String[0];
                ++stateGeneration; main.removeCallbacks(refresh);
                scheduleQuery();
            }
            environment(); return true;
        }
        if (!enabled || !foregroundMatches || !allowed() || SystemClock.uptimeMillis() - lastAction < 200) return true;
        lastAction = SystemClock.uptimeMillis();
        try {
            if (message.what == LAUNCH) launch(ComponentName.unflattenFromString(data.getString(COMPONENT, "")), data.getBoolean(SMALL));
            else if (message.what == ACTION) {
                if (data.getInt("action") == RECOGNIZE) capture(data.getInt(CAPTURE_ID));
                else action(data.getInt("action"));
            }
            else if (message.what == MANAGE) context.startActivityAsUser(new Intent().setComponent(SETTINGS)
                    .putExtra("edit_items", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), users.getUserHandle());
        } catch (RuntimeException e) {
            Log.w("ArkWheel", "Cannot execute wheel shortcut", e);
            Toast.makeText(context, R.string.arkui_sidebar_app_unavailable, Toast.LENGTH_SHORT).show();
        }
        return true;
    }
    private void reconnect() { unbind(); main.postDelayed(retry, 1000); }
    private void unbind() {
        ++generation; ++stateGeneration; enabled = false; environment(); service = null; lastEnvironment = null;
        main.removeCallbacks(retry); main.removeCallbacks(refresh); queryPending = false; foregroundMatches = false;
        foreground = ""; recent = new String[0];
        if (bound) context.unbindService(connection); bound = false; connection = null;
    }
    private void scheduleQuery() {
        if (service == null || !enabled) return;
        ++stateGeneration; main.removeCallbacks(refresh); main.postDelayed(refresh, 100);
    }
    private void queryTasks() {
        if (service == null || !enabled) return;
        if (querying) { queryPending = true; return; }
        querying = true; int user = users.getUserId(), sequence = stateGeneration;
        background.execute(() -> {
            boolean matches = false; String pkg = ""; List<String> packages = new ArrayList<>();
            try {
                var tasks=ActivityTaskManager.getInstance().getTasks(1,false,false,Display.DEFAULT_DISPLAY);
                ActivityManager.RunningTaskInfo top = tasks.isEmpty()?null:tasks.get(0);
                matches = top != null && top.userId == user && top.displayId == Display.DEFAULT_DISPLAY && top.topActivity != null;
                if (matches) pkg = top.topActivity.getPackageName();
                for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getInstance().getRecentTasks(12, ActivityManager.RECENT_IGNORE_UNAVAILABLE, user)) {
                    if (task.userId == user && task.realActivity != null) {
                        String name = task.realActivity.getPackageName();
                        if (!name.equals(pkg) && !packages.contains(name)) packages.add(name);
                    }
                }
            } catch (RuntimeException e) { Log.w("ArkWheel", "Cannot query foreground", e); }
            boolean valid = matches; String name = pkg;
            main.post(() -> {
                querying = false;
                if (sequence == stateGeneration && user == users.getUserId() && enabled) {
                    foregroundMatches = valid; foreground = name; recent = packages.toArray(new String[0]); environment();
                }
                if (queryPending) { queryPending = false; main.removeCallbacks(refresh); main.postDelayed(refresh, 100); }
            });
        });
    }
    private void environment() {
        if (service == null) return;
        boolean valid = enabled && foregroundMatches && allowed();
        if (lastEnvironment != null && lastEnvironment.getBoolean(ALLOWED) == valid
                && foreground.equals(lastEnvironment.getString(FOREGROUND))
                && Arrays.equals(recent, lastEnvironment.getStringArray(RECENT))) return;
        Bundle data = new Bundle(); data.putBoolean(ALLOWED, valid); data.putString(FOREGROUND, foreground); data.putStringArray(RECENT, recent);
        Message m = Message.obtain(null, ENVIRONMENT); m.setData(data);
        try { service.send(m); lastEnvironment = data; } catch (RemoteException e) { lastEnvironment = null; }
    }
    private void launch(ComponentName component, boolean small) {
        if (component == null) return;
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        boolean valid = false;
        for (LauncherActivityInfo item : launcher.getActivityList(component.getPackageName(), users.getUserHandle())) {
            if (component.equals(item.getComponentName())) { valid = true; break; }
        }
        if (!valid || !launcher.isActivityEnabled(component, users.getUserHandle())) return;
        boolean supports = SmallWindowSettings.isPackageAllowed(users.getUserContext(), component.getPackageName());
        ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        options.setLaunchWindowingMode(small && supports ? WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT : WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        if (small && !supports) Toast.makeText(context, R.string.arkui_sidebar_fullscreen_fallback, Toast.LENGTH_SHORT).show();
        launcher.startMainActivity(component, users.getUserHandle(), null, options.toBundle());
    }
    private void action(int action) {
        switch (action) {
            case BACK: key(KeyEvent.KEYCODE_BACK); break;
            case HOME: key(KeyEvent.KEYCODE_HOME); break;
            case RECENTS: recentsController.ifPresent(Recents::toggleRecentApps); break;
            case NOTIFICATIONS: shade.animateExpandShade(); break;
            case QUICK_SETTINGS: shade.animateExpandQs(); break;
            case SCREENSHOT:
                int token = generation;
                main.postDelayed(() -> {
                    if (token == generation && enabled && allowed()) screenshot.takeScreenshot(
                            WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS, main, null);
                }, 150); break;
            case LOCK:
                context.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_ACCESSIBILITY, 0);
                try { WindowManagerGlobal.getWindowManagerService().lockNow(null); } catch (RemoteException ignored) { } break;
            case FLASHLIGHT:
                if (flashlight.isAvailable()) flashlight.setFlashlight(!flashlight.isEnabled());
                else Toast.makeText(context, R.string.arkui_sidebar_app_unavailable, Toast.LENGTH_SHORT).show(); break;
            default: break;
        }
    }
    private void capture(int request) {
        int user = users.getUserId(), token = generation;
        String source = foreground;
        main.postDelayed(() -> {
            if (token != generation || !enabled || !allowed()) return;
            background.execute(() -> {
                Bitmap result = captureScreen(user, user);
                main.post(() -> {
                    try {
                        if (token != generation || user != users.getUserId() || !enabled || !allowed()
                                || !source.equals(foreground) || service == null) return;
                        Message reply = Message.obtain(null, CAPTURE_RESULT);
                        Bundle data = new Bundle(); data.putInt(CAPTURE_ID, request); data.putParcelable(BITMAP, result); reply.setData(data);
                        service.send(reply);
                    } catch (RemoteException e) { Log.w("ArkWheel", "Recognition receiver disconnected", e); }
                    finally { if (result != null) result.recycle(); }
                });
            });
        }, 100);
    }

    /** The sidebar supplies a validated current-user request after its closing animation. */
    public void recognizeFromSidebar(String source, BooleanSupplier valid, Runnable captureFinished) {
        closeSidebarRecognition();
        recognitionCaptureFinished = captureFinished;
        if (!allowed() || !valid.getAsBoolean()) { closeSidebarRecognition(); return; }
        int user = users.getUserId(), token = ++recognitionGeneration;
        List<UserInfo> profiles = users.getUserProfiles();
        final int uid;
        try {
            uid = context.getPackageManager().getPackageUidAsUser(PACKAGE, user);
            if (context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
                    != PackageManager.SIGNATURE_MATCH) { closeSidebarRecognition(); return; }
        } catch (PackageManager.NameNotFoundException e) { closeSidebarRecognition(); return; }
        recognitionConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (token != recognitionGeneration || user != users.getUserId()) return;
                recognitionService = new Messenger(binder);
                // Leave a compositor frame after the sidebar's final window/handle removal.
                main.postDelayed(() -> {
                    if (token != recognitionGeneration || !allowed() || !valid.getAsBoolean()) {
                        if (token == recognitionGeneration) closeSidebarRecognition(); return;
                    }
                    background.execute(() -> {
                        ActivityManager.RunningTaskInfo top = recognitionTopTask();
                        boolean owned = top != null && top.topActivity != null
                                && source.equals(top.topActivity.getPackageName())
                                && (top.userId == user || profiles.stream().anyMatch(profile ->
                                        profile.id == top.userId && profile.isEnabled()
                                                && (profile.isAppTwinProfile() || profile.isCloneProfile())));
                        Bitmap bitmap = owned ? captureScreen(user, top.userId) : null;
                        ActivityManager.RunningTaskInfo after = recognitionTopTask();
                        boolean unchanged = owned && after != null && after.taskId == top.taskId
                                && after.userId == top.userId && top.topActivity.equals(after.topActivity);
                        main.post(() -> {
                            try {
                                if (token != recognitionGeneration) return;
                                if (user != users.getUserId() || !allowed() || !valid.getAsBoolean()
                                        || !unchanged || recognitionService == null) {
                                    closeSidebarRecognition(); return;
                                }
                                // A task-change callback may have arrived before capture established
                                // its task identity. Verify again at the actual bitmap handoff.
                                ActivityManager.RunningTaskInfo current = recognitionTopTask();
                                if (current == null || current.taskId != top.taskId
                                        || current.userId != top.userId
                                        || !top.topActivity.equals(current.topActivity)) {
                                    closeSidebarRecognition(); return;
                                }
                                recognitionTaskId = top.taskId; recognitionTaskUser = top.userId;
                                recognitionActivity = top.topActivity;
                                Message reply = Message.obtain(null, CAPTURE_RESULT);
                                Bundle data = new Bundle(); data.putParcelable(BITMAP, bitmap); reply.setData(data);
                                reply.replyTo = new Messenger(new Handler(main.getLooper(), message -> {
                                    if (message.sendingUid == uid && token == recognitionGeneration
                                            && message.what == RECOGNITION_CLOSED) closeSidebarRecognition();
                                    return true;
                                }));
                                recognitionService.send(reply);
                                main.removeCallbacks(recognitionTimeout); finishSidebarCapture();
                            } catch (RemoteException e) {
                                Log.w("ArkWheel", "Sidebar recognition receiver disconnected", e);
                                closeSidebarRecognition();
                            } finally { if (bitmap != null) bitmap.recycle(); }
                        });
                    });
                }, 100);
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (token == recognitionGeneration) closeSidebarRecognition();
            }
            @Override public void onBindingDied(ComponentName name) {
                if (token == recognitionGeneration) closeSidebarRecognition();
            }
            @Override public void onNullBinding(ComponentName name) {
                if (token == recognitionGeneration) closeSidebarRecognition();
            }
        };
        try {
            recognitionBound = context.bindServiceAsUser(new Intent().setComponent(RECOGNITION_SERVICE),
                    recognitionConnection, Context.BIND_AUTO_CREATE, main, UserHandle.of(user));
        } catch (SecurityException e) { Log.w("ArkWheel", "Cannot bind sidebar recognition", e); }
        if (recognitionBound) {
            recognitionVisibility(true); main.postDelayed(recognitionTimeout, 6000);
        } else closeSidebarRecognition();
    }

    private ActivityManager.RunningTaskInfo recognitionTopTask() {
        try {
            var tasks = ActivityTaskManager.getInstance().getTasks(1, false, false, Display.DEFAULT_DISPLAY);
            return tasks.isEmpty() ? null : tasks.get(0);
        } catch (RuntimeException e) { return null; }
    }
    private void checkRecognitionForeground() {
        if (!recognitionBound || recognitionTaskId < 0) return;
        int token = recognitionGeneration, task = recognitionTaskId, user = recognitionTaskUser;
        ComponentName activity = recognitionActivity;
        background.execute(() -> {
            ActivityManager.RunningTaskInfo top = recognitionTopTask();
            if (top == null || top.taskId != task || top.userId != user
                    || !activity.equals(top.topActivity)) main.post(() -> {
                if (token == recognitionGeneration) closeSidebarRecognition();
            });
        });
    }
    private void finishSidebarCapture() {
        Runnable finished = recognitionCaptureFinished; recognitionCaptureFinished = null;
        if (finished != null) finished.run();
    }
    private void closeSidebarRecognition() {
        ++recognitionGeneration; main.removeCallbacks(recognitionTimeout);
        recognitionService = null; recognitionTaskId = -1; recognitionTaskUser = -1;
        recognitionActivity = null;
        if (recognitionBound) context.unbindService(recognitionConnection);
        recognitionBound = false; recognitionConnection = null; finishSidebarCapture();
        recognitionVisibility(false);
    }
    private void recognitionVisibility(boolean visible) {
        if (service == null) return;
        Message message = Message.obtain(null, RECOGNITION_VISIBILITY); message.arg1 = visible ? 1 : 0;
        try { service.send(message); }
        catch (RemoteException e) { Log.w("ArkWheel", "Cannot update recognition visibility", e); }
    }
    private Bitmap captureScreen(int parentUser, int foregroundUser) {
        try {
            for (int user : new int[] { parentUser, foregroundUser }) {
                if (context.createContextAsUser(UserHandle.of(user), 0)
                        .getSystemService(DevicePolicyManager.class).getScreenCaptureDisabled(null)) return null;
            }
            var args = new ScreenCaptureInternal.CaptureArgs.Builder<>()
                    .setSecureContentPolicy(ScreenCapture.ScreenCaptureParams.SECURE_CONTENT_POLICY_THROW_EXCEPTION)
                    .setProtectedContentPolicy(ScreenCapture.ScreenCaptureParams.PROTECTED_CONTENT_POLICY_REDACT).build();
            var listener = ScreenCaptureInternal.createSyncCaptureListener();
            WindowManagerGlobal.getWindowManagerService().captureDisplay(Display.DEFAULT_DISPLAY, args, listener);
            var buffer = listener.getBuffer();
            if (buffer == null) return null;
            try {
                if (buffer.containsSecureLayers()) return null;
                Bitmap hardware = buffer.asBitmap();
                if (hardware == null) return null;
                Bitmap copy;
                try { copy = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                finally { hardware.recycle(); }
                if (copy == null) return null;
                Bitmap shared = null;
                try { shared = copy.asShared(); return shared; }
                finally { if (shared != copy) copy.recycle(); }
            } finally { if (buffer.getHardwareBuffer() != null) buffer.getHardwareBuffer().close(); }
        } catch (RuntimeException | RemoteException e) {
            Log.w("ArkWheel", "Cannot capture recognition screen", e); return null;
        }
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            KeyEvent event = KeyEvent.obtain(now, SystemClock.uptimeMillis(), action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD, null);
            context.getSystemService(InputManager.class).injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC); event.recycle();
        }
    }
}
