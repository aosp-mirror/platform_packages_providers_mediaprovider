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
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.OverflowMenuUtils.assertOverflowMenuNotShown;
import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemDisplayed;

import static org.hamcrest.Matchers.allOf;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class AlbumsTabTest extends PhotoPickerBaseTest {

    // TODO(b/192304192): We need to use multi selection mode to go into full screen to check all
    // the categories. Remove this when we can change BottomSheet behavior from test.
    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule =
            new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Ignore("b/227478958 Odd failure to verify Downloads album")
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

        // First album is Camera
        assertItemContentInAlbumList(/* position */ 0, R.string.picker_category_videos);
        // Second album is Videos
        assertItemContentInAlbumList(/* position */ 1, R.string.picker_category_camera);
        // Third album is Downloads
        assertItemContentInAlbumList(/* position */ 2, R.string.picker_category_downloads);

        // Verify the overflow menu is not shown for PICK_IMAGES intent
        assertOverflowMenuNotShown();

        // TODO(b/200513628): Check the bitmap of the album covers
    }

    private void assertItemContentInAlbumList(int position, int albumNameResId) {
        // Verify the components are shown on the album item
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, R.id.album_name);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, R.id.item_count);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, position, R.id.icon_thumbnail);

        // Verify we have the album in the list
        onView(allOf(withText(albumNameResId), isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW_ID))))
                .check(matches(isDisplayed()));

        // Verify the position of the album name matches the correct order
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW_ID)
                .atPositionOnView(position, R.id.album_name))
                .check(matches(withText(albumNameResId)));

        // Verify the item count is correct
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW_ID)
                .atPositionOnView(position, R.id.item_count))
                .check(matches(withText("1 item")));
    }
}
