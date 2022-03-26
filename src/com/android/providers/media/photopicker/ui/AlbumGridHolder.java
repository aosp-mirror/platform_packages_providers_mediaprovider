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
import android.icu.text.MessageFormat;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.util.StringUtils;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ViewHolder of a album item within a RecyclerView.
 */
public class AlbumGridHolder extends BaseViewHolder {

    private final ImageLoader mImageLoader;
    private final ImageView mIconThumb;
    private final TextView mAlbumName;
    private final TextView mItemCount;
    private final boolean mHasMimeTypeFilter;

    public AlbumGridHolder(@NonNull Context context, @NonNull ViewGroup parent,
            @NonNull ImageLoader imageLoader, boolean hasMimeTypeFilter) {
        super(context, parent, R.layout.item_album_grid);

        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mAlbumName = itemView.findViewById(R.id.album_name);
        mItemCount = itemView.findViewById(R.id.item_count);
        mImageLoader = imageLoader;
        mHasMimeTypeFilter = hasMimeTypeFilter;
    }

    @Override
    public void bind() {
        final Category category = (Category) itemView.getTag();
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
