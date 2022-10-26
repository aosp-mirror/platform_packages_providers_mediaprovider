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

import static com.android.providers.media.photopicker.ui.settings.SettingsCloudMediaSelectFragment.EXTRA_PROFILE;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.android.settingslib.widget.ProfileSelectFragment;

/**
 * This fragment will display swipable "Personal" and "Work" tabs on the settings page.
 */
public class SettingsProfileSelectFragment extends ProfileSelectFragment {
    private static final String TAG = "SettingsProfileFragment";

    @Override
    public Fragment createFragment(int position) {
        if (position != ProfileSelectFragment.PERSONAL_TAB
                && position != ProfileSelectFragment.WORK_TAB) {
            throw new IllegalArgumentException(
                    "Fragment tab position is neither work nor personal.");
        }

        // Add extras to communicate if fragment is created for work profile or personal profile.
        final Bundle extras = new Bundle();
        extras.putInt(EXTRA_PROFILE, position);

        final Fragment childFragment = new SettingsCloudMediaSelectFragment();
        childFragment.setArguments(extras);
        return childFragment;
    }
}
