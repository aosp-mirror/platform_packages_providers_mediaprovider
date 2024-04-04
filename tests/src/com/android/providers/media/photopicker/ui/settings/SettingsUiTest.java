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

import static com.android.providers.media.ConfigStore.NAMESPACE_MEDIAPROVIDER;
import static com.android.providers.media.photopicker.PhotoPickerComponentTestUtils.PICKER_SETTINGS_ACTIVITY_COMPONENT;

import static com.google.common.truth.Truth.assertThat;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.test.filters.SdkSuppress;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.cloudproviders.CloudProviderSecondary;
import com.android.providers.media.photopicker.DeviceStatePreserver;
import com.android.providers.media.photopicker.PhotoPickerCloudTestUtils;
import com.android.providers.media.photopicker.PhotoPickerComponentTestUtils;
import com.android.providers.media.photopicker.ui.UiBaseTest;
import com.android.providers.media.photopicker.ui.testapp.TestActivityUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S, codeName = "S")
@RunWith(AndroidJUnit4ClassRunner.class)
public class SettingsUiTest extends UiBaseTest {
    private static final String TAG = SettingsUiTest.class.getSimpleName();
    private static final Intent SETTINGS_ACTIVITY_INTENT =
            new Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS);

    private static Integer sPhotoPickerSettingsActivityState;
    @Nullable private static DeviceStatePreserver sDeviceStatePreserver;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        sPhotoPickerSettingsActivityState = PhotoPickerComponentTestUtils.getCurrentStateAndEnable(
                PICKER_SETTINGS_ACTIVITY_COMPONENT);

        sDeviceStatePreserver = new DeviceStatePreserver(sDevice);
        sDeviceStatePreserver.saveCurrentCloudProviderState();

        PhotoPickerCloudTestUtils.enableCloudMediaAndSetAllowedCloudProviders(
                NAMESPACE_MEDIAPROVIDER, /* allowedCloudProviders */ sTargetPackageName);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();

        if (sPhotoPickerSettingsActivityState != null) {
            PhotoPickerComponentTestUtils.setState(
                    PICKER_SETTINGS_ACTIVITY_COMPONENT, sPhotoPickerSettingsActivityState);
        }

        if (sDeviceStatePreserver != null) {
            sDeviceStatePreserver.restoreCloudProviderState();
        }
    }

    @Test
    public void testSettings_launchProviderSettingsFromExtraWidget() throws Exception {
        // set cloud provider
        PhotoPickerCloudTestUtils.setCloudProvider(sDevice, CloudProviderSecondary.AUTHORITY);
        assertThat(PhotoPickerCloudTestUtils.getCurrentCloudProvider(sDevice))
                .isEqualTo(CloudProviderSecondary.AUTHORITY);

        // Launch PhotoPickerSettingsActivity.
        launchSettingsActivityWithRetry(/* retryCount */ 3, /* backoffSeedInMillis */ 500);

        // Verify PhotoPickerSettingsActivity is launched and visible.
        SettingsTestUtils.verifySettingsActivityIsVisible(sDevice);

        // verify account
        SettingsTestUtils.verifySettingsCloudProviderAccountIsVisible(sDevice,
                CloudProviderSecondary.ACCOUNT_NAME);

        // find extra widget and click, assert test activity launched
        SettingsTestUtils.findAndClickSettingsProviderOptionExtraWidget(sDevice);
        TestActivityUtils.assertThatTestActivityIsVisible(sDevice, sTargetPackageName);
    }

    private void launchSettingsActivityWithRetry(long maxRetries, long backoffSeedInMillis)
            throws InterruptedException {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                // If the Settings Activity component has been recently enabled, it may take some
                // time for the resolver to resolve the intent to the right activity.
                long backoffTimeInMillis = backoffSeedInMillis * (2 ^ (attempt - 1));
                Log.e(TAG, "Retry launching activity for " + SETTINGS_ACTIVITY_INTENT
                        + " after backoff " + backoffTimeInMillis);
                Thread.sleep(backoffTimeInMillis);
            }

            try {
                mActivity.startActivity(SETTINGS_ACTIVITY_INTENT);
                sDevice.waitForIdle();
                return;
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Activity not found for intent " + SETTINGS_ACTIVITY_INTENT);
            }
        }

        Log.e(TAG, "Intent " + SETTINGS_ACTIVITY_INTENT + " does not resolve to any component.");
        throw new AssertionError("Cannot find activity for intent " + SETTINGS_ACTIVITY_INTENT);
    }
}
