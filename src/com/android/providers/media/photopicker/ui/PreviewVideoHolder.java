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
import android.view.SurfaceView;
import android.view.ViewGroup;

import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;

/**
 * ViewHolder of a video item within the {@link ViewPager2}
 */
public class PreviewVideoHolder extends BaseViewHolder {

    private SurfaceView mSurfaceView;

    public PreviewVideoHolder(Context context, ViewGroup parent, boolean enabledCloudMediaPreview) {
        super(context, parent, enabledCloudMediaPreview ? R.layout.item_cloud_video_preview
                : R.layout.item_video_preview);
        if (enabledCloudMediaPreview) {
            mSurfaceView = itemView.findViewById(R.id.preview_player_view);
        }
    }

    @Override
    public void bind() {
        // Video playback needs granular page state events and hence video playback is initiated by
        // ViewPagerWrapper and handled by PlaybackHandler#handleVideoPlayback
    }

    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }
}
