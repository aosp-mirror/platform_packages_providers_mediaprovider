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

import android.annotation.NonNull;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.android.providers.media.R;

/**
 * Photo Picker settings page where user can view/edit current cloud media provider.
 */
public class PhotoPickerSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // We use the device default theme as the base theme. Apply the material theme for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_picker_settings);

        displayActionBar();
        switchToFragment();
    }

    private void displayActionBar() {
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // Stop PhotoPickerSettingsActivity when back button is pressed.
                this.finish();
                return true;
            default:
        }
        return false;
    }

    private void switchToFragment() {
        // TODO(b/195009187): implement this method.
        return;
    }

}
