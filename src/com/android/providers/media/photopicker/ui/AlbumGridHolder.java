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

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.util.StringUtils;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * {@link RecyclerView.ViewHolder} of a {@link View} representing an album {@link Category}.
 */
class AlbumGridHolder extends RecyclerView.ViewHolder {

    private final ImageLoader mImageLoader;
    private final ImageView mIconThumb;
    private final TextView mAlbumName;
    private final TextView mItemCount;
    private final boolean mHasMimeTypeFilter;
    @NonNull
    private final AlbumsTabAdapter.OnAlbumClickListener mOnAlbumClickListener;

    AlbumGridHolder(@NonNull View itemView, @NonNull ImageLoader imageLoader,
            boolean hasMimeTypeFilter,
            @NonNull AlbumsTabAdapter.OnAlbumClickListener onAlbumClickListener) {
        super(itemView);

        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mAlbumName = itemView.findViewById(R.id.album_name);
        mItemCount = itemView.findViewById(R.id.item_count);
        mImageLoader = imageLoader;
        mHasMimeTypeFilter = hasMimeTypeFilter;
        mOnAlbumClickListener = onAlbumClickListener;
    }

    void bind(@NonNull Category category) {
        itemView.setOnClickListener(v -> mOnAlbumClickListener.onAlbumClick(category));
        mImageLoader.loadAlbumThumbnail(category, mIconThumb);
        mAlbumName.setText(category.getDisplayName(itemView.getContext()));

        // Check whether there is a mime type filter or not. If yes, hide the item count. Otherwise,
        // show the item count and update the count.
        if (mHasMimeTypeFilter) {
            mItemCount.setVisibility(View.GONE);
        } else {
            mItemCount.setVisibility(View.VISIBLE);
            final int itemCount = category.getItemCount();
            final String quantityText =
                    StringUtils.getICUFormatString(
                        itemView.getResources(), itemCount, R.string.picker_album_item_count);
            final String itemCountString = NumberFormat.getInstance(Locale.getDefault()).format(
                    itemCount);
            mItemCount.setText(TextUtils.expandTemplate(quantityText, itemCountString));
        }
    }
}
