/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.android.providers.media.photopicker.data.UserIdManager;

/**
 * SettingsViewModel stores common objects used across PhotoPickerSettingsActivity and
 * SettingsProfileSelectFragment. It also stores the tab selected state which helps maintain tab
 * state when activity is destroyed and recrreated.
 */
public class SettingsViewModel extends AndroidViewModel {
    public static final int TAB_NOT_SET = -1;

    @NonNull
    private final UserIdManager mUserIdManager;
    private int mSelectedTab;

    public SettingsViewModel(@NonNull Application application) {
        super(application);

        final Context context = application.getApplicationContext();
        mUserIdManager = UserIdManager.create(context);
        mSelectedTab = TAB_NOT_SET;
    }

    public void setSelectedTab(int selectedTab) {
        mSelectedTab = selectedTab;
    }

    public int getSelectedTab() {
        return mSelectedTab;
    }

    @NonNull
    public UserIdManager getUserIdManager() {
        return mUserIdManager;
    }
}
