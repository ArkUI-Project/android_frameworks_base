/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

final class AppErrorDialog extends BaseErrorDialog implements View.OnClickListener {

    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;
    private final Data mData;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;
    private final boolean mIsRestartable;
    private final CharSequence mDialogTitle;
    private final String mCrashDetails;
    private AppErrorDialogUi mUi;
    private boolean mShowingCrashDetails;
    private final BroadcastReceiver mPrivacyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mShowingCrashDetails) showOverview();
        }
    };

    static int CANT_SHOW = -1;
    static int BACKGROUND_USER = -2;
    static int ALREADY_SHOWING = -3;

    // Event 'what' codes
    static final int FORCE_QUIT = 1;
    static final int FORCE_QUIT_AND_REPORT = 2;
    static final int RESTART = 3;
    static final int MUTE = 5;
    static final int TIMEOUT = 6;
    static final int CANCEL = 7;
    static final int APP_INFO = 8;

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;

    public AppErrorDialog(Context context, ActivityManagerService service, Data data) {
        super(context);
        Resources res = context.getResources();

        mService = service;
        mProcLock = service.mProcLock;
        mData = data;
        mProc = data.proc;
        mResult = data.result;
        mIsRestartable = (data.taskId != INVALID_TASK_ID || data.isRestartableForService)
                && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SHOW_RESTART_IN_CRASH_DIALOG, 0) != 0;
        BidiFormatter bidi = BidiFormatter.getInstance();

        CharSequence name;
        if (mProc.getPkgList().size() == 1
                && (name = context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            mDialogTitle = res.getString(
                    data.repeating ? com.android.internal.R.string.aerr_application_repeated
                            : com.android.internal.R.string.aerr_application,
                    bidi.unicodeWrap(name.toString()),
                    bidi.unicodeWrap(mProc.info.processName));
        } else {
            name = mProc.processName;
            mDialogTitle = res.getString(
                    data.repeating ? com.android.internal.R.string.aerr_process_repeated
                            : com.android.internal.R.string.aerr_process,
                    bidi.unicodeWrap(name.toString()));
        }
        setTitle(mDialogTitle);
        // Strings captured with this dialog's result, never a later process report or logcat.
        final String trace = !TextUtils.isEmpty(data.stackTrace) ? data.stackTrace
                : (TextUtils.isEmpty(data.crashSummary) ? "" : data.crashSummary + "\n\n")
                        + res.getString(com.android.internal.R.string.arkui_aerr_no_stack);
        mCrashDetails = res.getString(com.android.internal.R.string.arkui_aerr_process,
                mProc.processName) + "\n\n" + trace;

        setCancelable(true);
        setCancelMessage(mHandler.obtainMessage(CANCEL));

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + mProc.info.processName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (mProc.isPersistent()) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(TIMEOUT),
                DISMISS_TIMEOUT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUi = new AppErrorDialogUi(this);
        showOverview();
    }

    private void showOverview() {
        mShowingCrashDetails = false;
        final Context context = getContext();
        mUi.showHeader(mDialogTitle, null);
        final boolean hasReceiver = mProc.mErrorState.getErrorReportReceiver() != null;
        mUi.addAction(com.android.internal.R.id.aerr_app_info,
                com.android.internal.R.string.app_info,
                com.android.internal.R.drawable.ic_info_outline_24, AppErrorDialogUi.TEXT, this);
        if (hasReceiver) {
            mUi.addAction(com.android.internal.R.id.aerr_report,
                    com.android.internal.R.string.aerr_report,
                    com.android.internal.R.drawable.ic_lock_bugreport, AppErrorDialogUi.TEXT, this);
        }
        boolean showMute = !Build.IS_USER && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SHOW_MUTE_IN_CRASH_DIALOG, 0) != 0;
        if (showMute) {
            mUi.addAction(com.android.internal.R.id.aerr_mute,
                    com.android.internal.R.string.aerr_mute,
                    com.android.internal.R.drawable.ic_eject_24dp, AppErrorDialogUi.TEXT, this);
        }
        mUi.addAction(com.android.internal.R.id.aerr_close,
                com.android.internal.R.string.aerr_close_app, com.android.internal.R.drawable.ic_close,
                mIsRestartable ? AppErrorDialogUi.TONAL : AppErrorDialogUi.PRIMARY, this);
        if (canViewCrashDetails()) {
            mUi.addAction(com.android.internal.R.id.arkui_aerr_details,
                    com.android.internal.R.string.arkui_aerr_view_stack,
                    com.android.internal.R.drawable.ic_doc_codes, AppErrorDialogUi.PRIMARY,
                    v -> showCrashDetails());
        }
        if (mIsRestartable) {
            mUi.addAction(com.android.internal.R.id.aerr_restart,
                    com.android.internal.R.string.aerr_restart,
                    com.android.internal.R.drawable.ic_restart, AppErrorDialogUi.PRIMARY, this);
        }
    }

    private boolean canViewCrashDetails() {
        final KeyguardManager keyguard = getContext().getSystemService(KeyguardManager.class);
        return mService.mUserController.isCurrentProfile(mProc.userId)
                && keyguard != null && !keyguard.isDeviceLocked(mProc.userId);
    }

    private void showCrashDetails() {
        if (!canViewCrashDetails()) return;
        mShowingCrashDetails = true;
        final Context context = getContext();
        mUi.showHeader(context.getText(com.android.internal.R.string.arkui_aerr_stack_title),
                BidiFormatter.getInstance().unicodeWrap(mProc.info.packageName));
        final Context userContext = context.createContextAsUser(UserHandle.of(mProc.userId), 0);
        mUi.addStackTrace(mCrashDetails, userContext);
        mUi.addAction(com.android.internal.R.id.arkui_aerr_copy,
                com.android.internal.R.string.copy, com.android.internal.R.drawable.ic_file_copy,
                AppErrorDialogUi.TONAL, v -> {
                    if (!canViewCrashDetails()) {
                        showOverview();
                        return;
                    }
                    final ClipData clip = ClipData.newPlainText(
                            context.getText(com.android.internal.R.string.arkui_aerr_stack_title),
                            mCrashDetails);
                    final PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                    clip.getDescription().setExtras(extras);
                    userContext.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                    ((Button) v).setText(com.android.internal.R.string.arkui_aerr_copied);
                });
        mUi.addAction(com.android.internal.R.id.arkui_aerr_back,
                com.android.internal.R.string.arkui_aerr_back,
                com.android.internal.R.drawable.ic_ime_nav_back, AppErrorDialogUi.PRIMARY,
                v -> showOverview());
    }

    @Override
    public void onStart() {
        super.onStart();
        mUi.start();
        final IntentFilter privacyEvents = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        privacyEvents.addAction(Intent.ACTION_USER_SWITCHED);
        privacyEvents.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        getContext().registerReceiver(mPrivacyReceiver, privacyEvents, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        getContext().unregisterReceiver(mPrivacyReceiver);
        mUi.stop();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (mShowingCrashDetails) {
            showOverview();
        } else {
            super.onBackPressed();
        }
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            setResult(msg.what);
            dismiss();
        }
    };

    @Override
    public void dismiss() {
        if (!mResult.mHasResult) {
            // We are dismissing and the result has not been set...go ahead and set.
            setResult(FORCE_QUIT);
        }
        super.dismiss();
    }

    void remove() {
        // Only dismiss the dialog to remove the window from the display, do not set result.
        super.dismiss();
    }

    Data getData() {
        return mData;
    }

    private void setResult(int result) {
        synchronized (mProcLock) {
            if (mProc != null) {
                // Don't dismiss again since it leads to recursive call between dismiss and this
                // method.
                mProc.mErrorState.getDialogController().clearCrashDialogs(false /* needDismiss */);
            }
        }
        mResult.set(result);

        // Make sure we don't have time timeout still hanging around.
        mHandler.removeMessages(TIMEOUT);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case com.android.internal.R.id.aerr_restart:
                mHandler.obtainMessage(RESTART).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_report:
                mHandler.obtainMessage(FORCE_QUIT_AND_REPORT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_close:
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_app_info:
                mHandler.obtainMessage(APP_INFO).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_mute:
                mHandler.obtainMessage(MUTE).sendToTarget();
                break;
            default:
                break;
        }
    }

    static class Data {
        AppErrorResult result;
        int taskId;
        boolean repeating;
        ProcessRecord proc;
        boolean isRestartableForService;
        String crashSummary;
        String stackTrace;
    }
}
