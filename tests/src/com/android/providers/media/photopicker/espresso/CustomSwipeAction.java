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

import android.view.View;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralLocation;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.matcher.ViewMatchers;

import com.android.providers.media.R;

import org.hamcrest.Matcher;

public class CustomSwipeAction {

    /**
     * A custom swipeLeft method to avoid system gestures taking over ViewActions#swipeLeft
     */
    private static ViewAction customSwipeLeft() {
        return new GeneralSwipeAction(Swipe.FAST, GeneralLocation.CENTER,
                GeneralLocation.CENTER_LEFT, Press.FINGER);
    }

    public static void swipeLeftAndWait(int viewId) {
        onView(withId(viewId)).perform(customSwipeLeft());
        Espresso.onIdle();
    }

    /**
     * A custom swipeRight method to avoid system gestures taking over ViewActions#swipeRight
     */
    private static ViewAction customSwipeRight() {
        return new GeneralSwipeAction(Swipe.FAST, GeneralLocation.CENTER,
                GeneralLocation.CENTER_RIGHT, Press.FINGER);
    }

    public static void swipeRightAndWait(int viewId) {
        // Use customSwipeRight to avoid system gestures taking over ViewActions#swipeRight
        onView(withId(viewId)).perform(customSwipeRight());
        Espresso.onIdle();
    }

    /**
     * A custom swipeDown method to avoid 90% visibility criteria on a view
     */
    public static ViewAction customSwipeDownPartialScreen() {
        return withCustomConstraints(ViewActions.swipeDown(),
                ViewMatchers.isDisplayingAtLeast(/* areaPercentage */ 60));
    }

    private static ViewAction withCustomConstraints(ViewAction action, Matcher<View> constraints) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return constraints;
            }

            @Override
            public String getDescription() {
                return action.getDescription();
            }

            @Override
            public void perform(UiController uiController, View view) {
                action.perform(uiController, view);
            }
        };
    }
}
