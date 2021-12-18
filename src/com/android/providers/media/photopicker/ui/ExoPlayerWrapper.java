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
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;

import com.android.providers.media.R;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsCollector;
import com.google.android.exoplayer2.source.MediaParserExtractorAdapter;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.ContentDataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;

/**
 * A helper class that assists in initialize/prepare/play/release of ExoPlayer. The class assumes
 * that all its public methods are called from main thread only.
 */
class ExoPlayerWrapper {
    // The minimum duration of media that the player will attempt to ensure is buffered at all
    // times.
    private static final int MIN_BUFFER_MS = 1000;
    // The maximum duration of media that the player will attempt to buffer.
    private static final int MAX_BUFFER_MS = 2000;
    // The duration of media that must be buffered for playback to start or resume following a user
    // action such as a seek.
    private static final int BUFFER_FOR_PLAYBACK_MS = 1000;
    // The default duration of media that must be buffered for playback to resume after a rebuffer.
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000;
    private static final LoadControl sLoadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build();

    private final Context mContext;
    private ExoPlayer mExoPlayer;
    private boolean mIsPlayerReleased = true;

    public ExoPlayerWrapper(Context context) {
        mContext = context;
    }

    /**
     * Prepares the {@link ExoPlayer} and attaches it to given {@code styledPlayerView} and starts
     * playing.
     * Note: The method tries to release the {@link ExoPlayer} before preparing the new one. As we
     * don't have previous page's {@link StyledPlayerView}, we can't switch the player from previous
     * {@link StyledPlayerView} to new one. Hence, we try to create a new {@link ExoPlayer} instead.
     */
    public void prepareAndPlay(StyledPlayerView styledPlayerView, ImageView imageView, Uri uri) {
        // TODO(b/197083539): Explore options for not re-creating ExoPlayer everytime.
        initializeExoPlayer(uri);

        // TODO(b/197083539): Remove this if it drains battery.
        styledPlayerView.setKeepScreenOn(true);
        styledPlayerView.setPlayer(mExoPlayer);
        styledPlayerView.setVisibility(View.VISIBLE);
        // Hide ImageView when the player is ready.
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY ) {
                    imageView.setVisibility(View.GONE);
                }
            }
        });

        mExoPlayer.prepare();
        mIsPlayerReleased = false;

        mExoPlayer.setPlayWhenReady(true);
    }

    public void releaseIfNecessary() {
        releaseIfNecessaryInternal();
    }

    private ExoPlayer createExoPlayer() {
        // ProgressiveMediaFactory will be enough for video playback of videos on the device.
        // This also reduces apk size.
        ProgressiveMediaSource.Factory mediaSourceFactory = new ProgressiveMediaSource.Factory(
                () -> new ContentDataSource(mContext), MediaParserExtractorAdapter.FACTORY);

        return new ExoPlayer.Builder(mContext,
                new DefaultRenderersFactory(mContext),
                new DefaultTrackSelector(mContext),
                mediaSourceFactory,
                sLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mContext),
                new AnalyticsCollector(Clock.DEFAULT)).buildExoPlayer();
    }

    private void initializeExoPlayer(Uri uri) {
        // Try releasing the ExoPlayer first.
        releaseIfNecessaryInternal();

        mExoPlayer = createExoPlayer();
        // We always start from the beginning of the video, and we always repeat the video in a loop
        mExoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        // We only play one video in the player, hence we should always use setMediaItem instead of
        // ExoPlayer#addMediaItem
        mExoPlayer.setMediaItem(MediaItem.fromUri(uri));
    }

    private void releaseIfNecessaryInternal() {
        // Release the player only when it's not already released. ExoPlayer doesn't crash if we try
        // to release already released player, but ExoPlayer#release() may not be a no-op, hence we
        // call release() only when it's not already released.
        if (!mIsPlayerReleased) {
            mExoPlayer.release();
            mIsPlayerReleased = true;
        }
    }
}
