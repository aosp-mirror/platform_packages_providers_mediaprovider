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

package com.android.providers.media.photopicker;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.test.InstrumentationRegistry;

import com.android.providers.media.photopicker.ui.AutoFitRecyclerView;

import org.junit.Test;

public class AutoFitRecyclerViewTest {

    @Test
    public void testMeasure_spanCountIsRounded() {
        final int defaultSpanCount = 2;
        final int size = 520;
        final int columnWidth = 200;
        final int measureSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
        final Context context = InstrumentationRegistry.getTargetContext();
        AutoFitRecyclerView recyclerView = new AutoFitRecyclerView(context);
        final GridLayoutManager gridLayoutManager = new GridLayoutManager(context,
                defaultSpanCount);

        assertThat(gridLayoutManager.getSpanCount()).isEqualTo(defaultSpanCount);

        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setColumnWidth(columnWidth);

        recyclerView.onMeasure(measureSpec, measureSpec);

        // The calculated count is 2.6. The result of Math.round should be 3
        assertThat(gridLayoutManager.getSpanCount()).isEqualTo(3);
    }

    @Test
    public void testSetMinimumSpanCount_resultSmallerThanMinSpanCount() {
        final int defaultSpanCount = 1;
        final int minSpanCount = 3;
        final int size = 1200;
        final int columnWidth = size;
        final int measureSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
        final Context context = InstrumentationRegistry.getTargetContext();
        AutoFitRecyclerView recyclerView = new AutoFitRecyclerView(context);
        final GridLayoutManager gridLayoutManager = new GridLayoutManager(context,
                defaultSpanCount);

        assertThat(gridLayoutManager.getSpanCount()).isEqualTo(defaultSpanCount);

        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setMinimumSpanCount(minSpanCount);
        recyclerView.setColumnWidth(columnWidth);

        recyclerView.onMeasure(measureSpec, measureSpec);

        // The column width equals the measured width, the calculated count is 1. It is smaller than
        // minSpanCount, we expected the span count equals minSpanCount.
        assertThat(gridLayoutManager.getSpanCount()).isEqualTo(minSpanCount);
    }

    @Test
    public void testSetMinimumSpanCount_resultLargerThanMinSpanCount() {
        final int defaultSpanCount = 1;
        final int minSpanCount = 3;
        final int size = 1200;
        final int columnWidth = 300;
        final int expectedSpanCount = size / columnWidth;
        final int measureSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
        final Context context = InstrumentationRegistry.getTargetContext();
        AutoFitRecyclerView recyclerView = new AutoFitRecyclerView(context);
        final GridLayoutManager gridLayoutManager = new GridLayoutManager(context,
                defaultSpanCount);

        assertThat(gridLayoutManager.getSpanCount()).isEqualTo(defaultSpanCount);

        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setMinimumSpanCount(minSpanCount);
        recyclerView.setColumnWidth(columnWidth);

        recyclerView.onMeasure(measureSpec, measureSpec);

        // The calculated count is 4. It is larger than minSpanCount. Verify the span count equals
        // expectedSpanCount.
        assertThat(gridLayoutManager.getSpanCount()).isEqualTo(expectedSpanCount);
    }
}
