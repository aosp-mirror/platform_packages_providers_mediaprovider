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

import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.ui.remotepreview.RemotePreviewHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for Preview RecyclerView to preview all images and videos.
 */
class PreviewAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    private static final int ITEM_TYPE_IMAGE = 1;
    private static final int ITEM_TYPE_VIDEO = 2;

    private List<Item> mItemList = new ArrayList<>();
    private final ImageLoader mImageLoader;
    private final RemotePreviewHandler mRemotePreviewHandler;

    PreviewAdapter(Context context, MuteStatus muteStatus) {
        mImageLoader = new ImageLoader(context);
        mRemotePreviewHandler = new RemotePreviewHandler(context, muteStatus);
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if (viewType == ITEM_TYPE_IMAGE) {
            return new PreviewImageHolder(viewGroup.getContext(), viewGroup, mImageLoader);
        }
        return new PreviewVideoHolder(viewGroup.getContext(), viewGroup, mImageLoader);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        final Item item = getItem(position);
        holder.itemView.setContentDescription(
                item.getContentDescription(holder.itemView.getContext()));
        holder.itemView.setTag(item);
        holder.bind();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull BaseViewHolder holder) {
        super.onViewAttachedToWindow(holder);

        final Item item = (Item) holder.itemView.getTag();
        if (item.isVideo()) {
            PreviewVideoHolder videoHolder = (PreviewVideoHolder) holder;
            mRemotePreviewHandler.onViewAttachedToWindow(videoHolder, item);
        }
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

    void onHandlePageSelected(View itemView) {
        final Item item = (Item) itemView.getTag();
        mRemotePreviewHandler.onHandlePageSelected(item);
    }

    void onStop() {
        mRemotePreviewHandler.onStop();
    }

    void onDestroy() {
        mRemotePreviewHandler.onDestroy();
    }

    Item getItem(int position) {
        return mItemList.get(position);
    }

    void updateItemList(List<Item> itemList) {
        mItemList = itemList;
        notifyDataSetChanged();
    }
}
