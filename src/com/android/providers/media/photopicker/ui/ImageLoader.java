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
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * A class to assist with loading and managing the Images (i.e. thumbnails and preview) associated
 * with item.
 */
public class ImageLoader {

    private final Context mContext;

    public ImageLoader(Context context) {
        mContext = context;
    }

    /**
     * Load the thumbnail of the {@code category} and set it on the {@code imageView}
     * @param category the album
     * @param imageView the imageView shows the thumbnail
     */
    public void loadAlbumThumbnail(@NonNull Category category, @NonNull ImageView imageView) {
        Glide.with(mContext)
                .load(category.getCoverUri())
                .thumbnail()
                .into(imageView);
    }

    /**
     * Load the thumbnail of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item the photo item
     * @param imageView the imageView shows the thumbnail
     */
    public void loadPhotoThumbnail(@NonNull Item item, @NonNull ImageView imageView) {
        Glide.with(mContext)
                .load(item.getContentUri())
                .thumbnail()
                .into(imageView);
    }

    /**
     * Load the image of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item the photo item
     * @param imageView the imageView shows the image
     */
    public void loadImagePreview(@NonNull Item item, @NonNull ImageView imageView) {
        Glide.with(mContext)
                .load(item.getContentUri())
                .into(imageView);
    }
}
