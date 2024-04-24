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
import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.settingslib.widget.ProfileSelectFragment;
import com.android.settingslib.widget.profileselector.R;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * This fragment will display swipable "Personal" and "Work" tabs on the settings page.
 */
public class SettingsProfileSelectFragment extends ProfileSelectFragment {
    @NonNull
    private SettingsViewModel mSettingsViewModel;
    @NonNull
    private TabLayout mTabLayout;
    private static boolean sUserIdProvided = false;
    private static List<Integer> sUserIdListToShowProfileTabs = new ArrayList<>();

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mSettingsViewModel =
                new ViewModelProvider(requireActivity()).get(SettingsViewModel.class);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mTabLayout = getTabLayout(view);
    }

    @Override
    public Fragment createFragment(int tabUserIdOrPosition) {
        final int userId = getTabUserId(tabUserIdOrPosition);

        return getCloudMediaSelectFragment(userId);
    }

    @Override
    public void onPause() {
        super.onPause();

        // Save selected tab state in ViewModel.
        final int selectedTab = mTabLayout.getSelectedTabPosition();
        mSettingsViewModel.setSelectedTab(selectedTab);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Set selected tab according to saved state.
        final int previouslySelectedTab = mSettingsViewModel.getSelectedTab();
        if (previouslySelectedTab != SettingsViewModel.TAB_NOT_SET) {
            // Selected tab state has previously been set in onPause() and we should restore it.
            mTabLayout.getTabAt(previouslySelectedTab).select();
        }
    }

    /**
     * Create an instance of {@link SettingsCloudMediaSelectFragment}.
     *
     * @param userId User will be able to view and update cloud media providers in
     * {@link SettingsCloudMediaSelectFragment} for the given userId.
     * @return {@link SettingsCloudMediaSelectFragment} for given userId.
     */
    @NonNull
    public static SettingsCloudMediaSelectFragment getCloudMediaSelectFragment(
            @UserIdInt int userId) {
        // Add extras to communicate the fragment can choose cloud media provider for which userId.
        final SettingsCloudMediaSelectFragment fragment = new SettingsCloudMediaSelectFragment();
        final Bundle extras = new Bundle();
        extras.putInt(EXTRA_TAB_USER_ID, userId);
        fragment.setArguments(extras);

        return fragment;
    }

    /**
     * Create an instance of {@link SettingsProfileSelectFragment}.
     *
     * @param selectedProfileTab is the tab position id or the tab's UserId that should be selected
     *                           when the user first lands on the settings page.
     * @return A new {@link SettingsProfileSelectFragment} object.
     */
    @NonNull
    public static SettingsProfileSelectFragment getProfileSelectFragment(int selectedProfileTab,
            boolean userIdProvided, List<Integer> userIdListToShowProfileTabs) {
        final SettingsProfileSelectFragment fragment = new SettingsProfileSelectFragment();
        final Bundle extras = new Bundle();
        if (userIdProvided) {
            sUserIdProvided = true;
            sUserIdListToShowProfileTabs = userIdListToShowProfileTabs;
            extras.putInt(EXTRA_SHOW_FRAGMENT_USER_ID, selectedProfileTab);
            extras.putIntegerArrayList(EXTRA_LIST_OF_USER_IDS,
                    new ArrayList<>(userIdListToShowProfileTabs));
        } else {
            extras.putInt(EXTRA_SHOW_FRAGMENT_TAB, selectedProfileTab);
        }
        fragment.setArguments(extras);
        return fragment;
    }

    private int getManagedUser() {
        if (mSettingsViewModel.getConfigStore().isPrivateSpaceInPhotoPickerEnabled()
                && SdkLevel.isAtLeastS()) {
            for (UserId userId : mSettingsViewModel.getUserManagerState().getAllUserProfileIds()) {
                if (mSettingsViewModel.getUserManagerState().isManagedUserProfile(userId)) {
                    return userId.getIdentifier();
                }
            }
        } else {
            return mSettingsViewModel.getUserIdManager().getManagedUserId().getIdentifier();
        }
        return -1;
    }

    @UserIdInt
    private int getTabUserId(int tabUserIdOrPosition) {
        if (sUserIdProvided) {
            final int userId = tabUserIdOrPosition;
            if (!sUserIdListToShowProfileTabs.contains(userId)) {
                throw new IllegalArgumentException("Unidentified user id " + tabUserIdOrPosition);
            }
            return userId;
        }
        int personalUser = ActivityManager.getCurrentUser();
        int managedUser = getManagedUser();
        switch (tabUserIdOrPosition) {
            case ProfileSelectFragment.PERSONAL_TAB:
                return personalUser;
            case ProfileSelectFragment.WORK_TAB:
                return managedUser;
            default:
                // tabUserIdOrPosition should match one of the cases above.
                throw new IllegalArgumentException("Unidentified tab id " + tabUserIdOrPosition);
        }
    }

    @NonNull
    private TabLayout getTabLayout(@NonNull View view) {
        final View tabContainer = view.findViewById(R.id.tab_container);

        return tabContainer.findViewById(R.id.tabs);
    }
}
