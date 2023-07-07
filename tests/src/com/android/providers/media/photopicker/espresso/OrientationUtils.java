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

package com.android.providers.media.photopicker.espresso;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static androidx.test.espresso.Espresso.onIdle;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ActivityScenario;

class OrientationUtils {
    public static void setLandscapeOrientation(ActivityScenario<PhotoPickerTestActivity> scenario) {
        changeOrientation(scenario, SCREEN_ORIENTATION_LANDSCAPE, ORIENTATION_LANDSCAPE);
    }

    public static void setPortraitOrientation(ActivityScenario<PhotoPickerTestActivity> scenario) {
        changeOrientation(scenario, SCREEN_ORIENTATION_PORTRAIT, ORIENTATION_PORTRAIT);
    }

    private static void changeOrientation(
            ActivityScenario<PhotoPickerTestActivity> scenario,
            int screenOrientation,
            int configOrientation) {
        scenario.onActivity(
                activity -> {
                    activity.setRequestedOrientation(screenOrientation);
                });

        onIdle();

        scenario.onActivity(
                activity -> {
                    assertThat(activity.getResources().getConfiguration().orientation)
                            .isEqualTo(configOrientation);
                });
    }
}
