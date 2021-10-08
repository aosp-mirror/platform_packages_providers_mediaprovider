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

import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * A {@link org.hamcrest.Matcher} that checks the given View is assignable from
 * {@link RecyclerView}
 * <p>
 * Shamelessly borrowed from various codebase.
 */
class RecyclerViewMatcher {
    private final int mRecyclerViewId;

    RecyclerViewMatcher(int recyclerViewId) {
        mRecyclerViewId = recyclerViewId;
    }

    public static RecyclerViewMatcher withRecyclerView(int recyclerViewId) {
        return new RecyclerViewMatcher(recyclerViewId);
    }

    public Matcher<View> atPositionOnView(int position, int targetViewId) {
        return new TypeSafeMatcher<View>() {
            @Nullable
            private View mRecyclerViewChildView;

            @Override
            public void describeTo(Description description) {
                description.appendText("is assignable from class: " + RecyclerView.class
                        + ", at given position: " + position
                        + ", with given targetViewId: " + targetViewId);
            }

            @Override
            public boolean matchesSafely(View view) {
                if (mRecyclerViewChildView == null) {
                    RecyclerView recyclerView =
                            view.getRootView().findViewById(mRecyclerViewId);
                    if (recyclerView == null) {
                        // No RecyclerView with given Id, hence no match
                        return false;
                    }

                    RecyclerView.ViewHolder viewHolder =
                            recyclerView.findViewHolderForAdapterPosition(position);
                    if (viewHolder == null) {
                        // No viewHolder, hence no match
                        return false;
                    }

                    // Get itemView at given position from RecyclerView ViewHolder
                    mRecyclerViewChildView = viewHolder.itemView;
                }

                if (targetViewId == -1) {
                    return view == mRecyclerViewChildView;
                } else {
                    // Returns specific view in the given RecyclerView item
                    View targetView = mRecyclerViewChildView.findViewById(targetViewId);
                    return view == targetView;
                }
            }
        };
    }
}