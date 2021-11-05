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

import static androidx.test.InstrumentationRegistry.getTargetContext;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotSelected;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeLeftAndWait;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeRightAndWait;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;

import android.view.View;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class PreviewMultiSelectTest extends PhotoPickerBaseTest {
    private static final int VIDEO_VIEW_ID = R.id.preview_videoView;

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testPreview_multiSelect_common() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select two items and Navigate to preview
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        assertMultiSelectPreviewCommonLayoutDisplayed();
        // Verify ImageView is displayed
        onView(withId(PREVIEW_IMAGE_VIEW_ID)).check(matches(isCompletelyDisplayed()));

        // Click back button and verify we are back to photos tab
        onView(withContentDescription("Navigate up")).perform(click());
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_multiSelect_deselect() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select image and gif
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        final String addButtonString =
                getTargetContext().getResources().getString(R.string.add);
        final int previewAddButtonId = R.id.preview_add_or_select_button;
        final int previewSelectButtonId = R.id.preview_select_check_button;
        final String deselectString =
                getTargetContext().getResources().getString(R.string.deselect);

        // Verify that, initially, we show deselect button
        onView(withId(previewSelectButtonId)).check(matches(isSelected()));
        onView(withId(previewSelectButtonId)).check(matches(withText(deselectString)));
        // Verify that the text in Add button matches
        onView(withId(previewAddButtonId))
                .check(matches(withText(addButtonString + " (2)")));

        // Deselect item in preview
        onView(withId(previewSelectButtonId)).perform(click());
        onView(withId(previewSelectButtonId)).check(matches(isNotSelected()));
        onView(withId(previewSelectButtonId)).check(matches(withText(R.string.select)));
        // Verify that the text in Add button now changes to "Add (1)"
        onView(withId(previewAddButtonId))
                .check(matches(withText(addButtonString + " (1)")));
        // Verify that we have one item in selected items
        mRule.getScenario().onActivity(activity -> {
            Selection selection
                    = new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
            assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(1);
        });

        // Select the item again
        onView(withId(previewSelectButtonId)).perform(click());
        onView(withId(previewSelectButtonId)).check(matches(isSelected()));
        onView(withId(previewSelectButtonId)).check(matches(withText(deselectString)));
        // Verify that the text in Add button now changes back to "Add (2)"
        onView(withId(previewAddButtonId))
                .check(matches(withText(addButtonString + " (2)")));
        // Verify that we have 2 items in selected items
        mRule.getScenario().onActivity(activity -> {
            Selection selection
                    = new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
            assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(2);
        });
    }

    @Test
    public void testPreview_multiSelect_navigation() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select items
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_POSITION, ICON_THUMBNAIL_ID);
        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        // Preview Order
        // 1 - Image
        // 2 - Gif
        // 3 - Video
        // Navigate from Image -> Gif -> Video -> Gif -> Image -> Gif and verify the layout matches

        // 1. Image
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, PREVIEW_IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeLeftAndWait();
        // 2. Gif
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, PREVIEW_IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeLeftAndWait();
        // Since there is no video in the video file, we get an error.
        onView(withText(android.R.string.ok)).perform(click());
        // 3. Video item
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, VIDEO_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeRightAndWait();
        // 2. Gif
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, PREVIEW_IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeRightAndWait();
        // 1. Image
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, PREVIEW_IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeLeftAndWait();
        // 2. Gif
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, PREVIEW_IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_multiSelect_fromAlbumsTab() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select 1 item in Photos tab
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_POSITION, ICON_THUMBNAIL_ID);
        final int iconCheckId = R.id.icon_check;
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_POSITION, iconCheckId);

        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .perform(click());
        // The Albums tab chip is selected
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isSelected()));
        final int cameraStringId = R.string.picker_category_camera;
        // Camera album is shown
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));

        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        assertMultiSelectPreviewCommonLayoutDisplayed();
        // Verify ImageView is displayed
        onView(withId(PREVIEW_IMAGE_VIEW_ID)).check(matches(isCompletelyDisplayed()));

        // Click back button and verify we are back to Albums tab
        onView(withContentDescription("Navigate up")).perform(click());
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_viewSelectedAfterLongPress() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select video item
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);

        final int position = 2;
        // Preview gif item using preview on long press
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify that we have one item as selected item and 1 item as item for preview, and verify
        // they are not the same.
        mRule.getScenario().onActivity(activity -> {
            Selection selection
                    = new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
            assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(1);
            assertThat(selection.getSelectedItemsForPreview().size()).isEqualTo(1);
            assertThat(selection.getSelectedItems().get(0))
                    .isNotEqualTo(selection.getSelectedItemsForPreview().get(0));
        });

        // Click back button to go back to Photos tab
        onView(withContentDescription("Navigate up")).perform(click());

        // Navigate to preview by clicking "View Selected" button.
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());
        registerIdlingResourceAndWaitForIdle();

        // Since there is no video in the video file, we get an error.
        onView(withText(android.R.string.ok)).perform(click());
        assertMultiSelectPreviewCommonLayoutDisplayed();

        // Verify that "View Selected" shows the video item, not the gif item that was previewed
        // earlier with preview on long press
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, VIDEO_VIEW_ID))
                .check(matches(isDisplayed()));

        // Swipe and verify we don't preview the gif item
        swipeLeftAndWait();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, VIDEO_VIEW_ID))
                .check(matches(isDisplayed()));
        swipeRightAndWait();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, VIDEO_VIEW_ID))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_multiSelect_acrossAlbums() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select gif and video item from Photos tab
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);

        // Navigate to albums
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .perform(click());

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());
        // Select image item from Camera category
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, 1, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());
        registerIdlingResourceAndWaitForIdle();

        // Deselect the image item
        final int previewSelectButtonId = R.id.preview_select_check_button;
        onView(withId(previewSelectButtonId)).perform(click());

        // Go back to Camera album and verify that item is deselected
        onView(withContentDescription("Navigate up")).perform(click());
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        // Go back to photo grid and verify that item is deselected
        onView(withContentDescription("Navigate up")).perform(click());
        // Navigate to Photo grid
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .perform(click());

        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        // Go back to preview and deselect another item
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());
        registerIdlingResourceAndWaitForIdle();

        // Deselect the Gif item
        onView(withId(previewSelectButtonId)).perform(click());

        // Go back to Photos tab and verify that Gif item is deselected
        onView(withContentDescription("Navigate up")).perform(click());
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);
    }

    private void assertMultiSelectPreviewCommonLayoutDisplayed() {
        onView(withId(PREVIEW_VIEW_PAGER_ID)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_add_or_select_button)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_select_check_button)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_select_check_button)).check(matches(isSelected()));
    }

    private Matcher<View> ViewPagerMatcher(int viewPagerId, int itemViewId) {
        return new TypeSafeMatcher<View>() {
            @Override
            public void describeTo(Description description) {
                // This is a minimal implementation which is enough to debug current test failures
                description.appendText("is assignable from class: " + ViewPager2.class + " with "
                        + itemViewId);
            }

            @Override
            public boolean matchesSafely(View view) {
                final ViewPager2 viewPager = view.getRootView().findViewById(viewPagerId);
                if (viewPager == null) {
                    return false;
                }

                return view == viewPager.findViewById(itemViewId);
            }
        };
    }

    private void registerIdlingResourceAndWaitForIdle() {
        mRule.getScenario().onActivity((activity -> IdlingRegistry.getInstance().register(
                new ViewPager2IdlingResource(activity.findViewById(PREVIEW_VIEW_PAGER_ID)))));
        Espresso.onIdle();
    }
}
