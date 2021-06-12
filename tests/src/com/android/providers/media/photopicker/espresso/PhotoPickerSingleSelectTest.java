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

import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PhotoPickerSingleSelectTest {
    @Rule
    public ActivityScenarioRule<PhotoPickerActivity> activityScenarioRule
            = new ActivityScenarioRule<>(PhotoPickerActivity.class);

    @Test
    @Ignore("b/14882350: The test needs the device to be on to be able to run tests")
    public void testSimple() {
        // Verify activity has toolbar and photo fragment.
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
        onView(withId(R.id.fragment_container)).check(matches(isDisplayed()));

        // This should launch Preview Fragment for single select
        onView(withId(R.id.photo_list))
                .perform(RecyclerViewActions.actionOnItemAtPosition(0, click()));

        // Verify the preview buttons are displayed
        onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_add_button)).check(matches(isDisplayed()));

        onView(withId(R.id.preview_add_button)).perform(click());
        assertThat(activityScenarioRule.getScenario().getResult().getResultCode())
                .isEqualTo(Activity.RESULT_OK);
        // TODO(b/168681160): Add assertions for uri.
    }
}