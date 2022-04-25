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
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.espresso.Espresso;

import com.android.providers.media.R;

public class OverflowMenuUtils {

    public static void assertBrowseButtonInOverflowMenu() {
        // Opens the Overflow menu
        openActionBarOverflowOrOptionsMenu(PhotoPickerBaseTest.getIsolatedContext());
        // Checks that "Browse..." is displayed
        onView(withText(R.string.picker_browse)).check(matches(isDisplayed()));
        // Closes the overflow menu so that other tests are not affected
        Espresso.pressBack();
    }
}
