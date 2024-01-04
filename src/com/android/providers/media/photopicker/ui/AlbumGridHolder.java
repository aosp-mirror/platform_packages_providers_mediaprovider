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

import android.provider.CloudMediaProviderContract;
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
    private final ImageView mIconDefaultThumb;
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
        mIconDefaultThumb = itemView.findViewById(R.id.icon_default_thumbnail);
        mAlbumName = itemView.findViewById(R.id.album_name);
        mItemCount = itemView.findViewById(R.id.item_count);
        mImageLoader = imageLoader;
        mHasMimeTypeFilter = hasMimeTypeFilter;
        mOnAlbumClickListener = onAlbumClickListener;
    }

    void bind(@NonNull Category category) {
        int position = getAbsoluteAdapterPosition();
        itemView.setOnClickListener(v -> mOnAlbumClickListener.onAlbumClick(category, position));

        // Show default thumbnail icons if merged album is empty.
        int defaultResId = -1;
        if (CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES.equals(category.getId())) {
            defaultResId = R.drawable.thumbnail_favorites;
        } else if (CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
                .equals(category.getId())) {
            defaultResId = R.drawable.thumbnail_videos;
        }
        mImageLoader.loadAlbumThumbnail(category, mIconThumb, defaultResId, mIconDefaultThumb);
        mAlbumName.setText(category.getDisplayName(itemView.getContext()));
        // Check whether there is a mime type filter or not. If yes, hide the item count. Otherwise,
        // show the item count and update the count.
        if (mItemCount.getVisibility() == View.VISIBLE) {
            // As per the current requirements, we are hiding album's item count and this piece of
            // code will never execute. for now keeping it here as it is, in case if in future we
            // need to show album's item count.
            if (mHasMimeTypeFilter) {
                mItemCount.setVisibility(View.GONE);
            } else {
                mItemCount.setVisibility(View.VISIBLE);
                final int itemCount = category.getItemCount();
                final String quantityText =
                        StringUtils.getICUFormatString(
                                itemView.getResources(), itemCount,
                                R.string.picker_album_item_count);
                final String itemCountString = NumberFormat.getInstance(Locale.getDefault()).format(
                        itemCount);
                mItemCount.setText(TextUtils.expandTemplate(quantityText, itemCountString));
            }
        }
    }
}
