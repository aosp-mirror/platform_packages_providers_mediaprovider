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

import android.content.Context;
import android.os.UserHandle;

import androidx.annotation.NonNull;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.UserIdManager;

class BannerTestUtils {
    static BannerController getTestBannerController(@NonNull Context context,
            @NonNull UserHandle userHandle, @NonNull ConfigStore configStore) {
        return new BannerController(context, userHandle, configStore) {
            @Override
            void updateCloudProviderDataFile() {
                // No-op
            }
        };
    }

    static BannerManager getTestCloudBannerManager(@NonNull Context context,
            @NonNull UserIdManager userIdManager, @NonNull ConfigStore configStore) {
        return new BannerManager.CloudBannerManager(context, userIdManager, configStore) {
            @Override
            void maybeInitialiseAndSetBannersForCurrentUser() {
                // Get (iff exists) or create the banner controller for the current user
                final BannerController bannerController =
                        getBannerControllersPerUser().forUser(getCurrentUserProfileId());
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
            }

            @NonNull
            @Override
            BannerController createBannerController(@NonNull Context context,
                    @NonNull UserHandle userHandle, @NonNull ConfigStore configStore) {
                return getTestBannerController(context, userHandle, configStore);
            }
        };
    }
}
