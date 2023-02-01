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
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;

import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
public class PhotosTabAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    public static final int ITEM_TYPE_DATE_HEADER = 0;
    private static final int ITEM_TYPE_PHOTO = 1;

    public static final int COLUMN_COUNT = 3;

    private List<Item> mItemList = new ArrayList<>();
    private final ImageLoader mImageLoader;
    private final View.OnClickListener mOnClickListener;
    private final View.OnLongClickListener mOnLongClickListener;
    private final Selection mSelection;

    public PhotosTabAdapter(@NonNull Selection selection, @NonNull ImageLoader imageLoader,
            @NonNull View.OnClickListener onClickListener,
            @NonNull View.OnLongClickListener onLongClickListener) {
        mImageLoader = imageLoader;
        mSelection = selection;
        mOnClickListener = onClickListener;
        mOnLongClickListener = onLongClickListener;
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if (viewType == ITEM_TYPE_DATE_HEADER) {
            return new DateHeaderHolder(viewGroup.getContext(), viewGroup);
        }
        return new PhotoGridHolder(viewGroup.getContext(), viewGroup, mImageLoader,
                mSelection.canSelectMultiple());
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder itemHolder, int position) {
        final Item item = getItem(position);
        itemHolder.itemView.setTag(item);

        if (getItemViewType(position) == ITEM_TYPE_PHOTO) {
            itemHolder.itemView.setOnClickListener(mOnClickListener);
            itemHolder.itemView.setOnLongClickListener(mOnLongClickListener);

            final Context context = itemHolder.itemView.getContext();
            itemHolder.itemView.setContentDescription(item.getContentDescription(context));

            if (mSelection.canSelectMultiple()) {
                final boolean isSelected = mSelection.isItemSelected(item);
                itemHolder.itemView.setSelected(isSelected);

                // There is an issue b/223695510 about not selected in Accessibility mode. It only
                // says selected state, but it doesn't say not selected state. Add the not selected
                // only to avoid that it says selected twice.
                itemHolder.itemView.setStateDescription(
                        isSelected ? null : context.getString(R.string.not_selected));
            }
        }
        itemHolder.bind();
    }

    @Override
    public int getItemCount() {
        return mItemList.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (getItem(position).isDate()) {
            return ITEM_TYPE_DATE_HEADER;
        }
        return ITEM_TYPE_PHOTO;
    }

    @NonNull
    public Item getItem(int position) {
        return mItemList.get(position);
    }

    public void updateItemList(@NonNull List<Item> itemList) {
        mItemList = itemList;
        notifyDataSetChanged();
    }

    @NonNull
    public GridLayoutManager.SpanSizeLookup createSpanSizeLookup(
            @NonNull GridLayoutManager layoutManager) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final int itemViewType = getItemViewType(position);
                // For the item view type is ITEM_TYPE_DATE_HEADER, it is full
                // span, return the span count of the layoutManager.
                if (itemViewType == ITEM_TYPE_DATE_HEADER ) {
                    return layoutManager.getSpanCount();
                } else {
                    return 1;
                }
            }
        };
    }
}
