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
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeLeftAndWait;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeRightAndWait;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.*;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.not;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class PreviewMultiSelectLongPressTest extends PhotoPickerBaseTest {
    private static final int ICON_THUMBNAIL_ID = R.id.icon_thumbnail;

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testPreview_multiSelect_longPress_image() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // No dragBar in preview
            onView(withId(DRAG_BAR_ID)).check(matches(not(isDisplayed())));

            // No privacy text in preview
            onView(withId(PRIVACY_TEXT_ID)).check(matches(not(isDisplayed())));

            // Verify image is previewed
            assertMultiSelectLongPressCommonLayoutMatches();
            onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
            // Verify no special format icon is previewed
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
        }

        // Navigate back to Photo grid
        onView(withContentDescription("Navigate up")).perform(click());

        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
        // Shows dragBar and privacy text after we are back to Photos tab
        onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
        onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_multiSelect_longPress_video() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            assertMultiSelectLongPressCommonLayoutMatches();
            // Verify thumbnail view is displayed
            onView(withId(R.id.preview_video_image)).check(matches(isDisplayed()));
            // TODO (b/232792753): Assert video player visibility using custom IdlingResource

            // Verify no special format icon is previewed
            onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
            onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
        }
    }

    @Test
    public void testPreview_multiSelect_longPress_select() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        final int position = 1;
        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_THUMBNAIL_ID);

        final int selectButtonId = PREVIEW_ADD_OR_SELECT_BUTTON_ID;
        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Select the item within Preview
            onView(withId(selectButtonId)).perform(click());
            // Check that button text is changed to "deselect"
            onView(withId(selectButtonId)).check(matches(withText(R.string.deselect)));
        }

        // Navigate back to PhotoGrid and check that item is selected
        onView(withContentDescription("Navigate up")).perform(click());

        final int iconCheckId = R.id.icon_check;
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, position, iconCheckId);

        // Navigate to Preview and check the select button text
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            // Check that button text is set to "deselect" and common layout matches
            assertMultiSelectLongPressCommonLayoutMatches(/* isSelected */ true);

            // Click on "Deselect" and verify text changes to "Select"
            onView(withId(selectButtonId)).perform(click());
            // Check that button text is changed to "select"
            onView(withId(selectButtonId)).check(matches(withText(R.string.select)));
        }

        // Navigate back to Photo grid and verify the item is not selected
        onView(withContentDescription("Navigate up")).perform(click());

        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, position, iconCheckId);
    }

    @Test
    public void testPreview_multiSelect_longPress_showsOnlyOne() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select two items - first image and video item
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_THUMBNAIL_ID);

        // Long press second image item to preview the item.
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_THUMBNAIL_ID);

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mRule.getScenario(), PREVIEW_VIEW_PAGER_ID)) {
            mRule.getScenario().onActivity(activity -> {
                Selection selection
                        = new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
                // Verify that we have two items(first image and video) as selected items and
                // 1 item (second image) as item for preview
                assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(2);
                assertThat(selection.getSelectedItemsForPreview().size()).isEqualTo(1);
            });

            final int imageViewId = R.id.preview_imageView;
            onView(withId(imageViewId)).check(matches(isDisplayed()));

            // Verify that only one item is being previewed. Swipe left and right, and verify we
            // still have ImageView in preview.
            swipeLeftAndWait(PREVIEW_VIEW_PAGER_ID);
            onView(withId(imageViewId)).check(matches(isDisplayed()));

            swipeRightAndWait(PREVIEW_VIEW_PAGER_ID);
            onView(withId(imageViewId)).check(matches(isDisplayed()));
        }
    }

    private void assertMultiSelectLongPressCommonLayoutMatches() {
        assertMultiSelectLongPressCommonLayoutMatches(/* isSelected */ false);
    }

    private void assertMultiSelectLongPressCommonLayoutMatches(boolean isSelected) {
        onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(isDisplayed()));
        // Verify that the text in AddOrSelect button
        if (isSelected) {
            onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID))
                    .check(matches(withText(R.string.deselect)));
        } else {
            onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID))
                    .check(matches(withText(R.string.select)));
        }

        onView(withId(R.id.preview_selected_check_button)).check(matches(not(isDisplayed())));
        onView(withId(R.id.preview_add_button)).check(matches(not(isDisplayed())));
    }
}
