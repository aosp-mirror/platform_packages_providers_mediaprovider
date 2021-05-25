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

import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * ViewHolder of a photo item within a RecyclerView.
 */
public class PhotoGridHolder extends BaseItemHolder {

    private final Context mContext;
    private final IconHelper mIconHelper;
    private final ImageView mIconThumb;
    private final ImageView mIconGif;
    private final ImageView mIconVideo;
    private final View mVideoBadgeContainer;
    private final TextView mVideoDuration;

    public PhotoGridHolder(Context context, ViewGroup parent, IconHelper iconHelper,
            boolean canSelectMultiple) {
        super(context, parent, R.layout.item_photo_grid);

        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mIconGif = itemView.findViewById(R.id.icon_gif);
        mVideoBadgeContainer = itemView.findViewById(R.id.video_container);
        mIconVideo = mVideoBadgeContainer.findViewById(R.id.icon_video);
        mVideoDuration = mVideoBadgeContainer.findViewById(R.id.video_duration);
        mContext = context;
        mIconHelper = iconHelper;
        final ImageView iconCheck = itemView.findViewById(R.id.icon_check);
        if (canSelectMultiple) {
            iconCheck.setVisibility(View.VISIBLE);
        } else {
            iconCheck.setVisibility(View.GONE);
        }
    }

    @Override
    public void bind() {
        final Item item = (Item) itemView.getTag();
        mIconHelper.load(item, mIconThumb);

        if (item.isGif()) {
            mIconGif.setVisibility(View.VISIBLE);
        } else {
            mIconGif.setVisibility(View.GONE);
        }

        if (item.isVideo()) {
            mVideoBadgeContainer.setVisibility(View.VISIBLE);
            mVideoDuration.setText(DateUtils.formatElapsedTime(item.getDuration() / 1000));
        } else {
            mVideoBadgeContainer.setVisibility(View.GONE);
        }
    }
}
