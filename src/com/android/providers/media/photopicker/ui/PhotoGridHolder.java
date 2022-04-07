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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * ViewHolder of a photo item within a RecyclerView.
 */
public class PhotoGridHolder extends BaseViewHolder {

    private final ImageLoader mImageLoader;
    private final ImageView mIconThumb;
    private final ImageView mIconGif;
    private final ImageView mIconMotionPhoto;
    private final View mVideoBadgeContainer;
    private final TextView mVideoDuration;
    private final View mOverlayGradient;
    private final boolean mCanSelectMultiple;

    public PhotoGridHolder(@NonNull Context context, @NonNull ViewGroup parent,
            @NonNull ImageLoader imageLoader, boolean canSelectMultiple) {
        super(context, parent, R.layout.item_photo_grid);

        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mIconGif = itemView.findViewById(R.id.icon_gif);
        mIconMotionPhoto = itemView.findViewById(R.id.icon_motion_photo);
        mVideoBadgeContainer = itemView.findViewById(R.id.video_container);
        mVideoDuration = mVideoBadgeContainer.findViewById(R.id.video_duration);
        mOverlayGradient = itemView.findViewById(R.id.overlay_gradient);
        mImageLoader = imageLoader;
        final ImageView iconCheck = itemView.findViewById(R.id.icon_check);
        mCanSelectMultiple = canSelectMultiple;
        if (mCanSelectMultiple) {
            iconCheck.setVisibility(View.VISIBLE);
        } else {
            iconCheck.setVisibility(View.GONE);
        }
    }

    @Override
    public void bind() {
        final Item item = (Item) itemView.getTag();
        mImageLoader.loadPhotoThumbnail(item, mIconThumb);

        mIconGif.setVisibility(item.isGifOrAnimatedWebp() ? View.VISIBLE : View.GONE);
        mIconMotionPhoto.setVisibility(item.isMotionPhoto() ? View.VISIBLE : View.GONE);

        if (item.isVideo()) {
            mVideoBadgeContainer.setVisibility(View.VISIBLE);
            mVideoDuration.setText(item.getDurationText());
        } else {
            mVideoBadgeContainer.setVisibility(View.GONE);
        }

        if (showShowOverlayGradient(item)) {
            mOverlayGradient.setVisibility(View.VISIBLE);
        } else {
            mOverlayGradient.setVisibility(View.GONE);
        }
    }

    private boolean showShowOverlayGradient(@NonNull Item item) {
        return mCanSelectMultiple || item.isGifOrAnimatedWebp() || item.isVideo() ||
                item.isMotionPhoto();
    }
}
