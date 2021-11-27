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
import android.view.View;

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
    private View mEmptyView;
    private AdapterDataObserver mAdapterDataObserver = new AdapterDataObserver() {
        @Override
        public void onChanged() {
            super.onChanged();
            checkIsEmpty();
        }

        /**
         * If the user triggers {@link RecyclerView.Adapter#notifyItemInserted(int)}, this method
         * will be triggered. We also need to check whether the dataset is empty or not to decide
         * the visibility of the empty view.
         */
        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            super.onItemRangeInserted(positionStart, itemCount);
            checkIsEmpty();
        }

        /**
         * If the user triggers {@link RecyclerView.Adapter#notifyItemRemoved(int)}, this method
         * will be triggered. We also need to check whether the dataset is empty or not to decide
         * the visibility of the empty view.
         */
        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            super.onItemRangeRemoved(positionStart, itemCount);
            checkIsEmpty();
        }

        private void checkIsEmpty() {
            if (mEmptyView == null) {
                return;
            }

            if (getAdapter().getItemCount() == 0) {
                mEmptyView.setVisibility(VISIBLE);
                setVisibility(GONE);
            } else {
                mEmptyView.setVisibility(GONE);
                setVisibility(VISIBLE);
            }
        }
    };

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
            final int spanCount = Math.max(mMinimumSpanCount, getMeasuredWidth() / mColumnWidth);
            ((GridLayoutManager) getLayoutManager()).setSpanCount(spanCount);
        }
    }

    @Override
    public void setLayoutManager(@Nullable RecyclerView.LayoutManager layoutManager) {
        super.setLayoutManager(layoutManager);
        if (layoutManager instanceof GridLayoutManager) {
            mIsGridLayout = true;
        }
    }

    @Override
    public void setAdapter(@Nullable RecyclerView.Adapter adapter) {
        super.setAdapter(adapter);
        if (adapter != null) {
            adapter.registerAdapterDataObserver(mAdapterDataObserver);
        }
        mAdapterDataObserver.onChanged();
    }

    /**
     * Set the empty view. If the empty view is not null, when the item count is zero, it is shown.
     */
    public void setEmptyView(@Nullable View emptyView) {
        mEmptyView = emptyView;
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
