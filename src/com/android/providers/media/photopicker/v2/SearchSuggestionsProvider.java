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

import static android.provider.MediaStore.MY_USER_ID;

import static androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.SearchState;
import com.android.providers.media.photopicker.sync.PickerSearchProviderClient;
import com.android.providers.media.photopicker.v2.model.SearchSuggestion;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsDatabaseUtils;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Class that encapsulates logic to provide search suggestions.
 */
public class SearchSuggestionsProvider {
    private static final String TAG = "SearchSuggestionsProvider";

    /**
     * Caches input search suggestions if they are zero state suggestions.
     *
     * @param query SearchSuggestionsQuery object
     * @param suggestions List of search suggestions received from a cloud media provider.
     * @return true if the suggestions were cached, else returns false.
     */
    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    public static boolean maybeCacheSearchSuggestions(
            @NonNull SearchSuggestionsQuery query,
            @Nullable List<SearchSuggestion> suggestions) {
        if (!query.isZeroState()) {
            Log.d(TAG, "Skip caching search suggestions for non-zero state");
            return false;
        }

        if (suggestions == null || suggestions.isEmpty()) {
            Log.d(TAG, "Received no suggestions to cache.");
            return false;
        }

        final String authority = suggestions.get(0).getAuthority();

        try {
            final int numberOfInsertedRows = SearchSuggestionsDatabaseUtils.cacheSearchSuggestions(
                    PickerSyncController.getInstanceOrThrow().getDbFacade().getDatabase(),
                    authority,
                    suggestions);

            Log.d(TAG, String.format("Cached %d search suggestions from authority %s",
                    numberOfInsertedRows, authority));
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, String.format("Could not cache search suggestions from authority %s",
                    authority));
            return false;
        }
    }

    /**
     * Converts suggestions to a cursor with
     * {@link PickerSQLConstants.SearchSuggestionsResponseColumns}
     *
     * @param suggestions Input list of suggestions.
     * @return a cursor with formatted suggestions.
     */
    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    public static Cursor suggestionsToCursor(
            @NonNull List<SearchSuggestion> suggestions) {
        requireNonNull(suggestions);

        final MatrixCursor cursor = new MatrixCursor(List.of(
                PickerSQLConstants.SearchSuggestionsResponseColumns.AUTHORITY.getProjection(),
                PickerSQLConstants.SearchSuggestionsResponseColumns.MEDIA_SET_ID.getProjection(),
                PickerSQLConstants.SearchSuggestionsResponseColumns.SEARCH_TEXT.getProjection(),
                PickerSQLConstants.SearchSuggestionsResponseColumns.SUGGESTION_TYPE.getProjection(),
                PickerSQLConstants.SearchSuggestionsResponseColumns.COVER_MEDIA_URI.getProjection()
        ).toArray(new String[5]));

        for (SearchSuggestion suggestion : suggestions) {
            final String coverMediaUri;
            if (suggestion.getAuthority() != null && suggestion.getCoverMediaId() != null) {
                coverMediaUri = PickerUriResolver
                        .getMediaUri(MY_USER_ID + "@" + suggestion.getAuthority())
                        .buildUpon().appendPath(suggestion.getCoverMediaId())
                        .build().toString();
            } else {
                coverMediaUri = null;
            }

            final ArrayList<Object> row = new ArrayList<Object>();
            row.add(suggestion.getAuthority());
            row.add(suggestion.getMediaSetId());
            row.add(suggestion.getSearchText());
            row.add(suggestion.getSearchSuggestionType());
            row.add(coverMediaUri);
            cursor.addRow(row.toArray(new Object[5]));
        }

        return cursor;
    }

    static List<SearchSuggestion> getDefaultSuggestions() {
        //TODO(b/378511004) to be implemented
        return new ArrayList<>();
    }

    /**
     * Fetches suggestions from a cloud provider.
     *
     * @param appContext Application context.
     * @param query A SearchSuggestionsQuery object.
     * @param cancellationSignal CancellationSignal that helps you propagate that the query has been
     *                          cancelled.
     * @return A list of valid search suggestions received from the cloud provider.
     */
    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    @NonNull
    public static List<SearchSuggestion> getSuggestionsFromCloudProvider(
            @NonNull Context appContext,
            @NonNull SearchSuggestionsQuery query,
            @Nullable CancellationSignal cancellationSignal) {
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final SearchState searchState = syncController.getSearchState();
        final String cloudAuthority = syncController
                .getCloudProviderOrDefault(/* defaultValue */ null);

        final String effectiveCloudAuthority =
                (syncController.shouldQueryCloudMedia(query.getProviderAuthorities(),
                        cloudAuthority) && searchState.isCloudSearchEnabled(appContext))
                        ? cloudAuthority
                        : null;

        if (effectiveCloudAuthority == null) {
            return new ArrayList<>();
        }

        return getSuggestionsFromCmp(appContext, query, effectiveCloudAuthority,
                cancellationSignal);
    }

    /**
     * Fetches suggestions from a local provider.
     *
     * @param appContext Application context.
     * @param query A SearchSuggestionsQuery object.
     * @param cancellationSignal CancellationSignal that helps you propagate that the query has been
     *                          cancelled.
     * @return A list of valid search suggestions received from the local provider.
     */
    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    @NonNull
    public static List<SearchSuggestion> getSuggestionsFromLocalProvider(
            @NonNull Context appContext,
            @NonNull SearchSuggestionsQuery query,
            @Nullable CancellationSignal cancellationSignal) {
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final SearchState searchState = syncController.getSearchState();

        final String effectiveLocalAuthority =
                (query.getProviderAuthorities().contains(syncController.getLocalProvider())
                        && searchState.isLocalSearchEnabled())
                        ? syncController.getLocalProvider()
                        : null;

        if (effectiveLocalAuthority == null) {
            return new ArrayList<>();
        }

        return getSuggestionsFromCmp(appContext, query, effectiveLocalAuthority,
                cancellationSignal);
    }

    /**
     * Fetches suggestions from a cloud media provider. This should be an asynchronous call because
     * it might take a while to fetch the results. It is recommended to cancel the request using
     * input CancellationSignal when the results are no longer needed.
     *
     * @param appContext Application context.
     * @param query A SearchSuggestionsQuery object.
     * @param cancellationSignal CancellationSignal that helps you propagate that the query has been
     *                          cancelled.
     * @param authority Authority of the cloud media provider. This could be the authority of the
     *                  local provider or the cloud provider.
     * @return A list of valid search suggestions received from the cloud media provider.
     */
    @NonNull
    private static List<SearchSuggestion> getSuggestionsFromCmp(
            @NonNull Context appContext,
            @NonNull SearchSuggestionsQuery query,
            @NonNull String authority,
            @Nullable CancellationSignal cancellationSignal) {
        final PickerSearchProviderClient searchClient =
                PickerSearchProviderClient.create(appContext, authority);

        try (Cursor cursor = searchClient.fetchSearchSuggestionsFromCmp(
                query.getPrefix(), query.getLimit(), cancellationSignal)) {

            if (cursor != null) {
                return SearchSuggestionsDatabaseUtils.extractSearchSuggestions(cursor, authority);
            }
        }

        return new ArrayList<>();
    }
}
