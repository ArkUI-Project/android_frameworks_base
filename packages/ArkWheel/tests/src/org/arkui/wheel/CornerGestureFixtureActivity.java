/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** An ordinary app underneath the real wheel and navigation input consumers. */
public final class CornerGestureFixtureActivity extends Activity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ScrollView mScroll;
    private TextView mStatus;
    private int mDown, mMove, mUp, mCancel, mClicks, mPauses;
    private boolean mResumed;
    private final Runnable mUpdate = new Runnable() {
        @Override public void run() {
            mStatus.setText(snapshot().toString());
            mHandler.postDelayed(this, 100);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        mScroll = new ScrollView(this);
        mScroll.setFillViewport(true);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 80; i++) {
            TextView row = new TextView(this);
            row.setText("Application scroll row " + i);
            row.setTextColor(Color.BLACK);
            row.setTextSize(20);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(24), 0, dp(24), 0);
            row.setBackgroundColor(i % 2 == 0 ? 0xffeef3ff : Color.WHITE);
            row.setOnClickListener(v -> mClicks++);
            rows.addView(row, new LinearLayout.LayoutParams(-1, dp(72)));
        }
        mScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        root.addView(mScroll, new FrameLayout.LayoutParams(-1, -1));
        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setBackgroundColor(0xee173653);
        mStatus.setTextSize(15);
        mStatus.setPadding(dp(12), dp(8), dp(12), dp(8));
        FrameLayout.LayoutParams status = new FrameLayout.LayoutParams(-1,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(mStatus, status);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            status.topMargin = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            mStatus.setLayoutParams(status);
            return insets;
        });
        setContentView(root);
        mHandler.post(mUpdate);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: mDown++; break;
            case MotionEvent.ACTION_MOVE: mMove++; break;
            case MotionEvent.ACTION_UP: mUp++; break;
            case MotionEvent.ACTION_CANCEL: mCancel++; break;
        }
        Log.i("ArkWheelFixture", "touch=" + MotionEvent.actionToString(event.getAction())
                + " raw=" + event.getRawX() + "," + event.getRawY());
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onResume() { super.onResume(); mResumed = true; }
    @Override protected void onPause() { mPauses++; mResumed = false; super.onPause(); }
    @Override protected void onDestroy() {
        mHandler.removeCallbacks(mUpdate);
        super.onDestroy();
    }

    void reset() {
        mScroll.scrollTo(0, 0);
        mDown = mMove = mUp = mCancel = mClicks = mPauses = 0;
    }

    Snapshot snapshot() {
        return new Snapshot(mDown, mMove, mUp, mCancel, mClicks, mScroll.getScrollY(),
                mPauses, mResumed, getTaskId());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    static final class Snapshot {
        final int down, move, up, cancel, clicks, scrollY, pauses, taskId;
        final boolean resumed;
        Snapshot(int down, int move, int up, int cancel, int clicks, int scrollY,
                int pauses, boolean resumed, int taskId) {
            this.down = down; this.move = move; this.up = up; this.cancel = cancel;
            this.clicks = clicks; this.scrollY = scrollY; this.pauses = pauses;
            this.resumed = resumed; this.taskId = taskId;
        }
        @Override public String toString() {
            return "TouchCount: down=" + down + " move=" + move + " up=" + up
                    + " cancel=" + cancel + "\nclicks=" + clicks + " scrollY=" + scrollY
                    + " pauses=" + pauses + " resumed=" + resumed + " task=" + taskId;
        }
    }
}
