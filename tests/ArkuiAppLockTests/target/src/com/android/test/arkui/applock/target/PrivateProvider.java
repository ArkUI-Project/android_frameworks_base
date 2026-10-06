/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock.target;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;

/** Private content is accessible by another UID only through the original URI grant. */
public class PrivateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write("icon".equals(uri.getLastPathSegment())
                        ? android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
                        : "original-private-uri-grant".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) {
        return "icon".equals(uri.getLastPathSegment()) ? "image/png" : "text/plain";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
}
