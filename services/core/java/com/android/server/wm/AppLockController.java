/*
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
package com.android.server.wm;

import static android.app.AppLockManager.*;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppLockManagerInternal;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.HandlerExecutor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.am.PendingIntentRecord;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** System-owned policy and capabilities. Mutable state is protected by the WM global lock. */
final class AppLockController {
    private static final String TAG = "AppLockController";
    private static final long AUTH_TTL = 5 * 60_000L;
    private static final long MANAGEMENT_TTL = 5 * 60_000L;
    private static final long LAUNCH_GRACE = 30_000L;
    private static final ComponentName CONFIRM_ACTIVITY = new ComponentName(
            "com.android.settings", "com.android.settings.arkui.applock.AppLockConfirmActivity");

    private final ActivityTaskManagerService mService;
    private final LockPatternUtils mLockPatternUtils;
    private final SparseArray<UserState> mUsers = new SparseArray<>();
    private final ArrayMap<IBinder, Challenge> mChallenges = new ArrayMap<>();
    private final ArrayMap<IBinder, ManagementSession> mManagement = new ArrayMap<>();
    private final CopyOnWriteArrayList<AppLockManagerInternal.Listener> mListeners =
            new CopyOnWriteArrayList<>();
    private volatile Map<Integer, UserSnapshot> mSnapshot = Collections.emptyMap();
    private boolean mReady;
    private boolean mRefreshScheduled;
    private boolean mVisibilityScheduled;
    private int mRequestId;

    private final AppLockManagerInternal mInternal = new AppLockManagerInternal() {
        @Override public boolean isPackageProtected(String pkg, int userId) {
            UserSnapshot user = mSnapshot.get(userId);
            return user != null && user.packages.contains(pkg);
        }
        @Override public boolean isPackageLocked(String pkg, int userId) {
            UserSnapshot user = mSnapshot.get(userId);
            return user != null && user.isLocked(pkg);
        }
        @Override public boolean shouldHideNotifications(String pkg, int userId) {
            UserSnapshot user = mSnapshot.get(userId);
            return user != null && user.hideNotifications && user.isLocked(pkg);
        }
        @Override public void addListener(Listener listener) {
            mListeners.addIfAbsent(listener);
        }
        @Override public void removeListener(Listener listener) {
            mListeners.remove(listener);
        }
    };

    AppLockController(ActivityTaskManagerService service) {
        mService = service;
        mLockPatternUtils = new LockPatternUtils(service.mContext);
        LocalServices.addService(AppLockManagerInternal.class, mInternal);
    }

