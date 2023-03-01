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
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.settingslib.widget.ProfileSelectFragment;
import com.android.settingslib.widget.R;

import com.google.android.material.tabs.TabLayout;

/**
 * This fragment will display swipable "Personal" and "Work" tabs on the settings page.
 */
public class SettingsProfileSelectFragment extends ProfileSelectFragment {
    @NonNull
    private SettingsViewModel mSettingsViewModel;
    @NonNull
    private TabLayout mTabLayout;

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
    public Fragment createFragment(int tabPosition) {
        final int userId = getTabUserId(tabPosition);

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
     * @param selectedProfileTab is the tab id of the work/profile tab that should be selected when
     *                           the user first lands on the settings page.
     * @return A new {@link SettingsProfileSelectFragment} object.
     */
    @NonNull
    public static SettingsProfileSelectFragment getProfileSelectFragment(int selectedProfileTab) {
        final SettingsProfileSelectFragment fragment = new SettingsProfileSelectFragment();
        final Bundle extras = new Bundle();
        extras.putInt(EXTRA_SHOW_FRAGMENT_TAB, selectedProfileTab);
        fragment.setArguments(extras);
        return fragment;
    }

    @UserIdInt
    private int getTabUserId(int tabPosition) {
        final UserIdManager userIdManager = mSettingsViewModel.getUserIdManager();

        switch (tabPosition) {
            case ProfileSelectFragment.PERSONAL_TAB:
                return userIdManager.getPersonalUserId().getIdentifier();
            case ProfileSelectFragment.WORK_TAB:
                return userIdManager.getManagedUserId().getIdentifier();
            default:
                // tabPosition should match one of the cases above.
                throw new IllegalArgumentException("Unidentified tab id " + tabPosition);
        }
    }

    @NonNull
    private TabLayout getTabLayout(@NonNull View view) {
        final View tabContainer = view.findViewById(R.id.tab_container);

        return tabContainer.findViewById(R.id.tabs);
    }
}
