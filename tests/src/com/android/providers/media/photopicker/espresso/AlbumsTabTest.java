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
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;

import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemDisplayed;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import com.android.providers.media.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class AlbumsTabTest extends PhotoPickerBaseTest {
    private static final int PICKER_TAB_RECYCLERVIEW = R.id.picker_tab_recyclerview;

    // TODO(b/192304192): We need to use multi selection mode to go into full screen to check all
    // the categories. Remove this when we can change BottomSheet behavior from test.
    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule =
            new ActivityScenarioRule<>(PhotoPickerBaseTest.getMultiSelectionIntent());

    @Test
    public void testAlbumGrid() {
        // Goto Albums page
        onView(allOf(withText(R.string.picker_albums), withParent(withId(R.id.chip_container))))
                .perform(click());

        onView(withId(PICKER_TAB_RECYCLERVIEW)).check(matches(isDisplayed()));

        final int numOfItems = 3;
        onView(withId(PICKER_TAB_RECYCLERVIEW))
                .check(new RecyclerViewItemCountAssertion(numOfItems));

        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW, /* position */ 0, R.id.album_name);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW, /* position */ 0, R.id.item_count);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW, /* position */ 0, R.id.icon_thumbnail);

        // Verify we have all three categories listed
        onView(allOf(withText(R.string.picker_category_camera),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW)))).check(matches(isDisplayed()));
        onView(allOf(withText(R.string.picker_category_videos),
                isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW)))).check(matches(isDisplayed()));
        onView(allOf(withText(R.string.picker_category_downloads),
               isDescendantOfA(withId(PICKER_TAB_RECYCLERVIEW)))).check(matches(isDisplayed()));

        // Verify the position of the album names
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW)
                .atPositionOnView(/* position */ 0, R.id.album_name))
                .check(matches(withText(R.string.picker_category_camera)));
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW)
                .atPositionOnView(/* position */ 1, R.id.album_name))
                .check(matches(withText(R.string.picker_category_videos)));
        onView(withRecyclerView(PICKER_TAB_RECYCLERVIEW)
                .atPositionOnView(/* position */ 2, R.id.album_name))
                .check(matches(withText(R.string.picker_category_downloads)));

        // TODO(b/200513628): Check the bitmap of the album covers
    }
}