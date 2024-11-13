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

import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.platform.test.annotations.EnableFlags;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.SearchState;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.v2.model.SearchSuggestion;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionType;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsDatabaseUtils;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsQuery;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@EnableFlags(Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH)
public class SearchSuggestionsProviderTest {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private SearchState mSearchState;
    private Context mContext;
    private SQLiteDatabase mDatabase;
    private PickerDbFacade mFacade;

    @Before
    public void setup() {
        initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PickerSyncController.setInstance(mMockSyncController);

        final File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        final PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
        mFacade = new PickerDbFacade(
                mContext, new PickerSyncLockManager(), LOCAL_PICKER_PROVIDER_AUTHORITY);
        mFacade.setCloudProvider(SearchProvider.AUTHORITY);

        doReturn(LOCAL_PICKER_PROVIDER_AUTHORITY).when(mMockSyncController).getLocalProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(mSearchState).when(mMockSyncController).getSearchState();
        doReturn(new PickerSyncLockManager()).when(mMockSyncController).getPickerSyncLockManager();
    }

    @Test
    public void testCacheSearchSuggestionsNonZeroState() {
        final SearchSuggestionsQuery query = new SearchSuggestionsQuery(getQueryArgs("x"));
        final SearchSuggestion suggestion = new SearchSuggestion(
                "search-text",
                "media-set-id",
                "authority",
                SearchSuggestionType.ALBUM,
                null);

        final boolean result = SearchSuggestionsProvider
                .maybeCacheSearchSuggestions(query, List.of(suggestion));

        assertThat(result).isFalse();
    }

    @Test
    public void testCacheEmptySearchSuggestions() {
        final SearchSuggestionsQuery query = new SearchSuggestionsQuery(getQueryArgs(""));

        final boolean result = SearchSuggestionsProvider
                .maybeCacheSearchSuggestions(query, List.of());

        assertThat(result).isFalse();
    }

    @Test
    public void testCacheSearchSuggestions() {
        final SearchSuggestionsQuery query = new SearchSuggestionsQuery(getQueryArgs(""));
        final String mediaSetId = "media-set-id";
        final SearchSuggestion suggestion = new SearchSuggestion(
                "search-text",
                mediaSetId,
                SearchProvider.AUTHORITY,
                SearchSuggestionType.ALBUM,
                null);

        final boolean result = SearchSuggestionsProvider
                .maybeCacheSearchSuggestions(query, List.of(suggestion));

        assertThat(result).isTrue();

        final List<SearchSuggestion> searchSuggestionsResult =
                SearchSuggestionsDatabaseUtils.getCachedSuggestions(mDatabase, query);

        assertWithMessage("Search suggestions should not be null")
                .that(searchSuggestionsResult)
                .isNotNull();

        assertWithMessage("Search suggestions size is not as expected")
                .that(searchSuggestionsResult.size())
                .isEqualTo(1);

        assertWithMessage("Search suggestion media set id is not as expected")
                .that(searchSuggestionsResult.get(0).getMediaSetId())
                .isEqualTo(mediaSetId);
    }

    @Test
    public void testSuggestionsToCursor() {
        final String authority = "authority";
        final SearchSuggestion albumSuggestion = new SearchSuggestion(
                "album",
                "album-set-id",
                authority,
                SearchSuggestionType.ALBUM,
                null);
        final SearchSuggestion faceSuggestion = new SearchSuggestion(
                null,
                "face-set-id",
                authority,
                SearchSuggestionType.FACE,
                "id");
        final SearchSuggestion historySuggestion = new SearchSuggestion(
                "history",
                null,
                null,
                SearchSuggestionType.HISTORY,
                null);

        try (Cursor cursor = SearchSuggestionsProvider
                .suggestionsToCursor(List.of(albumSuggestion, faceSuggestion, historySuggestion))) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(3);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.SearchSuggestionsResponseColumns
                                    .MEDIA_SET_ID.getProjection())))
                    .isEqualTo("album-set-id");

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.SearchSuggestionsResponseColumns
                                    .MEDIA_SET_ID.getProjection())))
                    .isEqualTo("face-set-id");

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.SearchSuggestionsResponseColumns
                                    .MEDIA_SET_ID.getProjection())))
                    .isNull();
        }
    }

    @Test
    public void testSuggestionsFromCloudProvider() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(true).when(mSearchState).isCloudSearchEnabled(any());

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsProvider.getSuggestionsFromCloudProvider(
                        mContext, new SearchSuggestionsQuery(getQueryArgs("")), null);

        assertWithMessage("Suggestions should not be null")
                .that(searchSuggestions)
                .isNotNull();

        assertWithMessage("Suggestions size is not as expected")
                .that(searchSuggestions.size())
                .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getCount());

        SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToFirst();
        for (int iterator = 0; iterator < searchSuggestions.size(); iterator++) {
            assertWithMessage("Media ID is not as expected")
                    .that(searchSuggestions.get(iterator).getMediaSetId())
                    .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getString(
                            SearchProvider.DEFAULT_SUGGESTION_RESULTS.getColumnIndexOrThrow(
                                    PickerSQLConstants.SearchSuggestionsResponseColumns
                                    .MEDIA_SET_ID.getProjection())));

            SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToNext();
        }
    }

    @Test
    public void testSuggestionsFromInactiveCloudProvider() {
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(true).when(mSearchState).isCloudSearchEnabled(any());

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsProvider.getSuggestionsFromCloudProvider(
                        mContext, new SearchSuggestionsQuery(getQueryArgs("")), null);

        assertWithMessage("Suggestions should not be null")
                .that(searchSuggestions)
                .isNotNull();

        assertWithMessage("Suggestions size is not as expected")
                .that(searchSuggestions.size())
                .isEqualTo(0);
    }

    @Test
    public void testSuggestionsFromCloudProviderWithSearchDisabled() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(false).when(mSearchState).isCloudSearchEnabled(any());

        final List<SearchSuggestion> searchSuggestions =
                SearchSuggestionsProvider.getSuggestionsFromCloudProvider(
                        mContext, new SearchSuggestionsQuery(getQueryArgs("")), null);

        assertWithMessage("Suggestions should not be null")
                .that(searchSuggestions)
                .isNotNull();

        assertWithMessage("Suggestions size is not as expected")
                .that(searchSuggestions.size())
                .isEqualTo(0);
    }

    private Bundle getQueryArgs(@Nullable String prefix) {
        return getQueryArgs(prefix, new ArrayList<>(List.of(SearchProvider.AUTHORITY)));
    }

    private Bundle getQueryArgs(@Nullable String prefix, @NonNull ArrayList<String> providers) {
        final Bundle bundle = new Bundle();
        bundle.putString("prefix", prefix);
        bundle.putStringArrayList("providers", providers);
        return bundle;
    }
}
