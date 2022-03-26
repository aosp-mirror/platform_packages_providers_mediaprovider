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

package com.android.providers.media.photopicker;

import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED;
import static android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_BUFFERING;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_COMPLETED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;

import android.annotation.DurationMillisLong;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import com.android.providers.media.LocalCallingIdentity;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;
import com.android.providers.media.photopicker.data.ExternalDbFacade;
import com.android.providers.media.photopicker.ui.remotepreview.RemotePreviewHandler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.State;
import com.google.android.exoplayer2.analytics.AnalyticsCollector;
import com.google.android.exoplayer2.source.MediaParserExtractorAdapter;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.ContentDataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.video.VideoSize;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Implements the {@link CloudMediaProvider} interface over the local items in the MediaProvider
 * database.
 */
public class PhotoPickerProvider extends CloudMediaProvider {
    private static final String TAG = "PhotoPickerProvider";

    private MediaProvider mMediaProvider;
    private ExternalDbFacade mDbFacade;

    @Override
    public boolean onCreate() {
        mMediaProvider = getMediaProvider();
        mDbFacade = mMediaProvider.getExternalDbFacade();
        return true;
    }

    @Override
    public Cursor onQueryMedia(@Nullable Bundle extras) {
        // TODO(b/190713331): Handle extra_page
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryMedia(queryExtras.getGeneration(), queryExtras.getAlbumId(),
                queryExtras.getMimeType());
    }

    @Override
    public Cursor onQueryDeletedMedia(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryDeletedMedia(queryExtras.getGeneration());
    }

    @Override
    public Cursor onQueryAlbums(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryAlbums(queryExtras.getMimeType());
    }

    @Override
    public AssetFileDescriptor onOpenPreview(@NonNull String mediaId, @NonNull Point size,
            @NonNull Bundle extras, @NonNull CancellationSignal signal)
            throws FileNotFoundException {
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, size);

        final LocalCallingIdentity token = mMediaProvider.clearLocalCallingIdentity();
        try {
            return mMediaProvider.openTypedAssetFile(fromMediaId(mediaId), "image/*", opts);
        } finally {
            mMediaProvider.restoreLocalCallingIdentity(token);
        }
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(@NonNull String mediaId,
            @NonNull Bundle extras, @NonNull CancellationSignal signal)
            throws FileNotFoundException {
        final LocalCallingIdentity token = mMediaProvider.clearLocalCallingIdentity();
        try {
            return mMediaProvider.openFile(fromMediaId(mediaId), "r");
        } finally {
            mMediaProvider.restoreLocalCallingIdentity(token);
        }
    }

    @Override
    public Bundle onGetMediaCollectionInfo(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        // TODO(b/190713331): Handle extra_filter_albums
        Bundle bundle = new Bundle();
        try (Cursor cursor = mDbFacade.getMediaCollectionInfo(queryExtras.getGeneration())) {
            if (cursor.moveToFirst()) {
                int generationIndex = cursor.getColumnIndexOrThrow(
                        MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

                bundle.putString(MediaCollectionInfo.MEDIA_COLLECTION_ID,
                        MediaStore.getVersion(getContext()));
                bundle.putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION,
                        cursor.getLong(generationIndex));
            }
        }
        return bundle;
    }

