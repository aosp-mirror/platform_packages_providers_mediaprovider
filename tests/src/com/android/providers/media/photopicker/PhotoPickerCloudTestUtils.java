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

package com.android.providers.media.photopicker;

import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.WRITE_DEVICE_CONFIG;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.providers.media.ConfigStore.ConfigStoreImpl.KEY_CLOUD_MEDIA_FEATURE_ENABLED;
import static com.android.providers.media.ConfigStore.ConfigStoreImpl.KEY_CLOUD_MEDIA_PROVIDER_ALLOWLIST;

import static com.google.common.truth.Truth.assertWithMessage;

import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.uiautomator.UiDevice;

import com.android.modules.utils.build.SdkLevel;

import java.io.IOException;

public class PhotoPickerCloudTestUtils {
    private static final String TAG = PhotoPickerCloudTestUtils.class.getSimpleName();
    static final String INVALID_CLOUD_PROVIDER = "Invalid";

    /**
     * @return true if cloud is enabled in the device config of the given namespace,
     * otherwise false.
     */
    static boolean isCloudMediaEnabled(@NonNull String namespace) {
        return Boolean.parseBoolean(
                readDeviceConfigProp(namespace, KEY_CLOUD_MEDIA_FEATURE_ENABLED));
    }

    /**
     * @return the allowed providers in the device config of the given namespace.
     */
    @Nullable
    static String getAllowedProvidersDeviceConfig(@NonNull String namespace) {
        return readDeviceConfigProp(namespace, KEY_CLOUD_MEDIA_PROVIDER_ALLOWLIST);
    }

    /**
     * Enables cloud media and sets the allowed cloud provider in the given namespace.
     */
    public static void enableCloudMediaAndSetAllowedCloudProviders(
            @NonNull String namespace, @NonNull String allowedPackagesJoined) {
        writeDeviceConfigProp(namespace, KEY_CLOUD_MEDIA_PROVIDER_ALLOWLIST, allowedPackagesJoined);
        assertWithMessage("Failed to update the allowed cloud providers device config")
                .that(getAllowedProvidersDeviceConfig(namespace))
                .isEqualTo(allowedPackagesJoined);

        writeDeviceConfigProp(namespace, KEY_CLOUD_MEDIA_FEATURE_ENABLED, true);
        assertWithMessage("Failed to update the cloud media feature device config")
                .that(isCloudMediaEnabled(namespace))
                .isTrue();
    }

    /**
     * Disable cloud media in the given namespace.
     */
    static void disableCloudMediaAndClearAllowedCloudProviders(@NonNull String namespace) {
        writeDeviceConfigProp(namespace, KEY_CLOUD_MEDIA_FEATURE_ENABLED, false);
        assertWithMessage("Failed to update the cloud media feature device config")
                .that(isCloudMediaEnabled(namespace))
                .isFalse();

        deleteDeviceConfigProp(namespace, KEY_CLOUD_MEDIA_PROVIDER_ALLOWLIST);
        assertWithMessage("Failed to delete the allowed cloud providers device config")
                .that(getAllowedProvidersDeviceConfig(namespace))
                .isNull();
    }

    @Nullable
    private static String readDeviceConfigProp(@NonNull String namespace, @NonNull String name) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(READ_DEVICE_CONFIG);
        try {
            return DeviceConfig.getProperty(namespace, name);
        } finally {
            getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static void writeDeviceConfigProp(
            @NonNull String namespace, @NonNull String key, boolean value) {
        writeDeviceConfigProp(namespace, key, Boolean.toString(value));
    }

    private static void writeDeviceConfigProp(
            @NonNull String namespace, @NonNull String name, @NonNull String value) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(WRITE_DEVICE_CONFIG);

        try {
            DeviceConfig.setProperty(namespace, name, value, /* makeDefault= */ false);
        } finally {
            getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static void deleteDeviceConfigProp(@NonNull String namespace, @NonNull String name) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(WRITE_DEVICE_CONFIG);
        try {
            if (SdkLevel.isAtLeastU()) {
                DeviceConfig.deleteProperty(namespace, name);
            } else {
                // DeviceConfig.deleteProperty API is only available from U onwards.
                try {
                    UiDevice.getInstance(getInstrumentation())
                            .executeShellCommand(
                                    String.format("device_config delete %s %s", namespace, name));
                } catch (IOException e) {
                    Log.e(TAG, String.format("Could not delete device_config %s / %s",
                            namespace, name), e);
                }
            }
        } finally {
            getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }

    /**
     * Set cloud provider in the device to the provided authority.
     * If the provided cloud authority equals {@link #INVALID_CLOUD_PROVIDER},
     * the cloud provider will not be updated.
     */
    public static void setCloudProvider(@NonNull UiDevice sDevice,
            @Nullable String authority) throws IOException {
        if (INVALID_CLOUD_PROVIDER.equals(authority)) {
            Log.w(TAG, "Cloud provider is invalid. "
                    + "Ignoring the request to set the cloud provider to invalid");
            return;
        }
        if (authority == null) {
            sDevice.executeShellCommand(
                    "content call"
                            + " --user " + UserHandle.myUserId()
                            + " --uri content://media/"
                            + " --method set_cloud_provider"
                            + " --extra cloud_provider:n:null");
        } else {
            sDevice.executeShellCommand(
                    "content call"
                            + " --user " + UserHandle.myUserId()
                            + " --uri content://media/"
                            + " --method set_cloud_provider"
                            + " --extra cloud_provider:s:" + authority);
        }
    }

    /**
     * @return the current cloud provider.
     */
    public static String getCurrentCloudProvider(UiDevice sDevice) throws IOException {
        final String shellOutput =
                sDevice.executeShellCommand(
                        "content call"
                                + " --user " + UserHandle.myUserId()
                                + " --uri content://media/"
                                + " --method get_cloud_provider");
        return extractCloudProvider(shellOutput);
    }

    private static String extractCloudProvider(String shellOutput) {
        final String[] splitOutput;
        if (TextUtils.isEmpty(shellOutput) || ((splitOutput = shellOutput.split("=")).length < 2)) {
            throw new RuntimeException("Could not get current cloud provider. Output: "
                    + shellOutput);
        }
        String cloudProvider = splitOutput[1];
        cloudProvider = cloudProvider.substring(0, cloudProvider.length() - 3);
        if (cloudProvider.equals("null")) {
            return null;
        }
        return cloudProvider;
    }
}
