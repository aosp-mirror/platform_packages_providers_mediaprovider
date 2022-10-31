/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.util;

import static android.provider.CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.CloudProviderInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility methods for retrieving available and/or allowlisted Cloud Providers.
 *
 * @see CloudMediaProvider
 */
public class CloudProviderUtils {
    private static final String TAG = "CloudProviderUtils";
    /**
     * @return list of available <b>and</b> allowlisted {@link CloudMediaProvider}-s for the current
     * user.
     */
    public static List<CloudProviderInfo> getAvailableCloudProviders(
            @NonNull Context context, @NonNull ConfigStore configStore) {
        return getAvailableCloudProviders(
                context, configStore, Process.myUserHandle());
    }

    /**
     * @return list of available <b>and</b> allowlisted {@link CloudMediaProvider}-s for the given
     * userId.
     */
    public static List<CloudProviderInfo> getAvailableCloudProviders(
            @NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        return getAvailableCloudProvidersInternal(
                context, configStore, /* ignoreAllowList */ false, userHandle);
    }

    /**
     * @return list of <b>all</b> available {@link CloudMediaProvider}-s (<b>ignoring</b> the
     *         allowlist) for the current user.
     */
    public static List<CloudProviderInfo> getAllAvailableCloudProviders(
            @NonNull Context context, @NonNull ConfigStore configStore) {
        return getAllAvailableCloudProviders(context, configStore, Process.myUserHandle());
    }

    /**
     * @return list of <b>all</b> available {@link CloudMediaProvider}-s (<b>ignoring</b> the
     *         allowlist) for the given userId.
     */
    public static List<CloudProviderInfo> getAllAvailableCloudProviders(
            @NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        return getAvailableCloudProvidersInternal(context, configStore, /* ignoreAllowList */ true,
                userHandle);
    }

    private static List<CloudProviderInfo> getAvailableCloudProvidersInternal(
            @NonNull Context context, @NonNull ConfigStore configStore, boolean ignoreAllowlist,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(context);

        final List<CloudProviderInfo> providers = new ArrayList<>();

        // We do not need to read the allowlist from the ConfigStore (DeviceConfig) if we are not
        // going to skip if-allowlisted check below.
        final List<String> allowlistedProviders =
                ignoreAllowlist ? null : configStore.getAllowlistedCloudProviders();

        final Intent intent = new Intent(CloudMediaProviderContract.PROVIDER_INTERFACE);
        final List<ResolveInfo> allAvailableProviders = getAllCloudProvidersForUser(context,
                intent, userHandle);

        for (ResolveInfo info : allAvailableProviders) {
            final ProviderInfo providerInfo = info.providerInfo;
            final String authority = providerInfo.authority;

            if (authority == null) {
                // Provider does NOT declare an authority.
                continue;
            }

            if (!MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION.equals(providerInfo.readPermission)) {
                // Provider does NOT have the right read permission.
                continue;
            }

            if (!ignoreAllowlist && !allowlistedProviders.contains(authority)) {
                // Provider is not allowlisted.
                continue;
            }

            final CloudProviderInfo cloudProvider = new CloudProviderInfo(authority,
                    providerInfo.applicationInfo.packageName,
                    providerInfo.applicationInfo.uid);
            providers.add(cloudProvider);
        }

        Log.d(TAG, (ignoreAllowlist ? "All (ignoring allowlist)" : "")
                + "Available CloudMediaProvider-s: " + providers);
        return providers;
    }

    /**
     * Returns a list of all available providers with the given intent for a userId. If userId is
     * null, results are returned for the current user.
     */
    private static List<ResolveInfo> getAllCloudProvidersForUser(@NonNull Context context,
            @NonNull Intent intent, @NonNull UserHandle userHandle) {
        return context.getPackageManager()
                .queryIntentContentProvidersAsUser(intent, 0, userHandle);
    }
}
