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
import android.provider.MediaStore;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.AlbumColumns;

import java.util.Objects;

/**
 * Represents the {@link CloudMediaProviderContract} extra filters from a {@link Bundle}.
 */
public class CloudProviderQueryExtras {
    private final String mAlbumId;
    private final String mAlbumType;
    private final String mMimeType;
    private final String mCloudProvider;
    private final long mSizeBytes;
    private final long mGeneration;
    private final int mLimit;
    private final boolean mIsFavorite;

    private CloudProviderQueryExtras() {
        mAlbumId = STRING_DEFAULT;
        mAlbumType = STRING_DEFAULT;
        mMimeType = STRING_DEFAULT;
        mCloudProvider = STRING_DEFAULT;
        mSizeBytes = LONG_DEFAULT;
        mGeneration = LONG_DEFAULT;
        mLimit = LIMIT_DEFAULT;
        mIsFavorite = BOOLEAN_DEFAULT;
    }

    private CloudProviderQueryExtras (String albumId, String albumType, String mimeType,
            String cloudProvider, long sizeBytes, long generation, int limit, boolean isFavorite) {
        mAlbumId = albumId;
        mAlbumType = albumType;
        mMimeType = mimeType;
        mCloudProvider = cloudProvider;
        mSizeBytes = sizeBytes;
        mGeneration = generation;
        mLimit = limit;
        mIsFavorite = isFavorite;
    }

    public static CloudProviderQueryExtras fromMediaStoreBundle(Bundle bundle) {
        if (bundle == null) {
            return new CloudProviderQueryExtras();
        }

        final String albumId = bundle.getString(MediaStore.QUERY_ARG_ALBUM_ID, STRING_DEFAULT);
        final String albumType = bundle.getString(MediaStore.QUERY_ARG_ALBUM_TYPE, STRING_DEFAULT);
        final String mimeType = bundle.getString(MediaStore.QUERY_ARG_MIME_TYPE, STRING_DEFAULT);
        final String cloudProvider = bundle.getString(MediaStore.EXTRA_CLOUD_PROVIDER,
                STRING_DEFAULT);

        final long sizeBytes = bundle.getLong(MediaStore.QUERY_ARG_SIZE_BYTES, LONG_DEFAULT);
        final long generation = LONG_DEFAULT;
        final int limit = bundle.getInt(MediaStore.QUERY_ARG_LIMIT, LIMIT_DEFAULT);

        final boolean isFavorite = AlbumColumns.TYPE_FAVORITES.equals(albumType);

        return new CloudProviderQueryExtras(albumId, albumType, mimeType, cloudProvider, sizeBytes,
                generation, limit, isFavorite);
    }

    public static CloudProviderQueryExtras fromCloudMediaBundle(Bundle bundle) {
        if (bundle == null) {
            return new CloudProviderQueryExtras();
        }

        final String albumId = bundle.getString(CloudMediaProviderContract.EXTRA_FILTER_ALBUM,
                STRING_DEFAULT);
        final String albumType = STRING_DEFAULT;
        final String mimeType = bundle.getString(CloudMediaProviderContract.EXTRA_FILTER_MIMETYPE,
                STRING_DEFAULT);
        final String cloudProvider = STRING_DEFAULT;

        final long sizeBytes = bundle.getLong(CloudMediaProviderContract.EXTRA_FILTER_SIZE_BYTES,
                LONG_DEFAULT);
        final long generation = bundle.getLong(CloudMediaProviderContract.EXTRA_GENERATION,
                LONG_DEFAULT);
        final int limit = LIMIT_DEFAULT;

        final boolean isFavorite = BOOLEAN_DEFAULT;

        return new CloudProviderQueryExtras(albumId, albumType, mimeType, cloudProvider, sizeBytes,
                generation, limit, isFavorite);
    }

    public PickerDbFacade.QueryFilter toQueryFilter() {
        PickerDbFacade.QueryFilterBuilder qfb = new PickerDbFacade.QueryFilterBuilder(mLimit);
        qfb.setSizeBytes(mSizeBytes);
        qfb.setMimeType(mMimeType);
        qfb.setIsFavorite(mIsFavorite);
        return qfb.build();
    }

    public Bundle toCloudMediaBundle() {
        final Bundle extras = new Bundle();
        extras.putString(CloudMediaProviderContract.EXTRA_FILTER_ALBUM, mAlbumId);
        extras.putString(CloudMediaProviderContract.EXTRA_FILTER_MIMETYPE, mMimeType);
        extras.putLong(CloudMediaProviderContract.EXTRA_FILTER_SIZE_BYTES, mSizeBytes);

        return extras;
    }

    public String getAlbumId() {
        return mAlbumId;
    }

    public String getAlbumType() {
        return mAlbumType;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public String getCloudProvider() {
        return mCloudProvider;
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
}
