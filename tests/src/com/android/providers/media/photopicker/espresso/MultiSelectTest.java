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
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotSelected;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.BottomSheetTestUtils.assertBottomSheetState;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeLeftAndWait;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeRightAndWait;
import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemDisplayed;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;
import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.app.Activity;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.action.ViewActions;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class MultiSelectTest extends PhotoPickerBaseTest {

    private static final int TAB_VIEW_PAGER_ID = R.id.picker_tab_viewpager;

    private ActivityScenario<PhotoPickerTestActivity> mScenario;

    @Before
    public void launchActivity() {
        mScenario =
                ActivityScenario.launchActivityForResult(
                        PhotoPickerBaseTest.getMultiSelectionIntent());
    }

    @After
    public void closeActivity() {
        mScenario.close();
    }

    @Test
    public void testMultiSelectDoesNotShowProfileButton() {
        assertProfileButtonNotShown();
    }

    @Test
    public void testMultiselect_showDragBar() {
        onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
    }

    @Test
    public void testMultiselect_showPrivacyText() {
        onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
    }

    @Test
    public void testMultiselect_selectIcon() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Check select icon is visible
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, R.id.overlay_gradient);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Verify that select icon is not selected yet
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Select image item thumbnail and verify select icon is selected
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Deselect the item to check item is marked as not selected.
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Now, click on the select/check icon, verify we can also click on check icon to select or
        // deselect an item.
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Click on recyclerView item, this deselects the item. Verify that we can click on any
        // region on the recyclerView item to select/deselect the item.
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, /* targetViewId */ -1);
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);
    }

    @Test
    public void testMultiSelect_bottomBar() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        final int bottomBarId = R.id.picker_bottom_bar;
        final int viewSelectedId = R.id.button_view_selected;
        final int addButtonId = R.id.button_add;

        // Initially, buttons should be hidden
        onView(withId(bottomBarId)).check(matches(not(isDisplayed())));

        // Selecting one item shows view selected and add button
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

        onView(withId(bottomBarId)).check(matches(isDisplayed()));
        onView(withId(viewSelectedId)).check(matches(isDisplayed()));
        onView(withId(viewSelectedId)).check(matches(withText(R.string.picker_view_selected)));
        onView(withId(addButtonId)).check(matches(isDisplayed()));

        // When the selected item count is 0, ViewSelected and add button should hide
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        onView(withId(bottomBarId)).check(matches(not(isDisplayed())));
        onView(withId(viewSelectedId)).check(matches(not(isDisplayed())));
        onView(withId(addButtonId)).check(matches(not(isDisplayed())));
    }

    @Test
    public void testMultiSelect_addButtonText() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        final int addButtonId = R.id.button_add;
        final String addButtonString =
                getTargetContext().getResources().getString(R.string.add);

        // Selecting one item will enable add button and show "Add (1)" as button text
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

        onView(withId(addButtonId)).check(matches(isDisplayed()));
        onView(withId(addButtonId)).check(matches(withText(addButtonString + " (1)")));

        // When the selected item count is 2, "Add (2)" should be displayed
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_THUMBNAIL_ID);
        onView(withId(addButtonId)).check(matches(isDisplayed()));
        onView(withId(addButtonId)).check(matches(withText(addButtonString + " (2)")));

        // When the item is deselected add button resets to selected count
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_THUMBNAIL_ID);
        onView(withId(addButtonId)).check(matches(isDisplayed()));
        onView(withId(addButtonId)).check(matches(withText(addButtonString + " (1)")));
    }

    @Test
    public void testMultiSelectAcrossCategories() {
        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());

        // Selecting one item will enable add button and show "Add (1)" as button text
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);

        final int addButtonId = R.id.button_add;
        final String addButtonString =
                getTargetContext().getResources().getString(R.string.add);
        onView(withId(addButtonId)).check(matches(isDisplayed()));
        onView(withId(addButtonId)).check(matches(withText(addButtonString + " (1)")));

        // Click back button
        onView(withContentDescription("Navigate up")).perform(click());

        // On clicking back button we are back to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));

        // Navigate to photos in Video album
        final int videoStringId = R.string.picker_category_videos;
        onView(allOf(withText(videoStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());

        // When the selected item count is 2, "Add (2)" should be displayed
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, /* position */ 1, ICON_THUMBNAIL_ID);
        onView(withId(addButtonId)).check(matches(isDisplayed()));
        onView(withId(addButtonId)).check(matches(withText(addButtonString + " (2)")));
    }

    @Test
    public void testMultiSelectAcrossDifferentTabs() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select image item thumbnail and verify select icon is selected
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());

        final int position = 1;
        // The image item in Camera album is selected
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_CHECK_ID);

        // Deselect the item
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_THUMBNAIL_ID);
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, position, ICON_CHECK_ID);

        // Click back button
        onView(withContentDescription("Navigate up")).perform(click());

        // On clicking back button we are back to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));

        // Navigate to Photos tab
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        // The image item is not selected
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);
    }

    @Test
    @Ignore("Enable after b/228574741 is fixed")
    public void testMultiSelectTabSwiping() throws Exception {
        onView(withId(TAB_LAYOUT_ID)).check(matches(isDisplayed()));

        try (ViewPager2IdlingResource idlingResource =
                ViewPager2IdlingResource.register(mScenario, TAB_VIEW_PAGER_ID)) {
            // Swipe left, we should see albums tab
            swipeLeftAndWait(TAB_VIEW_PAGER_ID);

            onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                    .check(matches(isSelected()));
            onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                    .check(matches(isNotSelected()));
            // Verify Camera album is shown, we are in albums tab
            onView(allOf(withText(R.string.picker_category_camera),
                    isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(
                    matches(isDisplayed()));

            // Swipe right, we should see photos tab
            swipeRightAndWait(TAB_VIEW_PAGER_ID);

            onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                    .check(matches(isSelected()));
            onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                    .check(matches(isNotSelected()));
            // Verify first item is recent header, we are in photos tab
            onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW_ID)
                    .atPositionOnView(0, R.id.date_header_title))
                    .check(matches(withText(R.string.recent)));
        }
    }

    @Test
    @Ignore("Enable after b/222013536 is fixed")
    public void testMultiSelectScrollDownToClose() {
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mScenario);

        try {
            bottomSheetIdlingResource.setExpectedState(STATE_EXPANDED);
            onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            mScenario.onActivity(activity -> {
                assertBottomSheetState(activity, STATE_EXPANDED);
            });

            // Shows dragBar and privacy text after we are back to Photos tab
            onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            mScenario.onActivity(activity -> {
                assertBottomSheetState(activity, STATE_EXPANDED);
            });

            // Swiping down on drag bar or toolbar is not closing the bottom sheet as closing the
            // bottomsheet requires a stronger downward swipe.
            onView(withId(R.id.bottom_sheet)).perform(ViewActions.swipeDown());
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }

        assertThat(mScenario.getResult().getResultCode()).isEqualTo(
                Activity.RESULT_CANCELED);
    }


    private void assertProfileButtonNotShown() {
        // Partial screen does not show profile button
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).perform(click());
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        // Click back button
        onView(withContentDescription("Navigate up")).perform(click());

        // on clicking back button we are back to Album grid
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));
    }
}
