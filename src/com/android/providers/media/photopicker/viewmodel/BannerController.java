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

import static com.android.providers.media.MediaApplication.getConfigStore;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getAvailableCloudProviders;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getCloudMediaAccountName;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getProviderLabelForUser;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.util.XmlUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Banner Controller to store and handle the banner data per user for
 * {@link com.android.providers.media.photopicker.PhotoPickerActivity}.
 */
class BannerController {
    private static final String TAG = "BannerController";
    private static final String DATA_MEDIA_DIRECTORY_PATH = "/data/media/";
    private static final String LAST_CLOUD_PROVIDER_DATA_FILE_PATH_IN_USER_MEDIA_DIR =
            "/.transforms/picker/last_cloud_provider_info";
    /**
     * {@link #mCloudProviderDataMap} key to the last fetched
     * {@link android.provider.CloudMediaProvider} authority.
     */
    private static final String AUTHORITY = "authority";
    /**
     * {@link #mCloudProviderDataMap} key to the last fetched account name in the then fetched
     * {@link android.provider.CloudMediaProvider}.
     */
    private static final String ACCOUNT_NAME = "account_name";

    private final Context mContext;
    private final UserHandle mUserHandle;

    /**
     * {@link File} for persisting the last fetched {@link android.provider.CloudMediaProvider}
     * data.
     */
    private final File mLastCloudProviderDataFile;

    /**
     * Last fetched {@link android.provider.CloudMediaProvider} data.
     */
    private final Map<String, String> mCloudProviderDataMap = new HashMap<>();

    // Label of the current cloud media provider
    private String mCmpLabel;

    // Boolean 'Choose App' banner visibility
    private boolean mShowChooseAppBanner;

    // Boolean 'Cloud Media Available' banner visibility
    private boolean mShowCloudMediaAvailableBanner;

    // Boolean 'Account Updated' banner visibility
    private boolean mShowAccountUpdatedBanner;

    // Boolean 'Choose Account' banner visibility
    private boolean mShowChooseAccountBanner;

    BannerController(@NonNull Context context, @NonNull UserHandle userHandle) {
        Log.d(TAG, "Constructing the BannerController for user " + userHandle.getIdentifier());
        mContext = context;
        mUserHandle = userHandle;

        final String lastCloudProviderDataFilePath = DATA_MEDIA_DIRECTORY_PATH
                + userHandle.getIdentifier() + LAST_CLOUD_PROVIDER_DATA_FILE_PATH_IN_USER_MEDIA_DIR;
        mLastCloudProviderDataFile = new File(lastCloudProviderDataFilePath);
        loadCloudProviderInfo();

        initialise();
    }

    /**
     * Same as {@link #initialise()}, renamed for readability.
     */
    void reset() {
        Log.d(TAG, "Resetting the BannerController for user " + mUserHandle.getIdentifier());
        initialise();
    }

    /**
     * Initialise the banner controller data
     *
     * 0. Assert non-main thread.
     * 1. Fetch the latest cloud provider info.
     * 2. {@link #onChangeCloudMediaInfo(String, String)} with the newly fetched authority and
     *    account name.
     *
     * Note : This method is expected to be called only in a non-main thread since we shouldn't
     * block the UI thread on the heavy Binder calls to fetch the cloud media provider info.
     */
    private void initialise() {
        final String cmpAuthority, cmpAccountName;
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
                    UserId.of(mUserHandle).getContentResolver(mContext);
            cmpAuthority = getCurrentCloudProvider(contentResolver);
            mCmpLabel = getProviderLabelForUser(mContext, mUserHandle, cmpAuthority);
            cmpAccountName = getCloudMediaAccountName(contentResolver, cmpAuthority);

            // Not logging the account name due to privacy concerns
            Log.d(TAG, "Current CloudMediaProvider authority: " + cmpAuthority + ", label: "
                    + mCmpLabel);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Could not fetch the current CloudMediaProvider", e);
            resetToDefault();
            return;
        }

