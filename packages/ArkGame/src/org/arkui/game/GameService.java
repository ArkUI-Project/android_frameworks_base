/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import static org.arkui.game.GameStyle.*;
import static org.arkui.game.protocol.GameProtocol.*;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.AlarmManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns UI only. Privileged operations use the current-user SystemUI's validated capability. */
public final class GameService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final List<View> guards = new ArrayList<>();
    private Messenger host;
    private IBinder.DeathRecipient death;
    private int hostUid = -1, loadGeneration;
    private boolean allowed, active, destroyed, idle, comboPlaying, loading, reloadPending;
    private boolean screenIdleAvailable, memoryAvailable;
    private String foreground = "", idlePackage = "";
    private List<GamePreferences.App> apps = List.of();
    private GamePreferences prefs;
    private WindowManager windows;
    private Context themed;
    private Configuration overlayConfiguration;
    private final android.content.ComponentCallbacks windowChanges=new android.content.ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration config) {
            int changed=overlayConfiguration.updateFrom(config);
            int geometry=android.content.pm.ActivityInfo.CONFIG_ORIENTATION|android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
                    |android.content.pm.ActivityInfo.CONFIG_DENSITY|android.content.pm.ActivityInfo.CONFIG_UI_MODE
                    |android.content.pm.ActivityInfo.CONFIG_LOCALE|android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION;
            if (destroyed || (changed & geometry)==0) return;
            hideUi(); send(STOP_COMBO,new Bundle()); state(); updateReturn();
        }
        @Override public void onLowMemory() { }
    };
    private View panel, returnChip, stopChip, messageView;
    private ComboRecorder recorder;
    private SpringAnimation opening;
    private boolean left = true;
    private long timerEnd;
    private final Runnable timerTick = this::updateTimer;
    private final AlarmManager.OnAlarmListener timerAlarm = this::updateTimer;
    private final Runnable reload = this::loadApps;
    private TextView timerText;
    private final BroadcastReceiver open = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (canUse()) { left = intent.getBooleanExtra("left", true); showPanel(); }
        }
    };
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { hideUi(); send(STOP_COMBO, new Bundle()); }
            else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(i.getAction())) closePanel();
            else updateReturn();
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())) return;
            ++loadGeneration; main.removeCallbacks(reload); main.postDelayed(reload,200);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        themed = new ContextThemeWrapper(createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),R.style.GameTheme);
        windows = themed.getSystemService(WindowManager.class); prefs = new GamePreferences(this);
        overlayConfiguration=new Configuration(themed.getResources().getConfiguration());
        themed.registerComponentCallbacks(windowChanges);
        prefs.store.registerOnSharedPreferenceChangeListener(this);
        registerReceiver(open,new IntentFilter(OPEN),PERMISSION,main,Context.RECEIVER_EXPORTED);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF); sf.addAction(Intent.ACTION_USER_PRESENT);
        sf.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS); registerReceiver(events,sf,Context.RECEIVER_NOT_EXPORTED);
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED); pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED); pf.addDataScheme("package"); registerReceiver(packages,pf,Context.RECEIVER_NOT_EXPORTED);
        try {
            // SystemUI hosts the bridge in user 0 even when this service runs for another user.
            hostUid = android.os.UserHandle.getUid(android.os.UserHandle.USER_SYSTEM,
                    android.os.UserHandle.getAppId(getPackageManager().getPackageUid("com.android.systemui",0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        loadApps();
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private boolean receive(Message m) {
        if (m.sendingUid != hostUid) return true;
        Bundle b = m.getData();
        if (m.what == ATTACH && m.replyTo != null) {
            detach(); idle=false; updateReturn();
            host = m.replyTo; death = () -> main.post(() -> { allowed=false; active=false; idle=false; hideUi(); detach(); });
            try { host.getBinder().linkToDeath(death,0); } catch (RemoteException e) { detach(); }
            state();
        } else if (m.what == ENVIRONMENT) {
            screenIdleAvailable=b.getBoolean("screenIdle"); memoryAvailable=b.getBoolean("memory");
            boolean nextAllowed = b.getBoolean("allowed"); String pkg = b.getString("package","");
            if (!pkg.equals(foreground) || allowed != nextAllowed) { hideUi(); send(STOP_COMBO,new Bundle()); }
            foreground=pkg; allowed=nextAllowed; state(); updateReturn();
        } else if (m.what == NOTIFICATION && canUse() && prefs.mode(foreground)==1) {
            showMessage(b.getString("text",""));
        } else if (m.what == RESULT) {
            if (b.containsKey("idle")) { idle=b.getBoolean("idle"); idlePackage=b.getString("package",""); updateReturn(); }
            if (b.containsKey("combo")) { comboPlaying=b.getBoolean("combo"); updateStop(); }
            if (b.containsKey("memory") && b.getInt("memory")>=0) toast(getString(R.string.memory_result,b.getInt("memory")));
            if (b.getBoolean("error")) toast(getString(R.string.unavailable));
            if (b.getBoolean("comboRejected")) toast(getString(R.string.combo_rejected));
        }
        return true;
    }
    private void detach() {
        if (host != null && death != null) host.getBinder().unlinkToDeath(death,0);
        host=null; death=null;
    }
    private void send(int what, Bundle data) {
        if (host==null) return;
        Message m=Message.obtain(null,what); m.setData(data);
        try { host.send(m); } catch (RemoteException e) { detach(); hideUi(); }
    }
    private void loadApps() {
        if (destroyed) return;
        if (loading) { reloadPending=true; return; }
        loading=true; int generation=loadGeneration;
        loader.execute(() -> {
            List<GamePreferences.App> result;
            try { result=GamePreferences.apps(this); } catch (RuntimeException e) { result=List.of(); }
            var loaded=result;
            main.post(() -> { loading=false; if (destroyed) return;
                if (generation==loadGeneration) { apps=loaded; state(); }
                if (reloadPending || generation!=loadGeneration) { reloadPending=false; loadApps(); }
            });
        });
    }
    private boolean unlocked() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked()
                && getSystemService(UserManager.class).isUserUnlocked();
    }
    private boolean canUse() { return host!=null && active && allowed && unlocked(); }
    private void state() {
        active=allowed && prefs.enabled() && apps.stream().anyMatch(a -> a.pkg().equals(foreground) && prefs.selected(a.pkg(),a.category()));
        if (!active || prefs.mode(foreground)!=1) { remove(messageView); messageView=null; }
        Bundle data=new Bundle(); data.putBoolean("active",active); data.putString("package",foreground);
        data.putInt("mode",prefs.mode(foreground));
        if (prefs.store.contains("brightness:"+foreground)) data.putInt("brightness",prefs.store.getInt("brightness:"+foreground,50));
        send(STATE,data);
        // Same foreground user's sidebar owns the only edge handle.
        sendBroadcast(new Intent(SIDEBAR_STATE).setPackage("org.arkui.sidebar")
                .putExtra("active",active).putExtra("package",foreground),PERMISSION);
        refreshGuard();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key) {
        state();
        if (!active) { hideUi(); send(STOP_COMBO,new Bundle()); }
        if (idle && (!prefs.enabled() || apps.stream().noneMatch(a -> a.pkg().equals(idlePackage) && prefs.selected(a.pkg(),a.category())))) {
            send(RETURN,new Bundle());
        } else updateReturn();
    }
    private WindowManager.LayoutParams layout(int width,int height,String title) {
        var lp=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT; lp.setFitInsetsTypes(0); lp.setTitle(title); lp.setCanPlayMoveAnimation(false); return lp;
    }
    private Rect safeBounds() {
        var metrics=windows.getCurrentWindowMetrics(); var b=new Rect(metrics.getBounds());
        Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        b.inset(insets); return b;
    }
    private void attach(View view,WindowManager.LayoutParams lp) {
        try { windows.addView(view,lp); } catch (RuntimeException e) { toast(getString(R.string.unavailable)); }
    }
    private void remove(View v) {
        if (v==null) return;
        v.animate().cancel();
        // A window is registered before its first traversal attaches the View. Removing only
        // attached Views leaks windows when menu transitions happen within that same frame.
        try { windows.removeViewImmediate(v); } catch (IllegalArgumentException ignored) { }
    }
    private void closePanel() { closePanel(true); }
    private void closePanel(boolean restoreGuard) {
        if (opening!=null) { opening.cancel(); opening=null; }
        remove(panel); panel=null; timerText=null; if (restoreGuard) refreshGuard();
    }
    private void hideUi() {
        closePanel(false); remove(messageView); messageView=null; remove(returnChip); returnChip=null;
        remove(stopChip); stopChip=null; comboPlaying=false;
        if (recorder!=null) { recorder.close(); recorder=null; }
        for (View v:guards) remove(v); guards.clear();
    }
    private String gameLabel() { for (var app:apps) if (app.pkg().equals(foreground)) return app.label(); return foreground; }
    private void showPanel() {
        if (!canUse()) return;
        boolean animate=panel==null;
        if (comboPlaying) send(STOP_COMBO,new Bundle());
        closePanel(false);
        var bounds=safeBounds(); var body=column(themed,20);
        body.setBackground(shape(surface(themed),dp(themed,32)));
        add(body,text(themed,getString(R.string.playing),12));
        add(body,text(themed,gameLabel(),28));
        var header=new LinearLayout(themed); header.setGravity(Gravity.CENTER_VERTICAL);
        var title=text(themed,getString(R.string.session),14); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(button(themed,getString(R.string.close),false,this::closePanel)); add(body,header);
        var modes=new LinearLayout(themed);
        int[] labels={R.string.normal,R.string.danmaku,R.string.block};
        for (int i=0;i<3;i++) { int mode=i; var button=button(themed,getString(labels[i]),prefs.mode(foreground)==i,() -> {
            prefs.store.edit().putInt("mode:"+foreground,mode).apply(); showPanel(); });
            var lp=new LinearLayout.LayoutParams(0,-1,1); lp.setMargins(dp(themed,2),0,dp(themed,2),0); modes.addView(button,lp); }
        add(body,modes);
        add(body,text(themed,getString(R.string.notification_summary),12));
        row(body,R.string.guard,prefs.guard(foreground),() -> { prefs.store.edit().putBoolean("guard:"+foreground,!prefs.guard(foreground)).apply(); showPanel(); },
                R.string.combo,comboPlaying,this::comboMenu);
        row(body,R.string.screenshot,false,() -> capture(SCREENSHOT),R.string.record,false,() -> capture(RECORD));
        row(body,R.string.screen_idle,idle,() -> idleMenu(true),R.string.background_idle,idle,() -> idleMenu(false));
        row(body,R.string.timer,timerEnd>0,this::timerMenu,R.string.memory,false,() -> send(MEMORY,new Bundle()));
        if (!screenIdleAvailable || !memoryAvailable) add(body,text(themed,getString(R.string.tools_unavailable),12));
        timerText=text(themed,"",16); add(body,timerText); updateTimer();
        add(body,text(themed,getString(R.string.brightness),16));
        var slider=new SeekBar(themed); slider.setMax(95); slider.setProgress(prefs.store.getInt("brightness:"+foreground,50)-5);
        slider.setContentDescription(getString(R.string.brightness)); slider.setMinHeight(dp(themed,48));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { prefs.store.edit().putInt("brightness:"+foreground,s.getProgress()+5).apply(); }
            public void onProgressChanged(SeekBar s,int progress,boolean user) { if (user) { Bundle b=new Bundle(); b.putInt("value",progress+5); send(BRIGHTNESS,b); } }
        }); add(body,slider); add(body,text(themed,getString(R.string.brightness_summary),12));
        add(body,button(themed,getString(R.string.apps_sidebar),false,() -> {
            closePanel(); sendBroadcast(new Intent(OPEN_APPS).setPackage("org.arkui.sidebar").putExtra("left",left),PERMISSION);
        }));
        add(body,button(themed,getString(R.string.settings),false,() -> { closePanel(); startActivity(new Intent(this,GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }));
        var scroll=new ScrollView(themed); scroll.setFillViewport(false); scroll.addView(body); scroll.setClipToOutline(true); scroll.setBackground(shape(surface(themed),dp(themed,32)));
        int width=Math.min(dp(themed,368),bounds.width()-dp(themed,28));
        int height=Math.min(dp(themed,720),bounds.height()-dp(themed,24));
        var lp=layout(width,height,"ArkGame:panel"); lp.x=left?bounds.left+dp(themed,12):bounds.right-width-dp(themed,12); lp.y=bounds.top+dp(themed,12);
        lp.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        scroll.setOnTouchListener((v,e) -> { if (e.getAction()==MotionEvent.ACTION_OUTSIDE) { closePanel(); return true; } return false; });
        panel=scroll; attachPanel(panel,lp); refreshGuard();
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            panel.setTranslationX((left?-1:1)*width); opening=new SpringAnimation(panel,SpringAnimation.TRANSLATION_X,0);
            opening.setSpring(new SpringForce(0).setStiffness(700).setDampingRatio(.85f)); opening.start();
        }
    }
    private void attachPanel(View view,WindowManager.LayoutParams lp) {
        lp.flags&=~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        view.setFocusableInTouchMode(true); view.setAccessibilityPaneTitle(getString(R.string.app_name));
        view.setOnKeyListener((v,key,event) -> {
            if (key!=android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction()==android.view.KeyEvent.ACTION_UP) closePanel(); return true;
        });
        view.setOnTouchListener((v,event) -> {
            if (event.getAction()==MotionEvent.ACTION_OUTSIDE) { closePanel(); return true; } return false;
        });
        attach(view,lp); view.requestFocus();
        view.post(() -> {
            if (panel==view && view.isAttachedToWindow()) {
                var dispatcher=view.findOnBackInvokedDispatcher();
                if (dispatcher!=null) dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::closePanel);
            }
        });
    }
    private void row(LinearLayout parent,int a,boolean sa,Runnable ra,int b,boolean sb,Runnable rb) {
        var row=new LinearLayout(themed);
        TextView first=button(themed,getString(a),sa,ra), second=button(themed,getString(b),sb,rb);
        if (a==R.string.screen_idle && !screenIdleAvailable) { first.setEnabled(false); first.setAlpha(.4f); }
        if (b==R.string.memory && !memoryAvailable) { second.setEnabled(false); second.setAlpha(.4f); }
        first.setCompoundDrawables(null,new ToolIcon(themed,a,sa),null,null);
        second.setCompoundDrawables(null,new ToolIcon(themed,b,sb),null,null);
        for (TextView v:new TextView[]{first,second}) {
            v.setCompoundDrawablePadding(dp(themed,6));
            var lp=new LinearLayout.LayoutParams(0,dp(themed,88),1); lp.setMargins(dp(themed,3),0,dp(themed,3),0); row.addView(v,lp);
        } add(parent,row);
    }
    private void menu(int title, int info, String[] labels, Runnable[] actions) {
        closePanel(false); var body=column(themed,20); body.setBackground(shape(surface(themed),dp(themed,28)));
        add(body,text(themed,getString(title),24)); if (info!=0) add(body,text(themed,getString(info),14));
        for (int i=0;i<labels.length;i++) { int n=i; add(body,button(themed,labels[i],i==0,() -> { closePanel(); actions[n].run(); })); }
        add(body,button(themed,getString(R.string.cancel),false,this::closePanel));
        var scroll=new ScrollView(themed); scroll.addView(body); Rect bounds=safeBounds();
        var lp=layout(Math.min(dp(themed,360),bounds.width()-dp(themed,24)),Math.min(dp(themed,500),bounds.height()-dp(themed,24)),"ArkGame:options");
        lp.x=left?bounds.left+dp(themed,12):bounds.right-lp.width-dp(themed,12); lp.y=bounds.top+dp(themed,12); panel=scroll; attachPanel(panel,lp); refreshGuard();
    }
    private void capture(int action) { String source=foreground; closePanel(); main.postDelayed(() -> { if (canUse() && foreground.equals(source)) send(action,new Bundle()); },180); }
    private void idleMenu(boolean screenOff) {
        menu(screenOff?R.string.screen_idle:R.string.background_idle,screenIdleAvailable?R.string.idle_info:R.string.background_info,new String[]{getString(R.string.start)},new Runnable[]{() -> {
            Bundle data=new Bundle(); data.putBoolean("screenOff",screenOff); send(IDLE,data);
        }});
    }
    private void comboMenu() {
        menu(R.string.combo,R.string.combo_intro,new String[]{getString(R.string.record_combo),getString(R.string.play_combo),getString(R.string.stop_combo)},new Runnable[]{
            this::recordCombo,this::playCombo,() -> send(STOP_COMBO,new Bundle())});
    }
    private String comboKey() { return "combo:"+foreground+":"+getResources().getConfiguration().orientation; }
    private void recordCombo() {
        if (!canUse()) return; send(STOP_COMBO,new Bundle());
        String key=comboKey(); recorder=new ComboRecorder(themed,windows,(actions) -> {
            prefs.store.edit().putString(key,actions.toString()).apply(); recorder=null; toast(getString(R.string.combo_saved,actions.length())); refreshGuard();
        },() -> { recorder=null; refreshGuard(); }); recorder.show(); refreshGuard();
    }
    private void playCombo() {
        try {
            var array=new org.json.JSONArray(prefs.store.getString(comboKey(),"[]"));
            if (array.length()==0) { toast(getString(R.string.combo_empty)); return; }
            int[] values=new int[Math.min(30,array.length())*6];
            for (int i=0;i<values.length;i++) values[i]=array.getJSONArray(i/6).getInt(i%6);
            Bundle data=new Bundle(); data.putIntArray("actions",values); data.putInt("repeat",prefs.store.getInt("repeat",1));
            send(COMBO,data);
        } catch (org.json.JSONException e) { toast(getString(R.string.combo_empty)); }
    }
    private void updateStop() {
        remove(stopChip); stopChip=null;
        if (!comboPlaying || !canUse()) { refreshGuard(); return; }
        stopChip=button(themed,getString(R.string.stop_combo),true,() -> send(STOP_COMBO,new Bundle()));
        var bounds=safeBounds(); var lp=layout(dp(themed,160),dp(themed,52),"ArkGame:stop"); lp.x=bounds.centerX()-lp.width/2; lp.y=bounds.top;
        attach(stopChip,lp); refreshGuard();
    }
    private void updateReturn() {
        remove(returnChip); returnChip=null;
        if (!idle || !unlocked() || host==null) return;
        returnChip=button(themed,getString(R.string.return_game),true,() -> send(RETURN,new Bundle()));
        var bounds=safeBounds(); var lp=layout(Math.min(dp(themed,250),bounds.width()),dp(themed,52),"ArkGame:return");
        lp.x=bounds.right-lp.width; lp.y=bounds.top+dp(themed,8); attach(returnChip,lp);
    }
    private void timerMenu() {
        int[] minutes={1,5,15,30}; String[] labels=new String[5]; Runnable[] actions=new Runnable[5];
        for (int i=0;i<4;i++) { int duration=minutes[i]; labels[i]=getString(R.string.minutes,duration); actions[i]=() -> {
            timerEnd=SystemClock.elapsedRealtime()+duration*60_000L;
            getSystemService(AlarmManager.class).setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,timerEnd,"ArkGame timer",timerAlarm,main);
            updateTimer(); showPanel(); }; }
        labels[4]=getString(R.string.timer_cancel); actions[4]=() -> { timerEnd=0; getSystemService(AlarmManager.class).cancel(timerAlarm); updateTimer(); }; menu(R.string.timer,0,labels,actions);
    }
    private void updateTimer() {
        main.removeCallbacks(timerTick);
        if (timerEnd==0) { if (timerText!=null) timerText.setVisibility(View.GONE); return; }
        long remaining=Math.max(0,(timerEnd-SystemClock.elapsedRealtime()+999)/1000);
        if (remaining==0) {
            timerEnd=0; getSystemService(AlarmManager.class).cancel(timerAlarm); toast(getString(R.string.timer_done));
            android.util.Log.i("ArkGame","Game timer finished");
            getSystemService(Vibrator.class).vibrate(VibrationEffect.createOneShot(400,VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            if (timerText!=null) { timerText.setVisibility(View.VISIBLE); timerText.setText(String.format(java.util.Locale.getDefault(),"%02d:%02d",remaining/60,remaining%60)); }
            main.postDelayed(timerTick,timerText!=null?1000:remaining*1000);
        }
    }
    private void refreshGuard() {
        for (View v:guards) remove(v); guards.clear();
        if (!canUse() || !prefs.guard(foreground) || panel!=null || recorder!=null || comboPlaying) return;
        Rect b=safeBounds(); int edge=dp(themed,10), handle=dp(themed,20);
        int[][] rects={{b.left,b.top,b.width(),edge},{b.left,b.bottom-edge,b.width(),edge},
                {b.left+handle,b.top+edge,edge,b.height()-edge*2},{b.right-handle-edge,b.top+edge,edge,b.height()-edge*2}};
        for (int[] r:rects) {
            View v=new View(themed); v.setBackgroundColor((accent(themed)&0xffffff)|0x25000000);
            v.setOnTouchListener((view,event) -> true); v.setContentDescription(getString(R.string.guard));
            var lp=layout(r[2],r[3],"ArkGame:guard"); lp.x=r[0]; lp.y=r[1]; attach(v,lp); guards.add(v);
        }
    }
    private void showMessage(String text) {
        if (text.isEmpty() || messageView!=null || !canUse() || recorder!=null) return;
        var message=text(themed,text,15); message.setMaxLines(1); message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        message.setGravity(Gravity.CENTER_VERTICAL); message.setPadding(dp(themed,16),0,dp(themed,16),0);
        message.setBackground(shape(accent(themed),dp(themed,24)));
        message.setTextColor(themed.getColor(com.android.internal.R.color.materialColorOnPrimaryContainer));
        Rect bounds=safeBounds(); var lp=layout(Math.min(dp(themed,360),bounds.width()-dp(themed,24)),dp(themed,48),"ArkGame:message");
        lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; lp.x=bounds.centerX()-lp.width/2; lp.y=bounds.top+dp(themed,70);
        var container=new FrameLayout(themed); container.setClipChildren(true);
        var child=new FrameLayout.LayoutParams(lp.width,lp.height); container.addView(message,child);
        lp.width=bounds.width(); lp.x=bounds.left;
        messageView=container; attach(container,lp);
        if (ValueAnimator.areAnimatorsEnabled()) {
            message.setTranslationX(bounds.width()); message.animate().translationX(-child.width).setDuration(6500).setInterpolator(new android.view.animation.LinearInterpolator()).withEndAction(() -> { remove(container); if (messageView==container) messageView=null; }).start();
        } else main.postDelayed(() -> { remove(container); if (messageView==container) messageView=null; },5000);
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_SHORT).show(); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); hideUi(); send(STOP_COMBO,new Bundle()); state(); updateReturn(); }
    @Override public boolean onUnbind(Intent i) { allowed=false; active=false; hideUi(); detach(); return false; }
    @Override public void onDestroy() {
        destroyed=true; allowed=false; active=false; hideUi(); detach();
        themed.unregisterComponentCallbacks(windowChanges);
        unregisterReceiver(open); unregisterReceiver(events); unregisterReceiver(packages);
        prefs.store.unregisterOnSharedPreferenceChangeListener(this); loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
        getSystemService(AlarmManager.class).cancel(timerAlarm);
    }
}
