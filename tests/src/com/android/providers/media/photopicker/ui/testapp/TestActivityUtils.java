/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.photopicker.ui.testapp;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Intent;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiSelector;

public class TestActivityUtils {
    private static final String ACTION_LAUNCH_TEST_ACTIVITY =
            "com.android.providers.media.photopicker.tests.LAUNCH_TEST";
    public static final Intent TEST_ACTIVITY_INTENT = new Intent(ACTION_LAUNCH_TEST_ACTIVITY);

    private static final long TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;

    /**
     * Verify if the {@link TestActivity} is launched and visible.
     */
    public static void assertThatTestActivityIsVisible(
            @NonNull UiDevice uiDevice, @NonNull String targetPackageName) {
        assertWithMessage("Timed out waiting for the test activity to appear")
                .that(uiDevice.findObject(new UiSelector().resourceId(
                        targetPackageName + ":id/test_app_text")).waitForExists(TIMEOUT))
                .isTrue();
    }
}
