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

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemSelected;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.clickItem;

import android.view.View;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.IdlingResource;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class MaxSelectionTest extends PhotoPickerBaseTest {
    private static final int MAX_SELECTION_COUNT = 2;

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule = new ActivityScenarioRule<>(
            PhotoPickerBaseTest.getMultiSelectionIntent(MAX_SELECTION_COUNT));

    @Test
    public void testMaxSelection() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Select first image item thumbnail and verify select icon is selected
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_1_POSITION, ICON_CHECK_ID);

        // Select second image item thumbnail and verify select icon is selected
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_CHECK_ID);

        // Click Video item thumbnail and verify select icon is not selected. Because we set the
        // max selection is 2.
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_THUMBNAIL_ID);
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_CHECK_ID);
        // Show pop up message
        onView(withText("Select up to 2 items")).check(matches(isDisplayed()));

        // Wait for the snackbar is dismissed
        registerSnackBarDetachedAndWaitForIdle();

        // Click View selected button
        onView(withId(VIEW_SELECTED_BUTTON_ID)).perform(click());
        onView(withId(PREVIEW_VIEW_PAGER_ID)).check(matches(isDisplayed()));

        // Click back button and verify we are back to photos tab
        onView(withContentDescription("Navigate up")).perform(click());
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // DeSelect second image item and verify select icon is not selected
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_THUMBNAIL_ID);
        assertItemNotSelected(PICKER_TAB_RECYCLERVIEW_ID, IMAGE_2_POSITION, ICON_CHECK_ID);

        // Click Video item thumbnail and verify select icon is selected.
        clickItem(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_THUMBNAIL_ID);
        assertItemSelected(PICKER_TAB_RECYCLERVIEW_ID, VIDEO_POSITION, ICON_CHECK_ID);
        // The pop up message is not shown
        onView(withText("Select up to 2 items")).check(doesNotExist());
    }

    private void registerSnackBarDetachedAndWaitForIdle() {
        registerViewDetachedIdlingResourceAndWaitForIdle(
                com.google.android.material.R.id.snackbar_text);
    }

    private void registerViewDetachedIdlingResourceAndWaitForIdle(int viewResId) {
        mRule.getScenario().onActivity(activity -> {
            final View view = activity.findViewById(viewResId);
            IdlingRegistry.getInstance().register(new ViewDetachedIdlingResource(view));
        });
        Espresso.onIdle();
    }

    private static class ViewDetachedIdlingResource implements IdlingResource {

        private boolean mIsDetached = false;
        private IdlingResource.ResourceCallback mCallback;

        ViewDetachedIdlingResource(View view) {
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {

                @Override
                public void onViewAttachedToWindow(View v) {
                    mIsDetached = false;
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    view.removeOnAttachStateChangeListener(this);
                    mIsDetached = true;
                    mCallback.onTransitionToIdle();
                }
            });
        }

        @Override
        public void registerIdleTransitionCallback(ResourceCallback resourceCallback) {
            mCallback = resourceCallback;
        }

        @Override
        public String getName() {
            return "ViewDetachedIdlingResource";
        }

        @Override
        public boolean isIdleNow() {
            return mIsDetached;
        }
    }
}
