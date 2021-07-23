/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.providers.media.photopicker.data.PickerResult.getPickerResponseIntent;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;

import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.ui.PhotosTabFragment;
import com.android.providers.media.photopicker.util.CrossProfileUtils;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {
    private static final String TAG =  "PhotoPickerActivity";

    private PickerViewModel mPickerViewModel;
    private UserIdManager mUserIdManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_picker);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // TODO (b/185801192): remove this and add tabs Photos and Albums
        getSupportActionBar().setTitle("Photos & Videos");

        mPickerViewModel = new ViewModelProvider(this).get(PickerViewModel.class);
        try {
            mPickerViewModel.parseValuesFromIntent(getIntent());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Finish activity due to: " + e);
            setCancelledResultAndFinishSelf();
        }

        // only add the fragment when the activity is created at first time
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragment_container, PhotosTabFragment.class, null)
                    .commitNow();
        }

        mUserIdManager = mPickerViewModel.getUserIdManager();
        final Switch profileSwitch = findViewById(R.id.workprofile);
        if (mUserIdManager.isMultiUserProfiles()) {
            profileSwitch.setVisibility(View.VISIBLE);
            setUpWorkProfileToggleSwitch(profileSwitch);
        }
    }

    private void setUpWorkProfileToggleSwitch(Switch profileSwitch) {
        if (mUserIdManager.isManagedUserId()) {
            profileSwitch.setChecked(true);
        }

        final Context context = this;
        profileSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Toast.makeText(PhotoPickerActivity.this, "Switching to work profile",
                            Toast.LENGTH_SHORT).show();
                    // TODO(b/190024747): Add caching for performance before switching data to and
                    //  fro work profile
                    mUserIdManager.setManagedAsCurrentUserProfile();

                } else {
                    Toast.makeText(PhotoPickerActivity.this, "Switching to personal profile",
                            Toast.LENGTH_SHORT).show();
                    // TODO(b/190024747): Add caching for performance before switching data to and
                    //  fro work profile
                    mUserIdManager.setPersonalAsCurrentUserProfile();
                }

                // Cross user checks
                if (!mUserIdManager.isCurrentUserSelected()) {
                    final PackageManager packageManager = context.getPackageManager();
                    // 1. Check if PICK_IMAGES intent is allowed by admin to show cross user content
                    if (!CrossProfileUtils.isPickImagesIntentAllowedCrossProfileAccess(
                            packageManager)) {
                        Log.i(TAG, "Device admin restricts PhotoPicker to show cross profile "
                                + "content for current user: " + UserId.CURRENT_USER);
                        // TODO (b/190727775): Show informative error message to the user in UI.
                        return;
                    }

                    // 2. Check if work profile is off
                    if (mUserIdManager.isManagedUserSelected()) {
                        final UserId currentUserProfileId =
                                mUserIdManager.getCurrentUserProfileId();
                        if (!CrossProfileUtils.isMediaProviderAvailable(currentUserProfileId,
                                    context)) {
                            Log.i(TAG, "Work Profile is off, please turn work profile on to "
                                    + "access work profile content");
                            // TODO (b/190727775): Show work profile turned off, please turn on.
                            return;
                        }
                    }
                }
                mPickerViewModel.updateItems();
            }
        });
    }

    public void setResultAndFinishSelf() {
        final List<Item> selectedItemList = new ArrayList<>(
                mPickerViewModel.getSelectedItems().getValue().values());
        // "persist.sys.photopicker.usepickeruri" property is used to indicate if picker uris should
        // be returned for all intent actions.
        // TODO(b/168001592): Remove this system property when intent-filter for ACTION_GET_CONTENT
        // is removed or when we don't have to send redactedUris any more.
        final boolean usePickerUriByDefault =
                SystemProperties.getBoolean("persist.sys.photopicker.usepickeruri", false);
        final boolean shouldReturnPickerUris = usePickerUriByDefault ||
                MediaStore.ACTION_PICK_IMAGES.equals(getIntent().getAction());
        setResult(Activity.RESULT_OK, getPickerResponseIntent(this, selectedItemList,
                shouldReturnPickerUris));
        finish();
    }

    private void setCancelledResultAndFinishSelf() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }
}