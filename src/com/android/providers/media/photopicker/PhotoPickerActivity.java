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
import android.content.Intent;
import android.os.Bundle;
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
import com.android.providers.media.photopicker.ui.PhotosTabFragment;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {

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

        final boolean canSelectMultiple = getIntent().getBooleanExtra(
                Intent.EXTRA_ALLOW_MULTIPLE, false);

        mPickerViewModel = new ViewModelProvider(this).get(PickerViewModel.class);
        mPickerViewModel.setSelectMultiple(canSelectMultiple);

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

        profileSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Toast.makeText(PhotoPickerActivity.this, "Switching to work profile",
                            Toast.LENGTH_SHORT).show();
                    // TODO(b/190024747): Add caching for performance before switching data to and
                    //  fro work profile
                    mUserIdManager.setCurrentUserProfileId(mUserIdManager.getManagedUserId());

                } else {
                    Toast.makeText(PhotoPickerActivity.this, "Switching to personal profile",
                            Toast.LENGTH_SHORT).show();
                    // TODO(b/190024747): Add caching for performance before switching data to and
                    //  fro work profile
                    mUserIdManager.setCurrentUserProfileId(mUserIdManager.getPersonalUserId());
                }
                mPickerViewModel.updateItems();
            }
        });
    }

    public void setResultAndFinishSelf() {
        final List<Item> selectedItemList = new ArrayList<>(
                mPickerViewModel.getSelectedItems().getValue().values());
        setResult(Activity.RESULT_OK, getPickerResponseIntent(this, selectedItemList));
        finish();
    }
}