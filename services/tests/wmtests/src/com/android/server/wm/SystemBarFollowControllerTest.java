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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class SystemBarFollowControllerTest extends WindowTestsBase {
    @Test
    public void testOpeningSkipsFinishingAliasWithoutWindow() {
        ActivityRecord displayed = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState appWindow = createWindow(displayed, TYPE_BASE_APPLICATION);
        makeWindowVisibleAndDrawn(appWindow);
        ActivityRecord alias = new ActivityBuilder(mAtm).setTask(displayed.getTask()).build();
        alias.finishing = true;
        assertSame(alias, displayed.getTask().getTopMostActivity());

        assertSame(appWindow, SystemBarFollowController.findWindowForTransition(
                displayed.getTask(), false /* closing */));
    }

    @Test
    public void testOpeningSkipsNewActivityWithoutWindow() {
        ActivityRecord displayed = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState appWindow = createWindow(displayed, TYPE_BASE_APPLICATION);
        new ActivityBuilder(mAtm).setTask(displayed.getTask()).build();

        assertSame(appWindow, SystemBarFollowController.findWindowForTransition(
                displayed.getTask(), false /* closing */));
    }

    @Test
    public void testOpeningPrefersRealWindowOverStartingWindow() {
        ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState appWindow = createWindow(activity, TYPE_BASE_APPLICATION);
        WindowState startingWindow = createWindow(activity, TYPE_APPLICATION_STARTING);
        assertSame(startingWindow, activity.findMainWindow());

        assertSame(appWindow, SystemBarFollowController.findWindowForTransition(
                activity.getTask(), false /* closing */));
    }

    @Test
    public void testOpeningKeepsForegroundStartingWindowOverOlderAppWindow() {
        ActivityRecord displayed = new ActivityBuilder(mAtm).setCreateTask(true).build();
        createWindow(displayed, TYPE_BASE_APPLICATION);
        ActivityRecord starting = new ActivityBuilder(mAtm).setTask(displayed.getTask()).build();
        WindowState startingWindow = createWindow(starting, TYPE_APPLICATION_STARTING);

        assertSame(startingWindow, SystemBarFollowController.findWindowForTransition(
                displayed.getTask(), false /* closing */));
    }

    @Test
    public void testOpeningUsesTopmostRealWindow() {
        ActivityRecord bottom = new ActivityBuilder(mAtm).setCreateTask(true).build();
        createWindow(bottom, TYPE_BASE_APPLICATION);
        ActivityRecord top = new ActivityBuilder(mAtm).setTask(bottom.getTask()).build();
        WindowState topWindow = createWindow(top, TYPE_BASE_APPLICATION);

        assertSame(topWindow, SystemBarFollowController.findWindowForTransition(
                bottom.getTask(), false /* closing */));
    }

    @Test
    public void testColdOpeningUsesStartingWindow() {
        ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState startingWindow = createWindow(activity, TYPE_APPLICATION_STARTING);

        assertSame(startingWindow, SystemBarFollowController.findWindowForTransition(
                activity.getTask(), false /* closing */));
    }

    @Test
    public void testOpeningUsesStartingWindowBelowWindowlessActivity() {
        ActivityRecord starting = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState startingWindow = createWindow(starting, TYPE_APPLICATION_STARTING);
        new ActivityBuilder(mAtm).setTask(starting.getTask()).build();

        assertSame(startingWindow, SystemBarFollowController.findWindowForTransition(
                starting.getTask(), false /* closing */));
    }

    @Test
    public void testClosingUsesLastVisibleFinishingAppWindow() {
        ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState appWindow = createWindow(activity, TYPE_BASE_APPLICATION);
        makeWindowVisibleAndDrawn(appWindow);
        activity.finishing = true;
        activity.setVisibleRequested(false);
        assertTrue(appWindow.isVisibleNow());

        assertSame(appWindow, SystemBarFollowController.findWindowForTransition(
                activity.getTask(), true /* closing */));
        assertNull(SystemBarFollowController.findWindowForTransition(
                activity.getTask(), false /* closing */));
    }

    @Test
    public void testClosingKeepsFinishingTopWindowOverUnderlyingApp() {
        ActivityRecord bottom = new ActivityBuilder(mAtm).setCreateTask(true).build();
        createWindow(bottom, TYPE_BASE_APPLICATION);
        ActivityRecord top = new ActivityBuilder(mAtm).setTask(bottom.getTask()).build();
        WindowState topWindow = createWindow(top, TYPE_BASE_APPLICATION);
        makeWindowVisibleAndDrawn(topWindow);
        top.finishing = true;
        top.setVisibleRequested(false);
        assertTrue(topWindow.isVisibleNow());

        assertSame(topWindow, SystemBarFollowController.findWindowForTransition(
                bottom.getTask(), true /* closing */));
    }

    @Test
    public void testClosingUsesFinishingWindowBelowWindowlessAlias() {
        ActivityRecord displayed = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState appWindow = createWindow(displayed, TYPE_BASE_APPLICATION);
        makeWindowVisibleAndDrawn(appWindow);
        ActivityRecord alias = new ActivityBuilder(mAtm).setTask(displayed.getTask()).build();
        alias.finishing = true;
        displayed.finishing = true;
        displayed.setVisible(true);
        assertTrue(appWindow.isVisibleNow());

        assertSame(appWindow, SystemBarFollowController.findWindowForTransition(
                displayed.getTask(), true /* closing */));
    }

    @Test
    public void testClosingSkipsHiddenFinishingAppWindow() {
        ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        WindowState appWindow = createWindow(activity, TYPE_BASE_APPLICATION);
        makeWindowVisibleAndDrawn(appWindow);
        activity.finishing = true;
        activity.setVisible(false);
        assertFalse(appWindow.isVisibleNow());

        assertNull(SystemBarFollowController.findWindowForTransition(
                activity.getTask(), true /* closing */));
    }

    private WindowState createWindow(ActivityRecord activity, int type) {
        return newWindowBuilder("app", type).setWindowToken(activity).build();
    }
}
