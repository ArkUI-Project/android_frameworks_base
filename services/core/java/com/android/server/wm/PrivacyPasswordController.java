/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.wm;

import static android.app.PrivacyPasswordManager.*;

import android.app.AppLockManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.SparseArray;
import android.util.Slog;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** System-owned credentials and throttling. Password derivation never holds the WM global lock. */
final class PrivacyPasswordController {
    private static final int ITERATIONS = 210_000;
    private static final long AUTH_TTL = 5 * 60_000L;
    private static final long MAX_COOLDOWN = 5 * 60_000L;
    private final ActivityTaskManagerService mService;
    private final Object mLock = new Object();
    private final SparseArray<Credential> mCredentials = new SparseArray<>();
    private final ArrayMap<IBinder, Authorization> mAuthorizations = new ArrayMap<>();
    private final SecureRandom mRandom = new SecureRandom();

    PrivacyPasswordController(ActivityTaskManagerService service) { mService = service; }

    private void enforceTrusted() {
        if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
            throw new SecurityException("Privacy credentials are restricted to trusted Settings UI");
        }
        mService.mContext.enforceCallingOrSelfPermission(AppLockManager.PERMISSION,
                "Privacy password");
    }

    private void requireUser(int userId) {
        UserManager users = mService.mContext.getSystemService(UserManager.class);
        if (userId < 0 || !mService.mAmInternal.isCurrentProfile(userId)
                || !users.isUserUnlocked(userId) || users.isManagedProfile(userId)) {
            throw new SecurityException("Privacy authentication requires this unlocked user");
        }
    }

    Bundle getState(int userId) {
        enforceTrusted();
        requireUser(userId);
        synchronized (mLock) {
            Credential state = credential(userId);
            Bundle result = new Bundle();
            result.putBoolean(KEY_CONFIGURED, state.hash != null);
            result.putInt(KEY_TYPE, state.type);
            result.putLong(KEY_RETRY_MILLIS, remaining(state));
            return result;
        }
    }

    boolean hasPassword(int userId) {
        synchronized (mLock) {
            try { return credential(userId).hash != null; }
            catch (IllegalStateException error) {
                Slog.e("PrivacyPassword", "Privacy credential unavailable for user " + userId);
                return true; // Keep dependent apps protected if credential storage is damaged.
            }
        }
    }

    long generation(int userId) {
        synchronized (mLock) {
            try { return credential(userId).generation; }
            catch (IllegalStateException error) { return -1; }
        }
    }

    IBinder beginAuthentication(int userId, IBinder owner) {
        enforceTrusted();
        requireUser(userId);
        synchronized (mLock) {
            Credential state = credential(userId);
            if (state.hash == null) throw new IllegalStateException("Set a privacy password first");
            return createAuthorization(userId, owner, state.generation, false).token;
        }
    }

    Bundle verify(IBinder token, byte[] password) {
        enforceTrusted();
        final Authorization authorization;
        synchronized (mLock) {
            authorization = requireAuthorization(token, false);
            if (authorization.verified) throw new SecurityException("Challenge already redeemed");
        }
        requireUser(authorization.userId);
        Bundle result = verifyCredential(authorization.userId, password);
        synchronized (mLock) {
            requireAuthorization(token, false);
            if (result.getBoolean(KEY_MATCHED)) {
                authorization.verified = true;
                result.putBinder(KEY_TOKEN, token);
            }
        }
        return result;
    }

    /** Also used for app-lock challenges; their caller and lifetime are checked by AppLockController. */
    Bundle verifyCredential(int userId, byte[] password) {
        Credential snapshot;
        synchronized (mLock) {
            snapshot = credential(userId);
            if (snapshot.hash == null) throw new IllegalStateException("Privacy password unavailable");
            if (remaining(snapshot) > 0 || snapshot.verifying) {
                if (password != null) Arrays.fill(password, (byte) 0);
                return result(false, Math.max(remaining(snapshot), 1000), snapshot.generation);
            }
            snapshot.verifying = true;
        }
        boolean matches = false;
        byte[] candidate = null;
        try {
            candidate = derive(password, snapshot.salt);
            matches = MessageDigest.isEqual(candidate, snapshot.hash);
        } catch (IllegalArgumentException ignored) {
            // Invalid input counts as a failed attempt and never becomes a setup operation.
        } catch (RuntimeException error) {
            synchronized (mLock) { snapshot.verifying = false; }
            throw error;
        } finally {
            if (password != null) Arrays.fill(password, (byte) 0);
            if (candidate != null) Arrays.fill(candidate, (byte) 0);
        }
        synchronized (mLock) {
            snapshot.verifying = false;
            if (credential(userId) != snapshot) throw new SecurityException("Credential changed");
            if (matches) {
                if (snapshot.failures != 0 || snapshot.cooldown != 0) {
                    snapshot.failures = 0;
                    snapshot.cooldown = 0;
                    snapshot.deadline = 0;
                    persist(userId, snapshot);
                }
            } else {
                snapshot.failures = Math.min(30, snapshot.failures + 1);
                if (snapshot.failures >= 5) {
                    snapshot.cooldown = Math.min(MAX_COOLDOWN,
                            30_000L << Math.min(4, snapshot.failures - 5));
                    snapshot.deadline = SystemClock.elapsedRealtime() + snapshot.cooldown;
                    scheduleCooldown(userId, snapshot);
                }
                persist(userId, snapshot);
            }
            return result(matches, remaining(snapshot), snapshot.generation);
        }
    }

    IBinder setPassword(int userId, IBinder owner, IBinder token, int type, byte[] password) {
        enforceTrusted();
        requireUser(userId);
        if (owner == null) throw new IllegalArgumentException("Missing authentication owner");
        final long oldGeneration;
        synchronized (mLock) {
            Credential current = credential(userId);
            if (current.hash != null) requireForUser(userId, token);
            oldGeneration = current.generation;
        }
        byte[] salt = new byte[32];
        mRandom.nextBytes(salt);
        byte[] hash;
        try {
            validate(password, type);
            hash = derive(password, salt);
        } finally {
            if (password != null) Arrays.fill(password, (byte) 0);
        }
        IBinder session;
        synchronized (mLock) {
            Credential current = credential(userId);
            if (current.generation != oldGeneration) throw new SecurityException("Credential changed");
            if (current.hash != null) requireForUser(userId, token);
            Credential replacement = new Credential(type, salt, hash, oldGeneration + 1);
            persist(userId, replacement);
            invalidateLocked(userId);
            mCredentials.put(userId, replacement);
            session = createAuthorization(userId, owner, replacement.generation, true).token;
        }
        mService.mAppLockController.onPrivacyPasswordChanged(userId, true);
        return session;
    }

    void clearPassword(int userId, IBinder token) {
        enforceTrusted();
        requireUser(userId);
        synchronized (mLock) {
            requireForUser(userId, token);
            Credential empty = new Credential(TYPE_PIN, null, null, credential(userId).generation + 1);
            // An empty record preserves generation, rather than allowing an old token to revive.
            persist(userId, empty);
            invalidateLocked(userId);
            mCredentials.put(userId, empty);
        }
        mService.mAppLockController.onPrivacyPasswordChanged(userId, false);
    }

    void endAuthentication(IBinder token) {
        enforceTrusted();
        synchronized (mLock) {
            Authorization session = mAuthorizations.get(token);
            if (session != null && session.uid != Binder.getCallingUid()) {
                throw new SecurityException("Authentication belongs to another caller");
            }
            removeAuthorization(token);
        }
    }

    void invalidate(int userId, boolean removed) {
        synchronized (mLock) {
            invalidateLocked(userId);
            if (removed) {
                file(userId).delete();
                mCredentials.remove(userId);
            }
        }
    }

    private void invalidateLocked(int userId) {
        for (int i = mAuthorizations.size() - 1; i >= 0; i--) {
            Authorization session = mAuthorizations.valueAt(i);
            if (userId == UserHandle.USER_ALL || session.userId == userId) {
                removeAuthorization(session.token);
            }
        }
    }

    private Authorization createAuthorization(int userId, IBinder owner, long generation,
            boolean verified) {
        if (owner == null) throw new IllegalArgumentException("Missing authentication owner");
        for (int i = mAuthorizations.size() - 1; i >= 0; i--) {
            if (mAuthorizations.valueAt(i).expires <= SystemClock.elapsedRealtime()) {
                removeAuthorization(mAuthorizations.keyAt(i));
            }
        }
        if (mAuthorizations.size() >= 128) throw new IllegalStateException("Too many challenges");
        Authorization authorization = new Authorization(userId, owner, generation, verified);
        try { owner.linkToDeath(authorization.death, 0); }
        catch (RemoteException e) { throw new IllegalStateException("Authentication owner died", e); }
        mAuthorizations.put(authorization.token, authorization);
        mService.mH.postDelayed(() -> {
            synchronized (mLock) { removeAuthorization(authorization.token); }
        }, AUTH_TTL);
        return authorization;
    }

    private Authorization requireAuthorization(IBinder token, boolean verified) {
        Authorization session = mAuthorizations.get(token);
        if (session == null || session.uid != Binder.getCallingUid()
                || session.expires <= SystemClock.elapsedRealtime() || !session.owner.isBinderAlive()
                || session.generation != credential(session.userId).generation
                || (verified && !session.verified)) {
            throw new SecurityException("Verify the privacy password before continuing");
        }
        return session;
    }

    private void requireForUser(int userId, IBinder token) {
        if (requireAuthorization(token, true).userId != userId) {
            throw new SecurityException("Authentication belongs to another user");
        }
    }

    private void removeAuthorization(IBinder token) {
        Authorization session = mAuthorizations.remove(token);
        if (session != null) session.owner.unlinkToDeath(session.death, 0);
    }

    private static long remaining(Credential state) {
        return Math.max(0, state.deadline - SystemClock.elapsedRealtime());
    }

    private static Bundle result(boolean matched, long retryMillis, long generation) {
        Bundle result = new Bundle();
        result.putBoolean(KEY_MATCHED, matched);
        result.putLong(KEY_RETRY_MILLIS, retryMillis);
        result.putLong(KEY_GENERATION, generation);
        return result;
    }

    private void scheduleCooldown(int userId, Credential state) {
        final long deadline = state.deadline;
        mService.mH.postDelayed(() -> {
            synchronized (mLock) {
                if (mCredentials.get(userId) != state || state.deadline != deadline) return;
                state.cooldown = 0;
                try { persist(userId, state); }
                catch (IllegalStateException error) {
                    Slog.w("PrivacyPassword", "Could not persist the end of a cooldown", error);
                }
            }
        }, remaining(state) + 20);
    }

    private Credential credential(int userId) {
        Credential state = mCredentials.get(userId);
        if (state != null) return state;
        try {
            JSONObject object = new JSONObject(new String(file(userId).readFully(),
                    StandardCharsets.UTF_8));
            if (object.getInt("version") != 1) throw new IllegalStateException("Unknown credential version");
            boolean configured = object.getBoolean(KEY_CONFIGURED);
            byte[] salt = configured ? Base64.decode(object.getString("salt"), Base64.NO_WRAP) : null;
            byte[] hash = configured ? Base64.decode(object.getString("hash"), Base64.NO_WRAP) : null;
            if (configured && (salt.length != 32 || hash.length != 32)) {
                throw new IllegalStateException("Invalid credential record");
            }
            state = new Credential(object.getInt(KEY_TYPE), salt, hash, object.getLong(KEY_GENERATION));
            state.failures = Math.min(30, Math.max(0, object.getInt("failures")));
            state.cooldown = Math.min(MAX_COOLDOWN, Math.max(0, object.getLong("cooldown")));
            // Resume persisted throttling with monotonic time. Reboot or wall-clock changes cannot
            // turn an outstanding cooldown into another immediate batch of password guesses.
            state.deadline = SystemClock.elapsedRealtime() + state.cooldown;
        } catch (FileNotFoundException e) {
            state = new Credential(TYPE_PIN, null, null, 0);
        } catch (Exception e) {
            // Corruption must never be treated as an unset password that can be replaced freely.
            throw new IllegalStateException("Privacy credential storage unavailable", e);
        }
        mCredentials.put(userId, state);
        if (state.cooldown > 0) scheduleCooldown(userId, state);
        return state;
    }

    private static AtomicFile file(int userId) {
        return new AtomicFile(new File(Environment.getDataSystemDeDirectory(userId),
                "arkui_privacy_password.json"));
    }

    private static void persist(int userId, Credential state) {
        AtomicFile file = file(userId);
        FileOutputStream output = null;
        try {
            JSONObject object = new JSONObject();
            object.put("version", 1).put(KEY_CONFIGURED, state.hash != null)
                    .put(KEY_TYPE, state.type).put(KEY_GENERATION, state.generation)
                    .put("failures", state.failures).put("cooldown", state.cooldown);
            if (state.hash != null) {
                object.put("salt", Base64.encodeToString(state.salt, Base64.NO_WRAP));
                object.put("hash", Base64.encodeToString(state.hash, Base64.NO_WRAP));
            }
            output = file.startWrite();
            FileUtils.setPermissions(output.getFD(), 0600, -1, -1);
            output.write(object.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("Could not save privacy password", e);
        }
    }

    private static char[] decode(byte[] password) {
        if (password == null || password.length == 0 || password.length > 256) {
            throw new IllegalArgumentException("Invalid password length");
        }
        try {
            CharBuffer buffer = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(password));
            char[] chars = new char[buffer.remaining()];
            buffer.get(chars);
            return chars;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid password encoding"); }
    }

    private static void validate(byte[] password, int type) {
        char[] chars = decode(password);
        try {
            if (type == TYPE_PIN) {
                if (chars.length != 6) throw new IllegalArgumentException("Use a six-digit privacy PIN");
                for (char c : chars) if (c < '0' || c > '9') throw new IllegalArgumentException("PIN must be numeric");
            } else if (type != TYPE_PASSWORD || chars.length < 8 || chars.length > 64) {
                throw new IllegalArgumentException("Privacy password must have 8–64 characters");
            }
        } finally { Arrays.fill(chars, '\0'); }
    }

    private static byte[] derive(byte[] password, byte[] salt) {
        char[] chars = decode(password);
        PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, 256);
        Arrays.fill(chars, '\0');
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("Privacy password derivation unavailable", e); }
        finally { spec.clearPassword(); }
    }

    private static final class Credential {
        final int type;
        final byte[] salt;
        final byte[] hash;
        final long generation;
        int failures;
        long cooldown;
        long deadline;
        boolean verifying;
        Credential(int kind, byte[] randomSalt, byte[] verifier, long revision) {
            type = kind; salt = randomSalt; hash = verifier; generation = revision;
        }
    }

    private final class Authorization {
        final IBinder token = new Binder();
        final int userId;
        final int uid = Binder.getCallingUid();
        final IBinder owner;
        final long generation;
        final long expires = SystemClock.elapsedRealtime() + AUTH_TTL;
        boolean verified;
        final IBinder.DeathRecipient death = () -> {
            synchronized (mLock) { mAuthorizations.remove(token); }
        };
        Authorization(int user, IBinder binder, long revision, boolean authenticated) {
            userId = user; owner = binder; generation = revision; verified = authenticated;
        }
    }
}
