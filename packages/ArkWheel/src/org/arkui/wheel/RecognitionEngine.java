/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** One cancellable OCR session. Native work and model installation never run on the UI thread. */
final class RecognitionEngine {
    private static final Object MODEL_INSTALL = new Object();
    private final Context context;
    private volatile boolean cancelled;
    private TessBaseAPI active;
    RecognitionEngine(Context c) { context = c.getApplicationContext(); }
    synchronized void cancel() {
        cancelled = true;
        if (active != null) active.stop();
    }
    RecognitionResult recognize(Bitmap bitmap) throws IOException {
        long startTime = SystemClock.uptimeMillis();
        File root = new File(context.getFilesDir(), "ocr-v1");
        synchronized (MODEL_INSTALL) {
            File models = new File(root, "tessdata");
            if (!models.isDirectory() && !models.mkdirs()) throw new IOException("Cannot create OCR directory");
            for (String name : new String[] {"eng", "chi_sim", "chi_tra"}) {
                if (cancelled) return null;
                File target = new File(models, name + ".traineddata");
                if (target.isFile()) continue;
                File temporary = new File(models, name + ".tmp");
                try (var in = context.getAssets().open("tessdata/" + name + ".traineddata");
                        var out = new FileOutputStream(temporary)) {
                    in.transferTo(out); out.getFD().sync();
                }
                if (!temporary.renameTo(target)) throw new IOException("Cannot install OCR model");
            }
        }
        TessBaseAPI tess = new TessBaseAPI();
        try {
            String language = new WheelPreferences(context).get("ocr_language", 0, 0, 2) == 1 ? "chi_tra+eng" : "chi_sim+eng";
            if (new WheelPreferences(context).get("ocr_language", 0, 0, 2) == 2) language = "eng";
            if (!tess.init(root.getAbsolutePath(), language, TessBaseAPI.OEM_LSTM_ONLY)) throw new IOException("Cannot initialize OCR");
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            tess.setVariable("user_defined_dpi", "300");
            tess.setImage(bitmap);
            synchronized (this) { if (cancelled) return null; active = tess; }
            tess.getHOCRText(0);
            if (cancelled) return null;
            StringBuilder text = new StringBuilder();
            List<RecognitionResult.Glyph> glyphs = new ArrayList<>();
            ResultIterator iterator = tess.getResultIterator();
            int line = -1;
            Rect previousLine = new Rect();
            if (iterator != null) {
                try {
                    iterator.begin();
                    do {
                        if (cancelled) return null;
                        String value = iterator.getUTF8Text(RIL_SYMBOL);
                        if (value == null || value.isBlank() || iterator.confidence(RIL_WORD) < 35) continue;
                        Rect bounds = iterator.getBoundingRect(RIL_SYMBOL);
                        if (!bounds.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()) || bounds.height() < 5) continue;
                        Rect currentLine = iterator.getBoundingRect(RIL_TEXTLINE);
                        boolean newLine = !currentLine.equals(previousLine);
                        if (newLine || line < 0) {
                            if (text.length() > 0) text.append('\n');
                            line++; previousLine.set(currentLine);
                        } else if (iterator.isAtBeginningOf(RIL_WORD) && text.length() > 0
                                && !cjk(text.codePointBefore(text.length())) && !cjk(value.codePointAt(0))) text.append(' ');
                        int start = text.length(); text.append(value);
                        glyphs.add(new RecognitionResult.Glyph(bounds, start, text.length(), line));
                    } while (iterator.next(RIL_SYMBOL));
                } finally { iterator.delete(); }
            }
            if (cancelled) return null;
            List<Rect> images = ImageRegions.detect(bitmap, glyphs);
            return new RecognitionResult(text.toString(), glyphs, images, SystemClock.uptimeMillis() - startTime);
        } finally {
            synchronized (this) { active = null; tess.recycle(); }
        }
    }
    private static boolean cjk(int cp) { return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN; }
}
