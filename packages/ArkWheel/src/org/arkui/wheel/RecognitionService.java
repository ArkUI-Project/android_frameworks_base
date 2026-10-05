/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import org.arkui.wheel.protocol.WheelProtocol;

/** Transient recognition entry point, independent of the wheel's invocation preferences. */
public final class RecognitionService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::message));
    private Messenger host;
    private IBinder.DeathRecipient hostDeath;
    private RecognitionPanel panel;
    private Context themed;
    private WindowManager windows;
    private int hostUid = -1;
    private final BroadcastReceiver state = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { close(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        themed = new ContextThemeWrapper(createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.WheelTheme);
        windows = themed.getSystemService(WindowManager.class);
        try { hostUid = getPackageManager().getPackageUidAsUser("com.android.systemui", UserHandle.USER_SYSTEM); }
        catch (PackageManager.NameNotFoundException ignored) { }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(state, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { dispose(); detach(); return false; }

    private boolean message(Message message) {
        if (message.sendingUid != hostUid || message.what != WheelProtocol.CAPTURE_RESULT) return true;
        Bitmap bitmap = message.getData().getParcelable(WheelProtocol.BITMAP, Bitmap.class);
        dispose(); detach(); host = message.replyTo;
        if (host != null) {
            hostDeath = () -> main.post(() -> { dispose(); detach(); });
            try { host.getBinder().linkToDeath(hostDeath, 0); }
            catch (RemoteException e) { if (bitmap != null) bitmap.recycle(); detach(); return true; }
        }
        if (host == null || !getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(UserManager.class).isUserUnlocked()
                || getSystemService(UserManager.class).isManagedProfile()) {
            if (bitmap != null) bitmap.recycle(); close(); return true;
        }
        if (bitmap == null) {
            Toast.makeText(this, R.string.recognition_capture_failed, Toast.LENGTH_SHORT).show();
            close(); return true;
        }
        try {
            panel = new RecognitionPanel(themed, bitmap, this::close);
            panel.show(windows);
        } catch (RuntimeException e) {
            android.util.Log.w("ArkRecognition", "Cannot show sidebar recognition", e);
            if (panel == null) bitmap.recycle();
            close();
        }
        return true;
    }

    private void close() {
        dispose();
        if (host != null) try { host.send(Message.obtain(null, WheelProtocol.RECOGNITION_CLOSED)); }
        catch (RemoteException ignored) { }
        detach();
    }
    private void dispose() {
        if (panel != null) { panel.dispose(); panel = null; }
    }
    private void detach() {
        if (host != null && hostDeath != null) host.getBinder().unlinkToDeath(hostDeath, 0);
        host = null; hostDeath = null;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); close();
    }
    @Override public void onDestroy() {
        dispose(); detach(); unregisterReceiver(state);
        main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
