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
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.BottomSheetTestUtils.assertBottomSheetState;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.action.ViewActions;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import static org.hamcrest.Matchers.not;

import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class WorkAppsOffProfileButtonTest extends PhotoPickerBaseTest {
    @BeforeClass
    public static void setupClass() throws Exception {
        PhotoPickerBaseTest.setupClass();
        PhotoPickerBaseTest.setUpWorkAppsOffProfileButton();
    }

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule =
            new ActivityScenarioRule<>(PhotoPickerBaseTest.getSingleSelectionIntent());

    @Test
    @Ignore("Enable after b/218806007 is fixed")
    public void testProfileButton_dialog() throws Exception {
        // Register bottom sheet idling resource so that we don't read bottom sheet state when
        // in between changing states
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mRule);

        try {
            // Single select PhotoPicker is launched in half sheet mode
            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            mRule.getScenario().onActivity(activity -> {
                assertBottomSheetState(activity, STATE_COLLAPSED);
            });

            final int profileButtonId = R.id.profile_button;
            // Verify profile button is not displayed in partial screen
            onView(withId(profileButtonId)).check(matches(not(isDisplayed())));

            bottomSheetIdlingResource.setExpectedState(STATE_EXPANDED);
            onView(withId(PRIVACY_TEXT_ID)).perform(ViewActions.swipeUp());
            mRule.getScenario().onActivity(activity -> {
                assertBottomSheetState(activity, STATE_EXPANDED);
            });
            // Verify profile button is displayed
            onView(withId(profileButtonId)).check(matches(isDisplayed()));
            // Check the text on the button. It should be "Switch to work"
            onView(withText(R.string.picker_work_profile)).check(matches(isDisplayed()));

            // Verify onClick shows a dialog
            onView(withId(profileButtonId)).check(matches(isDisplayed())).perform(click());
            onView(withText(R.string.picker_profile_work_paused_title)).check(
                    matches(isDisplayed()));
            onView(withText(R.string.picker_profile_work_paused_msg)).check(matches(isDisplayed()));
            onView(withText(android.R.string.ok)).check(matches(isDisplayed())).perform(click());
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }
    }
}
