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
package org.arkui.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.junit.Test;

public class VisibleImageCropTests {
    @Test
    public void imageFullyVisible_retainsAllSourcePixels() {
        Matrix matrix = new Matrix();
        matrix.setScale(0.1f, 0.1f);
        matrix.postTranslate(20, 30);
        Rect viewport = new Rect(20, 30, 420, 230);

        assertEquals(new Rect(0, 0, 4000, 2000), crop(matrix, 4000, 2000, viewport,
                new Region(viewport)));
        matrix.setScale(1.3125f, 1.3125f);
        matrix.postTranslate(120, 793);
        viewport.set(120, 793, 960, 1318);
        assertEquals(new Rect(0, 0, 640, 400), crop(matrix, 640, 400, viewport,
                new Region(viewport)));
    }

    @Test
    public void centerCrop_doesNotExportHiddenSourceEdges() {
        Matrix matrix = new Matrix();
        matrix.setScale(0.5f, 0.5f);
        matrix.postTranslate(-100, 0);
        Rect viewport = new Rect(0, 0, 200, 200);

        assertEquals(new Rect(200, 0, 600, 400), crop(matrix, 800, 400, viewport,
                new Region(viewport)));
    }

    @Test
    public void paddingAndWindowPosition_mapBackToSource() {
        Matrix matrix = new Matrix();
        matrix.setScale(2, 2);
        matrix.postTranslate(110, 220);
        Rect viewport = new Rect(100, 200, 210, 320);

        assertEquals(new Rect(0, 0, 50, 50), crop(matrix, 100, 100, viewport,
                new Region(viewport)));
    }

    @Test
    public void foregroundWindowOcclusion_excludesCoveredSourcePixels() {
        Matrix matrix = new Matrix();
        matrix.setScale(2, 2);
        Rect viewport = new Rect(0, 0, 200, 200);
        Region visible = new Region(100, 0, 200, 200);

        assertEquals(new Rect(50, 0, 100, 100), crop(matrix, 100, 100, viewport, visible));
    }

    @Test
    public void fractionalClipping_onlyExportsWholeVisiblePixels() {
        Matrix matrix = new Matrix();
        matrix.setScale(2, 2);
        matrix.postTranslate(0.5f, 0.5f);
        Rect viewport = new Rect(1, 1, 100, 100);

        assertEquals(new Rect(1, 1, 49, 49), crop(matrix, 100, 100, viewport,
                new Region(viewport)));
    }

    @Test
    public void mirroredDrawable_cropsTheCorrectSourceSide() {
        Matrix matrix = new Matrix();
        matrix.setScale(-2, 2);
        matrix.postTranslate(200, 0);
        Rect viewport = new Rect(0, 0, 200, 200);
        Region visible = new Region(0, 0, 100, 200);

        assertEquals(new Rect(50, 0, 100, 100), crop(matrix, 100, 100, viewport, visible));
    }

    @Test
    public void occlusionHole_cannotExportAnUnmaskedBitmap() {
        Rect viewport = new Rect(0, 0, 100, 100);
        Region visible = new Region(viewport);
        visible.op(new Rect(25, 25, 75, 75), Region.Op.DIFFERENCE);

        assertNull(crop(new Matrix(), 100, 100, viewport, visible));
    }

    @Test
    public void arbitraryRotation_cannotProveRectangleVisibility() {
        Matrix matrix = new Matrix();
        matrix.setRotate(30);
        Rect viewport = new Rect(-100, -100, 200, 200);

        assertNull(crop(matrix, 100, 100, viewport, new Region(viewport)));
    }

    @Test
    public void sourceOutsideViewport_exportsNoPixels() {
        Matrix matrix = new Matrix();
        matrix.setTranslate(200, 200);
        Rect viewport = new Rect(0, 0, 100, 100);

        assertEquals(new Rect(), crop(matrix, 100, 100, viewport, new Region(viewport)));
    }

    @Test
    public void clippedFontPadding_doesNotHideACompleteVisibleGlyph() {
        Layout layout = glyphLayout();
        RectF selection = selection(layout);
        RectF ink = ink(layout, selection);
        assertFalse(ink.isEmpty());
        assertTrue("The selection cell includes blank space above V", ink.top > selection.top);
        Region visible = new Region(0, (int) Math.floor(ink.top), 200,
                (int) Math.ceil(selection.bottom));

        assertFalse(contains(visible, roundedOut(selection)));
        assertTrue("Actual visible ink must remain exportable", contains(visible, roundedOut(ink)));
    }

    @Test
    public void partlyOccludedInk_isNotExportableAsACompleteCharacter() {
        Layout layout = glyphLayout();
        RectF ink = ink(layout, selection(layout));
        int clippedLeft = (int) Math.ceil((ink.left + ink.right) / 2);
        Region visible = new Region(clippedLeft, 0, 200, 200);

        assertFalse("An occluder through V's ink must omit the character",
                contains(visible, roundedOut(ink)));
    }

    @Test
    public void videoEntryWithOcclusionHole_staysInsideAVisibleRectangle() {
        Region visible = new Region(0, 0, 100, 100);
        Rect hole = new Rect(40, 40, 60, 60);
        visible.op(hole, Region.Op.DIFFERENCE);
        Rect entry = (Rect) invoke("largestVisibleRectangle", new Class<?>[] { Region.class },
                visible);

        assertEquals(4000, entry.width() * entry.height());
        assertTrue(contains(visible, entry));
        assertFalse(Rect.intersects(entry, hole));
    }

    private static Layout glyphLayout() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(60);
        return StaticLayout.Builder.obtain("V", 0, 1, paint, 200).setIncludePad(true).build();
    }

    private static RectF selection(Layout layout) {
        Path path = new Path();
        layout.getSelectionPath(0, 1, path);
        RectF selection = new RectF();
        path.computeBounds(selection, true);
        return selection;
    }

    private static RectF ink(Layout layout, RectF selection) {
        return (RectF) invoke("visibleTextInkBounds",
                new Class<?>[] { Layout.class, int.class, int.class, RectF.class },
                layout, 0, 1, selection);
    }

    private static Rect roundedOut(RectF rectangle) {
        return new Rect((int) Math.floor(rectangle.left), (int) Math.floor(rectangle.top),
                (int) Math.ceil(rectangle.right), (int) Math.ceil(rectangle.bottom));
    }

    private static boolean contains(Region visible, Rect rectangle) {
        return (boolean) invoke("fullyContains", new Class<?>[] { Region.class, Rect.class },
                visible, rectangle);
    }

    private static Rect crop(Matrix matrix, int width, int height, Rect viewport, Region visible) {
        return (Rect) invoke("visibleImageCrop",
                new Class<?>[] { Matrix.class, int.class, int.class, Region.class, Rect.class },
                matrix, width, height, visible, viewport);
    }

    private static Object invoke(String name, Class<?>[] types, Object... arguments) {
        try {
            var method = Class.forName("android.view.VisibleScreenContentCollector")
                    .getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Framework visible content helper is unavailable: " + name, e);
        }
    }
}
