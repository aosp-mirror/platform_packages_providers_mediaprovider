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

import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;
import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_HISTORY;
import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_FACE;

import static java.util.Objects.requireNonNull;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestion;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Convenience class for running Picker Search Suggestions related sql queries.
 */
public class SearchSuggestionsDatabaseUtils {
    private static final String TAG = "SearchSuggestionsDBUtil";
    private static final int TTL_HISTORY_SUGGESTIONS_IN_DAYS = 60;
    private static final int TTL_CACHED_SUGGESTIONS_IN_DAYS = 30;

    /**
     * Save Search Request as search history to serve as search suggestions later.
     *
     * @param database Instance of Picker Database.
     * @param searchRequest Search Request issued by the user.
     * @return true if the search history was saved successfully, else returns false.
     */
    public static boolean saveSearchHistory(
            @NonNull SQLiteDatabase database,
            @NonNull SearchRequest searchRequest) {
        requireNonNull(database);
        requireNonNull(searchRequest);
        Log.d(TAG, "Saving search history: " + searchRequest);

        try {
            // Note that CONFLICT_REPLACE create a new row in case of a conflict so the
            // _id might change. If you need the _id to remain the same, use update instead.
            final long result = database.insertWithOnConflict(
                    PickerSQLConstants.Table.SEARCH_HISTORY.name(),
                    /* nullColumnHack */ null,
                    searchRequestToHistoryContentValues(searchRequest),
                    CONFLICT_REPLACE
            );

            if (result == -1) {
                throw new RuntimeException(
                        "Search history was not saved due to a constraint conflict.");
            }

            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not save search history ", e);
            return false;
        }
    }

    /**
     * Fetch Search history as suggestions.
     *
     * @param database Instance of Picker Database.
     * @param query SearchSuggestionsQuery that holds search suggestions query inputs.
     */
    public static List<SearchSuggestion> getHistorySuggestions(
            @NonNull SQLiteDatabase database,
            @NonNull SearchSuggestionsQuery query) {
        requireNonNull(database);
        requireNonNull(query);

        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.SEARCH_HISTORY.name())
                .setProjection(List.of(
                        PickerSQLConstants.SearchHistoryTableColumns.SEARCH_TEXT.getColumnName(),
                        PickerSQLConstants.SearchHistoryTableColumns.MEDIA_SET_ID.getColumnName(),
                        PickerSQLConstants.SearchHistoryTableColumns.AUTHORITY.getColumnName(),
                        PickerSQLConstants.SearchHistoryTableColumns.COVER_MEDIA_ID.getColumnName()
                ))
                .setSortOrder(String.format(
                        Locale.ROOT,
                        "%s DESC",
                        PickerSQLConstants.SearchHistoryTableColumns.PICKER_ID.getColumnName()));

        final long creationThreshold = System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(TTL_HISTORY_SUGGESTIONS_IN_DAYS);
        queryBuilder.appendWhereStandalone(String.format(
                Locale.ROOT,
                " %s >= %d ",
                PickerSQLConstants.SearchHistoryTableColumns.CREATION_TIME_MS,
                creationThreshold));

