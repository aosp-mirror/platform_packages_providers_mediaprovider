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
import static androidx.test.espresso.action.ViewActions.swipeLeft;
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

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;

import static org.hamcrest.Matchers.allOf;

import android.view.View;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralLocation;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class PreviewMultiSelectTest extends PhotoPickerBaseTest {
    private static final int PREVIEW_VIEW_PAGER_ID = R.id.preview_viewPager;
    private static final int ICON_THUMBNAIL_ID = R.id.icon_thumbnail;
    private static final int VIEW_SELECTED_BUTTON_ID = R.id.button_view_selected;
    private static final int IMAGE_VIEW_ID = R.id.preview_imageView;
    private static final int VIDEO_VIEW_ID = R.id.preview_videoView;

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testPreview_multiSelect_common() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select two items and Navigate to preview
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        assertMultiSelectPreviewCommonLayoutDisplayed();
        // Verify ImageView is displayed
        onView(withId(IMAGE_VIEW_ID)).check(matches(isCompletelyDisplayed()));

        // Click back button and verify we are back to photos tab
        onView(withContentDescription("Navigate up")).perform(click());
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_multiSelect_deselect() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select image and gif
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);
        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        registerIdlingResourceAndWaitForIdle();

        final String addButtonString =
                getTargetContext().getResources().getString(R.string.add);
        final int previewAddButtonId = R.id.preview_add_button;
        final int previewSelectButtonId = R.id.preview_select_button;
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

        // Select the item again
        onView(withId(previewSelectButtonId)).perform(click());
        onView(withId(previewSelectButtonId)).check(matches(isSelected()));
        onView(withId(previewSelectButtonId)).check(matches(withText(deselectString)));
        // Verify that the text in Add button now changes back to "Add (2)"
        onView(withId(previewAddButtonId))
                .check(matches(withText(addButtonString + " (2)")));
    }

    @Test
    public void testPreview_multiSelect_navigation() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select items
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 3, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 2, ICON_THUMBNAIL_ID);
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);
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
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeLeftAndWait();
        // 2. Gif
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, IMAGE_VIEW_ID))
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
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeRightAndWait();
        // 1. Image
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));

        swipeLeftAndWait();
        // 2. Gif
        assertMultiSelectPreviewCommonLayoutDisplayed();
        onView(ViewPagerMatcher(PREVIEW_VIEW_PAGER_ID, IMAGE_VIEW_ID))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testPreview_multiSelect_fromAlbumsTab() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select 1 item in Photos tab
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);
        final int iconCheckId = R.id.icon_check;
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, iconCheckId);

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
        onView(withId(IMAGE_VIEW_ID)).check(matches(isCompletelyDisplayed()));

        // Click back button and verify we are back to Albums tab
        onView(withContentDescription("Navigate up")).perform(click());
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), withParent(withId(CHIP_CONTAINER_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));
    }

    private void assertMultiSelectPreviewCommonLayoutDisplayed() {
        onView(withId(PREVIEW_VIEW_PAGER_ID)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_add_button)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_select_button)).check(matches(isDisplayed()));
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

    private void swipeLeftAndWait() {
        onView(withId(PREVIEW_VIEW_PAGER_ID)).perform(swipeLeft());
        Espresso.onIdle();
    }

    /**
     * A custom swipeRight method to avoid system gestures taking over ViewActions#swipeRight
     */
    private static ViewAction customSwipeRight() {
        return new GeneralSwipeAction(Swipe.FAST, GeneralLocation.CENTER,
                GeneralLocation.CENTER_RIGHT, Press.FINGER);
    }

    private void swipeRightAndWait() {
        // Use customSwipeRight to avoid system gestures taking over ViewActions#swipeRight
        onView(withId(PREVIEW_VIEW_PAGER_ID)).perform(customSwipeRight());
        Espresso.onIdle();
    }
}
