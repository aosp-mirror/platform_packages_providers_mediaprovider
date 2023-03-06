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

package com.android.providers.media.photopicker.ui.settings;

import static com.android.providers.media.photopicker.util.CloudProviderUtils.getProviderLabel;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.model.UserId;

/**
 * Class that represents a single radio button cloud provider option displayed in the
 * SettingsCloudMediaSelectFragment.
 */
class CloudMediaProviderOption {
    @NonNull private final String mKey;
    @NonNull private final CharSequence mLabel;
    @NonNull private final Drawable mIcon;

    /**
     * Creates and returns a new CloudMediaProviderOption object from given cloud provider info.
     *
     * @param cloudProviderInfo contains the details of the cloud provider.
     * @param context is the application context that is used to get application info from
     *                PackageManager.
     * @param userId is the userId creating the cloud provider option. If the userId is for a
     *               managed profile, the icon and label may be different from the one for personal
     *               profile.
     * @return a new CloudMediaProviderOption object that will be displayed as a radio button item
     *                on the Photo Picker Settings page.
     */
    @NonNull
    static CloudMediaProviderOption fromCloudProviderInfo(
            @NonNull CloudProviderInfo cloudProviderInfo,
            @NonNull Context context,
            @NonNull UserId userId) {
        try {
            // Get Package Manager as the user in the selected tab. This will ensure that for a
            // managed profile, icons are displayed with a badge. The label of an app in managed
            // profile may also be different.
            final PackageManager packageManager = userId.getPackageManager(context);
            final ProviderInfo providerInfo = packageManager.resolveContentProvider(
                    cloudProviderInfo.authority, /* flags */ 0);

            final CharSequence label = getProviderLabel(packageManager, providerInfo);
            final Drawable icon = providerInfo.loadIcon(packageManager);
            final String key = cloudProviderInfo.authority;
            return new CloudMediaProviderOption(key , label, icon);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException(
                    "Package name not found: " + cloudProviderInfo.packageName, e);
        }
    }

    CloudMediaProviderOption(@NonNull String key, @NonNull CharSequence label,
            @NonNull Drawable icon) {
        mKey = key;
        mLabel = label;
        mIcon = icon;
    }

    @NonNull
    String getKey() {
        return mKey;
    }

    @NonNull
    CharSequence getLabel() {
        return mLabel;
    }

    @NonNull
    Drawable getIcon() {
        return mIcon;
    }
}
