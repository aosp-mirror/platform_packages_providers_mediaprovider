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
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemDisplayed;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotDisplayed;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import static org.hamcrest.Matchers.not;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.providers.media.R;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class SpecialFormatSingleSelectTest extends SpecialFormatBaseTest {

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getSingleSelectionIntent());

    @Test
    public void testPhotoGridLayout_motionPhoto() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Verify we have the thumbnail
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);
        // Verify motion photo icon is displayed
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, OVERLAY_GRADIENT_ID);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION,
                ICON_MOTION_PHOTO_ID);

        // Verify check icon, video icon and gif icon are not displayed
        assertSingleSelectImageThumbnailCommonLayout(MOTION_PHOTO_POSITION);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_GIF_ID);
    }

    @Test
    public void testPhotoGridLayout_gif() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Verify we have the thumbnail
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        // Verify gif icon is displayed
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, OVERLAY_GRADIENT_ID);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_GIF_ID);

        // Verify check icon, video icon and motion photo icon are not displayed
        assertSingleSelectImageThumbnailCommonLayout(GIF_POSITION);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_MOTION_PHOTO_ID);
    }

    @Test
    public void testPhotoGridLayout_animatedWebp() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Verify we have the animated webp thumbnail
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);
        // Verify gif icon is displayed for animated webp
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION,
                OVERLAY_GRADIENT_ID);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_GIF_ID);

        // Verify check icon, video icon and motion photo icon are not displayed
        assertSingleSelectImageThumbnailCommonLayout(ANIMATED_WEBP_POSITION);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION,
                ICON_MOTION_PHOTO_ID);
    }

    @Test
    public void testPhotoGridLayout_nonAnimatedWebp() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Verify we have the non-animated webp thumbnail
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION,
                ICON_THUMBNAIL_ID);
        // Verify gif icon is not displayed for non-animated webp
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION,
                OVERLAY_GRADIENT_ID);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION, ICON_GIF_ID);

        // Verify check icon, video icon and motion photo icon are not displayed
        assertSingleSelectImageThumbnailCommonLayout(NON_ANIMATED_WEBP_POSITION);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION,
                ICON_MOTION_PHOTO_ID);
    }

    @Test
    public void testPreview_singleSelect_gif() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource
                     = ViewPager2IdlingResource.register(mRule, PREVIEW_VIEW_PAGER_ID)) {
            // Verify gif icon is displayed for gif preview
            assertSingleSelectImagePreviewCommonLayout();
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        }
    }

    @Test
    public void testPreview_singleSelect_animatedWebp() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource
                     = ViewPager2IdlingResource.register(mRule, PREVIEW_VIEW_PAGER_ID)) {
            // Verify gif icon is displayed for animated preview
            assertSingleSelectImagePreviewCommonLayout();
            onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        }
    }

    @Test
    @Ignore("Enable after b/222013536 is fixed")
    public void testPreview_singleSelect_nonAnimatedWebp() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // This is the 4th item which is on the second row
        BottomSheetTestUtils.swipeUp(mRule);

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, NON_ANIMATED_WEBP_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource
                     = ViewPager2IdlingResource.register(mRule, PREVIEW_VIEW_PAGER_ID)) {
            // Verify gif icon is not displayed for non-animated webp preview
            assertSingleSelectImagePreviewCommonLayout();
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
        }
    }

    @Test
    public void testPreview_singleSelect_motionPhoto() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource
                     = ViewPager2IdlingResource.register(mRule, PREVIEW_VIEW_PAGER_ID)) {
            // Verify motion photo icon is displayed for motion photo preview
            assertSingleSelectImagePreviewCommonLayout();
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
        }
    }

    private void assertSingleSelectCommonLayoutMatches() {
        onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(isDisplayed()));
        // Verify that the text in Add button
        onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(withText(R.string.add)));

        onView(withId(R.id.preview_selected_check_button)).check(matches(not(isDisplayed())));
        onView(withId(R.id.preview_add_button)).check(matches(not(isDisplayed())));
        // Verify the overflow menu is not shown for PICK_IMAGES intent
        assertOverflowMenuNotShown();
    }

    private void assertSingleSelectImagePreviewCommonLayout() {
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
        assertSingleSelectCommonLayoutMatches();
    }

    private void assertSingleSelectImageThumbnailCommonLayout(int position) {
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_CHECK_ID);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, VIDEO_CONTAINER_ID);
    }
}
