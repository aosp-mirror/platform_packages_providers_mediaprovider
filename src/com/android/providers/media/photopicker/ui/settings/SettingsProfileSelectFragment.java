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

import static com.android.providers.media.photopicker.ui.settings.SettingsCloudMediaSelectFragment.EXTRA_TAB_USER_ID;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.android.providers.media.photopicker.PhotoPickerSettingsActivity;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.settingslib.widget.ProfileSelectFragment;

/**
 * This fragment will display swipable "Personal" and "Work" tabs on the settings page.
 */
public class SettingsProfileSelectFragment extends ProfileSelectFragment {
    /**
     * Create an instance of {@link SettingsCloudMediaSelectFragment}.
     *
     * @param userId User will be able to view and update cloud media providers in
     * {@link SettingsCloudMediaSelectFragment} for the given userId.
     * @return {@link SettingsCloudMediaSelectFragment} for given userId.
     */
    @NonNull
    public static Fragment getCloudMediaSelectFragment(@UserIdInt int userId) {
        // Add extras to communicate the fragment can choose cloud media provider for which userId.
        final Fragment fragment = new SettingsCloudMediaSelectFragment();
        final Bundle extras = new Bundle();
        extras.putInt(EXTRA_TAB_USER_ID, userId);
        fragment.setArguments(extras);
        return fragment;
    }

    @Override
    public Fragment createFragment(int tabPosition) {
        final int userId = getTabUserId(tabPosition);
        return getCloudMediaSelectFragment(userId);
    }

    private int getTabUserId(int tabPosition) {
        UserIdManager userIdManager =
                ((PhotoPickerSettingsActivity) getActivity()).getUserIdManager();

        // In case a managed profile is linked to the current user, profileType will indicate if
        // fragment represents personal or work profile. Return the userId accordingly.
        // In case a managed profile is NOT linked, return the current user.
        switch (tabPosition) {
            case ProfileSelectFragment.PERSONAL_TAB:
                return userIdManager.getPersonalUserId().getIdentifier();
            case ProfileSelectFragment.WORK_TAB:
                return userIdManager.getManagedUserId().getIdentifier();
            default:
                return userIdManager.getCurrentUserProfileId().getIdentifier();
        }
    }
}
