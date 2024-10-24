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

import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
import static com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil.addNextPageKey;
import static com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil.addPrevPageKey;

import static java.util.Objects.requireNonNull;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;

import java.util.List;

public class SearchResultsDatabaseUtil {
    private static final String TAG = "SearchResultsDatabaseUtil";

    /**
     * Saved the search results media items received from CMP in the database as a temporary cache.
     *
     * @param database SQLite database object that holds DB connection(s) and provides a wrapper
     *                 for executing DB queries.
     * @param authority Authority of the CMP that is the source of search results media.
     * @param contentValuesList List of ContentValues that contain the search results media.
     *                          Each ContentValue in the list represents a media item.
     * @return The number of items inserted in the DB.
     * @throws RuntimeException if no items could be inserted in the database due to an unexpected
     * exception.
     */
    public static int cacheSearchResults(
            @NonNull SQLiteDatabase database,
            @NonNull String authority,
            @Nullable List<ContentValues> contentValuesList) {
        requireNonNull(database);
        requireNonNull(authority);

        if (contentValuesList == null || contentValuesList.isEmpty()) {
            Log.e(TAG, "Cursor is either null or empty. Nothing to do.");
            return 0;
        }

        final boolean isLocal = LOCAL_PICKER_PROVIDER_AUTHORITY.equals(authority);

        try {
            // Start a transaction with EXCLUSIVE lock.
            database.beginTransaction();

            int numberOfRowsInserted = 0;
            for (ContentValues contentValues : contentValuesList) {
                try {
                    // Prefer media received from local provider over cloud provider to avoid
                    // joining with media table on cloud_id when not required.
                    final int conflictResolutionStrategy = isLocal
                            ? CONFLICT_REPLACE
                            : CONFLICT_IGNORE;
                    final long rowID = database.insertWithOnConflict(
                            PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                            null,
                            contentValues,
                            conflictResolutionStrategy
                    );

                    if (rowID == -1) {
                        Log.d(TAG, "Did not insert row in the search results media table"
                                + " due to IGNORE conflict resolution strategy " + contentValues);
                    } else {
                        numberOfRowsInserted++;
                    }
                } catch (SQLException e) {
                    // Skip the row that could not be inserted.
                    Log.e(TAG, "Could not insert row in the search results media table "
                            + contentValues, e);
                }
            }

            // Mark transaction as successful so that it gets committed after it ends.
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }

            return numberOfRowsInserted;
        } catch (RuntimeException e) {
            // Do not mark transaction as successful so that it gets roll-backed. after it ends.
            throw new RuntimeException("Could not insert items in the DB", e);
        } finally {
            // Mark transaction as ended. The inserted items will either be committed if the
            // transaction has been set as successful, or roll-backed otherwise.
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Query media from the database and prepare a cursor in response.
     *
     * To get search media, we'll fetch media IDs for a corresponding search request ID from the
     * search_result_media table and then enrich it with media metadata from the media table using
     * sql joins.
     *
     * We need to make multiple queries to prepare a response for the media query.
     * {@link android.database.sqlite.SQLiteQueryBuilder} currently does not support the creation of
     * a transaction in {@code DEFERRED} mode. This is why we'll perform the read queries in
     * {@code IMMEDIATE} mode instead.
     *
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    public static Cursor querySearchMedia(
            @NonNull PickerSyncController syncController,
            @NonNull SearchMediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getSearchMediaPageQuery(
                                query,
                                database,
                                query.getTableWithRequiredJoins(
                                        database, localAuthority, cloudAuthority)
                        ),
                        /* selectionArgs */ null
                );
                Bundle extraArgs = new Bundle();
                Cursor nextPageKeyCursor = database.rawQuery(
                        getSearchMediaNextPageKeyQuery(
                                query,
                                database,
                                query.getTableWithRequiredJoins(
                                        database, localAuthority, cloudAuthority)
                        ),
                        /* selectionArgs */ null
                );
                addNextPageKey(extraArgs, nextPageKeyCursor);

                Cursor prevPageKeyCursor = database.rawQuery(
                        getSearchMediaPreviousPageQuery(
                                query,
                                database,
                                query.getTableWithRequiredJoins(
                                        database, localAuthority, cloudAuthority)
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                database.setTransactionSuccessful();
                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " media metadata");
                return pageData;
            } finally {
                database.endTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }

    /**
     * Builds and returns the SQL query to get the page contents for the search results from
     * Picker DB. To get the media items, we need to query the search_result_media table
     * and join with media table.
     */
    public static String getSearchMediaPageQuery(
            @NonNull SearchMediaQuery query,
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
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                )
                .setLimit(query.getPageSize());

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the next page's first row for the search results
     * query.
     */
    @Nullable
    public static String getSearchMediaNextPageKeyQuery(
            @NonNull SearchMediaQuery query,
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
     * Builds and returns the SQL query to get the previous page contents for the search results
     * from the previous page.
     *
     * We fetch the whole page and not just one key because it is possible that the previous page
     * is smaller than the page size. So, we get the whole page and only use the last row item to
     * get the previous page key.
     */
    public static String getSearchMediaPreviousPageQuery(
            @NonNull SearchMediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull String table) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table)
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
                )).setSortOrder(
                        String.format(
                                "%s ASC, %s ASC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
                        )
                ).setLimit(query.getPageSize());

        return queryBuilder.buildQuery();
    }
}
