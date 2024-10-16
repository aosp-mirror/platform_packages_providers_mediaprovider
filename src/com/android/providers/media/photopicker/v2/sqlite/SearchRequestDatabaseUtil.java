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

import com.android.providers.media.photopicker.v2.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.SelectSQLiteQueryBuilder;
import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionType;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;

import java.util.List;

/**
 * Convenience class for running Picker Search related sql queries.
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
     * Tries to insert the given search request in the DB with the IGNORE constraint conflict
     * resolution strategy.
     *
     * @param database The database you need to run the query on.
     * @param searchRequest An object that contains search request details.
     * @return The row id of the inserted row. If the insertion did not happen, return -1.
     * @throws RuntimeException if an error occurs in running the sql command.
     */
    public static long saveSearchRequestIfRequired(
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
                Log.e(TAG, "Insertion ignored because the row already exists");
            }
            return result;
        } catch (RuntimeException e) {
            throw new RuntimeException("Could not save search request ", e);
        }
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
    public static SearchRequest getSearchRequestDetails(
            @NonNull SQLiteDatabase database,
            @NonNull int searchRequestID
    ) {
        final List<String> projection = List.of(
                PickerSQLConstants.SearchRequestTableColumns.SYNC_RESUME_KEY.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                PickerSQLConstants.SearchRequestTableColumns.AUTHORITY.getColumnName(),
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

                final String authority = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns.AUTHORITY.getColumnName()
                );
                final String mimeTypes = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns.MIME_TYPES.getColumnName()
                );
                final String searchText = getColumnValueOrNull(
                            cursor,
                            PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName()
                );
                final String resumeKey = getColumnValueOrNull(
                        cursor,
                        PickerSQLConstants.SearchRequestTableColumns.SYNC_RESUME_KEY.getColumnName()
                );

                final SearchRequest searchRequest;
                if (authority == null) {
                    // This is a search text request
                    searchRequest = new SearchTextRequest(
                            SearchRequest.getMimeTypesAsList(mimeTypes),
                            requireNonNull(searchText),
                            resumeKey
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
                    final SearchSuggestionType suggestionType = SearchSuggestionType.valueOf(
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
                            authority,
                            suggestionType,
                            resumeKey
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

        // Insert value as it is for a non-unique column.
        values.put(
                PickerSQLConstants.SearchRequestTableColumns.SYNC_RESUME_KEY.getColumnName(),
                searchRequest.getResumeKey());

        if (searchRequest instanceof SearchTextRequest searchTextRequest) {
            // Insert placeholder for null for unique column.
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                    getValueOrPlaceholder(searchTextRequest.getSearchText()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                    PLACEHOLDER_FOR_NULL);
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.AUTHORITY.getColumnName(),
                    PLACEHOLDER_FOR_NULL);
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_TYPE.getColumnName(),
                    PLACEHOLDER_FOR_NULL);
        } else if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            // Insert value or placeholder for null for unique column.
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SEARCH_TEXT.getColumnName(),
                    getValueOrPlaceholder(searchSuggestionRequest.getSearchText()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.MEDIA_SET_ID.getColumnName(),
                    getValueOrPlaceholder(searchSuggestionRequest.getMediaSetId()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.AUTHORITY.getColumnName(),
                    getValueOrPlaceholder(searchSuggestionRequest.getAuthority()));
            values.put(
                    PickerSQLConstants.SearchRequestTableColumns.SUGGESTION_TYPE.getColumnName(),
                    getValueOrPlaceholder(
                            searchSuggestionRequest.getSearchSuggestionType().name()));
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
            searchText = getValueOrPlaceholder(searchSuggestionRequest.getSearchText());
            mediaSetId = getValueOrPlaceholder(searchSuggestionRequest.getMediaSetId());
            authority = getValueOrPlaceholder(searchSuggestionRequest.getAuthority());
            suggestionType = getValueOrPlaceholder(
                    searchSuggestionRequest.getSearchSuggestionType().name());
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
                PickerSQLConstants.SearchRequestTableColumns.AUTHORITY.getColumnName(),
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
                String.format(" %s = '%s' ",
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
        queryBuilder.appendWhereStandalone(String.format(" %s = '%s' ", columnName, value));
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
