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

package com.android.providers.media.photopicker.ui.settings;

import static android.provider.MediaStore.AUTHORITY;

import static com.android.providers.media.photopicker.util.CloudProviderUtils.fetchProviderAuthority;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getAvailableCloudProviders;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getCloudMediaCollectionInfo;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.persistSelectedProvider;

import static java.util.Objects.requireNonNull;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.util.ForegroundThread;

import java.util.ArrayList;
import java.util.List;

/**
 * SettingsCloudMediaViewModel stores cloud media app settings data for each profile.
 */
public class SettingsCloudMediaViewModel extends ViewModel {
    static final String NONE_PREF_KEY = "none";
    private static final String TAG = "SettingsFragVM";
    private static final long GET_CLOUD_MEDIA_COLLECTION_INFO_TIMEOUT_IN_MILLIS = 10000L;

    @NonNull
    private final Context mContext;
    @NonNull
    private final MutableLiveData<CloudProviderMediaCollectionInfo>
            mCurrentProviderMediaCollectionInfo;
    @NonNull
    private final List<CloudMediaProviderOption> mProviderOptions;
    @NonNull
    private final UserId mUserId;
    @Nullable
    private String mSelectedProviderAuthority;

    SettingsCloudMediaViewModel(
            @NonNull Context context,
            @NonNull UserId userId) {
        super();

        mContext = requireNonNull(context);
        mUserId = requireNonNull(userId);
        mProviderOptions = new ArrayList<>();
        mSelectedProviderAuthority = null;
        mCurrentProviderMediaCollectionInfo = new MutableLiveData<>();
    }

    @NonNull
    List<CloudMediaProviderOption> getProviderOptions() {
        return mProviderOptions;
    }

    @Nullable
    String getSelectedProviderAuthority() {
        return mSelectedProviderAuthority;
    }

    @NonNull
    LiveData<CloudProviderMediaCollectionInfo> getCurrentProviderMediaCollectionInfo() {
        return mCurrentProviderMediaCollectionInfo;
    }

    @NonNull
    String getSelectedPreferenceKey() {
        return getPreferenceKey(mSelectedProviderAuthority);
    }

    /**
     * Fetch and cache the available cloud provider options and the selected provider.
     */
    void loadData(@NonNull ConfigStore configStore) {
        refreshProviderOptions(configStore);
        refreshSelectedProvider();
    }

    /**
     * Updates the selected cloud provider on disk and in cache.
     * Returns true if the update was successful.
     */
    boolean updateSelectedProvider(@NonNull String newPreferenceKey) {
        final String newCloudProvider = getProviderAuthority(newPreferenceKey);
        try (ContentProviderClient client = getContentProviderClient()) {
            if (client == null) {
                // This could happen when work profile is turned off after opening the Settings
                // page. The work tab would still be visible but the MP process for work profile
                // will not be running.
                return false;
            }
            final boolean success =
                    persistSelectedProvider(client, newCloudProvider);
            if (success) {
                mSelectedProviderAuthority = newCloudProvider;
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not persist selected cloud provider", e);
        }
        return false;
    }

    @Nullable
    private String getProviderAuthority(@NonNull String preferenceKey) {
        // For None option, the provider auth should be null to disable cloud media provider.
        return preferenceKey.equals(SettingsCloudMediaViewModel.NONE_PREF_KEY)
                ? null : preferenceKey;
    }

    @NonNull
    private String getPreferenceKey(@Nullable String providerAuthority) {
        return providerAuthority == null
                ? SettingsCloudMediaViewModel.NONE_PREF_KEY : providerAuthority;
    }

    private void refreshProviderOptions(@NonNull ConfigStore configStore) {
        mProviderOptions.clear();
        mProviderOptions.addAll(fetchProviderOptions(configStore));
        mProviderOptions.add(getNoneProviderOption());
    }

    private void refreshSelectedProvider() {
        try (ContentProviderClient client = getContentProviderClient()) {
            if (client == null) {
                // TODO(b/266927613): Handle the edge case where work profile is turned off
                //  while user is on the settings page but work tab's data is not fetched yet.
                throw new IllegalArgumentException("Could not get selected cloud provider"
                        + " because Media Provider client is null.");
            }
            mSelectedProviderAuthority =
                    fetchProviderAuthority(client, /* default */ NONE_PREF_KEY);
        } catch (Exception e) {
            // Since displaying the current cloud provider is the core function of the Settings
            // page, if we're not able to fetch this info, there is no point in displaying this
            // activity.
            throw new IllegalArgumentException("Could not get selected cloud provider", e);
        }
    }

    @UiThread
    void loadMediaCollectionInfoAsync() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            // This method should only be run from the UI thread so that fetch media collection info
            // requests are executed serially.
            Log.w(TAG, "loadMediaCollectionInfoAsync method needs to be called from the UI thread");
            return;
        }

