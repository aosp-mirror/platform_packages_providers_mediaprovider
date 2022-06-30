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
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_STATE_CALLBACK;
import static android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;
import android.provider.ICloudMediaSurfaceController;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.LocalCallingIdentity;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;
import com.android.providers.media.photopicker.data.ExternalDbFacade;
import com.android.providers.media.photopicker.ui.remotepreview.RemotePreviewHandler;


import java.io.FileNotFoundException;

/**
 * Implements the {@link CloudMediaProvider} interface over the local items in the MediaProvider
 * database.
 */
public class PhotoPickerProvider extends CloudMediaProvider {
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
                queryExtras.getMimeTypes());
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

        return mDbFacade.queryAlbums(queryExtras.getMimeTypes());
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
        if (!RemotePreviewHandler.isRemotePreviewEnabled()) {
            return null;
        }

        // The config has all parameters except the |callback|, so marshall that into the config
        config.putBinder(EXTRA_SURFACE_STATE_CALLBACK, callback.getIBinder());
        // Add the local provider authority so the RemoteVideoPreviewProvider knows who to forward
        // URI requests to
        config.putString(EXTRA_AUTHORITY, PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);

        final Bundle bundle = getContext().getContentResolver().call(
                PickerUriResolver.createSurfaceControllerUri(RemoteVideoPreviewProvider.AUTHORITY),
                METHOD_CREATE_SURFACE_CONTROLLER, /* arg */ null, config);

        final IBinder binder = bundle.getBinder(EXTRA_SURFACE_CONTROLLER);
        if (binder == null) {
            throw new IllegalStateException("Surface controller not created");
        }
        return new RemoteVideoPreviewProvider.SurfaceControllerProxy(
                ICloudMediaSurfaceController.Stub.asInterface(binder));
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
