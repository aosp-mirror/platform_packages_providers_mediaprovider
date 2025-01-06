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

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.sync.PickerSearchProviderClient;

/**
 * Contains the logic to decide if search feature is enabled.
 */
public class SearchState {
    private static final String TAG = "PickerSearchState";
    private final ConfigStore mConfigStore;

    public SearchState(@NonNull ConfigStore configStore) {
        mConfigStore = requireNonNull(configStore);
    }

    /**
     * Returns true if cloud search is enabled for the given cloud provider.
     */
    public boolean isCloudSearchEnabled(
            @NonNull Context context,
            @NonNull String cloudAuthority) {
        requireNonNull(context);
        requireNonNull(cloudAuthority);

        if (!isSearchFeatureEnabled(context)) {
            Log.d(TAG, "Search feature is disabled on the device.");
            return false;
        }

        final PickerSearchProviderClient client =
                PickerSearchProviderClient.create(context, cloudAuthority);
        final boolean cloudPickerSearchState =  client.fetchCapabilities().isSearchEnabled();
        Log.d(TAG, String.format(
                "Current cloud media provider: %s, Is search capability available: %s",
                cloudAuthority,
                cloudPickerSearchState));

        return cloudPickerSearchState;
    }

    /**
     * Returns true if cloud search is enabled for the current cloud provider.
     */
    public boolean isCloudSearchEnabled(@NonNull Context context) {
        final String currentCloudAuthority = PickerSyncController
                .getInstanceOrThrow()
                .getCloudProviderOrDefault(null);

        return isCloudSearchEnabled(context, currentCloudAuthority);
    }

    public boolean isLocalSearchEnabled() {
        // Local search is not implemented yet.
        return false;
    }

    /**
     * Checks if the search feature is enabled on the device.
     */
    private boolean isSearchFeatureEnabled(Context context) {
        if (!SdkLevel.isAtLeastT()) {
            Log.d(TAG, "SDK level is less than T.");
            return false;
        }

        if (!isHardwareSupported(context)) {
            Log.d(TAG, "Hardware is not supported.");
            return false;
        }

        if (!mConfigStore.isModernPickerEnabled()) {
            Log.d(TAG, "Modern picker is disabled.");
            return false;
        }

        if (!Flags.cloudMediaProviderSearch()) {
            Log.d(TAG, "Search APIs are disabled.");
            return false;
        }

        if (!Flags.enableCloudMediaProviderCapabilities()) {
            Log.d(TAG, "Capability API is disabled.");
            return false;
        }

        if (!Flags.enablePhotopickerSearch()) {
            Log.d(TAG, "Search feature flag is disabled.");
            return false;
        }

        return true;
    }

    private boolean isHardwareSupported(@NonNull Context context) {
        // The Search feature in Picker is disabled for Watches and IoT devices.
        final PackageManager pm = context.getPackageManager();
        return !pm.hasSystemFeature(PackageManager.FEATURE_EMBEDDED)
                && !pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
    }
}
