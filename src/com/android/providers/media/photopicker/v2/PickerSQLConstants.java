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

package com.android.providers.media.photopicker.v2;

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DURATION_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_MIME_TYPE;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_SIZE_BYTES;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION;

import static java.util.Objects.requireNonNull;

import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.v2.model.MediaSource;

import java.util.Arrays;

/**
 * Helper class that keeps track of Picker related Constants.
 */
public class PickerSQLConstants {
    /**
     * An enum that holds the table names in Picker DB
     */
    enum Table {
        MEDIA,
        ALBUM_MEDIA,
    }

    /**
     * An enum that holds the columns names for the Available Providers query response.
     */
    enum AvailableProviderResponse {
        AUTHORITY("authority"),
        MEDIA_SOURCE("media_source"),
        UID("uid");

        private final String mColumnName;

        AvailableProviderResponse(String columnName) {
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
     * An enum that holds the DB columns names and projections for the Media SQL query response.
     */
    enum MediaResponse {
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
        DURATION_MS(KEY_DURATION_MS, CloudMediaProviderContract.MediaColumns.DURATION_MILLIS);

        private static final String DEFAULT_PROJECTION = "%s AS %s";
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

        @NonNull
        public String getProjection(
                @Nullable String localAuthority,
                @Nullable String cloudAuthority
        ) {
            switch (this) {
                case AUTHORITY:
                    return String.format(
                            DEFAULT_PROJECTION,
                            getAuthority(localAuthority, cloudAuthority),
                            mProjectedName
                    );
                case WRAPPED_URI:
                    return String.format(
                            DEFAULT_PROJECTION,
                            getWrappedUri(localAuthority, cloudAuthority),
                            mProjectedName
                    );
                case UNWRAPPED_URI:
                    return String.format(
                            DEFAULT_PROJECTION,
                            getUnwrappedUri(localAuthority, cloudAuthority),
                            mProjectedName
                    );
                default:
                    return getProjection();
            }
        }

        @NonNull
        public String getProjection() {
            switch (this) {
                case MEDIA_ID:
                    return String.format(
                            DEFAULT_PROJECTION,
                            getMediaId(),
                            mProjectedName
                    );
                case MEDIA_SOURCE:
                    return String.format(
                            DEFAULT_PROJECTION,
                            getMediaSource(),
                            mProjectedName
                    );
                default:
                    if (mColumnName == null) {
                        throw new IllegalArgumentException(
                                "Could not get projection for " + this.name()
                        );
                    }
                    return String.format(DEFAULT_PROJECTION, mColumnName, mProjectedName);
            }
        }

        private String getMediaId() {
            return String.format(
                    "IFNULL(%s, %s)",
                    KEY_CLOUD_ID,
                    KEY_LOCAL_ID
            );
        }

        private String getMediaSource() {
            return String.format(
                    "CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END",
                    KEY_CLOUD_ID,
                    MediaSource.LOCAL,
                    MediaSource.REMOTE
            );
        }

        private String getAuthority(
                @Nullable String localAuthority,
                @Nullable String cloudAuthority
        ) {
            return String.format(
                    "CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END",
                    KEY_CLOUD_ID,
                    localAuthority,
                    cloudAuthority
            );
        }

        private String getWrappedUri(
                @Nullable String localAuthority,
                @Nullable String cloudAuthority
        ) {
            // The format is:
            // content://media/picker/<user-id>/<cloud-provider-authority>/media/<media-id>
            return String.format(
                    "'content://%s/%s/%s/' || %s || '/media/' || %s",
                    MediaStore.AUTHORITY,
                    PickerUriResolver.PICKER_SEGMENT,
                    MediaStore.MY_USER_ID,
                    getAuthority(localAuthority, cloudAuthority),
                    getMediaId()
            );
        }

        private String getUnwrappedUri(
                @Nullable String localAuthority,
                @Nullable String cloudAuthority
        ) {
            // The format is:
            // content://<cloud-provider-authority>/media/<media-id>
            return String.format(
                    "'content://%s@' || %s || '/media/' || %s",
                    MediaStore.MY_USER_ID,
                    getAuthority(localAuthority, cloudAuthority),
                    getMediaId()
            );
        }
    }

    enum MediaResponseExtras {
        PREV_PAGE_ID("prev_page_picker_id"),
        PREV_PAGE_DATE_TAKEN("prev_page_date_taken"),
        NEXT_PAGE_ID("next_page_picker_id"),
        NEXT_PAGE_DATE_TAKEN("next_page_date_taken");

        private final String mKey;

        MediaResponseExtras(String key) {
            mKey = key;
        }

        public String getKey() {
            return mKey;
        }
    }
}
