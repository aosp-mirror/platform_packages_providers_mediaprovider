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
import static android.provider.CloudMediaProvider.SurfaceEventCallback.PLAYBACK_EVENT_READY;
import static android.provider.CloudMediaProviderContract.MediaInfo;

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
import com.google.android.exoplayer2.analytics.AnalyticsCollector;
import com.google.android.exoplayer2.source.MediaParserExtractorAdapter;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.ContentDataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;

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
    public Cursor onQueryMedia(@NonNull String mediaId) {
        return mDbFacade.queryMediaId(Long.parseLong(mediaId));
    }

    @Override
    public Cursor onQueryMedia(@Nullable Bundle extras) {
        // TODO(b/190713331): Handle extra_page
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryMediaGeneration(queryExtras.getGeneration(), queryExtras.getAlbumId(),
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
    public Bundle onGetMediaInfo(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        // TODO(b/190713331): Handle extra_filter_albums
        Bundle bundle = new Bundle();
        try (Cursor cursor = mDbFacade.getMediaInfo(queryExtras.getGeneration())) {
            if (cursor.moveToFirst()) {
                int generationIndex = cursor.getColumnIndexOrThrow(MediaInfo.MEDIA_GENERATION);
                int countIndex = cursor.getColumnIndexOrThrow(MediaInfo.MEDIA_COUNT);

                bundle.putString(MediaInfo.MEDIA_VERSION, MediaStore.getVersion(getContext()));
                bundle.putLong(MediaInfo.MEDIA_GENERATION, cursor.getLong(generationIndex));
                bundle.putLong(MediaInfo.MEDIA_COUNT, cursor.getLong(countIndex));
            }
        }
        return bundle;
    }

    @Override
    @Nullable
    public SurfaceController onCreateSurfaceController(@Nullable Bundle config,
            SurfaceEventCallback callback) {
        if (RemotePreviewHandler.isRemotePreviewEnabled()) {
            boolean enableLoop = config != null && config.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED,
                    false);
            return new SurfaceControllerImpl(getContext(), enableLoop, callback);
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

    private static final class SurfaceControllerImpl extends SurfaceController {

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
        private final SurfaceEventCallback mCallback;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final boolean mEnableLoop;
        private ExoPlayer mPlayer;
        private int mCurrentSurfaceId = -1;

        SurfaceControllerImpl(Context context, boolean enableLoop, SurfaceEventCallback callback) {
            mCallback = callback;
            mContext = context;
            mEnableLoop = enableLoop;
            Log.d(TAG, "Surface controller created.");
        }

        @Override
        public void onPlayerCreate() {
            mHandler.post(() -> {
                mPlayer = createExoPlayer();
                Log.d(TAG, "Player created.");
            });
        }

        @Override
        public void onPlayerRelease() {
            mHandler.post(() -> {
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
                    mPlayer.setRepeatMode(mEnableLoop ?
                            Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                    final Uri mediaUri =
                            Uri.parse(
                                    MediaStore.Files.getContentUri(
                                            MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                    + File.separator + mediaId);
                    mPlayer.setMediaItem(MediaItem.fromUri(mediaUri));
                    mPlayer.setVideoSurface(surface);
                    mCurrentSurfaceId = surfaceId;
                    mPlayer.prepare();

                    mCallback.onPlaybackEvent(surfaceId, PLAYBACK_EVENT_READY, null);

                    Log.d(TAG, "Surface prepared: " + surfaceId + ". Surface: " + surface
                            + ". MediaId: " + mediaId);
                } catch (Exception e) {
                    Log.e(TAG, "Error preparing surface.", e);
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
