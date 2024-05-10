/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.provider.CloudMediaProviderContract.EXTRA_AUTHORITY;
import static android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.CloudMediaProvider;
import android.provider.ICloudMediaSurfaceController;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.ui.remotepreview.RemoteSurfaceController;

import java.io.FileNotFoundException;

/**
 * Implements the {@link CloudMediaProvider} interface over the local items in the MediaProvider
 * database.
 */
public class RemoteVideoPreviewProvider extends CloudMediaProvider {
    private static final String TAG = "RemoteVideoPreviewProvider";
    public static final String AUTHORITY =
            "com.android.providers.media.remote_video_preview";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor onQueryMedia(Bundle extras) {
        throw new UnsupportedOperationException("onQueryMedia not supported");
    }

    @Override
    public Cursor onQueryDeletedMedia(Bundle extras) {
        throw new UnsupportedOperationException("onQueryDeletedMedia not supported");
    }

    @Override
    public AssetFileDescriptor onOpenPreview(String mediaId, Point size, Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenPreview not supported");
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(String mediaId, Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenMedia not supported");
    }

    @Override
    public Bundle onGetMediaCollectionInfo(Bundle extras) {
        throw new UnsupportedOperationException("onGetMediaCollectionInfo not supported");
    }

    @Override
    @Nullable
    public CloudMediaSurfaceController onCreateCloudMediaSurfaceController(@NonNull Bundle config,
            CloudMediaSurfaceStateChangedCallback callback) {
        final String authority = config.getString(EXTRA_AUTHORITY);
        if (authority == null) {
            throw new IllegalArgumentException("No cloud provider authority available");
        }

        final boolean enableLoop = config.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED, false);
        final boolean muteAudio = config.getBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED,
                false);
        return new RemoteSurfaceController(getContext(), authority, enableLoop, muteAudio,
                callback);
    }

    /**
     * {@link CloudMediaSurfaceController} implementation that proxies all requests to a 'remote'
     * {@link RemoteSurfaceController}.
     */
    public static class SurfaceControllerProxy extends CloudMediaSurfaceController {
        private final ICloudMediaSurfaceController mController;

        public SurfaceControllerProxy(ICloudMediaSurfaceController controller) {
            mController = controller;
        }

        @Override
        public void onPlayerCreate() {
            try {
                mController.onPlayerCreate();
            } catch (RemoteException e) {
                Log.e(TAG, "onPlayerCreate failed", e);
            }
        }

        @Override
        public void onPlayerRelease() {
            try {
                mController.onPlayerRelease();
            } catch (RemoteException e) {
                Log.e(TAG, "onPlayerRelease failed", e);
            }
        }

        @Override
        public void onSurfaceCreated(int surfaceId, @NonNull Surface surface,
                @NonNull String mediaId) {
            try {
                mController.onSurfaceCreated(surfaceId, surface, mediaId);
            } catch (RemoteException e) {
                Log.e(TAG, "onSurfaceCreated failed", e);
            }
        }

        @Override
        public void onSurfaceChanged(int surfaceId, int format, int width, int height) {
            try {
                mController.onSurfaceChanged(surfaceId, format, width, height);
            } catch (RemoteException e) {
                Log.e(TAG, "onSurfaceChanged failed", e);
            }
        }

        @Override
        public void onSurfaceDestroyed(int surfaceId) {
            try {
                mController.onSurfaceDestroyed(surfaceId);
            } catch (RemoteException e) {
                Log.e(TAG, "onSurfaceDestroyed failed", e);
            }
        }

        @Override
        public void onMediaPlay(int surfaceId) {
            try {
                mController.onMediaPlay(surfaceId);
            } catch (RemoteException e) {
                Log.e(TAG, "onMediaPlay failed", e);
            }
        }

        @Override
        public void onMediaPause(int surfaceId) {
            try {
                mController.onMediaPause(surfaceId);
            } catch (RemoteException e) {
                Log.e(TAG, "onMediaPause failed", e);
            }
        }

        @Override
        public void onMediaSeekTo(int surfaceId, long timestampMillis) {
            try {
                mController.onMediaSeekTo(surfaceId, timestampMillis);
            } catch (RemoteException e) {
                Log.e(TAG, "onMediaSeekTo failed", e);
            }
        }

        @Override
        public void onConfigChange(@NonNull Bundle config) {
            try {
                mController.onConfigChange(config);
            } catch (RemoteException e) {
                Log.e(TAG, "onConfigChange failed", e);
            }
        }

        @Override
        public void onDestroy() {
            try {
                mController.onDestroy();
            } catch (RemoteException e) {
                Log.e(TAG, "onDestroy failed", e);
            }
        }
    }
}
