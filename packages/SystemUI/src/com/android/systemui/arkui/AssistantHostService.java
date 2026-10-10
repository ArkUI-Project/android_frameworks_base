/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;

import static org.arkui.assistant.protocol.AssistantProtocol.*;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.IScreenContentCallback;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.ScreenContent;
import android.view.WindowManagerGlobal;

import com.android.wm.shell.shared.ShellTransitions;

import org.json.JSONArray;

import javax.inject.Inject;

/** A leased, signature-checked channel; model output never reaches a shell or arbitrary intent. */
public final class AssistantHostService extends Service {
    private final ShellTransitions transitions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger owner;
    private String session;
    private int ownerUid = -1;
    private final Runnable expire = this::finishSession;
    private final IBinder.DeathRecipient death = () -> main.post(this::finishSession);
    private ActivityManager.RunningTaskInfo capturedTask;
    private Rect capturedBounds;
    private int capturedRotation;
    private long capturedAt;
    private boolean capturing, touching;
    private float touchX, touchY;
    private long touchDown;
    private Runnable gesture;
    private Message gestureRequest;
    private Message inputRequest;
    private AssistantVirtualSession virtual;
    private int displayId = Display.DEFAULT_DISPLAY;
    private ActivityManager.RunningTaskInfo foregroundTask;
    private boolean autoBackground, executionActive, movingToBackground, assistantNavigation;
    private String launchingPackage;
    private long launchingUntil;
    private final Runnable moveToBackground = this::finishBackgroundMove;
    private final TaskStackListener tasks = new TaskStackListener() {
        @Override public void onTaskStackChanged() { main.post(AssistantHostService.this::checkForeground); }
        @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo task) {
            main.post(AssistantHostService.this::checkForeground);
        }
        @Override public void onTaskFocusChanged(int taskId, boolean focused) {
            if (focused) main.post(AssistantHostService.this::checkForeground);
        }
    };
    private final Runnable inputTimeout = () -> {
        if (inputRequest != null) error(inputRequest, "Overlay input handoff timed out");
        inputRequest = null;
        inputState(false, 0);
    };
    private final BroadcastReceiver invalidated = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finishSession(); }
    };

    @Inject
    public AssistantHostService(ShellTransitions transitions) {
        this.transitions = transitions;
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerReceiver(invalidated, filter, Context.RECEIVER_NOT_EXPORTED);
        try { ActivityTaskManager.getService().registerTaskStackListener(tasks); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    @Override public IBinder onBind(Intent intent) { return receiver.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { finishSession(); return false; }
    @Override public void onDestroy() {
        unregisterReceiver(invalidated);
        try { ActivityTaskManager.getService().unregisterTaskStackListener(tasks); }
        catch (RemoteException ignored) { }
        finishSession(); super.onDestroy();
    }

    private boolean authorized(int uid) {
        int user = ActivityManager.getCurrentUser();
        try {
            return UserHandle.getUserId(uid) == user
                    && getPackageManager().getPackageUidAsUser(PACKAGE, user) == uid
                    && getPackageManager().checkSignatures(getPackageName(), PACKAGE)
                            == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private boolean allowed() {
        int user = ActivityManager.getCurrentUser();
        return owner != null && UserHandle.getUserId(ownerUid) == user
                && Settings.Secure.getIntForUser(getContentResolver(), ENABLED, 0, user) == 1
                && createContextAsUser(UserHandle.of(user), 0).getSystemService(UserManager.class).isUserUnlocked()
                && !createContextAsUser(UserHandle.of(user), 0).getSystemService(UserManager.class).isManagedProfile()
                && getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked()
                && getSystemService(ActivityManager.class).getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private boolean receive(Message request) {
        if (request.replyTo == null || !authorized(request.sendingUid)) return true;
        Bundle data = request.getData();
        String token = data.getString(SESSION, "");
        if (request.what == BEGIN) {
            if (token.length() < 16 || token.length() > 80) return true;
            finishSession();
            owner = request.replyTo; ownerUid = request.sendingUid; session = token;
            try { owner.getBinder().linkToDeath(death, 0); }
            catch (RemoteException e) { finishSession(); return true; }
        }
        if (owner == null || request.sendingUid != ownerUid || !token.equals(session)
                || !request.replyTo.getBinder().equals(owner.getBinder())) return true;
        if (request.what == END) { finishSession(); return true; }
        if (!allowed()) { error(request, "Device locked, user changed or AI disabled"); finishSession(); return true; }
        main.removeCallbacks(expire); main.postDelayed(expire, 180_000);
        try {
            if (request.what == BEGIN) {
                autoBackground = data.getBoolean("auto_background");
                executionActive = true;
                if (data.getBoolean("background")) {
                    ActivityManager.RunningTaskInfo task = top();
                    if (task == null || task.topActivity == null) {
                        error(request, "No foreground app is available"); finishSession(); return true;
                    }
                    if (rejectRestrictedApp(request, task.topActivity.getPackageName())) {
                        finishSession(); return true;
                    }
                    virtual = new AssistantVirtualSession();
                    virtual.start(this, main, task);
                    displayId = virtual.displayId();
                }
                Bundle result = new Bundle(); result.putInt("display", displayId); reply(request, result);
            }
            else if (request.what == EXECUTION_STATE) {
                executionActive = data.getBoolean("active");
                if (executionActive) checkForeground();
            }
            else if (movingToBackground) error(request, "Display is moving; capture again");
            else if (request.what == CAPTURE || request.what == PREVIEW) capture(Message.obtain(request));
            else if (request.what == ACTION) prepareInput(Message.obtain(request));
            else if (request.what == INPUT_STATE) {
                if (inputRequest != null && inputRequest.arg1 == request.arg1) {
                    Message pending = inputRequest; inputRequest = null;
                    main.removeCallbacks(inputTimeout);
                    execute(pending);
                }
            } else if (request.what == TAKE_OVER) {
                if (capturing || touching || inputRequest != null) {
                    error(request, "Another operation is pending"); return true;
                }
                if (virtual != null) { virtual.finish(true); virtual = null; displayId = Display.DEFAULT_DISPLAY; }
                foregroundTask = top();
                capturedAt = 0; reply(request, new Bundle());
            }
            else error(request, "Unknown operation");
        } catch (RemoteException | RuntimeException e) {
            capturing = false;
            cancelGesture();
            if (request.what == BEGIN && data.getBoolean("background")) {
                android.util.Slog.w("ArkAssistant", "Cannot create assistant display", e);
                error(request, "Background mode unavailable. Open a fullscreen app first");
            } else error(request, "System operation unavailable");
            if (request.what == BEGIN) finishSession();
        }
        return true;
    }

    private ActivityManager.RunningTaskInfo top() {
        var tasks = ActivityTaskManager.getInstance().getTasks(1, false, false, displayId);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private void checkForeground() {
        if (!autoBackground || !executionActive || movingToBackground
                || displayId != Display.DEFAULT_DISPLAY || !allowed()) return;
        var task = top();
        if (task == null || !task.isFocused || task.userId != ActivityManager.getCurrentUser()) return;
        if (task.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD) {
            if (task.topActivity != null && task.topActivity.getPackageName().equals(launchingPackage)) {
                foregroundTask = task;
                launchingPackage = null;
            }
            return;
        }
        // Observe the system's completed Home navigation, not a swipe inside the app.
        // AI-requested Home/Recents actions must remain on the physical display.
        if (task.getActivityType() != WindowConfiguration.ACTIVITY_TYPE_HOME || assistantNavigation
                || foregroundTask == null) return;
        if (SystemClock.uptimeMillis() < launchingUntil) {
            main.postDelayed(this::checkForeground, launchingUntil - SystemClock.uptimeMillis());
            return;
        }
        movingToBackground = true;
        capturedAt = 0;
        if (gestureRequest != null) error(gestureRequest, "Display is moving; capture again");
        cancelGesture();
        if (inputRequest != null) error(inputRequest, "Display is moving; capture again");
        inputRequest = null; main.removeCallbacks(inputTimeout);
        displayState(null);
        // Recents' finish transaction restores its old surface parents. Migrate only after
        // that transaction has finished, or it can pull the resized task back onto display 0.
        String movingSession = session;
        transitions.runOnIdle(() -> {
            if (movingSession.equals(session) && movingToBackground) finishBackgroundMove();
        }, main::post);
    }

    private void finishBackgroundMove() {
        if (!movingToBackground || owner == null) return;
        if (!allowed()) { finishSession(); return; }
        if (!executionActive) { movingToBackground = false; displayState(null); return; }
        if (capturing) { main.postDelayed(moveToBackground, 40); return; }
        try {
            ActivityManager.RunningTaskInfo target = null;
            for (var task : ActivityTaskManager.getInstance().getTasks(100, false, false, Display.DEFAULT_DISPLAY)) {
                if (task.taskId == foregroundTask.taskId) { target = task; break; }
            }
            if (target == null || target.topActivity == null
                    || target.userId != ActivityManager.getCurrentUser() || blocked(target.topActivity.getPackageName())) {
                throw new IllegalStateException("App unavailable or blocked");
            }
            // A quick cancelled Home gesture must not pull the app away from its user.
            if (target.isFocused) {
                movingToBackground = false; displayState(null); return;
            }
            virtual = new AssistantVirtualSession();
            virtual.start(this, main, target);
            displayId = virtual.displayId();
            capturedTask = null; capturedAt = 0;
            movingToBackground = false;
            displayState(null);
        } catch (RuntimeException e) {
            if (virtual != null) virtual.finish(false);
            virtual = null; movingToBackground = false; executionActive = false;
            displayState("Background transfer unavailable");
        }
    }

    private void displayState(String error) {
        if (owner == null) return;
        Message message = Message.obtain(null, DISPLAY_STATE);
        Bundle data = new Bundle(); data.putString(SESSION, session);
        data.putBoolean("background", displayId != Display.DEFAULT_DISPLAY);
        data.putBoolean("moving", movingToBackground);
        if (error != null) data.putString(ERROR, error);
        message.setData(data);
        try { owner.send(message); } catch (RemoteException ignored) { finishSession(); }
    }

    private boolean blocked(String packageName) {
        return restriction(packageName) != null;
    }

    private String restriction(String packageName) {
        if (PACKAGE.equals(packageName)) return SCREEN_PROTECTED;
        String setting = Settings.Secure.getStringForUser(getContentResolver(), BLOCKED_APPS,
                ActivityManager.getCurrentUser());
        if (setting == null || setting.isEmpty()) return null;
        try {
            JSONArray apps = new JSONArray(setting);
            for (int i = 0; i < apps.length(); i++) {
                if (packageName.equals(apps.optString(i))) return APP_BLOCKED;
            }
            return null;
        } catch (org.json.JSONException e) { return POLICY_UNAVAILABLE; }
    }

    private boolean rejectRestrictedApp(Message request, String packageName) {
        String code = restriction(packageName);
        if (code == null) return false;
        String message = SCREEN_PROTECTED.equals(code) ? "ArkAI pages are protected from screen reading"
                : APP_BLOCKED.equals(code) ? "This app is in the user's blocklist"
                : "The app blocklist could not be read";
        Bundle result = new Bundle();
        result.putString(ERROR, message); result.putString(ERROR_CODE, code);
        reply(request, result);
        return true;
    }

    private boolean sameTask(ActivityManager.RunningTaskInfo task) {
        ActivityManager.RunningTaskInfo now = top();
        return task != null && now != null && now.taskId == task.taskId && now.userId == task.userId
                && now.topActivity != null && now.topActivity.equals(task.topActivity)
                && now.userId == ActivityManager.getCurrentUser();
    }

    private int rotation() {
        Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
        return display == null ? -1 : display.getRotation();
    }

    private void capture(Message request) throws RemoteException {
        if (capturing || touching) { error(request, "Another operation is pending"); return; }
        ActivityManager.RunningTaskInfo task = top();
        if (task == null || task.topActivityInfo == null || task.userId != ActivityManager.getCurrentUser()
                || task.topActivity == null) {
            error(request, "No readable foreground app is available"); return;
        }
        if (rejectRestrictedApp(request, task.topActivity.getPackageName())) return;
        capturing = true;
        final String token = session;
        final int captureDisplay = displayId;
        WindowManagerGlobal.getWindowManagerService().requestAssistantScreenContent(displayId,
                getPackageName(), new IScreenContentCallback.Stub() {
                    @Override public void onScreenContent(ScreenContent content) {
                        if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
                            if (content != null) content.close(); return;
                        }
                        main.post(() -> {
                            if (!token.equals(session)) { if (content != null) content.close(); return; }
                            capturing = false;
                            // A capture callback can beat the asynchronous task-stack callback.
                            // Announce the handoff before rejecting its now-stale physical frame.
                            checkForeground();
                            try {
                                if (!allowed() || movingToBackground || displayId != captureDisplay
                                        || !sameTask(task) || blocked(task.topActivity.getPackageName()) || content == null
                                        || content.status != ScreenContent.STATUS_OK || content.screenshot == null) {
                                    error(request, "Cannot capture a protected, changed or unavailable screen"); return;
                                }
                                // Viewing progress must never authorize an AI action against a
                                // screen the model has not observed. Both paths use the same WM gates.
                                if (request.what == CAPTURE) {
                                    capturedTask = task; capturedBounds = new Rect(content.displayBounds);
                                    capturedRotation = content.rotation; capturedAt = SystemClock.uptimeMillis();
                                    if (displayId == Display.DEFAULT_DISPLAY
                                            && task.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD) {
                                        foregroundTask = task; assistantNavigation = false;
                                    }
                                }
                                // Only a screenshot crosses this channel; media descriptors are not needed by AI.
                                for (var item : content.items) if (item != null) item.close();
                                content.items.clear();
                                Bundle result = new Bundle(); result.putParcelable(CONTENT, content);
                                result.putString("package", task.topActivity.getPackageName());
                                reply(request, result);
                            } finally { if (content != null) content.close(); }
                        });
                    }
                });
    }

    private boolean fresh() {
        return allowed() && !movingToBackground && capturedTask != null && capturedBounds != null && !capturedBounds.isEmpty()
                && !blocked(capturedTask.topActivity.getPackageName())
                && visibleAppsAllowed()
                && sameTask(capturedTask) && rotation() == capturedRotation
                && SystemClock.uptimeMillis() - capturedAt < 45_000;
    }

    private boolean visibleAppsAllowed() {
        for (var task : ActivityTaskManager.getInstance().getTasks(100, false, false, displayId)) {
            if (task.isVisible && task.topActivity != null && blocked(task.topActivity.getPackageName())) return false;
        }
        return true;
    }

    private void prepareInput(Message request) {
        if (inputRequest != null || touching || capturing) { error(request, "Another operation is pending"); return; }
        String action = request.getData().getString("action", "");
        // Explicit launches do not read or inject input into the source app. Still validate the
        // destination inside execute(), including its blocklist and protected-app restriction.
        if ("open_app".equals(action) || "open_url".equals(action)) { execute(request); return; }
        if (capturedTask != null && capturedTask.topActivity != null
                && rejectRestrictedApp(request, capturedTask.topActivity.getPackageName())) return;
        if (!fresh()) { error(request, "Screen changed; capture again before operating"); return; }
        if (displayId != Display.DEFAULT_DISPLAY) { execute(request); return; }
        // Keep the drawer visible. Only its input region is briefly removed while the gesture
        // is injected, with an acknowledged frame boundary and a bounded watchdog on both ends.
        inputRequest = request;
        inputState(true, request.arg1);
        main.postDelayed(inputTimeout, 2000);
    }

    private void inputState(boolean passthrough, int id) {
        if (owner == null) return;
        Message message = Message.obtain(null, INPUT_STATE); message.arg1 = id;
        Bundle data = new Bundle(); data.putString(SESSION, session); data.putBoolean("passthrough", passthrough);
        message.setData(data);
        try { owner.send(message); } catch (RemoteException ignored) { }
    }

    private void execute(Message request) {
        Bundle args = request.getData();
        String action = args.getString("action", "");
        if (touching) { error(request, "A gesture is still pending"); return; }
        if (!"open_app".equals(action) && !"open_url".equals(action) && !fresh()) {
            error(request, "Screen changed; capture a fresh screenshot before operating"); return;
        }
        switch (action) {
            case "tap": case "long_press": case "swipe": gesture(request, action); return;
            case "key": {
                String key = args.getString("key", "");
                int code = "back".equals(key) ? KeyEvent.KEYCODE_BACK : "home".equals(key) ? KeyEvent.KEYCODE_HOME
                        : "recents".equals(key) ? KeyEvent.KEYCODE_APP_SWITCH : "enter".equals(key) ? KeyEvent.KEYCODE_ENTER : -1;
                if (code < 0) { error(request, "Unsupported navigation key"); return; }
                if (displayId != Display.DEFAULT_DISPLAY && (code == KeyEvent.KEYCODE_HOME
                        || code == KeyEvent.KEYCODE_APP_SWITCH)) {
                    error(request, "Use list_apps and open_app in background mode"); return;
                }
                if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH) assistantNavigation = true;
                if (!key(code, code == KeyEvent.KEYCODE_ENTER ? capturedTask.topActivityInfo.applicationInfo.uid : -1)) {
                    error(request, "Input rejected"); return;
                }
                break;
            }
            case "type_text": {
                String text = args.getString("text", "");
                if (text.isEmpty() || text.length() > 4000) { error(request, "Invalid text length"); return; }
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                ClipData previous = clipboard.getPrimaryClip();
                String label = "arkai_" + session;
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
                boolean sent = key(KeyEvent.KEYCODE_PASTE, capturedTask.topActivityInfo.applicationInfo.uid);
                main.postDelayed(() -> {
                    try {
                        ClipData current = clipboard.getPrimaryClip();
                        if (current != null && label.contentEquals(current.getDescription().getLabel())) {
                            if (previous != null) clipboard.setPrimaryClip(previous); else clipboard.clearPrimaryClip();
                        }
                    } catch (RuntimeException ignored) { }
                }, 700);
                if (!sent) { error(request, "Text input rejected"); return; }
                break;
            }
            case "open_app": {
                String target = args.getString("package", "");
                if (rejectRestrictedApp(request, target)) return;
                int user = ActivityManager.getCurrentUser();
                Intent launch = createContextAsUser(UserHandle.of(user), 0)
                        .getPackageManager().getLaunchIntentForPackage(target);
                if (launch == null) { error(request, "App is unavailable"); return; }
                launchingPackage = target; launchingUntil = SystemClock.uptimeMillis() + 700;
                startActivityAsUser(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(), UserHandle.of(user));
                break;
            }
            case "open_url": {
                Uri uri = Uri.parse(args.getString("url", ""));
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.toString().length() > 4096) { error(request, "Only HTTPS webpages are supported"); return; }
                Intent launch = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                var resolved = getPackageManager().resolveActivity(launch, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved == null) { error(request, "Browser is unavailable"); return; }
                if (rejectRestrictedApp(request, resolved.activityInfo.packageName)) return;
                launchingPackage = resolved.activityInfo.packageName;
                launchingUntil = SystemClock.uptimeMillis() + 700;
                startActivityAsUser(launch, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                        UserHandle.of(ActivityManager.getCurrentUser()));
                break;
            }
            default: error(request, "Unsupported tool"); return;
        }
        capturedAt = 0;
        reply(request, new Bundle());
    }

    private float coordinate(Bundle args, String key, boolean horizontal) {
        int value = args.getInt(key, -1);
        if (value < 0 || value > 1000) throw new IllegalArgumentException("Invalid coordinate");
        return (horizontal ? capturedBounds.left : capturedBounds.top) + value / 1000f
                * ((horizontal ? capturedBounds.width() : capturedBounds.height()) - 1);
    }

    private void gesture(Message request, String action) {
        Bundle args = request.getData();
        float fromX = coordinate(args, "x", true), fromY = coordinate(args, "y", false);
        float toX = "swipe".equals(action) ? coordinate(args, "end_x", true) : fromX;
        float toY = "swipe".equals(action) ? coordinate(args, "end_y", false) : fromY;
        long duration = "swipe".equals(action) ? 400 : "long_press".equals(action) ? 650 : 70;
        touchX = fromX; touchY = fromY; touchDown = SystemClock.uptimeMillis(); touching = true;
        gestureRequest = request;
        if (!touch(MotionEvent.ACTION_DOWN)) { cancelGesture(); error(request, "Touch rejected"); return; }
        gesture = new Runnable() {
            @Override public void run() {
                if (!touching) return;
                if (!fresh()) { cancelGesture(); error(request, "Screen changed during gesture"); return; }
                float fraction = Math.min(1, (SystemClock.uptimeMillis() - touchDown) / (float) duration);
                touchX = fromX + (toX - fromX) * fraction; touchY = fromY + (toY - fromY) * fraction;
                if (!touch(fraction == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE)) {
                    cancelGesture(); error(request, "Touch rejected"); return;
                }
                if (fraction == 1) { touching = false; capturedAt = 0; gestureRequest = null; reply(request, new Bundle()); }
                else main.postDelayed(this, 16);
            }
        };
        main.postDelayed(gesture, 16);
    }

    private boolean touch(int action) {
        MotionEvent event = MotionEvent.obtain(touchDown, SystemClock.uptimeMillis(), action, touchX, touchY, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN); event.setDisplayId(displayId);
        // The captured display includes system surfaces such as the keyboard and notification shade.
        try { return getSystemService(InputManager.class).injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC); }
        catch (RuntimeException e) { return false; }
        finally { event.recycle(); }
    }

    private boolean key(int code, int uid) {
        long now = SystemClock.uptimeMillis();
        InputManager input = getSystemService(InputManager.class);
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        down.setDisplayId(displayId); up.setDisplayId(displayId);
        boolean sent = input.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, uid);
        boolean released = input.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, uid);
        return sent && released;
    }

    private void cancelGesture() {
        if (gesture != null) main.removeCallbacks(gesture);
        if (touching) touch(MotionEvent.ACTION_CANCEL);
        touching = false; gesture = null; gestureRequest = null;
    }

    private void finishSession() {
        main.removeCallbacks(expire);
        main.removeCallbacks(moveToBackground);
        foregroundTask = null; movingToBackground = false; autoBackground = false;
        executionActive = false; assistantNavigation = false; launchingPackage = null; launchingUntil = 0;
        main.removeCallbacks(inputTimeout); inputRequest = null;
        inputState(false, 0);
        if (gestureRequest != null) error(gestureRequest, "Task stopped");
        cancelGesture(); capturing = false; capturedTask = null; capturedAt = 0;
        if (virtual != null) virtual.finish(false);
        virtual = null; displayId = Display.DEFAULT_DISPLAY;
        if (owner != null) {
            try {
                Message ended = Message.obtain(null, END);
                Bundle data = new Bundle(); data.putString(SESSION, session); ended.setData(data);
                owner.send(ended);
            } catch (RemoteException ignored) { }
            try { owner.getBinder().unlinkToDeath(death, 0); }
            catch (java.util.NoSuchElementException ignored) { }
        }
        owner = null; ownerUid = -1; session = null;
    }

    private void error(Message request, String error) {
        Bundle result = new Bundle(); result.putString(ERROR, error); reply(request, result);
    }

    private void reply(Message request, Bundle data) {
        if (request.what == ACTION) inputState(false, request.arg1);
        if (request.replyTo == null) return;
        try {
            Message response = Message.obtain(null, request.what); response.arg1 = request.arg1; response.setData(data);
            request.replyTo.send(response);
        } catch (RemoteException ignored) { finishSession(); }
    }
}
