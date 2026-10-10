/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package android.inputmethodservice;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.inputmethod.ImeVoiceProtocol;

/** Owns one explicitly requested dictation session, bound to one editor connection. */
final class ImeVoiceInputController {
    private final InputMethodService mService;
    private final ImeVoiceInputView mView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ServiceConnection mConnection;
    private Messenger mRemote;
    private Messenger mReply;
    private InputConnection mEditor;
    private int mSession;
    private boolean mActive;
    private boolean mProcessing;

    ImeVoiceInputController(InputMethodService service) {
        mService = service;
        mView = new ImeVoiceInputView(service, this::toggle, this::cancel,
                () -> service.requestHideSelf(0), () -> service.getSystemService(
                        InputMethodManager.class).showInputMethodPicker());
        updateEditor();
    }

    static boolean isAvailable(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationInfo(ImeVoiceProtocol.PACKAGE, 0).enabled
                    && pm.checkSignatures("android", ImeVoiceProtocol.PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    View getView() { return mView; }

    void setSwitcherVisible(boolean visible) { mView.setSwitcherVisible(visible); }

    private boolean canDictate() {
        EditorInfo editor = mService.getCurrentInputEditorInfo();
        if (editor == null || mService.getCurrentInputConnection() == null
                || mService.getSystemService(KeyguardManager.class).isKeyguardLocked()
                || Settings.Secure.getInt(mService.getContentResolver(),
                        "arkui_assistant_enabled", 0) == 0) return false;
        int type = editor.inputType & InputType.TYPE_MASK_CLASS;
        int variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
        return type == InputType.TYPE_CLASS_TEXT
                && variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    void updateEditor() {
        cancel();
        mView.setVoiceEnabled(canDictate());
    }

    private void toggle() {
        if (mActive) {
            if (!mProcessing && mRemote != null) {
                send(ImeVoiceProtocol.FINISH);
                mProcessing = true;
                mView.showState(true, true, mService.getString(R.string.ime_voice_processing), 0f);
            }
            return;
        }
        if (!canDictate() || !mView.isShown() || !isAvailable(mService)) return;
        mActive = true;
        mEditor = mService.getCurrentInputConnection();
        int session = ++mSession;
        mReply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (session != mSession || !mActive) return true;
            if (!canDictate() || !mView.isShown()
                    || mEditor != mService.getCurrentInputConnection()) {
                cancel();
                return true;
            }
            String text = message.getData().getString(ImeVoiceProtocol.TEXT, "");
            switch (message.what) {
                case ImeVoiceProtocol.ACTIVATE:
                    PendingIntent activation = message.getData().getParcelable(
                            ImeVoiceProtocol.ACTIVATION, PendingIntent.class);
                    if (activation == null || !ImeVoiceProtocol.PACKAGE.equals(
                            activation.getCreatorPackage())) {
                        fail("");
                        break;
                    }
                    try {
                        activation.send(mService, 0, null, null, null, null,
                                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle());
                    } catch (PendingIntent.CanceledException e) {
                        fail("");
                    }
                    break;
                case ImeVoiceProtocol.STATE:
                    mProcessing = message.getData().getBoolean(ImeVoiceProtocol.TRANSCRIBING);
                    mView.showState(true, mProcessing, text.isEmpty() ? mService.getString(
                            mProcessing ? R.string.ime_voice_processing : R.string.ime_voice_listening) : text,
                            message.getData().getFloat(ImeVoiceProtocol.LEVEL));
                    break;
                case ImeVoiceProtocol.RESULT:
                    InputConnection target = mEditor;
                    // Release the session before commitText triggers an editor selection callback.
                    cancel();
                    if (!text.isBlank() && text.length() <= 6000) {
                        target.beginBatchEdit();
                        target.finishComposingText();
                        target.commitText(text, 1);
                        target.endBatchEdit();
                    }
                    break;
                case ImeVoiceProtocol.ERROR:
                    fail(text);
                    break;
                default:
                    break;
            }
            return true;
        }));
        mView.showState(true, false, mService.getString(R.string.ime_voice_connecting), 0f);
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (session != mSession || !mActive) return;
                mRemote = new Messenger(binder);
                send(ImeVoiceProtocol.START);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (session == mSession) fail("");
            }

            @Override
            public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }

            @Override
            public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
        };
        try {
            boolean bound = mService.bindService(new Intent().setComponent(new ComponentName(
                    ImeVoiceProtocol.PACKAGE, ImeVoiceProtocol.SERVICE)), mConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_INCLUDE_CAPABILITIES);
            if (!bound) { mConnection = null; fail(""); return; }
        } catch (SecurityException e) {
            mConnection = null;
            fail("");
            return;
        }
        mHandler.postDelayed(() -> { if (session == mSession && mRemote == null) fail(""); }, 8000);
        mHandler.postDelayed(() -> { if (session == mSession) fail(""); }, 95000);
    }

    private void send(int command) {
        if (mRemote == null) return;
        Message message = Message.obtain(null, command);
        message.replyTo = mReply;
        message.arg1 = mSession;
        try {
            mRemote.send(message);
        } catch (RemoteException e) {
            if (command != ImeVoiceProtocol.CANCEL) fail("");
        }
    }

    private void fail(String message) {
        cancel();
        Toast.makeText(mService, message.isEmpty() ? mService.getString(R.string.ime_voice_unavailable)
                : message, Toast.LENGTH_LONG).show();
    }

    void cancel() {
        if (!mActive && mConnection == null) return;
        send(ImeVoiceProtocol.CANCEL);
        ++mSession;
        mActive = false;
        mProcessing = false;
        mHandler.removeCallbacksAndMessages(null);
        if (mConnection != null) {
            mService.unbindService(mConnection);
            mConnection = null;
        }
        mRemote = null;
        mReply = null;
        mEditor = null;
        mView.showState(false, false, "", 0f);
    }
}
