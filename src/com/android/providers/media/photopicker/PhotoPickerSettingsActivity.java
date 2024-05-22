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
import android.annotation.RequiresApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
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

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.UserManagerState;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.ui.settings.SettingsProfileSelectFragment;
import com.android.providers.media.photopicker.ui.settings.SettingsViewModel;
import com.android.providers.media.photopicker.util.RecentsPreviewUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Photo Picker settings page where user can view/edit current cloud media provider.
 */
public class PhotoPickerSettingsActivity extends AppCompatActivity {
    private static final String TAG = "PickerSettings";
    static final String EXTRA_CURRENT_USER_ID = "user_id";
    private static final int DEFAULT_EXTRA_USER_ID = -1;
    private ArrayList<String> mProfileActions;
    private int mCallingUserId;
    private static final int DEFAULT_TAB_USER_ID = ActivityManager.getCurrentUser();;

    @NonNull
    private SettingsViewModel mSettingsViewModel;

    private final BroadcastReceiver mProfilesActionsReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (SdkLevel.isAtLeastV()
                            && !isFinishing()
                            && mProfileActions.contains(intent.getAction())) {
                        mSettingsViewModel.getUserManagerState().resetUserIds();
                        createAndShowFragment(mCallingUserId, /* allowReplace= */ true);
                        updateRecentsVisibilitySetting();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // We use the device default theme as the base theme. Apply the material theme for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        // TODO(b/309578419): Make this activity handle insets properly and then remove this.
        getTheme().applyStyle(R.style.OptOutEdgeToEdgeEnforcement, /* force */ false);

        super.onCreate(savedInstanceState);

        mSettingsViewModel =
                new ViewModelProvider(this).get(SettingsViewModel.class);
        final Bundle extras = getIntent().getExtras();
        if (extras != null) {
            mCallingUserId = extras.getInt(EXTRA_CURRENT_USER_ID, DEFAULT_EXTRA_USER_ID);
        } else {
            mCallingUserId = DEFAULT_EXTRA_USER_ID;
        }

        setContentView(R.layout.activity_photo_picker_settings);
        displayActionBar();
        createAndShowFragment(mCallingUserId, /* allowReplace= */ false);

        updateRecentsVisibilitySetting();

        // TODO: merge with CrossProfile listeners in the main Photo picker activity.
        if (SdkLevel.isAtLeastV()) {
            mProfileActions =
                    new ArrayList<>(
                            Arrays.asList(
                                    Intent.ACTION_PROFILE_ADDED, Intent.ACTION_PROFILE_AVAILABLE,
                                    Intent.ACTION_PROFILE_REMOVED,
                                            Intent.ACTION_PROFILE_UNAVAILABLE));

            final IntentFilter profileFilter = new IntentFilter();
            for (String action : mProfileActions) {
                profileFilter.addAction(action);
            }
            registerReceiver(mProfilesActionsReceiver, profileFilter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (SdkLevel.isAtLeastV()) {
            unregisterReceiver(mProfilesActionsReceiver);
        }
    }

    private void updateRecentsVisibilitySetting() {
        RecentsPreviewUtil.updateRecentsVisibilitySetting(mSettingsViewModel.getConfigStore(),
                mSettingsViewModel.getUserManagerState(), this);
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

    private void createAndShowFragment(@UserIdInt int callingUserId, boolean allowReplace) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (!allowReplace
                && fragmentManager.findFragmentById(R.id.settings_fragment_container) != null) {
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

    @RequiresApi(Build.VERSION_CODES.S)
    private List<Integer> getUserIdListToShowProfileTabs(UserManagerState userManagerState) {
        List<Integer> userIdList = new ArrayList<>();
        for (UserId userId : userManagerState.getAllUserProfileIds()) {
            if (!userManagerState.isProfileOff(userId)) {
                userIdList.add(userId.getIdentifier());
            }
        }
        return userIdList;
    }

    @NonNull
    private Fragment getTargetFragment(@UserIdInt int callingUserId) {
        // Target fragment is SettingsProfileSelectFragment if there exists more than one
        // UserHandles for profiles associated with the context user, including the user itself.
        // Else target fragment is SettingsCloudMediaSelectFragment
        boolean showOtherProfileTabs = false;
        List<Integer> userIdListToShowProfileTabs = null;
        if (mSettingsViewModel.getConfigStore().isPrivateSpaceInPhotoPickerEnabled()
                && SdkLevel.isAtLeastS()) {
            final UserManagerState userManagerState = mSettingsViewModel.getUserManagerState();
            userManagerState.updateProfileOffValues();
            userIdListToShowProfileTabs = getUserIdListToShowProfileTabs(userManagerState);
            showOtherProfileTabs = userIdListToShowProfileTabs.size() > 1;
        } else {
            final UserIdManager userIdManager = mSettingsViewModel.getUserIdManager();
            if (userIdManager.isMultiUserProfiles()) {
                userIdManager.updateWorkProfileOffValue();
                // In case work profile exists and is turned off, do not show the work tab.
                if (!userIdManager.isWorkProfileOff()) {
                    showOtherProfileTabs = true;
                }
            }
        }

        if (showOtherProfileTabs) {
            final int selectedProfileTab = getInitialProfileTab(
                    callingUserId, userIdListToShowProfileTabs);
            // 'userIdProvided` represents whether we are working with userIds or tab positions.
            // If we are working with tab’s user id that is only possible when
            // `Private space feature flag is enabled && SdkLevel.isAtLeastV()` , this variable will
            // be true and false otherwise
            boolean userIdProvided = SdkLevel.isAtLeastV() && mSettingsViewModel
                    .getConfigStore().isPrivateSpaceInPhotoPickerEnabled();
            return SettingsProfileSelectFragment.getProfileSelectFragment(
                    selectedProfileTab, userIdProvided, userIdListToShowProfileTabs);
        }

        return getCloudMediaSelectFragment();
    }

    @NonNull
    private Fragment getCloudMediaSelectFragment() {
        return SettingsProfileSelectFragment.getCloudMediaSelectFragment(
                UserId.CURRENT_USER.getIdentifier());
    }

    /**
     *  Returns the user id of the initially selected tab when `private space is enabled &&
     * SdkLevel.isAtLeastV()` and for rest of the case it returns the tab position of the initially
     * selected tab
     */
    private int getInitialProfileTab(@UserIdInt int callingUserId, List<Integer> userIdList) {
        final UserManager userManager = getApplicationContext().getSystemService(UserManager.class);
        int selectedTabId = callingUserId;
        if (userManager == null || callingUserId == DEFAULT_EXTRA_USER_ID) {
            selectedTabId = DEFAULT_TAB_USER_ID;
        }

        // For all the cases when private space may exist on the device, we will work with tab
        // user ids of different profiles and for rest of the case we will use tab positions to get
        // personal and work profile tabs
        if (SdkLevel.isAtLeastV()
                && mSettingsViewModel.getConfigStore().isPrivateSpaceInPhotoPickerEnabled()) {
            if (!userIdList.contains(callingUserId)) {
                selectedTabId = DEFAULT_TAB_USER_ID;
            }
            return selectedTabId;
        }

        return userManager.isManagedProfile(selectedTabId) ? WORK_TAB : PERSONAL_TAB;
    }
}
