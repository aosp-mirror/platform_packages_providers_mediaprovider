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
import static org.hamcrest.Matchers.not;

import androidx.test.core.app.ActivityScenario;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import android.provider.MediaStore;

import com.android.providers.media.R;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4ClassRunner.class)
public class NoItemsTest extends PhotoPickerBaseTest {

    @BeforeClass
    public static void setupClass() throws Exception {
        PhotoPickerBaseTest.setupClass();
        deleteFiles(/* invalidateMediaStore */ true);
        MediaStore.waitForIdle(getIsolatedContext().getContentResolver());
    }

    /**
     * Simple test to check we are able to launch PhotoPickerActivity with no items
     */
    @Test
    public void testNoItems_Simple() {
        try (ActivityScenario<PhotoPickerTestActivity> scenario = ActivityScenario.launch(
                PhotoPickerBaseTest.getSingleSelectionIntent())) {
            final int pickerTabRecyclerViewId = R.id.picker_tab_recyclerview;

            onView(allOf(withId(pickerTabRecyclerViewId))).check(matches(not(isDisplayed())));
            onView(withId(android.R.id.empty)).check(matches(isDisplayed()));
            onView(withText(R.string.picker_photos_empty_message)).check(matches(isDisplayed()));

            // Goto Albums page
            onView(allOf(withText(R.string.picker_albums),
                    isDescendantOfA(withId(R.id.tab_layout)))).perform(click());

            onView(allOf(withId(pickerTabRecyclerViewId))).check(matches(not(isDisplayed())));
            onView(withId(android.R.id.empty)).check(matches(isDisplayed()));
            onView(withText(R.string.picker_albums_empty_message)).check(matches(isDisplayed()));
        }
    }
}
