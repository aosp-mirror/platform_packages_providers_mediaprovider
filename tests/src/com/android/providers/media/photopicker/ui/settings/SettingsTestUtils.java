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

package com.android.providers.media.photopicker.ui.settings;

import static com.google.common.truth.Truth.assertWithMessage;

import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;

public class SettingsTestUtils {
    private static final long TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final String REGEX_PACKAGE_NAME =
            "com(.google)?.android.providers.media(.module)?";
    private static final String SETTINGS_ACTIVITY_ROOT_RES_ID =
            REGEX_PACKAGE_NAME + ":id/settings_activity_root";

    /**
     * Verify if the {@link com.android.providers.media.photopicker.PhotoPickerSettingsActivity}
     * is launched and visible.
     */
    static void verifySettingsActivityIsVisible(@NonNull UiDevice uiDevice) {
        // id/settings_activity_root is the root layout in activity_photo_picker_settings.xml
        assertWithMessage("Timed out waiting for settings activity to appear")
                .that(uiDevice.findObject(
                        new UiSelector().resourceIdMatches(SETTINGS_ACTIVITY_ROOT_RES_ID))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    /**
     * Verify if the account of the selected {@link android.provider.CloudMediaProvider} is visible.
     */
    static void verifySettingsCloudProviderAccountIsVisible(@NonNull UiDevice uiDevice,
            @NonNull String cloudAccount) {
        assertWithMessage("Timed out waiting for the selected cloud provider account to appear")
                .that(uiDevice.findObject(new UiSelector().textContains(cloudAccount))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    /**
     * Find and click the settings provider option extra widget to launch provider settings.
     */
    static void findAndClickSettingsProviderOptionExtraWidget(@NonNull UiDevice uiDevice)
            throws Exception {
        final UiObject selectorExtraWidget = uiDevice.findObject(new UiSelector()
                .resourceIdMatches(REGEX_PACKAGE_NAME + ":id/selector_extra_widget"));

        assertWithMessage("Timed out waiting for the provider option extra widget to appear")
                .that(selectorExtraWidget.waitForExists(TIMEOUT))
                .isTrue();

        clickAndWait(uiDevice, selectorExtraWidget);
    }

    private static void clickAndWait(@NonNull UiDevice uiDevice, @NonNull UiObject uiObject)
            throws Exception {
        uiObject.click();
        uiDevice.waitForIdle();
    }
}
