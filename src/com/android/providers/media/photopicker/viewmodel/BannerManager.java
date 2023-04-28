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

package com.android.providers.media.photopicker.viewmodel;

import android.annotation.UserIdInt;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.PerUser;

class BannerManager {
    private static final String TAG = "BannerManager";

    private final UserIdManager mUserIdManager;

    // Authority of the current CloudMediaProvider of the current user
    private final MutableLiveData<String> mCloudMediaProviderAuthority = new MutableLiveData<>();
    // Label of the current CloudMediaProvider of the current user
    private final MutableLiveData<String> mCloudMediaProviderLabel = new MutableLiveData<>();
    // Account name of the current CloudMediaProvider of the current user
    private final MutableLiveData<String> mCloudMediaAccountName = new MutableLiveData<>();

    // Boolean Choose App Banner visibility
    private final MutableLiveData<Boolean> mShowChooseAppBanner = new MutableLiveData<>(false);
    // Boolean Cloud Media Available Banner visibility
    private final MutableLiveData<Boolean> mShowCloudMediaAvailableBanner =
            new MutableLiveData<>(false);
    // Boolean 'Account Updated' banner visibility
    private final MutableLiveData<Boolean> mShowAccountUpdatedBanner = new MutableLiveData<>(false);
    // Boolean 'Choose Account' banner visibility
    private final MutableLiveData<Boolean> mShowChooseAccountBanner = new MutableLiveData<>(false);

    // The banner controllers per user
    private final PerUser<BannerController> mBannerControllers;

    BannerManager(@NonNull Context context, @NonNull UserIdManager userIdManager) {
        mUserIdManager = userIdManager;
        mBannerControllers = new PerUser<BannerController>() {
            @NonNull
            @Override
            protected BannerController create(@UserIdInt int userId) {
                return new BannerController(context, UserHandle.of(userId));
            }
        };
        maybeInitialiseAndSetBannersForCurrentUser();
    }

    @UserIdInt int getCurrentUserProfileId() {
        return mUserIdManager.getCurrentUserProfileId().getIdentifier();
    }

