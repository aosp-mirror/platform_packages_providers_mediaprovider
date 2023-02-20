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

package com.android.providers.media.photopicker;

import static com.android.settingslib.widget.ProfileSelectFragment.PERSONAL_TAB;
import static com.android.settingslib.widget.ProfileSelectFragment.WORK_TAB;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.ui.settings.SettingsProfileSelectFragment;
import com.android.providers.media.photopicker.ui.settings.SettingsViewModel;

/**
 * Photo Picker settings page where user can view/edit current cloud media provider.
 */
public class PhotoPickerSettingsActivity extends AppCompatActivity {
    private static final String TAG = "PickerSettings";
    static final String EXTRA_CURRENT_USER_ID = "user_id";
    private static final int DEFAULT_EXTRA_USER_ID = -1;
    private static final int DEFAULT_TAB = PERSONAL_TAB;

    @NonNull
    private SettingsViewModel mSettingsViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // We use the device default theme as the base theme. Apply the material theme for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        super.onCreate(savedInstanceState);

        mSettingsViewModel =
                new ViewModelProvider(this).get(SettingsViewModel.class);
        final Bundle extras = getIntent().getExtras();
        final int callingUserId;
        if (extras != null) {
            callingUserId = extras.getInt(EXTRA_CURRENT_USER_ID, DEFAULT_EXTRA_USER_ID);
        } else {
            callingUserId = DEFAULT_EXTRA_USER_ID;
        }

        setContentView(R.layout.activity_photo_picker_settings);
        displayActionBar();
        createAndShowFragmentIfNeeded(callingUserId);
    }

    private void displayActionBar() {
        final Toolbar toolbar = findViewById(R.id.picker_settings_toolbar);
        setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // Stop PhotoPickerSettingsActivity when back button is pressed.
            finish();
            return true;
        }
        return false;
    }

    private void createAndShowFragmentIfNeeded(@UserIdInt int callingUserId) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentById(R.id.settings_fragment_container) != null) {
            // Fragment already exists and is attached to this Activity.
            // Nothing further needs to be done.
            Log.d(TAG, "An instance of target fragment is already attached to the "
                    + "PhotoPickerSettingsActivity. Not creating a new fragment.");
            return;
        }

        // Create a new fragment and attach it to this Activity. The new fragment could be of type
        // SettingsProfileSelectFragment or SettingsCloudMediaSelectFragment.
        final Fragment targetFragment = getTargetFragment(callingUserId);
        fragmentManager.beginTransaction()
                .replace(R.id.settings_fragment_container, targetFragment)
                .commitAllowingStateLoss();
        fragmentManager.executePendingTransactions();
    }

    @NonNull
    private Fragment getTargetFragment(@UserIdInt int callingUserId) {
        // Target fragment is SettingsProfileSelectFragment if there exists more than one
        // UserHandles for profiles associated with the context user, including the user itself.
        // Else target fragment is SettingsCloudMediaSelectFragment
        final UserIdManager userIdManager = mSettingsViewModel.getUserIdManager();
        if (userIdManager.isMultiUserProfiles()) {
            // In case work profile exists and is turned off, do not show the work tab.
            userIdManager.updateWorkProfileOffValue();
            if (!userIdManager.isWorkProfileOff()) {
                final int selectedProfileTab = getInitialProfileTab(callingUserId);
                return SettingsProfileSelectFragment.getProfileSelectFragment(selectedProfileTab);
            }
        }
        return getCloudMediaSelectFragment();
    }

    @NonNull
    private Fragment getCloudMediaSelectFragment() {
        final UserIdManager userIdManager = mSettingsViewModel.getUserIdManager();
        final int userId = userIdManager.getCurrentUserProfileId().getIdentifier();
        return SettingsProfileSelectFragment.getCloudMediaSelectFragment(userId);
    }

    /**
     * @return the tab position that should be open when user initially lands on the Settings page.
     */
    private int getInitialProfileTab(@UserIdInt int callingUserId) {
        final UserManager userManager = getApplicationContext().getSystemService(UserManager.class);
        if (userManager == null || callingUserId == DEFAULT_EXTRA_USER_ID) {
            return DEFAULT_TAB;
        }
        return userManager.isManagedProfile(callingUserId) ? WORK_TAB : PERSONAL_TAB;
    }
}
