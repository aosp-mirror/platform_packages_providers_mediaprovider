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

import static android.provider.CloudMediaProviderContract.EXTRA_AUTHORITY;
import static android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.LocalCallingIdentity;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;
import com.android.providers.media.photopicker.data.ExternalDbFacade;
import com.android.providers.media.photopicker.ui.remotepreview.RemotePreviewHandler;
import com.android.providers.media.photopicker.ui.remotepreview.RemoteSurfaceController;

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

        String mimeTypeFilter = null;
        if (extras.getBoolean(EXTRA_MEDIASTORE_THUMB)) {
            // This is a request for thumbnail, set "image/*" to get cached thumbnails from
            // MediaProvider.
            mimeTypeFilter = "image/*";
        }

        final LocalCallingIdentity token = mMediaProvider.clearLocalCallingIdentity();
        try {
            return mMediaProvider.openTypedAssetFile(fromMediaId(mediaId), mimeTypeFilter, opts);
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

        return mDbFacade.getMediaCollectionInfo(queryExtras.getGeneration());
    }

    @Override
    @Nullable
    public CloudMediaSurfaceController onCreateCloudMediaSurfaceController(@NonNull Bundle config,
            CloudMediaSurfaceStateChangedCallback callback) {
        if (RemotePreviewHandler.isRemotePreviewEnabled()) {
            final String authority = config.getString(EXTRA_AUTHORITY,
                    PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
            final boolean enableLoop = config.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED, false);
            final boolean muteAudio = config.getBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED,
                    false);
            return new RemoteSurfaceController(getContext(), authority, enableLoop, muteAudio,
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
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                Long.parseLong(mediaId));
    }
}
