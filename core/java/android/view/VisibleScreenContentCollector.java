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
package android.view;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.media.VisibleMediaSource;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ReplacementSpan;
import android.text.style.CharacterStyle;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Bounded, main-thread collection of content whose displayed geometry is known.
 * @hide
 */
final class VisibleScreenContentCollector {
    private static final int MAX_NODES = 1200;
    private static final int MAX_CHARACTERS = 1536;
    private static final int MAX_ITEMS = 64;
    private static final int MAX_IMAGES = 6;
    private static final int MAX_VIDEOS = 3;
    private static final int MAX_IMAGE_EDGE = 4096;
    private static final long MAX_IMAGE_PIXELS = 4 * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_PIXELS = 16 * 1024 * 1024;
    private static final long MAX_COLLECTION_MILLIS = 150;

    private final ScreenContent mResult;
    private final String mPackageName;
    private final long mDeadline;
    private int mNodes;
    private int mCharacters;
    private int mImages;
    private long mImagePixels;
    private int mVideos;

    private VisibleScreenContentCollector(ScreenContent result, String packageName) {
        mResult = result;
        mPackageName = packageName;
        mDeadline = SystemClock.uptimeMillis() + MAX_COLLECTION_MILLIS;
    }

    static ScreenContent collect(ViewRootImpl root, int displayId, int rotation,
            Rect expectedWindowBounds, Region visibleRegion) {
        ScreenContent result = new ScreenContent();
        result.displayId = displayId;
        result.rotation = rotation;
        result.displayBounds = new Rect(expectedWindowBounds);
        result.capturedAt = SystemClock.uptimeMillis();
        View view = root.getView();
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return result;
        if ((root.mWindowAttributes.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            result.status = ScreenContent.STATUS_SECURE;
            return result;
        }
        Rect actualBounds = new Rect();
        view.getBoundsOnScreen(actualBounds);
        if (root.getDisplayId() != displayId || view.getDisplay() == null
                || view.getDisplay().getRotation() != rotation
                || !root.mWinFrame.equals(expectedWindowBounds)
                || !actualBounds.equals(expectedWindowBounds)) {
            result.status = ScreenContent.STATUS_CHANGED;
            result.partial = true;
            return result;
        }
        result.status = ScreenContent.STATUS_OK;
        VisibleScreenContentCollector collector = new VisibleScreenContentCollector(result,
                view.getContext().getPackageName());
        try {
            collector.visit(view, new Region(visibleRegion));
        } catch (RuntimeException e) {
            // Application drawing and accessibility providers may fail or change while traversing.
            result.partial = true;
        }
        return result;
    }

    private boolean atLimit() {
        if (mResult.status != ScreenContent.STATUS_OK) return true;
        if (mNodes >= MAX_NODES || mCharacters >= MAX_CHARACTERS
                || mResult.items.size() >= MAX_ITEMS || SystemClock.uptimeMillis() >= mDeadline) {
            mResult.partial = true;
            return true;
        }
        return false;
    }

    private void visit(View view, Region permitted) {
        if (atLimit()) return;
        mNodes++;
        if (!view.isAttachedToWindow() || view.getVisibility() != View.VISIBLE
                || view.getAlpha() <= 0 || view.getTransitionAlpha() <= 0) return;
        if ((view.getAnimation() != null && !view.getAnimation().hasEnded())
                || view.getAlpha() < 1 || view.getTransitionAlpha() < 1) {
            mResult.partial = true;
            return;
        }
        Rect bounds = new Rect();
        view.getBoundsOnScreen(bounds, true);
        if (bounds.isEmpty() || !permitted.op(bounds, Region.Op.INTERSECT)) return;
        Matrix transform = new Matrix();
        view.transformMatrixToGlobal(transform);
        if (!transform.rectStaysRect()) {
            mResult.partial = true;
            return;
        }
        if (view.mOverlay != null && !view.mOverlay.isEmpty()) {
            // Overlay drawing can escape individual drawable bounds.
            mResult.partial = true;
            return;
        }
        RectF viewport = new RectF(0, 0, view.getWidth(), view.getHeight());
        transform.mapRect(viewport);
        if (!permitted.op(inwardRect(viewport), Region.Op.INTERSECT)) return;
        Rect clipBounds = view.getClipBounds();
        if (clipBounds != null) {
            RectF clip = new RectF(clipBounds);
            transform.mapRect(clip);
            if (!permitted.op(inwardRect(clip), Region.Op.INTERSECT)) return;
        }
        if (view.getClipToOutline() && view.getOutlineProvider() != null) {
            Outline outline = new Outline();
            view.getOutlineProvider().getOutline(view, outline);
            Rect outlineBounds = new Rect();
            if (!outline.getRect(outlineBounds)) {
                // Arbitrary outline paths cannot prove that an entire glyph or source pixel shows.
                mResult.partial = true;
                return;
            }
            Region outlineRegion = new Region();
            float radius = outline.getRadius();
            if (!Float.isFinite(radius) || radius < 0) {
                mResult.partial = true;
                return;
            }
            if (radius == 0) {
                RectF rectangle = new RectF(outlineBounds);
                transform.mapRect(rectangle);
                outlineRegion.set(inwardRect(rectangle));
            } else {
                // These two strips stay inside the rounded rect, excluding all four corner boxes.
                int inset = (int) Math.ceil(Math.min(radius,
                        Math.min(outlineBounds.width(), outlineBounds.height()) / 2f));
                RectF horizontal = new RectF(outlineBounds.left,
                        outlineBounds.top + inset, outlineBounds.right,
                        outlineBounds.bottom - inset);
                RectF vertical = new RectF(outlineBounds.left + inset, outlineBounds.top,
                        outlineBounds.right - inset, outlineBounds.bottom);
                if (!horizontal.isEmpty()) {
                    transform.mapRect(horizontal);
                    outlineRegion.set(inwardRect(horizontal));
                }
                if (!vertical.isEmpty()) {
                    transform.mapRect(vertical);
                    outlineRegion.op(inwardRect(vertical), Region.Op.UNION);
                }
                mResult.partial = true;
            }
            if (!permitted.op(outlineRegion, Region.Op.INTERSECT)) return;
        }
        Drawable foreground = view.getForeground();
        if (foreground != null && foreground.getAlpha() > 0) {
            if (foreground.getBounds().isEmpty()) {
                mResult.partial = true;
                return;
            }
            RectF foregroundBounds = new RectF(foreground.getBounds());
            foregroundBounds.offset(-view.getScrollX(), -view.getScrollY());
            transform.mapRect(foregroundBounds);
            // Transparent parts of a foreground are unknown; only its bounds occlude structure.
            mResult.partial = true;
            if (!permitted.op(outwardRect(foregroundBounds), Region.Op.DIFFERENCE)) return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            ArrayList<View> ordered = group.buildOrderedChildList();
            if (ordered != null) {
                ArrayList<View> copy = new ArrayList<>(ordered);
                ordered.clear();
                ordered = copy;
            }
            if (!group.getClipChildren()) mResult.partial = true;
            Region covered = new Region();
            final int count = group.getChildCount();
            for (int position = count - 1; position >= 0 && !atLimit(); position--) {
                int index = ordered == null && group.isChildrenDrawingOrderEnabled()
                        ? group.getChildDrawingOrder(count, position) : position;
                View child = ordered == null ? group.getChildAt(index) : ordered.get(position);
                if (child == null) continue;
                Region childRegion = new Region(permitted);
                childRegion.op(covered, Region.Op.DIFFERENCE);
                visit(child, childRegion);
                if (child.getVisibility() == View.VISIBLE && child.getAlpha() > 0
                        && child.getTransitionAlpha() > 0) {
                    Rect childBounds = new Rect();
                    child.getBoundsOnScreen(childBounds, true);
                    // Transparent/custom drawing cannot establish exact sibling occlusion. Keep
                    // the screenshot as the authority and conservatively omit content behind it.
                    if (!child.isOpaque()) mResult.partial = true;
                    covered.op(childBounds, Region.Op.UNION);
                }
            }
            return;
        }
        if (view instanceof TextView) {
            collectText((TextView) view, permitted, transform);
        } else if (view instanceof ImageView) {
            collectImage((ImageView) view, permitted, transform);
        } else if (view instanceof SurfaceView) {
            SurfaceView surface = (SurfaceView) view;
            if (surface.isSecureForScreenContent()) {
                mResult.status = ScreenContent.STATUS_SECURE;
                mResult.close();
                return;
            }
            Rect complete = visibleVideoBounds(view, permitted);
            if (complete != null && mVideos < MAX_VIDEOS) {
                addVideo(VisibleMediaSource.copyForSurface(surface.getHolder().getSurface()),
                        complete);
            } else mResult.partial = true;
        } else if (view instanceof TextureView) {
            TextureView texture = (TextureView) view;
            Rect complete = visibleVideoBounds(view, permitted);
            if (texture.isAvailable() && complete != null
                    && mVideos < MAX_VIDEOS) {
                addVideo(VisibleMediaSource.copyForTexture(texture.getSurfaceTexture()), complete);
            } else mResult.partial = true;
        } else if (view.getAccessibilityNodeProvider() != null || !view.willNotDraw()) {
            // Virtual text may be descriptive, clipped, or merged from invisible descendants.
            // Without glyph geometry, claiming it is displayed would leak hidden text.
            mResult.partial = true;
        }
    }

    static boolean fullyContains(Region region, Rect rect) {
        if (rect.isEmpty()) return false;
        Region remainder = new Region(rect);
        remainder.op(region, Region.Op.DIFFERENCE);
        return remainder.isEmpty();
    }

    private Rect visibleVideoBounds(View view, Region permitted) {
        Rect complete = new Rect(); view.getBoundsOnScreen(complete);
        Region visible = new Region(permitted); visible.op(complete, Region.Op.INTERSECT);
        if (visible.isEmpty()) {
            mResult.partial = true;
            return null;
        }
        Rect bounds = largestVisibleRectangle(visible);
        if (!bounds.equals(complete)) mResult.partial = true;
        // The selectable region is visible. An explicit save exports its complete local video,
        // including frames outside this frozen instant; system bars need not hide that action.
        return bounds;
    }

    static Rect largestVisibleRectangle(Region visible) {
        RegionIterator iterator = new RegionIterator(visible);
        Rect rectangle = new Rect();
        Rect largest = new Rect();
        long area = 0;
        while (iterator.next(rectangle)) {
            long candidate = (long) rectangle.width() * rectangle.height();
            if (candidate > area) {
                area = candidate;
                largest.set(rectangle);
            }
        }
        return largest;
    }

    private void collectText(TextView view, Region permitted, Matrix transform) {
        if (isPassword(view) || Color.alpha(view.getCurrentTextColor()) == 0
                || view.getPaint().getAlpha() == 0 || view.getPaint().getShader() != null) {
            mResult.partial = true;
            return;
        }
        Layout layout = view.getLayout();
        if (layout == null || view.getEllipsize() == TextUtils.TruncateAt.MARQUEE) {
            mResult.partial = true;
            return;
        }
        CharSequence displayed = layout.getText();
        if (displayed == null) return;
        float offsetX = view.getTotalPaddingLeft() - view.getScrollX();
        float offsetY = view.getTotalPaddingTop() - view.getScrollY();
        Path selection = new Path();
        RectF glyph = new RectF();
        RectF textViewport = new RectF(view.getCompoundPaddingLeft(),
                view.getExtendedPaddingTop(), view.getWidth() - view.getCompoundPaddingRight(),
                view.getHeight() - view.getExtendedPaddingBottom());
        transform.mapRect(textViewport);
        Region textRegion = new Region(permitted);
        textRegion.op(inwardRect(textViewport), Region.Op.INTERSECT);
        for (int line = 0; line < layout.getLineCount() && !atLimit(); line++) {
            int start = layout.getLineStart(line);
            int end = Math.min(layout.getLineEnd(line), displayed.length());
            int ellipsisStart = start + layout.getEllipsisStart(line);
            int ellipsisEnd = ellipsisStart + layout.getEllipsisCount(line);
            StringBuilder text = new StringBuilder();
            ArrayList<Rect> characters = new ArrayList<>();
            Rect itemBounds = new Rect();
            for (int index = start; index < end && !atLimit();) {
                int next = Math.min(end, index + Character.charCount(
                        Character.codePointAt(displayed, index)));
                boolean omitted = index >= ellipsisStart && index < ellipsisEnd;
                int codePoint = Character.codePointAt(displayed, index);
                if (omitted || codePoint == '\n' || codePoint == '\r' || codePoint == 0xfeff
                        || hasReplacement(displayed, index, next)) {
                    if (omitted || hasReplacement(displayed, index, next)) {
                        flushText(text, characters, itemBounds);
                        mResult.partial = true;
                    }
                    index = next;
                    continue;
                }
                selection.reset();
                layout.getSelectionPath(index, next, selection);
                selection.computeBounds(glyph, true);
                RectF ink = visibleTextInkBounds(layout, index, next, glyph);
                if (ink == null || ink.isEmpty()) {
                    flushText(text, characters, itemBounds);
                    mResult.partial = true;
                    index = next;
                    continue;
                }
                glyph.set(ink);
                glyph.offset(offsetX, offsetY);
                transform.mapRect(glyph);
                Rect character = outwardRect(glyph);
                if (!fullyContains(textRegion, character)) {
                    flushText(text, characters, itemBounds);
                    mResult.partial = true;
                    index = next;
                    continue;
                }
                if (mCharacters + next - index > MAX_CHARACTERS) {
                    mResult.partial = true;
                    break;
                }
                text.append(displayed, index, next);
                for (int unit = index; unit < next; unit++) characters.add(new Rect(character));
                itemBounds.union(character);
                mCharacters += next - index;
                index = next;
            }
            flushText(text, characters, itemBounds);
        }
    }

    private static boolean isPassword(TextView view) {
        int inputType = view.getInputType();
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        int type = inputType & InputType.TYPE_MASK_CLASS;
        return view.getTransformationMethod() instanceof PasswordTransformationMethod
                || (type == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
                || (type == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static boolean hasReplacement(CharSequence text, int start, int end) {
        return text instanceof Spanned
                && ((Spanned) text).getSpans(start, end, ReplacementSpan.class).length != 0;
    }

    static RectF visibleTextInkBounds(Layout layout, int start, int end, RectF selection) {
        CharSequence text = layout.getText();
        TextPaint paint = new TextPaint();
        paint.set(layout.getPaint());
        int line = layout.getLineForOffset(start);
        long run = layout.getRunRange(start);
        int contextStart = Math.max(layout.getLineStart(line),
                TextUtils.unpackRangeStartFromLong(run));
        int contextEnd = Math.min(layout.getLineEnd(line), TextUtils.unpackRangeEndFromLong(run));
        if (text instanceof Spanned) {
            Spanned styled = (Spanned) text;
            for (CharacterStyle span : styled.getSpans(start, end, CharacterStyle.class)) {
                span.updateDrawState(paint);
                contextStart = Math.max(contextStart, styled.getSpanStart(span));
                contextEnd = Math.min(contextEnd, styled.getSpanEnd(span));
            }
            contextEnd = styled.nextSpanTransition(start, contextEnd, CharacterStyle.class);
        }
        if (paint.getAlpha() == 0 || paint.getShader() != null || paint.getColorFilter() != null
                || paint.getMaskFilter() != null || selection.isEmpty()) return null;
        float baseline = layout.getLineBaseline(line) + paint.baselineShift;
        PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(text, start, end - start,
                contextStart, contextEnd - contextStart, selection.left, baseline,
                layout.isRtlCharAt(start), paint);
        RectF ink = new RectF();
        RectF bounds = new RectF();
        for (int index = 0; index < glyphs.glyphCount(); index++) {
            glyphs.getFont(index).getGlyphBounds(glyphs.getGlyphId(index), paint, bounds);
            if (bounds.isEmpty()) continue;
            bounds.offset(glyphs.getGlyphX(index), glyphs.getGlyphY(index));
            ink.union(bounds);
        }
        if (ink.isEmpty() && Character.isWhitespace(Character.codePointAt(text, start))) {
            // A visible space has an advance but no ink. Keep its selection near the baseline,
            // without treating font ascent/padding above it as obscured text.
            ink.set(selection.left, baseline - 1, selection.right, baseline + 1);
        }
        return ink;
    }

    private void flushText(StringBuilder text, ArrayList<Rect> characters, Rect bounds) {
        if (text.length() != 0 && mResult.items.size() < MAX_ITEMS) {
            ScreenContentItem item = new ScreenContentItem(ScreenContentItem.TYPE_TEXT, bounds);
            item.text = text.toString();
            item.characterBounds = new ArrayList<>(characters);
            item.packageName = mPackageName;
            mResult.items.add(item);
        }
        text.setLength(0);
        characters.clear();
        bounds.setEmpty();
    }

    private void collectImage(ImageView view, Region permitted, Matrix transform) {
        if (!(view.getDrawable() instanceof BitmapDrawable) || mImages >= MAX_IMAGES
                || view.getImageAlpha() != 255 || view.getDrawable().getAlpha() != 255
                || view.getImageTintList() != null || view.getDrawable().getColorFilter() != null
                || view.getWidth() <= 0 || view.getHeight() <= 0
                || mImagePixels >= MAX_TOTAL_IMAGE_PIXELS) {
            mResult.partial = true;
            return;
        }
        BitmapDrawable drawable = (BitmapDrawable) view.getDrawable();
        Bitmap source = drawable.getBitmap();
        if (source == null || source.isRecycled() || drawable.getBounds().isEmpty()
                || drawable.getTileModeX() != null || drawable.getTileModeY() != null
                || drawable.getGravity() != Gravity.FILL) {
            mResult.partial = true;
            return;
        }

        // ImageView first positions the drawable within its padding and image matrix.
        // BitmapDrawable's default FILL gravity maps the source pixels to drawable bounds.
        Matrix sourceToScreen = new Matrix();
        sourceToScreen.setRectToRect(new RectF(0, 0, source.getWidth(), source.getHeight()),
                new RectF(drawable.getBounds()), Matrix.ScaleToFit.FILL);
        if (drawable.isAutoMirrored() && view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            sourceToScreen.postScale(-1, 1, drawable.getBounds().exactCenterX(),
                    drawable.getBounds().exactCenterY());
        }
        sourceToScreen.postConcat(view.getImageMatrix());
        sourceToScreen.postTranslate(view.getPaddingLeft() - view.getScrollX(),
                view.getPaddingTop() - view.getScrollY());
        sourceToScreen.postConcat(transform);
        Rect crop = visibleImageCrop(sourceToScreen, source.getWidth(), source.getHeight(),
                permitted, imageViewport(view, transform));
        if (crop == null) {
            mResult.partial = true;
            return;
        }
        if (crop.isEmpty()) return;
        RectF imageBounds = new RectF(crop);
        sourceToScreen.mapRect(imageBounds);
        Rect bounds = outwardRect(imageBounds);
        if (!fullyContains(permitted, bounds)) {
            mResult.partial = true;
            return;
        }
        long budget = Math.min(MAX_IMAGE_PIXELS, MAX_TOTAL_IMAGE_PIXELS - mImagePixels);
        double scale = Math.min(1, Math.min(MAX_IMAGE_EDGE / (double) Math.max(
                crop.width(), crop.height()), Math.sqrt(budget / (double)
                ((long) crop.width() * crop.height()))));
        Bitmap pixels = null;
        Bitmap shared = null;
        boolean transferred = false;
        try {
            Matrix downsample = new Matrix();
            downsample.setScale((float) scale, (float) scale);
            pixels = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height(),
                    downsample, true);
            // createBitmap may return the ImageView's original. Never transfer or recycle it.
            if (pixels == source) {
                pixels = null;
                pixels = source.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (pixels == null) {
                mResult.partial = true;
                return;
            }
            long pixelCount = (long) pixels.getWidth() * pixels.getHeight();
            if (pixelCount > budget || pixels.getWidth() > MAX_IMAGE_EDGE
                    || pixels.getHeight() > MAX_IMAGE_EDGE) {
                int width = Math.max(1, (int) Math.floor(crop.width() * scale));
                int height = Math.max(1, (int) Math.floor(crop.height() * scale));
                Bitmap smaller = Bitmap.createScaledBitmap(pixels, width, height, true);
                if (smaller != pixels) pixels.recycle();
                pixels = smaller;
                pixelCount = (long) width * height;
            }
            if (pixelCount > budget) {
                mResult.partial = true;
                return;
            }
            shared = pixels.asShared();
            ScreenContentItem item = new ScreenContentItem(ScreenContentItem.TYPE_IMAGE, bounds);
            item.image = shared;
            item.mimeType = "image/png";
            item.packageName = mPackageName;
            mResult.items.add(item);
            transferred = true;
            mImages++;
            mImagePixels += pixelCount;
            if (scale < 1) mResult.partial = true;
        } catch (RuntimeException | OutOfMemoryError e) {
            mResult.partial = true;
        } finally {
            if (!transferred && shared != null && shared != pixels) shared.recycle();
            // Shared bitmaps are now owned by the result, including an already shared crop.
            if (pixels != null && (!transferred || pixels != shared)) pixels.recycle();
        }
    }

    private static Rect imageViewport(ImageView view, Matrix transform) {
        RectF viewport = view.getCropToPadding()
                ? new RectF(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getWidth() - view.getPaddingRight(),
                        view.getHeight() - view.getPaddingBottom())
                : new RectF(0, 0, view.getWidth(), view.getHeight());
        transform.mapRect(viewport);
        return inwardRect(viewport);
    }

    /** Returns only whole source pixels contained in a single visible screen rectangle. */
    static Rect visibleImageCrop(Matrix sourceToScreen, int width, int height,
            Region permitted, Rect viewport) {
        if (!sourceToScreen.rectStaysRect()) return null;
        RectF mapped = new RectF(0, 0, width, height);
        sourceToScreen.mapRect(mapped);
        Region visible = new Region(permitted);
        visible.op(viewport, Region.Op.INTERSECT);
        visible.op(outwardRect(mapped), Region.Op.INTERSECT);
        if (visible.isEmpty()) return new Rect();
        if (!visible.isRect()) return null;
        Rect viewportBounds = visible.getBounds();
        if (viewportBounds.left <= mapped.left && viewportBounds.top <= mapped.top
                && viewportBounds.right >= mapped.right && viewportBounds.bottom >= mapped.bottom) {
            // Do not lose an original row/column to inverse floating point rounding when the
            // complete image is already proved visible, including at a fractional display scale.
            return new Rect(0, 0, width, height);
        }
        Matrix inverse = new Matrix();
        if (!sourceToScreen.invert(inverse)) return null;
        RectF source = new RectF(visible.getBounds());
        inverse.mapRect(source);
        Rect crop = new Rect(Math.max(0, (int) Math.ceil(source.left)),
                Math.max(0, (int) Math.ceil(source.top)),
                Math.min(width, (int) Math.floor(source.right)),
                Math.min(height, (int) Math.floor(source.bottom)));
        return crop.isEmpty() ? new Rect() : crop;
    }

    private void addVideo(ScreenContentItem item, Rect bounds) {
        if (item == null) {
            mResult.partial = true;
            return;
        }
        if (item.protectedContent || item.descriptor == null || item.length < 0
                || item.offset < 0 || mResult.items.size() >= MAX_ITEMS) {
            item.close();
            mResult.partial = true;
            return;
        }
        item.bounds = new Rect(bounds);
        item.packageName = mPackageName;
        mResult.items.add(item);
        mVideos++;
    }

    private static Rect outwardRect(RectF rect) {
        return new Rect((int) Math.floor(rect.left), (int) Math.floor(rect.top),
                (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom));
    }

    private static Rect inwardRect(RectF rect) {
        return new Rect((int) Math.ceil(rect.left), (int) Math.ceil(rect.top),
                (int) Math.floor(rect.right), (int) Math.floor(rect.bottom));
    }
}
