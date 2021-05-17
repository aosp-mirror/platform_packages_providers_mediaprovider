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

package com.android.providers.media.photopicker.espresso;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;

import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PhotoPickerActivityTest {
    @Rule
    public ActivityScenarioRule<PhotoPickerActivity> activityScenarioRule
            = new ActivityScenarioRule<>(PhotoPickerActivity.class);

    @Ignore("The test needs the device to be on to be able to run tests")
    @Test
    public void testPickerActivity() {
        onView(withId(R.id.button)).check(matches(isDisplayed()));
        onView(withId(R.id.names_list)).check(matches(isDisplayed()));
        onView(withId(R.id.button)).perform(click());
        assertThat(activityScenarioRule.getScenario().getResult().getResultCode())
                .isEqualTo(Activity.RESULT_OK);
    }
}