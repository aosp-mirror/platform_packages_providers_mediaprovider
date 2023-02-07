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

import static com.android.settingslib.widget.ProfileSelectFragment.EXTRA_SHOW_FRAGMENT_TAB;
import static com.android.settingslib.widget.ProfileSelectFragment.PERSONAL_TAB;
import static com.android.settingslib.widget.ProfileSelectFragment.WORK_TAB;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.UserManager;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.ui.settings.SettingsProfileSelectFragment;

/**
 * Photo Picker settings page where user can view/edit current cloud media provider.
 */
public class PhotoPickerSettingsActivity extends AppCompatActivity {
    static final String EXTRA_CURRENT_USER_ID = "user_id";
    private static final int DEFAULT_EXTRA_USER_ID = -1;
    private static final int DEFAULT_TAB = PERSONAL_TAB;

    // Do NOT reference directly, use getUserIdManager() to avoid NPEs.
    @Nullable private UserIdManager mUserIdManager;
    // Do NOT reference directly, use getConfigStore() to avoid NPEs.
    @Nullable private ConfigStore mConfigStore;
    @NonNull private int mCurrentUserId = DEFAULT_EXTRA_USER_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // We use the device default theme as the base theme. Apply the material theme for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        super.onCreate(savedInstanceState);

        final Bundle extras = getIntent().getExtras();
        if (extras != null) {
            mCurrentUserId = extras.getInt(EXTRA_CURRENT_USER_ID, DEFAULT_EXTRA_USER_ID);
        }

        setContentView(R.layout.activity_photo_picker_settings);
        displayActionBar();
        switchToFragment();
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

    private void switchToFragment() {
        final Fragment targetFragment = getTargetFragment();
        final FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.settings_fragment_container, targetFragment);
        transaction.commitAllowingStateLoss();
        getSupportFragmentManager().executePendingTransactions();
    }

    @VisibleForTesting
    @NonNull
    Fragment getTargetFragment() {
        // Target fragment is SettingsProfileSelectFragment if there exists more than one
        // UserHandles for profiles associated with the context user, including the user itself.
        // Else target fragment is SettingsCloudMediaSelectFragment
        if (getUserIdManager().isMultiUserProfiles()) {
            // In case work profile exists and is turned off, do not show the work tab.
            getUserIdManager().updateWorkProfileOffValue();
            if (!getUserIdManager().isWorkProfileOff()) {
                return getProfileSelectFragment();
            }
        }
        return getCloudMediaSelectFragment();
    }

    @NonNull
    private Fragment getCloudMediaSelectFragment() {
        final int userId = getUserIdManager().getCurrentUserProfileId().getIdentifier();
        return SettingsProfileSelectFragment.getCloudMediaSelectFragment(userId);
    }

    @NonNull
    private Fragment getProfileSelectFragment() {
        final Fragment fragment = new SettingsProfileSelectFragment();
        final Bundle extras = new Bundle();
        extras.putInt(EXTRA_SHOW_FRAGMENT_TAB, getInitialProfileTab());
        fragment.setArguments(extras);
        return fragment;
    }

    /**
     * @return the tab position that should be open when user initially lands on the Settings page.
     */
    private int getInitialProfileTab() {
        final UserManager userManager = getApplicationContext().getSystemService(UserManager.class);
        if (userManager == null || mCurrentUserId == DEFAULT_EXTRA_USER_ID) {
            return DEFAULT_TAB;
        }
        return userManager.isManagedProfile(mCurrentUserId) ? WORK_TAB : PERSONAL_TAB;
    }

    /**
     * Get UserIdManager and instantiate it if it is null.
     */
    // TODO(b/255782519): This is temporarily used till we have a ViewModel to hold state for this
    //  Activity.
    @NonNull
    public UserIdManager getUserIdManager() {
        if (mUserIdManager == null) {
            mUserIdManager = UserIdManager.create(getApplicationContext());
        }
        return mUserIdManager;
    }

    /**
     * Get ConfigStore and instantiate it if it is null.
     */
    // TODO(b/255782519): This is temporarily used till we have a ViewModel to hold state for this
    //  Activity.
    @NonNull
    public ConfigStore getConfigStore() {
        if (mConfigStore == null) {
            mConfigStore = new ConfigStore.ConfigStoreImpl();
        }
        return mConfigStore;
    }
}
