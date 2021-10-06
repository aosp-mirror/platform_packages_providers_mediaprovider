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
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.viewpager2.widget.ViewPager2;
import androidx.annotation.NonNull;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * ViewHolder of an image item within the {@link ViewPager2}
 */
public class PreviewImageHolder extends BaseViewHolder {
    private final ImageLoader mImageLoader;
    private final ImageView mImageView;

    public PreviewImageHolder(@NonNull Context context, @NonNull ViewGroup parent,
            @NonNull ImageLoader imageLoader) {
        super(context, parent, R.layout.item_image_preview);

        mImageView = itemView.findViewById(R.id.preview_imageView);
        mImageLoader = imageLoader;
    }

    @Override
    public void bind() {
        final Item item = (Item) itemView.getTag();
        mImageLoader.loadImagePreview(item, mImageView);
    }
}
