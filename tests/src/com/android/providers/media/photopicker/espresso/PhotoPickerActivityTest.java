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
import static org.hamcrest.Matchers.allOf;

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
        onView(withContentDescription("Navigate up")).perform(click());
    }

    @Test
    public void testToolbarLayout() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));

        final int chipContainerId = R.id.chip_container;
        final int stringPickerPhotos = R.string.picker_photos;
        final int stringPickerAlbums = R.string.picker_albums;

        onView(withId(chipContainerId)).check(matches(isDisplayed()));
        onView(withId(chipContainerId)).check(matches(hasChildCount(2)));

        onView(allOf(withText(stringPickerPhotos),
                isDescendantOfA(withId(chipContainerId)))).check(matches(isDisplayed()));
        onView(allOf(withText(stringPickerPhotos),
                isDescendantOfA(withId(chipContainerId)))).check(matches(isClickable()));

        onView(allOf(withText(stringPickerAlbums),
                isDescendantOfA(withId(chipContainerId)))).check(matches(isDisplayed()));
        onView(allOf(withText(stringPickerAlbums),
                isDescendantOfA(withId(chipContainerId)))).check(matches(isClickable()));

        // TODO(b/200513333): Check close icon
    }

    @Test
    public void testTabChipNavigation() {
        final int chipContainerId = R.id.chip_container;
        final int stringPickerPhotos = R.string.picker_photos;
        final int stringPickerAlbums = R.string.picker_albums;

        onView(withId(chipContainerId)).check(matches(isDisplayed()));

        // On clicking albums tab, we should see albums tab
        onView(allOf(withText(stringPickerAlbums), withParent(withId(chipContainerId))))
                .perform(click());
        onView(allOf(withText(stringPickerAlbums), withParent(withId(chipContainerId))))
                .check(matches(isSelected()));
        onView(allOf(withText(stringPickerPhotos), withParent(withId(chipContainerId))))
                .check(matches(isNotSelected()));
        // TODO(b/200513638): Also check respective tab is shown when we click on a particular
        // tab chip

        // On clicking photos tab chip, we should see all photos tab
        onView(allOf(withText(stringPickerPhotos), withParent(withId(chipContainerId))))
                .perform(click());
        onView(allOf(withText(stringPickerPhotos), withParent(withId(chipContainerId))))
                .check(matches(isSelected()));
        onView(allOf(withText(stringPickerAlbums), withParent(withId(chipContainerId))))
                .check(matches(isNotSelected()));
        // TODO(b/200513638): Also check respective tab is shown when we click on a particular
        // tab chip
    }
}