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

import static java.util.Objects.requireNonNull;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Convenience class for running Picker Search Request related sql queries.
 */
public class SearchRequestDatabaseUtil {
    private static final String TAG = "SearchDatabaseUtil";

    // Note that SQLite treats all null values as different. So, if you apply a
    // UNIQUE(...) constraint on some columns and if any of those columns holds a null value,
    // the unique constraint will not be applied. This is why in the search request table,
    // a placeholder value will be used instead of null so that the unique constraint gets
    // applied to all search requests saved in the table.
    // The placeholder values should not be a valid value to any of the columns in the unique
    // constraint.
    public static final String PLACEHOLDER_FOR_NULL = "";

    /**
     * Tries to insert the given search request in the DB with the REPLACE constraint conflict
     * resolution strategy.
     *
     * @param database The database you need to run the query on.
     * @param searchRequest An object that contains search request details.
     * @return The row id of the inserted row or -1 in case of a SQLite constraint conflict.
     * @throws RuntimeException if an error occurs in running the sql command.
     */
    public static long saveSearchRequest(
            @NonNull SQLiteDatabase database,
            @NonNull SearchRequest searchRequest) {
        final String table = PickerSQLConstants.Table.SEARCH_REQUEST.name();

        try {
            final long result = database.insertWithOnConflict(
                    table,
                    /* nullColumnHack */ null,
                    searchRequestToContentValues(searchRequest),
                    CONFLICT_IGNORE
            );

            if (result == -1) {
                Log.e(TAG, "Could not save request due to a conflict constraint");
            }
            return result;
        } catch (RuntimeException e) {
            throw new RuntimeException("Could not save search request ", e);
        }
    }

