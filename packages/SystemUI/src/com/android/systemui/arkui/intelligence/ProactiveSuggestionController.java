/*
 * Copyright (C) 2025-2026 The AviumUI Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * ArkUI reimplements AviumUseful suggestions inside SystemUI. Per-user lifecycle,
 * authenticated SMS/power events and native floating suggestions are ArkUI changes.
 */
package com.android.systemui.arkui.intelligence;

import static com.android.settingslib.arkui.IntelligenceSettings.*;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Native event-driven suggestions. Message contents never leave memory or enter dumps/logs. */
@SysUISingleton
public final class ProactiveSuggestionController implements CoreStartable {
    public static final String ACTION_DIM_REENGAGED = "org.arkui.action.SCREEN_DIM_REENGAGED";
    private static final String ACTION_STOP_AWAKE = "com.android.systemui.arkui.STOP_AWAKE";
    private static final String SELF_PERMISSION = "com.android.systemui.permission.SELF";
    private static final String AWAKE_CHANNEL = "arkui_temporary_awake";
    private static final int AWAKE_NOTIFICATION = 59803;
    private static final String CLIP_LEASE = "arkui_otp_lease";
    private final Context mContext;
    private final Handler mMain;
    private final Executor mBackground;
    private final UserTracker mUsers;
    private final ActivityStarter mStarter;
    private final FlashlightController mFlashlight;
    private final KeyguardStateController mKeyguard;
    private final PowerManager mPower;
    private final Set<Integer> mHeadphones = new HashSet<>();
    private PowerManager.WakeLock mAwake;
    private UserHandle mAwakeUser;
    private PendingIntent mStopAwake;
    private FloatingSuggestionView mFloatingIcon;
    private WindowManager mWindow;
    private Suggestion mSuggestion;
    private int mGeneration;
    private boolean mStarted;
    private final Runnable mExpire = () -> dismiss(true);
    private final Runnable mTorchReminder = this::remindTorch;
    private void remindTorch() {
        if (feature(TORCH) && mFlashlight.isEnabled()) offer(new Suggestion("torch", 1,
                R.string.arkui_suggest_torch, R.string.arkui_suggest_torch_detail,
                R.string.arkui_suggest_turn_off, null, () -> mFlashlight.setFlashlight(false),
                deadline(SUGGESTION_SECONDS)));
    }
    private final ContentObserver mObserver;

    private record Suggestion(String kind, int priority, int title, int detail, int actionLabel,
            String code, Runnable action, long expires, RemoteAction remoteAction) {
        Suggestion(String kind, int priority, int title, int detail, int actionLabel,
                String code, Runnable action, long expires) {
            this(kind, priority, title, detail, actionLabel, code, action, expires, null);
        }
    }

