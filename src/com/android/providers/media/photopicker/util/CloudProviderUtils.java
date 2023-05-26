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
import static android.provider.CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME;
import static android.provider.MediaStore.EXTRA_CLOUD_PROVIDER;
import static android.provider.MediaStore.GET_CLOUD_PROVIDER_CALL;
import static android.provider.MediaStore.GET_CLOUD_PROVIDER_RESULT;
import static android.provider.MediaStore.SET_CLOUD_PROVIDER_CALL;

import static java.util.Collections.emptyList;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.model.UserId;

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
        if (!configStore.isCloudMediaInPhotoPickerEnabled()) {
            Log.i(TAG, "Returning an empty list of available cloud providers since the "
                    + "Cloud-Media-in-Photo-Picker feature is disabled.");
            return emptyList();
        }

        Objects.requireNonNull(context);

        ignoreAllowlist = ignoreAllowlist || !configStore.shouldEnforceCloudProviderAllowlist();

        final List<CloudProviderInfo> providers = new ArrayList<>();

        // We do not need to get the allowlist from the ConfigStore if we are going to skip
        // if-allowlisted check below.
        final List<String> allowlistedPackages =
                ignoreAllowlist ? null : configStore.getAllowedCloudProviderPackages();

        final Intent intent = new Intent(CloudMediaProviderContract.PROVIDER_INTERFACE);
        final List<ResolveInfo> allAvailableProviders = getAllCloudProvidersForUser(context,
                intent, userHandle);

        for (ResolveInfo info : allAvailableProviders) {
            final ProviderInfo providerInfo = info.providerInfo;
            if (providerInfo.authority == null) {
                // Provider does NOT declare an authority.
                continue;
            }

            if (!MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION.equals(providerInfo.readPermission)) {
                // Provider does NOT have the right read permission.
                continue;
            }

            if (!ignoreAllowlist && !allowlistedPackages.contains(providerInfo.packageName)) {
                // Provider is not allowlisted.
                continue;
            }

            final CloudProviderInfo cloudProvider = new CloudProviderInfo(
                    providerInfo.authority,
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

    /**
     * Request content provider to change cloud provider.
     */
    public static boolean persistSelectedProvider(
            @NonNull ContentProviderClient client,
            @Nullable String newCloudProvider) throws RemoteException {
        final Bundle input = new Bundle();
        input.putString(EXTRA_CLOUD_PROVIDER, newCloudProvider);
        client.call(SET_CLOUD_PROVIDER_CALL, /* arg */ null, /* extras */ input);
        return true;
    }

    /**
     * Fetch selected cloud provider from content provider.
     * @param defaultAuthority is the default returned in case query result is null.
     * @return fetched cloud provider authority if it is non-null.
     *               Otherwise return defaultAuthority.
     */
    @Nullable
    public static String fetchProviderAuthority(
            @NonNull ContentProviderClient client,
            @NonNull String defaultAuthority) throws RemoteException {
        final Bundle result = client.call(GET_CLOUD_PROVIDER_CALL, /* arg */ null,
                /* extras */ null);
        return result.getString(GET_CLOUD_PROVIDER_RESULT, defaultAuthority);
    }

    /**
     * @return the label for the {@link ProviderInfo} with {@code authority} for the given
     *         {@link UserHandle}.
     */
    @Nullable
    public static String getProviderLabelForUser(@NonNull Context context, @NonNull UserHandle user,
            @Nullable String authority) throws PackageManager.NameNotFoundException {
        if (authority == null) {
            return null;
        }

        final PackageManager packageManager = UserId.of(user).getPackageManager(context);
        return getProviderLabel(packageManager, authority);
    }

    /**
     * @return the label for the {@link ProviderInfo} with {@code authority}.
     */
    @NonNull
    public static String getProviderLabel(@NonNull PackageManager packageManager,
            @NonNull String authority) {
        final ProviderInfo providerInfo = packageManager.resolveContentProvider(
                authority, /* flags */ 0);
        return getProviderLabel(packageManager, providerInfo);
    }

    /**
     * @return the label for the given {@link ProviderInfo}.
     */
    @NonNull
    public static String getProviderLabel(@NonNull PackageManager packageManager,
            @NonNull ProviderInfo providerInfo) {
        return String.valueOf(providerInfo.loadLabel(packageManager));
    }

    /**
     * @return the current cloud media account name for the {@link CloudMediaProvider} with the
     *         given {@code cloudMediaProviderAuthority}.
     */
    @Nullable
    public static String getCloudMediaAccountName(@NonNull ContentResolver resolver,
            @Nullable String cloudMediaProviderAuthority) {
        if (cloudMediaProviderAuthority == null) {
            return null;
        }

        try (ContentProviderClient client =
                     resolver.acquireContentProviderClient(cloudMediaProviderAuthority)) {
            final Bundle out = client.call(METHOD_GET_MEDIA_COLLECTION_INFO, /* arg */ null,
                    /* extras */ null);
            return out.getString(ACCOUNT_NAME);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
