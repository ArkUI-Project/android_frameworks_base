/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.ScreenContentItem;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Explicit exports use original media when available; positional reads leave playback alone. */
final class RecognitionExport {
    static final class Result {
        final Uri uri;
        final String mime;
        final boolean video;
        Result(Uri uri, String mime, boolean video) { this.uri = uri; this.mime = mime; this.video = video; }
    }
    private static final class Video {
        final FileDescriptor descriptor;
        final long offset, length;
        final String mime, extension;
        Video(FileDescriptor descriptor, long offset, long length, String mime, String extension) {
            this.descriptor = descriptor; this.offset = offset; this.length = length; this.mime = mime; this.extension = extension;
        }
    }
    static Result write(Context context, Bitmap screenshot, Rect crop, ScreenContentItem source,
            int operation, BooleanSupplier cancelled) throws IOException {
        if (source != null && source.protectedContent) throw new IOException("Protected media");
        Video video = source != null && source.type == ScreenContentItem.TYPE_VIDEO && source.descriptor != null ? video(source) : null;
        if (video != null && operation == 1) throw new IOException("Video clipboard is unsupported");
        String mime = video == null ? "image/png" : video.mime;
        String name = "ArkUI_" + System.currentTimeMillis() + "." + (video == null ? "png" : video.extension);
        Bitmap image = null; Uri uri = null; File file = null;
        try {
            check(cancelled);
            if (video == null) {
                image = source != null && source.image != null && !source.image.isRecycled() ? source.image
                        : Bitmap.createBitmap(screenshot, crop.left, crop.top, crop.width(), crop.height());
            }
            if (operation == 0) {
                ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime); values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video == null ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES) + "/ArkUI");
                uri = context.getContentResolver().insert(video == null ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Cannot create media");
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) { encode(out, image, video, cancelled); }
                check(cancelled); values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            } else {
                File directory = new File(context.getCacheDir(), "extracted");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create export directory");
                File[] old = directory.listFiles();
                if (old != null) for (File previous : old) if (System.currentTimeMillis() - previous.lastModified() > 86400000) previous.delete();
                file = new File(directory, name);
                try (OutputStream out = new FileOutputStream(file)) { encode(out, image, video, cancelled); }
                check(cancelled); uri = FileProvider.getUriForFile(context, "org.arkui.recognition.images", file);
            }
            return new Result(uri, mime, video != null);
        } catch (IOException | RuntimeException e) {
            if (operation == 0 && uri != null) try { context.getContentResolver().delete(uri, null, null); }
            catch (RuntimeException cleanup) { android.util.Log.w("ArkRecognition", "Cannot remove incomplete media", cleanup); }
            if (file != null) file.delete();
            throw e;
        } finally {
            if (image != null && image != screenshot && (source == null || image != source.image)) image.recycle();
        }
    }
    private static void encode(OutputStream out, Bitmap image, Video video, BooleanSupplier cancelled) throws IOException {
        if (out == null) throw new IOException("Cannot open media");
        if (video == null) {
            check(cancelled); if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Cannot encode image");
            return;
        }
        byte[] bytes = new byte[64 * 1024]; long copied = 0;
        while (copied < video.length) {
            check(cancelled);
            int read = pread(video.descriptor, bytes, (int) Math.min(bytes.length, video.length - copied), video.offset + copied);
            if (read <= 0) throw new IOException("Truncated media");
            out.write(bytes, 0, read); copied += read;
        }
    }
    private static Video video(ScreenContentItem source) throws IOException {
        FileDescriptor fd = source.descriptor.getFileDescriptor();
        try {
            var stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode) || source.offset < 0 || source.offset >= stat.st_size) throw new IOException("Media is not a complete local file");
            long available = stat.st_size - source.offset;
            long length = source.length == -1 ? available : source.length;
            if (length <= 0 || length > available) throw new IOException("Invalid media range");
            byte[] header = new byte[(int) Math.min(512, length)];
            int count = pread(fd, header, header.length, source.offset);
            String mime, extension;
            if (count >= 12 && ascii(header, 4, 4).equals("ftyp")) {
                String brand = ascii(header, 8, 4);
                if (brand.equals("qt  ")) { mime = "video/quicktime"; extension = "mov"; }
                else if (brand.startsWith("3gp")) { mime = "video/3gpp"; extension = "3gp"; }
                else if (brand.startsWith("3g2")) { mime = "video/3gpp2"; extension = "3g2"; }
                else { mime = "video/mp4"; extension = "mp4"; }
            }
            else if (count >= 4 && (header[0] & 255) == 0x1a && (header[1] & 255) == 0x45 && (header[2] & 255) == 0xdf && (header[3] & 255) == 0xa3) {
                String doc = ascii(header, 0, count);
                if (doc.contains("webm")) { mime = "video/webm"; extension = "webm"; }
                else if (doc.contains("matroska")) { mime = "video/x-matroska"; extension = "mkv"; }
                else throw new IOException("Unknown video container");
            } else if (count >= 12 && ascii(header, 0, 4).equals("RIFF") && ascii(header, 8, 4).equals("AVI ")) { mime = "video/avi"; extension = "avi"; }
            else if (count >= 4 && ascii(header, 0, 4).equals("OggS") && ("video/ogg".equals(source.mimeType)
                    || ascii(header, 0, count).contains("theora"))) { mime = "video/ogg"; extension = "ogv"; }
            else if (count >= 376 && (header[0] & 255) == 0x47 && (header[188] & 255) == 0x47) { mime = "video/mp2t"; extension = "ts"; }
            else throw new IOException("Unknown video container");
            return new Video(fd, source.offset, length, mime, extension);
        } catch (ErrnoException e) { throw new IOException("Cannot inspect media", e); }
    }
    private static int pread(FileDescriptor fd, byte[] bytes, int count, long offset) throws IOException {
        while (true) {
            try { return Os.pread(fd, bytes, 0, count, offset); }
            catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) throw new IOException("Cannot read media", e);
            }
        }
    }
    private static String ascii(byte[] bytes, int offset, int count) { return new String(bytes, offset, count, StandardCharsets.ISO_8859_1); }
    private static void check(BooleanSupplier cancelled) throws IOException { if (cancelled.getAsBoolean()) throw new IOException("Export cancelled"); }
}
