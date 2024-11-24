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
import android.database.Cursor;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.sync.PickerSearchProviderClient;

import java.util.Objects;

/**
 * Provides with the search feature enabled state by checking different flags in Picker backend and
 * the current cloud media provider. Also caches the state to avoid IPC calls to the cloud media
 * provider.
 */
public class SearchState {
    private static final String TAG = "PickerSearchState";
    @NonNull
    private final ConfigStore mConfigStore;
    private boolean mIsCloudSearchEnabled = false;
    @Nullable
    private String mCloudAuthority = null;

    public SearchState(@NonNull ConfigStore configStore) {
        mConfigStore = configStore;
    }

    /**
     * Returns true if cloud search is enabled for the given cloud provider.
     */
    public boolean isCloudSearchEnabled(
            @NonNull Context context,
            @NonNull String cloudAuthority) {
        requireNonNull(cloudAuthority);

        isCloudSearchEnabled(context);

        synchronized (SearchState.class) {
            return mIsCloudSearchEnabled && cloudAuthority.equals(mCloudAuthority);
        }
    }

    /**
     * Either returns the previously cached value of whether cloud search is enabled or
     * checks with the cloud media provider has search enabled, caches the result and returns it.
     */
    public boolean isCloudSearchEnabled(
            @NonNull Context context) {
        if (!isSearchEnabled(context)) {
            Log.d(TAG, "Search is disabled on the device.");
            return false;
        }

        final String currentCloudAuthority = PickerSyncController
                .getInstanceOrThrow()
                .getCloudProviderOrDefault(null);

        synchronized (SearchState.class) {
            // Check if cache is up to date.
            if (Objects.equals(mCloudAuthority, currentCloudAuthority)) {
                return mIsCloudSearchEnabled;
            }

            // Refresh cache
            if (currentCloudAuthority == null) {
                mIsCloudSearchEnabled = false;
                mCloudAuthority = null;
                Log.d(TAG, "Current cloud authority is null");
            } else {
                final PickerSearchProviderClient client =
                        PickerSearchProviderClient.create(context, currentCloudAuthority);

                if (Flags.enableCloudMediaProviderCapabilities()) {
                    mIsCloudSearchEnabled = client.fetchCapabilities().isSearchEnabled();
                    mCloudAuthority = currentCloudAuthority;
                    Log.d(TAG, String.format(
                            "Current cloud media provider: %s, Are search capabilities enabled: %s",
                            currentCloudAuthority,
                            mIsCloudSearchEnabled));
                } else {
                    try (Cursor ignored = client.fetchSearchResultsFromCmp(
                            /* suggestedMediaSetId */ null,
                            /* searchText */ "",
                            CloudMediaProviderContract.SORT_ORDER_DESC_DATE_TAKEN,
                            /* pageSize */ 0,
                            /* resumePageToken */ null,
                            /* cancellationSignal */ null)) {
                        Log.d(TAG, "Search APIs are implemented by the cloud provider "
                                + currentCloudAuthority);
                        mIsCloudSearchEnabled = true;
                        mCloudAuthority = currentCloudAuthority;
                    } catch (UnsupportedOperationException e) {
                        Log.d(TAG, "Search APIs are NOT implemented by the cloud provider "
                                + currentCloudAuthority);
                        mIsCloudSearchEnabled = false;
                        mCloudAuthority = currentCloudAuthority;
                    }
                }
            }

            return mIsCloudSearchEnabled;
        }
    }

    public boolean isLocalSearchEnabled() {
        // TODO implement this later for local search.
        return false;
    }

    /**
     * Clears the cached values.
     */
    public void clearCache() {
        synchronized (SearchState.class) {
            mIsCloudSearchEnabled = false;
            mCloudAuthority = null;
        }
    }

    private boolean isSearchEnabled(@NonNull Context context) {
        if (!isHardwareSupported(context)) {
            Log.d(TAG, "Hardware is not supported.");
            return false;
        }

        if (!Flags.cloudMediaProviderSearch()) {
            Log.d(TAG, "Search APIs are disabled.");
            return false;
        }

        if (!mConfigStore.isSearchFeatureEnabled()) {
            Log.d(TAG, "Search feature is disabled.");
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
