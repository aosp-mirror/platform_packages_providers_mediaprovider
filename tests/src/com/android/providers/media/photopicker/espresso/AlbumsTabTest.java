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
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemDisplayed;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotDisplayed;

import static org.hamcrest.Matchers.allOf;

import android.os.Build;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.SdkSuppress;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;
import com.android.providers.media.library.RunOnlyOnPostsubmit;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger.PhotoPickerEvent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunOnlyOnPostsubmit
@RunWith(AndroidJUnit4ClassRunner.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public class AlbumsTabTest extends PhotoPickerBaseTest {

    // TODO(b/192304192): We need to use multi selection mode to go into full screen to check all
    // the categories. Remove this when we can change BottomSheet behavior from test.
    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule =
            new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testAlbumGrid() {
        // Goto Albums page
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .perform(click());

        // Verify that toolbar has correct components
        onView(withId(TAB_LAYOUT_ID)).check(matches((isDisplayed())));
        // Photos tab
        onView(allOf(withText(PICKER_PHOTOS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches((isDisplayed())));
        // Albums tab
        onView(allOf(withText(PICKER_ALBUMS_STRING_ID), isDescendantOfA(withId(TAB_LAYOUT_ID))))
                .check(matches((isDisplayed())));
        // Cancel button
        final String cancelString =
                InstrumentationRegistry.getTargetContext().getResources().getString(
                        android.R.string.cancel);
        onView(withContentDescription(cancelString)).check(matches((isDisplayed())));

        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        final int expectedAlbumCount = 3;
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID))
                .check(new RecyclerViewItemCountAssertion(expectedAlbumCount));

        // Verify albums tab click and albums loaded UI events
        UiEventLoggerTestUtils.verifyLogWithInstanceId(
                mRule, PhotoPickerEvent.PHOTO_PICKER_TAB_ALBUMS_OPEN);
        UiEventLoggerTestUtils.verifyLogWithInstanceIdAndPosition(
                mRule, PhotoPickerEvent.PHOTO_PICKER_UI_LOADED_ALBUMS, expectedAlbumCount);

        // First album is Camera
        assertItemContentInAlbumList(/* position */ 0, R.string.picker_category_camera);
        // Second album is Videos
        assertItemContentInAlbumList(/* position */ 1, R.string.picker_category_videos);
        // Third album is Downloads
        assertItemContentInAlbumList(/* position */ 2, R.string.picker_category_downloads);

        // Verify the overflow menu is not shown for PICK_IMAGES intent
        assertOverflowMenuNotShown();

        // TODO(b/200513628): Check the bitmap of the album covers
    }

    private void assertItemContentInAlbumList(int position, int albumNameResId) {
        // Verify the components are shown on the album item
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, R.id.album_name);
        // As per the current requirements , hiding album's item count.
        // In case if in future we need to show album's item count , we also have to assert its
        // correct count with the visibility of album's item count block.
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, R.id.item_count);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, R.id.icon_thumbnail);

        // Verify we have the album in the list
        onView(allOf(withText(albumNameResId), isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID))))
                .check(matches(isDisplayed()));

        // Verify the position of the album name matches the correct order AND click the album
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW_ID)
                .atPositionOnView(position, R.id.album_name))
                .check(matches(withText(albumNameResId)))
                .perform(click());

        // Verify album opened
        onView(allOf(withText(albumNameResId), withParent(withId(R.id.toolbar))))
                .check(matches(isDisplayed()));

        // Verify album click UI event
        UiEventLoggerTestUtils.verifyLogWithInstanceId(mRule, getUiEventForAlbumId(albumNameResId));

        // Go back to the Albums tab
        pressBack();
    }

    private PhotoPickerEvent getUiEventForAlbumId(int albumNameResId) {
        switch (albumNameResId) {
            case R.string.picker_category_videos:
                return PhotoPickerEvent.PHOTO_PICKER_ALBUM_VIDEOS_OPEN;
            case R.string.picker_category_camera:
                return PhotoPickerEvent.PHOTO_PICKER_ALBUM_CAMERA_OPEN;
            case R.string.picker_category_downloads:
                return PhotoPickerEvent.PHOTO_PICKER_ALBUM_DOWNLOADS_OPEN;
            default:
                throw new IllegalArgumentException("Unexpected albumNameResId: " + albumNameResId);
        }
    }
}
