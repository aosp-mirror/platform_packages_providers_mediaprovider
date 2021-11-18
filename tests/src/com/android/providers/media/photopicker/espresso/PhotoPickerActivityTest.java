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
import static androidx.test.espresso.matcher.ViewMatchers.hasChildCount;
import static androidx.test.espresso.matcher.ViewMatchers.isClickable;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotSelected;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;

import android.app.Activity;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class PhotoPickerActivityTest extends PhotoPickerBaseTest {
    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getSingleSelectionIntent());

    /**
     * Simple test to check we are able to launch PhotoPickerActivity
     */
    @Test
    public void testActivityLayout_Simple() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
        onView(withId(R.id.fragment_container)).check(matches(isDisplayed()));
        onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
        onView(withContentDescription("Navigate up")).perform(click());
        assertThat(mRule.getScenario().getResult().getResultCode()).isEqualTo(
                Activity.RESULT_CANCELED);
    }

    @Test
    public void testToolbarLayout() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));

        onView(withId(CHIP_CONTAINER_ID)).check(matches(isDisplayed()));
        onView(withId(CHIP_CONTAINER_ID)).check(matches(hasChildCount(2)));

        onView(allOf(withText(PICKER_PHOTOS_STRING_ID),
                isDescendantOfA(withId(CHIP_CONTAINER_ID)))).check(matches(isDisplayed()));
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID),
                isDescendantOfA(withId(CHIP_CONTAINER_ID)))).check(matches(isClickable()));

        onView(allOf(withText(PICKER_ALBUMS_STRING_ID),
                isDescendantOfA(withId(CHIP_CONTAINER_ID)))).check(matches(isDisplayed()));
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID),
                isDescendantOfA(withId(CHIP_CONTAINER_ID)))).check(matches(isClickable()));

        // TODO(b/200513333): Check close icon
    }

    @Test
    public void testTabChipNavigation() {
        onView(withId(CHIP_CONTAINER_ID)).check(matches(isDisplayed()));

        // On clicking albums tab, we should see albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .perform(click());
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isNotSelected()));
        // Verify Camera album is shown, we are in albums tab
        onView(allOf(withText(R.string.picker_category_camera),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));


        // On clicking photos tab chip, we should see photos tab
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .perform(click());
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isNotSelected()));
        // Verify first item is recent header, we are in photos tab
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW_ID)
                .atPositionOnView(0, R.id.date_header_title))
                .check(matches(withText(R.string.recent)));
    }
}