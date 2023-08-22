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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * ViewHolder of a video item within the {@link ViewPager2}
 */
public class PreviewVideoHolder extends BaseViewHolder {

    private final ImageLoader mImageLoader;
    private final ImageView mImageView;
    private final SurfaceView mSurfaceView;
    private final AspectRatioFrameLayout mPlayerFrame;
    private final View mPlayerContainer;
    private final View mPlayerControlsRoot;
    private final ImageButton mPlayPauseButton;
    private final ImageButton mMuteButton;
    private final CircularProgressIndicator mCircularProgressIndicator;

    PreviewVideoHolder(Context context, ViewGroup parent, ImageLoader imageLoader) {
        super(context, parent, R.layout.item_video_preview);

        mImageLoader = imageLoader;
        mImageView = itemView.findViewById(R.id.preview_video_image);
        mSurfaceView = itemView.findViewById(R.id.preview_player_view);
        mPlayerFrame = itemView.findViewById(R.id.preview_player_frame);
        mPlayerContainer = itemView.findViewById(R.id.preview_player_container);
        mPlayerControlsRoot = itemView.findViewById(R.id.preview_player_controls);
        mPlayPauseButton = itemView.findViewById(R.id.exo_play_pause);
        mMuteButton = itemView.findViewById(R.id.preview_mute);
        mCircularProgressIndicator = itemView.findViewById(R.id.preview_progress_indicator);

    }

    @Override
    public void bind() {
        // Video playback needs granular page state events and hence video playback is initiated by
        // ViewPagerWrapper and handled by PlaybackHandler#handleVideoPlayback.
        // Here, we set the ImageView with thumbnail from the video, to improve the
        // user experience while video player is not yet initialized or being prepared.
        final Item item = (Item) itemView.getTag();
        mImageLoader.loadImageFromVideoForPreview(item, mImageView);
    }

    public ImageView getThumbnailView() {
        return mImageView;
    }

    public SurfaceHolder getSurfaceHolder() {
        return mSurfaceView.getHolder();
    }

    public AspectRatioFrameLayout getPlayerFrame() {
        return mPlayerFrame;
    }

    public View getPlayerContainer() {
        return mPlayerContainer;
    }

    public View getPlayerControlsRoot() {
        return mPlayerControlsRoot;
    }

    public ImageButton getPlayPauseButton() {
        return mPlayPauseButton;
    }

    public ImageButton getMuteButton() {
        return mMuteButton;
    }

    public CircularProgressIndicator getCircularProgressIndicator() {
        return mCircularProgressIndicator;
    }
}