        if (query.getProviderAuthorities() != null && !query.getProviderAuthorities().isEmpty()) {
            queryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT,
                    "(%s IS NULL) OR (%s IN ('%s'))",
                    PickerSQLConstants.SearchHistoryTableColumns.AUTHORITY,
                    PickerSQLConstants.SearchHistoryTableColumns.AUTHORITY,
                    String.join("','", query.getProviderAuthorities())));
        } else {
            queryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT,
                    "%s IS NULL",
                    PickerSQLConstants.SearchHistoryTableColumns.AUTHORITY));
        }


        final List<SearchSuggestion> historySuggestions = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                queryBuilder.buildQuery(), /* selectionArgs */ null)) {
            if (cursor.moveToFirst()) {
                do {
                    final SearchSuggestion historySuggestion = getHistorySuggestion(cursor);
                    if (historySuggestion != null) {
                        if (query.isZeroState() || (historySuggestion.getSearchText() != null
                                && historySuggestion.getSearchText().toLowerCase(Locale.ROOT)
                                .startsWith(query.getPrefix().toLowerCase(Locale.ROOT)))) {
                            historySuggestions.add(historySuggestion);
                        }
                    }
                } while (cursor.moveToNext()
                        && historySuggestions.size() < query.getHistoryLimit());
            }

            Log.d(TAG, "Number of history suggestions: " + historySuggestions.size());
            return historySuggestions;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not fetch search history suggestions.", e);
            return new ArrayList<>();
        }
    }

    /**
     * Fetch cached suggestions received from cloud media providers.
     *
     * @param database Instance of Picker Database.
     * @param query SearchSuggestionsQuery that holds search suggestions query inputs.
     */
    public static List<SearchSuggestion> getCachedSuggestions(
            @NonNull SQLiteDatabase database,
            @NonNull SearchSuggestionsQuery query) {
        requireNonNull(database);
        requireNonNull(query);

        if (query.getProviderAuthorities() == null || query.getProviderAuthorities().isEmpty()) {
            Log.e(TAG, "Available providers list is empty");
            return new ArrayList<>();
        }

        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.SEARCH_SUGGESTION.name())
                .setProjection(List.of(
                        PickerSQLConstants.SearchSuggestionsTableColumns
                                .SEARCH_TEXT.getColumnName(),
                        PickerSQLConstants.SearchSuggestionsTableColumns
                                .MEDIA_SET_ID.getColumnName(),
                        PickerSQLConstants.SearchSuggestionsTableColumns
                                .AUTHORITY.getColumnName(),
                        PickerSQLConstants.SearchSuggestionsTableColumns
                                .COVER_MEDIA_ID.getColumnName(),
                        PickerSQLConstants.SearchSuggestionsTableColumns
                                .SUGGESTION_TYPE.getColumnName()
                ))
                .setSortOrder(String.format(
                        Locale.ROOT,
                        "%s ASC",
                        PickerSQLConstants.SearchSuggestionsTableColumns
                                .PICKER_ID.getColumnName()));

        final long creationThreshold = System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(TTL_CACHED_SUGGESTIONS_IN_DAYS);
        queryBuilder.appendWhereStandalone(String.format(
                Locale.ROOT,
                " %s >= %d ",
                PickerSQLConstants.SearchSuggestionsTableColumns.CREATION_TIME_MS,
                creationThreshold));

        queryBuilder.appendWhereStandalone(String.format(
                Locale.ROOT,
                "%s IN ('%s')",
                PickerSQLConstants.SearchSuggestionsTableColumns.AUTHORITY,
                String.join("','", query.getProviderAuthorities())));

        final List<SearchSuggestion> suggestions = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                queryBuilder.buildQuery(), /* selectionArgs */ null)) {
            if (cursor.moveToFirst()) {
                do {
                    final SearchSuggestion suggestion = getSuggestion(cursor);
                    if (suggestion != null) {
                        if (query.isZeroState() || (suggestion.getSearchText() != null
                                && suggestion.getSearchText().toLowerCase(Locale.ROOT)
                                .startsWith(query.getPrefix().toLowerCase(Locale.ROOT)))) {
                            suggestions.add(suggestion);
                        }
                    }
                } while (cursor.moveToNext() && suggestions.size() < query.getLimit());
            }

            Log.d(TAG, "Number of fetched cached suggestions: " + suggestions.size());
            return suggestions;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not fetch search suggestions.", e);
            return new ArrayList<>();
        }
    }

    /**
     * Extract search suggestions from the input cursor received from cloud media provider.
     *
     * @param cursor Cursor received from cloud media provider.
     * @param authority Authority of the cloud media provider.
     * @return A list of valid SearchSuggestions. If an invalid search suggestion is encountered
     * it will not be included in the returned list.
     */
    @NonNull
    public static List<SearchSuggestion> extractSearchSuggestions(
            @NonNull Cursor cursor,
            @NonNull String authority) {
        requireNonNull(cursor);
        requireNonNull(authority);

        final List<SearchSuggestion> searchSuggestions = new ArrayList<>(cursor.getCount());
        if (cursor.moveToFirst()) {
            do {
                try {
                    searchSuggestions.add(extractSearchSuggestion(cursor, authority));
                } catch (RuntimeException e) {
                    ContentValues contentValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, contentValues);
                    Log.e(TAG, "Invalid search suggestion - skipping it: " + contentValues);
                }
            } while (cursor.moveToNext());
        }

        Log.d(TAG, "Extracted suggestions from cursor: " + searchSuggestions);
        return searchSuggestions;
    }

    /**
     * Save zero-state search suggestions received from cloud media providers in the database.
     *
     * @param database Instance of Picker Database.
     * @param authority Authority of the source cloud media provider of the suggestions.
     * @param searchSuggestions List of search suggestions that need to be cached.
     * @return number of search suggestions cached in the database.
     */
    public static int cacheSearchSuggestions(
            @NonNull SQLiteDatabase database,
            @NonNull String authority,
            @NonNull List<SearchSuggestion> searchSuggestions) {
        requireNonNull(database);
        requireNonNull(authority);
        requireNonNull(searchSuggestions);

        try {
            // Start a database write transaction.
            database.beginTransaction();

            // Delete all cached suggestions for the authority
            final String[] deletionArgs = new String[1];
            deletionArgs[0] = authority;
            database.delete(
                    PickerSQLConstants.Table.SEARCH_SUGGESTION.name(),
                    /* whereClause */ String.format(
                            Locale.ROOT,
                            "%s = ?",
                            PickerSQLConstants.SearchSuggestionsTableColumns
                                    .AUTHORITY.getColumnName()
                    ),
                    /* whereArgs */ deletionArgs
            );

            // Insert suggestions for the authority
            int numberOfRowsInserted = 0;
            for (SearchSuggestion suggestion : searchSuggestions) {
                final ContentValues contentValues = getContentValues(suggestion);
                try {
                    final long rowID = database.insertWithOnConflict(
                            PickerSQLConstants.Table.SEARCH_SUGGESTION.name(),
                            null,
                            contentValues,
                            CONFLICT_REPLACE
                    );

                    if (rowID == -1) {
                        throw new RuntimeException("Could not cache search suggestion due to "
                                + "constraint conflict");
                    } else {
                        numberOfRowsInserted++;
                    }
                } catch (RuntimeException e) {
                    // Skip the row that could not be inserted.
                    Log.e(TAG, "Could not insert row in the search suggestions table "
                            + contentValues, e);
                }
            }

            // Mark transaction as successful so that it gets committed after it ends.
            if (database.inTransaction()) {
                Log.d(TAG, "Marked transaction as successful");
                database.setTransactionSuccessful();
            }

            Log.d(TAG, "Number of suggestions cached in the DB: " + numberOfRowsInserted);
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
     * @param suggestion Input search suggestion to be saved to be saved in
     * {@link PickerSQLConstants.Table#SEARCH_SUGGESTION}
     * @return ContentValues that contains the search suggestion data to be saved in the format
     * {@link PickerSQLConstants.SearchSuggestionsTableColumns}
     */
    private static ContentValues getContentValues(SearchSuggestion suggestion) {
        final ContentValues contentValues = new ContentValues();

        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.SEARCH_TEXT.getColumnName(),
                suggestion.getSearchText()
        );
        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.MEDIA_SET_ID.getColumnName(),
                suggestion.getMediaSetId()
        );
        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.AUTHORITY.getColumnName(),
                suggestion.getAuthority()
        );
        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.COVER_MEDIA_ID.getColumnName(),
                suggestion.getCoverMediaId()
        );
        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.SUGGESTION_TYPE.getColumnName(),
                suggestion.getSearchSuggestionType()
        );
        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.CREATION_TIME_MS.getColumnName(),
                System.currentTimeMillis()
        );

        return contentValues;
    }

    /**
     * Extract search suggestion from the current row in the given cursor.
     *
     * @param cursor The input cursor received from cloud media provider with suggestions.
     * @param authority The authority of the cloud media provider.
     * @return The extracted SearchSuggestion.
     * @throws IllegalArgumentException if the input cursor has invalid values.
     */
    @NonNull
    private static SearchSuggestion extractSearchSuggestion(
            @NonNull Cursor cursor,
            @NonNull String authority) {
        requireNonNull(cursor);
        requireNonNull(authority);

        String searchText = cursor.getString(cursor.getColumnIndexOrThrow(
                CloudMediaProviderContract.SearchSuggestionColumns.DISPLAY_TEXT));
        final String mediaSetId = cursor.getString(cursor.getColumnIndexOrThrow(
                CloudMediaProviderContract.SearchSuggestionColumns.MEDIA_SET_ID));
        final String suggestionType = cursor.getString(cursor.getColumnIndexOrThrow(
                CloudMediaProviderContract.SearchSuggestionColumns.TYPE));
        final String coverMediaId = cursor.getString(cursor.getColumnIndexOrThrow(
                CloudMediaProviderContract.SearchSuggestionColumns.MEDIA_COVER_ID));

        if (mediaSetId == null || mediaSetId.trim().isEmpty()) {
            throw new IllegalArgumentException("Media set ID cannot be null or empty");
        }
        if (searchText.trim().isEmpty()) {
            searchText = null;
        }
        if (suggestionType == null) {
            throw new IllegalArgumentException("Suggestion type cannot be null");
        }

        if (searchText == null && (suggestionType != SEARCH_SUGGESTION_FACE)) {
            throw new IllegalArgumentException(
                    "Only FACE type suggestions can have null search text");
        }

        return new SearchSuggestion(
                searchText,
                mediaSetId,
                authority,
                suggestionType,
                coverMediaId
        );
    }

    /**
     * Get search history suggestion from the current row in the given cursor.
     *
     * @param cursor The input cursor sourced from the picker database.
     * @return a history type SearchSuggestion or null if data in the cursor is invalid.
     */
    @Nullable
    private static SearchSuggestion getHistorySuggestion(Cursor cursor) {
        requireNonNull(cursor);

        try {
            final String searchText = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchHistoryTableColumns.SEARCH_TEXT.getColumnName()));
            final String mediaSetId = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchHistoryTableColumns.MEDIA_SET_ID.getColumnName()));
            final String authority = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchHistoryTableColumns.AUTHORITY.getColumnName()));
            final String coverMediaId = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchHistoryTableColumns.COVER_MEDIA_ID.getColumnName()));

            return new SearchSuggestion(
                    searchText,
                    mediaSetId,
                    authority,
                    SEARCH_SUGGESTION_HISTORY,
                    coverMediaId
            );
        } catch (RuntimeException e) {
            final ContentValues contentValues = new ContentValues();
            DatabaseUtils.cursorRowToContentValues(cursor, contentValues);
            Log.e(TAG, "Invalid history suggestion: " + contentValues, e);
            return null;
        }

    }

    /**
     * Get search suggestion from the current row in the given cursor.
     *
     * @param cursor The input cursor sourced from the picker database.
     * @return a SearchSuggestion object or null if data in the cursor is invalid.
     */
    @Nullable
    private static SearchSuggestion getSuggestion(Cursor cursor) {
        requireNonNull(cursor);

        try {
            final String searchText = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchSuggestionsTableColumns.SEARCH_TEXT.getColumnName()));
            final String mediaSetId = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchSuggestionsTableColumns.MEDIA_SET_ID.getColumnName()));
            final String authority = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchSuggestionsTableColumns.AUTHORITY.getColumnName()));
            final String coverMediaId = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchSuggestionsTableColumns
                            .COVER_MEDIA_ID.getColumnName()));
            final String type = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.SearchSuggestionsTableColumns
                            .SUGGESTION_TYPE.getColumnName()));

            return new SearchSuggestion(
                    searchText,
                    mediaSetId,
                    authority,
                    type,
                    coverMediaId
            );
        } catch (RuntimeException e) {
            final ContentValues contentValues = new ContentValues();
            DatabaseUtils.cursorRowToContentValues(cursor, contentValues);
            Log.e(TAG, "Invalid suggestion: " + contentValues, e);
            return null;
        }
    }

    /**
     * @param searchRequest Input search request to be saved to be saved in
     * {@link PickerSQLConstants.Table#SEARCH_HISTORY}
     * @return ContentValues that contains the search history data to be saved in the format
     * {@link PickerSQLConstants.SearchHistoryTableColumns}
     */
    @NonNull
    private static ContentValues searchRequestToHistoryContentValues(
            @NonNull SearchRequest searchRequest) {
        requireNonNull(searchRequest);

        final ContentValues values = new ContentValues();

        values.put(
                PickerSQLConstants.SearchHistoryTableColumns.CREATION_TIME_MS.getColumnName(),
                System.currentTimeMillis());

        if (searchRequest instanceof SearchTextRequest searchTextRequest) {
            values.put(
                    PickerSQLConstants.SearchHistoryTableColumns.SEARCH_TEXT.getColumnName(),
                    searchTextRequest.getSearchText());
        } else if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            values.put(
                    PickerSQLConstants.SearchHistoryTableColumns.SEARCH_TEXT.getColumnName(),
                    searchSuggestionRequest.getSearchSuggestion().getSearchText());

            values.put(
                    PickerSQLConstants.SearchHistoryTableColumns.MEDIA_SET_ID.getColumnName(),
                    searchSuggestionRequest.getSearchSuggestion().getMediaSetId());

            values.put(
                    PickerSQLConstants.SearchHistoryTableColumns.AUTHORITY.getColumnName(),
                    searchSuggestionRequest.getSearchSuggestion().getAuthority());

            values.put(
                    PickerSQLConstants.SearchHistoryTableColumns.COVER_MEDIA_ID.getColumnName(),
                    searchSuggestionRequest.getSearchSuggestion().getCoverMediaId());
        } else {
            throw new IllegalStateException(
                    "Could not identify search request type " + searchRequest);
        }

        return values;
    }
}

