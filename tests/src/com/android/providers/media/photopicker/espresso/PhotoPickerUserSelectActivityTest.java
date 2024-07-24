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

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.atPositionOnItemViewType;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;
import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_BANNER;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static junit.framework.Assert.fail;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;
import com.android.providers.media.library.RunOnlyOnPostsubmit;
import com.android.providers.media.photopicker.DataLoaderThread;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunOnlyOnPostsubmit
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
        // User select mode does not show profile button
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
    public void testAddButtonIsShowsAllowNone() {
        launchValidActivityWithManagedSelectionEnabled();
        final int bottomBarId = R.id.picker_bottom_bar;
        final int viewSelectedId = R.id.button_view_selected;
        final int addButtonId = R.id.button_add;

        // Default view, no item selected.
        onView(withId(bottomBarId)).check(matches(isDisplayed()));
        onView(withId(viewSelectedId)).check(matches(not(isDisplayed())));
        onView(withId(addButtonId)).check(matches(isDisplayed()));
        // verify that 'Allow none' is displayed in this case.
        onView(withId(addButtonId)).check(
                matches(withText(R.string.picker_add_button_allow_none_option)));

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
    public void testPreview_deselectAll_showAllowNone() throws Exception {
        launchValidActivityWithManagedSelectionEnabled();
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select first and second image
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());
        try (ViewPager2IdlingResource idlingResource =
                     ViewPager2IdlingResource.register(mScenario, PREVIEW_VIEW_PAGER_ID)) {
            final int previewAddButtonId = R.id.preview_add_button;
            final int previewSelectButtonId = R.id.preview_selected_check_button;
            final String selectedString =
                    getTargetContext().getResources().getString(R.string.selected);
            // Verify that, initially, we show "selected" check button
            onView(withId(previewSelectButtonId)).check(matches(isSelected()));
            onView(withId(previewSelectButtonId)).check(matches(withText(selectedString)));
            // Verify that the text in Add button matches "Allow (1)"
            onView(withId(previewAddButtonId))
                    .check(matches(withText("Allow (1)")));

            // Deselect item in preview
            onView(withId(previewSelectButtonId)).perform(click());
            onView(withId(previewSelectButtonId)).check(matches(isNotSelected()));
            onView(withId(previewSelectButtonId)).check(matches(withText(R.string.deselected)));
            // Verify that the text in Add button now changes to "Allow none"
            onView(withId(previewAddButtonId))
                    .check(matches(withText("Allow none")));
            // Verify that we have 0 items in selected items
            mScenario.onActivity(activity -> {
                Selection selection =
                        new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
                assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(0);
            });

            // Select the item again
            onView(withId(previewSelectButtonId)).perform(click());
            onView(withId(previewSelectButtonId)).check(matches(isSelected()));
            onView(withId(previewSelectButtonId)).check(matches(withText(selectedString)));
            // Verify that the text in Add button now changes back to "Allow (1)"
            onView(withId(previewAddButtonId))
                    .check(matches(withText("Allow (1)")));
            // Verify that we have 1 item in selected items
            mScenario.onActivity(activity -> {
                Selection selection =
                        new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
                assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(1);
            });
        }
    }

    @Test
    public void testPreview_showsOnlyAlreadyLoadedGrantItems() throws Exception {
        launchValidActivityWithManagedSelectionEnabled();
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        final Uri uri = MediaStore.scanFile(getIsolatedContext().getContentResolver(),
                IMAGE_1_FILE);
        MediaStore.waitForIdle(getIsolatedContext().getContentResolver());
        mScenario.onActivity(activity -> {
            // Add an item id to the pre-granted set, so that when preview fragment gets opened up
            // there is something to load as a remaining item.
            Selection selection =
                    new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
            selection.setTotalNumberOfPreGrantedItems(1);
            selection.setPreGrantedItemSet(Set.of(String.valueOf(ContentUris.parseId(uri))));

            // Verify that we don't have anything to preview
            selection.prepareSelectedItemsForPreviewAll();
            assertWithMessage("Expected preview-able item list to be empty")
                    .that(selection.getSelectedItemsForPreview()).isEmpty();
        });

        // Block the DataLoader thread by posting a conditional wait. This will block fetching of
        // pregranted items in preview
        final CountDownLatch latch = new CountDownLatch(1);
        DataLoaderThread.waitForIdle();
        DataLoaderThread.getHandler().postDelayed(() -> {
            // Wait for 5 seconds if we don't receive a countdown
            try {
                assertWithMessage("Expected the test to send countdown before 5s")
                        .that(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                fail("Unexpected excepetion : " + e.getMessage());
            }
        }, DataLoaderThread.TOKEN, 0);

        // Navigate to preview
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());

        // Verify that UI shows no selected / deselected button
        try (ViewPager2IdlingResource idlingResource =
                     ViewPager2IdlingResource.register(mScenario, PREVIEW_VIEW_PAGER_ID)) {
            final int previewAddButtonId = R.id.preview_add_button;
            final int previewSelectButtonId = R.id.preview_selected_check_button;
            // Verify that, initially, we show "selected" check button
            onView(withId(previewSelectButtonId)).check(matches(not(isDisplayed())));
            onView(withId(previewAddButtonId)).check(matches(isDisplayed()));
            // Verify that the text in Add button matches "Allow (1)"
            onView(withId(previewAddButtonId)).check(matches(withText("Allow (1)")));
        }

        // Free DataLoaderThread so that it can load pregranted items
        latch.countDown();
        DataLoaderThread.waitForIdle();

        // Verify that UI now shows selected button
        try (ViewPager2IdlingResource idlingResource =
                     ViewPager2IdlingResource.register(mScenario, PREVIEW_VIEW_PAGER_ID)) {
            final int previewAddButtonId = R.id.preview_add_button;
            final int previewSelectButtonId = R.id.preview_selected_check_button;
            final String selectedString =
                    getTargetContext().getResources().getString(R.string.selected);
            // Verify that, initially, we show "selected" check button
            onView(withId(previewSelectButtonId)).check(matches(isSelected()));
            onView(withId(previewSelectButtonId)).check(matches(withText(selectedString)));
            // Verify that the text in Add button matches "Allow (1)"
            onView(withId(previewAddButtonId))
                    .check(matches(withText("Allow (1)")));
            mScenario.onActivity(activity -> {
                Selection selection =
                        new ViewModelProvider(activity).get(PickerViewModel.class).getSelection();
                assertThat(selection.getSelectedItemCount().getValue()).isEqualTo(1);
            });
        }
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

    /** Test helper to launch a valid test activity. */
    private void launchValidActivityWithManagedSelectionEnabled() {
        mScenario = ActivityScenario.launchActivityForResult(
                PhotoPickerBaseTest.getPickerChoiceManagedSelectionIntent());
    }
}
