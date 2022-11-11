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

package com.android.providers.media.cloudproviders;

import static com.android.providers.media.PickerProviderMediaGenerator.MediaGenerator;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;

import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;

import java.io.FileNotFoundException;

/**
 * Implements a cloud {@link CloudMediaProvider} interface over items generated with
 * {@link MediaGenerator}
 */
public class CloudProviderPrimary extends CloudMediaProvider {
    public static final String AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_primary";

    private final MediaGenerator mMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(AUTHORITY);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor onQueryMedia(Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mMediaGenerator.getMedia(queryExtras.getGeneration(), queryExtras.getAlbumId(),
                queryExtras.getMimeTypes(), queryExtras.getSizeBytes());
    }

    @Override
    public Cursor onQueryDeletedMedia(Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mMediaGenerator.getDeletedMedia(queryExtras.getGeneration());
    }

    @Override
    public Cursor onQueryAlbums(Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mMediaGenerator.getAlbums(queryExtras.getMimeTypes(), queryExtras.getSizeBytes(),
                /* isLocal */ false);
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
        return mMediaGenerator.getMediaCollectionInfo();
    }
}
