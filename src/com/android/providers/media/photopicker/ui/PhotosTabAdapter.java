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
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
public class PhotosTabAdapter extends RecyclerView.Adapter<BaseItemHolder> {

    private static final int ITEM_TYPE_PHOTO = 1;

    public static final int COLUMN_COUNT = 3;

    private List<Item> mItemList = new ArrayList<>();
    private IconHelper mIconHelper;
    private View.OnClickListener mOnClickListener;

    public PhotosTabAdapter(IconHelper iconHelper, View.OnClickListener listener) {
        mIconHelper = iconHelper;
        mOnClickListener = listener;
    }

    @NonNull
    @Override
    public BaseItemHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        return new PhotoGridHolder(viewGroup.getContext(), viewGroup, mIconHelper);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseItemHolder photoHolder, int position) {
        photoHolder.itemView.setTag(getItem(position));
        photoHolder.itemView.setOnClickListener(mOnClickListener);
        photoHolder.bind();
    }

    @Override
    public int getItemCount() {
        return mItemList.size();
    }

    @Override
    public int getItemViewType(int position) {
        return ITEM_TYPE_PHOTO;
    }

    public Item getItem(int position) {
        return mItemList.get(position);
    }

    public void updateItemList(List<Item> itemList) {
        mItemList = itemList;
        notifyDataSetChanged();
    }
}
