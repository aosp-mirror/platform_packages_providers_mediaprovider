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
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotSelected;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;

import static com.android.providers.media.photopicker.espresso.RecyclerViewMatcher.withRecyclerView;

import static org.hamcrest.Matchers.not;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.matcher.BoundedMatcher;

import org.hamcrest.Description;
import org.hamcrest.Matcher;

class RecyclerViewTestUtils {
    public static void assertItemDisplayed(int recyclerViewId, int position, int targetViewId) {
        onView(withRecyclerView(recyclerViewId)
                .atPositionOnView(position, targetViewId))
                .check(matches(isDisplayed()));
    }

    public static void assertItemNotDisplayed(int recyclerViewId, int position, int targetViewId) {
        onView(withRecyclerView(recyclerViewId)
                .atPositionOnView(position, targetViewId))
                .check(matches(not(isDisplayed())));
    }

    public static void assertItemSelected(int recyclerViewId, int position, int targetViewId) {
        onView(withRecyclerView(recyclerViewId)
                .atPositionOnView(position, targetViewId))
                .check(matches(isSelected()));
    }

    public static void assertItemNotSelected(int recyclerViewId, int position, int targetViewId) {
        onView(withRecyclerView(recyclerViewId)
                .atPositionOnView(position, targetViewId))
                .check(matches(isNotSelected()));
    }

    public static void clickItem(int recyclerViewId, int position, int targetViewId) {
        onView(withRecyclerView(recyclerViewId)
                .atPositionOnView(position, targetViewId))
                .perform(click());
    }

    public static void longClickItem(int recyclerViewId, int position, int targetViewId) {
        onView(withRecyclerView(recyclerViewId)
                .atPositionOnView(position, targetViewId))
                .perform(longClick());
    }

    static Matcher<View> atPositionOnItemViewType(int position, int itemViewType) {
        return new BoundedMatcher<View, RecyclerView>(RecyclerView.class) {
            @Override
            public void describeTo(Description description) {
                description.appendText("has item at position " + position + " with itemViewType "
                        + itemViewType);
            }

            @Override
            protected boolean matchesSafely(RecyclerView view) {
                RecyclerView.ViewHolder viewHolder =
                        view.findViewHolderForAdapterPosition(position);
                if (viewHolder == null) {
                    // has no item at given position
                    return false;
                }
                return viewHolder.getItemViewType() == itemViewType;
            }
        };
    }
}
