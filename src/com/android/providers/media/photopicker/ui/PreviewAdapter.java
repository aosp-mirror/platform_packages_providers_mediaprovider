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

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for Preview RecyclerView to preview all images and videos.
 */
public class PreviewAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    private static final int ITEM_TYPE_IMAGE = 1;
    private static final int ITEM_TYPE_VIDEO = 2;

    private List<Item> mItemList = new ArrayList<>();
    private ImageLoader mImageLoader;

    public PreviewAdapter(ImageLoader imageLoader) {
        mImageLoader = imageLoader;
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if (viewType == ITEM_TYPE_IMAGE) {
            return new PreviewImageHolder(viewGroup.getContext(), viewGroup, mImageLoader);
        } else {
            return new PreviewVideoHolder(viewGroup.getContext(), viewGroup);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder photoHolder, int position) {
        final Item item = getItem(position);
        photoHolder.itemView.setTag(item);
        photoHolder.bind();
    }

    @Override
    public void onViewAttachedToWindow(BaseViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.onViewAttachedToWindow();
    }

    @Override
    public void onViewDetachedFromWindow(BaseViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        holder.onViewDetachedFromWindow();
    }

    @Override
    public void onViewRecycled(BaseViewHolder holder) {
        super.onViewRecycled(holder);
        holder.onViewRecycled();
    }

    @Override
    public int getItemCount() {
        return mItemList.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (mItemList.get(position).isVideo()) {
            return ITEM_TYPE_VIDEO;
        }
        // Everything other than video mimeType are previewed using PreviewImageHolder. This also
        // includes GIF which uses Glide to load image.
        return ITEM_TYPE_IMAGE;
    }

    public Item getItem(int position) {
        return mItemList.get(position);
    }

    public void updateItemList(List<Item> itemList) {
        mItemList = itemList;
        notifyDataSetChanged();
    }
}
