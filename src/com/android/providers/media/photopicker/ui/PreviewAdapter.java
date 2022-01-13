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
    private final PlaybackHandler mPlaybackHandler;

    public PreviewAdapter(Context context) {
        mImageLoader = new ImageLoader(context);
        mPlaybackHandler = new PlaybackHandler(context, mImageLoader);
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
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        final Item item = getItem(position);
        holder.itemView.setTag(item);
        holder.bind();

        if (item.isVideo()) {
            mPlaybackHandler.onBind(holder.itemView);
        }
    }

    @Override
    public void onViewAttachedToWindow(BaseViewHolder holder) {
        super.onViewAttachedToWindow(holder);

        final Item item = (Item) holder.itemView.getTag();
        if (item.isVideo()) {
            mPlaybackHandler.onViewAttachedToWindow(holder.itemView);
        }
    }

    public void handlePageSelected(View itemView) {
        mPlaybackHandler.handleVideoPlayback(itemView);
    }

    public void onStop() {
        mPlaybackHandler.releaseResources();
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
