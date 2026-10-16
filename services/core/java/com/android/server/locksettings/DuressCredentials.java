/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted from GrapheneOS DuressCredential and DuressCredentials (Apache-2.0):
 * https://github.com/GrapheneOS/platform_frameworks_base/tree/17/services/core/java/com/android/server/locksettings
 * ArkUI uses a bounded, versioned format and clears temporary credential hashes.
 */
package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.PIN_LENGTH_UNAVAILABLE;

import com.android.internal.widget.LockscreenCredential;
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import libcore.util.HexEncoding;

/** Device-wide duress credentials. Only salted scrypt hashes are persisted. */
final class DuressCredentials {
    static final String STORAGE_KEY = "arkui_duress_credentials";
    private static final int VERSION = 1;
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int ENCODED_LENGTH = 2 * (1 + 2 * (3 + SALT_LENGTH + HASH_LENGTH));

    private final Entry mPin;
    private final Entry mPassword;

    private DuressCredentials(Entry pin, Entry password) {
        mPin = pin;
        mPassword = password;
    }

    static void validate(LockscreenCredential credential, boolean pin) {
        if (credential == null || (pin ? !credential.isPin() : !credential.isPassword())
                || credential.size() < 4 || credential.size() > 128) {
            throw new IllegalArgumentException("Duress credentials must contain 4 to 128 characters");
        }
        credential.validateBasicRequirements();
        for (byte value : credential.getCredential()) {
            if (pin ? value < '0' || value > '9' : value < 32 || value > 126) {
                throw new IllegalArgumentException("Invalid duress credential characters");
            }
        }
    }

    static DuressCredentials create(SyntheticPasswordManager spm, LockscreenCredential pin,
            LockscreenCredential password) {
        validate(pin, true);
        validate(password, false);
        return new DuressCredentials(Entry.create(spm, pin), Entry.create(spm, password));
    }

    boolean matches(SyntheticPasswordManager spm, LockscreenCredential credential) {
        Entry entry = credential.isPin() ? mPin : credential.isPassword() ? mPassword : null;
        if (entry == null) return false;
        byte[] hash = spm.stretchLskf(credential, entry.data);
        try {
            return MessageDigest.isEqual(entry.hash, hash);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    String serialize() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            mPin.write(out);
            mPassword.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return HexEncoding.encodeToString(bytes.toByteArray());
    }

    static DuressCredentials deserialize(String encoded) {
        if (encoded.length() != ENCODED_LENGTH) {
            throw new IllegalArgumentException("Invalid duress credential record length");
        }
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(HexEncoding.decode(encoded)))) {
            if (in.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("Unsupported duress credential record");
            }
            return new DuressCredentials(Entry.read(in, CREDENTIAL_TYPE_PIN),
                    Entry.read(in, CREDENTIAL_TYPE_PASSWORD));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid duress credential record", e);
        }
    }

    private static final class Entry {
        final PasswordData data;
        final byte[] hash;

        Entry(PasswordData data, byte[] hash) {
            this.data = data;
            this.hash = hash;
        }

        static Entry create(SyntheticPasswordManager spm, LockscreenCredential credential) {
            PasswordData data = PasswordData.create(credential.getType(), PIN_LENGTH_UNAVAILABLE);
            return new Entry(data, spm.stretchLskf(credential, data));
        }

        void write(DataOutputStream out) throws IOException {
            out.writeByte(data.scryptLogN);
            out.writeByte(data.scryptLogR);
            out.writeByte(data.scryptLogP);
            out.write(data.salt);
            out.write(hash);
        }

        static Entry read(DataInputStream in, int type) throws IOException {
            PasswordData data = new PasswordData();
            data.credentialType = type;
            data.pinLength = PIN_LENGTH_UNAVAILABLE;
            data.scryptLogN = in.readByte();
            data.scryptLogR = in.readByte();
            data.scryptLogP = in.readByte();
            // Bound resource use before passing persisted parameters to scrypt.
            if (data.scryptLogN < 9 || data.scryptLogN > 11
                    || data.scryptLogR != 3 || data.scryptLogP != 1) {
                throw new IllegalArgumentException("Invalid duress scrypt parameters");
            }
            data.salt = new byte[SALT_LENGTH];
            in.readFully(data.salt);
            byte[] hash = new byte[HASH_LENGTH];
            in.readFully(hash);
            return new Entry(data, hash);
        }
    }
}
