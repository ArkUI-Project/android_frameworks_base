/*
 * Copyright (C) 2026 The AviumUI Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * ArkUI adapts AviumLyricBarCore's active MediaSession/title behavior into a native
 * lifecycle-bound view, with local LRC timing, authenticated session events and user settings.
 */
package com.android.systemui.arkui.intelligence;

import static com.android.settingslib.arkui.IntelligenceSettings.*;

import android.app.StatusBarManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.ArrayList;
import java.util.List;

/** Gives native activity/privacy chips precedence and releases media listeners when detached. */
public final class StatusBarLyricView extends TextView implements DarkIconDispatcher.DarkReceiver,
        StatusBarStateController.StateListener, CommandQueue.Callbacks {
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<MediaController> mControllers = new ArrayList<>();
    private final MediaSessionManager mSessions;
    private final Runnable mTick = this::render;
    private UserTracker mUsers;
    private StatusBarStateController mStatus;
    private DarkIconDispatcher mDark;
    private KeyguardStateController mKeyguard;
    private CommandQueue mCommands;
    private View mIcons;
    private View mChip;
    private View mSecondaryChip;
    private View mComposeChips;
    private View mHeadsUp;
    private View mClock;
    private View mStartContainer;
    private boolean mRegistered;
    private boolean mEnabled;
    private boolean mBlocked;
    private boolean mFallback;
    private boolean mAnimate;
    private boolean mScreenOn;
    private int mWidth;
    private int mOffset;
    private String mSource = "";
    private String mTrack = "";
    private String mSessionLine = "";
    private MediaController mActive;
    private PlaybackState mPlayback;
    private String mTitle = "";
    private LyricsTimeline mTimeline = LyricsTimeline.parse(null);
    private final ContentObserver mObserver = new ContentObserver(mMain) {
        @Override public void onChange(boolean selfChange) { readSettings(); }
    };
    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override public void onUserChanged(int id, @NonNull Context context) {
            stopSessions();
            mSessionLine = "";
            mSource = "";
            mActive = null;
            readSettings();
        }
    };
    private final KeyguardStateController.Callback mKeyguardCallback = new KeyguardStateController.Callback() {
        @Override public void onKeyguardShowingChanged() { render(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionListener = this::setControllers;
    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { selectActive(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { selectActive(); }
        @Override public void onSessionDestroyed() { refreshSessions(); }
    };
    private final View.OnLayoutChangeListener mLayout = (v, l, t, r, b, ol, ot, or, ob) -> render();
    private final ViewTreeObserver.OnGlobalLayoutListener mGlobalLayout = this::render;
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            mScreenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            render();
        }
    };

    public StatusBarLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSessions = context.getSystemService(MediaSessionManager.class);
        setSingleLine(true);
        setGravity(Gravity.CENTER_VERTICAL);
        setMarqueeRepeatLimit(-1);
        setPadding(dp(9), dp(2), dp(9), dp(2));
        setVisibility(GONE);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mUsers = Dependency.get(UserTracker.class);
        mStatus = Dependency.get(StatusBarStateController.class);
        mKeyguard = Dependency.get(KeyguardStateController.class);
        mDark = Dependency.get(DarkIconDispatcher.class);
        mCommands = Dependency.get(CommandQueue.class);
        final View root = getRootView();
        mIcons = root.findViewById(R.id.notification_icon_area);
        mChip = root.findViewById(R.id.ongoing_activity_chip_primary);
        mSecondaryChip = root.findViewById(R.id.ongoing_activity_chip_secondary);
        mComposeChips = root.findViewById(R.id.arkui_compose_activity_chips);
        mHeadsUp = root.findViewById(R.id.heads_up_status_bar_view);
        mClock = root.findViewById(R.id.clock);
        mStartContainer = root.findViewById(R.id.status_bar_start_side_container);
        for (View v : new View[]{mChip, mSecondaryChip, mComposeChips, mHeadsUp, mClock, mStartContainer}) {
            if (v != null) v.addOnLayoutChangeListener(mLayout);
        }
        for (Option option : new Option[]{LYRICS_ENABLED, LYRICS_FALLBACK, LYRICS_MARQUEE,
                LYRICS_ANIMATE, LYRICS_WIDTH, LYRICS_TEXT_SIZE, LYRICS_OFFSET}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(option.key()), false, mObserver, UserHandle.USER_ALL);
        }
        mUsers.addCallback(mUserCallback, command -> mMain.post(command));
        mStatus.addCallback(this);
        mKeyguard.addCallback(mKeyguardCallback);
        mCommands.addCallback(this);
        mDark.addDarkReceiver(this);
        getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayout);
        mScreenOn = getContext().getSystemService(PowerManager.class).isInteractive();
        final IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(mScreenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
        readSettings();
    }
    @Override protected void onDetachedFromWindow() {
        stopSessions();
        mMain.removeCallbacks(mTick);
        getContext().getContentResolver().unregisterContentObserver(mObserver);
        mUsers.removeCallback(mUserCallback);
        mStatus.removeCallback(this);
        mKeyguard.removeCallback(mKeyguardCallback);
        mCommands.removeCallback(this);
        mDark.removeDarkReceiver(this);
        getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayout);
        getContext().unregisterReceiver(mScreenReceiver);
        for (View v : new View[]{mChip, mSecondaryChip, mComposeChips, mHeadsUp, mClock, mStartContainer}) {
            if (v != null) v.removeOnLayoutChangeListener(mLayout);
        }
        if (mIcons != null) mIcons.setVisibility(VISIBLE);
        animate().cancel();
        super.onDetachedFromWindow();
    }
    private void readSettings() {
        final var r = getContext().getContentResolver();
        final int user = mUsers.getUserId();
        mEnabled = LYRICS_ENABLED.enabled(r, user);
        mFallback = LYRICS_FALLBACK.enabled(r, user);
        mAnimate = LYRICS_ANIMATE.enabled(r, user);
        mWidth = dp(LYRICS_WIDTH.read(r, user));
        mOffset = LYRICS_OFFSET.read(r, user);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LYRICS_TEXT_SIZE.read(r, user));
        setEllipsize(LYRICS_MARQUEE.enabled(r, user) ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        setSelected(LYRICS_MARQUEE.enabled(r, user));
        if (mEnabled && !mRegistered) {
            mSessions.addOnActiveSessionsChangedListener(null, UserHandle.of(user),
                    command -> mMain.post(command), mSessionListener);
            mRegistered = true;
            refreshSessions();
        } else if (!mEnabled) stopSessions();
        render();
    }
    private void refreshSessions() {
        if (mRegistered) setControllers(mSessions.getActiveSessionsForUser(null, mUsers.getUserHandle()));
    }
    private void setControllers(List<MediaController> controllers) {
        for (MediaController controller : mControllers) controller.unregisterCallback(mMediaCallback);
        mControllers.clear();
        if (controllers != null) mControllers.addAll(controllers);
        for (MediaController controller : mControllers) controller.registerCallback(mMediaCallback, mMain);
        selectActive();
    }
    private void stopSessions() {
        if (mRegistered) mSessions.removeOnActiveSessionsChangedListener(mSessionListener);
        mRegistered = false;
        for (MediaController controller : mControllers) controller.unregisterCallback(mMediaCallback);
        mControllers.clear();
        if (mActive != null) mActive.unregisterCallback(mLineCallback);
        mActive = null;
        mPlayback = null;
        mTitle = "";
        mMain.removeCallbacks(mTick);
    }
    private final MediaController.Callback mLineCallback = new MediaController.Callback() {
        @Override public void onSessionEvent(String event, Bundle extras) {
            // Session callbacks identify the real active player; no exported broadcast is trusted.
            if (extras == null) return;
            String line = extras.getString("lyric");
            if (line == null) line = extras.getString("lyrics");
            if (line == null || line.length() > 65536) return;
            final LyricsTimeline timeline = LyricsTimeline.parse(line);
            if (!timeline.isEmpty()) {
                mTimeline = timeline;
                mSessionLine = "";
            } else mSessionLine = line.length() > 512 ? line.substring(0, 512) : line;
            render();
        }
    };
    private void selectActive() {
        MediaController playing = null;
        for (MediaController controller : mControllers) {
            if (controller.getPlaybackState() != null
                    && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                playing = controller; break;
            }
        }
        if (mActive != playing) {
            if (mActive != null) mActive.unregisterCallback(mLineCallback);
            mActive = playing;
            mSessionLine = "";
            mSource = "";
            mTrack = "";
            mTimeline = LyricsTimeline.parse(null);
            if (playing != null) playing.registerCallback(mLineCallback, mMain);
        }
        final MediaMetadata metadata = playing == null ? null : playing.getMetadata();
        mPlayback = playing == null ? null : playing.getPlaybackState();
        mTitle = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String source = metadata == null ? "" : metadataLyrics(metadata);
        final String track = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                + "\u0000" + metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (!track.equals(mTrack) || !source.equals(mSource)) {
            mTrack = track;
            mSessionLine = "";
            mSource = source;
            mTimeline = LyricsTimeline.parse(source);
        }
        render();
    }
    private static String metadataLyrics(MediaMetadata metadata) {
        for (String key : new String[]{"org.arkui.media.LYRICS", "android.media.metadata.LYRICS", "LYRICS", "lyrics", "lyric"}) {
            final String value = metadata.getString(key);
            if (!TextUtils.isEmpty(value) && value.length() <= 65536) return value;
        }
        return "";
    }
    private void render() {
        mMain.removeCallbacks(mTick);
        if (!isAttachedToWindow() || mUsers == null) return;
        final PlaybackState state = mActive == null ? null : mPlayback;
        String text = "";
        long position = 0;
        if (mEnabled && state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            position = state.getPosition();
            if (state.getLastPositionUpdateTime() > 0) position += (long) ((SystemClock.elapsedRealtime()
                    - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            position += mOffset;
            text = !mSessionLine.isEmpty() ? mSessionLine : mTimeline.isEmpty() ? mSource : mTimeline.at(position);
            if (text.isEmpty() && mTimeline.isEmpty() && mFallback) {
                text = mTitle;
                if (text == null) text = "";
            }
        }
        if (text.length() > 512) text = text.substring(0, 512);
        int width = mWidth;
        if (mStartContainer != null && mStartContainer.getWidth() > 0) {
            width = Math.min(width, mStartContainer.getWidth() - (mClock != null ? mClock.getWidth() : dp(48)) - dp(20));
        }
        final boolean allowed = mScreenOn && !mBlocked && !mKeyguard.isShowing()
                && mStatus.getState() == StatusBarState.SHADE && width >= dp(40)
                && !shown(mChip) && !shown(mSecondaryChip) && !shown(mHeadsUp)
                && !(shown(mComposeChips) && mComposeChips.getWidth() > 0);
        final boolean visible = allowed && !TextUtils.isEmpty(text);
        // Keep the next timestamp even during an instrumental gap or a negative timing offset.
        if (allowed && mEnabled && state != null && state.getState() == PlaybackState.STATE_PLAYING
                && !mTimeline.isEmpty() && mSessionLine.isEmpty() && state.getPlaybackSpeed() > 0) {
            final long next = mTimeline.next(position);
            if (next >= 0) mMain.postDelayed(mTick, Math.max(16,
                    (long) Math.ceil((next - position) / state.getPlaybackSpeed())));
        }
        final int maximum = Math.max(dp(40), width);
        if (getMaxWidth() != maximum) setMaxWidth(maximum);
        if (mIcons != null) mIcons.setVisibility(visible ? GONE : VISIBLE);
        if (!visible) { animate().cancel(); setVisibility(GONE); return; }
        final boolean changed = !TextUtils.equals(getText(), text) || getVisibility() != VISIBLE;
        setVisibility(VISIBLE);
        if (changed) {
            setText(text);
            setContentDescription(getText());
            animate().cancel();
            if (mAnimate) {
                setAlpha(0);
                animate().alpha(1).setDuration(240).start();
            } else setAlpha(1);
        }
    }
    private static boolean shown(View view) {
        return view != null && view.getVisibility() == VISIBLE;
    }
    @Override public void onStateChanged(int state) { render(); }
    @Override public void disable(int displayId, int state1, int state2, boolean animate) {
        if (getDisplay() == null || displayId != getDisplay().getDisplayId()) return;
        mBlocked = (state1 & (StatusBarManager.DISABLE_CLOCK | StatusBarManager.DISABLE_NOTIFICATION_ICONS)) != 0;
        render();
    }
    @Override public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        final int color = DarkIconDispatcher.getTint(areas, this, tint);
        setTextColor(color);
        final GradientDrawable background = new GradientDrawable();
        background.setColor((color & 0x00ffffff) | 0x18000000);
        background.setCornerRadius(dp(12));
        setBackground(background);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