        onChangeCloudMediaInfo(cmpAuthority, cmpAccountName);
    }

    /**
     * On Change Cloud Media Info
     *
     * @param cmpAuthority Current {@link android.provider.CloudMediaProvider} authority.
     * @param cmpAccountName Current {@link android.provider.CloudMediaProvider} account name.
     *
     * 1. If the previous & new cloud provider infos are the same, No-op.
     * 2. Reset should show banners.
     * 3. Update the saved and cached cloud provider info with the latest info.
     */
    @VisibleForTesting
    void onChangeCloudMediaInfo(@Nullable String cmpAuthority, @Nullable String cmpAccountName) {
        // 1. If the previous & new cloud provider infos are the same, No-op.
        final String lastCmpAuthority = mCloudProviderDataMap.get(AUTHORITY);
        final String lastCmpAccountName = mCloudProviderDataMap.get(ACCOUNT_NAME);

        Log.d(TAG, "Last CloudMediaProvider authority: " + lastCmpAuthority);

        if (TextUtils.equals(lastCmpAuthority, cmpAuthority)
                && TextUtils.equals(lastCmpAccountName, cmpAccountName)) {
            // no-op
            return;
        }

        // 2. Update banner visibilities.
        clearBanners();

        if (cmpAuthority == null) {
            // mShowChooseAppBanner is true iff the new authority is null and the available cloud
            // providers list is not empty.
            mShowChooseAppBanner = areCloudProviderOptionsAvailable();
        } else if (cmpAccountName == null) {
            // mShowChooseAccountBanner is true iff the new account name is null while the new
            // authority is NOT null.
            mShowChooseAccountBanner = true;
        } else if (TextUtils.equals(lastCmpAuthority, cmpAuthority)) {
            // mShowAccountUpdatedBanner is true iff the new authority AND account name are NOT null
            // AND the authority is unchanged.
            mShowAccountUpdatedBanner = true;
        } else {
            // mShowCloudMediaAvailableBanner is true iff the new authority AND account name are
            // NOT null AND the authority has changed.
            mShowCloudMediaAvailableBanner = true;
        }

        // 3. Update the saved and cached cloud provider info with the latest info.
        persistCloudProviderInfo(cmpAuthority, cmpAccountName);
    }

    /**
     * Reset all the controller data to their default values.
     */
    private void resetToDefault() {
        mCloudProviderDataMap.clear();
        mCmpLabel = null;
        clearBanners();
    }

    /**
     * Clear all banners
     *
     * Reset all should show banner {@code boolean} values to {@code false}.
     */
    private void clearBanners() {
        mShowChooseAppBanner = false;
        mShowCloudMediaAvailableBanner = false;
        mShowAccountUpdatedBanner = false;
        mShowChooseAccountBanner = false;
    }

    @VisibleForTesting
    boolean areCloudProviderOptionsAvailable() {
        return !getAvailableCloudProviders(mContext, getConfigStore(), mUserHandle).isEmpty();
    }

    /**
     * @return the authority of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderAuthority() {
        return mCloudProviderDataMap.get(AUTHORITY);
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
        return mCloudProviderDataMap.get(ACCOUNT_NAME);
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
     * @return the 'Account Updated' banner visibility {@link #mShowAccountUpdatedBanner}.
     */
    boolean shouldShowAccountUpdatedBanner() {
        return mShowAccountUpdatedBanner;
    }

    /**
     * @return the 'Choose Account' banner visibility {@link #mShowChooseAccountBanner}.
     */
    boolean shouldShowChooseAccountBanner() {
        return mShowChooseAccountBanner;
    }

    /**
     * Dismiss (hide) the 'Choose App' banner
     *
     * Set the 'Choose App' banner visibility {@link #mShowChooseAppBanner} as {@code false}.
     */
    void onUserDismissedChooseAppBanner() {
        if (!mShowChooseAppBanner) {
            Log.d(TAG, "Choose app banner visibility for current user is false on dismiss");
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
            Log.d(TAG, "Cloud media available banner visibility for current user is false on "
                    + "dismiss");
        } else {
            mShowCloudMediaAvailableBanner = false;
        }
    }

    /**
     * Dismiss (hide) the 'Account Updated' banner
     *
     * Set the 'Account Updated' banner visibility {@link #mShowAccountUpdatedBanner} as
     * {@code false}.
     */
    void onUserDismissedAccountUpdatedBanner() {
        if (!mShowAccountUpdatedBanner) {
            Log.d(TAG, "Account Updated banner visibility for current user is false on dismiss");
        } else {
            mShowAccountUpdatedBanner = false;
        }
    }

    /**
     * Dismiss (hide) the 'Choose Account' banner
     *
     * Set the 'Choose Account' banner visibility {@link #mShowChooseAccountBanner} as
     * {@code false}.
     */
    void onUserDismissedChooseAccountBanner() {
        if (!mShowChooseAccountBanner) {
            Log.d(TAG, "Choose Account banner visibility for current user is false on dismiss");
        } else {
            mShowChooseAccountBanner = false;
        }
    }

    private static void assertNonMainThread() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            return;
        }

        throw new IllegalStateException("Expected to NOT be called from the main thread."
                + " Current thread: " + Thread.currentThread());
    }

    private void loadCloudProviderInfo() {
        FileInputStream fis = null;
        final Map<String, String> lastCloudProviderDataMap = new HashMap<>();
        try {
            if (!mLastCloudProviderDataFile.exists()) {
                return;
            }

            final AtomicFile atomicLastCloudProviderDataFile = new AtomicFile(
                    mLastCloudProviderDataFile);
            fis = atomicLastCloudProviderDataFile.openRead();
            lastCloudProviderDataMap.putAll(XmlUtils.readMapXml(fis));
        } catch (Exception e) {
            Log.w(TAG, "Could not load the cloud provider info.", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close the FileInputStream.", e);
                }
            }
            mCloudProviderDataMap.clear();
            mCloudProviderDataMap.putAll(lastCloudProviderDataMap);
        }
    }

    private void persistCloudProviderInfo(@Nullable String cmpAuthority,
            @Nullable String cmpAccountName) {
        mCloudProviderDataMap.clear();
        if (cmpAuthority != null) {
            mCloudProviderDataMap.put(AUTHORITY, cmpAuthority);
        }
        if (cmpAccountName != null) {
            mCloudProviderDataMap.put(ACCOUNT_NAME, cmpAccountName);
        }

        updateCloudProviderDataFile();
    }

    @VisibleForTesting
    void updateCloudProviderDataFile() {
        FileOutputStream fos = null;
        final AtomicFile atomicLastCloudProviderDataFile = new AtomicFile(
                mLastCloudProviderDataFile);

        try {
            fos = atomicLastCloudProviderDataFile.startWrite();
            XmlUtils.writeMapXml(mCloudProviderDataMap, fos);
            atomicLastCloudProviderDataFile.finishWrite(fos);
        } catch (Exception e) {
            atomicLastCloudProviderDataFile.failWrite(fos);
            Log.w(TAG, "Could not persist the cloud provider info.", e);
        }
    }
}
