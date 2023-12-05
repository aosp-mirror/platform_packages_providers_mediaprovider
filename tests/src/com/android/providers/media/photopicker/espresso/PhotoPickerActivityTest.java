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
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.providers.media.PickerUriResolver.REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI;
import static com.android.providers.media.photopicker.espresso.BottomSheetTestUtils.assertBottomSheetState;
import static com.android.providers.media.photopicker.espresso.OrientationUtils.setLandscapeOrientation;
import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;
import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.content.ContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.action.ViewActions;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.library.RunOnlyOnPostsubmit;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

@RunOnlyOnPostsubmit
@RunWith(AndroidJUnit4ClassRunner.class)
public class PhotoPickerActivityTest extends PhotoPickerBaseTest {

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
        // Assuming by default, the tests run without a managed user
        // Single user mode does not show profile button
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));
        onView(withId(android.R.id.empty)).check(matches(not(isDisplayed())));

        final String cancelString =
                InstrumentationRegistry.getTargetContext().getResources().getString(
                        android.R.string.cancel);
        onView(withContentDescription(cancelString)).perform(click());
        assertThat(mScenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    public void testProfileButtonHiddenInSingleUserMode() {
        // Assuming that the test runs without a managed user

        // Single user mode does not show profile button in the main grid
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        onView(withId(TAB_LAYOUT_ID)).check(matches(isDisplayed()));

        // On clicking albums tab item, we should see albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isNotSelected()));

        // Single user mode does not show profile button in the albums grid
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));
    }

    @Test
    @Ignore("b/313489524")
    // TODO(b/313489524): Fix flaky orientation change in the photo picker espresso tests
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
    public void testResetOnCloudProviderChange() throws InterruptedException {
        // Enable cloud media feature for the activity through the test config store
        mScenario.onActivity(
                activity ->
                        activity.getConfigStore()
                                .enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                                        "com.hooli.super.awesome.cloud.provider"));

        // Switch to the albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));

        // Navigate to the photos in the Camera album
        final int cameraStringId = R.string.picker_category_camera;
        onView(allOf(withText(cameraStringId), isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID))))
                .perform(click());
        onView(allOf(withText(cameraStringId), withParent(withId(R.id.toolbar))))
                .check(matches(isDisplayed()));

        // Notify refresh ui
        final ContentResolver contentResolver =
                getInstrumentation().getTargetContext().getContentResolver();
        contentResolver.notifyChange(
                REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI, /* observer= */ null);

        TimeUnit.MILLISECONDS.sleep(/* timeout= */ 100);

        // Verify activity reset to the initial launch state (Photos tab)
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
    }
}
