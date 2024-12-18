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

package com.android.providers.media.photopicker.util;

import android.annotation.SuppressLint;
import android.content.pm.UserProperties;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.UserManagerState;
import com.android.providers.media.photopicker.data.model.UserId;

public class RecentsPreviewUtil {
    private static final String TAG = "PhotoPickerRecentsPreviewUtil";

    /**
     * Show blank screen in Recents if profile with SHOW_IN_QUIET_MODE_HIDDEN property is on
     * (e.g. Private profile). This prevents leaking existence of the profile after the activity
     * is moved to Recents and the profile was switched to the quiet mode.
     */
    @SuppressLint("NewApi")
    public static void updateRecentsVisibilitySetting(ConfigStore configStore,
            UserManagerState state, AppCompatActivity activity) {
        if (!(SdkLevel.isAtLeastV() && configStore.isPrivateSpaceInPhotoPickerEnabled())) return;
        if (state == null) {
            Log.e(TAG, "Can't update Recents screenshot setting, user manager state is null");
            return;
        }
        for (UserId userId : state.getAllUserProfileIds()) {
            if (state.getShowInQuietMode(userId) == UserProperties.SHOW_IN_QUIET_MODE_HIDDEN
                    && !state.isProfileOff(userId)) {
                activity.setRecentsScreenshotEnabled(false);
                return;
            }
        }
        activity.setRecentsScreenshotEnabled(true);
    }
}
