/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

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
import android.view.ScreenContent;
import android.view.WindowManager;
import android.widget.Toast;
import org.arkui.recognition.protocol.RecognitionProtocol;

/** Trusted capture handoff; the session ends on host death, lock, user switch or unbind. */
public final class RecognitionService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean accepting, destroyed;
    private int bindingGeneration;
    private Messenger host;
    private IBinder.DeathRecipient hostDeath;
    private RecognitionPanel panel;
    private int hostUid = -1;
    private final BroadcastReceiver state = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { close(); }
    };
    @Override public void onCreate() {
        super.onCreate();
        try { hostUid = getPackageManager().getPackageUidAsUser("com.android.systemui", UserHandle.USER_SYSTEM); }
        catch (PackageManager.NameNotFoundException ignored) { }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS); filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerReceiver(state, filter, Context.RECEIVER_NOT_EXPORTED);
    }
    @Override public IBinder onBind(Intent intent) {
        accepting = !destroyed;
        final int token = ++bindingGeneration;
        return new Messenger(new Handler(Looper.getMainLooper(), message -> message(message, token))).getBinder();
    }
    @Override public boolean onUnbind(Intent intent) {
        accepting = false; ++bindingGeneration; dispose(); detach(); return false;
    }
    private boolean message(Message message, int token) {
        if (hostUid < 0 || message.sendingUid != hostUid || message.what != RecognitionProtocol.CAPTURE_RESULT) return true;
        ScreenContent content = message.getData().getParcelable(RecognitionProtocol.CONTENT, ScreenContent.class);
        Bitmap bitmap = content == null ? message.getData().getParcelable(RecognitionProtocol.BITMAP, Bitmap.class) : content.screenshot;
        if (!accepting || destroyed || token != bindingGeneration) {
            release(content, bitmap); return true;
        }
        dispose(); detach(); host = message.replyTo;
        if (host != null) {
            hostDeath = () -> main.post(() -> { dispose(); detach(); });
            try { host.getBinder().linkToDeath(hostDeath, 0); }
            catch (RemoteException e) { release(content, bitmap); detach(); return true; }
        }
        if (host == null || !getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(UserManager.class).isUserUnlocked()
                || getSystemService(UserManager.class).isManagedProfile()) {
            release(content, bitmap); close(); return true;
        }
        if (content != null && (content.status == ScreenContent.STATUS_SECURE || content.status == ScreenContent.STATUS_CHANGED)) {
            release(content, bitmap); Toast.makeText(this, R.string.recognition_capture_failed, Toast.LENGTH_SHORT).show(); close(); return true;
        }
        if (bitmap == null || bitmap.isRecycled()) {
            release(content, bitmap); Toast.makeText(this, R.string.recognition_capture_failed, Toast.LENGTH_SHORT).show(); close(); return true;
        }
        Display display = getSystemService(DisplayManager.class).getDisplay(content == null ? Display.DEFAULT_DISPLAY : content.displayId);
        if (display == null) { release(content, bitmap); close(); return true; }
        try {
            Context themed = new ContextThemeWrapper(createWindowContext(display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.RecognitionTheme);
            panel = new RecognitionPanel(themed, bitmap, content, this::close);
            panel.show(themed.getSystemService(WindowManager.class));
        } catch (RuntimeException e) {
            android.util.Log.w("ArkRecognition", "Cannot show recognition", e);
            if (panel == null) release(content, bitmap); close();
        }
        return true;
    }
    private static void release(ScreenContent content, Bitmap bitmap) {
        if (content != null) content.close(); else if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
    private void close() {
        dispose();
        if (host != null) try { host.send(Message.obtain(null, RecognitionProtocol.CLOSED)); }
        catch (RemoteException ignored) { }
        detach();
    }
    private void dispose() { if (panel != null) { panel.dispose(); panel = null; } }
    private void detach() {
        if (host != null && hostDeath != null) host.getBinder().unlinkToDeath(hostDeath, 0);
        host = null; hostDeath = null;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); close();
    }
    @Override public void onDestroy() {
        destroyed = true; accepting = false; ++bindingGeneration;
        dispose(); detach(); unregisterReceiver(state); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
