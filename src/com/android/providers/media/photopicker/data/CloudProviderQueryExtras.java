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

import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_DATE_TAKEN_BEFORE_MS;
import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_LOCAL_ID_SELECTION;
import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_ROW_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.BOOLEAN_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.INT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LIMIT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LIST_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_ARRAY_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.content.Intent;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.MediaStore;

import com.android.providers.media.photopicker.PickerDataLayer;

import java.util.List;

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
    private final int mPageSize;
    private final long mDateTakenBeforeMs;
    private final int mRowId;

    private final List<Integer> mLocalIdSelection;

    private String mPageToken;

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
        mPageSize = INT_DEFAULT;
        mDateTakenBeforeMs = Long.MIN_VALUE;
        mRowId = INT_DEFAULT;
        mLocalIdSelection = LIST_DEFAULT;
        mPageToken = STRING_DEFAULT;
    }

    private CloudProviderQueryExtras(String albumId, String albumAuthority, String[] mimeTypes,
            long sizeBytes, long generation, int limit, boolean isFavorite, boolean isVideo,
            boolean isLocalOnly, int pageSize, long dateTakenBeforeMs, int rowId,
            List<Integer> localIdSelection, String pageToken) {
        mAlbumId = albumId;
        mAlbumAuthority = albumAuthority;
        mMimeTypes = mimeTypes;
        mSizeBytes = sizeBytes;
        mGeneration = generation;
        mLimit = limit;
        mIsFavorite = isFavorite;
        mIsVideo = isVideo;
        mIsLocalOnly = isLocalOnly;
        mPageSize = pageSize;
        mDateTakenBeforeMs = dateTakenBeforeMs;
        mRowId = rowId;
        mLocalIdSelection = localIdSelection;
        mPageToken = pageToken;
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

        final boolean isFavorite = isFavorite(albumId);
        final boolean isVideo = isVideo(albumId);

        final boolean isLocalOnly = bundle.getBoolean(PickerDataLayer.QUERY_ARG_LOCAL_ONLY,
                BOOLEAN_DEFAULT);
        final int pageSize = INT_DEFAULT;
        final long dateTakenBeforeMs = bundle.getLong(QUERY_DATE_TAKEN_BEFORE_MS, Long.MIN_VALUE);
        final int rowId = bundle.getInt(QUERY_ROW_ID, INT_DEFAULT);
        final List<Integer> localIdSelection = bundle.getIntegerArrayList(QUERY_LOCAL_ID_SELECTION);
        final String pageToken = bundle.getString(
                CloudMediaProviderContract.EXTRA_PAGE_TOKEN, STRING_DEFAULT);

        return new CloudProviderQueryExtras(albumId, albumAuthority, mimeTypes, sizeBytes,
                generation, limit, isFavorite, isVideo, isLocalOnly, pageSize, dateTakenBeforeMs,
                rowId, localIdSelection, pageToken);
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
        final long dateTakenBeforeMs = bundle.getLong(QUERY_DATE_TAKEN_BEFORE_MS, Long.MIN_VALUE);
        final int rowId = bundle.getInt(QUERY_ROW_ID, INT_DEFAULT);

        final int pageSize = bundle.getInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, INT_DEFAULT);
        final List<Integer> localIdSelection = bundle.getIntegerArrayList(QUERY_LOCAL_ID_SELECTION);
        final String pageToken = bundle.getString(
                CloudMediaProviderContract.EXTRA_PAGE_TOKEN, STRING_DEFAULT);

        return new CloudProviderQueryExtras(albumId, albumAuthority, mimeTypes, sizeBytes,
                generation, limit, isFavorite, isVideo, isLocalOnly, pageSize, dateTakenBeforeMs,
                rowId, localIdSelection, pageToken);
    }

    public PickerDbFacade.QueryFilter toQueryFilter() {
        PickerDbFacade.QueryFilterBuilder qfb = new PickerDbFacade.QueryFilterBuilder(mLimit);
        qfb.setSizeBytes(mSizeBytes);
        qfb.setMimeTypes(mMimeTypes);
        qfb.setIsFavorite(mIsFavorite);
        qfb.setIsVideo(mIsVideo);
        qfb.setAlbumId(mAlbumId);
        qfb.setIsLocalOnly(mIsLocalOnly);
        qfb.setDateTakenBeforeMs(mDateTakenBeforeMs);
        qfb.setId(mRowId);
        qfb.setLocalIdSelection(mLocalIdSelection);
        qfb.setPageSize(mPageSize);
        qfb.setPageToken(mPageToken);
        return qfb.build();
    }

    public Bundle toCloudMediaBundle() {
        final Bundle extras = new Bundle();
        extras.putString(CloudMediaProviderContract.EXTRA_ALBUM_ID, mAlbumId);
        extras.putStringArray(Intent.EXTRA_MIME_TYPES, mMimeTypes);
        extras.putLong(CloudMediaProviderContract.EXTRA_SIZE_LIMIT_BYTES, mSizeBytes);

        return extras;
    }

    /**
     * Checks if the query is for a merged album type.
     */
    public boolean isMergedAlbum() {
        return mIsFavorite || mIsVideo;
    }

    private static boolean isFavorite(String albumId) {
        return AlbumColumns.ALBUM_ID_FAVORITES.equals(albumId);
    }

    private static boolean isVideo(String albumId) {
        return AlbumColumns.ALBUM_ID_VIDEOS.equals(albumId);
    }

    /**
     * Checks if the given albumID belongs to a merged album type.
     */
    public static boolean isMergedAlbum(String albumId) {
        return isFavorite(albumId) || isVideo(albumId);
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

    public int getPageSize() {
        return mPageSize;
    }

    public String getPageToken() {
        return mPageToken;
    }
}
