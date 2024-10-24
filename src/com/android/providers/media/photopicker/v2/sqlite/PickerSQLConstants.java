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

package com.android.providers.media.photopicker.v2.sqlite;

import static androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE;

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DURATION_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_MIME_TYPE;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_SIZE_BYTES;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION;

import static java.util.Objects.requireNonNull;

import android.provider.CloudMediaProviderContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Arrays;

/**
 * Helper class that keeps track of Picker related Constants.
 */
public class PickerSQLConstants {
    static final String COUNT_COLUMN = "Count";

    /**
     * An enum that holds the table names in Picker DB
     */
    public enum Table {
        MEDIA,
        ALBUM_MEDIA,
        SEARCH_REQUEST,
        SEARCH_RESULT_MEDIA
    }

    /**
     * An enum that holds the columns names for the Available Providers query response.
     */
    public enum AvailableProviderResponse {
        AUTHORITY("authority"),
        MEDIA_SOURCE("media_source"),
        UID("uid"),
        DISPLAY_NAME("display_name");

        private final String mColumnName;

        AvailableProviderResponse(String columnName) {
            this.mColumnName = columnName;
        }

        public String getColumnName() {
            return mColumnName;
        }
    }

    public enum CollectionInfoResponse {
        AUTHORITY("authority"),
        COLLECTION_ID("collection_id"),
        ACCOUNT_NAME("account_name");

        private final String mColumnName;

        CollectionInfoResponse(String columnName) {
            this.mColumnName = columnName;
        }

        public String getColumnName() {
            return mColumnName;
        }
    }

    /**
     * An enum that holds the DB columns names and projections for the Album SQL query response.
     */
    public enum AlbumResponse {
        ALBUM_ID(CloudMediaProviderContract.AlbumColumns.ID),
        PICKER_ID("picker_id"),
        AUTHORITY("authority"),
        DATE_TAKEN(CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS),
        ALBUM_NAME(CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME),
        UNWRAPPED_COVER_URI("unwrapped_cover_uri"),
        COVER_MEDIA_SOURCE("media_source");

        private final String mColumnName;

        AlbumResponse(@NonNull String columnName) {
            requireNonNull(columnName);
            this.mColumnName = columnName;
        }

        public String getColumnName() {
            return mColumnName;
        }
    }

    /**
     * @param columnName Input album column name.
     * @return Corresponding enum AlbumResponse to the given column name.
     * @throws IllegalArgumentException if the column name does not correspond to a AlbumResponse
     * enum.
     */
    public static AlbumResponse mapColumnNameToAlbumResponseColumn(String columnName)
            throws IllegalArgumentException {
        for (AlbumResponse albumResponseColumn : AlbumResponse.values()) {
            if (albumResponseColumn.getColumnName().equalsIgnoreCase(columnName)) {
                return albumResponseColumn;
            }
        }
        throw new IllegalArgumentException(columnName + " does not exist. Available data: "
                + Arrays.toString(PickerSQLConstants.AlbumResponse.values()));
    }

    /**
     * An enum that holds the DB columns names and projected names for the Media SQL query response.
     */
    public enum MediaResponse {
        MEDIA_ID(CloudMediaProviderContract.MediaColumns.ID),
        AUTHORITY(CloudMediaProviderContract.MediaColumns.AUTHORITY),
        MEDIA_SOURCE("media_source"),
        WRAPPED_URI("wrapped_uri"),
        UNWRAPPED_URI("unwrapped_uri"),
        PICKER_ID(KEY_ID, "picker_id"),
        DATE_TAKEN_MS(KEY_DATE_TAKEN_MS, CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS),
        SIZE_IN_BYTES(KEY_SIZE_BYTES, CloudMediaProviderContract.MediaColumns.SIZE_BYTES),
        MIME_TYPE(KEY_MIME_TYPE, CloudMediaProviderContract.MediaColumns.MIME_TYPE),
        STANDARD_MIME_TYPE(KEY_STANDARD_MIME_TYPE_EXTENSION,
                CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION),
        DURATION_MS(KEY_DURATION_MS, CloudMediaProviderContract.MediaColumns.DURATION_MILLIS),
        IS_PRE_GRANTED("is_pre_granted");

        @Nullable
        private final String mColumnName;
        @NonNull
        private final String mProjectedName;

        MediaResponse(@NonNull String dbColumnName, @NonNull String projectedName) {
            this.mColumnName = dbColumnName;
            this.mProjectedName = projectedName;
        }

        MediaResponse(@NonNull String projectedName) {
            this.mColumnName = null;
            this.mProjectedName = projectedName;
        }

        @Nullable
        public String getColumnName() {
            return mColumnName;
        }

        @NonNull
        public String getProjectedName() {
            return mProjectedName;
        }
    }

    public enum MediaResponseExtras {
        PREV_PAGE_ID("prev_page_picker_id"),
        PREV_PAGE_DATE_TAKEN("prev_page_date_taken"),
        NEXT_PAGE_ID("next_page_picker_id"),
        NEXT_PAGE_DATE_TAKEN("next_page_date_taken"),
        ITEMS_BEFORE_COUNT("items_before_count");

        private final String mKey;

        MediaResponseExtras(String key) {
            mKey = key;
        }

        public String getKey() {
            return mKey;
        }
    }

    public enum SearchRequestTableColumns {
        SEARCH_REQUEST_ID("_id"),
        SYNC_RESUME_KEY("sync_resume_key"),
        SEARCH_TEXT("search_text"),
        MEDIA_SET_ID("media_set_id"),
        SUGGESTION_TYPE("suggestion_type"),
        AUTHORITY("authority"),
        MIME_TYPES("mime_types");

        private final String mColumnName;

        SearchRequestTableColumns(@NonNull String columnName) {
            mColumnName = columnName;
        }

        public String getColumnName() {
            return mColumnName;
        }
    }

    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    public enum SearchResultMediaTableColumns {
        PICKER_ID("_id"),
        SEARCH_REQUEST_ID("search_request_id"),
        LOCAL_ID("local_id"),
        CLOUD_ID("cloud_id");

        private final String mColumnName;

        SearchResultMediaTableColumns(@NonNull String columnName) {
            mColumnName = columnName;
        }

        public String getColumnName() {
            return mColumnName;
        }
    }
}