        final String providerAuthority = getSelectedProviderAuthority();
        // Foreground thread internally uses a queue to execute each request in a serialized manner.
        ForegroundThread.getExecutor().execute(() -> {
            mCurrentProviderMediaCollectionInfo.postValue(
                    fetchMediaCollectionInfoFromProvider(providerAuthority));
        });
    }

    @Nullable
    private CloudProviderMediaCollectionInfo fetchMediaCollectionInfoFromProvider(
            @Nullable String currentProviderAuthority) {
        // If the selected cloud provider preference is "None", the media collection info is not
        // applicable.
        if (currentProviderAuthority == null) {
            return null;
        }

        Bundle cloudMediaCollectionInfo = null;
        try {
            final ContentResolver currentUserContentResolver = mUserId.getContentResolver(mContext);
            cloudMediaCollectionInfo = getCloudMediaCollectionInfo(currentUserContentResolver,
                    currentProviderAuthority, GET_CLOUD_MEDIA_COLLECTION_INFO_TIMEOUT_IN_MILLIS);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch media collection info from the cloud media provider.", e);
        }

        if (cloudMediaCollectionInfo == null) {
            return new CloudProviderMediaCollectionInfo(currentProviderAuthority);
        }

        final String accountName = cloudMediaCollectionInfo.getString(
                CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME);
        final Intent cloudProviderSettingsActivityIntent = cloudMediaCollectionInfo.getParcelable(
                CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT);
        return new CloudProviderMediaCollectionInfo(currentProviderAuthority, accountName,
                cloudProviderSettingsActivityIntent);
    }

    @NonNull
    private List<CloudMediaProviderOption> fetchProviderOptions(@NonNull ConfigStore configStore) {
        // Get info of available cloud providers.
        List<CloudProviderInfo> cloudProviders = getAvailableCloudProviders(
                        mContext, configStore, UserHandle.of(mUserId.getIdentifier()));

        return getProviderOptionsFromCloudProviderInfos(cloudProviders);
    }

    @NonNull
    private List<CloudMediaProviderOption> getProviderOptionsFromCloudProviderInfos(
            @NonNull List<CloudProviderInfo> cloudProviders) {
        // TODO(b/195009187): In case current cloud provider is not part of the allow list, it will
        // not be listed on the Settings page. Handle this case so that it does show up.
        final List<CloudMediaProviderOption> providerOption = new ArrayList<>();
        for (CloudProviderInfo cloudProvider : cloudProviders) {
            providerOption.add(
                    CloudMediaProviderOption
                            .fromCloudProviderInfo(cloudProvider, mContext, mUserId));
        }
        return providerOption;
    }

    @NonNull
    private CloudMediaProviderOption getNoneProviderOption() {
        final Drawable nonePrefIcon = mContext.getDrawable(R.drawable.ic_cloud_picker_off);
        final String nonePrefLabel = mContext.getString(R.string.picker_settings_no_provider);

        return new CloudMediaProviderOption(NONE_PREF_KEY, nonePrefLabel, nonePrefIcon);
    }

    @Nullable
    @VisibleForTesting
    public ContentProviderClient getContentProviderClient()
            throws PackageManager.NameNotFoundException {
        return mUserId
                .getContentResolver(mContext)
                .acquireUnstableContentProviderClient(AUTHORITY);
    }
}
