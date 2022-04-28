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

import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.BOOLEAN_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LIMIT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.MediaStore;

/**
 * Represents the {@link CloudMediaProviderContract} extra filters from a {@link Bundle}.
 */
public class CloudProviderQueryExtras {
    private final String mAlbumId;
    private final String mAlbumAuthority;
    private final String mMimeType;
    private final long mSizeBytes;
    private final long mGeneration;
    private final int mLimit;
    private final boolean mIsFavorite;
    private final boolean mIsVideo;

    private CloudProviderQueryExtras() {
        mAlbumId = STRING_DEFAULT;
        mAlbumAuthority = STRING_DEFAULT;
        mMimeType = STRING_DEFAULT;
        mSizeBytes = LONG_DEFAULT;
        mGeneration = LONG_DEFAULT;
        mLimit = LIMIT_DEFAULT;
        mIsFavorite = BOOLEAN_DEFAULT;
        mIsVideo = BOOLEAN_DEFAULT;
    }

    private CloudProviderQueryExtras (String albumId, String albumAuthority, String mimeType,
            long sizeBytes, long generation, int limit, boolean isFavorite, boolean isVideo) {
        mAlbumId = albumId;
        mAlbumAuthority = albumAuthority;
        mMimeType = mimeType;
        mSizeBytes = sizeBytes;
        mGeneration = generation;
        mLimit = limit;
        mIsFavorite = isFavorite;
        mIsVideo = isVideo;
    }

    public static CloudProviderQueryExtras fromMediaStoreBundle(Bundle bundle,
            String localProvider) {
        if (bundle == null) {
            return new CloudProviderQueryExtras();
        }

        final String albumId = bundle.getString(MediaStore.QUERY_ARG_ALBUM_ID, STRING_DEFAULT);
        final String albumAuthority = bundle.getString(MediaStore.QUERY_ARG_ALBUM_AUTHORITY,
                STRING_DEFAULT);
        final String mimeType = bundle.getString(MediaStore.QUERY_ARG_MIME_TYPE, STRING_DEFAULT);

        final long sizeBytes = bundle.getLong(MediaStore.QUERY_ARG_SIZE_BYTES, LONG_DEFAULT);
        final long generation = LONG_DEFAULT;
        final int limit = bundle.getInt(MediaStore.QUERY_ARG_LIMIT, LIMIT_DEFAULT);

        final boolean isFavorite = localProvider.equals(albumAuthority)
                && AlbumColumns.ALBUM_ID_FAVORITES.equals(albumId);
        final boolean isVideo = localProvider.equals(albumAuthority)
                && AlbumColumns.ALBUM_ID_VIDEOS.equals(albumId);

        return new CloudProviderQueryExtras(albumId, albumAuthority, mimeType, sizeBytes,
                generation, limit, isFavorite, isVideo);
    }

    public static CloudProviderQueryExtras fromCloudMediaBundle(Bundle bundle) {
        if (bundle == null) {
            return new CloudProviderQueryExtras();
        }

        final String albumId = bundle.getString(CloudMediaProviderContract.EXTRA_ALBUM_ID,
                STRING_DEFAULT);
        final String albumAuthority = STRING_DEFAULT;
        final String mimeType = bundle.getString(CloudMediaProviderContract.EXTRA_MIME_TYPE,
                STRING_DEFAULT);
        final long sizeBytes = bundle.getLong(CloudMediaProviderContract.EXTRA_SIZE_LIMIT_BYTES,
                LONG_DEFAULT);
        final long generation = bundle.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION,
                LONG_DEFAULT);
        final int limit = LIMIT_DEFAULT;

        final boolean isFavorite = BOOLEAN_DEFAULT;
        final boolean isVideo = BOOLEAN_DEFAULT;

        return new CloudProviderQueryExtras(albumId, albumAuthority, mimeType, sizeBytes,
                generation, limit, isFavorite, isVideo);
    }

    public PickerDbFacade.QueryFilter toQueryFilter() {
        PickerDbFacade.QueryFilterBuilder qfb = new PickerDbFacade.QueryFilterBuilder(mLimit);
        qfb.setSizeBytes(mSizeBytes);
        qfb.setMimeType(mMimeType);
        qfb.setIsFavorite(mIsFavorite);
        qfb.setIsVideo(mIsVideo);
        qfb.setAlbumId(mAlbumId);
        return qfb.build();
    }

    public Bundle toCloudMediaBundle() {
        final Bundle extras = new Bundle();
        extras.putString(CloudMediaProviderContract.EXTRA_ALBUM_ID, mAlbumId);
        extras.putString(CloudMediaProviderContract.EXTRA_MIME_TYPE, mMimeType);
        extras.putLong(CloudMediaProviderContract.EXTRA_SIZE_LIMIT_BYTES, mSizeBytes);

        return extras;
    }

    public String getAlbumId() {
        return mAlbumId;
    }

    public String getAlbumAuthority() {
        return mAlbumAuthority;
    }

    public String getMimeType() {
        return mMimeType;
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
}
