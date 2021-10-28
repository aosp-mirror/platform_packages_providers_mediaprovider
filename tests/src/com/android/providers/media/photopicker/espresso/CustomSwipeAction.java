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
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralLocation;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;

import com.android.providers.media.R;

public class CustomSwipeAction {
    private static final int PREVIEW_VIEW_PAGER_ID = R.id.preview_viewPager;

    /**
     * A custom swipeLeft method to avoid system gestures taking over ViewActions#swipeLeft
     */
    private static ViewAction customSwipeLeft() {
        return new GeneralSwipeAction(Swipe.FAST, GeneralLocation.CENTER,
                GeneralLocation.CENTER_LEFT, Press.FINGER);
    }

    public static void swipeLeftAndWait() {
        onView(withId(PREVIEW_VIEW_PAGER_ID)).perform(customSwipeLeft());
        Espresso.onIdle();
    }

    /**
     * A custom swipeRight method to avoid system gestures taking over ViewActions#swipeRight
     */
    private static ViewAction customSwipeRight() {
        return new GeneralSwipeAction(Swipe.FAST, GeneralLocation.CENTER,
                GeneralLocation.CENTER_RIGHT, Press.FINGER);
    }

    public static void swipeRightAndWait() {
        // Use customSwipeRight to avoid system gestures taking over ViewActions#swipeRight
        onView(withId(PREVIEW_VIEW_PAGER_ID)).perform(customSwipeRight());
        Espresso.onIdle();
    }
}
