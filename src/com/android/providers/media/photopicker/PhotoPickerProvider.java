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

import static android.provider.CloudMediaProviderContract.MediaInfo;

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

import com.android.providers.media.MediaProvider;
import com.android.providers.media.photopicker.data.ExternalDbFacadeForPicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;

/**
 * Implements the {@link CloudMediaProvider} interface over the local items in the MediaProvider
 * database.
 */
public class PhotoPickerProvider extends CloudMediaProvider {
    private static final String TAG = "PhotoPickerProvider";

    private MediaProvider mMediaProvider;
    private ExternalDbFacadeForPicker mDbFacade;

    @Override
    public boolean onCreate() {
        mMediaProvider = getMediaProvider();
        mDbFacade = new ExternalDbFacadeForPicker(mMediaProvider.getExternalDatabaseHelper());
        return true;
    }

    @Override
    public Cursor onQueryMedia(@NonNull String mediaId) {
        return mDbFacade.queryMediaId(Long.parseLong(mediaId));
    }

    @Override
    public Cursor onQueryMedia(@Nullable Bundle extras) {
        // TODO(b/190713331): Handle extra_filter_albums, and extra_page
        return mDbFacade.queryMediaGeneration(getGeneration(extras));
    }

    @Override
    public Cursor onQueryDeletedMedia(@Nullable Bundle extras) {
        return mDbFacade.queryDeletedMedia(getGeneration(extras));
    }

    @Override
    public AssetFileDescriptor onOpenThumbnail(@NonNull String mediaId, @NonNull Point size,
            @NonNull CancellationSignal signal) throws FileNotFoundException {
        Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, size);
        return mMediaProvider.openTypedAssetFile(fromMediaId(mediaId), "image/*", opts);
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(@NonNull String mediaId,
            @NonNull CancellationSignal signal)
            throws FileNotFoundException {
        return mMediaProvider.openFile(fromMediaId(mediaId), "r");
    }

    @Override
    public Bundle onGetMediaInfo(@Nullable Bundle extras) {
        // TODO(b/190713331): Handle extra_filter_albums
        Bundle bundle = new Bundle();
        try (Cursor cursor = mDbFacade.getMediaInfo(getGeneration(extras))) {
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

    private static long getGeneration(@Nullable Bundle extras) {
        return extras == null ? 0 : extras.getLong(MediaInfo.MEDIA_GENERATION, 0);
    }
}
