/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.arkui;

import static org.arkui.game.protocol.GameProtocol.*;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
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
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.WindowManager;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.screenrecord.ScreenRecordUxController;
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor;
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType;
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters;
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor;
import android.media.projection.StopReason;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import javax.inject.Inject;

/** Fixed capabilities for one signature-verified APK, bound as the foreground user.
 * No arbitrary intent, task, display, UID or key injection is accepted from the app. */
@SysUISingleton
public final class GameBridge {
    private final Context context;
    private final UserTracker users;
    private final Handler main;
    private final Executor background;
    private final ScreenRecordUxController recording;
    private final ScreenCaptureUiInteractor captureUi;
    private final NotificationListener notifications;
    private final VisualInterruptionDecisionProvider interruptions;
    private final ScreenshotHelper screenshot;
    private final GameComboPlayer combo;
    private final GameIdleSession idle;
    private Messenger service;
    private ServiceConnection connection;
    private boolean bound, allowed, active, initialized;
    private String foreground="", activePackage="";
    private int generation, mode;
    private long lastAction, lastMemory;
    private boolean brightnessChanged;
    private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();
    private final Runnable retry=this::bind;

    @Inject public GameBridge(Context c,UserTracker users,@Main Handler main,@Background Executor executor,
            ScreenRecordUxController recording,NotificationListener notifications,VisualInterruptionDecisionProvider interruptions,
            ScreenCaptureUiInteractor captureUi) {
        context=c; this.users=users; this.main=main; background=executor; this.recording=recording;
        this.notifications=notifications; this.interruptions=interruptions; screenshot=new ScreenshotHelper(c);
        this.captureUi=captureUi;
        combo=new GameComboPlayer(c,main,this::validTask,playing -> { Bundle b=new Bundle(); b.putBoolean("combo",playing); send(RESULT,b); });
        idle=new GameIdleSession(c,main,() -> {
            Bundle b=new Bundle(); b.putBoolean("idle",idleRunning()); b.putString("package",activePackage); send(RESULT,b);
        });
    }
    private boolean idleRunning() { return idle!=null && idle.isRunning(); }
    public void start() {
        users.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanged(int user,Context c) { unbind(); foreground=""; allowed=false; bind(); }
        },main::post);
        interruptions.addLegacySuppressor(new NotificationInterruptSuppressor() {
            @Override public boolean suppressAwakeInterruptions(NotificationEntry entry) {
                return active && mode!=0 && usable() && sameUser(entry.getSbn()) && isMessage(entry.getSbn());
            }
        });
        notifications.addNotificationHandler(new NotificationListener.NotificationHandler() {
            @Override public void onNotificationPosted(StatusBarNotification sbn,RankingMap rankings) {
                if (!initialized || !active || mode!=1 || !usable() || !sameUser(sbn) || !isMessage(sbn)) return;
                Notification n=sbn.getNotification();
                if (n.visibility==Notification.VISIBILITY_SECRET || n.isGroupSummary() || sbn.isOngoing()) return;
                Long previous=seen.put(sbn.getKey(),sbn.getPostTime());
                if (previous!=null && previous==sbn.getPostTime()) return;
                if (seen.size()>100) seen.remove(seen.keySet().iterator().next());
                CharSequence title=n.extras.getCharSequence(Notification.EXTRA_TITLE,"");
                CharSequence text=n.extras.getCharSequence(Notification.EXTRA_TEXT,"");
                String value=(title+"  "+text).replace('\n',' ').trim();
                if (value.length()>140) value=value.substring(0,140);
                Bundle data=new Bundle(); data.putString("text",value); send(NOTIFICATION,data);
            }
            @Override public void onNotificationRemoved(StatusBarNotification s,RankingMap r) { seen.remove(s.getKey()); }
            @Override public void onNotificationRemoved(StatusBarNotification s,RankingMap r,int reason) { onNotificationRemoved(s,r); }
            @Override public void onNotificationRankingUpdate(RankingMap r) { }
            @Override public void onNotificationsInitialized() { initialized=true; applyHints(); }
        });
        bind();
    }
    private boolean sameUser(StatusBarNotification s) { return s.getUserId()==users.getUserId(); }
    private boolean isMessage(StatusBarNotification s) {
        Notification n=s.getNotification();
        return !Notification.CATEGORY_CALL.equals(n.category) && !Notification.CATEGORY_ALARM.equals(n.category)
                && !s.getPackageName().equals(context.getPackageName()) && !s.getPackageName().equals(PACKAGE)
                && !s.getPackageName().equals("android");
    }
    private boolean usable() {
        return allowed && context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && users.getUserContext().getSystemService(UserManager.class).isUserUnlocked()
                && !users.getUserContext().getSystemService(UserManager.class).isManagedProfile()
                && context.getSystemService(ActivityManager.class).getLockTaskModeState()==ActivityManager.LOCK_TASK_MODE_NONE;
    }
    public void environment(boolean value,String pkg) {
        idle.reconcile();
        if (allowed==value && foreground.equals(pkg) && service!=null) return;
        boolean changed=!foreground.equals(pkg) || allowed!=value;
        allowed=value; foreground=pkg;
        if (changed) clearActive();
        if (!bound) bind();
        sendEnvironment();
    }
    private void sendEnvironment() {
        Bundle data=new Bundle(); data.putBoolean("allowed",usable()); data.putString("package",foreground);
        data.putBoolean("screenIdle",context.checkSelfPermission(android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY)==PackageManager.PERMISSION_GRANTED);
        data.putBoolean("memory",context.checkSelfPermission(android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES)==PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)==PackageManager.PERMISSION_GRANTED);
        send(ENVIRONMENT,data);
    }
    private void bind() {
        main.removeCallbacks(retry);
        if (bound || !users.getUserContext().getSystemService(UserManager.class).isUserUnlocked()) return;
        final int user=users.getUserId(), token=++generation, uid;
        try {
            uid=context.getPackageManager().getPackageUidAsUser(PACKAGE,user);
            if (context.getPackageManager().checkSignatures(context.getPackageName(),PACKAGE)!=PackageManager.SIGNATURE_MATCH) return;
        } catch (PackageManager.NameNotFoundException e) { return; }
        connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                if (token!=generation || user!=users.getUserId()) return;
                service=new Messenger(binder);
                Message message=Message.obtain(null,ATTACH);
                message.replyTo=new Messenger(new Handler(main.getLooper(),m -> receive(m,uid,token)));
                try { service.send(message); } catch (RemoteException e) { reconnect(); return; }
                sendEnvironment();
            }
            @Override public void onServiceDisconnected(ComponentName name) { if (token==generation) { service=null; clearActive(); idle.finish(); sidebarOff(); } }
            @Override public void onBindingDied(ComponentName name) { if (token==generation) reconnect(); }
            @Override public void onNullBinding(ComponentName name) { if (token==generation) unbind(); }
        };
        try { bound=context.bindServiceAsUser(new Intent().setComponent(SERVICE),connection,Context.BIND_AUTO_CREATE,main,UserHandle.of(user)); }
        catch (SecurityException ignored) { }
    }
    private void reconnect() { unbind(); main.postDelayed(retry,1500); }
    private void sidebarOff() {
        context.sendBroadcastAsUser(new Intent(SIDEBAR_STATE).setPackage("org.arkui.sidebar")
                .putExtra("active",false),users.getUserHandle(),PERMISSION);
    }
    private void unbind() {
        ++generation; main.removeCallbacks(retry); clearActive(); idle.finish(); sidebarOff();
        service=null; if (bound) context.unbindService(connection); bound=false; seen.clear();
    }
    private void clearActive() {
        active=false; mode=0; combo.stop(); restoreBrightness(); applyHints();
    }
    private void applyHints() {
        if (initialized) notifications.requestListenerHints(active && mode!=0 && usable()
                ? NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS:0);
    }
    private boolean receive(Message message,int uid,int token) {
        if (message.sendingUid!=uid || token!=generation || service==null) return true;
        Bundle b=message.getData();
        if (message.what==STATE) {
            boolean value=b.getBoolean("active") && foreground.equals(b.getString("package")) && usable();
            if (!value) clearActive();
            else {
                boolean entering=!active;
                active=true; activePackage=foreground; mode=Math.max(0,Math.min(2,b.getInt("mode"))); applyHints();
                if (entering && b.containsKey("brightness")) {
                    context.getSystemService(DisplayManager.class).setTemporaryBrightness(Display.DEFAULT_DISPLAY,
                            Math.max(.05f,Math.min(1,b.getInt("brightness")/100f))); brightnessChanged=true;
                }
            }
            return true;
        }
        if (message.what==STOP_COMBO) { combo.stop(); return true; }
        if (message.what==RETURN) {
            if (!context.getSystemService(KeyguardManager.class).isKeyguardLocked()) idle.finish();
            return true;
        }
        ActivityManager.RunningTaskInfo task=validTask();
        if (task==null || SystemClock.uptimeMillis()-lastAction<150) return true;
        if (message.what!=BRIGHTNESS) lastAction=SystemClock.uptimeMillis();
        try {
            switch (message.what) {
                case SCREENSHOT -> screenshot.takeScreenshot(WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS,main,null);
                case RECORD -> {
                    if (ScreenCaptureRecordFeaturesInteractor.INSTANCE.getShouldShowNewToolbar()) {
                        captureUi.show(new ScreenCaptureUiParameters(ScreenCaptureType.RECORD,false,null,null,users.getUserHandle(),0));
                    } else if (recording.isRecording()) recording.stopRecording(StopReason.STOP_QS_TILE);
                    else if (recording.isStarting()) recording.cancelCountdown();
                    else recording.createScreenRecordDialog(null).show();
                }
                case BRIGHTNESS -> {
                    context.getSystemService(DisplayManager.class).setTemporaryBrightness(Display.DEFAULT_DISPLAY,Math.max(.05f,Math.min(1,b.getInt("value",50)/100f)));
                    brightnessChanged=true;
                }
                case COMBO -> {
                    if (!combo.play(task,b.getIntArray("actions"),b.getInt("repeat",1))) {
                        Bundle result=new Bundle(); result.putBoolean("comboRejected",true); send(RESULT,result);
                    }
                }
                case IDLE -> { combo.stop(); idle.start(task,b.getBoolean("screenOff")); }
                case MEMORY -> {
                    if (SystemClock.uptimeMillis()-lastMemory<30_000) return true;
                    lastMemory=SystemClock.uptimeMillis();
                    String game=activePackage; int user=users.getUserId();
                    background.execute(() -> {
                        int count=releaseCachedApps(user,game);
                        main.post(() -> { if (token!=generation) return;
                            Bundle result=new Bundle(); result.putInt("memory",count); result.putBoolean("error",count<0); send(RESULT,result); });
                    });
                }
            }
        } catch (RuntimeException e) { android.util.Log.w("ArkGame","Game action failed",e); Bundle error=new Bundle(); error.putBoolean("error",true); send(RESULT,error); }
        return true;
    }
    private int releaseCachedApps(int user,String game) {
        try {
            var manager=users.getUserContext().getSystemService(ActivityManager.class);
            var pm=context.getPackageManager(); java.util.Set<String> candidates=new java.util.HashSet<>();
            for (var process:manager.getRunningAppProcesses()) {
                if (UserHandle.getUserId(process.uid)!=user || process.importance<ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                        || process.pkgList==null || process.pkgList.length==0) continue;
                boolean safe=true;
                for (String pkg:process.pkgList) {
                    if (pkg.equals(game) || pkg.equals(PACKAGE) || pkg.equals(context.getPackageName())
                            || (pm.getApplicationInfoAsUser(pkg,0,user).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0) { safe=false; break; }
                }
                if (safe) java.util.Collections.addAll(candidates,process.pkgList);
            }
            // AMS rechecks current process importance; this never force-stops packages or removes tasks.
            for (String pkg:candidates) {
                if (users.getUserId()!=user) break;
                ActivityManager.getService().killBackgroundProcesses(pkg,user);
            }
            return candidates.size();
        } catch (RemoteException|RuntimeException|PackageManager.NameNotFoundException e) {
            android.util.Log.w("ArkGame","Cannot reclaim cached apps",e); return -1;
        }
    }
    private ActivityManager.RunningTaskInfo validTask() {
        if (!active || !usable()) return null;
        try {
            for (var task:ActivityTaskManager.getInstance().getTasks(1,false,false,Display.DEFAULT_DISPLAY)) {
                if (task.userId==users.getUserId() && task.displayId==Display.DEFAULT_DISPLAY && task.topActivity!=null
                        && task.topActivity.getPackageName().equals(activePackage)
                        && task.getActivityType()==android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD) return task;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    private void restoreBrightness() {
        if (!brightnessChanged) return;
        context.getSystemService(DisplayManager.class).setTemporaryBrightness(Display.DEFAULT_DISPLAY,Float.NaN); brightnessChanged=false;
    }
    private void send(int what,Bundle data) {
        if (service==null) return;
        Message m=Message.obtain(null,what); m.setData(data);
        try { service.send(m); } catch (RemoteException e) { service=null; clearActive(); idle.finish(); sidebarOff(); }
    }
}
