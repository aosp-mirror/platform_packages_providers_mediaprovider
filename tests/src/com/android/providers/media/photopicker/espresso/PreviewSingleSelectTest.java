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
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class PreviewSingleSelectTest extends PhotoPickerBaseTest {
    private static final int ICON_THUMBNAIL_ID = R.id.icon_thumbnail;

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getSingleSelectionIntent());

    @Test
    public void testPreview_singleSelect_image() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify image is previewed
        assertSingleSelectCommonLayoutMatches();
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));

        // Navigate back to Photo grid
        onView(withContentDescription("Navigate up")).perform(click());

        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_singleSelect_video() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Since there is no video in the video file, we get an error.
        onView(withText(android.R.string.ok)).perform(click());

        // Verify videoView is displayed
        assertSingleSelectCommonLayoutMatches();
        onView(withId(R.id.preview_videoView)).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_singleSelect_gif() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify imageView is displayed for gif preview
        assertSingleSelectCommonLayoutMatches();
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_singleSelect_fromAlbumsPhoto() {
        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .perform(click());

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());

        // Verify that toolbar has the title as category name Camera
        onView(allOf(withText(cameraStringId), withParent(withId(R.id.toolbar))))
                .check(matches(isDisplayed()));

        // Navigate to preview
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify image is previewed
        assertSingleSelectCommonLayoutMatches();
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));

        // Navigate back to Camera album
        onView(withContentDescription("Navigate up")).perform(click());

        // Verify that toolbar has the title as category name Camera
        onView(allOf(withText(cameraStringId), withParent(withId(R.id.toolbar))))
                .check(matches(isDisplayed()));
    }

    private void registerIdlingResourceAndWaitForIdle() {
        mRule.getScenario().onActivity((activity -> IdlingRegistry.getInstance().register(
                new ViewPager2IdlingResource(activity.findViewById(R.id.preview_viewPager)))));
        Espresso.onIdle();
    }

    private void assertSingleSelectCommonLayoutMatches() {
        onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_select_button)).check(matches(not(isDisplayed())));
        onView(withId(R.id.preview_add_button)).check(matches(isDisplayed()));
        // Verify that the text in Add button
        onView(withId(R.id.preview_add_button)).check(matches(withText(R.string.add)));
    }
}
