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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.BidiFormatter;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

final class AppNotRespondingDialog extends BaseErrorDialog implements View.OnClickListener {
    private static final String TAG = "AppNotRespondingDialog";

    // Event 'what' codes
    static final int FORCE_CLOSE = 1;
    static final int WAIT = 2;
    static final int WAIT_AND_REPORT = 3;

    public static final int CANT_SHOW = -1;
    public static final int ALREADY_SHOWING = -2;

    private final ActivityManagerService mService;
    private final ProcessRecord mProc;
    private final Data mData;
    private final CharSequence mDialogTitle;
    private AppErrorDialogUi mUi;

    public AppNotRespondingDialog(ActivityManagerService service, Context context, Data data) {
        super(context);

        mService = service;
        mProc = data.proc;
        mData = data;
        Resources res = context.getResources();

        setCancelable(false);

        int resid;
        CharSequence name1 = data.aInfo != null
                ? data.aInfo.loadLabel(context.getPackageManager())
                : null;
        CharSequence name2 = null;
        if (mProc.getPkgList().size() == 1
                && (name2 = context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            if (name1 != null) {
                resid = com.android.internal.R.string.anr_activity_application;
            } else {
                name1 = name2;
                name2 = mProc.processName;
                resid = com.android.internal.R.string.anr_application_process;
            }
        } else {
            if (name1 != null) {
                name2 = mProc.processName;
                resid = com.android.internal.R.string.anr_activity_process;
            } else {
                name1 = mProc.processName;
                resid = com.android.internal.R.string.anr_process;
            }
        }

        BidiFormatter bidi = BidiFormatter.getInstance();

        mDialogTitle = name2 != null
                ? res.getString(resid, bidi.unicodeWrap(name1.toString()), bidi.unicodeWrap(name2.toString()))
                : res.getString(resid, bidi.unicodeWrap(name1.toString()));
        setTitle(mDialogTitle);

        if (data.aboveSystem) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Not Responding: " + mProc.info.processName);
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR |
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context context = getContext();
        mUi = new AppErrorDialogUi(this);
        mUi.showHeader(mDialogTitle, null);
        mUi.addAction(com.android.internal.R.id.aerr_close,
                com.android.internal.R.string.aerr_close_app, com.android.internal.R.drawable.ic_close,
                AppErrorDialogUi.TONAL, this);
        final boolean hasReceiver = mProc.mErrorState.getErrorReportReceiver() != null;
        if (hasReceiver) {
            mUi.addAction(com.android.internal.R.id.aerr_report,
                    com.android.internal.R.string.aerr_report,
                    com.android.internal.R.drawable.ic_lock_bugreport, AppErrorDialogUi.TEXT, this);
        }
        mUi.addAction(com.android.internal.R.id.aerr_wait,
                com.android.internal.R.string.aerr_wait,
                com.android.internal.R.drawable.ic_zen_mode_type_schedule_time,
                AppErrorDialogUi.PRIMARY, this);
    }

    @Override
    public void onStart() {
        super.onStart();
        mUi.start();
    }

    @Override
    protected void onStop() {
        mUi.stop();
        super.onStop();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case com.android.internal.R.id.aerr_report:
                mHandler.obtainMessage(WAIT_AND_REPORT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_close:
                mHandler.obtainMessage(FORCE_CLOSE).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_wait:
                mHandler.obtainMessage(WAIT).sendToTarget();
                break;
            default:
                break;
        }
    }

    Data getData() {
        return mData;
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            Intent appErrorIntent = null;

            MetricsLogger.action(getContext(), MetricsProto.MetricsEvent.ACTION_APP_ANR,
                    msg.what);

            switch (msg.what) {
                case FORCE_CLOSE:
                    // Kill the application.
                    mService.killAppAtUsersRequest(mProc);
                    break;
                case WAIT_AND_REPORT:
                case WAIT:
                    // Continue waiting for the application.
                    synchronized (mService) {
                        ProcessRecord app = mProc;
                        final ProcessErrorStateRecord errState = app.mErrorState;

                        if (msg.what == WAIT_AND_REPORT) {
                            appErrorIntent = mService.mAppErrors.createAppErrorIntentLOSP(app,
                                    System.currentTimeMillis(), null);
                        }

                        synchronized (mService.mProcLock) {
                            errState.setNotResponding(false);
                            // We're not clearing the ANR report here, in case we'd need to report
                            // it again when the ANR dialog shows again.
                            // errState.setNotRespondingReport(null);
                            errState.getDialogController().clearAnrDialogs();
                        }
                        mService.mServices.scheduleServiceTimeoutLocked(app);
                        if (mData.isContinuousAnr) {
                            // If the app remains unresponsive, show the dialog again after a delay.
                            mService.mInternal.rescheduleAnrDialog(mData);
                        }
                    }
                    break;
            }

            if (appErrorIntent != null) {
                try {
                    getContext().startActivity(appErrorIntent);
                } catch (ActivityNotFoundException e) {
                    Slog.w(TAG, "bug report receiver dissappeared", e);
                }
            }

            dismiss();
        }
    };

    @Override
    protected void closeDialog() {
        mHandler.obtainMessage(FORCE_CLOSE).sendToTarget();
    }

    static class Data {
        final ProcessRecord proc;
        final ApplicationInfo aInfo;
        final boolean aboveSystem;

        // If true, then even if the user presses "WAIT" on the ANR dialog,
        // we'll show it again until the app start responding again.
        // (we only use it for input dispatch ANRs)
        final boolean isContinuousAnr;

        Data(ProcessRecord proc, ApplicationInfo aInfo, boolean aboveSystem,
                boolean isContinuousAnr) {
            this.proc = proc;
            this.aInfo = aInfo;
            this.aboveSystem = aboveSystem;
            this.isContinuousAnr = isContinuousAnr;
        }
    }
}
