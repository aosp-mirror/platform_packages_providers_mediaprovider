/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.photopicker.v2.model;

import static java.util.Objects.requireNonNull;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.v2.PickerSQLConstants;

import java.util.List;

/**
 * A wrapper for Albums cursor to map a value from the cursor received from CMP to the value in the
 * projected value in the response.
 */
public class AlbumsCursorWrapper extends CursorWrapper {
    private static final String TAG = "AlbumsCursorWrapper";
    // Local albums predefined order they should be displayed in. They always need to be
    // displayed above the cloud albums too. The sort order is DESC(date_taken, picker_id).
    private static final List<String> localAlbumsOrder = List.of(
            CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
            CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
            CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
            CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS,
            CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS
    );

    @NonNull final String mCoverAuthority;
    @NonNull final String mLocalAuthority;

    public AlbumsCursorWrapper(
            @NonNull Cursor cursor,
            @NonNull String authority,
            @NonNull String localAuthority) {
        super(requireNonNull(cursor));
        mCoverAuthority = requireNonNull(authority);
        mLocalAuthority = requireNonNull(localAuthority);
    }

    @Override
    public int getColumnCount() {
        return PickerSQLConstants.AlbumResponse.values().length;
    }

    @Override
    public int getColumnIndex(String columnName) {
        try {
            return getColumnIndexOrThrow(columnName);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Column not present in cursor." + e);
            return -1;
        }
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException {
        return PickerSQLConstants.mapColumnNameToAlbumResponseColumn(columnName).ordinal();
    }

    @Override
    public String getColumnName(int columnIndex) {
        return PickerSQLConstants.AlbumResponse.values()[columnIndex].getColumnName();
    }

    @Override
    public String[] getColumnNames() {
        String[] columnNames = new String[PickerSQLConstants.AlbumResponse.values().length];
        for (int iterator = 0;
                iterator < PickerSQLConstants.AlbumResponse.values().length;
                iterator++) {
            columnNames[iterator] = PickerSQLConstants.AlbumResponse.values()[iterator]
                    .getColumnName();
        }
        return columnNames;
    }

    @Override
    public long getLong(int columnIndex) {
        return Long.parseLong(getString(columnIndex));
    }

    @Override
    public int getInt(int columnIndex) {
        return Integer.parseInt(getString(columnIndex));
    }

    @Override
    public String getString(int columnIndex) {
        final String columnName = getColumnName(columnIndex);
        final PickerSQLConstants.AlbumResponse albumResponse =
                PickerSQLConstants.mapColumnNameToAlbumResponseColumn(columnName);
        final String albumId = getWrappedCursor().getString(
                getWrappedCursor().getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.ALBUM_ID.getColumnName()));

        switch (albumResponse) {
            case AUTHORITY:
                return mCoverAuthority;

            case UNWRAPPED_COVER_URI:
                // TODO(b/317118334): Use local copy of the cover image when available.
                return PickerUriResolver.getMediaUri(mCoverAuthority)
                        .buildUpon()
                        .appendPath(getMediaIdFromWrappedCursor())
                        .build()
                        .toString();

            case PICKER_ID:
                if (localAlbumsOrder.contains(albumId)) {
                    return Integer.toString(
                            Integer.MAX_VALUE - localAlbumsOrder.indexOf(columnName)
                    );
                } else {
                    return Integer.toString(getMediaIdFromWrappedCursor().hashCode());
                }

            case COVER_MEDIA_SOURCE:
                if (mLocalAuthority.equals(mCoverAuthority)) {
                    return MediaSource.LOCAL.toString();
                } else {
                    return MediaSource.REMOTE.toString();
                }

            case ALBUM_ID:
                return albumId;

            case DATE_TAKEN:
                if (localAlbumsOrder.contains(albumId)) {
                    return Long.toString(Long.MAX_VALUE);
                }
                // Fall through to return the wrapped cursor value as it is.
            case ALBUM_NAME:
            default:
                // These values must be present in the cursor received from CMP. Note that this
                // works because the column names in the returned cursor is the same as the column
                // name received from the CMP.
                return getWrappedCursor().getString(
                        getWrappedCursor().getColumnIndexOrThrow(columnName)
                );
        }
    }

    @Override
    public int getType(int columnIndex) {
        final String columnName = getColumnName(columnIndex);
        final PickerSQLConstants.AlbumResponse albumResponse =
                PickerSQLConstants.mapColumnNameToAlbumResponseColumn(columnName);


        switch (albumResponse) {
            case AUTHORITY:
            case UNWRAPPED_COVER_URI:
            case COVER_MEDIA_SOURCE:
                return FIELD_TYPE_STRING;

            case PICKER_ID:
                return FIELD_TYPE_INTEGER;

            case DATE_TAKEN:
            case ALBUM_ID:
            case ALBUM_NAME:
            default:
                // These values must be present in the cursor received from CMP. Note that this
                // works because the column names in the returned cursor is the same as the column
                // name received from the CMP.
                return getWrappedCursor().getType(
                        getWrappedCursor().getColumnIndexOrThrow(columnName)
                );
        }
    }

    /**
     * Extract and return the cover media id from the wrapped cursor.
     */
    private String getMediaIdFromWrappedCursor() {
        final String mediaId = getWrappedCursor().getString(
                getWrappedCursor().getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID)
        );
        requireNonNull(mediaId);
        return mediaId;
    }
}
