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

import static com.google.common.truth.Truth.assertThat;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.ViewAssertion;

/**
 * A {@link ViewAssertion} that asserts for item count of {@link RecyclerView}
 * Shamelessly borrowed from various codebase.
 */
class RecyclerViewItemCountAssertion implements ViewAssertion {
    private final int mExpectedCount;

    public RecyclerViewItemCountAssertion(int expectedCount) {
        mExpectedCount = expectedCount;
    }

    @Override
    public void check(View view, NoMatchingViewException noMatchingViewException) {
        if (noMatchingViewException != null) {
            throw noMatchingViewException;
        }

        RecyclerView.Adapter adapter = ((RecyclerView) view).getAdapter();
        assertThat(adapter).isNotNull();
        assertThat(adapter.getItemCount()).isEqualTo(mExpectedCount);
    }
}