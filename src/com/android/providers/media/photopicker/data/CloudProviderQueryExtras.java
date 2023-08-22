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
package com.android.providers.media.photopicker.data;

import static android.content.ContentResolver.QUERY_ARG_LIMIT;

import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.BOOLEAN_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LIMIT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_ARRAY_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.content.Intent;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.MediaStore;

import com.android.providers.media.photopicker.PickerDataLayer;

/**
 * Represents the {@link CloudMediaProviderContract} extra filters from a {@link Bundle}.
 */
public class CloudProviderQueryExtras {
    private final String mAlbumId;
    private final String mAlbumAuthority;
    private final String[] mMimeTypes;
    private final long mSizeBytes;
    private final long mGeneration;
    private final int mLimit;
    private final boolean mIsFavorite;
    private final boolean mIsVideo;
    private final boolean mIsLocalOnly;

    private CloudProviderQueryExtras() {
        mAlbumId = STRING_DEFAULT;
        mAlbumAuthority = STRING_DEFAULT;
        mMimeTypes = STRING_ARRAY_DEFAULT;
        mSizeBytes = LONG_DEFAULT;
        mGeneration = LONG_DEFAULT;
        mLimit = LIMIT_DEFAULT;
        mIsFavorite = BOOLEAN_DEFAULT;
        mIsVideo = BOOLEAN_DEFAULT;
        mIsLocalOnly = BOOLEAN_DEFAULT;
    }

    private CloudProviderQueryExtras(String albumId, String albumAuthority, String[] mimeTypes,
            long sizeBytes, long generation, int limit, boolean isFavorite, boolean isVideo,
            boolean isLocalOnly) {
        mAlbumId = albumId;
        mAlbumAuthority = albumAuthority;
        mMimeTypes = mimeTypes;
        mSizeBytes = sizeBytes;
        mGeneration = generation;
        mLimit = limit;
        mIsFavorite = isFavorite;
        mIsVideo = isVideo;
        mIsLocalOnly = isLocalOnly;
    }

    /**
     * Builds {@link CloudProviderQueryExtras} from {@link Bundle} queryExtras from MediaProvider
     */
    public static CloudProviderQueryExtras fromMediaStoreBundle(Bundle bundle) {
        if (bundle == null) {
            return new CloudProviderQueryExtras();
        }

        final String albumId = bundle.getString(MediaStore.QUERY_ARG_ALBUM_ID, STRING_DEFAULT);
        final String albumAuthority = bundle.getString(MediaStore.QUERY_ARG_ALBUM_AUTHORITY,
                STRING_DEFAULT);
        final String[] mimeTypes = bundle.getStringArray(MediaStore.QUERY_ARG_MIME_TYPE);

        final long sizeBytes = bundle.getLong(MediaStore.QUERY_ARG_SIZE_BYTES, LONG_DEFAULT);
        final long generation = LONG_DEFAULT;
        final int limit = bundle.getInt(QUERY_ARG_LIMIT, LIMIT_DEFAULT);

        final boolean isFavorite = AlbumColumns.ALBUM_ID_FAVORITES.equals(albumId);
        final boolean isVideo = AlbumColumns.ALBUM_ID_VIDEOS.equals(albumId);

        final boolean isLocalOnly = bundle.getBoolean(PickerDataLayer.QUERY_ARG_LOCAL_ONLY,
                BOOLEAN_DEFAULT);

        return new CloudProviderQueryExtras(albumId, albumAuthority, mimeTypes, sizeBytes,
                generation, limit, isFavorite, isVideo, isLocalOnly);
    }

    public static CloudProviderQueryExtras fromCloudMediaBundle(Bundle bundle) {
        if (bundle == null) {
            return new CloudProviderQueryExtras();
        }

        final String albumId = bundle.getString(CloudMediaProviderContract.EXTRA_ALBUM_ID,
                STRING_DEFAULT);
        final String albumAuthority = STRING_DEFAULT;
        final String[] mimeTypes = bundle.getStringArray(
                Intent.EXTRA_MIME_TYPES);
        final long sizeBytes = bundle.getLong(CloudMediaProviderContract.EXTRA_SIZE_LIMIT_BYTES,
                LONG_DEFAULT);
        final long generation = bundle.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION,
                LONG_DEFAULT);
        final int limit = LIMIT_DEFAULT;

        final boolean isFavorite = BOOLEAN_DEFAULT;
        final boolean isVideo = BOOLEAN_DEFAULT;
        final boolean isLocalOnly = BOOLEAN_DEFAULT;

        return new CloudProviderQueryExtras(albumId, albumAuthority, mimeTypes, sizeBytes,
                generation, limit, isFavorite, isVideo, isLocalOnly);
    }

    public PickerDbFacade.QueryFilter toQueryFilter() {
        PickerDbFacade.QueryFilterBuilder qfb = new PickerDbFacade.QueryFilterBuilder(mLimit);
        qfb.setSizeBytes(mSizeBytes);
        qfb.setMimeTypes(mMimeTypes);
        qfb.setIsFavorite(mIsFavorite);
        qfb.setIsVideo(mIsVideo);
        qfb.setAlbumId(mAlbumId);
        qfb.setIsLocalOnly(mIsLocalOnly);
        return qfb.build();
    }

    public Bundle toCloudMediaBundle() {
        final Bundle extras = new Bundle();
        extras.putString(CloudMediaProviderContract.EXTRA_ALBUM_ID, mAlbumId);
        extras.putStringArray(Intent.EXTRA_MIME_TYPES, mMimeTypes);
        extras.putLong(CloudMediaProviderContract.EXTRA_SIZE_LIMIT_BYTES, mSizeBytes);

        return extras;
    }

    public String getAlbumId() {
        return mAlbumId;
    }

    public String getAlbumAuthority() {
        return mAlbumAuthority;
    }

    public String[] getMimeTypes() {
        return mMimeTypes;
    }

    public long getSizeBytes() {
        return mSizeBytes;
    }

    public long getGeneration() {
        return mGeneration;
    }

    public boolean isFavorite() {
        return mIsFavorite;
    }

    public boolean isVideo() {
        return mIsVideo;
    }

    public boolean isLocalOnly() {
        return mIsLocalOnly;
    }
}
