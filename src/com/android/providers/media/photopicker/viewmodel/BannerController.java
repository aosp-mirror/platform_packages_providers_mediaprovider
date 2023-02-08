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

package com.android.providers.media.photopicker.viewmodel;

import static android.provider.MediaStore.getCurrentCloudProvider;

import static com.android.providers.media.photopicker.util.CloudProviderUtils.getAvailableCloudProviders;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.model.UserId;

import java.util.Objects;

/**
 * Banner Controller to store and handle the banner data per user for {@link PhotoPickerActivity}.
 */
class BannerController {
    private static final String TAG = "BannerController";
    private static final String UI_PREFS_FILE_NAME = "picker_ui_prefs";
    /**
     * Key to the {@link SharedPreferences} value of the last received
     * {@link android.provider.CloudMediaProvider} authority by the UI (PhotoPicker) process.
     */
    private static final String PREFS_KEY_CLOUD_PROVIDER_AUTHORITY =
            "cached_cloud_provider_authority";

    /**
     * Last received {@link android.provider.CloudMediaProvider} authority by the UI (PhotoPicker)
     * process.
     */
    @Nullable
    private String mCloudMediaProviderAuthority;

    // Boolean Choose App Banner visibility
    private boolean mShowChooseAppBanner;

    BannerController(@NonNull Context appContext, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        // Hide cross profile banners until cross profile shared preferences access is resolved.
        if (isCrossProfile(userHandle)) {
            return;
        }

        // Fetch the last cached authority from ui prefs and save.
        mCloudMediaProviderAuthority = getUiPrefs(appContext)
                .getString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, /* defValue */ null);

        initialise(appContext, configStore, userHandle);
    }

    /**
     * Same as {@link #initialise(Context, ConfigStore, UserHandle)}, renamed for readability.
     */
    void reset(@NonNull Context appContext, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        // Hide cross profile banners until cross profile shared preferences access is resolved.
        if (isCrossProfile(userHandle)) {
            return;
        }

        initialise(appContext, configStore, userHandle);
    }

    /**
     * Initialise the banner controller data
     *
     * 1. Fetch the latest cloud provider info.
     * 2. If the previous & new cloud provider infos are the same, No-op.
     * 3. Reset should show banners.
     * 4. Update the saved and cached cloud provider info with the latest info.
     */
    private void initialise(@NonNull Context appContext, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {

        // 1. Fetch the latest cloud provider info.
        final String newCloudMediaProviderAuthority;
        // TODO(b/245746037): Remove try-catch for the RuntimeException.
        //  Under the hood MediaStore.getCurrentCloudProvider() makes an IPC call to the primary
        //  MediaProvider process, where we currently perform a UID check (making sure that
        //  the call both sender and receiver belong to the same UID).
        //  This setup works for our "regular" PhotoPickerActivity (running in :PhotoPicker
        //  process), but does not work for our test applications (installed to a different
        //  UID), that provide a mock PhotoPickerActivity which will also run this code.
        //  SOLUTION: replace the UID check on the receiving end (in MediaProvider) with a
        //  check for MANAGE_CLOUD_MEDIA_PROVIDER permission.
        try {
            final ContentResolver contentResolver =
                    UserId.of(userHandle).getContentResolver(appContext);
            newCloudMediaProviderAuthority = getCurrentCloudProvider(contentResolver);
            Log.d(TAG, "Current CloudMediaProvider authority: " + newCloudMediaProviderAuthority);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Could not fetch the current CloudMediaProvider", e);
            hideBanners();
            return;
        }

        // 2. If the previous & new cloud provider infos are the same, No-op.
        if (Objects.equals(mCloudMediaProviderAuthority, newCloudMediaProviderAuthority)) {
            // no-op
            return;
        }

        // 3. Reset should show banners.
        // mShowChooseAppBanner is true iff new authority is null and the available cloud
        // providers list is not empty.
        if (newCloudMediaProviderAuthority == null) {
            mShowChooseAppBanner =
                    !getAvailableCloudProviders(appContext, configStore, userHandle).isEmpty();
        } else {
            mShowChooseAppBanner = false;
        }

        // 4. Update the saved and cached cloud provider info with the latest info.
        mCloudMediaProviderAuthority = newCloudMediaProviderAuthority;
        getUiPrefs(appContext).edit()
                .putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, mCloudMediaProviderAuthority)
                .apply();
    }

    /**
     * @return {@code true} if the given {@link UserHandle} is not the calling user,
     *         {@code false} otherwise.
     */
    private static boolean isCrossProfile(@NonNull UserHandle userHandle) {
        return !Process.myUserHandle().equals(userHandle);
    }

    @NonNull
    // TODO(b/267525755): Migrate the Picker UI Shared preferences actions to a helper class that
    //  ensures synchronization.
    private static SharedPreferences getUiPrefs(@NonNull Context appContext) {
        return appContext.getSharedPreferences(UI_PREFS_FILE_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Hide all banners (Fallback for error scenarios)
     */
    private void hideBanners() {
        mShowChooseAppBanner = false;
    }

    /**
     * If the controller user handle is of the user profile the picker was launched from,
     * @return the {@link android.content.ContentProvider#mAuthority authority} of the current
     *         {@link android.provider.CloudMediaProvider}
     * Else, return {@code null}.
     */
    @Nullable
    String getCloudMediaProviderAuthority() {
        return mCloudMediaProviderAuthority;
    }

    /**
     * @return the 'Choose App' banner visibility {@link #mShowChooseAppBanner}.
     */
    boolean shouldShowChooseAppBanner() {
        return mShowChooseAppBanner;
    }

    /**
     * Dismiss (hide) the 'Choose App' banner
     *
     * Set the 'Choose App' banner visibility {@link #mShowChooseAppBanner} as {@code false}.
     */
    void onUserDismissedChooseAppBanner() {
        if (!mShowChooseAppBanner) {
            Log.wtf(TAG, "Choose app banner visibility for current user is false on dismiss");
        } else {
            mShowChooseAppBanner = false;
        }
    }
}
