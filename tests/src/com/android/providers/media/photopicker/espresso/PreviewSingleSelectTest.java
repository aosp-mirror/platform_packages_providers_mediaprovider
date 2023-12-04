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

import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.OrientationUtils.setLandscapeOrientation;
import static com.android.providers.media.photopicker.espresso.OrientationUtils.setPortraitOrientation;
import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;
import com.android.providers.media.library.RunOnlyOnPostsubmit;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger.PhotoPickerEvent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunOnlyOnPostsubmit
@RunWith(AndroidJUnit4ClassRunner.class)
public class PreviewSingleSelectTest extends PhotoPickerBaseTest {

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getSingleSelectionIntent());

    @Test
    public void testPreview_singleSelect_video() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_THUMBNAIL_ID);

        UiEventLoggerTestUtils.verifyLogWithInstanceIdAndPosition(
                mRule, PhotoPickerEvent.PHOTO_PICKER_PREVIEW_ITEM_MAIN_GRID,
                _SPECIAL_FORMAT_NONE, MP4_VIDEO_MIME_TYPE, VIDEO_POSITION);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            PreviewFragmentAssertionUtils.assertSingleSelectCommonLayoutMatches();
            // Verify thumbnail view is displayed
            onView(withId(R.id.preview_video_image)).check(matches(isDisplayed()));
            // TODO (b/232792753): Assert video player visibility using custom IdlingResource

            // Verify no special format icon is previewed
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
            // Verify the overflow menu is not shown for PICK_IMAGES intent
            assertOverflowMenuNotShown();
        }
    }

    @Test
    public void testPreview_singleSelect_fromAlbumsPhoto() throws Exception {
        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());

        // Verify that toolbar has the title as category name Camera
        onView(allOf(withText(cameraStringId), withParent(withId(R.id.toolbar))))
                .check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Verify image is previewed
            PreviewFragmentAssertionUtils.assertSingleSelectCommonLayoutMatches();
            onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
        }

        // Navigate back to Camera album
        onView(withContentDescription("Navigate up")).perform(click());

        // Verify that toolbar has the title as category name Camera
        onView(allOf(withText(cameraStringId), withParent(withId(R.id.toolbar))))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_noScrimLayerAndHasSolidColorInPortrait() throws Exception {
        setPortraitOrientation(mRule.getScenario());

        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            onView(withId(R.id.preview_top_scrim)).check(matches(not(isDisplayed())));
            onView(withId(R.id.preview_bottom_scrim)).check(matches(not(isDisplayed())));

            mRule.getScenario().onActivity(activity -> {
                assertBackgroundColorOnToolbarAndBottomBar(activity,
                        R.color.preview_scrim_solid_color);
            });
        }
    }

    @Test
    public void testPreview_showScrimLayerInLandscape() throws Exception {
        setLandscapeOrientation(mRule.getScenario());

        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            onView(withId(R.id.preview_top_scrim)).check(matches(isDisplayed()));
            onView(withId(R.id.preview_bottom_scrim)).check(matches(isDisplayed()));

            mRule.getScenario().onActivity(activity -> {
                assertBackgroundColorOnToolbarAndBottomBar(activity, android.R.color.transparent);
            });
        }
    }

    @Test
    public void testPreview_addButtonVisible() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Check that Add button is visible
            onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(isDisplayed()));
            onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(withText(R.string.add)));
        }
    }

    private void assertBackgroundColorOnToolbarAndBottomBar(Activity activity, int colorResId) {
        final Toolbar toolbar = activity.findViewById(R.id.toolbar);
        final Drawable toolbarDrawable = toolbar.getBackground();

        assertThat(toolbarDrawable).isInstanceOf(ColorDrawable.class);

        final int expectedColor = activity.getColor(colorResId);
        assertThat(((ColorDrawable) toolbarDrawable).getColor()).isEqualTo(expectedColor);

        final View bottomBar = activity.findViewById(R.id.preview_bottom_bar);
        final Drawable bottomBarDrawable = bottomBar.getBackground();

        assertThat(bottomBarDrawable).isInstanceOf(ColorDrawable.class);
        assertThat(((ColorDrawable) bottomBarDrawable).getColor()).isEqualTo(expectedColor);
    }
}
