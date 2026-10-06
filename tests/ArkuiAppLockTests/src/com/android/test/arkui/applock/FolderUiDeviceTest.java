/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock;

import static org.junit.Assert.*;

import android.app.PrivacyPasswordManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Real home-screen gestures on an owned page; existing items and credentials are preserved. */
@RunWith(AndroidJUnit4.class)
public final class FolderUiDeviceTest {
    private static final String LAUNCHER = "com.android.launcher3";
    private static final String SETTINGS = "com.android.settings";
    private static final String TARGET = "com.android.test.arkui.applock.target";
    private static final String SECOND = "com.android.test.arkui.applock.second";
    private static final String TITLE = "ArkUI folder test";
    private static final String PIN = "274839";
    private static final int USER = UserHandle.myUserId();
    private final IBinder mOwner = new Binder();
    private UiDevice mDevice;
    private PrivacyPasswordManager mPrivacy;
    private Context mSettings;
    private int mId;
    private int mScreen;
    private int mCellWidth;
    private int mCellHeight;
    private String mOriginalItems;
    private String mFolderTitle = TITLE;
    private boolean mOwnPassword;
    private final List<String> mNeighbours = new ArrayList<>();

    @Before public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mPrivacy = new PrivacyPasswordManager(context);
        mSettings = context.createPackageContext(SETTINGS, 0);
        assertFalse("Do not replace a user's privacy password", configured());
        mDevice.wakeUp();
        mDevice.executeShellCommand("am force-stop " + TARGET);
        mDevice.executeShellCommand("am force-stop " + SECOND);
        mDevice.pressHome();
        stopLauncher();
        mOriginalItems = query("SELECT _id,container,screen,cellX,cellY,spanX,spanY,options,rank"
                + " FROM favorites ORDER BY _id;");
        mId = Integer.parseInt(query("SELECT COALESCE(MAX(_id),0)+100 FROM favorites;"));
        mScreen = Integer.parseInt(query("SELECT COALESCE(MAX(screen),0)+1 FROM favorites"
                + " WHERE container=-100;"));
        StringBuilder sql = new StringBuilder("BEGIN;");
        sql.append("INSERT INTO favorites (_id,title,container,screen,cellX,cellY,spanX,spanY,"
                + "itemType,options) VALUES (").append(mId).append(",'" + TITLE + "',-100,")
                .append(mScreen).append(",0,0,1,1,2,8);");
        for (int i = 0; i < 10; i++) {
            String pkg = i % 2 == 0 ? TARGET : SECOND;
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(pkg, TARGET + ".TargetActivity"))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            sql.append("INSERT INTO favorites (_id,title,intent,container,screen,cellX,cellY,"
                    + "spanX,spanY,itemType,rank) VALUES (").append(mId + i + 1)
                    .append(",'App lock test target','").append(intent.toUri(0))
                    .append("',").append(mId).append(",0,").append(i % 3).append(',')
                    .append(i / 3).append(",1,1,0,").append(i).append(");");
        }
        sql.append("COMMIT;");
        query(sql.toString());
        restartLauncher();
        folder();
    }

    @After public void tearDown() throws Exception {
        if (mDevice == null || mId == 0) return;
        mDevice.pressHome();
        if (mOwnPassword && configured()) {
            IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
            Bundle verified = mPrivacy.verify(challenge, bytes(PIN));
            assertTrue("Only the owned test credential may be removed",
                    verified.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
            mPrivacy.clearPassword(USER, verified.getBinder(PrivacyPasswordManager.KEY_TOKEN));
        }
        stopLauncher();
        query("DELETE FROM favorites WHERE _id BETWEEN " + mId + " AND " + (mId + 10)
                + " OR container=" + mId + ";");
        if (!mNeighbours.isEmpty()) {
            query("DELETE FROM favorites WHERE _id IN (" + String.join(",", mNeighbours) + ");");
        }
        assertEquals("The user's existing home-screen layout must be preserved", mOriginalItems,
                query("SELECT _id,container,screen,cellX,cellY,spanX,spanY,options,rank"
                        + " FROM favorites ORDER BY _id;"));
        mDevice.pressHome();
    }

    @Test public void allFourSizesPersistWithoutLosingApps() throws Exception {
        edit();
        assertDimensions(2, 2);
        resize(1, 2);
        resize(2, 2);
        resize(2, 1);
        resize(1, 1);
        mDevice.pressBack();
        restartLauncher();
        assertDimensions(1, 1);
        edit();
        resize(2, 1);
        mDevice.pressHome();
        restartLauncher();
        assertDimensions(2, 1);
        assertEquals("10", query("SELECT COUNT(*) FROM favorites WHERE container=" + mId + ";"));
        assertEquals("10", query("SELECT COUNT(DISTINCT rank) FROM favorites WHERE container="
                + mId + ";"));
    }

    @Test public void directTargetsLaunchAppsAndOverflowOpensFolder() throws Exception {
        edit();
        mDevice.pressBack();
        UiObject2 app = mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_preview_app")
                .desc("App lock test target")), 5000);
        assertNotNull("The large folder must expose real app targets", app);
        app.click();
        assertTrue(mDevice.wait(Until.hasObject(By.text("Private app content: " + TARGET)), 10000));
        mDevice.pressHome();
        folder();
        UiObject2 more = mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_preview_more")), 5000);
        assertNotNull("More than nine apps require an overflow target", more);
        more.click();
        openedFolder();
        assertTrue(mDevice.hasObject(By.text("App lock test target")));
        mDevice.pressBack();
        assertTrue(mDevice.wait(Until.gone(By.res(LAUNCHER, "folder_name")), 5000));
        edit();
        resize(1, 2);
        mDevice.pressBack();
        assertEquals("Two direct targets plus an overflow tile in a narrow large folder", 2,
                mDevice.findObjects(By.res(LAUNCHER, "folder_preview_app")
                        .desc(Pattern.compile(".+"))).size());
        mDevice.findObject(By.res(LAUNCHER, "folder_preview_more")).click();
        openedFolder();
        assertEquals("10", query("SELECT COUNT(*) FROM favorites WHERE container=" + mId + ";"));
    }

    @Test public void previewContentsFollowTheResizeFrameDuringDrag() throws Exception {
        edit();
        Rect grip = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
        int iconSize = mDevice.findObject(By.res(LAUNCHER, "folder_preview_app")
                .desc("App lock test target")).getVisibleBounds().width();
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, grip.centerX(), grip.centerY(), down);
        try {
            float[][] offsets = {{-.4f, -.3f}, {-.9f, 0}, {-3, 0}, {0, 0}, {0, -.9f}};
            for (float[] offset : offsets) {
                touch(MotionEvent.ACTION_MOVE, grip.centerX() + offset[0] * mCellWidth,
                        grip.centerY() + offset[1] * mCellWidth, down);
                SystemClock.sleep(120);
                Rect surface = mDevice.findObject(By.res(LAUNCHER, "large_folder_preview"))
                        .getVisibleBounds();
                Rect currentGrip = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle"))
                        .getVisibleBounds();
                List<UiObject2> icons = mDevice.findObjects(By.res(LAUNCHER, "folder_preview_app"));
                assertFalse("Resizing must keep the populated preview visible", icons.isEmpty());
                Rect direct = mDevice.findObject(By.res(LAUNCHER, "folder_preview_app")
                        .desc("App lock test target")).getVisibleBounds();
                assertTrue("A direct app icon must keep its size while the folder shrinks: " + direct,
                        Math.abs(direct.width() - iconSize) <= 2
                                && Math.abs(direct.height() - iconSize) <= 2);
                assertTrue("A large folder cannot be dragged below one tile's minimum width",
                        currentGrip.centerX() - surface.left >= mCellWidth * .75f);
                for (UiObject2 icon : icons) {
                    Rect bounds = icon.getVisibleBounds();
                    assertTrue("A preview icon must stay inside the current painted frame: " + bounds,
                            bounds.left >= surface.left && bounds.top >= surface.top
                                    && bounds.right <= currentGrip.centerX() + 2
                                    && bounds.bottom <= currentGrip.centerY() + 2);
                }
            }
        } finally {
            touch(MotionEvent.ACTION_UP, grip.centerX(),
                    grip.centerY() - .9f * mCellWidth, down);
        }
        assertDimensions(2, 1);
        mDevice.pressBack();
    }

    @Test public void editorSheetPreviewsThenSavesOrCancelsTheDraft() throws Exception {
        menu("folder_action_edit");
        assertNotNull(mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_editor_sheet")), 5000));
        UiObject2 draftName = mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_editor_name")), 5000);
        assertNotNull(draftName);
        float density = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().getDisplayMetrics().density;
        assertTrue("The name field must leave room around its text",
                draftName.getVisibleBounds().height() >= 64 * density);
        draftName.setText("Saved folder");
        mDevice.findObject(By.res(LAUNCHER, "folder_size_2x1")).click();
        mDevice.findObject(By.res(LAUNCHER, "folder_editor_smart_sort")).click();
        assertDimensions(1, 1);
        assertEquals(TITLE + "|8", query("SELECT title,options FROM favorites WHERE _id=" + mId + ";"));
        mDevice.findObject(By.res(LAUNCHER, "folder_editor_cancel")).click();
        assertTrue(mDevice.wait(Until.gone(By.res(LAUNCHER, "folder_editor_sheet")), 5000));
        menu("folder_action_edit");
        UiObject2 name = mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_editor_name")), 5000);
        assertNotNull("The animated sheet must finish revealing its name field", name);
        assertEquals(TITLE, name.getText());
        assertFalse(mDevice.findObject(By.res(LAUNCHER, "folder_editor_smart_sort")).isChecked());
        mDevice.findObject(By.res(LAUNCHER, "folder_editor_name")).setText("Saved folder");
        mDevice.findObject(By.res(LAUNCHER, "folder_size_2x1")).click();
        mDevice.findObject(By.res(LAUNCHER, "folder_editor_smart_sort")).click();
        mDevice.findObject(By.res(LAUNCHER, "folder_editor_save")).click();
        mFolderTitle = "Saved folder";
        assertTrue(mDevice.wait(Until.gone(By.res(LAUNCHER, "folder_editor_sheet")), 5000));
        assertDimensions(2, 1);
        assertEquals("Saved folder|1", query("SELECT title,(options & 512)!=0 FROM favorites"
                + " WHERE _id=" + mId + ";"));
        restartLauncher();
        assertDimensions(2, 1);
        assertEquals("10", query("SELECT COUNT(*) FROM favorites WHERE container=" + mId + ";"));
    }

    @Test public void protectedEditorSecuresItsPreviewAndClosesWhenLeavingHome() throws Exception {
        createPassword();
        menu("folder_action_lock");
        verify(PIN);
        awaitLocked(true);
        menu("folder_action_edit");
        privacyTitle("arkui_privacy_password_enter");
        verify(PIN);
        assertNotNull(mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_editor_sheet")), 5000));
        assertTrue("The authenticated editor preview must also protect screenshots", launcherWindowSecure());
        mDevice.pressHome();
        folder();
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "folder_editor_sheet")));
        assertNoPrivatePreview();
        menu("folder_action_edit");
        privacyTitle("arkui_privacy_password_enter");
        mDevice.findObject(By.res(SETTINGS, "privacy_cancel")).click();
        assertDimensions(1, 1);
    }

    @Test public void accessibleResizeRequiresThePrivacyPassword() throws Exception {
        createPassword();
        menu("folder_action_lock");
        verify(PIN);
        awaitLocked(true);
        longPressFolder();
        accessibleResizeClick();
        privacyTitle("arkui_privacy_password_enter");
        assertDimensions(1, 1);
        mDevice.findObject(By.res(SETTINGS, "privacy_cancel")).click();
        folder();
        longPressFolder();
        accessibleResizeClick();
        verify(PIN);
        assertDimensions(2, 2);
        awaitLocked(true);
        mDevice.pressBack();
        assertNoPrivatePreview();
    }

    private void accessibleResizeClick() {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        List<AccessibilityNodeInfo> handles = root.findAccessibilityNodeInfosByViewId(
                LAUNCHER + ":id/folder_resize_handle");
        assertFalse("The resize handle must also be available to accessibility services", handles.isEmpty());
        assertTrue(handles.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    @Test public void droppingOntoTheLargeFolderCornerAddsTheAppWithoutMovingTheFolder() throws Exception {
        stopLauncher();
        String neighbour = Integer.toString(mId + 20);
        mNeighbours.add(neighbour);
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(TARGET, TARGET + ".TargetActivity"))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        query("INSERT INTO favorites (_id,title,intent,container,screen,cellX,cellY,spanX,spanY,itemType)"
                + " VALUES (" + neighbour + ",'App lock test target','" + intent.toUri(0)
                + "',-100," + mScreen + ",3,0,1,1,0);");
        restartLauncher();
        edit();
        Rect grip = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
        mDevice.pressBack();
        Rect source = mDevice.findObject(By.text("App lock test target")).getVisibleBounds();
        float x = source.centerX();
        float y = source.top + source.height() * .4f;
        float endX = grip.centerX() - mCellWidth * .15f;
        float endY = grip.centerY() - mCellWidth * .15f;
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, x, y, down);
        SystemClock.sleep(900);
        for (int step = 1; step <= 24; step++) {
            touch(MotionEvent.ACTION_MOVE, x + (endX - x) * step / 24,
                    y + (endY - y) * step / 24, down);
            SystemClock.sleep(12);
        }
        touch(MotionEvent.ACTION_UP, endX, endY, down);
        long timeout = SystemClock.elapsedRealtime() + 5000;
        while (!Integer.toString(mId).equals(query("SELECT container FROM favorites WHERE _id="
                + neighbour + ";")) && SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(100);
        assertEquals("Dropping within the painted corner must add the app to the large folder",
                Integer.toString(mId), query("SELECT container FROM favorites WHERE _id=" + neighbour + ";"));
        assertEquals("11", query("SELECT COUNT(*) FROM favorites WHERE container=" + mId + ";"));
        assertEquals("0|0|2|2", query("SELECT cellX,cellY,spanX,spanY FROM favorites WHERE _id=" + mId + ";"));
    }

    private void touch(int action, float x, float y, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .injectInputEvent(event, true));
        } finally {
            event.recycle();
        }
    }

    @Test public void resizeResistsThenSnapsAtTheThresholdWithoutFreeStretching() throws Exception {
        Rect cell = folder().getParent().getVisibleBounds();
        int step = cell.width();
        longPressFolder();
        Rect grip = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, grip.centerX(), grip.centerY(), down);
        try {
            touch(MotionEvent.ACTION_MOVE, grip.centerX() + step * .35f,
                    grip.centerY() + step * .35f, down);
            SystemClock.sleep(150);
            assertDimensions(1, 1);
            Rect resisted = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
            assertTrue("Below the threshold only a small elastic displacement is allowed",
                    resisted.centerX() - grip.centerX() < step * .15f
                            && resisted.centerY() - grip.centerY() < step * .15f);
            touch(MotionEvent.ACTION_MOVE, grip.centerX() + step * .65f,
                    grip.centerY() + step * .65f, down);
            SystemClock.sleep(400);
            assertDimensions(2, 2);
            Rect snapped = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
            touch(MotionEvent.ACTION_MOVE, grip.centerX() + step * 2,
                    grip.centerY() + step * 2, down);
            SystemClock.sleep(150);
            Rect limit = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
            assertEquals("Further pulling cannot freely stretch a snapped folder", snapped, limit);
            touch(MotionEvent.ACTION_MOVE, grip.centerX() + step * .45f,
                    grip.centerY() + step * .45f, down);
            SystemClock.sleep(150);
            assertDimensions(2, 2);
            touch(MotionEvent.ACTION_MOVE, grip.centerX() + step * .05f,
                    grip.centerY() + step * .05f, down);
            SystemClock.sleep(400);
            assertDimensions(1, 1);
        } finally {
            touch(MotionEvent.ACTION_UP, grip.centerX() + step * .05f,
                    grip.centerY() + step * .05f, down);
        }
        assertDimensions(1, 1);
        mDevice.pressBack();
    }

    @Test public void lockRequiresPrivacyPasswordAndReauthenticatesAfterHome() throws Exception {
        createPassword();
        edit();
        mDevice.pressBack();
        menu("folder_action_lock");
        privacyTitle("arkui_privacy_password_enter");
        verify("123456");
        privacyTitle("arkui_privacy_password_wrong");
        assertFalse(locked());
        verify(PIN);
        awaitLocked(true);
        assertNoPrivatePreview();
        restartLauncher();
        assertNoPrivatePreview();
        folder().click();
        privacyTitle("arkui_privacy_password_enter");
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "folder_name")));
        verify(PIN);
        openedFolder();
        assertTrue("Authenticated contents must protect screenshots and recents",
                launcherWindowSecure());
        mDevice.pressHome();
        folder();
        assertNoPrivatePreview();
        long cleanFrame = SystemClock.elapsedRealtime() + 5000;
        while (launcherWindowSecure() && SystemClock.elapsedRealtime() < cleanFrame) {
            SystemClock.sleep(100);
        }
        assertFalse("A clean home-screen frame should restore normal screenshots",
                launcherWindowSecure());
        folder().click();
        privacyTitle("arkui_privacy_password_enter");
        mDevice.findObject(By.res(SETTINGS, "privacy_cancel")).click();
        folder();
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "folder_name")));
        awaitLocked(true);
    }

    @Test public void removingLockAndEditingRequireAuthentication() throws Exception {
        createPassword();
        menu("folder_action_lock");
        verify(PIN);
        awaitLocked(true);
        menu("folder_action_edit");
        privacyTitle("arkui_privacy_password_enter");
        mDevice.findObject(By.res(SETTINGS, "privacy_cancel")).click();
        folder();
        assertDimensions(1, 1);
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "folder_resize_handle")));
        menu("folder_action_lock");
        verify("123456");
        privacyTitle("arkui_privacy_password_wrong");
        awaitLocked(true);
        verify(PIN);
        awaitLocked(false);
        folder().click();
        openedFolder();
        assertFalse(mDevice.hasObject(By.res(SETTINGS, "privacy_password_entry")));
    }

    @Test public void singleAppProtectedFolderSurvivesReload() throws Exception {
        createPassword();
        menu("folder_action_lock");
        verify(PIN);
        awaitLocked(true);
        stopLauncher();
        query("DELETE FROM favorites WHERE container=" + mId + " AND rank>0;");
        restartLauncher();
        assertNoPrivatePreview();
        assertEquals("2", query("SELECT itemType FROM favorites WHERE _id=" + mId + ";"));
        folder().click();
        privacyTitle("arkui_privacy_password_enter");
        verify(PIN);
        openedFolder();
        assertTrue(mDevice.hasObject(By.text("App lock test target")));
    }

    @Test public void settingFirstPasswordFromFolderCreatesActualLock() throws Exception {
        menu("folder_action_lock");
        privacyTitle("arkui_privacy_password_create");
        verify(PIN);
        privacyTitle("arkui_privacy_password_confirm");
        mOwnPassword = true;
        verify(PIN);
        awaitLocked(true);
        assertTrue(configured());
        assertNoPrivatePreview();
    }

    @Test public void singleAppLargeFolderRetainsItsSizeAndContents() throws Exception {
        stopLauncher();
        query("UPDATE favorites SET spanX=2,spanY=2 WHERE _id=" + mId + ";"
                + "DELETE FROM favorites WHERE container=" + mId + " AND rank>0;");
        restartLauncher();
        assertDimensions(2, 2);
        assertNotNull(mDevice.findObject(By.res(LAUNCHER, "folder_preview_app")));
        folder().click();
        openedFolder();
        assertTrue(mDevice.hasObject(By.text("App lock test target")));
        assertEquals("2", query("SELECT itemType FROM favorites WHERE _id=" + mId + ";"));
    }

    @Test public void fullPageRejectsGrowthWithoutRemovingOrOverlappingItems() throws Exception {
        int columns = Integer.parseInt(InstrumentationRegistry.getArguments().getString("columns", "5"));
        int rows = Integer.parseInt(InstrumentationRegistry.getArguments().getString("rows", "5"));
        stopLauncher();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(TARGET, TARGET + ".TargetActivity"))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        StringBuilder sql = new StringBuilder("BEGIN;");
        for (int i = 1; i < columns * rows; i++) {
            String id = Integer.toString(mId + 20 + i);
            mNeighbours.add(id);
            sql.append("INSERT INTO favorites (_id,title,intent,container,screen,cellX,cellY,"
                    + "spanX,spanY,itemType) VALUES (").append(id).append(",'Folder test neighbour','")
                    .append(intent.toUri(0)).append("',-100,").append(mScreen).append(',')
                    .append(i % columns).append(',').append(i / columns).append(",1,1,0);");
        }
        query(sql.append("COMMIT;").toString());
        restartLauncher();
        String before = query("SELECT _id,cellX,cellY,spanX,spanY FROM favorites"
                + " WHERE container=-100 AND screen=" + mScreen + " ORDER BY _id;");
        longPressFolder();
        assertNotNull(mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_resize_handle")), 5000));
        assertDimensions(1, 1);
        Rect grip = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
        mDevice.swipe(grip.centerX(), grip.centerY(), grip.centerX() + mDevice.getDisplayWidth() / columns,
                grip.centerY() + mDevice.getDisplayHeight() / rows, 35);
        assertDimensions(1, 1);
        mDevice.pressBack();
        assertEquals("An unsuccessful resize must leave every occupied cell intact", before,
                query("SELECT _id,cellX,cellY,spanX,spanY FROM favorites"
                        + " WHERE container=-100 AND screen=" + mScreen + " ORDER BY _id;"));
    }

    private void edit() throws Exception {
        String[] size = dimensions().split("\\|");
        Rect bounds = folder().getParent().getVisibleBounds();
        mCellWidth = bounds.width() / Integer.parseInt(size[0]);
        mCellHeight = bounds.height() / Integer.parseInt(size[1]);
        longPressFolder();
        assertNotNull(mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_resize_handle")), 5000));
        assertFalse("Long press must expose resizing without entering the editor sheet",
                mDevice.hasObject(By.res(LAUNCHER, "folder_editor_sheet")));
        resize(2, 2);
        assertDimensions(2, 2);
        assertTrue(mCellWidth > 50 && mCellHeight > 50);
    }

    private void resize(int x, int y) throws Exception {
        String[] old = dimensions().split("\\|");
        Rect grip = mDevice.findObject(By.res(LAUNCHER, "folder_resize_handle")).getVisibleBounds();
        int dx = (x - Integer.parseInt(old[0])) * mCellWidth;
        int dy = (y - Integer.parseInt(old[1])) * mCellWidth;
        assertTrue(mDevice.swipe(grip.centerX(), grip.centerY(), grip.centerX() + dx,
                grip.centerY() + dy, 35));
        assertDimensions(x, y);
        mDevice.waitForIdle();
    }

    private void menu(String action) throws Exception {
        longPressFolder();
        UiObject2 row = mDevice.wait(Until.findObject(By.res(LAUNCHER, action)), 5000);
        assertNotNull("The folder's native long-press menu must offer " + action, row);
        row.click();
    }

    private void longPressFolder() {
        Rect bounds = folder().getVisibleBounds();
        assertTrue(mDevice.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(),
                bounds.centerY(), 45));
    }

    private UiObject2 folder() {
        UiObject2 label = mDevice.wait(Until.findObject(By.text(mFolderTitle)), 1500);
        if (label == null) {
            assertTrue(mDevice.wait(Until.hasObject(By.res(LAUNCHER, "workspace")), 10000));
            // Home can return to the default page. Keep the same process and authentication state.
            for (int i = 0; i < 6 && label == null; i++) {
                mDevice.swipe(mDevice.getDisplayWidth() * 4 / 5, mDevice.getDisplayHeight() / 2,
                        mDevice.getDisplayWidth() / 5, mDevice.getDisplayHeight() / 2, 25);
                mDevice.waitForIdle();
                label = mDevice.wait(Until.findObject(By.text(mFolderTitle)), 1500);
            }
        }
        assertNotNull("The disposable folder must be visible", label);
        return label;
    }

    private void restartLauncher() throws Exception {
        stopLauncher();
        mDevice.pressHome();
        mDevice.wait(Until.hasObject(By.res(LAUNCHER, "workspace")), 10000);
        folder();
    }

    private void stopLauncher() throws Exception {
        mDevice.executeShellCommand("am force-stop " + LAUNCHER);
    }

    private void openedFolder() {
        assertNotNull("Opening must display the real, populated folder",
                mDevice.wait(Until.findObject(By.res(LAUNCHER, "folder_name")), 10000));
        mDevice.waitForIdle();
    }

    private void assertNoPrivatePreview() {
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "folder_preview_app")));
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "folder_preview_more")));
        assertFalse(mDevice.hasObject(By.text("App lock test target")));
    }

    private String dimensions() throws Exception {
        return query("SELECT spanX,spanY FROM favorites WHERE _id=" + mId + ";");
    }

    private void assertDimensions(int x, int y) throws Exception {
        String expected = x + "|" + y;
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!expected.equals(dimensions()) && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(100);
        }
        assertEquals("The accepted grid size must be persisted", expected, dimensions());
    }

    private boolean locked() throws Exception {
        return "1".equals(query("SELECT (options & 256)!=0 FROM favorites WHERE _id=" + mId + ";"));
    }

    private void awaitLocked(boolean expected) throws Exception {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (expected != locked() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertEquals("Verified folder actions must save their lock state", expected, locked());
        mDevice.waitForIdle();
    }

    private boolean configured() {
        return mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED);
    }

    private boolean launcherWindowSecure() throws Exception {
        for (String window : mDevice.executeShellCommand("dumpsys window windows").split("Window #")) {
            if (window.contains("package=" + LAUNCHER) && window.matches("(?s).*fl=[^\\n]*\\bSECURE\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private void createPassword() {
        mPrivacy.setPassword(USER, mOwner, null, PrivacyPasswordManager.TYPE_PIN, bytes(PIN));
        mOwnPassword = true;
    }

    private void verify(String value) {
        UiObject2 entry = mDevice.wait(Until.findObject(By.res(SETTINGS, "privacy_password_entry")), 5000);
        assertNotNull("A folder action must use trusted Settings authentication", entry);
        entry.setText(value);
        mDevice.findObject(By.res(SETTINGS, "privacy_primary")).click();
    }

    private void privacyTitle(String resource) {
        int id = mSettings.getResources().getIdentifier(resource, "string", SETTINGS);
        assertTrue(mDevice.wait(Until.hasObject(By.text(mSettings.getString(id))), 5000));
    }

    private String query(String sql) throws Exception {
        ParcelFileDescriptor[] pipes = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommandRwe("sqlite3 /data/user/0/" + LAUNCHER + "/databases/launcher.db");
        try (var output = new ParcelFileDescriptor.AutoCloseInputStream(pipes[0]);
             var input = new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]);
             var error = new ParcelFileDescriptor.AutoCloseInputStream(pipes[2])) {
            // The model writer may be committing a resize or lock while a read polls its state.
            input.write(bytes(".timeout 5000\n" + sql + "\n"));
            input.close();
            String result = new String(output.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals("The owned fixture's database operation must succeed", "",
                    new String(error.readAllBytes(), StandardCharsets.UTF_8).trim());
            return result;
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
