/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.atPositionOnItemViewType;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;
import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_BANNER;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.content.Intent;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@SdkSuppress(minSdkVersion = 34, codeName = "UpsideDownCake")
@RunWith(AndroidJUnit4ClassRunner.class)
public class PhotoPickerUserSelectActivityTest extends PhotoPickerBaseTest {

    public ActivityScenario<PhotoPickerTestActivity> mScenario;

    @After
    public void closeActivity() {
        mScenario.close();
    }

    @Test
    public void testMissingUidExtraReturnsCancelled() {

        Intent intent = new Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP);
        intent.addCategory(Intent.CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST);
        mScenario = ActivityScenario.launchActivityForResult(intent);

        assertThat(mScenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    public void testActivityCancelledByUser() {
        launchValidActivity();
        final String cancelString =
                InstrumentationRegistry.getTargetContext()
                        .getResources()
                        .getString(android.R.string.cancel);
        onView(withContentDescription(cancelString)).perform(click());
        assertThat(mScenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    public void testActivityProfileButtonNotShown() {
        launchValidActivity();
        // Partial screen does not show profile button
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        // Navigate to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        final int cameraStringId = R.string.picker_category_camera;
        // Navigate to photos in Camera album
        onView(allOf(withText(cameraStringId), isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID))))
                .perform(click());
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));

        // Click back button
        onView(withContentDescription("Navigate up")).perform(click());

        // on clicking back button we are back to Album grid
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));
        onView(withId(R.id.profile_button)).check(matches(not(isDisplayed())));
    }

    @Test
    public void testAddButtonIsShownWithCorrectTextWhenItemsSelected() {
        launchValidActivity();
        final int bottomBarId = R.id.picker_bottom_bar;
        final int viewSelectedId = R.id.button_view_selected;
        final int addButtonId = R.id.button_add;

        onView(withId(bottomBarId)).check(matches(not(isDisplayed())));
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);

        onView(withId(bottomBarId)).check(matches(isDisplayed()));
        onView(withId(viewSelectedId)).check(matches(isDisplayed()));

        onView(withId(addButtonId)).check(matches(withText("Allow (1)")));
        onView(withId(addButtonId)).check(matches(isDisplayed()));


        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());
        onView(withId(addButtonId)).check(matches(withText("Allow (1)")));
    }

    @Test
    public void testNoCloudSettingsAndBanners() {
        launchValidActivity();

        OverflowMenuUtils.assertOverflowMenuNotShown();

        // Assert no banners shown
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID))
                .check(matches(not(atPositionOnItemViewType(
                        DEFAULT_BANNER_POSITION, ITEM_TYPE_BANNER))));
    }

    @Test
    public void testUserSelectCorrectHeaderTextIsShown() {
        launchValidActivity();
        onView(withText(R.string.picker_header_permissions)).check(matches(isDisplayed()));
    }

    /** Test helper to launch a valid test activity. */
    private void launchValidActivity() {
        mScenario =
                ActivityScenario.launchActivityForResult(
                        PhotoPickerBaseTest.getUserSelectImagesForAppIntent());
    }
}
