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

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
public class PhotosTabAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    public static final int ITEM_TYPE_DATE_HEADER = 0;
    private static final int ITEM_TYPE_PHOTO = 1;
    private static final int ITEM_TYPE_MESSAGE = 2;

    public static final int COLUMN_COUNT = 3;

    private List<Item> mItemList = new ArrayList<>();
    private ImageLoader mImageLoader;
    private View.OnClickListener mOnClickListener;
    private PickerViewModel mPickerViewModel;

    public PhotosTabAdapter(PickerViewModel pickerViewModel, ImageLoader imageLoader,
            View.OnClickListener listener) {
        mImageLoader = imageLoader;
        mPickerViewModel = pickerViewModel;
        mOnClickListener = listener;
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if (viewType == ITEM_TYPE_DATE_HEADER) {
            return new DateHeaderHolder(viewGroup.getContext(), viewGroup);
        }
        if (viewType == ITEM_TYPE_MESSAGE) {
            return new MessageHolder(viewGroup.getContext(), viewGroup,
                    mPickerViewModel.getMaxSelectionLimit());
        }
        return new PhotoGridHolder(viewGroup.getContext(), viewGroup, mImageLoader,
                mPickerViewModel.canSelectMultiple());
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder itemHolder, int position) {
        final Item item = getItem(position);
        itemHolder.itemView.setTag(item);

        if (getItemViewType(position) == ITEM_TYPE_PHOTO) {
            itemHolder.itemView.setOnClickListener(mOnClickListener);
            final boolean isItemSelected =
                    mPickerViewModel.getSelectedItems().getValue().containsKey(
                            item.getContentUri());
            itemHolder.itemView.setSelected(isItemSelected);
        }
        itemHolder.bind();
    }

    @Override
    public int getItemCount() {
        return mItemList.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (getItem(position).isMessage()) {
            return ITEM_TYPE_MESSAGE;
        }
        if (getItem(position).isDate()) {
            return ITEM_TYPE_DATE_HEADER;
        }
        return ITEM_TYPE_PHOTO;
    }

    public Item getItem(int position) {
        return mItemList.get(position);
    }

    public void updateItemList(List<Item> itemList) {
        mItemList = itemList;
        notifyDataSetChanged();
    }

    public GridLayoutManager.SpanSizeLookup createSpanSizeLookup() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // Make layout whitespace span the grid. This has the effect of breaking
                // grid rows whenever layout whitespace is encountered.
                if (getItemViewType(position) == ITEM_TYPE_DATE_HEADER ||
                        getItemViewType(position) == ITEM_TYPE_MESSAGE) {
                    return COLUMN_COUNT;
                } else {
                    return 1;
                }
            }
        };
    }
}
