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
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeLeftAndWait;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeRightAndWait;
import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.providers.media.R;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class SpecialFormatMultiSelectTest extends SpecialFormatBaseTest {

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testPreview_multiSelect_longPress_gif() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Verify imageView is displayed for gif preview
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        }
    }

    @Test
    public void testPreview_multiSelect_longPress_animatedWebp() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Verify imageView is displayed for animated webp preview
            onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));

            // Verify GIF icon is shown for animated webp preview
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));

            // Verify Motion Photo icon is not shown for animated webp preview
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

            // Verify the overflow menu is not shown for PICK_IMAGES intent
            assertOverflowMenuNotShown();
        }
    }

    @Test
    public void testPreview_multiSelect_longPress_nonAnimatedWebp() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Verify imageView is displayed for non-animated webp preview
            onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));

            // Verify GIF icon is not shown for non-animated webp preview
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

            // Verify Motion Photo icon is not shown for non-animated webp preview
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

            // Verify the overflow menu is not shown for PICK_IMAGES intent
            assertOverflowMenuNotShown();
        }
    }

    @Test
    public void testPreview_multiSelect_longPress_motionPhoto() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Verify imageView is displayed for motion photo preview
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
            onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
            // Verify the overflow menu is not shown for PICK_IMAGES intent
            assertOverflowMenuNotShown();
        }
    }

    @Test
    @Ignore("Enable after b/218806007 is fixed")
    public void testPreview_multiSelect_navigation() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select items
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);
        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Preview Order
            // 1 - Gif
            // 2 - Animated Webp
            // 3 - MotionPhoto
            // 4 - Non-Animated Webp
            // Navigate from Gif -> Motion Photo -> Animated Webp -> Non-Animated Webp ->
            // Animated Webp -> Gif and verify the layout matches.
            // This test does not check for common layout as that is already covered in
            // other tests.

            // 1. Gif
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

            swipeLeftAndWait(PREVIEW_VIEW_PAGER_ID);
            // 2. Animated Webp
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

            swipeLeftAndWait(PREVIEW_VIEW_PAGER_ID);
            // 3. Motion Photo
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

            swipeLeftAndWait(PREVIEW_VIEW_PAGER_ID);
            // 4. Non-Animated Webp
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

            swipeRightAndWait(PREVIEW_VIEW_PAGER_ID);
            // 3. Motion Photo
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

            swipeRightAndWait(PREVIEW_VIEW_PAGER_ID);
            // 2. Animated Webp
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

            swipeRightAndWait(PREVIEW_VIEW_PAGER_ID);
            // 1. Gif
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        }
    }
}
