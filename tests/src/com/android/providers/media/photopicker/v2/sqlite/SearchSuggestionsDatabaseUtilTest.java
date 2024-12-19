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

import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_ALBUM;
import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_HISTORY;
import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_FACE;
import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_LOCATION;

import static com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsDatabaseUtils.TTL_CACHED_SUGGESTIONS_IN_DAYS;
import static com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsDatabaseUtils.TTL_HISTORY_SUGGESTIONS_IN_DAYS;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestion;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SearchSuggestionsDatabaseUtilTest {
    private SQLiteDatabase mDatabase;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
    }

    @After
    public void teardown() {
        mDatabase.close();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
    }

    @Test
    public void testSaveTextSearchRequestHistory() {
        final String searchText = "mountains";
        final SearchTextRequest searchRequest = new SearchTextRequest(
                /* mimeTypes */ null,
                searchText
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest);

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of(),
                                /* limit */ 10));

        assertWithMessage("Search history suggestions cannot be null")
                .that(searchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search history suggestions.")
                .that(searchSuggestions.size())
                .isEqualTo(1);

        final SearchSuggestion result = searchSuggestions.get(0);
        assertWithMessage("Search history search text is not as expected")
                .that(result.getSearchText())
                .isEqualTo(searchText);
        assertWithMessage("Search history media set id is not as expected")
                .that(result.getMediaSetId())
                .isNull();
        assertWithMessage("Search history authority is not as expected")
                .that(result.getAuthority())
                .isNull();
        assertWithMessage("Search history suggestion type is not as expected")
                .that(result.getSearchSuggestionType())
                .isEqualTo(SEARCH_SUGGESTION_HISTORY);
    }

    @Test
    public void testSaveSuggestionSearchRequestHistory() {
        final String mediaSetID = "MEDIA-SET-ID";
        final String authority = "com.random.authority";
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                List.of("video/mp4", "image/*", "image/gif"),
                null,
                mediaSetID,
                authority,
                SEARCH_SUGGESTION_LOCATION
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest);

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("random.authority", authority),
                                /* limit */ 10));

        assertWithMessage("Search history suggestions cannot be null")
                .that(searchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search history suggestions.")
                .that(searchSuggestions.size())
                .isEqualTo(1);

        final SearchSuggestion result = searchSuggestions.get(0);
        assertWithMessage("Search history search text is not as expected")
                .that(result.getSearchText())
                .isNull();
        assertWithMessage("Search history media set id is not as expected")
                .that(result.getMediaSetId())
                .isEqualTo(mediaSetID);
        assertWithMessage("Search history authority is not as expected")
                .that(result.getAuthority())
                .isEqualTo(authority);
        assertWithMessage("Search history suggestion type is not as expected")
                .that(result.getSearchSuggestionType())
                .isEqualTo(SEARCH_SUGGESTION_HISTORY);
    }

    @Test
    public void testQueryHistoryForProviders() {
        final String mediaSetID = "MEDIA-SET-ID";
        final String authority = "com.random.authority";
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                List.of("video/mp4", "image/*", "image/gif"),
                null,
                mediaSetID,
                authority,
                SEARCH_SUGGESTION_LOCATION
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest);

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("random.authority",
                                        "another.random.authority"),
                                /* limit */ 10));

        assertWithMessage("Search history suggestions cannot be null")
                .that(searchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search history suggestions.")
                .that(searchSuggestions.size())
                .isEqualTo(0);
    }

    @Test
    public void testQueryHistorySortOrder() {
        final String searchText1 = "mountains";
        final SearchTextRequest searchRequest1 = new SearchTextRequest(
                /* mimeTypes */ null,
                searchText1
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest1);

        final String mediaSetId2 = "MEDIA-SET-ID";
        final String authority2 = "com.random.authority";
        SearchSuggestionRequest searchRequest2 = new SearchSuggestionRequest(
                List.of("video/mp4", "image/*", "image/gif"),
                null,
                mediaSetId2,
                authority2,
                SEARCH_SUGGESTION_LOCATION
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest2);

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of(authority2),
                                /* limit */ 10));

        assertWithMessage("Search history suggestions cannot be null")
                .that(searchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search history suggestions.")
                .that(searchSuggestions.size())
                .isEqualTo(2);

        final SearchSuggestion firstSuggestion = searchSuggestions.get(0);
        assertWithMessage("Search history search text is not as expected")
                .that(firstSuggestion.getSearchText())
                .isNull();
        assertWithMessage("Search history media set id is not as expected")
                .that(firstSuggestion.getMediaSetId())
                .isEqualTo(mediaSetId2);
        assertWithMessage("Search history authority is not as expected")
                .that(firstSuggestion.getAuthority())
                .isEqualTo(authority2);
        assertWithMessage("Search history suggestion type is not as expected")
                .that(firstSuggestion.getSearchSuggestionType())
                .isEqualTo(SEARCH_SUGGESTION_HISTORY);

        final SearchSuggestion secondSuggestion = searchSuggestions.get(1);
        assertWithMessage("Search history search text is not as expected")
                .that(secondSuggestion.getSearchText())
                .isEqualTo(searchText1);
        assertWithMessage("Search history media set id is not as expected")
                .that(secondSuggestion.getMediaSetId())
                .isNull();
        assertWithMessage("Search history authority is not as expected")
                .that(secondSuggestion.getAuthority())
                .isNull();
        assertWithMessage("Search history suggestion type is not as expected")
                .that(secondSuggestion.getSearchSuggestionType())
                .isEqualTo(SEARCH_SUGGESTION_HISTORY);
    }

    @Test
    public void testQueryHistoryLimit() {
        final String searchText1 = "mountains";
        final SearchTextRequest searchRequest1 = new SearchTextRequest(
                /* mimeTypes */ null,
                searchText1
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest1);

        final String mediaSetId2 = "MEDIA-SET-ID";
        final String authority2 = "com.random.authority";
        SearchSuggestionRequest searchRequest2 = new SearchSuggestionRequest(
                List.of("video/mp4", "image/*", "image/gif"),
                null,
                mediaSetId2,
                authority2,
                SEARCH_SUGGESTION_LOCATION
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest2);

        final String searchText3 = "Mormot";
        final SearchTextRequest searchRequest3 = new SearchTextRequest(
                /* mimeTypes */ null,
                searchText3
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest3);

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of(authority2),
                                /* limit */ 10,
                                /* historyLimit */ 2,
                                /* prefix */ "mo"));

        assertWithMessage("Search history suggestions cannot be null")
                .that(searchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search history suggestions.")
                .that(searchSuggestions.size())
                .isEqualTo(2);

        final SearchSuggestion suggestion1 = searchSuggestions.get(0);
        assertWithMessage("Search history search text is not as expected")
                .that(suggestion1.getSearchText())
                .isEqualTo(searchText3);

        final SearchSuggestion suggestion2 = searchSuggestions.get(1);
        assertWithMessage("Search history search text is not as expected")
                .that(suggestion2.getSearchText())
                .isEqualTo(searchText1);
    }

    @Test
    public void testHistoryPrefixFilter() {
        final String searchText1 = "mountains";
        final SearchTextRequest searchRequest1 = new SearchTextRequest(
                /* mimeTypes */ null,
                searchText1
        );
        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest1);

        final String searchText2 = "beaches";
        SearchSuggestionRequest searchRequest2 = new SearchSuggestionRequest(
                List.of("video/mp4", "image/*", "image/gif"),
                searchText2,
                "mediaSetId",
                "authority",
                SEARCH_SUGGESTION_LOCATION
        );
        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest2);

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("authority"),
                                /* limit */ 10,
                                /* historyLimit */ 3,
                                /* prefix */ "Beach"));

        assertWithMessage("Search history suggestions cannot be null")
                .that(searchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search history suggestions.")
                .that(searchSuggestions.size())
                .isEqualTo(1);

        final SearchSuggestion result = searchSuggestions.get(0);
        assertWithMessage("Search history search text is not as expected")
                .that(result.getSearchText())
                .isEqualTo(searchText2);
    }

    @Test
    public void testExtractSearchSuggestions() {
        final String authority = "authority";
        final SearchSuggestion expectedSearchSuggestion = new SearchSuggestion(
                /* searchText */ "mountains",
                /* mediaSetId */ "media-set-id",
                authority,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ "media-id"
        );

        try (Cursor cursor = getCursor(List.of(expectedSearchSuggestion))) {
            final List<SearchSuggestion> result =
                    SearchSuggestionsDatabaseUtils.extractSearchSuggestions(cursor, authority);

            assertWithMessage("Search suggestions cannot be null")
                    .that(result)
                    .isNotNull();
            assertWithMessage("Unexpected number of search suggestions.")
                    .that(result.size())
                    .isEqualTo(1);
            assertWithMessage("Search text is not as expected")
                    .that(result.get(0).getSearchText())
                    .isEqualTo(expectedSearchSuggestion.getSearchText());
            assertWithMessage("Media set id is not as expected")
                    .that(result.get(0).getMediaSetId())
                    .isEqualTo(expectedSearchSuggestion.getMediaSetId());
            assertWithMessage("Authority is not as expected")
                    .that(result.get(0).getAuthority())
                    .isEqualTo(expectedSearchSuggestion.getAuthority());
            assertWithMessage("Suggestion type is not as expected")
                    .that(result.get(0).getSearchSuggestionType())
                    .isEqualTo(expectedSearchSuggestion.getSearchSuggestionType());
        }
    }

    @Test
    public void testSaveSuggestionSearchCache() {
        final String mediaSetId1 = "MEDIA-SET-ID-1";
        final String authority1 = "com.random.authority";
        SearchSuggestion searchSuggestion1 = new SearchSuggestion(
                /* searchText */ null,
                mediaSetId1,
                authority1,
                SEARCH_SUGGESTION_LOCATION,
                /* coverMediaId */ null
        );

        final String mediaSetId2 = "MEDIA-SET-ID-2";
        final String authority2 = "com.another.random.authority";
        SearchSuggestion searchSuggestion2 = new SearchSuggestion(
                /* searchText */ null,
                mediaSetId2,
                authority2,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ null
        );

        final String mediaSetId3 = "MEDIA-SET-ID-3";
        SearchSuggestion searchSuggestion3 = new SearchSuggestion(
                /* searchText */ null,
                mediaSetId3,
                authority2,
                SEARCH_SUGGESTION_FACE,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                mDatabase, authority1, List.of(searchSuggestion1));
        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                mDatabase, authority2, List.of(searchSuggestion2, searchSuggestion3));

        final List<SearchSuggestion> resultSearchSuggestions1 =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                            /* providers */ List.of("test", authority1),
                            /* limit */ 10));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions1)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions1.size())
                .isEqualTo(1);

        final SearchSuggestion result1 = resultSearchSuggestions1.get(0);
        assertWithMessage("Search search text is not as expected")
                .that(result1.getSearchText())
                .isNull();
        assertWithMessage("Search media set id is not as expected")
                .that(result1.getMediaSetId())
                .isEqualTo(searchSuggestion1.getMediaSetId());
        assertWithMessage("Search authority is not as expected")
                .that(result1.getAuthority())
                .isEqualTo(searchSuggestion1.getAuthority());
        assertWithMessage("Search suggestion type is not as expected")
                .that(result1.getSearchSuggestionType())
                .isEqualTo(searchSuggestion1.getSearchSuggestionType());

        final List<SearchSuggestion> resultSearchSuggestions2 =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("test", authority2),
                                /* limit */ 10));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions2)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions2.size())
                .isEqualTo(2);

        final SearchSuggestion result2 = resultSearchSuggestions2.get(0);
        assertWithMessage("Search search text is not as expected")
                .that(result2.getSearchText())
                .isNull();
        assertWithMessage("Search media set id is not as expected")
                .that(result2.getMediaSetId())
                .isEqualTo(searchSuggestion2.getMediaSetId());
        assertWithMessage("Search authority is not as expected")
                .that(result2.getAuthority())
                .isEqualTo(searchSuggestion2.getAuthority());
        assertWithMessage("Search suggestion type is not as expected")
                .that(result2.getSearchSuggestionType())
                .isEqualTo(searchSuggestion2.getSearchSuggestionType());
    }

    @Test
    public void testSuggestionsCachePrefixFilter() {
        final String searchText1 = "BUTTER";
        final String mediaSetId1 = "MEDIA-SET-ID-1";
        final String authority = "com.random.authority";
        SearchSuggestion searchSuggestion1 = new SearchSuggestion(
                searchText1,
                mediaSetId1,
                authority,
                SEARCH_SUGGESTION_LOCATION,
                /* coverMediaId */ null
        );

        final String searchText2 = "Butterfly";
        final String mediaSetId2 = "MEDIA-SET-ID-2";
        SearchSuggestion searchSuggestion2 = new SearchSuggestion(
                searchText2,
                mediaSetId2,
                authority,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ null
        );

        final String searchText3 = "dragon";
        final String mediaSetId3 = "MEDIA-SET-ID-3";
        SearchSuggestion searchSuggestion3 = new SearchSuggestion(
                searchText3,
                mediaSetId3,
                authority,
                SEARCH_SUGGESTION_FACE,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(mDatabase, authority,
                List.of(searchSuggestion1, searchSuggestion2, searchSuggestion3));

        final List<SearchSuggestion> resultSearchSuggestions =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("test", authority),
                                /* limit */ 10,
                                /* historyLimit */ 3,
                                /* prefix */ "but"));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions.size())
                .isEqualTo(2);

        assertWithMessage("Search search text is not as expected")
                .that(resultSearchSuggestions.get(0).getSearchText())
                .isEqualTo(searchSuggestion1.getSearchText());
        assertWithMessage("Search search text is not as expected")
                .that(resultSearchSuggestions.get(1).getSearchText())
                .isEqualTo(searchSuggestion2.getSearchText());
    }

    @Test
    public void testSuggestionsCacheQueryLimit() {
        final String searchText1 = "BUTTER";
        final String mediaSetId1 = "MEDIA-SET-ID-1";
        final String authority = "com.random.authority";
        SearchSuggestion searchSuggestion1 = new SearchSuggestion(
                searchText1,
                mediaSetId1,
                authority,
                SEARCH_SUGGESTION_LOCATION,
                /* coverMediaId */ null
        );

        final String searchText2 = "dragon";
        final String mediaSetId2 = "MEDIA-SET-ID-2";
        SearchSuggestion searchSuggestion2 = new SearchSuggestion(
                searchText2,
                mediaSetId2,
                authority,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ null
        );

        final String searchText3 = "Butterfly";
        final String mediaSetId3 = "MEDIA-SET-ID-3";
        SearchSuggestion searchSuggestion3 = new SearchSuggestion(
                searchText3,
                mediaSetId3,
                authority,
                SEARCH_SUGGESTION_FACE,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(mDatabase, authority,
                List.of(searchSuggestion1, searchSuggestion2, searchSuggestion3));

        final List<SearchSuggestion> resultSearchSuggestions =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("test", authority),
                                /* limit */ 2,
                                /* historyLimit */ 3,
                                /* prefix */ "but"));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions.size())
                .isEqualTo(2);

        assertWithMessage("Search search text is not as expected")
                .that(resultSearchSuggestions.get(1).getSearchText())
                .isEqualTo(searchSuggestion3.getSearchText());
        assertWithMessage("Search search text is not as expected")
                .that(resultSearchSuggestions.get(0).getSearchText())
                .isEqualTo(searchSuggestion1.getSearchText());
    }

    @Test
    public void testSaveSuggestionWithNullMediaSetId() {
        final String authority = "com.random.authority";
        SearchSuggestion searchSuggestion = new SearchSuggestion(
                /* searchText */ null,
                /* mediaSetId */ null,
                authority,
                SEARCH_SUGGESTION_LOCATION,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                mDatabase, authority, List.of(searchSuggestion));

        final List<SearchSuggestion> resultSearchSuggestions =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("random.authority", authority),
                                /* limit */ 10));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions.size())
                .isEqualTo(0);
    }

    @Test
    public void testCleanUpSearchSuggestionsBeforeCaching() {
        final String authority = "com.random.authority";
        SearchSuggestion searchSuggestion1 = new SearchSuggestion(
                /* searchText */ null,
                "media-set-id",
                authority,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                mDatabase, authority, List.of(searchSuggestion1));

        final List<SearchSuggestion> resultSearchSuggestions1 =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("random.authority", authority),
                                /* limit */ 10));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions1)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions1.size())
                .isEqualTo(1);

        SearchSuggestion searchSuggestion2 = new SearchSuggestion(
                /* searchText */ null,
                "media-set-id",
                authority,
                SEARCH_SUGGESTION_FACE,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                mDatabase, authority, List.of(searchSuggestion2));

        final List<SearchSuggestion> resultSearchSuggestions2 =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(
                        mDatabase,
                        getSearchSuggestionQuery(
                                /* providers */ List.of("random.authority", authority),
                                /* limit */ 10));

        assertWithMessage("Search suggestions cannot be null")
                .that(resultSearchSuggestions2)
                .isNotNull();
        assertWithMessage("Unexpected number of search suggestions.")
                .that(resultSearchSuggestions2.size())
                .isEqualTo(1);
    }

    @Test
    public void testClearExpiredCachedSuggestions() {
        final String authority = "com.random.authority";
        SearchSuggestion searchSuggestion1 = new SearchSuggestion(
                /* searchText */ null,
                "media-set-id1",
                authority,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ null
        );
        SearchSuggestion searchSuggestion2 = new SearchSuggestion(
                /* searchText */ "test",
                "media-set-id2",
                authority,
                SEARCH_SUGGESTION_ALBUM,
                /* coverMediaId */ null
        );

        SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                mDatabase, authority, List.of(searchSuggestion1, searchSuggestion2));

        final ContentValues contentValues = new ContentValues();
        contentValues.put(
                PickerSQLConstants.SearchSuggestionsTableColumns.CREATION_TIME_MS.getColumnName(),
                System.currentTimeMillis()
                        - TimeUnit.DAYS.toMillis(TTL_CACHED_SUGGESTIONS_IN_DAYS + 1));
        mDatabase.update(
                PickerSQLConstants.Table.SEARCH_SUGGESTION.name(),
                contentValues,
                PickerSQLConstants.SearchSuggestionsTableColumns.MEDIA_SET_ID.getColumnName()
                        + " = 'media-set-id2'", null);

        final int rowsDeletedCount =
                SearchSuggestionsDatabaseUtils.clearExpiredCachedSearchSuggestions(mDatabase);
        assertWithMessage("Unexpected number of expired cached suggestions deleted.")
                .that(rowsDeletedCount)
                .isEqualTo(1);
    }

    @Test
    public void testClearExpiredHistorySuggestions() {
        SearchRequest searchRequest1 = new SearchTextRequest(
                /* mimeTypes */ null,
                "summer"
        );
        SearchRequest searchRequest2 = new SearchTextRequest(
                /* mimeTypes */ null,
                "coffee"
        );

        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest1);
        SearchSuggestionsDatabaseUtils.saveSearchHistory(mDatabase, searchRequest2);

        final ContentValues contentValues = new ContentValues();
        contentValues.put(
                PickerSQLConstants.SearchHistoryTableColumns.CREATION_TIME_MS.getColumnName(),
                System.currentTimeMillis()
                        - TimeUnit.DAYS.toMillis(TTL_HISTORY_SUGGESTIONS_IN_DAYS + 1));
        mDatabase.update(
                PickerSQLConstants.Table.SEARCH_HISTORY.name(),
                contentValues,
                PickerSQLConstants.SearchHistoryTableColumns.SEARCH_TEXT.getColumnName()
                        + " = 'summer'", null);

        final int rowsDeletedCount =
                SearchSuggestionsDatabaseUtils.clearExpiredHistorySearchSuggestions(mDatabase);
        assertWithMessage("Unexpected number of expired history suggestions deleted.")
                .that(rowsDeletedCount)
                .isEqualTo(1);
    }

    private Cursor getCursor(@NonNull List<SearchSuggestion> searchSuggestions) {
        final MatrixCursor cursor = new MatrixCursor(
                CloudMediaProviderContract.SearchSuggestionColumns.ALL_PROJECTION);

        for (SearchSuggestion searchSuggestion : searchSuggestions) {
            cursor.addRow(List.of(
                    searchSuggestion.getMediaSetId(),
                    searchSuggestion.getSearchText(),
                    searchSuggestion.getSearchSuggestionType(),
                    searchSuggestion.getCoverMediaId()
            ).toArray(new Object[4]));
        }

        return cursor;
    }

    private SearchSuggestionsQuery getSearchSuggestionQuery(@Nullable List<String> providers,
                                                            int limit) {
        return getSearchSuggestionQuery(providers, limit, 10, "");
    }

    private SearchSuggestionsQuery getSearchSuggestionQuery(@Nullable List<String> providers,
                                                            int limit,
                                                            int historyLimit,
                                                            @Nullable String prefix) {
        final Bundle bundle = new Bundle();
        bundle.putInt("limit", limit);
        bundle.putStringArrayList("providers", new ArrayList<>(providers));
        bundle.putInt("history_limit", historyLimit);
        bundle.putString("prefix", prefix);
        return new SearchSuggestionsQuery(bundle);
    }
}
