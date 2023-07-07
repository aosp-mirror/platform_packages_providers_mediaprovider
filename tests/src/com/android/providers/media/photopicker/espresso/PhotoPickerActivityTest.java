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
import static androidx.test.espresso.matcher.ViewMatchers.isNotSelected;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.BottomSheetTestUtils.assertBottomSheetState;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.customSwipeDownPartialScreen;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeLeftAndWait;
import static com.android.providers.media.photopicker.espresso.CustomSwipeAction.swipeRightAndWait;
import static com.android.providers.media.photopicker.espresso.OrientationUtils.setLandscapeOrientation;
import static com.android.providers.media.photopicker.espresso.OrientationUtils.setPortraitOrientation;
import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;
import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.app.Activity;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.action.ViewActions;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class PhotoPickerActivityTest extends PhotoPickerBaseTest {

    private static final int TAB_VIEW_PAGER_ID = R.id.picker_tab_viewpager;

    public ActivityScenario<PhotoPickerTestActivity> mScenario;

    @Before
    public void launchActivity() {
        mScenario =
                ActivityScenario.launchActivityForResult(
                        PhotoPickerBaseTest.getSingleSelectionIntent());
    }

    @After
    public void closeActivity() {
        mScenario.close();
    }

    /**
     * Simple test to check we are able to launch PhotoPickerActivity
     */
    @Test
    public void testActivityLayout_Simple() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
        onView(withId(R.id.fragment_container)).check(matches(isDisplayed()));
        onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
        onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
        // Partial screen does not show profile button
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));
        onView(withId(android.R.id.empty)).check(matches(not(isDisplayed())));

        final String cancelString =
                InstrumentationRegistry.getTargetContext().getResources().getString(
                        android.R.string.cancel);
        onView(withContentDescription(cancelString)).perform(click());
        assertThat(mScenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    public void testDoesNotShowProfileButton_partialScreen() {
        assertProfileButtonNotShown();
    }

    @Test
    @Ignore("Enable after b/222013536 is fixed")
    public void testDoesNotShowProfileButton_fullScreen() {
        // Bottomsheet assertions are different for landscape mode
        setPortraitOrientation(mScenario);

        // Partial screen does not show profile button
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        BottomSheetTestUtils.swipeUp(mScenario);

        assertProfileButtonNotShown();
    }

    @Test
    @Ignore("Enable after b/222013536 is fixed")
    public void testBottomSheetState() {
        // Bottom sheet assertions are different for landscape mode
        setPortraitOrientation(mScenario);

        // Register bottom sheet idling resource so that we don't read bottom sheet state when
        // in between changing states
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mScenario);

        try {
            // Single select PhotoPicker is launched in partial screen mode
            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            mScenario.onActivity(
                    activity -> {
                        assertBottomSheetState(activity, STATE_COLLAPSED);
                    });

            // Swipe up and check that the PhotoPicker is in full screen mode
            bottomSheetIdlingResource.setExpectedState(STATE_EXPANDED);
            onView(withId(PRIVACY_TEXT_ID)).perform(ViewActions.swipeUp());
            mScenario.onActivity(
                    activity -> {
                        assertBottomSheetState(activity, STATE_EXPANDED);
                    });

            // Swipe down and check that the PhotoPicker is in partial screen mode
            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            onView(withId(PRIVACY_TEXT_ID)).perform(ViewActions.swipeDown());
            mScenario.onActivity(
                    activity -> {
                        assertBottomSheetState(activity, STATE_COLLAPSED);
                    });

            // Swiping down on drag bar is not strong enough as closing the bottomsheet requires a
            // stronger downward swipe using espresso.
            // Simply swiping down on R.id.bottom_sheet throws an error from espresso, as the view
            // is only 60% visible, but downward swipe is only successful on an element which is 90%
            // visible.
            onView(withId(R.id.bottom_sheet)).perform(customSwipeDownPartialScreen());
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }
        assertThat(mScenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    @Ignore("Enable after b/222013536 is fixed")
    public void testBottomSheetStateInLandscapeMode() {
        // Bottom sheet assertions are different for landscape mode
        setLandscapeOrientation(mScenario);

        // Register bottom sheet idling resource so that we don't read bottom sheet state when
        // in between changing states
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mScenario);

        try {
            // Single select PhotoPicker is launched in full screen mode in Landscape orientation
            mScenario.onActivity(
                    activity -> {
                        assertBottomSheetState(activity, STATE_EXPANDED);
                    });

            // Swiping down on drag bar / privacy text is not strong enough as closing the
            // bottomsheet requires a stronger downward swipe using espresso.
            onView(withId(R.id.bottom_sheet)).perform(ViewActions.swipeDown());
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }
        assertThat(mScenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    public void testToolbarLayout() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));

        onView(withId(TAB_LAYOUT_ID)).check(matches(isDisplayed()));

        mScenario.onActivity(
                activity -> {
                    final ViewPager2 viewPager2 = activity.findViewById(TAB_VIEW_PAGER_ID);
                    assertThat(viewPager2.getAdapter().getItemCount()).isEqualTo(2);
                });

        onView(allOf(withText(PICKER_PHOTOS_STRING_ID),
                isDescendantOfA(withId(TAB_LAYOUT_ID)))).check(matches(isDisplayed()));
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID),
                isDescendantOfA(withId(TAB_LAYOUT_ID)))).check(matches(isDisplayed()));

        // Verify the overflow menu is not shown for PICK_IMAGES intent
        assertOverflowMenuNotShown();

        // TODO(b/200513333): Check close icon
    }

    @Test
    public void testTabNavigation() {
        onView(withId(TAB_LAYOUT_ID)).check(matches(isDisplayed()));

        // On clicking albums tab item, we should see albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isNotSelected()));
        // Verify Camera album is shown, we are in albums tab
        onView(allOf(withText(R.string.picker_category_camera),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));

        // On clicking photos tab item, we should see photos tab
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isNotSelected()));
        // Verify first item is recent header, we are in photos tab
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW_ID)
                .atPositionOnView(0, R.id.date_header_title))
                .check(matches(withText(R.string.recent)));
    }

    @Test
    @Ignore("Enable after b/222013536 is fixed")
    public void testTabSwiping() throws Exception {
        onView(withId(TAB_LAYOUT_ID)).check(matches(isDisplayed()));

        // If we want to swipe the viewPager2 of tabContainerFragment in Espresso tests, at least 90
        // percent of the view's area is displayed to the user. Swipe up the bottom Sheet to make
        // sure it is in full Screen mode.
        // Register bottom sheet idling resource so that we don't read bottom sheet state when
        // in between changing states
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mScenario);

        try {

            // When accessibility is enabled, we always launch the photo picker in full screen mode.
            // Accessibility is enabled in Espresso test, so we can't check the COLLAPSED state.
            //            // Single select PhotoPicker is launched in partial screen mode
            //            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            //            mScenario.onActivity(activity -> {
            //                assertBottomSheetState(activity, STATE_COLLAPSED);
            //            });

            // Swipe up and check that the PhotoPicker is in full screen mode.
            //            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            //            onView(withId(PRIVACY_TEXT_ID)).perform(ViewActions.swipeUp());
            bottomSheetIdlingResource.setExpectedState(STATE_EXPANDED);
            mScenario.onActivity(
                    activity -> {
                        assertBottomSheetState(activity, STATE_EXPANDED);
                    });
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }

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
