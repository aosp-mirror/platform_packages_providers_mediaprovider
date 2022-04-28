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
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class ActiveProfileButtonTest extends PhotoPickerBaseTest {
    private static final int PROFILE_BUTTON = R.id.profile_button;

    @BeforeClass
    public static void setupClass() throws Exception {
        PhotoPickerBaseTest.setupClass();
        PhotoPickerBaseTest.setUpActiveProfileButton();
    }

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule =
            new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testProfileButton_hideInAlbumPhotos() throws Exception {
        // Verify profile button is displayed
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));

        // Goto Albums page
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        // Verify profile button is displayed
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));

        // Navigate to photos in Camera album
        onView(allOf(withText(R.string.picker_category_camera),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());
        // Verify profile button is not displayed
        onView(withId(PROFILE_BUTTON)).check(matches(not(isDisplayed())));

        // Click back button
        onView(withContentDescription("Navigate up")).perform(click());

        // on clicking back button we are back to Album grid
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        // Verify profile button is displayed
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));

        // Goto Photos grid
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        // Verify profile button is displayed
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));
    }

    @Test
    public void testProfileButton_hideOnItemSelection() throws Exception {
        // Verify profile button is displayed
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));

        // Select 1st item thumbnail and verify profile button is not shown
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        onView(withId(PROFILE_BUTTON)).check(matches(not(isDisplayed())));

        // Deselect the item to check profile button is shown
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));

        // Select 1st item thumbnail and verify profile button is not shown
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);
        onView(withId(PROFILE_BUTTON)).check(matches(not(isDisplayed())));

        // Goto Albums page and verify profile button is not shown
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(withId(PROFILE_BUTTON)).check(matches(not(isDisplayed())));
    }

    @Test
    public void testProfileButton_doesNotShowErrorDialog() throws Exception {
        // Verify profile button is displayed
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed()));
        // Check the text on the button. It should be "Switch to work"
        onView(withText(R.string.picker_work_profile)).check(matches(isDisplayed()));

        // verify clicking it does not open error dialog
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed())).perform(click());
        onView(withText(R.string.picker_profile_admin_title)).check(doesNotExist());
        onView(withText(R.string.picker_profile_work_paused_title)).check(doesNotExist());

        // Clicking the button, it takes a few ms to change the string.
        // Wait 100ms to be sure.
        // TODO(b/201982046): Replace with more stable workaround using Espresso idling resources
        Thread.sleep(100);
        onView(withText(R.string.picker_personal_profile)).check(matches(isDisplayed()));

        // verify clicking it does not open error dialog
        onView(withId(PROFILE_BUTTON)).check(matches(isDisplayed())).perform(click());
        onView(withText(R.string.picker_profile_admin_title)).check(doesNotExist());
        onView(withText(R.string.picker_profile_work_paused_title)).check(doesNotExist());

        // Clicking the button, it takes a few ms to change the string.
        // Wait 100ms to be sure.
        // TODO(b/201982046): Replace with more stable workaround using Espresso idling resources
        Thread.sleep(100);
        onView(withText(R.string.picker_work_profile)).check(matches(isDisplayed()));
    }
}
