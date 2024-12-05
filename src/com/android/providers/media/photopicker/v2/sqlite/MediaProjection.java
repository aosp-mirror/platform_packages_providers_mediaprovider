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

import static com.android.providers.media.PickerUriResolver.getPickerSegmentFromIntentAction;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaGrants;
import com.android.providers.media.photopicker.v2.PickerDataLayerV2;
import com.android.providers.media.photopicker.v2.model.MediaSource;

import java.util.List;
import java.util.Locale;

/**
 * Utility class to generate and return sql projection for {@link PickerSQLConstants.MediaResponse}.
 * {@link PickerSQLConstants.MediaResponse} contains the columns returned in the response of
 * {@link PickerDataLayerV2#queryMedia(Context, Bundle)},
 * {@link PickerDataLayerV2#queryAlbumMedia(Context, Bundle, String)} and
 * {@link PickerDataLayerV2#queryPreviewMedia(Context, Bundle)}
 */
public class MediaProjection {
    @Nullable
    private final String mLocalAuthority;
    @Nullable
    private final String mCloudAuthority;
    @Nullable
    private final String mIntentAction;
    @Nullable
    private final PickerSQLConstants.Table mTableName;
    private static final String DEFAULT_PROJECTION = "%s AS %s";

    public MediaProjection(
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            @Nullable String intentAction,
            @Nullable PickerSQLConstants.Table tableName) {
        mLocalAuthority = localAuthority;
        mCloudAuthority = cloudAuthority;
        mIntentAction = intentAction;
        mTableName = tableName;
    }

    /**
     * Returns a list of all media response sql projections for the given media tables.
     */
    public List<String> getAll() {
        return List.of(
                get(PickerSQLConstants.MediaResponse.MEDIA_ID),
                get(PickerSQLConstants.MediaResponse.PICKER_ID),
                get(PickerSQLConstants.MediaResponse.AUTHORITY),
                get(PickerSQLConstants.MediaResponse.MEDIA_SOURCE),
                get(PickerSQLConstants.MediaResponse.WRAPPED_URI),
                get(PickerSQLConstants.MediaResponse.UNWRAPPED_URI),
                get(PickerSQLConstants.MediaResponse.DATE_TAKEN_MS),
                get(PickerSQLConstants.MediaResponse.SIZE_IN_BYTES),
                get(PickerSQLConstants.MediaResponse.MIME_TYPE),
                get(PickerSQLConstants.MediaResponse.STANDARD_MIME_TYPE),
                get(PickerSQLConstants.MediaResponse.DURATION_MS),
                get(PickerSQLConstants.MediaResponse.IS_PRE_GRANTED)
        );
    }

    /**
     * Returns the sql projection on media tables for a given media response column.
     */
    public String get(@NonNull PickerSQLConstants.MediaResponse mediaResponseColumn) {
        requireNonNull(mediaResponseColumn);

        switch (mediaResponseColumn) {
            case MEDIA_ID:
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        getMediaId(),
                        mediaResponseColumn.getProjectedName());
            case MEDIA_SOURCE:
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        getMediaSource(),
                        mediaResponseColumn.getProjectedName());
            case WRAPPED_URI:
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        getWrappedUri(mLocalAuthority, mCloudAuthority, mIntentAction),
                        mediaResponseColumn.getProjectedName());
            case AUTHORITY:
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        getAuthority(mLocalAuthority, mCloudAuthority),
                        mediaResponseColumn.getProjectedName());
            case UNWRAPPED_URI:
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        getUnwrappedUri(mLocalAuthority, mCloudAuthority),
                        mediaResponseColumn.getProjectedName());
            case IS_PRE_GRANTED:
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        getIsPregranted(mIntentAction),
                        mediaResponseColumn.getProjectedName());
            default:
                if (mediaResponseColumn.getColumnName() == null) {
                    throw new IllegalArgumentException(
                            "Could not get projection for " + mediaResponseColumn.name()
                    );
                }
                return String.format(
                        Locale.ROOT,
                        DEFAULT_PROJECTION,
                        prependTableName(mTableName, mediaResponseColumn.getColumnName()),
                        mediaResponseColumn.getProjectedName());
        }
    }


    private String getMediaId() {
        return String.format(
                Locale.ROOT,
                "IFNULL(%s, %s)",
                getCloudIdColumn(),
                getLocalIdColumn()
        );
    }

    private String getMediaSource() {
        return String.format(
                Locale.ROOT,
                "CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END",
                getCloudIdColumn(),
                MediaSource.LOCAL,
                MediaSource.REMOTE
        );
    }

    private String getAuthority(
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        return String.format(
                Locale.ROOT,
                "CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END",
                getCloudIdColumn(),
                localAuthority,
                cloudAuthority
        );
    }

    private String getWrappedUri(
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            @Nullable String intentAction
    ) {
        // The format is:
        // content://media/picker/<user-id>/<cloud-provider-authority>/media/<media-id>
        return String.format(
                Locale.ROOT,
                "'content://%s/%s/%s/' || %s || '/media/' || %s",
                MediaStore.AUTHORITY,
                getPickerSegmentFromIntentAction(intentAction),
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
                Locale.ROOT,
                "'content://%s@' || %s || '/media/' || %s",
                MediaStore.MY_USER_ID,
                getAuthority(localAuthority, cloudAuthority),
                getMediaId()
        );
    }

    private String getIsPregranted(String intentAction) {
        if (MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(intentAction)) {
            return String.format(
                    Locale.ROOT, "CASE WHEN %s.%s IS NOT NULL THEN 1 ELSE 0 END",
                    PickerDataLayerV2.CURRENT_GRANTS_TABLE, MediaGrants.FILE_ID_COLUMN);
        } else {
            return "0"; // default case for other intent actions
        }
    }

    private String getCloudIdColumn() {
        return prependTableName(mTableName, KEY_CLOUD_ID);
    }

    private String getLocalIdColumn() {
        return prependTableName(mTableName, KEY_LOCAL_ID);
    }

    /**
     * Prepend the in table name to the given column for sql statements and return the
     * resultant string.
     */
    public static String prependTableName(
            @NonNull PickerSQLConstants.Table table,
            @NonNull String columnName) {
        if (table == null) {
            return columnName;
        } else {
            return String.format(Locale.ROOT, "%s.%s", table.name(), columnName);
        }
    }
}
