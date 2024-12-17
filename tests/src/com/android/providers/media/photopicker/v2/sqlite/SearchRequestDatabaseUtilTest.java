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

import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_LOCATION;
import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_TEXT;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

public class SearchRequestDatabaseUtilTest {
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
    public void testInsertSearchTextRequest() {
        final SearchTextRequest searchRequest = new SearchTextRequest(
                /* mimeTypes */ null,
                "mountains"
        );

        final long firstInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        assertWithMessage("Insert search request failed")
                .that(firstInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        final long secondInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        assertWithMessage("Second insert for same search request should fail silently")
                .that(secondInsertResult)
                .isEqualTo(/* failed to insert row on constraint conflict */ -1);
    }

    @Test
    public void testInsertSearchSuggestionRequest() {
        final SearchSuggestionRequest suggestionRequest = new SearchSuggestionRequest(
                /* mimeTypes */ null,
                "mountains",
                "media-set-id",
                "authority",
                SEARCH_SUGGESTION_TEXT
        );

        final long firstInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, suggestionRequest);
        assertWithMessage("Insert search request failed")
                .that(firstInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        final long secondInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, suggestionRequest);
        assertWithMessage("Second insert for same search request should fail silently")
                .that(secondInsertResult)
                .isEqualTo(/* failed to insert row on constraint conflict */ -1);
    }

