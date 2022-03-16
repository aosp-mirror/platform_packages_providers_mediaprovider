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

import static android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceEventCallback.PLAYBACK_EVENT_BUFFERING;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceEventCallback.PLAYBACK_EVENT_COMPLETED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceEventCallback.PLAYBACK_EVENT_MEDIA_SIZE_CHANGED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceEventCallback.PLAYBACK_EVENT_READY;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;

import android.annotation.DurationMillisLong;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
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
    public CloudMediaSurfaceController onCreateCloudMediaSurfaceController(@Nullable Bundle config,
            CloudMediaSurfaceEventCallback callback) {
        if (RemotePreviewHandler.isRemotePreviewEnabled()) {
            boolean enableLoop = config != null && config.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED,
                    false);
            return new CloudMediaSurfaceControllerImpl(getContext(), enableLoop, callback);
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
        private final CloudMediaSurfaceEventCallback mCallback;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final boolean mEnableLoop;
        private final Player.Listener mEventListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(@State int state) {
                Log.d(TAG, "Received player event " + state);

                switch (state) {
                    case Player.STATE_READY:
                        mCallback.onPlaybackEvent(mCurrentSurfaceId, PLAYBACK_EVENT_READY,
                                null);
                        return;
                    case Player.STATE_BUFFERING:
                        mCallback.onPlaybackEvent(mCurrentSurfaceId, PLAYBACK_EVENT_BUFFERING,
                                null);
                        return;
                    case Player.STATE_ENDED:
                        mCallback.onPlaybackEvent(mCurrentSurfaceId, PLAYBACK_EVENT_COMPLETED,
                                null);
                        return;
                    default:
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                Point size = new Point(videoSize.width, videoSize.height);
                Bundle bundle = new Bundle();
                bundle.putParcelable(ContentResolver.EXTRA_SIZE, size);
                mCallback.onPlaybackEvent(mCurrentSurfaceId, PLAYBACK_EVENT_MEDIA_SIZE_CHANGED,
                        bundle);
            }
        };

        private ExoPlayer mPlayer;
        private int mCurrentSurfaceId = -1;

        CloudMediaSurfaceControllerImpl(Context context, boolean enableLoop,
                CloudMediaSurfaceEventCallback callback) {
            mCallback = callback;
            mContext = context;
            mEnableLoop = enableLoop;
            Log.d(TAG, "Surface controller created.");
        }

        @Override
        public void onPlayerCreate() {
            mHandler.post(() -> {
                mPlayer = createExoPlayer();
                mPlayer.setRepeatMode(mEnableLoop ?
                        Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                mPlayer.addListener(mEventListener);
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
            // TODO(b/195009562): Implement mute/unmute audio and loop enabled/disabled
            // for video preview
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
    }
}