    @Inject public ProactiveSuggestionController(Context context, @Main Handler main,
            @Background Executor background, UserTracker users, ActivityStarter starter,
            FlashlightController flashlight, KeyguardStateController keyguard) {
        mContext = context;
        mMain = main;
        mBackground = background;
        mUsers = users;
        mStarter = starter;
        mFlashlight = flashlight;
        mKeyguard = keyguard;
        mPower = context.getSystemService(PowerManager.class);
        mObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { reconcile(); }
        };
    }
    @Override public void start() {
        if (mStarted) return;
        mStarted = true;
        // Wake locks die with SystemUI, but its previous notification can survive a restart.
        releaseAwake();
        for (Option option : OPTIONS) mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(option.key()), false, mObserver, UserHandle.USER_ALL);
        mUsers.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanged(int id, @NonNull Context context) {
                ++mGeneration;
                dismiss(true);
                releaseAwake();
                mMain.removeCallbacks(mTorchReminder);
            }
        }, command -> mMain.post(command));
        mKeyguard.addCallback(new KeyguardStateController.Callback() {
            @Override public void onKeyguardShowingChanged() {
                if (mKeyguard.isShowing()) {
                    dismiss(false);
                    releaseAwake();
                } else showCurrent();
            }
        });
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSendingUserId() != mUsers.getUserId() || !SMS_ENABLED.enabled(
                        mContext.getContentResolver(), mUsers.getUserId())) return;
                final int user = mUsers.getUserId();
                final int generation = mGeneration;
                final PendingResult result = goAsync();
                mBackground.execute(() -> {
                    try {
                        final SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                        final StringBuilder text = new StringBuilder();
                        if (parts != null) for (SmsMessage part : parts) {
                            if (part != null && part.getMessageBody() != null) text.append(part.getMessageBody());
                            if (text.length() > 8192) return;
                        }
                        final String code = VerificationCodeParser.extract(text.toString());
                        if (code != null) mMain.post(() -> {
                            if (user != mUsers.getUserId() || generation != mGeneration
                                    || !SMS_ENABLED.enabled(mContext.getContentResolver(), user)) return;
                            offer(new Suggestion("sms", 3, R.string.arkui_suggest_sms,
                                    R.string.arkui_suggest_sms_detail, R.string.arkui_suggest_copy,
                                    code, () -> copyCode(code, user), deadline(SMS_SECONDS)));
                        });
                    } catch (RuntimeException ignored) {
                        // Malformed or incomplete modem PDUs must not crash SystemUI.
                    } finally { result.finish(); }
                });
            }
        }, UserHandle.ALL, new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
                Manifest.permission.BROADCAST_SMS, mMain, Context.RECEIVER_EXPORTED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1) != mUsers.getUserId()
                        || !feature(KEEP_AWAKE) || isAwakeHeld()) return;
                offer(new Suggestion("awake", 1, R.string.arkui_suggest_awake,
                        R.string.arkui_suggest_awake_detail, R.string.arkui_suggest_enable,
                        null, () -> keepAwake(), deadline(SUGGESTION_SECONDS)));
            }
        }, UserHandle.ALL, new IntentFilter(ACTION_DIM_REENGAGED), Manifest.permission.DEVICE_POWER,
                mMain, Context.RECEIVER_EXPORTED);
        final IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    mMain.postDelayed(() -> showCurrent(), 300);
                    return;
                }
                releaseAwake();
                dismiss(!"sms".equals(mSuggestion != null ? mSuggestion.kind : ""));
            }
        }, UserHandle.ALL, screen, null, mMain, Context.RECEIVER_NOT_EXPORTED);
        // Notification commands have their own protected receiver, independent of screen
        // lifecycle events. Target the SystemUI process that owns the wake lock, even when
        // the notification is shown for a secondary user.
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (ACTION_STOP_AWAKE.equals(intent.getAction()) && mAwakeUser != null
                        && intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL)
                                == mAwakeUser.getIdentifier()) {
                    releaseAwake();
                }
            }
        }, new IntentFilter(ACTION_STOP_AWAKE), SELF_PERMISSION, mMain,
                Context.RECEIVER_EXPORTED);
        mFlashlight.addCallback(new FlashlightController.FlashlightListener() {
            @Override public void onFlashlightChanged(boolean enabled) {
                mMain.post(() -> {
                    mMain.removeCallbacks(mTorchReminder);
                    if (enabled && feature(TORCH)) mMain.postDelayed(mTorchReminder, 15000);
                    else if (mSuggestion != null && "torch".equals(mSuggestion.kind)) dismiss(true);
                });
            }
            @Override public void onFlashlightError() { onFlashlightChanged(false); }
            @Override public void onFlashlightAvailabilityChanged(boolean available) {
                if (!available) onFlashlightChanged(false);
            }
        });
        final AudioManager audio = mContext.getSystemService(AudioManager.class);
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isHeadphone(device)) mHeadphones.add(device.getId());
        }
        audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                boolean connected = false;
                for (AudioDeviceInfo device : devices) if (isHeadphone(device)) {
                    connected |= mHeadphones.add(device.getId());
                }
                if (connected && feature(HEADPHONES) && !audio.isMusicActive()) {
                    final Intent launch = musicIntent();
                    if (launch != null) offer(new Suggestion("headphones", 1,
                            R.string.arkui_suggest_headphones, R.string.arkui_suggest_headphones_detail,
                            R.string.arkui_suggest_open, null,
                            () -> mStarter.startActivity(launch, true), deadline(SUGGESTION_SECONDS)));
                }
            }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                for (AudioDeviceInfo device : devices) mHeadphones.remove(device.getId());
                if (mHeadphones.isEmpty() && mSuggestion != null
                        && "headphones".equals(mSuggestion.kind)) dismiss(true);
            }
        }, mMain);
    }
    private Intent musicIntent() {
        String pkg = Settings.Secure.getStringForUser(mContext.getContentResolver(), MUSIC_APP, mUsers.getUserId());
        if (pkg == null) pkg = "org.lineageos.twelve";
        return mUsers.getUserContext().getPackageManager().getLaunchIntentForPackage(pkg);
    }
    private static boolean isHeadphone(AudioDeviceInfo d) {
        if (!d.isSink()) return false;
        return switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true;
            default -> false;
        };
    }
    private boolean feature(Option option) {
        return ENABLED.enabled(mContext.getContentResolver(), mUsers.getUserId())
                && option.enabled(mContext.getContentResolver(), mUsers.getUserId());
    }
    private long deadline(Option option) {
        return SystemClock.elapsedRealtime() + option.read(mContext.getContentResolver(), mUsers.getUserId()) * 1000L;
    }
    private boolean canShow() {
        return mPower.isInteractive() && !mKeyguard.isShowing()
                && mContext.getSystemService(UserManager.class).isUserUnlocked(mUsers.getUserHandle())
                && Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, mUsers.getUserId()) != 0;
    }
    private void offer(Suggestion suggestion) {
        if (mSuggestion != null && mSuggestion.expires > SystemClock.elapsedRealtime()
                && mSuggestion.priority > suggestion.priority) return;
        dismiss(true);
        mSuggestion = suggestion;
        mMain.postDelayed(mExpire, Math.max(1, suggestion.expires - SystemClock.elapsedRealtime()));
        showCurrent();
    }

    /** Called on the main thread after clipboard matching finishes for the current user. */
    public boolean showClipboardSuggestion(RemoteAction action, int user, Runnable onAccepted) {
        if (user != mUsers.getUserId() || !feature(ENABLED)) return false;
        final Suggestion suggestion = new Suggestion("clipboard", 2, 0, 0, 0, null, () -> {
            onAccepted.run();
            mStarter.startPendingIntentDismissingKeyguard(action.getActionIntent());
        }, deadline(SUGGESTION_SECONDS), action);
        offer(suggestion);
        return mSuggestion == suggestion;
    }

    /** A new clipboard item invalidates the action derived from the previous item. */
    public void clearClipboardSuggestion() {
        if (mSuggestion != null && "clipboard".equals(mSuggestion.kind)) dismiss(true);
    }

    private void showCurrent() {
        if (mSuggestion == null || mFloatingIcon != null || !canShow()) return;
        if (mSuggestion.expires <= SystemClock.elapsedRealtime()) { dismiss(true); return; }
        final Suggestion suggestion = mSuggestion;
        final Context windowContext = mContext.createWindowContext(
                mContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY),
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL, null);
        final int icon = switch (suggestion.kind) {
            case "sms" -> R.drawable.arkui_suggestion_sms;
            case "torch" -> R.drawable.arkui_suggestion_torch;
            case "awake" -> R.drawable.arkui_suggestion_awake;
            case "clipboard" -> R.drawable.arkui_suggestion_web;
            default -> R.drawable.arkui_suggestion_music;
        };
        final FloatingSuggestionView floating = new FloatingSuggestionView(windowContext, icon,
                () -> { if (mSuggestion == suggestion) dismiss(true); });
        if (suggestion.remoteAction != null) {
            floating.setIcon(suggestion.remoteAction.getIcon(), mUsers.getUserId());
            floating.setContentDescription(suggestion.remoteAction.getContentDescription());
        } else {
            floating.setContentDescription(windowContext.getString(suggestion.title) + ", "
                    + windowContext.getString(suggestion.detail) + ", "
                    + windowContext.getString(suggestion.actionLabel));
        }
        floating.setOnClickListener(v -> {
            if (mSuggestion != suggestion || !canShow()
                    || suggestion.expires <= SystemClock.elapsedRealtime()) return;
            dismiss(true);
            suggestion.action.run();
        });
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(50), dp(50),
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = dp(20);
        params.y = dp(15);
        params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        params.setTrustedOverlay();
        params.setTitle("ArkUI floating suggestion");
        mWindow = windowContext.getSystemService(WindowManager.class);
        mFloatingIcon = floating;
        try { mWindow.addView(floating, params); }
        catch (RuntimeException e) {
            mFloatingIcon = null;
            Log.w("ArkIntelligence", "Unable to attach floating suggestion", e);
            return;
        }
        floating.animateIn();
        floating.announceForAccessibility(floating.getContentDescription());
    }
    private void dismiss(boolean clear) {
        if (mFloatingIcon != null) {
            mFloatingIcon.animate().cancel();
            mWindow.removeViewImmediate(mFloatingIcon);
            mFloatingIcon = null;
        }
        if (clear) { mSuggestion = null; mMain.removeCallbacks(mExpire); }
    }
    private void reconcile() {
        ++mGeneration;
        if (!feature(KEEP_AWAKE)) releaseAwake();
        if (mSuggestion == null) return;
        if ("clipboard".equals(mSuggestion.kind)) {
            // Link, parcel and master switches all invalidate a previously matched action.
            dismiss(true);
            return;
        }
        final Option option = switch (mSuggestion.kind) {
            case "sms" -> SMS_ENABLED;
            case "torch" -> TORCH;
            case "awake" -> KEEP_AWAKE;
            default -> HEADPHONES;
        };
        if (!(option == SMS_ENABLED ? option.enabled(mContext.getContentResolver(), mUsers.getUserId())
                : feature(option))) dismiss(true);
    }
    private void copyCode(String code, int user) {
        final Context context = mContext.createContextAsUser(UserHandle.of(user), 0);
        final ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        final String lease = UUID.randomUUID().toString();
        final ClipData data = ClipData.newPlainText(context.getString(R.string.arkui_suggest_sms), code);
        final PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        extras.putString(CLIP_LEASE, lease);
        if (SMS_CLEAR.enabled(context.getContentResolver(), user)) {
            extras.putLong("arkui_otp_expiry_elapsed", SystemClock.elapsedRealtime() + 120000);
        }
        data.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(data);
    }
    @SuppressWarnings("deprecation") private void keepAwake() {
        if (!canShow() || !feature(KEEP_AWAKE) || isAwakeHeld()) return;
        mAwakeUser = mUsers.getUserHandle();
        mAwake = mPower.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ArkUI:temporary-awake");
        mAwake.acquire();
        final NotificationManager notifications = mContext.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(AWAKE_CHANNEL,
                mContext.getString(R.string.arkui_suggest_awake), NotificationManager.IMPORTANCE_LOW));
        mStopAwake = PendingIntent.getBroadcast(mContext, AWAKE_NOTIFICATION,
                new Intent(ACTION_STOP_AWAKE).setPackage(mContext.getPackageName())
                        .putExtra(Intent.EXTRA_USER_HANDLE, mAwakeUser.getIdentifier())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifications.notifyAsUser("ArkUI", AWAKE_NOTIFICATION, new Notification.Builder(mContext, AWAKE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(mContext.getString(R.string.arkui_suggest_awake_active))
                .setContentText(mContext.getString(R.string.arkui_suggest_awake_stop))
                .setContentIntent(mStopAwake)
                .addAction(new Notification.Action.Builder(null,
                        mContext.getString(R.string.arkui_suggest_awake_end), mStopAwake).build())
                .setOngoing(true).setShowWhen(false).setLocalOnly(true).build(), mAwakeUser);
    }
    private boolean isAwakeHeld() { return mAwake != null && mAwake.isHeld(); }
    private void releaseAwake() {
        if (isAwakeHeld()) mAwake.release();
        mAwake = null;
        if (mStopAwake != null) { mStopAwake.cancel(); mStopAwake = null; }
        mContext.getSystemService(NotificationManager.class).cancelAsUser("ArkUI",
                AWAKE_NOTIFICATION, mAwakeUser != null ? mAwakeUser : UserHandle.ALL);
        mAwakeUser = null;
    }
    private int dp(int value) { return Math.round(value * mContext.getResources().getDisplayMetrics().density); }
    @Override public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("user=" + mUsers.getUserId() + " enabled=" + feature(ENABLED));
        pw.println("suggestion=" + (mSuggestion == null ? "none" : mSuggestion.kind)
                + " visible=" + (mFloatingIcon != null) + " temporaryAwake=" + isAwakeHeld());
        pw.println("headphones=" + mHeadphones.size());
    }
}