    @Test
    public void testInsertSearchRequestsWithSameQuery() {
        // Insert a search text request with "mountains" search text. This insert should be
        // successful.
        final SearchTextRequest searchRequest1 = new SearchTextRequest(
                /* mimeTypes */ null,
                "mountains"
        );

        final long firstInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest1);
        assertWithMessage("Insert search request failed")
                .that(firstInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        // Insert search suggestion request with "mountains" search text. This insert should be
        // successful.
        final SearchSuggestionRequest searchRequest2 = new SearchSuggestionRequest(
                /* mimeTypes */ null,
                "mountains",
                "media-set-id",
                "authority",
                SEARCH_SUGGESTION_TEXT
        );

        final long secondInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest2);
        assertWithMessage("Insert search request failed")
                .that(secondInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        // Insert search text request with "Mountains" search text. This insert should be
        // successful since search text is text sensitive.
        final SearchTextRequest searchRequest3 = new SearchTextRequest(
                /* mimeTypes */ null,
                "Mountains"
        );

        final long thirdInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest3);
        assertWithMessage("Insert search request failed")
                .that(thirdInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        // Insert search text request with "mountains" search text but a different media set id
        // than before. This insert should be successful since search text is text sensitive.
        final SearchSuggestionRequest searchRequest4 = new SearchSuggestionRequest(
                /* mimeTypes */ null,
                "mountains",
                "different-media-set-id",
                "authority",
                SEARCH_SUGGESTION_TEXT
        );

        final long fourthInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest4);
        assertWithMessage("Insert search request failed")
                .that(fourthInsertResult)
                .isAtLeast(/* minimum row id */ 0);
    }

    @Test
    public void testMimeTypeUniqueConstraintSearchRequest() {
        SearchTextRequest request = new SearchTextRequest(
                /* mimeTypes */ List.of("image/*", "video/*", "image/gif"),
                /* searchText */ "volcano"
        );

        final long firstInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, request);
        assertWithMessage("Insert search request failed")
                .that(firstInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        request = new SearchTextRequest(
                /* mimeTypes */ List.of("image/gif", "video/*", "image/*"),
                /* searchText */ "volcano"
        );
        final long secondInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, request);
        assertWithMessage("Second insert for same search request should fail silently")
                .that(secondInsertResult)
                .isEqualTo(/* failed to insert row on constraint conflict */ -1);

        request = new SearchTextRequest(
                /* mimeTypes */ List.of("image/GIF", "Video/*", "IMAGE/*"),
                /* searchText */ "volcano"
        );
        final long thirdInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, request);
        assertWithMessage("Third insert for same search request should fail silently")
                .that(thirdInsertResult)
                .isEqualTo(/* failed to insert row on constraint conflict */ -1);
    }

    @Test
    public void testGetSearchRequestID() {
        SearchTextRequest searchRequest = new SearchTextRequest(
                /* mimeTypes */ null,
                "mountains"
        );
        assertWithMessage("Search request should not exist in database yet")
                .that(SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest))
                .isEqualTo(/* expectedRequestID */ -1);


        final long firstInsertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        assertWithMessage("Insert search request failed")
                .that(firstInsertResult)
                .isAtLeast(/* minimum row id */ 0);

        assertWithMessage("Search request ID should exist in DB")
                .that(SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest))
                .isAtLeast(0);

        searchRequest = new SearchTextRequest(
                /* mimeTypes */ List.of("image/*"),
                "mountains"
        );
        assertWithMessage("Search request should not exist in database for the given mime types")
                .that(SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest))
                .isEqualTo(/* expectedRequestID */ -1);
    }

    @Test
    public void testGetSearchTextRequestDetails() {
        final List<String> mimeTypes = List.of("video/mp4", "image/*", "image/gif");
        final String searchText = "mountains";
        final String cloudResumeKey = "RANDOM_RESUME_KEY_CLOUD";
        final String localResumeKey = "RANDOM_RESUME_KEY_LOCAL";
        final String cloudAuthority = "com.random.cloud.authority";
        final String localAuthority = "com.random.local.authority";
        SearchTextRequest searchRequest = new SearchTextRequest(
                mimeTypes,
                searchText,
                localResumeKey,
                localAuthority,
                cloudResumeKey,
                cloudAuthority
        );

        // Insert a search request
        final long insertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        assertWithMessage("Insert search request failed")
                .that(insertResult)
                .isAtLeast(/* minimum row id */ 0);

        // Get search request ID
        final int searchRequestID =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);
        assertWithMessage("Search request ID should exist in DB")
                .that(searchRequestID)
                .isAtLeast(0);

        // Fetch search details from search request ID
        final SearchRequest resultSearchRequest =
                SearchRequestDatabaseUtil.getSearchRequestDetails(mDatabase, searchRequestID);
        assertWithMessage("Unable to fetch search details from the database")
                .that(resultSearchRequest)
                .isNotNull();
        assertWithMessage("Search request should be an instance of SearchTextRequest")
                .that(resultSearchRequest)
                .isInstanceOf(SearchTextRequest.class);
        assertWithMessage("Search request mime types are not as expected")
                .that(resultSearchRequest.getMimeTypes())
                .containsExactlyElementsIn(mimeTypes);
        assertWithMessage("Search request cloud resume key is not as expected")
                .that(resultSearchRequest.getCloudSyncResumeKey())
                .isEqualTo(cloudResumeKey);
        assertWithMessage("Search request cloud authority is not as expected")
                .that(resultSearchRequest.getCloudAuthority())
                .isEqualTo(cloudAuthority);
        assertWithMessage("Search request local resume key is not as expected")
                .that(resultSearchRequest.getLocalSyncResumeKey())
                .isEqualTo(localResumeKey);
        assertWithMessage("Search request local authority is not as expected")
                .that(resultSearchRequest.getLocalAuthority())
                .isEqualTo(localAuthority);

        final SearchTextRequest resultSearchTextRequest = (SearchTextRequest) resultSearchRequest;
        assertWithMessage("Search request search text is not as expected")
                .that(resultSearchTextRequest.getSearchText())
                .isEqualTo(searchText);
    }

    @Test
    public void testGetSearchSuggestionRequestDetails() {
        final List<String> mimeTypes = List.of("video/mp4", "image/*", "image/gif");
        final String cloudResumeKey = "RANDOM_RESUME_KEY_CLOUD";
        final String localResumeKey = "RANDOM_RESUME_KEY_LOCAL";
        final String mediaSetID = "MEDIA-SET-ID";
        final String cloudAuthority = "com.random.cloud.authority";
        final String localAuthority = "com.random.local.authority";
        final String suggestionType = SEARCH_SUGGESTION_LOCATION;
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                mimeTypes,
                null,
                mediaSetID,
                cloudAuthority,
                suggestionType,
                localResumeKey,
                localAuthority,
                cloudResumeKey,
                cloudAuthority
        );

        // Insert a search request
        final long insertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        assertWithMessage("Insert search request failed")
                .that(insertResult)
                .isAtLeast(/* minimum row id */ 0);

        // Get search request ID
        final int searchRequestID =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);
        assertWithMessage("Search request ID should exist in DB")
                .that(searchRequestID)
                .isAtLeast(0);

        // Fetch search details from search request ID
        final SearchRequest resultSearchRequest =
                SearchRequestDatabaseUtil.getSearchRequestDetails(mDatabase, searchRequestID);
        assertWithMessage("Unable to fetch search details from the database")
                .that(resultSearchRequest)
                .isNotNull();
        assertWithMessage("Search request should be an instance of SearchSuggestionRequest")
                .that(resultSearchRequest)
                .isInstanceOf(SearchSuggestionRequest.class);
        assertWithMessage("Search request mime types are not as expected")
                .that(resultSearchRequest.getMimeTypes())
                .containsExactlyElementsIn(mimeTypes);
        assertWithMessage("Search request cloud resume key is not as expected")
                .that(resultSearchRequest.getCloudSyncResumeKey())
                .isEqualTo(cloudResumeKey);
        assertWithMessage("Search request cloud authority is not as expected")
                .that(resultSearchRequest.getCloudAuthority())
                .isEqualTo(cloudAuthority);
        assertWithMessage("Search request local resume key is not as expected")
                .that(resultSearchRequest.getLocalSyncResumeKey())
                .isEqualTo(localResumeKey);
        assertWithMessage("Search request local authority is not as expected")
                .that(resultSearchRequest.getLocalAuthority())
                .isEqualTo(localAuthority);

        final SearchSuggestionRequest resultSearchSuggestionRequest =
                (SearchSuggestionRequest) resultSearchRequest;
        assertWithMessage("Search request search text is not as expected")
                .that(resultSearchSuggestionRequest.getSearchSuggestion().getSearchText())
                .isNull();
        assertWithMessage("Search request search text is not as expected")
                .that(resultSearchSuggestionRequest.getSearchSuggestion().getMediaSetId())
                .isEqualTo(mediaSetID);
        assertWithMessage("Search request search text is not as expected")
                .that(resultSearchSuggestionRequest.getSearchSuggestion().getAuthority())
                .isEqualTo(cloudAuthority);
        assertWithMessage("Search request search text is not as expected")
                .that(resultSearchSuggestionRequest.getSearchSuggestion().getSearchSuggestionType())
                .isEqualTo(suggestionType);
    }

    @Test
    public void testResumeKeyUpdate() {
        final List<String> mimeTypes = List.of("video/mp4", "image/*", "image/gif");
        final String mediaSetID = "MEDIA-SET-ID";
        final String authority = "com.random.authority";
        final String suggestionType = SEARCH_SUGGESTION_LOCATION;
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                mimeTypes,
                null,
                mediaSetID,
                authority,
                suggestionType
        );

        // Insert a search request
        final long insertResult =
                SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        assertWithMessage("Insert search request failed")
                .that(insertResult)
                .isAtLeast(/* minimum row id */ 0);

        // Get search request ID
        final int searchRequestID =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);
        assertWithMessage("Search request ID should exist in DB")
                .that(searchRequestID)
                .isAtLeast(0);

        // Fetch search details from search request ID
        final SearchRequest savedSearchRequest =
                SearchRequestDatabaseUtil.getSearchRequestDetails(mDatabase, searchRequestID);
        assertWithMessage("Search request is null")
                .that(savedSearchRequest)
                .isNotNull();
        assertWithMessage("Initial search request cloud resume key is not null")
                .that(savedSearchRequest.getCloudSyncResumeKey())
                .isNull();
        assertWithMessage("Initial search request cloud authority is not null")
                .that(savedSearchRequest.getCloudAuthority())
                .isNull();
        assertWithMessage("Initial search request local sync resume key is not null")
                .that(savedSearchRequest.getLocalSyncResumeKey())
                .isNull();
        assertWithMessage("Initial search request local authority is not null")
                .that(savedSearchRequest.getLocalAuthority())
                .isNull();

        // Update cloud resume key and save
        final String cloudResumeKey = "CLOUD_RESUME_KEY";
        final String cloudAuthority = "CLOUD_AUTHORITY";
        SearchRequestDatabaseUtil.updateResumeKey(mDatabase, searchRequestID, cloudResumeKey,
                cloudAuthority, /* isLocal */ false);

        // Fetch updated search details from search request ID
        final SearchRequest updatedSearchRequest1 =
                SearchRequestDatabaseUtil.getSearchRequestDetails(mDatabase, searchRequestID);
        assertWithMessage("Search request is null")
                .that(updatedSearchRequest1)
                .isNotNull();
        assertWithMessage("Search request cloud resume key is not as expected")
                .that(updatedSearchRequest1.getCloudSyncResumeKey())
                .isEqualTo(cloudResumeKey);
        assertWithMessage("Search request cloud authority is not as expected")
                .that(updatedSearchRequest1.getCloudAuthority())
                .isEqualTo(cloudAuthority);
        assertWithMessage("Search request local sync resume key is not null")
                .that(updatedSearchRequest1.getLocalSyncResumeKey())
                .isNull();
        assertWithMessage("Initial search request local authority is not null")
                .that(updatedSearchRequest1.getLocalAuthority())
                .isNull();

        // Update local resume key and save
        final String localResumeKey = "LOCAL_RESUME_KEY";
        final String localAuthority = "LOCAL_AUTHORITY";
        SearchRequestDatabaseUtil.updateResumeKey(mDatabase, searchRequestID, localResumeKey,
                localAuthority, /* isLocal */ true);

        // Clear cloud resume key
        SearchRequestDatabaseUtil.clearSyncResumeInfo(mDatabase, List.of(searchRequestID),
                /* isLocal */ false);

        // Fetch updated search details from search request ID
        final SearchRequest updatedSearchRequest2 =
                SearchRequestDatabaseUtil.getSearchRequestDetails(mDatabase, searchRequestID);
        assertWithMessage("Search request is null")
                .that(updatedSearchRequest2)
                .isNotNull();
        assertWithMessage("Search request local resume key is not as expected")
                .that(updatedSearchRequest2.getLocalSyncResumeKey())
                .isEqualTo(localResumeKey);
        assertWithMessage("Search request local authority is not as expected")
                .that(updatedSearchRequest2.getLocalAuthority())
                .isEqualTo(localAuthority);
        assertWithMessage("Search request cloud sync resume key is not null")
                .that(updatedSearchRequest2.getCloudSyncResumeKey())
                .isNull();
        assertWithMessage("Initial search request cloud authority is not null")
                .that(updatedSearchRequest2.getCloudAuthority())
                .isNull();
    }
}