    @Override
    @Nullable
    public CloudMediaSurfaceController onCreateCloudMediaSurfaceController(@NonNull Bundle config,
            CloudMediaSurfaceStateChangedCallback callback) {
        if (RemotePreviewHandler.isRemotePreviewEnabled()) {
            boolean enableLoop = config.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED, false);
            boolean muteAudio = config.getBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED,
                    false);
            return new CloudMediaSurfaceControllerImpl(getContext(), enableLoop, muteAudio,
                    callback);
        }
        return null;
    }

    private MediaProvider getMediaProvider() {
        ContentResolver cr = getContext().getContentResolver();
        try (ContentProviderClient cpc = cr.acquireContentProviderClient(MediaStore.AUTHORITY)) {
            return (MediaProvider) cpc.getLocalContentProvider();
        } catch (OperationCanceledException e) {
            throw new IllegalStateException("Failed to acquire MediaProvider", e);
        }
    }

    private static Uri fromMediaId(String mediaId) {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY,
                Long.parseLong(mediaId));
    }

    private static final class CloudMediaSurfaceControllerImpl extends CloudMediaSurfaceController {

        // The minimum duration of media that the player will attempt to ensure is buffered at all
        // times.
        private static final int MIN_BUFFER_MS = 1000;
        // The maximum duration of media that the player will attempt to buffer.
        private static final int MAX_BUFFER_MS = 2000;
        // The duration of media that must be buffered for playback to start or resume following a
        // user action such as a seek.
        private static final int BUFFER_FOR_PLAYBACK_MS = 1000;
        // The default duration of media that must be buffered for playback to resume after a
        // rebuffer.
        private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000;
        private static final LoadControl sLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build();

        private final Context mContext;
        private final CloudMediaSurfaceStateChangedCallback mCallback;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Player.Listener mEventListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(@State int state) {
                Log.d(TAG, "Received player event " + state);

                switch (state) {
                    case Player.STATE_READY:
                        mCallback.setPlaybackState(mCurrentSurfaceId, PLAYBACK_STATE_READY,
                                null);
                        return;
                    case Player.STATE_BUFFERING:
                        mCallback.setPlaybackState(mCurrentSurfaceId, PLAYBACK_STATE_BUFFERING,
                                null);
                        return;
                    case Player.STATE_ENDED:
                        mCallback.setPlaybackState(mCurrentSurfaceId, PLAYBACK_STATE_COMPLETED,
                                null);
                        return;
                    default:
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                mCallback.setPlaybackState(mCurrentSurfaceId, isPlaying ? PLAYBACK_STATE_STARTED :
                                PLAYBACK_STATE_PAUSED, null);
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                Point size = new Point(videoSize.width, videoSize.height);
                Bundle bundle = new Bundle();
                bundle.putParcelable(ContentResolver.EXTRA_SIZE, size);
                mCallback.setPlaybackState(mCurrentSurfaceId, PLAYBACK_STATE_MEDIA_SIZE_CHANGED,
                        bundle);
            }
        };

        private boolean mEnableLoop;
        private boolean mMuteAudio;
        private ExoPlayer mPlayer;
        private int mCurrentSurfaceId = -1;

        CloudMediaSurfaceControllerImpl(Context context, boolean enableLoop, boolean muteAudio,
                CloudMediaSurfaceStateChangedCallback callback) {
            mCallback = callback;
            mContext = context;
            mEnableLoop = enableLoop;
            mMuteAudio = muteAudio;
            Log.d(TAG, "Surface controller created.");
        }

        @Override
        public void onPlayerCreate() {
            mHandler.post(() -> {
                mPlayer = createExoPlayer();
                mPlayer.addListener(mEventListener);
                updateLoopingPlaybackStatus();
                updateAudioMuteStatus();
                Log.d(TAG, "Player created.");
            });
        }

        @Override
        public void onPlayerRelease() {
            mHandler.post(() -> {
                mPlayer.removeListener(mEventListener);
                mPlayer.release();
                mPlayer = null;
                Log.d(TAG, "Player released.");
            });
        }

        @Override
        public void onSurfaceCreated(int surfaceId, @NonNull Surface surface,
                @NonNull String mediaId) {
            mHandler.post(() -> {
                try {
                    // onSurfaceCreated may get called while the player is already rendering on a
                    // different surface. In that case, pause the player before preparing it for
                    // rendering on the new surface.
                    // Unfortunately, Exoplayer#stop doesn't seem to work here. If we call stop(),
                    // as soon as the player becomes ready again, it automatically starts to play
                    // the new media. The reason is that Exoplayer treats play/pause as calls to
                    // the method Exoplayer#setPlayWhenReady(boolean) with true and false
                    // respectively. So, if we don't pause(), then since the previous play() call
                    // had set setPlayWhenReady to true, the player would start the playback as soon
                    // as it gets ready with the new media item.
                    if (mPlayer.isPlaying()) {
                        mPlayer.pause();
                    }

                    mCurrentSurfaceId = surfaceId;

                    final Uri mediaUri =
                            Uri.parse(
                                    MediaStore.Files.getContentUri(
                                            MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                    + File.separator + mediaId);
                    mPlayer.setMediaItem(MediaItem.fromUri(mediaUri));
                    mPlayer.setVideoSurface(surface);
                    mPlayer.prepare();

                    Log.d(TAG, "Surface prepared: " + surfaceId + ". Surface: " + surface
                            + ". MediaId: " + mediaId);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error preparing player with surface.", e);
                }
            });
        }

        @Override
        public void onSurfaceChanged(int surfaceId, int format, int width, int height) {
            Log.d(TAG, "Surface changed: " + surfaceId + ". Format: " + format + ". Width: "
                    + width + ". Height: " + height);
        }

        @Override
        public void onSurfaceDestroyed(int surfaceId) {
            mHandler.post(() -> {
                if (mCurrentSurfaceId != surfaceId) {
                    // This means that the player is already using some other surface, hence
                    // nothing to do.
                    return;
                }
                if (mPlayer.isPlaying()) {
                    mPlayer.stop();
                }
                mPlayer.clearVideoSurface();
                mCurrentSurfaceId = -1;

                Log.d(TAG, "Surface released: " + surfaceId);
            });
        }

        @Override
        public void onMediaPlay(int surfaceId) {
            mHandler.post(() -> {
                mPlayer.play();
                Log.d(TAG, "Media played: " + surfaceId);
            });
        }

        @Override
        public void onMediaPause(int surfaceId) {
            mHandler.post(() -> {
                if (mPlayer.isPlaying()) {
                    mPlayer.pause();
                    Log.d(TAG, "Media paused: " + surfaceId);
                }
            });
        }

        @Override
        public void onMediaSeekTo(int surfaceId, @DurationMillisLong long timestampMillis) {
            mHandler.post(() -> {
                mPlayer.seekTo((int) timestampMillis);
                Log.d(TAG, "Media seeked: " + surfaceId + ". Timestamp: " + timestampMillis);
            });
        }

        @Override
        public void onConfigChange(@NonNull Bundle config) {
            final boolean enableLoop = config.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED,
                    mEnableLoop);
            final boolean muteAudio = config.getBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED,
                    mMuteAudio);
            mHandler.post(() -> {
                if (mEnableLoop != enableLoop) {
                    mEnableLoop = enableLoop;
                    updateLoopingPlaybackStatus();
                }

                if (mMuteAudio != muteAudio) {
                    mMuteAudio = muteAudio;
                    updateAudioMuteStatus();
                }
            });
            Log.d(TAG, "Config changed. Updated config params: " + config);
        }

        @Override
        public void onDestroy() {
            Log.d(TAG, "Surface controller destroyed.");
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

        private void updateLoopingPlaybackStatus() {
            mPlayer.setRepeatMode(mEnableLoop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }

        private void updateAudioMuteStatus() {
            if (mMuteAudio) {
                mPlayer.setVolume(0f);
            } else {
                AudioManager audioManager = mContext.getSystemService(AudioManager.class);
                if (audioManager == null) {
                    Log.e(TAG, "Couldn't find AudioManager while trying to set volume,"
                            + " unable to set volume");
                    return;
                }
                mPlayer.setVolume(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
            }
        }
    }
}