    PerUser<BannerController> getBannerControllersPerUser() {
        return mBannerControllers;
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the
     *         {@link android.content.ContentProvider#mAuthority authority} of the current
     *         {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    MutableLiveData<String> getCloudMediaProviderAuthorityLiveData() {
        return mCloudMediaProviderAuthority;
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the label
     *         of the current {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    MutableLiveData<String> getCloudMediaProviderAppTitleLiveData() {
        return mCloudMediaProviderLabel;
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the account name
     *         of the current {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    MutableLiveData<String> getCloudMediaAccountNameLiveData() {
        return mCloudMediaAccountName;
    }

    /**
     * @return the {@link LiveData} of the 'Choose App' banner visibility.
     */
    @NonNull
    MutableLiveData<Boolean> shouldShowChooseAppBannerLiveData() {
        return mShowChooseAppBanner;
    }

    /**
     * @return the {@link LiveData} of the 'Cloud Media Available' banner visibility.
     */
    @NonNull
    MutableLiveData<Boolean> shouldShowCloudMediaAvailableBannerLiveData() {
        return mShowCloudMediaAvailableBanner;
    }

    /**
     * @return the {@link LiveData} of the 'Account Updated' banner visibility.
     */
    @NonNull
    MutableLiveData<Boolean> shouldShowAccountUpdatedBannerLiveData() {
        return mShowAccountUpdatedBanner;
    }

    /**
     * @return the {@link LiveData} of the 'Choose Account' banner visibility.
     */
    @NonNull
    MutableLiveData<Boolean> shouldShowChooseAccountBannerLiveData() {
        return mShowChooseAccountBanner;
    }

    /**
     * Dismiss (hide) the 'Choose App' banner for the current user.
     */
    @UiThread
    void onUserDismissedChooseAppBanner() {
        if (Boolean.FALSE.equals(mShowChooseAppBanner.getValue())) {
            Log.d(TAG, "Choose App banner visibility live data value is false on dismiss");
        } else {
            mShowChooseAppBanner.setValue(false);
        }

        final BannerController bannerController = getCurrentBannerController();
        if (bannerController != null) {
            bannerController.onUserDismissedChooseAppBanner();
        }
    }

    /**
     * Dismiss (hide) the 'Cloud Media Available' banner for the current user.
     */
    @UiThread
    void onUserDismissedCloudMediaAvailableBanner() {
        if (Boolean.FALSE.equals(mShowCloudMediaAvailableBanner.getValue())) {
            Log.d(TAG, "Cloud Media Available banner visibility live data value is false on "
                    + "dismiss");
        } else {
            mShowCloudMediaAvailableBanner.setValue(false);
        }

        final BannerController bannerController = getCurrentBannerController();
        if (bannerController != null) {
            bannerController.onUserDismissedCloudMediaAvailableBanner();
        }
    }

    /**
     * Dismiss (hide) the 'Account Updated' banner for the current user.
     */
    @UiThread
    void onUserDismissedAccountUpdatedBanner() {
        if (Boolean.FALSE.equals(mShowAccountUpdatedBanner.getValue())) {
            Log.d(TAG, "Account Updated banner visibility live data value is false on dismiss");
        } else {
            mShowAccountUpdatedBanner.setValue(false);
        }

        final BannerController bannerController = getCurrentBannerController();
        if (bannerController != null) {
            bannerController.onUserDismissedAccountUpdatedBanner();
        }
    }

    /**
     * Dismiss (hide) the 'Choose Account' banner for the current user.
     */
    @UiThread
    void onUserDismissedChooseAccountBanner() {
        if (Boolean.FALSE.equals(mShowChooseAccountBanner.getValue())) {
            Log.d(TAG, "Choose Account banner visibility live data value is false on dismiss");
        } else {
            mShowChooseAccountBanner.setValue(false);
        }

        final BannerController bannerController = getCurrentBannerController();
        if (bannerController != null) {
            bannerController.onUserDismissedChooseAccountBanner();
        }
    }

    @Nullable
    private BannerController getCurrentBannerController() {
        final int currentUserProfileId = getCurrentUserProfileId();
        return mBannerControllers.get(currentUserProfileId);
    }

    /**
     * Resets the banner controller per user.
     *
     * Note - Since {@link BannerController#reset()} cannot be called in the Main thread, using
     * {@link ForegroundThread} here.
     */
    void maybeResetAllBannerData() {
        for (int arrayIndex = 0, numControllers = mBannerControllers.size();
                arrayIndex < numControllers; arrayIndex++) {
            final BannerController bannerController = mBannerControllers.valueAt(arrayIndex);
            ForegroundThread.getExecutor().execute(bannerController::reset);
        }
    }

    /**
     * Update the banner {@link LiveData} values.
     *
     * 1. {@link #hideAllBanners()} in the Main thread to ensure consistency with the media items
     * displayed for the period when the items and categories have been updated but the
     * {@link BannerController} construction or {@link BannerController#reset()} is still in
     * progress.
     *
     * 2. Initialise and set the banner data for the current user
     * {@link #maybeInitialiseAndSetBannersForCurrentUser()}.
     */
    @UiThread
    void maybeUpdateBannerLiveDatas() {
        // Hide all banners in the Main thread to ensure consistency with the media items
        hideAllBanners();

        // Initialise and set the banner data for the current user
        maybeInitialiseAndSetBannersForCurrentUser();
    }

    /**
     * Hide all banners in the Main thread.
     *
     * Set all banner {@link LiveData} values to {@code false}.
     */
    @UiThread
    private void hideAllBanners() {
        mShowChooseAppBanner.setValue(false);
        mShowCloudMediaAvailableBanner.setValue(false);
        mShowAccountUpdatedBanner.setValue(false);
        mShowChooseAccountBanner.setValue(false);
    }


    /**
     * Initialise and set the banner data for the current user.
     *
     * No-op by default, overridden for cloud.
     */
    void maybeInitialiseAndSetBannersForCurrentUser() {
        // No-op, may be overridden
    }

    static class CloudBannerManager extends BannerManager {
        CloudBannerManager(@NonNull Context context, @NonNull UserIdManager userIdManager) {
            super(context, userIdManager);
        }

        /**
         * Initialise and set the banner data for the current user.
         *
         * 1. Get or create the {@link BannerController} for
         * {@link UserIdManager#getCurrentUserProfileId()} using {@link PerUser#forUser(int)}.
         * Since, the {@link BannerController} construction cannot be done in the Main thread,
         * using {@link ForegroundThread} here.
         *
         * 2. Post the updated {@link BannerController} {@link LiveData} values.
         */
        @Override
        void maybeInitialiseAndSetBannersForCurrentUser() {
            final int currentUserProfileId = getCurrentUserProfileId();
            ForegroundThread.getExecutor().execute(() -> {
                // Get (iff exists) or create the banner controller for the current user
                final BannerController bannerController =
                        getBannerControllersPerUser().forUser(currentUserProfileId);
                // Post the banner related live data values from this current user banner controller
                getCloudMediaProviderAuthorityLiveData()
                        .postValue(bannerController.getCloudMediaProviderAuthority());
                getCloudMediaProviderAppTitleLiveData()
                        .postValue(bannerController.getCloudMediaProviderLabel());
                getCloudMediaAccountNameLiveData()
                        .postValue(bannerController.getCloudMediaProviderAccountName());
                shouldShowChooseAppBannerLiveData()
                        .postValue(bannerController.shouldShowChooseAppBanner());
                shouldShowCloudMediaAvailableBannerLiveData()
                        .postValue(bannerController.shouldShowCloudMediaAvailableBanner());
                shouldShowAccountUpdatedBannerLiveData()
                        .postValue(bannerController.shouldShowAccountUpdatedBanner());
                shouldShowChooseAccountBannerLiveData()
                        .postValue(bannerController.shouldShowChooseAccountBanner());
            });
        }
    }
}
