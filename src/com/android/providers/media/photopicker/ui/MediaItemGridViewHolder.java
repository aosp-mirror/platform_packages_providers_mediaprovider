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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * {@link RecyclerView.ViewHolder} of a {@link View} representing a (media) {@link Item} (a photo or
 * a video).
 */
class MediaItemGridViewHolder extends RecyclerView.ViewHolder {
    private final ImageLoader mImageLoader;
    private final ImageView mIconThumb;
    private final ImageView mIconGif;
    private final ImageView mIconMotionPhoto;
    private final View mVideoBadgeContainer;
    private final TextView mVideoDuration;
    private final View mOverlayGradient;
    private final boolean mCanSelectMultiple;

    MediaItemGridViewHolder(@NonNull View itemView, @NonNull ImageLoader imageLoader,
            boolean canSelectMultiple) {
        super(itemView);
        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mIconGif = itemView.findViewById(R.id.icon_gif);
        mIconMotionPhoto = itemView.findViewById(R.id.icon_motion_photo);
        mVideoBadgeContainer = itemView.findViewById(R.id.video_container);
        mVideoDuration = mVideoBadgeContainer.findViewById(R.id.video_duration);
        mOverlayGradient = itemView.findViewById(R.id.overlay_gradient);
        mImageLoader = imageLoader;
        mCanSelectMultiple = canSelectMultiple;

        itemView.findViewById(R.id.icon_check).setVisibility(mCanSelectMultiple ? VISIBLE : GONE);
    }

    public void bind(@NonNull Item item, boolean isSelected) {
        mImageLoader.loadPhotoThumbnail(item, mIconThumb);

        mIconGif.setVisibility(item.isGifOrAnimatedWebp() ? VISIBLE : GONE);
        mIconMotionPhoto.setVisibility(item.isMotionPhoto() ? VISIBLE : GONE);

        if (item.isVideo()) {
            mVideoBadgeContainer.setVisibility(VISIBLE);
            mVideoDuration.setText(item.getDurationText());
        } else {
            mVideoBadgeContainer.setVisibility(GONE);
        }

        if (showShowOverlayGradient(item)) {
            mOverlayGradient.setVisibility(VISIBLE);
        } else {
            mOverlayGradient.setVisibility(GONE);
        }

        final Context context = getContext();
        itemView.setContentDescription(item.getContentDescription(context));

        if (mCanSelectMultiple) {
            itemView.setSelected(isSelected);
            // There is an issue b/223695510 about not selected in Accessibility mode. It only
            // says selected state, but it doesn't say not selected state. Add the not selected
            // only to avoid that it says selected twice.
            itemView.setStateDescription(
                    isSelected ? null : context.getString(R.string.not_selected));
        }
    }

    @NonNull
    private Context getContext() {
        return itemView.getContext();
    }

    private boolean showShowOverlayGradient(@NonNull Item item) {
        return mCanSelectMultiple
                || item.isGifOrAnimatedWebp()
                || item.isVideo()
                || item.isMotionPhoto();
    }
}
