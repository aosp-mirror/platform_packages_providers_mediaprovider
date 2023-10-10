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

import static com.android.providers.media.photopicker.DataLoaderThread.TOKEN;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.DataLoaderThread;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.util.ThreadUtils;
import com.android.providers.media.util.PerUser;

class BannerManager {
    private static final String TAG = "BannerManager";
    private static final int DELAY_MILLIS = 0;

    private final UserIdManager mUserIdManager;

    // Authority of the current CloudMediaProvider of the current user
    private final MutableLiveData<String> mCloudMediaProviderAuthority = new MutableLiveData<>();
    // Label of the current CloudMediaProvider of the current user
    private final MutableLiveData<String> mCloudMediaProviderLabel = new MutableLiveData<>();
    // Account name of the current CloudMediaProvider of the current user
    private final MutableLiveData<String> mCloudMediaAccountName = new MutableLiveData<>();
    // Account selection activity intent of the current CloudMediaProvider of the current user
    private Intent mChooseCloudMediaAccountActivityIntent = null;

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

    BannerManager(@NonNull Context context, @NonNull UserIdManager userIdManager,
            @NonNull ConfigStore configStore) {
        mUserIdManager = userIdManager;
        mBannerControllers = new PerUser<BannerController>() {
            @NonNull
            @Override
            protected BannerController create(@UserIdInt int userId) {
                return createBannerController(context, UserHandle.of(userId), configStore);
            }
        };
        maybeInitialiseAndSetBannersForCurrentUser();
    }

    @VisibleForTesting
    @NonNull
    BannerController createBannerController(@NonNull Context context,
            @NonNull UserHandle userHandle, @NonNull ConfigStore configStore) {
        return new BannerController(context, userHandle, configStore);
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
     * @return the account selection activity {@link Intent} of the current
     *         {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    Intent getChooseCloudMediaAccountActivityIntent() {
        return mChooseCloudMediaAccountActivityIntent;
    }


    /**
     * Update the account selection activity {@link Intent} of the current
     * {@link android.provider.CloudMediaProvider}.
     */
    void setChooseCloudMediaAccountActivityIntent(Intent intent) {
        mChooseCloudMediaAccountActivityIntent = intent;
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
    @MainThread
    void onUserDismissedChooseAppBanner() {
        ThreadUtils.assertMainThread();

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
    @MainThread
    void onUserDismissedCloudMediaAvailableBanner() {
        ThreadUtils.assertMainThread();

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
    @MainThread
    void onUserDismissedAccountUpdatedBanner() {
        ThreadUtils.assertMainThread();

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
    @MainThread
    void onUserDismissedChooseAccountBanner() {
        ThreadUtils.assertMainThread();

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
     * Resets the banner controller per user and sets the banner data for the current user.
     *
     * Note - Since {@link BannerController#reset()} cannot be called in the Main thread, using
     * {@link DataLoaderThread} here.
     */
    void reset() {
        for (int arrayIndex = 0, numControllers = mBannerControllers.size();
                arrayIndex < numControllers; arrayIndex++) {
            final BannerController bannerController = mBannerControllers.valueAt(arrayIndex);
            DataLoaderThread.getHandler().postDelayed(bannerController::reset, TOKEN, DELAY_MILLIS);
        }

        // Set the banner data for the current user
        maybeInitialiseAndSetBannersForCurrentUser();
    }

    /**
     * Hide all the banners in the DataLoader thread.
     *
     * Since this is always followed by a reset, they need to be done in the same threads (currently
     * DataLoaderThread thread). For the case when multiple hideAllBanners & reset are triggered
     * simultaneously, this ensures that they are called sequentially for each such trigger.
     *
     * Post all the banner {@link LiveData} values as {@code false}.
     */
    void hideAllBanners() {
        DataLoaderThread.getHandler().postDelayed(() -> {
            mShowChooseAppBanner.postValue(false);
            mShowCloudMediaAvailableBanner.postValue(false);
            mShowAccountUpdatedBanner.postValue(false);
            mShowChooseAccountBanner.postValue(false);
        }, TOKEN, DELAY_MILLIS);
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
        CloudBannerManager(@NonNull Context context, @NonNull UserIdManager userIdManager,
                @NonNull ConfigStore configStore) {
            super(context, userIdManager, configStore);
        }

        /**
         * Initialise and set the banner data for the current user.
         *
         * 1. Get or create the {@link BannerController} for
         * {@link UserIdManager#getCurrentUserProfileId()} using {@link PerUser#forUser(int)}.
         * Since, the {@link BannerController} construction cannot be done in the Main thread,
         * using {@link DataLoaderThread} here.
         *
         * 2. Post the updated {@link BannerController} {@link LiveData} values.
         */
        @Override
        void maybeInitialiseAndSetBannersForCurrentUser() {
            final int currentUserProfileId = getCurrentUserProfileId();
            DataLoaderThread.getHandler().postDelayed(() -> {
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
                setChooseCloudMediaAccountActivityIntent(
                        bannerController.getChooseCloudMediaAccountActivityIntent());
                shouldShowChooseAppBannerLiveData()
                        .postValue(bannerController.shouldShowChooseAppBanner());
                shouldShowCloudMediaAvailableBannerLiveData()
                        .postValue(bannerController.shouldShowCloudMediaAvailableBanner());
                shouldShowAccountUpdatedBannerLiveData()
                        .postValue(bannerController.shouldShowAccountUpdatedBanner());
                shouldShowChooseAccountBannerLiveData()
                        .postValue(bannerController.shouldShowChooseAccountBanner());
            }, TOKEN, DELAY_MILLIS);
        }
    }
}
