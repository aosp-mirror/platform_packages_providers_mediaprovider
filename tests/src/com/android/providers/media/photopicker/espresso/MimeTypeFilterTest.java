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
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.hamcrest.Matchers.allOf;

import androidx.test.core.app.ActivityScenario;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;
import com.android.providers.media.library.RunOnlyOnPostsubmit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunOnlyOnPostsubmit
@RunWith(AndroidJUnit4ClassRunner.class)
public class MimeTypeFilterTest extends PhotoPickerBaseTest {

    private static final String IMAGE_MIME_TYPE = "image/*";
    private static final String VIDEO_MIME_TYPE = "video/*";
    public ActivityScenario<PhotoPickerTestActivity> mScenario;

    @Before
    public void launchActivity() {
        mScenario =
                ActivityScenario.launchActivityForResult(
                        PhotoPickerBaseTest.getSingleSelectMimeTypeFilterIntent(IMAGE_MIME_TYPE));
    }

    @After
    public void closeActivity() {
        mScenario.close();
    }

    @Test
    public void testPhotosTabOnlyImageItems() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Two image items and one recent date header
        final int expectedItemCount = 3;
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(
                new RecyclerViewItemCountAssertion(expectedItemCount));

        final int videoContainerId = R.id.video_container;
        // No Video item
        onView(allOf(withId(videoContainerId),
                withParent(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(doesNotExist());
    }

    @Test
    public void testAlbumsTabNoVideosAlbum() {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Go to Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        // Only two albums, Camera and Downloads
        final int expectedItemCount = 2;
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(
                new RecyclerViewItemCountAssertion(expectedItemCount));

        final int cameraStringId = R.string.picker_category_camera;
        // Camera album exists
        onView(allOf(withText(cameraStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));

        final int downloadsStringId = R.string.picker_category_downloads;
        // Downloads album exists
        onView(allOf(withText(downloadsStringId),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(matches(isDisplayed()));

        final int itemCountId = R.id.item_count;
        // No item count on album items if there is mime type filter
        onView(allOf(withId(itemCountId),
                withParent(withId(PICKER_TAB_RECYCLERVIEW_ID)))).check(doesNotExist());
    }

    @Test
    public void testPickerTabTitleText_forVariousMimeTypeFilters() {
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));

        mScenario = ActivityScenario.launchActivityForResult(
                PhotoPickerBaseTest.getSingleSelectMimeTypeFilterIntent(VIDEO_MIME_TYPE));
        onView(allOf(withText(PICKER_VIDEOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));

        mScenario = ActivityScenario.launchActivityForResult(
                PhotoPickerBaseTest.getSingleSelectionIntent());
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches(isSelected()));

    }
}