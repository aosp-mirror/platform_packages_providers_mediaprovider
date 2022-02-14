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

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.providers.media.R;

import org.junit.Rule;
import org.junit.Test;

public class SpecialFormatMultiSelectTest extends SpecialFormatBaseTest {

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testPreview_multiSelect_longPress_gif() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify imageView is displayed for gif preview
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
    }

    @Test
    public void testPreview_multiSelect_longPress_animatedWebp() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify imageView is displayed for animated webp preview
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));

        // Verify GIF icon is shown for animated webp preview
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));

        // Verify Motion Photo icon is not shown for animated webp preview
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
    }

    @Test
    public void testPreview_multiSelect_longPress_nonAnimatedWebp() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify imageView is displayed for non-animated webp preview
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));

        // Verify GIF icon is not shown for non-animated webp preview
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

        // Verify Motion Photo icon is not shown for non-animated webp preview
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
    }

    @Test
    public void testPreview_multiSelect_longPress_motionPhoto() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify imageView is displayed for motion photo preview
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
    }

    @Test
    public void testPreview_multiSelect_navigation() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select items
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);
        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        // Preview Order
        // 1 - Image
        // 2 - Gif
        // 3 - Animated Webp
        // 4 - MotionPhoto
        // 5 - Non-Animated Webp
        // Navigate from Image -> Gif -> Motion Photo -> Animated Webp -> Non-Animated Webp ->
        // Animated Webp-> Gif -> Image and verify the layout
        // matches. This test does not check for common layout as that is already covered in
        // other tests.

        // 1. Image
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

        swipeLeftAndWait();
        // 2. Gif
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

        swipeLeftAndWait();
        // 3. Animated Webp
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

        swipeLeftAndWait();
        // 4. Motion Photo
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

        swipeLeftAndWait();
        // 5. Non-Animated Webp
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

        swipeRightAndWait();
        // 4. Motion Photo
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());

        swipeRightAndWait();
        // 3. Animated Webp
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

        swipeRightAndWait();
        // 2. Gif
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());

        swipeRightAndWait();
        // 1. Image
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
    }

    private void registerIdlingResourceAndWaitForIdle() {
        mRule.getScenario().onActivity((activity -> IdlingRegistry.getInstance().register(
                new ViewPager2IdlingResource(activity.findViewById(R.id.preview_viewPager)))));
        Espresso.onIdle();
    }
}