    /**
     * Update resume key for the given search request ID.
     *
     * @param database The database you need to run the query on.
     * @param searchRequestId Identifier for a search request.
     * @param resumeKey The resume key that can be used to fetch the next page of results,
     *                  or indicate that the sync is complete.
     * @param isLocal True if the sync resume key of local sync should be updated, else false if the
     *               sync resume key of cloud sync should be updated.
     * @throws RuntimeException if an error occurs in running the sql command.
     */
    public static void updateResumeKey(
            @NonNull SQLiteDatabase database,
            int searchRequestId,
            @Nullable String resumeKey,
            @NonNull String authority,
            boolean isLocal) {
        final String table = PickerSQLConstants.Table.SEARCH_REQUEST.name();

        ContentValues contentValues = new ContentValues();
        if (isLocal) {
            contentValues.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .LOCAL_SYNC_RESUME_KEY.getColumnName(),
                    resumeKey);
            contentValues.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .LOCAL_AUTHORITY.getColumnName(),
                    authority);
        } else {
            contentValues.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .CLOUD_SYNC_RESUME_KEY.getColumnName(),
                    resumeKey);
            contentValues.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .CLOUD_AUTHORITY.getColumnName(),
                    authority);
        }

        database.update(
                table,
                contentValues,
                String.format(
                        Locale.ROOT,
                        "%s.%s = %d",
                        table,
                        PickerSQLConstants.SearchRequestTableColumns
                                .SEARCH_REQUEST_ID.getColumnName(),
                        searchRequestId
                ),
                /* whereArgs */ null
        );
    }

    /**
     * Queries the database to try and fetch a unique search request ID for the given search
     * request.
     *
     * @param database The database you need to run the query on.
     * @param searchRequest Object that contains search request details.
     * @return the ID of the given search request or -1 if it can't find the search request in the
     * database. In case multiple search requests are a match, the first one is returned.
     */
    public static int getSearchRequestID(
            @NonNull SQLiteDatabase database,
            @NonNull SearchRequest searchRequest) {
        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.SEARCH_REQUEST.name())
                .setProjection(List.of(
                        PickerSQLConstants.SearchRequestTableColumns
                                .SEARCH_REQUEST_ID.getColumnName()));

        addSearchRequestIDWhereClause(queryBuilder, searchRequest);

        try (Cursor cursor = database.rawQuery(
                queryBuilder.buildQuery(), /* selectionArgs */ null)) {
            if (cursor.moveToFirst()) {
                if (cursor.getCount() > 1) {
                    Log.e(TAG, "Cursor cannot have more than one search request match "
                            + "- returning the first match");
                }
                return cursor.getInt(
                        cursor.getColumnIndexOrThrow(
                                PickerSQLConstants.SearchRequestTableColumns.SEARCH_REQUEST_ID
                                        .getColumnName()
                        )
                );
            }

            // If the cursor is empty, return -1;
            Log.w(TAG, "Search request does not exist in the DB.");
            return -1;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not fetch search request ID.", e);
            return -1;
        }
    }

    /**
     * Queries the database to try and fetch search request details for the given search request ID.
     *
     * @param database The database you need to run the query on.
     * @param searchRequestID ID of the search request.
     * @return the search request object corresponding to the given search request id,
     * or null if it can't find the search request in the database. In case multiple search
     * requests are a match, the first one is returned.
     */
    @Nullable
    public static SearchRequest getSearchRequestDetails(
            @NonNull SQLiteDatabase database,
            @NonNull int searchRequestID
    ) {
        final List<String> projection = List.of(
                PickerSQLConstants.SearchRequestTableColumns.LOCAL_SYNC_RESUME_KEY.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.LOCAL_AUTHORITY.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.CLOUD_SYNC_RESUME_KEY.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.CLOUD_AUTHORITY.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_AUTHORITY.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_TYPE.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.MIME_TYPES.getColumnName()
        );
        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.SEARCH_REQUEST.name())
                .setProjection(projection);

        addSearchRequestDetailsWhereClause(queryBuilder, searchRequestID);

        try (Cursor cursor = database.rawQuery(
                queryBuilder.buildQuery(), /* selectionArgs */ null)) {
            if (cursor.moveToFirst()) {
                if (cursor.getCount() > 1) {
                    Log.e(TAG, "Cursor cannot have more than one search request match "
                            + "- returning the first match");
                }

                final String suggestionAuthority = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns
                                .SUGGESTION_AUTHORITY.getColumnName()
                );
                final String mimeTypes = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns
                                .MIME_TYPES.getColumnName()
                );
                final String searchText = getColumnValueOrNull(
                            cursor,
                            PickerSQLConstants.SearchRequestTableColumns
                                    .SEARCH_TEXT.getColumnName()
                );
                final String localSyncResumeKey = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns
                                .LOCAL_SYNC_RESUME_KEY.getColumnName()
                );
                final String localAuthority = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns
                                .LOCAL_AUTHORITY.getColumnName()
                );
                final String cloudSyncResumeKey = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns
                                .CLOUD_SYNC_RESUME_KEY.getColumnName()
                );
                final String cloudAuthority = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns
                                .CLOUD_AUTHORITY.getColumnName()
                );

                final SearchRequest searchRequest;
                if (suggestionAuthority == null) {
                    // This is a search text request
                    searchRequest = new SearchTextRequest(
                            SearchRequest.getMimeTypesAsList(mimeTypes),
                            searchText,
                            localSyncResumeKey,
                            localAuthority,
                            cloudSyncResumeKey,
                            cloudAuthority
                    );
                } else {
                    // This is a search suggestion request
                    final String mediaSetID = requireNonNull(
                            getColumnValueOrNull(
                                    cursor,
                                    PickerSQLConstants.SearchRequestTableColumns
                                            .MEDIA_SET_ID.getColumnName()
                            )
                    );
                    final String suggestionType = requireNonNull(
                            getColumnValueOrNull(
                                    cursor,
                                    PickerSQLConstants.SearchRequestTableColumns
                                            .SUGGESTION_TYPE.getColumnName()
                            )
                    );

                    searchRequest = new SearchSuggestionRequest(
                            SearchRequest.getMimeTypesAsList(mimeTypes),
                            searchText,
                            mediaSetID,
                            suggestionAuthority,
                            suggestionType,
                            localSyncResumeKey,
                            localAuthority,
                            cloudSyncResumeKey,
                            cloudAuthority
                    );
                }
                return searchRequest;
            }

            // If the cursor is empty, return null;
            Log.w(TAG, "Search request does not exist in the DB.");
            return null;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not fetch search request details.", e);
            return null;
        }
    }

    /**
     * @param database The database you need to run the query on.
     * @param isLocal True if the search results synced with the local provider need to be reset.
     *                Else if the search results synced with cloud provider need to be reset,
     *                this is false.
     * @return a list of search request IDs of the search requests that are either fully or
     * partially synced with the provider.
     */
    public static List<Integer> getSyncedRequestIds(
            @NonNull SQLiteDatabase database,
            boolean isLocal) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database);
        queryBuilder.setTables(PickerSQLConstants.Table.SEARCH_REQUEST.name())
                .setProjection(List.of(
                        PickerSQLConstants.SearchRequestTableColumns
                                .SEARCH_REQUEST_ID.getColumnName()
                ));

        if (isLocal) {
            queryBuilder.appendWhereStandalone(
                    String.format(
                            Locale.ROOT,
                            "%s IS NOT NULL OR %s IS NOT NULL",
                            PickerSQLConstants.SearchRequestTableColumns
                                    .LOCAL_AUTHORITY.getColumnName(),
                            PickerSQLConstants.SearchRequestTableColumns
                                    .LOCAL_SYNC_RESUME_KEY.getColumnName()
                    )
            );
        } else {
            queryBuilder.appendWhereStandalone(
                    String.format(
                            Locale.ROOT,
                            "%s IS NOT NULL OR %s IS NOT NULL",
                            PickerSQLConstants.SearchRequestTableColumns
                                    .CLOUD_AUTHORITY.getColumnName(),
                            PickerSQLConstants.SearchRequestTableColumns
                                    .CLOUD_SYNC_RESUME_KEY.getColumnName()
                    )
            );
        }

        final List<Integer> searchRequestIds = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(queryBuilder.buildQuery(), null)) {
            if (cursor.moveToFirst()) {
                do {
                    searchRequestIds.add(cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    PickerSQLConstants.SearchRequestTableColumns
                                            .SEARCH_REQUEST_ID.getColumnName()
                            )
                    ));
                } while (cursor.moveToNext());
            }
        }
        return searchRequestIds;
    }

    /**
     * Clear sync resume info from the database.
     *
     * @param database SQLiteDatabase object that contains the database connection.
     * @param searchRequestIds List of search request ids that identify the rows that need to be
     *                         updated.
     * @param isLocal This is true when the local sync resume info needs to clear,
     *                otherwise it is false.
     * @return The number of items that were updated.
     */
    public static int clearSyncResumeInfo(
            @NonNull SQLiteDatabase database,
            @NonNull List<Integer> searchRequestIds,
            boolean isLocal) {
        requireNonNull(database);
        requireNonNull(searchRequestIds);
        if (searchRequestIds.isEmpty()) {
            Log.d(TAG, "No search request ids received for clearing resume info");
            return 0;
        }

        final String whereClause = String.format(
                Locale.ROOT,
                "%s IN ('%s')",
                PickerSQLConstants.SearchRequestTableColumns.SEARCH_REQUEST_ID.getColumnName(),
                searchRequestIds
                        .stream()
                        .map(Object::toString)
                        .collect(Collectors.joining("','")));

        final ContentValues updatedValues = new ContentValues();
        if (isLocal) {
            updatedValues.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .LOCAL_SYNC_RESUME_KEY.getColumnName(),
                    (String) null
            );
            updatedValues.put(
                    PickerSQLConstants.SearchRequestTableColumns.LOCAL_AUTHORITY.getColumnName(),
                    (String) null
            );
        } else {
            updatedValues.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .CLOUD_SYNC_RESUME_KEY.getColumnName(),
                    (String) null
            );
            updatedValues.put(
                    PickerSQLConstants.SearchRequestTableColumns.CLOUD_AUTHORITY.getColumnName(),
                    (String) null
            );
        }

        final int updatedSearchRequestsCount = database.update(
                PickerSQLConstants.Table.SEARCH_REQUEST.name(),
                updatedValues,
                whereClause,
                /* whereArgs */ null);
        Log.d(TAG, "Updated number of search results: " + updatedSearchRequestsCount);
        return updatedSearchRequestsCount;
    }

    /**
     * Clears all search requests from the database.
     *
     * @param database SQLiteDatabase object that contains the database connection.
     * @return The number of items that were updated.
     */
    public static int clearAllSearchRequests(@NonNull SQLiteDatabase database) {
        requireNonNull(database);

        int searchRequestsDeletionCount =
                database.delete(
                        PickerSQLConstants.Table.SEARCH_REQUEST.name(),
                        /* whereClause */ null,
                        /* whereArgs */ null);

        Log.d(TAG, String.format(
                Locale.ROOT,
                "Deleted %s rows in search request table",
                searchRequestsDeletionCount));

        return searchRequestsDeletionCount;
    }


    /**
     * @return ContentValues that contains a mapping of column names of search_request table as key
     * and search request data as values. This is intended to be used in SQLite insert queries.
     */
    @NonNull
    private static ContentValues searchRequestToContentValues(
            @NonNull SearchRequest searchRequest) {
        requireNonNull(searchRequest);

        final ContentValues values = new ContentValues();

        // Insert value or placeholder for null for unique column.
        values.put(
                PickerSQLConstants.SearchRequestTableColumns.MIME_TYPES.getColumnName(),
                getValueOrPlaceholder(
                        SearchRequest.getMimeTypesAsString(searchRequest.getMimeTypes())));

        // Insert value as it is for non-unique columns.
        values.put(
                PickerSQLConstants.SearchRequestTableColumns.LOCAL_SYNC_RESUME_KEY.getColumnName(),
                searchRequest.getLocalSyncResumeKey());

        values.put(
                PickerSQLConstants.SearchRequestTableColumns.LOCAL_AUTHORITY.getColumnName(),
                searchRequest.getLocalAuthority());

        values.put(
                PickerSQLConstants.SearchRequestTableColumns.CLOUD_SYNC_RESUME_KEY.getColumnName(),
                searchRequest.getCloudSyncResumeKey());

        values.put(
                PickerSQLConstants.SearchRequestTableColumns.CLOUD_AUTHORITY.getColumnName(),
                searchRequest.getCloudAuthority());

        if (searchRequest instanceof SearchTextRequest searchTextRequest) {
            // Insert placeholder for null for unique column.
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                    getValueOrPlaceholder(searchTextRequest.getSearchText()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                    PLACEHOLDER_FOR_NULL);
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .SUGGESTION_AUTHORITY.getColumnName(),
                    PLACEHOLDER_FOR_NULL);
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_TYPE.getColumnName(),
                    PLACEHOLDER_FOR_NULL);
        } else if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            // Insert value or placeholder for null for unique column.
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                    getValueOrPlaceholder(
                            searchSuggestionRequest.getSearchSuggestion().getSearchText()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                    getValueOrPlaceholder(
                            searchSuggestionRequest.getSearchSuggestion().getMediaSetId()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns
                            .SUGGESTION_AUTHORITY.getColumnName(),
                    getValueOrPlaceholder(searchSuggestionRequest
                            .getSearchSuggestion().getAuthority()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_TYPE.getColumnName(),
                    getValueOrPlaceholder(searchSuggestionRequest.getSearchSuggestion()
                            .getSearchSuggestionType()));
        } else {
            throw new IllegalStateException(
                    "Could not identify search request type " + searchRequest);
        }

        return values;
    }

    /**
     * @param queryBuilder Adds where clauses based on the given searchRequest.
     * @param searchRequest Object that contains search request details.
     */
    private static void addSearchRequestIDWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull SearchRequest searchRequest) {
        String searchText;
        String mediaSetId = null;
        String authority = null;
        String suggestionType = null;
        if (searchRequest instanceof SearchTextRequest searchTextRequest) {
            searchText = getValueOrPlaceholder(searchTextRequest.getSearchText());
        } else if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            searchText = getValueOrPlaceholder(
                    searchSuggestionRequest.getSearchSuggestion().getSearchText());
            mediaSetId = getValueOrPlaceholder(searchSuggestionRequest
                    .getSearchSuggestion().getMediaSetId());
            authority = getValueOrPlaceholder(searchSuggestionRequest
                    .getSearchSuggestion().getAuthority());
            suggestionType = getValueOrPlaceholder(searchSuggestionRequest
                            .getSearchSuggestion().getSearchSuggestionType());
        } else {
            throw new IllegalStateException(
                    "Could not identify search request type " + searchRequest);
        }

        addWhereClause(
                queryBuilder,
                PickerSQLConstants.SearchRequestTableColumns.MIME_TYPES.getColumnName(),
                SearchRequest.getMimeTypesAsString(searchRequest.getMimeTypes()));
        addWhereClause(
                queryBuilder,
                PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                searchText);
        addWhereClause(
                queryBuilder,
                PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                mediaSetId);
        addWhereClause(
                queryBuilder,
                PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_AUTHORITY.getColumnName(),
                authority);
        addWhereClause(
                queryBuilder,
                PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_TYPE.getColumnName(),
                suggestionType);
    }

    private static void addSearchRequestDetailsWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull int searchRequestID
    ) {
        queryBuilder.appendWhereStandalone(
                String.format(Locale.ROOT,
                        " %s = '%s' ",
                        PickerSQLConstants.SearchRequestTableColumns
                                .SEARCH_REQUEST_ID.getColumnName(),
                        searchRequestID));
    }

    /**
     * @param queryBuilder Adds an equality where clauses based on the given column name and value.
     * @param columnName Column name on which an equals check needs to be added.
     * @param value The desired value that needs to be added to the where clause equality check.
     *              If the value is null, it will be replaced by a non-null placeholder used in the
     *              table for empty/null values.
     */
    private static void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull String columnName,
            @Nullable String value) {
        value = getValueOrPlaceholder(value);
        queryBuilder.appendWhereStandalone(String.format(Locale.ROOT,
                " %s = '%s' ", columnName, value));
    }

    /**
     * @param value Input value that can be nullable.
     * @return If the input value is null, returns it as it is , otherwise returns a non-null
     * placeholder for empty/null values.
     */
    @NonNull
    private static String getValueOrPlaceholder(@Nullable String value) {
        if (value == null) {
            return PLACEHOLDER_FOR_NULL;
        }
        return value;
    }

    @Nullable
    private static String getColumnValueOrNull(@NonNull Cursor cursor, @NonNull String columnName) {
        return getValueOrNull(cursor.getString(cursor.getColumnIndexOrThrow(columnName)));
    }

    @Nullable
    private static String getValueOrNull(@NonNull String value) {
        if (PLACEHOLDER_FOR_NULL.equals(value)) {
            return null;
        }
        return value;
    }
}
