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

import com.android.providers.media.photopicker.data.model.Category;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
public class AlbumsTabAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    private static final int ITEM_TYPE_CATEGORY = 1;

    public static final int COLUMN_COUNT = 2;

    private final ImageLoader mImageLoader;
    private final View.OnClickListener mOnClickListener;
    private final boolean mHasMimeTypeFilter;

    private List<Category> mCategoryList = new ArrayList<>();


    public AlbumsTabAdapter(ImageLoader imageLoader, View.OnClickListener listener,
            boolean hasMimeTypeFilter) {
        mImageLoader = imageLoader;
        mOnClickListener = listener;
        mHasMimeTypeFilter = hasMimeTypeFilter;
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        return new AlbumGridHolder(viewGroup.getContext(), viewGroup, mImageLoader,
                mHasMimeTypeFilter);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder itemHolder, int position) {
        final Category category = getCategory(position);
        itemHolder.itemView.setTag(category);
        itemHolder.itemView.setOnClickListener(mOnClickListener);
        itemHolder.bind();
    }

    @Override
    public int getItemCount() {
        return mCategoryList.size();
    }

    @Override
    public int getItemViewType(int position) {
        return ITEM_TYPE_CATEGORY;
    }

    public Category getCategory(int position) {
        return mCategoryList.get(position);
    }

    public void updateCategoryList(List<Category> categoryList) {
        mCategoryList = categoryList;
        notifyDataSetChanged();
    }
}
