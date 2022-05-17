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

package com.android.providers.media.photopicker.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The AutoFitRecyclerView auto fits the column width to decide the span count
 */
public class AutoFitRecyclerView extends RecyclerView {

    private int mColumnWidth = -1;
    private int mMinimumSpanCount = 2;
    private boolean mIsGridLayout;

    public AutoFitRecyclerView(Context context) {
        super(context);
    }

    public AutoFitRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AutoFitRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (mIsGridLayout && mColumnWidth > 0) {
            final int spanCount = Math.max(mMinimumSpanCount,
                    Math.round((float) getMeasuredWidth() / mColumnWidth));
            ((GridLayoutManager) getLayoutManager()).setSpanCount(spanCount);
        }
    }

    @Override
    public void setLayoutManager(@Nullable RecyclerView.LayoutManager layoutManager) {
        super.setLayoutManager(layoutManager);
        mIsGridLayout = (layoutManager instanceof GridLayoutManager);
    }

    public void setColumnWidth(int columnWidth) {
        mColumnWidth = columnWidth;
    }

    /**
     * Set the minimum span count for the recyclerView.
     * @param minimumSpanCount The default value is 2.
     */
    public void setMinimumSpanCount(int minimumSpanCount) {
        mMinimumSpanCount = minimumSpanCount;
    }
}
