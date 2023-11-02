/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.espresso;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.hamcrest.Matchers.allOf;

import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiSelector;
import android.text.format.DateUtils;

import androidx.test.core.app.ActivityScenario;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.library.RunOnlyOnPostsubmit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunOnlyOnPostsubmit
@RunWith(AndroidJUnit4ClassRunner.class)
public class ProgressBarTest extends PhotoPickerBaseTest {
    public ActivityScenario<PhotoPickerTestActivity> mScenario;
    protected static final UiDevice sDevice = UiDevice.getInstance(getInstrumentation());

    @Before
    public void setup() {
        startPhotoPickerActivityAndEnableCloudFlag();
    }

    @Test
    public void test_progressBarAlbumsTab_isNotVisible() {

        // Navigate to albums tab.
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        // Verify that the progress bar and loading text is not visible.
        assertProgressBarAndLoadingTextDoesNotAppears();
    }

    private void startPhotoPickerActivityAndEnableCloudFlag() {
        sDevice.waitForIdle();
        launchPhotosActivity();
        mScenario.onActivity(
                (activity -> {
                    activity.getConfigStore()
                            .enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                                    getInstrumentation().getTargetContext().getPackageName());
                }));
    }

    private void launchPhotosActivity() {
        mScenario = ActivityScenario.launchActivityForResult(
                PhotoPickerBaseTest.getSingleSelectionIntent());
    }

    private void assertProgressBarAndLoadingTextDoesNotAppears() {
        final UiSelector progressBar = new UiSelector().resourceId(
                getIsolatedContext().getPackageName()
                        + ":id/progress_bar");
        assertWithMessage("Waiting for progressBar to appear on photos grid").that(
                new UiObject(progressBar).waitForExists(DateUtils.SECOND_IN_MILLIS / 2)).isFalse();

        final UiSelector loadingText = new UiSelector().resourceId(
                getIsolatedContext().getPackageName()
                        + ":id/loading_text_view");
        assertWithMessage("Waiting for progressBar to appear on photos grid").that(
                new UiObject(loadingText).waitForExists(DateUtils.SECOND_IN_MILLIS / 2)).isFalse();
    }

    @After
    public void tearDown() {
        if (mScenario != null) {
            mScenario.close();
        }
    }
}
