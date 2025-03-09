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

import static android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;

import static com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil.addNextPageKey;
import static com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil.addPrevPageKey;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility class for insertion or querying the media items in various media sets
 */
public class MediaInMediaSetsDatabaseUtil {

    private static final String TAG = "MediaInMediaSetsDatabaseUtil";

    /**
     * Caches the metadata of a media item identified by the given mediaSet into the
     * media_in_media_sets table.
     *
     * @param database The database to insert into
     * @param mediaListToInsert The ContentValues list of the items to insert
     * @param authority Authority of the media set
     * @return The number of items inserted into the table
     */
    public static int cacheMediaOfMediaSet(
            @NonNull SQLiteDatabase database,
            @Nullable List<ContentValues> mediaListToInsert,
            @NonNull String authority) {

        Objects.requireNonNull(database);
        Objects.requireNonNull(authority);

        final boolean isLocal = PickerSyncController.getInstanceOrThrow()
                .getLocalProvider()
                .equals(authority);

        if (mediaListToInsert == null || mediaListToInsert.isEmpty()) {
            Log.e(TAG, "Received cursor is either null or empty. Nothing to insert.");
            return 0;
        }

        try {
            // Start a transaction with EXCLUSIVE lock.
            database.beginTransaction();

            // Number of rows inserted or replaced
            int numberOfMediaRowsInserted = 0;
            for (ContentValues mediaValues : mediaListToInsert) {
                try {
                    // Prefer media received from local provider over cloud provider to avoid
                    // joining with media table on cloud_id when not required.
                    final int conflictResolutionStrategy = isLocal
                            ? CONFLICT_REPLACE
                            : CONFLICT_IGNORE;
                    final long rowId = database.insertWithOnConflict(
                            PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name(),
                            null,
                            mediaValues,
                            conflictResolutionStrategy
                    );

                    if (rowId == -1) {
                        Log.d(TAG, "Did not insert row in the media_in_media_sets_table"
                                + " due to IGNORE conflict resolution strategy " + mediaValues);
                    } else {
                        numberOfMediaRowsInserted++;
                    }
                } catch (SQLException e) {
                    Log.e(TAG, "Could not insert row in the media_in_media_sets_table "
                            + mediaValues + " due to ", e);
                }
            }
            // Mark transaction as successful so that it gets committed after it ends.
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }
            return numberOfMediaRowsInserted;
        } catch (RuntimeException e) {
            // Do not mark transaction as successful so that it gets rolled back after it ends.
            throw new RuntimeException("Could not insert items in the database", e);
        } finally {
            // Mark transaction as ended. The inserted items will either be committed if the
            // transaction has been set as successful, or rolled back otherwise.
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Fetches the media items of a particular mediaSet. The mediaItems belonging to a particular
     * mediaSet are fetched from the media_in_media_sets table. The metadata of these items is
     * fetched from the media table by joining the two together.
     *
     * @param syncController       Instance of the PickerSyncController singleton.
     * @param mediaInMediaSetQuery The MediaInMediaSetsQuery object that tells us about the
     * @param localAuthority       The effective local authority to consider for this transaction
     * @param cloudAuthority       The effective cloud authority to consider for this transaction
     * @return A cursor with all the media items for that mediaSet and their metadata
     */
    public static Cursor queryMediaInMediaSet(@NonNull PickerSyncController syncController,
            @NonNull MediaInMediaSetsQuery mediaInMediaSetQuery,
            @Nullable String localAuthority, @Nullable String cloudAuthority) {

        final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

        try {
            database.beginTransactionNonExclusive();
            Cursor pageData = database.rawQuery(
                    getSearchMediaInMediaSetsPageQuery(
                            mediaInMediaSetQuery,
                            database,
                            mediaInMediaSetQuery.getTableWithRequiredJoins(
                                    database, localAuthority, cloudAuthority,
                                    /* reverseOrder */ false)
                    ),
                    /* selectionArgs */ null
            );

            Bundle extraArgs = new Bundle();

            Cursor nextPageKeyCursor = database.rawQuery(
                    getMediaInMediaSetsNextPageKeyQuery(
                            mediaInMediaSetQuery,
                            database,
                            mediaInMediaSetQuery.getTableWithRequiredJoins(
                                    database, localAuthority, cloudAuthority,
                                    /* reverseOrder */ false)
                    ),
                    /* selectionArgs */ null
            );
            addNextPageKey(extraArgs, nextPageKeyCursor);

            Cursor prevPageKeyCursor = database.rawQuery(
                    getMediaInMediaSetsPreviousPageQuery(
                            mediaInMediaSetQuery,
                            database,
                            mediaInMediaSetQuery.getTableWithRequiredJoins(
                                    database, localAuthority, cloudAuthority,
                                    /* reverseOrder */ true)
                    ),
                    /* selectionArgs */ null
            );
            addPrevPageKey(extraArgs, prevPageKeyCursor);

            database.setTransactionSuccessful();
            pageData.setExtras(extraArgs);
            Log.i(TAG, "Returning " + pageData.getCount() + " media metadata");
            return pageData;
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media from the media set", e);
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Returns the database query to fetch the media for the given media set. The media table is
     * joined with the media_in_media_sets table to get the items of that mediaSet and their
     * metadata from the media table. The join is made on local_id and cloud_id separately for
     * the given mediaSet. These are then further combined to give all the results filtered by
     * other arguments provided.
     */
    private static String getSearchMediaInMediaSetsPageQuery(
            @NonNull MediaInMediaSetsQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse
                                .AUTHORITY.getProjectedName(),
                        PickerSQLConstants.MediaResponse.MEDIA_SOURCE.getProjectedName(),
                        PickerSQLConstants.MediaResponse.WRAPPED_URI.getProjectedName(),
                        PickerSQLConstants.MediaResponse
                                .UNWRAPPED_URI.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                        PickerSQLConstants.MediaResponse.SIZE_IN_BYTES.getProjectedName(),
                        PickerSQLConstants.MediaResponse.MIME_TYPE.getProjectedName(),
                        PickerSQLConstants.MediaResponse.STANDARD_MIME_TYPE.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DURATION_MS.getProjectedName(),
                        PickerSQLConstants.MediaResponse.IS_PRE_GRANTED.getProjectedName()
                ))
                .setSortOrder(
                        String.format(
                                Locale.ROOT,
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                )
                .setLimit(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the sql query to fetch the first item of the next page.
     */
    @Nullable
    private static String getMediaInMediaSetsNextPageKeyQuery(
            @NonNull MediaInMediaSetsQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        if (query.getPageSize() == Integer.MAX_VALUE) {
            return null;
        }

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
                ))
                .setSortOrder(
                        String.format(
                                Locale.ROOT,
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                )
                .setLimit(1)
                .setOffset(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the sql query to fetch all the media items of the previous page.
     */
    private static String getMediaInMediaSetsPreviousPageQuery(
            @NonNull MediaInMediaSetsQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
                )).setSortOrder(
                        String.format(
                                Locale.ROOT,
                                "%s ASC, %s ASC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                ).setLimit(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Extracts the metadata from the provided cursor and creates a list of content values to
     * insert into the media_in_media_sets_table
     */
    public static List<ContentValues> getMediaContentValuesFromCursor(
            @NonNull Cursor mediaCursor, @NonNull String mediaSetPickerId, boolean isLocal) {
        Objects.requireNonNull(mediaSetPickerId);
        Objects.requireNonNull(mediaCursor);

        List<ContentValues> contentValuesList = new ArrayList<>(mediaCursor.getCount());
        if (mediaCursor.moveToFirst()) {
            do {
                // Extract metadata of each media item
                String mediaId = mediaCursor.getString(
                        mediaCursor.getColumnIndexOrThrow(
                                CloudMediaProviderContract.MediaColumns.ID));
                String mediaStoreUri = mediaCursor.getString(
                        mediaCursor.getColumnIndexOrThrow(
                                CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI));
                Uri mediaUri = mediaStoreUri == null ? null : Uri.parse(mediaStoreUri);
                String extractedLocalId = mediaStoreUri == null ? null
                        : String.valueOf(ContentUris.parseId(mediaUri));

                String localId = isLocal ? mediaId : extractedLocalId;
                String cloudId = isLocal ? null : mediaId;

                ContentValues insertValue = new ContentValues();
                insertValue.put(
                        PickerSQLConstants.MediaInMediaSetsTableColumns.LOCAL_ID.getColumnName(),
                        localId
                );
                insertValue.put(
                        PickerSQLConstants.MediaInMediaSetsTableColumns.CLOUD_ID.getColumnName(),
                        cloudId
                );
                insertValue.put(
                        PickerSQLConstants.MediaInMediaSetsTableColumns.MEDIA_SETS_PICKER_ID
                                .getColumnName(),
                        mediaSetPickerId
                );
                contentValuesList.add(insertValue);
            } while (mediaCursor.moveToNext());
        }
        return contentValuesList;
    }
}
