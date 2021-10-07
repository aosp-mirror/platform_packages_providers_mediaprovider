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
import android.widget.VideoView;

import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * ViewHolder of a video item within the {@link ViewPager2}
 */
public class PreviewVideoHolder extends BaseViewHolder {
    private final VideoView mVideoView;

    public PreviewVideoHolder(Context context, ViewGroup parent) {
        super(context, parent, R.layout.item_video_preview);
        mVideoView = itemView.findViewById(R.id.preview_videoView);
    }

    @Override
    public void bind() {
        final Item item = (Item) itemView.getTag();
        mVideoView.setVideoURI(item.getContentUri());
    }

    @Override
    public void onViewAttachedToWindow() {
        super.onViewAttachedToWindow();
        mVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            // For simplicity, we will always start the video from the beginning.
            mp.seekTo(0);
            mp.start();
        });
    }

    @Override
    public void onViewDetachedFromWindow() {
        super.onViewDetachedFromWindow();
        mVideoView.pause();
    }

    @Override
    public void onViewRecycled() {
        super.onViewRecycled();
        // This will deallocate any MediaPlayer resources it has been holding
        mVideoView.stopPlayback();
    }
}
