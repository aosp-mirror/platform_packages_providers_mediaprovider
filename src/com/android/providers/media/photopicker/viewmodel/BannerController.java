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
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getCloudMediaAccountName;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getProviderLabelForUser;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.model.UserId;

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
            "last_cloud_provider_authority";
    /**
     * Key to the {@link SharedPreferences} value of the last received
     * {@link android.provider.CloudMediaProvider} account name by the UI (PhotoPicker) process.
     */
    private static final String PREFS_KEY_CLOUD_PROVIDER_ACCOUNT_NAME =
            "last_cloud_provider_account_name";

    // Authority of the current cloud media provider
    @Nullable
    private String mCmpAuthority;

    // Label of the current cloud media provider
    @Nullable
    private String mCmpLabel;

    // Account name in the current cloud media provider
    @Nullable
    private String mCmpAccountName;

    // Boolean Choose App Banner visibility
    private boolean mShowChooseAppBanner;

    // Boolean Choose App Banner visibility
    private boolean mShowCloudMediaAvailableBanner;

    BannerController(@NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {

        // TODO(b/268255830): Show picker banners in the work profile as per the cloud provider
        //  state in the work profile when launched from the personal profile and vice-versa.
        if (!isCrossProfile(userHandle)) {
            // Fetch the last cached cloud media info from ui prefs and save.
            mCmpAuthority = getUiPrefs(context)
                    .getString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, /* defValue */ null);
            mCmpAccountName = getUiPrefs(context)
                    .getString(PREFS_KEY_CLOUD_PROVIDER_ACCOUNT_NAME, /* defValue */ null);
        }

        initialise(context, configStore, userHandle);
    }

    /**
     * Same as {@link #initialise(Context, ConfigStore, UserHandle)}, renamed for readability.
     */
    void reset(@NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        initialise(context, configStore, userHandle);
    }

    /**
     * Initialise the banner controller data
     *
     * 0. Assert non-main thread.
     * 1. Fetch the latest cloud provider info.
     * 2. If the previous & new cloud provider infos are the same, No-op.
     * 3. Reset should show banners.
     * 4. Update the saved and cached cloud provider info with the latest info.
     *
     * Note : This method is expected to be called only in a non-main thread since we shouldn't
     * block the UI thread on the heavy Binder calls to fetch the cloud media provider info.
     */
    private void initialise(@NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        final String lastCmpAuthority = mCmpAuthority, lastCmpAccountName = mCmpAccountName;

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
            // 0. Assert non-main thread.
            assertNonMainThread();

            // 1. Fetch the latest cloud provider info.
            final ContentResolver contentResolver =
                    UserId.of(userHandle).getContentResolver(context);
            mCmpAuthority = getCurrentCloudProvider(contentResolver);
            mCmpLabel = getProviderLabelForUser(context, userHandle, mCmpAuthority);
            mCmpAccountName = getCloudMediaAccountName(contentResolver, mCmpAuthority);

            // Not logging the account name due to privacy concerns
            Log.d(TAG, "Current CloudMediaProvider authority: " + mCmpAuthority + ", label: "
                    + mCmpLabel);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Could not fetch the current CloudMediaProvider", e);
            hideBanners();
            return;
        }

        // TODO(b/268255830): Show picker banners in the work profile as per the cloud provider
        //  state in the work profile when launched from the personal profile and vice-versa.
        // Hide cross profile banners until cross profile shared preferences access is resolved.
        if (isCrossProfile(userHandle)) {
            // no-op
            return;
        }

        // 2. If the previous & new cloud provider infos are the same, No-op.
        if (TextUtils.equals(lastCmpAuthority, mCmpAuthority)
                && TextUtils.equals(lastCmpAccountName, mCmpAccountName)) {
            // no-op
            return;
        }

        // 3. Reset should show banners.
        // mShowChooseAppBanner is true iff new authority is null and the available cloud
        // providers list is not empty.
        mShowChooseAppBanner = (mCmpAuthority == null)
                && !getAvailableCloudProviders(context, configStore, userHandle).isEmpty();
        // mShowCloudMediaAvailableBanner is true iff the new authority AND account name are
        // NOT null while the old authority OR account is / are null.
        mShowCloudMediaAvailableBanner = mCmpAuthority != null && mCmpAccountName != null
                && (lastCmpAuthority == null || lastCmpAccountName == null);

        // 4. Update the saved and cached cloud provider info with the latest info.
        final SharedPreferences.Editor uiPrefsEditor = getUiPrefs(context).edit();
        if (!TextUtils.equals(mCmpAuthority, lastCmpAuthority)) {
            uiPrefsEditor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, mCmpAuthority);
        }
        if (!TextUtils.equals(mCmpAccountName, lastCmpAccountName)) {
            uiPrefsEditor.putString(PREFS_KEY_CLOUD_PROVIDER_ACCOUNT_NAME, mCmpAccountName);
        }
        uiPrefsEditor.apply();
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
    private static SharedPreferences getUiPrefs(@NonNull Context context) {
        return context.getSharedPreferences(UI_PREFS_FILE_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Hide all banners (Fallback for error scenarios)
     */
    private void hideBanners() {
        mCmpAuthority = null;
        mCmpLabel = null;
        mCmpAccountName = null;
        mShowChooseAppBanner = false;
        mShowCloudMediaAvailableBanner = false;
    }

    /**
     * @return the authority of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderAuthority() {
        return mCmpAuthority;
    }

    /**
     * @return the label of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderLabel() {
        return mCmpLabel;
    }

    /**
     * @return the account name of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderAccountName() {
        return mCmpAccountName;
    }

    /**
     * @return the 'Choose App' banner visibility {@link #mShowChooseAppBanner}.
     */
    boolean shouldShowChooseAppBanner() {
        return mShowChooseAppBanner;
    }

    /**
     * @return the 'Cloud Media Available' banner visibility
     *         {@link #mShowCloudMediaAvailableBanner}.
     */
    boolean shouldShowCloudMediaAvailableBanner() {
        return mShowCloudMediaAvailableBanner;
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

    /**
     * Dismiss (hide) the 'Cloud Media Available' banner
     *
     * Set the 'Cloud Media Available' banner visibility {@link #mShowCloudMediaAvailableBanner}
     * as {@code false}.
     */
    void onUserDismissedCloudMediaAvailableBanner() {
        if (!mShowCloudMediaAvailableBanner) {
            Log.wtf(TAG, "Choose app banner visibility for current user is false on dismiss");
        } else {
            mShowCloudMediaAvailableBanner = false;
        }
    }

    private static void assertNonMainThread() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            return;
        }

        throw new IllegalStateException("Expected to NOT be called from the main thread."
                + " Current thread: " + Thread.currentThread());
    }
}
