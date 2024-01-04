/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
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
import com.android.providers.media.library.RunOnlyOnPostsubmit;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link DisabledAccessibilityTest} tests the
 * {@link com.android.providers.media.photopicker.PhotoPickerActivity} behaviors that require it to
 * launch in partial screen.
 */
@RunOnlyOnPostsubmit
@RunWith(AndroidJUnit4ClassRunner.class)
public class DisabledAccessibilityTest extends PhotoPickerBaseTest {

    private ActivityScenario<PhotoPickerAccessibilityDisabledTestActivity> mScenario;

    /**
     * Note - {@link ActivityScenario#launchActivityForResult(Class)} launches the activity with the
     * intent action {@link android.content.Intent#ACTION_MAIN}.
     */
    @Before
    public void launchActivity() {
        mScenario = ActivityScenario.launchActivityForResult(
                PhotoPickerAccessibilityDisabledTestActivity.class);
    }

    @After
    public void closeActivity() {
        if (mScenario != null) {
            mScenario.close();
        }
    }

    @Test
    @Ignore("b/313489524")
    // TODO(b/313489524): Fix flaky orientation change in the photo picker espresso tests
    public void testBottomSheetState() {
        // Bottom sheet assertions are different based on the orientation
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
    @Ignore("b/313489524")
    // TODO(b/313489524): Fix flaky orientation change in the photo picker espresso tests
    public void testBottomSheetStateInLandscapeMode() {
        // Bottom sheet assertions are different based on the orientation
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
    public void testTabSwiping() throws Exception {
        // Bottom sheet assertions are different based on the orientation
        setPortraitOrientation(mScenario);

        onView(withId(TAB_LAYOUT_ID)).check(matches(isDisplayed()));

        // If we want to swipe the viewPager2 of tabContainerFragment in Espresso tests, at least 90
        // percent of the view's area is displayed to the user. Swipe up the bottom Sheet to make
        // sure it is in full Screen mode.
        // Register bottom sheet idling resource so that we don't read bottom sheet state when
        // in between changing states
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mScenario);

        try {
            // Single select PhotoPicker is launched in partial screen mode
            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            mScenario.onActivity(activity -> {
                assertBottomSheetState(activity, STATE_COLLAPSED);
            });

            // Swipe up and check that the PhotoPicker is in full screen mode.
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).perform(ViewActions.swipeUp());
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

    @Test
    public void testPreview_singleSelect_image() throws Exception {
        // Bottom sheet assertions are different based on the orientation
        setPortraitOrientation(mScenario);

        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(mScenario);

        try {
            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));
            mScenario.onActivity(activity -> {
                assertBottomSheetState(activity, STATE_COLLAPSED);
            });

            // Navigate to preview
            longClickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

            UiEventLoggerTestUtils.verifyLogWithInstanceIdAndPosition(mScenario,
                    PhotoPickerUiEventLogger.PhotoPickerEvent.PHOTO_PICKER_PREVIEW_ITEM_MAIN_GRID,
                    _SPECIAL_FORMAT_NONE, JPEG_IMAGE_MIME_TYPE, IMAGE_1_POSITION);

            try (ViewPager2IdlingResource idlingResource =
                         ViewPager2IdlingResource.register(mScenario, PREVIEW_VIEW_PAGER_ID)) {
                // No dragBar in preview
                bottomSheetIdlingResource.setExpectedState(STATE_EXPANDED);
                onView(withId(DRAG_BAR_ID)).check(matches(not(isDisplayed())));
                // No privacy text in preview
                onView(withId(PRIVACY_TEXT_ID)).check(matches(not(isDisplayed())));
                mScenario.onActivity(activity -> {
                    assertBottomSheetState(activity, STATE_EXPANDED);
                });

                // Verify image is previewed
                PreviewFragmentAssertionUtils.assertSingleSelectCommonLayoutMatches();
                onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
                // Verify no special format icon is previewed
                onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
                onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
                // Verify the overflow menu is not shown for PICK_IMAGES intent
                assertOverflowMenuNotShown();
            }
            // Navigate back to Photo grid
            onView(withContentDescription("Navigate up")).perform(click());

            onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));
            onView(withId(DRAG_BAR_ID)).check(matches(isDisplayed()));
            onView(withId(PRIVACY_TEXT_ID)).check(matches(isDisplayed()));

            bottomSheetIdlingResource.setExpectedState(STATE_COLLAPSED);
            // Shows dragBar and privacy text after we are back to Photos tab
            mScenario.onActivity(activity -> {
                assertBottomSheetState(activity, STATE_COLLAPSED);
            });
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }
    }
}
