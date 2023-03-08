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

import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_BANNER;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.appcompat.widget.ViewUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;

/**
 * The ItemDecoration that allows adding layout offsets to specific item views from the adapter's
 * data set for the {@link RecyclerView} on Albums tab.
 */
public class AlbumsTabItemDecoration extends RecyclerView.ItemDecoration {

    private final int mSpacing;
    private final int mTopSpacing;

    public AlbumsTabItemDecoration(Context context) {
        mSpacing = context.getResources().getDimensionPixelSize(R.dimen.picker_album_item_spacing);
        mTopSpacing = context.getResources().getDimensionPixelSize(
                R.dimen.picker_album_item_top_spacing);
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
            RecyclerView.State state) {
        final int adapterPosition = parent.getChildAdapterPosition(view);
        if (adapterPosition == RecyclerView.NO_POSITION) {
            outRect.setEmpty();
            return;
        }

        final int itemViewType = parent.getAdapter().getItemViewType(adapterPosition);

        // The banners don't have spacing
        if (itemViewType == ITEM_TYPE_BANNER) {
            outRect.setEmpty();
            return;
        }

        final GridLayoutManager.LayoutParams lp =
                (GridLayoutManager.LayoutParams) view.getLayoutParams();
        final GridLayoutManager layoutManager = (GridLayoutManager) parent.getLayoutManager();
        final int column = lp.getSpanIndex();
        final int spanCount = layoutManager.getSpanCount();

        // the top gap of the album items on the first row is mSpacing
        /**
         * {@link adapterPosition} for album items =
         *         {@link TabAdapter#getBannerCount()} + {@link column}
         *         + ((album item row index) * {@link spanCount}).
         *
         * Since {@link TabAdapter#getBannerCount()} only returns a 1 or 0,
         * and {@link spanCount} > 1,
         * for the first row of album items, {@link adapterPosition} <= {@link column} + 1,
         * whereas for the further rows, {@link adapterPosition} > {@link column} + 1.
         */
        if (adapterPosition <= column + 1) {
            outRect.top = mSpacing;
        } else {
            outRect.top = mTopSpacing;
        }

        // spacing - column * ((1f / spanCount) * spacing)
        final int start = mSpacing - column * mSpacing / spanCount;
        // (column + 1) * ((1f / spanCount) * spacing)
        final int end = (column + 1) * mSpacing / spanCount;
        if (ViewUtils.isLayoutRtl(parent)) {
            outRect.left = end;
            outRect.right = start;
        } else {
            outRect.left = start;
            outRect.right = end;
        }
    }
}
