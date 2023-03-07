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
import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_SECTION;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.appcompat.widget.ViewUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;

/**
 * The ItemDecoration that allows to add layout offsets to specific item views from the adapter's
 * data set for the {@link RecyclerView} on Photos tab.
 */
public class PhotosTabItemDecoration extends RecyclerView.ItemDecoration {

    private final int mSpacing;

    public PhotosTabItemDecoration(Context context) {
        mSpacing = context.getResources().getDimensionPixelSize(R.dimen.picker_photo_item_spacing);
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

        // The date header and banners don't have spacing
        if (itemViewType == ITEM_TYPE_BANNER || itemViewType == ITEM_TYPE_SECTION) {
            outRect.set(0, 0, 0, 0);
            return;
        }

        final GridLayoutManager.LayoutParams lp =
                (GridLayoutManager.LayoutParams) view.getLayoutParams();
        final GridLayoutManager layoutManager = (GridLayoutManager) parent.getLayoutManager();
        final int column = lp.getSpanIndex();
        final int spanCount = layoutManager.getSpanCount();

        if (adapterPosition > column) {
            final int aboveItemPosition = adapterPosition - column - 1;
            final int aboveItemViewType = parent.getAdapter().getItemViewType(aboveItemPosition);
            // if the above item is not a date header, add the top spacing
            if (aboveItemViewType != ITEM_TYPE_SECTION) {
                outRect.top = mSpacing;
            }
        }

        // column * ((1f / spanCount) * spacing)
        final int start = column * mSpacing / spanCount;
        // spacing - (column + 1) * ((1f / spanCount) * spacing)
        final int end = mSpacing - (column + 1) * mSpacing / spanCount;

        if (ViewUtils.isLayoutRtl(parent)) {
            outRect.left = end;
            outRect.right = start;
        } else {
            outRect.left = start;
            outRect.right = end;
        }
    }
}