    void systemReady() {
        UserManager users = mService.mContext.getSystemService(UserManager.class);
        for (UserInfo user : users.getUsers()) {
            pruneEssentialPackages(userState(user.id));
        }
        mReady = true;
        publishAll();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                synchronized (mService.mGlobalLock) {
                    String action = intent.getAction();
                    int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        invalidateCapabilities(UserHandle.USER_ALL);
                        mService.mPrivacyPasswordController.invalidate(UserHandle.USER_ALL, false);
                        for (int i = 0; i < mUsers.size(); i++) {
                            UserState state = mUsers.valueAt(i);
                            state.generation++;
                            if (state.config.lockOnScreenOff
                                    || state.config.mode == MODE_SCREEN_OFF) {
                                state.grants.clear();
                            }
                            updateForeground(state, Collections.emptySet());
                            publish(state.userId);
                        }
                        refreshWindows();
                    } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                        invalidateCapabilities(UserHandle.USER_ALL);
                        mService.mPrivacyPasswordController.invalidate(UserHandle.USER_ALL, false);
                        for (int i = 0; i < mUsers.size(); i++) {
                            UserState state = mUsers.valueAt(i);
                            state.generation++;
                            state.grants.clear();
                            state.foreground.clear();
                            publish(state.userId);
                        }
                        userState(user);
                        refreshWindows();
                    } else if (Intent.ACTION_USER_STOPPED.equals(action)
                            || Intent.ACTION_USER_REMOVED.equals(action)) {
                        onUserStopped(user);
                        mService.mPrivacyPasswordController.invalidate(user,
                                Intent.ACTION_USER_REMOVED.equals(action));
                        if (Intent.ACTION_USER_REMOVED.equals(action)) {
                            policyFile(user).delete();
                            mUsers.remove(user);
                            publishAll();
                        }
                    } else {
                        if (Intent.ACTION_USER_UNLOCKED.equals(action)) userState(user);
                        // Removing the device credential is itself a verified security operation.
                        // It must not leave applications behind an impossible challenge.
                        for (int i = 0; i < mUsers.size(); i++) {
                            UserState state = mUsers.valueAt(i);
                            if (state.config.enabled
                                    && !hasCredential(state.userId, state.config.authMethod)) {
                                Config disabled = new Config(state.config.toBundle());
                                disabled.enabled = false;
                                writeLifecycleConfig(state.userId, disabled);
                                state.config = disabled;
                                state.grants.clear();
                                state.generation++;
                                invalidateCapabilities(state.userId);
                                publish(state.userId);
                            }
                        }
                        refreshWindows();
                    }
                    scheduleExpiry();
                }
            }
        }, filter, null, mService.mH, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getData() == null) return;
                String pkg = intent.getData().getSchemeSpecificPart();
                int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        uid < 0 ? getSendingUserId() : UserHandle.getUserId(uid));
                if (userId < 0) return;
                synchronized (mService.mGlobalLock) {
                    UserState state = userState(userId);
                    if (!state.config.packages.contains(pkg)) return;
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                            && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                    state.grants.remove(pkg);
                    state.generation++;
                    invalidateCapabilities(userId);
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                            || !canProtect(pkg, userId)) {
                        Bundle values = state.config.toBundle();
                        ArrayList<String> selected = values.getStringArrayList(KEY_PACKAGES);
                        selected.remove(pkg);
                        values.putStringArrayList(KEY_PACKAGES, selected);
                        Config config = new Config(values);
                        writeLifecycleConfig(userId, config);
                        state.config = config;
                    }
                    publish(userId);
                    invalidateSnapshots(userId);
                    refreshWindows();
                    scheduleExpiry();
                }
            }
        }, packages, null, mService.mH, Context.RECEIVER_NOT_EXPORTED);
        RoleManager roles = mService.mContext.getSystemService(RoleManager.class);
        if (roles != null) {
            roles.addOnRoleHoldersChangedListenerAsUser(new HandlerExecutor(mService.mH),
                    (roleName, user) -> {
                        if (!RoleManager.ROLE_HOME.equals(roleName)) return;
                        synchronized (mService.mGlobalLock) {
                            UserState state = userState(user.getIdentifier());
                            if (pruneEssentialPackages(state)) {
                                state.generation++;
                                invalidateCapabilities(state.userId);
                                state.grants.clear();
                                publish(state.userId);
                                refreshWindows();
                            }
                        }
                    }, UserHandle.ALL);
        }
    }

    void onUserUnlocked(int userId) {
        UserState state = userState(userId);
        pruneEssentialPackages(state);
        publish(userId);
    }

    private boolean pruneEssentialPackages(UserState state) {
        ArrayList<String> retained = new ArrayList<>();
        for (String pkg : state.config.packages) {
            if (canProtect(pkg, state.userId)) retained.add(pkg);
        }
        if (retained.equals(state.config.packages)) return false;
        Bundle values = state.config.toBundle();
        values.putStringArrayList(KEY_PACKAGES, retained);
        Config config = new Config(values);
        writeLifecycleConfig(state.userId, config);
        state.config = config;
        return true;
    }

    void onUserStopped(int userId) {
        invalidateCapabilities(userId);
        UserState state = mUsers.get(userId);
        if (state != null) {
            state.generation++;
            state.grants.clear();
            state.foreground.clear();
            publish(userId);
        }
    }

    boolean isProtected(String pkg, int userId) {
        return mInternal.isPackageProtected(pkg, userId);
    }

    boolean isLocked(String pkg, int userId) {
        return mInternal.isPackageLocked(pkg, userId);
    }

    int nextRequestId() { return ++mRequestId; }

    boolean isTaskProtected(Task task) {
        if (task == null) return false;
        if (task.realActivity != null && isProtected(task.realActivity.getPackageName(), task.mUserId)) {
            return true;
        }
        return task.getActivity(r -> isProtected(r.packageName, r.mUserId)) != null;
    }

    boolean isSnapshotProtected(WindowContainer<?> container) {
        if (container == null) return false;
        Task task = container.asTask();
        if (task != null && isTaskProtected(task)) return true;
        ActivityRecord activity = container.asActivityRecord();
        if (activity != null && isProtected(activity.packageName, activity.mUserId)) return true;
        return container.getActivity(r -> isProtected(r.packageName, r.mUserId)) != null
                || container.getTask(this::isTaskProtected) != null;
    }

    boolean isCaptureProtected(WindowContainer<?> container) {
        if (container == null) return false;
        if (container.asTask() != null || container.asActivityRecord() != null) {
            return isSnapshotProtected(container);
        }
        // A display/area also owns invisible background tasks. Their policy must not turn an
        // unrelated desktop rotation into a blank animation when no protected pixels are shown.
        return container.getActivity(activity -> {
            if ((!activity.isVisible() && !activity.isVisibleRequested())
                    || !isProtected(activity.packageName, activity.mUserId)) return false;
            Task task = activity.getTask();
            if (task == null || task.isForceHidden()) return false;
            Task root = task.getRootTask();
            if (root == null || root.isForceHidden()) return false;
            TaskWindowSurfaceInfo info = root.mWindowContainerExt.getTaskWindowSurfaceInfo();
            return info == null || !info.isDockedToEdge() || info.isSurfaceAnimating();
        }) != null;
    }

    boolean isTaskSnapshotProtected(int taskId) {
        if (mService.mRootWindowContainer == null) return false;
        Task task = mService.mRootWindowContainer.anyTaskForId(taskId,
                RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
        return isTaskProtected(task);
    }

    Intent interceptLaunch(String pkg, int userId, PendingIntentRecord original, Task task,
            int displayId, IBinder resultTo, String resultWho, int requestCode) {
        if (!isLocked(pkg, userId)) return null;
        Challenge challenge = findLaunchChallenge(pkg, userId);
        if (challenge == null) {
            challenge = new Challenge(userState(userId), pkg, null);
            mChallenges.put(challenge.token, challenge);
        } else {
            cancelResult(challenge);
        }
        challenge.original = original;
        challenge.restoreOptions = null;
        challenge.resultTo = resultTo;
        challenge.resultWho = resultWho;
        challenge.requestCode = requestCode;
        challenge.resultDelivered = false;
        challenge.taskId = task == null ? ActivityTaskManager.INVALID_TASK_ID : task.mTaskId;
        challenge.displayId = displayId;
        // ActivityStarter is about to create the confirmation record synchronously. Bridge the
        // short interval before it exists without queuing a second confirmation task.
        challenge.directLaunchUntil = SystemClock.uptimeMillis() + 1_000L;
        scheduleExpiry();
        return confirmIntent(challenge);
    }

    boolean interceptTask(Task task, ActivityOptions options) {
        ActivityRecord top = task == null ? null : task.getTopNonFinishingActivity();
        if (top == null || !isLocked(top.packageName, top.mUserId)) return false;
        requestChallenge(top, options, true);
        return true;
    }

    boolean interceptResume(ActivityRecord activity) {
        if (!isLocked(activity.packageName, activity.mUserId)) return false;
        activity.setVisibility(false);
        requestChallenge(activity, null, false);
        return true;
    }

    private void requestChallenge(ActivityRecord activity, ActivityOptions originalOptions,
            boolean explicitTask) {
        if (!mReady || mService.isSleepingLocked()
                || mService.mTaskSupervisor.getKeyguardController()
                        .isKeyguardLocked(activity.getDisplayId())) return;
        Task task = activity.getTask();
        if (task == null) return;
        Challenge challenge = findLaunchChallenge(activity.packageName, activity.mUserId);
        if (challenge == null) {
            challenge = new Challenge(userState(activity.mUserId), activity.packageName, null);
            challenge.taskId = task.mTaskId;
            challenge.displayId = activity.getDisplayId();
            mChallenges.put(challenge.token, challenge);
        }
        if (explicitTask) {
            // Recents/move-to-front chooses an existing task, superseding a pending cold launch.
            // An automatic resume or its delayed retry must not replace this user selection.
            cancelResult(challenge);
            if (challenge.original != null) {
                mService.mPendingIntentController.cancelIntentSender(challenge.original,
                        true /* cleanActivity */, PendingIntentRecord.CANCEL_REASON_SUPERSEDED);
                challenge.original = null;
            }
            challenge.resultTo = null;
            challenge.resultWho = null;
            challenge.requestCode = -1;
            challenge.taskId = task.mTaskId;
            challenge.displayId = originalOptions != null
                    && originalOptions.getLaunchDisplayId() != android.view.Display.INVALID_DISPLAY
                    ? originalOptions.getLaunchDisplayId() : activity.getDisplayId();
            challenge.restoreOptions = originalOptions == null
                    ? null : new Bundle(originalOptions.toBundle());
        }
        if (hasConfirmationActivity(challenge)) {
            // Both callers have already checked task-start permissions and hold the WM lock.
            // Reuse the live prompt without restarting authentication or a delayed focus change.
            if (explicitTask) reuseConfirmation(challenge.token);
            return;
        }
        if (challenge.pendingLaunch) return;
        long bridge = challenge.directLaunchUntil - SystemClock.uptimeMillis();
        if (bridge > 0) {
            if (!challenge.retryScheduled) {
                challenge.retryScheduled = true;
                final Challenge retry = challenge;
                mService.mH.postDelayed(() -> {
                    synchronized (mService.mGlobalLock) {
                        retry.retryScheduled = false;
                        if (mChallenges.get(retry.token) == retry && valid(retry)
                                && activity.getTask() != null
                                && isLocked(activity.packageName, activity.mUserId)) {
                            requestChallenge(activity, null, false);
                        }
                    }
                }, bridge);
            }
            return;
        }
        challenge.pendingLaunch = true;
        final Challenge pending = challenge;
        mService.mH.post(() -> {
            synchronized (mService.mGlobalLock) {
                if (mChallenges.get(pending.token) != pending || !valid(pending)
                        || hasConfirmationActivity(pending)) {
                    pending.pendingLaunch = false;
                    return;
                }
            }
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(pending.displayId);
                mService.mContext.startActivityAsUser(confirmIntent(pending), options.toBundle(),
                        UserHandle.of(pending.userId));
            } catch (RuntimeException error) {
                Slog.e(TAG, "Unable to show application lock challenge", error);
                synchronized (mService.mGlobalLock) {
                    mChallenges.remove(pending.token);
                    discard(pending);
                }
            } finally {
                synchronized (mService.mGlobalLock) {
                    pending.pendingLaunch = false;
                }
            }
        });
        scheduleExpiry();
    }

    private ActivityRecord confirmationActivity(Challenge challenge) {
        return mService.mRootWindowContainer.getActivity(activity -> {
            if (activity.finishing || !CONFIRM_ACTIVITY.equals(activity.mActivityComponent)
                    || activity.isState(ActivityRecord.State.DESTROYED,
                            ActivityRecord.State.DESTROYING)) return false;
            Bundle extras = activity.intent.getExtras();
            if (extras == null || extras.getBinder(EXTRA_CHALLENGE) != challenge.token) return false;
            // A cold confirmation can be waiting for process attach. A credential fallback can
            // keep the confirmation STOPPED; neither is a process-death signal.
            return activity.isState(ActivityRecord.State.INITIALIZING)
                    || activity.attachedToProcess();
        });
    }

    private boolean hasConfirmationActivity(Challenge challenge) {
        return confirmationActivity(challenge) != null;
    }

    boolean canReuseConfirmation(IBinder token) {
        Challenge challenge = mChallenges.get(token);
        return challenge != null && valid(challenge) && hasConfirmationActivity(challenge);
    }

    ActivityRecord reuseConfirmation(IBinder token) {
        Challenge challenge = requireChallenge(token);
        ActivityRecord confirmation = confirmationActivity(challenge);
        if (confirmation == null) return null;
        DisplayContent display = confirmation.getDisplayContent();
        Task focusedRoot = display == null ? null : display.getFocusedRootTask();
        ActivityRecord focused = focusedRoot == null ? null
                : focusedRoot.getTopNonFinishingActivity();
        // Device-credential authentication may use a separate Settings task. Keep its PIN or
        // pattern entry in place instead of bringing the underlying confirmation above it.
        if (focused != null && focused.mUserId == challenge.userId
                && "com.android.settings".equals(focused.packageName)
                && focused.mActivityComponent.getClassName()
                        .startsWith("com.android.settings.password.Confirm")) return focused;
        Task task = confirmation.getTask();
        if (task == null) return null;
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(confirmation.getDisplayId());
        options.setDisableStartingWindow(true);
        options.setDisallowEnterPictureInPictureWhileLaunching(true);
        mService.mTaskSupervisor.findTaskToMoveToFront(task, 0, options,
                "app-lock-reuse-confirmation", false);
        return task.getTopNonFinishingActivity();
    }

    private Intent confirmIntent(Challenge challenge) {
        Bundle extras = new Bundle();
        extras.putBinder(EXTRA_CHALLENGE, challenge.token);
        return new Intent().setComponent(CONFIRM_ACTIVITY).putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    private Challenge findLaunchChallenge(String pkg, int userId) {
        for (int i = mChallenges.size() - 1; i >= 0; i--) {
            Challenge challenge = mChallenges.valueAt(i);
            if (challenge.packageName != null && challenge.userId == userId
                    && challenge.packageName.equals(pkg) && valid(challenge)) return challenge;
        }
        return null;
    }

    private void enforceTrusted() {
        if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
            throw new SecurityException("Application lock is restricted to trusted system UI");
        }
        mService.mContext.enforceCallingOrSelfPermission(PERMISSION, "Application lock");
    }

    private void enforceStateReader() {
        mService.mContext.enforceCallingOrSelfPermission(PERMISSION, "Application lock state");
        final int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId == Process.SYSTEM_UID) return;
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final ComponentName systemUi = pm == null ? null : pm.getSystemUiServiceComponent();
        final int systemUiUid = systemUi == null ? Process.INVALID_UID
                : pm.getPackageUid(systemUi.getPackageName(), PackageManager.MATCH_SYSTEM_ONLY,
                        UserHandle.USER_SYSTEM);
        // SystemUI has its own shared UID and reads media state for all current profiles.
        // A platform-signed application holding the permission is not a trusted state reader.
        if (systemUiUid < 0 || callingAppId != UserHandle.getAppId(systemUiUid)) {
            throw new SecurityException("Application lock state is restricted to system and SystemUI");
        }
    }

    IBinder beginManagement(int userId, IBinder owner) {
        enforceTrusted();
        if (owner == null || userId < 0) throw new IllegalArgumentException("Invalid owner/user");
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                UserState state = userState(userId);
                Challenge challenge = new Challenge(state, null, owner);
                requireCredential(userId, challenge.authMethod);
                try {
                    owner.linkToDeath(challenge.death, 0);
                } catch (RemoteException e) {
                    throw new IllegalStateException("Management owner is no longer alive", e);
                }
                mChallenges.put(challenge.token, challenge);
                scheduleExpiry();
                return challenge.token;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    Bundle authenticationInfo(IBinder token) {
        enforceTrusted();
        synchronized (mService.mGlobalLock) {
            Challenge challenge = requireChallenge(token);
            Bundle info = new Bundle();
            info.putInt(KEY_USER_ID, challenge.userId);
            info.putBoolean(KEY_MANAGEMENT, challenge.packageName == null);
            info.putString(KEY_PACKAGE_NAME, challenge.packageName);
            info.putInt(KEY_AUTH_METHOD, challenge.authMethod);
            if (challenge.authMethod == AUTH_PRIVACY_PASSWORD) {
                info.putInt(android.app.PrivacyPasswordManager.KEY_TYPE,
                        mService.mPrivacyPasswordController.getState(challenge.userId)
                                .getInt(android.app.PrivacyPasswordManager.KEY_TYPE));
            }
            return info;
        }
    }

    Bundle verifyPrivacyPassword(IBinder token, byte[] credential) {
        enforceTrusted();
        final Challenge challenge;
        synchronized (mService.mGlobalLock) {
            challenge = requireChallenge(token);
            if (challenge.authMethod != AUTH_PRIVACY_PASSWORD || challenge.privacyVerified) {
                throw new SecurityException("Not an outstanding privacy-password challenge");
            }
            requireCredential(challenge.userId, challenge.authMethod);
        }
        // Password derivation and attempt persistence run outside the WM lock.
        Bundle result = mService.mPrivacyPasswordController.verifyCredential(challenge.userId,
                credential);
        synchronized (mService.mGlobalLock) {
            if (requireChallenge(token) != challenge) throw new SecurityException("Challenge changed");
            if (result.getBoolean(android.app.PrivacyPasswordManager.KEY_MATCHED)) {
                challenge.privacyVerified = true;
            }
        }
        return result;
    }

    IBinder completeAuthentication(IBinder token) {
        enforceTrusted();
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                Challenge challenge = requireChallenge(token);
                requireCredential(challenge.userId, challenge.authMethod);
                if (challenge.authMethod == AUTH_PRIVACY_PASSWORD && !challenge.privacyVerified) {
                    throw new SecurityException("Privacy password has not been verified");
                }
                mChallenges.remove(token);
                unlink(challenge);
                if (challenge.packageName == null) {
                    ManagementSession session = new ManagementSession(challenge);
                    try {
                        session.owner.linkToDeath(session.death, 0);
                    } catch (RemoteException e) {
                        throw new IllegalStateException("Management owner is no longer alive", e);
                    }
                    mManagement.put(session.token, session);
                    scheduleExpiry();
                    return session.token;
                }
                UserState state = userState(challenge.userId);
                if (!state.config.protects(challenge.packageName)) {
                    throw new SecurityException("Application lock policy changed");
                }
                Grant grant = new Grant();
                grant.awaitingVisibility = true;
                grant.deadline = SystemClock.elapsedRealtime() + LAUNCH_GRACE;
                state.grants.put(challenge.packageName, grant);
                publish(state.userId);
                scheduleExpiry();
                mService.mH.post(() -> restoreLaunch(challenge));
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void restoreLaunch(Challenge challenge) {
        synchronized (mService.mGlobalLock) {
            UserState state = userState(challenge.userId);
            if (state.generation != challenge.generation
                    || isLocked(challenge.packageName, challenge.userId)) {
                cancelResult(challenge);
                return;
            }
            if (challenge.original == null) {
                Task task = mService.mRootWindowContainer.anyTaskForId(challenge.taskId,
                        RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                ActivityRecord top = task == null ? null : task.getTopNonFinishingActivity();
                if (task == null || task.mUserId != challenge.userId || top == null
                        || !challenge.packageName.equals(top.packageName)) {
                    state.grants.remove(challenge.packageName);
                    publish(state.userId);
                    return;
                }
            }
        }
        try {
            ActivityOptions options = challenge.restoreOptions == null ? ActivityOptions.makeBasic()
                    : ActivityOptions.fromBundle(new Bundle(challenge.restoreOptions));
            options.setLaunchDisplayId(challenge.displayId);
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            if (challenge.original != null) {
                int result = challenge.original.sendInner(null, 0, null, null, null, null, null,
                        challenge.resultTo, challenge.resultWho, challenge.requestCode,
                        0, 0, options.toBundle());
                if (!ActivityManager.isStartResultSuccessful(result)) {
                    throw new IllegalStateException("Authenticated activity start failed: " + result);
                }
            } else if (challenge.taskId != ActivityTaskManager.INVALID_TASK_ID) {
                int result = mService.startActivityFromRecents(challenge.taskId, options.toBundle());
                if (!ActivityManager.isStartResultSuccessful(result)) {
                    throw new IllegalStateException("Authenticated task start failed: " + result);
                }
            }
            synchronized (mService.mGlobalLock) {
                refreshWindows();
            }
        } catch (RuntimeException error) {
            Slog.w(TAG, "Unable to restore authenticated application", error);
            synchronized (mService.mGlobalLock) {
                UserState state = userState(challenge.userId);
                state.grants.remove(challenge.packageName);
                cancelResult(challenge);
                publish(state.userId);
            }
        }
    }

    void cancelAuthentication(IBinder token) {
        enforceTrusted();
        synchronized (mService.mGlobalLock) {
            Challenge challenge = mChallenges.remove(token);
            if (challenge != null) discard(challenge);
            scheduleExpiry();
        }
    }

    void cancelLaunch(IBinder token) {
        Challenge challenge = mChallenges.remove(token);
        if (challenge != null) discard(challenge);
    }

    private void cancelResult(Challenge challenge) {
        if (challenge.resultDelivered || challenge.resultTo == null || challenge.requestCode < 0) return;
        challenge.resultDelivered = true;
        ActivityRecord target = ActivityRecord.isInAnyTask(challenge.resultTo);
        if (target != null && !target.finishing) {
            target.sendResult(Process.INVALID_UID, challenge.resultWho, challenge.requestCode,
                    android.app.Activity.RESULT_CANCELED, null, null, null);
        }
    }

    void endManagement(IBinder token) {
        enforceTrusted();
        synchronized (mService.mGlobalLock) {
            removeManagement(token);
            scheduleExpiry();
        }
    }

    Bundle getSettings(int userId, IBinder token) {
        enforceTrusted();
        synchronized (mService.mGlobalLock) {
            requireManagement(userId, token);
            return userState(userId).config.toBundle();
        }
    }

    void setSettings(int userId, IBinder token, Bundle values) {
        enforceTrusted();
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                requireManagement(userId, token);
                if (values == null) throw new IllegalArgumentException("Missing application lock policy");
                Config config = new Config(values);
                UserState state = userState(userId);
                boolean changedMethod = config.authMethod != state.config.authMethod;
                if (config.enabled) requireCredential(userId, config.authMethod);
                for (String pkg : config.packages) {
                    if (!canProtect(pkg, userId)) {
                        throw new IllegalArgumentException("Cannot lock essential system package: " + pkg);
                    }
                }
                writeConfig(userId, config);
                state.config = config;
                state.generation++;
                state.grants.clear();
                invalidateLaunchChallenges(userId);
                // Existing management capabilities remain valid for editing this policy.
                if (changedMethod) invalidateCapabilities(userId);
                for (int i = 0; i < mManagement.size(); i++) {
                    ManagementSession session = mManagement.valueAt(i);
                    if (session.userId == userId) session.generation = state.generation;
                }
                publish(userId);
                invalidateSnapshots(userId);
                refreshWindows();
                scheduleExpiry();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void lockAll(int userId, IBinder token) {
        enforceTrusted();
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                requireManagement(userId, token);
                UserState state = userState(userId);
                state.grants.clear();
                invalidateLaunchChallenges(userId);
                publish(userId);
                refreshWindows();
                scheduleExpiry();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    boolean queryLocked(String pkg, int userId, boolean notifications) {
        enforceStateReader();
        return notifications ? mInternal.shouldHideNotifications(pkg, userId)
                : mInternal.isPackageLocked(pkg, userId);
    }

    private void requireCredential(int userId, int method) {
        UserManager users = mService.mContext.getSystemService(UserManager.class);
        if (userId < 0 || !mService.mAmInternal.isCurrentProfile(userId)
                || !users.isUserUnlocked(userId) || !hasCredential(userId, method)) {
            throw new IllegalStateException("A secure, unlocked user credential is required");
        }
    }

    private boolean hasCredential(int userId, int method) {
        return method == AUTH_PRIVACY_PASSWORD
                ? mService.mPrivacyPasswordController.hasPassword(userId) : hasSecureCredential(userId);
    }

    void onPrivacyPasswordChanged(int userId, boolean configured) {
        synchronized (mService.mGlobalLock) {
            UserState state = userState(userId);
            if (!configured && state.config.authMethod == AUTH_PRIVACY_PASSWORD) {
                Bundle values = state.config.toBundle();
                values.putBoolean(KEY_ENABLED, false);
                Config config = new Config(values);
                writeConfig(userId, config);
                state.config = config;
            }
            state.generation++;
            state.grants.clear();
            invalidateCapabilities(userId);
            publish(userId);
            invalidateSnapshots(userId);
            refreshWindows();
            scheduleExpiry();
        }
    }

    private boolean hasSecureCredential(int userId) {
        UserManager users = mService.mContext.getSystemService(UserManager.class);
        // Unified work profiles authenticate against the parent, whose credential can also be
        // removed. The profile's internally generated tie password is not a usable user secret.
        return mLockPatternUtils.isSecure(users.getCredentialOwnerProfile(userId));
    }

    private boolean canProtect(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255
                || pkg.equals("android") || pkg.equals("com.android.settings")
                || pkg.equals("com.android.systemui") || pkg.equals("com.android.keyguard")
                || pkg.equals("com.android.permissioncontroller")
                || pkg.equals("com.google.android.permissioncontroller")
                || pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.google.android.packageinstaller")
                || pkg.equals("com.android.certinstaller")
                || pkg.equals("com.android.emergency")) return false;
        PackageManager pm = mService.mContext.getPackageManager();
        try {
            ApplicationInfo app = pm.getApplicationInfoAsUser(pkg, 0, userId);
            if (UserHandle.getAppId(app.uid) < Process.FIRST_APPLICATION_UID) return false;
            if (pkg.equals(pm.getPermissionControllerPackageName())) return false;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo candidate : pm.queryIntentActivitiesAsUser(home, 0, userId)) {
                if (candidate.activityInfo != null
                        && pkg.equals(candidate.activityInfo.packageName)) return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Challenge requireChallenge(IBinder token) {
        Challenge challenge = token == null ? null : mChallenges.get(token);
        if (challenge == null || !valid(challenge)) {
            throw new SecurityException("Application lock authentication expired or was revoked");
        }
        return challenge;
    }

    private boolean valid(Challenge challenge) {
        return challenge.expires > SystemClock.elapsedRealtime()
                && mService.mAmInternal.isCurrentProfile(challenge.userId)
                && userState(challenge.userId).generation == challenge.generation
                && (challenge.authMethod != AUTH_PRIVACY_PASSWORD
                        || (challenge.privacyGeneration >= 0 && challenge.privacyGeneration
                                == mService.mPrivacyPasswordController.generation(challenge.userId)));
    }

    private void requireManagement(int userId, IBinder token) {
        ManagementSession session = token == null ? null : mManagement.get(token);
        if (session == null || session.userId != userId
                || !mService.mAmInternal.isCurrentProfile(userId)
                || session.expires <= SystemClock.elapsedRealtime()
                || session.generation != userState(userId).generation
                || (session.authMethod == AUTH_PRIVACY_PASSWORD && (session.privacyGeneration < 0
                        || session.privacyGeneration
                                != mService.mPrivacyPasswordController.generation(userId)))) {
            throw new SecurityException("Verify the device credential before managing application lock");
        }
    }

    private void removeManagement(IBinder token) {
        ManagementSession session = mManagement.remove(token);
        if (session != null) session.owner.unlinkToDeath(session.death, 0);
    }

    private void unlink(Challenge challenge) {
        if (challenge.owner != null) challenge.owner.unlinkToDeath(challenge.death, 0);
    }

    private void discard(Challenge challenge) {
        unlink(challenge);
        cancelResult(challenge);
    }

    private void invalidateLaunchChallenges(int userId) {
        for (int i = mChallenges.size() - 1; i >= 0; i--) {
            Challenge challenge = mChallenges.valueAt(i);
            if (challenge.userId == userId) {
                mChallenges.removeAt(i);
                discard(challenge);
            }
        }
    }

    private void invalidateCapabilities(int userId) {
        for (int i = mChallenges.size() - 1; i >= 0; i--) {
            Challenge challenge = mChallenges.valueAt(i);
            if (userId == UserHandle.USER_ALL || challenge.userId == userId) {
                mChallenges.removeAt(i);
                discard(challenge);
            }
        }
        for (int i = mManagement.size() - 1; i >= 0; i--) {
            ManagementSession session = mManagement.valueAt(i);
            if (userId == UserHandle.USER_ALL || session.userId == userId) {
                removeManagement(session.token);
            }
        }
    }

    void onVisibilityChanged() {
        if (!mReady || mVisibilityScheduled) return;
        mVisibilityScheduled = true;
        mService.mH.post(() -> {
            synchronized (mService.mGlobalLock) {
                mVisibilityScheduled = false;
                updateVisibility();
            }
        });
    }

    private void updateVisibility() {
        SparseArray<Set<String>> visible = new SparseArray<>();
        if (!mService.isSleepingLocked()) {
            mService.mRootWindowContainer.forAllActivities(activity -> {
                Task task = activity.getTask();
                if (task != null && !task.isAnimatingByRecents() && !task.isForceHidden()
                        && isProtected(activity.packageName, activity.mUserId)
                        && ((!activity.finishing && activity.isVisibleRequested())
                                || isSamePackageFinishTransition(activity, task))) {
                    Set<String> packages = visible.get(activity.mUserId);
                    if (packages == null) {
                        packages = new HashSet<>();
                        visible.put(activity.mUserId, packages);
                    }
                    packages.add(activity.packageName);
                }
            });
        }
        for (int i = 0; i < mUsers.size(); i++) {
            UserState state = mUsers.valueAt(i);
            Set<String> packages = visible.get(state.userId);
            if (updateForeground(state, packages == null ? Collections.emptySet() : packages)) {
                publish(state.userId);
            }
        }
        scheduleExpiry();
    }

    private boolean isSamePackageFinishTransition(ActivityRecord activity, Task task) {
        // finishIfPossible hides the outgoing activity before its pause callback resumes
        // the one below. Keep an existing foreground grant across this same-package gap,
        // only while the same task/root still owns focus. Home or another app must relock.
        if (mService.isSleepingOrShuttingDownLocked() || task.shouldSleepOrShutDownActivities()
                || !activity.finishing || !activity.isState(ActivityRecord.State.PAUSING)
                || !activity.canReceiveKeys()
                || task.getTopPausingActivity() != activity || !task.isFocused()
                || !task.getRootTask().isFocusedRootTaskOnDisplay()) return false;
        UserState state = mUsers.get(activity.mUserId);
        if (state == null || !state.foreground.contains(activity.packageName)
                || !state.grants.containsKey(activity.packageName)) return false;
        ActivityRecord next = task.topRunningActivity(true /* focusableOnly */);
        return next != null && next == task.getTopNonFinishingActivity()
                && next.canReceiveKeys() && next.mUserId == activity.mUserId
                && activity.packageName.equals(next.packageName);
    }

    private boolean updateForeground(UserState state, Set<String> visible) {
        boolean changed = !state.foreground.equals(visible);
        long now = SystemClock.elapsedRealtime();
        for (int i = state.grants.size() - 1; i >= 0; i--) {
            String pkg = state.grants.keyAt(i);
            Grant grant = state.grants.valueAt(i);
            if (visible.contains(pkg)) {
                if (grant.deadline != 0 || grant.awaitingVisibility) changed = true;
                grant.deadline = 0;
                grant.awaitingVisibility = false;
            } else if (grant.awaitingVisibility) {
                if (grant.deadline <= now) {
                    state.grants.removeAt(i);
                    changed = true;
                }
            } else if (state.config.mode == MODE_IMMEDIATE) {
                state.grants.removeAt(i);
                changed = true;
            } else if (state.config.mode == MODE_TIMEOUT && grant.deadline == 0) {
                grant.deadline = now + state.config.timeoutMinutes * 60_000L;
                changed = true;
            }
        }
        state.foreground.clear();
        state.foreground.addAll(visible);
        return changed;
    }

    private final Runnable mExpire = this::expire;

    private void expire() {
        synchronized (mService.mGlobalLock) {
            long now = SystemClock.elapsedRealtime();
            for (int i = mChallenges.size() - 1; i >= 0; i--) {
                Challenge challenge = mChallenges.valueAt(i);
                if (!valid(challenge)) {
                    mChallenges.removeAt(i);
                    discard(challenge);
                }
            }
            for (int i = mManagement.size() - 1; i >= 0; i--) {
                ManagementSession session = mManagement.valueAt(i);
                if (session.expires <= now) removeManagement(session.token);
            }
            for (int i = 0; i < mUsers.size(); i++) {
                UserState state = mUsers.valueAt(i);
                boolean changed = false;
                for (int j = state.grants.size() - 1; j >= 0; j--) {
                    Grant grant = state.grants.valueAt(j);
                    if (grant.deadline > 0 && grant.deadline <= now) {
                        state.grants.removeAt(j);
                        changed = true;
                    }
                }
                if (changed) publish(state.userId);
            }
            scheduleExpiry();
        }
    }

    private void scheduleExpiry() {
        mService.mH.removeCallbacks(mExpire);
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < mChallenges.size(); i++) {
            earliest = Math.min(earliest, mChallenges.valueAt(i).expires);
        }
        for (int i = 0; i < mManagement.size(); i++) {
            earliest = Math.min(earliest, mManagement.valueAt(i).expires);
        }
        for (int i = 0; i < mUsers.size(); i++) {
            UserState state = mUsers.valueAt(i);
            for (int j = 0; j < state.grants.size(); j++) {
                long deadline = state.grants.valueAt(j).deadline;
                if (deadline > 0) earliest = Math.min(earliest, deadline);
            }
        }
        if (earliest != Long.MAX_VALUE) {
            mService.mH.postDelayed(mExpire, Math.max(1, earliest - SystemClock.elapsedRealtime()));
        }
    }

    private void refreshWindows() {
        if (!mReady || mRefreshScheduled) return;
        mRefreshScheduled = true;
        mService.mH.post(() -> {
            synchronized (mService.mGlobalLock) {
                mRefreshScheduled = false;
                mService.mRootWindowContainer.ensureActivitiesVisible();
                mService.mRootWindowContainer.resumeFocusedTasksTopActivities();
                updateVisibility();
            }
        });
    }

    private void invalidateSnapshots(int userId) {
        ArraySet<Task> tasks = new ArraySet<>();
        mService.mRootWindowContainer.forAllLeafTasks(tasks::add, true);
        tasks.addAll(mService.getRecentTasks().getRawTasks());
        for (Task task : tasks) {
            if (task.mUserId == userId && isTaskProtected(task)) {
                mService.mWindowManager.mTaskSnapshotController
                        .removeAndDeleteSnapshot(task.mTaskId, task.mUserId);
                task.onSnapshotInvalidated();
            }
        }
    }

    private void publish(int userId) {
        publishAll();
        if (mReady && mService.mRootWindowContainer != null) {
            mService.getTransitionController().clearAppLockSnapshots(this);
            mService.mRootWindowContainer.forAllActivities(activity -> {
                if (activity.mUserId == userId && isLocked(activity.packageName, userId)) {
                    activity.setVisibility(false);
                    activity.commitVisibility(false, false);
                    if (activity.mSurfaceControl != null && activity.mSurfaceControl.isValid()) {
                        // Closing/recents leashes may survive the lifecycle visibility change.
                        // Remove their app content immediately, before the challenge can draw.
                        mService.mWindowManager.mTransactionFactory.get()
                                .hide(activity.mSurfaceControl).apply();
                    }
                }
            });
            mService.mWindowManager.requestTraversal();
        }
        mService.mH.post(() -> {
            for (AppLockManagerInternal.Listener listener : mListeners) {
                try {
                    listener.onAppLockStateChanged(userId);
                } catch (RuntimeException error) {
                    Slog.w(TAG, "Application lock listener failed", error);
                }
            }
            Intent intent = new Intent(ACTION_APP_LOCK_STATE_CHANGED)
                    .putExtra(Intent.EXTRA_USER_HANDLE, userId)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mService.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, PERMISSION);
        });
    }

    private void publishAll() {
        Map<Integer, UserSnapshot> snapshot = new HashMap<>();
        for (int i = 0; i < mUsers.size(); i++) {
            UserState state = mUsers.valueAt(i);
            if (state.config.enabled) snapshot.put(state.userId, new UserSnapshot(state));
        }
        mSnapshot = Collections.unmodifiableMap(snapshot);
    }

    private UserState userState(int userId) {
        UserState state = mUsers.get(userId);
        if (state == null) {
            state = new UserState(userId, readConfig(userId));
            mUsers.put(userId, state);
        }
        return state;
    }

    private static AtomicFile policyFile(int userId) {
        return new AtomicFile(new File(Environment.getDataSystemDeDirectory(userId),
                "arkui_app_lock.json"));
    }

    private Config readConfig(int userId) {
        try {
            JSONObject object = new JSONObject(new String(policyFile(userId).readFully(),
                    StandardCharsets.UTF_8));
            Bundle values = new Bundle();
            values.putBoolean(KEY_ENABLED, object.optBoolean(KEY_ENABLED, false));
            values.putBoolean(KEY_HIDE_NOTIFICATIONS, object.optBoolean(KEY_HIDE_NOTIFICATIONS, true));
            values.putBoolean(KEY_LOCK_ON_SCREEN_OFF, object.optBoolean(KEY_LOCK_ON_SCREEN_OFF, true));
            values.putInt(KEY_LOCK_MODE, object.optInt(KEY_LOCK_MODE, MODE_IMMEDIATE));
            values.putInt(KEY_TIMEOUT_MINUTES, object.optInt(KEY_TIMEOUT_MINUTES, 5));
            values.putInt(KEY_AUTH_METHOD, object.optInt(KEY_AUTH_METHOD, AUTH_SCREEN_LOCK));
            ArrayList<String> packages = new ArrayList<>();
            JSONArray array = object.optJSONArray(KEY_PACKAGES);
            if (array != null) for (int i = 0; i < array.length(); i++) packages.add(array.getString(i));
            values.putStringArrayList(KEY_PACKAGES, packages);
            return new Config(values);
        } catch (FileNotFoundException e) {
            return new Config(new Bundle());
        } catch (IOException | JSONException | IllegalArgumentException e) {
            Slog.e(TAG, "Unable to read application lock policy for user " + userId, e);
            return new Config(new Bundle());
        }
    }

    private void writeConfig(int userId, Config config) {
        AtomicFile file = policyFile(userId);
        FileOutputStream output = null;
        try {
            JSONObject object = new JSONObject();
            object.put(KEY_ENABLED, config.enabled);
            object.put(KEY_HIDE_NOTIFICATIONS, config.hideNotifications);
            object.put(KEY_LOCK_ON_SCREEN_OFF, config.lockOnScreenOff);
            object.put(KEY_LOCK_MODE, config.mode);
            object.put(KEY_TIMEOUT_MINUTES, config.timeoutMinutes);
            object.put(KEY_AUTH_METHOD, config.authMethod);
            object.put(KEY_PACKAGES, new JSONArray(config.packages));
            output = file.startWrite();
            output.write(object.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException | JSONException e) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("Unable to save application lock policy", e);
        }
    }

    private void writeLifecycleConfig(int userId, Config config) {
        try {
            writeConfig(userId, config);
        } catch (IllegalStateException error) {
            // User and package lifecycle handling must remain effective even on a full disk.
            Slog.e(TAG, "Unable to persist application lock lifecycle change", error);
        }
    }

    private final class Challenge {
        final IBinder token = new Binder();
        final int userId;
        final long generation;
        final long expires = SystemClock.elapsedRealtime() + AUTH_TTL;
        final String packageName;
        final IBinder owner;
        final int authMethod;
        final long privacyGeneration;
        boolean privacyVerified;
        final IBinder.DeathRecipient death = () -> {
            synchronized (mService.mGlobalLock) {
                mChallenges.remove(token);
            }
        };
        PendingIntentRecord original;
        Bundle restoreOptions;
        IBinder resultTo;
        String resultWho;
        int requestCode = -1;
        boolean resultDelivered;
        int taskId = ActivityTaskManager.INVALID_TASK_ID;
        int displayId;
        boolean pendingLaunch;
        boolean retryScheduled;
        long directLaunchUntil;
        Challenge(UserState state, String pkg, IBinder binder) {
            userId = state.userId;
            generation = state.generation;
            packageName = pkg;
            owner = binder;
            int method = state.config.authMethod;
            // Management remains reachable if only the other credential exists. Protected apps
            // always use their configured method, without a screen-lock fallback for privacy.
            if (pkg == null && !hasCredential(userId, method)) {
                int other = method == AUTH_SCREEN_LOCK ? AUTH_PRIVACY_PASSWORD : AUTH_SCREEN_LOCK;
                if (hasCredential(userId, other)) method = other;
            }
            authMethod = method;
            privacyGeneration = method == AUTH_PRIVACY_PASSWORD
                    ? mService.mPrivacyPasswordController.generation(userId) : 0;
        }
    }

    private final class ManagementSession {
        final IBinder token = new Binder();
        final int userId;
        final IBinder owner;
        final long expires = SystemClock.elapsedRealtime() + MANAGEMENT_TTL;
        long generation;
        final int authMethod;
        final long privacyGeneration;
        final IBinder.DeathRecipient death = () -> {
            synchronized (mService.mGlobalLock) {
                mManagement.remove(token);
            }
        };
        ManagementSession(Challenge challenge) {
            userId = challenge.userId;
            owner = challenge.owner;
            generation = challenge.generation;
            authMethod = challenge.authMethod;
            privacyGeneration = challenge.privacyGeneration;
        }
    }

    private static final class Grant {
        boolean awaitingVisibility;
        long deadline;
    }

    private static final class UserState {
        final int userId;
        Config config;
        long generation;
        final ArrayMap<String, Grant> grants = new ArrayMap<>();
        final Set<String> foreground = new HashSet<>();
        UserState(int user, Config policy) { userId = user; config = policy; }
    }

    private static final class UserSnapshot {
        final Set<String> packages;
        final Map<String, Long> grants;
        final boolean hideNotifications;
        UserSnapshot(UserState state) {
            packages = Collections.unmodifiableSet(new HashSet<>(state.config.packages));
            Map<String, Long> unlocked = new HashMap<>();
            for (int i = 0; i < state.grants.size(); i++) {
                unlocked.put(state.grants.keyAt(i), state.grants.valueAt(i).deadline);
            }
            grants = Collections.unmodifiableMap(unlocked);
            hideNotifications = state.config.hideNotifications;
        }
        boolean isLocked(String pkg) {
            if (!packages.contains(pkg)) return false;
            Long deadline = grants.get(pkg);
            return deadline == null || (deadline > 0 && deadline <= SystemClock.elapsedRealtime());
        }
    }

    private static final class Config {
        boolean enabled;
        final boolean hideNotifications;
        final boolean lockOnScreenOff;
        final int mode;
        final int timeoutMinutes;
        final int authMethod;
        final ArrayList<String> packages;
        Config(Bundle values) {
            enabled = values.getBoolean(KEY_ENABLED, false);
            authMethod = values.getInt(KEY_AUTH_METHOD, AUTH_SCREEN_LOCK);
            if (authMethod != AUTH_SCREEN_LOCK && authMethod != AUTH_PRIVACY_PASSWORD) {
                throw new IllegalArgumentException("Invalid application lock authentication method");
            }
            hideNotifications = values.getBoolean(KEY_HIDE_NOTIFICATIONS, true);
            mode = values.getInt(KEY_LOCK_MODE, MODE_IMMEDIATE);
            if (mode < MODE_IMMEDIATE || mode > MODE_SCREEN_OFF) {
                throw new IllegalArgumentException("Invalid application lock mode");
            }
            lockOnScreenOff = mode == MODE_SCREEN_OFF
                    || values.getBoolean(KEY_LOCK_ON_SCREEN_OFF, true);
            timeoutMinutes = values.getInt(KEY_TIMEOUT_MINUTES, 5);
            if (timeoutMinutes < 1 || timeoutMinutes > 1440) {
                throw new IllegalArgumentException("Application lock timeout must be 1–1440 minutes");
            }
            ArrayList<String> input = values.getStringArrayList(KEY_PACKAGES);
            if (input != null && input.size() > 1000) {
                throw new IllegalArgumentException("Too many protected applications");
            }
            ArraySet<String> unique = new ArraySet<>();
            if (input != null) for (String pkg : input) {
                if (pkg == null || pkg.isEmpty() || pkg.length() > 255) {
                    throw new IllegalArgumentException("Invalid application package name");
                }
                unique.add(pkg);
            }
            packages = new ArrayList<>(unique);
        }
        boolean protects(String pkg) { return enabled && packages.contains(pkg); }
        Bundle toBundle() {
            Bundle values = new Bundle();
            values.putBoolean(KEY_ENABLED, enabled);
            values.putBoolean(KEY_HIDE_NOTIFICATIONS, hideNotifications);
            values.putBoolean(KEY_LOCK_ON_SCREEN_OFF, lockOnScreenOff);
            values.putInt(KEY_LOCK_MODE, mode);
            values.putInt(KEY_TIMEOUT_MINUTES, timeoutMinutes);
            values.putInt(KEY_AUTH_METHOD, authMethod);
            values.putStringArrayList(KEY_PACKAGES, new ArrayList<>(packages));
            return values;
        }
    }
}
