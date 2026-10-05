/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One cancellable OCR session. Native work and model installation never run on the UI thread. */
final class RecognitionEngine {
    private static final Object MODEL_INSTALL = new Object();
    private static final int MAX_RETRY_LINES = 6;
    private static final int MAX_RETRY_PIXELS = 1_500_000;
    private static final long RETRY_TIME_MS = 1500;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final Context context;
    private final boolean useLexicon, useAccurateModels;
    private volatile boolean cancelled;
    private TessBaseAPI active;
    private long passGeneration;
    private ScheduledFuture<?> cancellationStop;
    private boolean retryExpired;
    RecognitionEngine(Context c) { this(c, true, true); }
    // Package-private controls allow pixel benchmarks to distinguish model and lexical gains.
    RecognitionEngine(Context c, boolean lexicon, boolean accurateModels) {
        context = c.getApplicationContext(); useLexicon = lexicon; useAccurateModels = accurateModels;
    }
    synchronized void cancel() {
        cancelled = true;
        if (active != null) {
            active.stop();
            long generation = passGeneration;
            // Native recognition initializes its monitor after active is published. Repeating
            // the stop closes that race, including the initial pass which has no retry deadline.
            if (cancellationStop == null) cancellationStop = DEADLINES.scheduleWithFixedDelay(() -> {
                synchronized (RecognitionEngine.this) {
                    if (cancelled && active != null && passGeneration == generation) active.stop();
                }
            }, 20, 20, TimeUnit.MILLISECONDS);
        }
    }
    RecognitionResult recognize(Bitmap bitmap) throws IOException {
        long startTime = SystemClock.uptimeMillis();
        int choice = new WheelPreferences(context).get("ocr_language", 0, 0, 2);
        String chinese = choice == 1 ? "chi_tra" : "chi_sim";
        String language = choice == 2 ? "eng" : chinese + "+eng";
        // Version the installed assets so an upgraded APK never silently reuses old weights.
        File root = new File(context.getFilesDir(), "ocr-v2-e12c65a");
        synchronized (MODEL_INSTALL) {
            File models = new File(root, "tessdata");
            if (!models.isDirectory() && !models.mkdirs()) throw new IOException("Cannot create OCR directory");
            for (String name : choice == 2 ? new String[] {"eng", "eng_best"}
                    : new String[] {"eng", "eng_best", chinese}) {
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
        TessBaseAPI accurate = null;
        String accurateLanguage = null;
        File lexicon = null;
        try {
            if (cancelled) return null;
            if (useLexicon) lexicon = RecognitionLexicon.create(context, () -> cancelled);
            if (cancelled) return null;
            Map<String, String> parameters = lexicon == null ? Map.of()
                    : Map.of("user_words_file", lexicon.getAbsolutePath());
            // LSTM loads the word trie during init; changing the variable afterwards has no effect.
            if (!tess.init(root.getAbsolutePath(), language, TessBaseAPI.OEM_LSTM_ONLY, parameters)) throw new IOException("Cannot initialize OCR");
            tess.setVariable("user_defined_dpi", "300");
            List<Line> lines = read(tess, bitmap, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), 1, 1, 0, 0);
            if (cancelled) return null;
            if (lines == null) lines = new ArrayList<>();
            // A screenshot contains photos and disconnected labels, so keep sparse segmentation
            // for discovery. Only uncertain/small text lines receive the more expensive line pass.
            List<Line> difficult = new ArrayList<>(lines);
            difficult.removeIf(line -> !line.needsRetry());
            difficult.sort(Comparator.comparingDouble(Line::quality));
            long deadline = SystemClock.uptimeMillis() + RETRY_TIME_MS;
            int pixels = 0, attempts = 0;
            for (Line line : difficult) {
                if (cancelled) return null;
                if (attempts >= MAX_RETRY_LINES || SystemClock.uptimeMillis() >= deadline) break;
                Rect crop = new Rect(line.bounds);
                int margin = Math.max(4, Math.min(12, crop.height() / 4));
                crop.inset(-margin, -margin);
                crop.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                float scale = Math.max(1, Math.min(3, 32f / line.medianHeight()));
                int border = 10;
                int width = Math.round(crop.width() * scale) + border * 2;
                int height = Math.round(crop.height() * scale) + border * 2;
                long area = (long) width * height;
                if (area > MAX_RETRY_PIXELS - pixels) continue;
                pixels += (int) area; attempts++;
                TessBaseAPI retry = tess;
                if (line.hasLatin() || line.latinOnly()) {
                    String english = useAccurateModels ? "eng_best" : "eng";
                    String retryLanguage = line.latinOnly() || choice == 2
                            ? english : english + "+" + chinese;
                    if (accurate == null) accurate = new TessBaseAPI();
                    if (!retryLanguage.equals(accurateLanguage)) {
                        if (!accurate.init(root.getAbsolutePath(), retryLanguage, TessBaseAPI.OEM_LSTM_ONLY, parameters)) {
                            accurate.recycle(); accurate = null; accurateLanguage = null; continue;
                        }
                        accurateLanguage = retryLanguage;
                        accurate.setVariable("user_defined_dpi", "300");
                    }
                    retry = accurate;
                }
                Bitmap prepared = prepare(bitmap, crop, width, height, border);
                try {
                    List<Line> candidates = read(retry, prepared,
                            TessBaseAPI.PageSegMode.PSM_SINGLE_LINE, crop,
                            (width - border * 2f) / crop.width(),
                            (height - border * 2f) / crop.height(), border, deadline);
                    if (candidates != null && candidates.size() == 1) {
                        Line candidate = candidates.get(0);
                        // Confidence alone favors a shorter fragment. Require comparable geometric
                        // coverage as well, so a confident "OCR" cannot erase a longer mixed line.
                        if (candidate.betterThan(line, retry == accurate)) lines.set(lines.indexOf(line), candidate);
                    }
                } finally { prepared.recycle(); }
            }
            if (cancelled) return null;
            StringBuilder text = new StringBuilder();
            List<RecognitionResult.Glyph> glyphs = new ArrayList<>();
            int index = 0;
            for (Line line : lines) {
                boolean first = true;
                for (Symbol symbol : line.symbols) {
                    // Keep independently confident characters when a single uncertain symbol
                    // lowered the word average, without accepting an entire low-confidence word.
                    if (!symbol.accepted()) continue;
                    if (first) {
                        if (text.length() > 0) text.append('\n');
                        first = false;
                    } else if (symbol.wordStart && !cjk(text.codePointBefore(text.length()))
                            && !cjk(symbol.value.codePointAt(0))) text.append(' ');
                    int start = text.length(); text.append(symbol.value);
                    glyphs.add(new RecognitionResult.Glyph(symbol.bounds, start, text.length(), index));
                }
                if (!first) index++;
            }
            if (cancelled) return null;
            List<Rect> images = ImageRegions.detect(bitmap, glyphs);
            return new RecognitionResult(text.toString(), glyphs, images, SystemClock.uptimeMillis() - startTime);
        } finally {
            synchronized (this) {
                active = null;
                if (cancellationStop != null) {
                    cancellationStop.cancel(false); cancellationStop = null;
                }
                if (accurate != null) accurate.recycle();
                tess.recycle();
            }
            if (lexicon != null) lexicon.delete();
        }
    }

    private List<Line> read(TessBaseAPI tess, Bitmap bitmap, int mode, Rect source,
            float scaleX, float scaleY, int border, long deadline) {
        if (cancelled || deadline != 0 && SystemClock.uptimeMillis() >= deadline) return null;
        tess.clear(); tess.setPageSegMode(mode); tess.setImage(bitmap);
        final long generation;
        synchronized (this) {
            if (cancelled) return null;
            generation = ++passGeneration;
            active = tess; retryExpired = false;
        }
        // Repeating the stop also covers cancellation racing the native monitor's initialization.
        // This task exists only during a retry and is removed as soon as that pass finishes.
        var timeout = deadline == 0 ? null : DEADLINES.scheduleWithFixedDelay(() -> {
            synchronized (RecognitionEngine.this) {
                if (active == tess && passGeneration == generation) { retryExpired = true; tess.stop(); }
            }
        }, Math.max(0, deadline - SystemClock.uptimeMillis()), 20, TimeUnit.MILLISECONDS);
        try {
            tess.getHOCRText(0);
            synchronized (this) { if (cancelled || retryExpired) return null; }
            List<Line> lines = new ArrayList<>();
            ResultIterator iterator = tess.getResultIterator();
            if (iterator == null) return lines;
            try {
                iterator.begin();
                Line line = null;
                Rect previous = new Rect();
                do {
                    if (cancelled) return null;
                    String value = iterator.getUTF8Text(RIL_SYMBOL);
                    if (value == null || value.isBlank()) continue;
                    Rect bounds = map(iterator.getBoundingRect(RIL_SYMBOL), source, scaleX, scaleY, border);
                    if (bounds.isEmpty() || bounds.height() < 3) continue;
                    Rect current = iterator.getBoundingRect(RIL_TEXTLINE);
                    if (line == null || !current.equals(previous)) {
                        line = new Line(); lines.add(line); previous.set(current);
                    }
                    line.add(new Symbol(value, bounds, iterator.confidence(RIL_SYMBOL),
                            iterator.confidence(RIL_WORD), iterator.isAtBeginningOf(RIL_WORD)));
                } while (iterator.next(RIL_SYMBOL));
            } finally { iterator.delete(); }
            synchronized (this) { return cancelled || retryExpired ? null : lines; }
        } finally {
            synchronized (this) {
                if (active == tess && passGeneration == generation) {
                    active = null;
                    if (cancellationStop != null) {
                        cancellationStop.cancel(false); cancellationStop = null;
                    }
                }
            }
            if (timeout != null) timeout.cancel(false);
        }
    }

    private static Rect map(Rect bounds, Rect source, float scaleX, float scaleY, int border) {
        Rect mapped = new Rect(source.left + (int) Math.floor((bounds.left - border) / scaleX),
                source.top + (int) Math.floor((bounds.top - border) / scaleY),
                source.left + (int) Math.ceil((bounds.right - border) / scaleX),
                source.top + (int) Math.ceil((bounds.bottom - border) / scaleY));
        if (!mapped.intersect(source)) mapped.setEmpty();
        return mapped;
    }

    private static Bitmap prepare(Bitmap bitmap, Rect crop, int width, int height, int border) {
        // Sample the crop border rather than text strokes. Normalize dark cards to the same
        // polarity as light cards before adding a white border; do not binarize thin strokes.
        int[] luminance = new int[20];
        for (int i = 0; i < 10; i++) {
            int x = crop.left + (crop.width() - 1) * i / 9;
            luminance[i * 2] = luminance(bitmap.getPixel(x, crop.top));
            luminance[i * 2 + 1] = luminance(bitmap.getPixel(x, crop.bottom - 1));
        }
        java.util.Arrays.sort(luminance);
        boolean invert = luminance[luminance.length / 2] < 128;
        float sign = invert ? -1 : 1;
        float offset = invert ? 255 : 0;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                .299f * sign, .587f * sign, .114f * sign, 0, offset,
                .299f * sign, .587f * sign, .114f * sign, 0, offset,
                .299f * sign, .587f * sign, .114f * sign, 0, offset,
                0, 0, 0, 1, 0})));
        Bitmap prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(prepared); canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, crop, new Rect(border, border, width - border, height - border), paint);
        return prepared;
    }

    private static int luminance(int color) {
        return (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000;
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ArkRecognitionDeadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class Symbol {
        final String value;
        final Rect bounds;
        final float confidence, wordConfidence;
        final boolean wordStart;
        Symbol(String value, Rect bounds, float confidence, float wordConfidence, boolean wordStart) {
            this.value = value; this.bounds = bounds; this.confidence = confidence;
            this.wordConfidence = wordConfidence; this.wordStart = wordStart;
        }
        boolean accepted() { return wordConfidence >= 35 || confidence >= 65; }
    }

    private static final class Line {
        final List<Symbol> symbols = new ArrayList<>();
        final Rect bounds = new Rect();
        void add(Symbol symbol) { symbols.add(symbol); bounds.union(symbol.bounds); }
        int medianHeight() {
            int[] heights = symbols.stream().mapToInt(s -> s.bounds.height()).sorted().toArray();
            return Math.max(1, heights[heights.length / 2]);
        }
        float quality() {
            float total = 0;
            for (Symbol symbol : symbols) total += symbol.confidence;
            return total / symbols.size();
        }
        boolean needsRetry() {
            return symbols.size() >= 2 && (medianHeight() < 22
                    || symbols.stream().anyMatch(s -> s.wordConfidence < 65) || mixedCaseName());
        }
        boolean mixedCaseName() {
            int upper = 0, lower = 0;
            for (Symbol symbol : symbols) {
                if (symbol.wordStart) {
                    if (upper >= 2 && lower > 0) return true;
                    upper = lower = 0;
                }
                upper += (int) symbol.value.codePoints().filter(Character::isUpperCase).count();
                lower += (int) symbol.value.codePoints().filter(Character::isLowerCase).count();
            }
            return upper >= 2 && lower > 0;
        }
        boolean hasLatin() {
            return symbols.stream().anyMatch(s -> s.value.codePoints()
                    .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN));
        }
        boolean latinOnly() {
            int latin = 0;
            for (Symbol symbol : symbols) {
                if (symbol.value.codePoints().anyMatch(RecognitionEngine::cjk)) return false;
                if (symbol.value.codePoints().anyMatch(Character::isLetterOrDigit)) latin++;
            }
            return latin >= 3;
        }
        boolean betterThan(Line original, boolean accurateModel) {
            int accepted = (int) symbols.stream().filter(Symbol::accepted).count();
            int oldAccepted = (int) original.symbols.stream().filter(Symbol::accepted).count();
            Rect overlap = new Rect(bounds);
            if (!overlap.intersect(original.bounds)
                    || overlap.width() < original.bounds.width() * .8f) return false;
            return (quality() >= original.quality() + 3 && accepted >= oldAccepted)
                    || (accepted > oldAccepted && quality() >= original.quality() - 3)
                    // Fast and floating-point LSTM confidences are not identically calibrated.
                    // Prefer the accurate model on a near tie, retaining the same coverage gate.
                    || (accurateModel && accepted >= oldAccepted && quality() >= original.quality() - 3);
        }
    }
    private static boolean cjk(int cp) { return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN; }
}
