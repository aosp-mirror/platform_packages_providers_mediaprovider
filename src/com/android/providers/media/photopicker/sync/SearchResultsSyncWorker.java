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

package com.android.providers.media.photopicker.sync;

import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SEARCH_REQUEST_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionType;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;
import com.android.providers.media.photopicker.v2.sqlite.SearchRequestDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.SearchResultsDatabaseUtil;

import java.util.List;

/**
 * This is a {@link Worker} class responsible for syncing search results media with the
 * correct sync source.
 */
public class SearchResultsSyncWorker extends Worker {
    private static final String TAG = "SearchSyncWorker";
    private static final int SYNC_PAGE_COUNT = 3;
    private static final int PAGE_SIZE = 500;
    private static final int INVALID_SYNC_SOURCE = -1;
    private static final int INVALID_SEARCH_REQUEST_ID = -1;
    @VisibleForTesting
    public static final String SYNC_COMPLETE_RESUME_KEY = "SYNCED";
    private final Context mContext;
    private final CancellationSignal mCancellationSignal;

    /**
     * Creates an instance of the {@link Worker}.
     *
     * @param context the application {@link Context}
     * @param workerParams the set of {@link WorkerParameters}
     */
    public SearchResultsSyncWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);

        mContext = context;
        mCancellationSignal = new CancellationSignal();
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {
        // Do not allow endless re-runs of this worker, if this isn't the original run,
        // just succeed and wait until the next scheduled run.
        if (getRunAttemptCount() > 0) {
            Log.w(TAG, "Worker retry was detected, ending this run in failure.");
            return ListenableWorker.Result.failure();
        }

        final int syncSource = getInputData().getInt(SYNC_WORKER_INPUT_SYNC_SOURCE,
                /* defaultValue */ INVALID_SYNC_SOURCE);
        final int searchRequestId = getInputData().getInt(SYNC_WORKER_INPUT_SEARCH_REQUEST_ID,
                /* defaultValue */ INVALID_SEARCH_REQUEST_ID);

        Log.i(TAG, String.format(
                "Starting search results sync from sync source: %s search request id: %s",
                syncSource, searchRequestId));

        try {
            throwIfWorkerStopped();

            final SearchRequest searchRequest = SearchRequestDatabaseUtil
                    .getSearchRequestDetails(getDatabase(), searchRequestId);
            validateWorkInput(syncSource, searchRequestId, searchRequest);

            syncWithSource(syncSource, searchRequestId, searchRequest);

            Log.i(TAG, String.format(
                    "Completed search results sync from sync source: %s search request id: %s",
                    syncSource, searchRequestId));
            return ListenableWorker.Result.success();
        } catch (RuntimeException | RequestObsoleteException e) {
            Log.e(TAG, String.format("Could not complete search results sync sync from "
                            + "sync source: %s search request id: %s",
                    syncSource, searchRequestId), e);
            return ListenableWorker.Result.failure();
        }
    }

    /**
     * Sync search results with the given sync source.
     *
     * @param syncSource Identifies if we need to sync with local provider or cloud provider.
     * @param searchRequestId Identifier for the search request.
     * @param searchRequest Details of the search request.
     * @throws IllegalArgumentException If the search request could not be identified.
     * @throws RequestObsoleteException If the search request has become obsolete.
     */
    private void syncWithSource(
            int syncSource,
            int searchRequestId,
            @Nullable SearchRequest searchRequest)
            throws IllegalArgumentException, RequestObsoleteException {
        final String authority = getProviderAuthority(syncSource, searchRequest);
        final PickerSearchProviderClient searchClient =
                PickerSearchProviderClient.create(mContext, authority);

        String resumePageToken = searchRequest.getResumeKey();

        if (SYNC_COMPLETE_RESUME_KEY.equals(resumePageToken)) {
            Log.i(TAG, "Sync has already been completed.");
            return;
        }

        try {
            for (int iteration = 0; iteration < SYNC_PAGE_COUNT; iteration++) {
                throwIfWorkerStopped();
                throwIfCloudProviderHasChanged(authority);

                try (Cursor cursor = fetchSearchResultsFromCmp(
                        searchClient, searchRequest, resumePageToken)) {

                    List<ContentValues> contentValues =
                            SearchResultsDatabaseUtil.extractContentValuesList(
                                    searchRequestId, cursor, isLocal(authority));

                    SearchResultsDatabaseUtil
                            .cacheSearchResults(getDatabase(), authority, contentValues);

                    resumePageToken = getResumePageToken(cursor.getExtras());
                    if (SYNC_COMPLETE_RESUME_KEY.equals(resumePageToken)) {
                        // Stop syncing if there are no more pages to sync.
                        break;
                    }
                }
            }
        } finally {
            // Save sync resume key till the point it was performed successfully
            searchRequest.setResumeKey(resumePageToken);
            SearchRequestDatabaseUtil
                    .updateResumeKey(getDatabase(), searchRequestId, resumePageToken);
        }
    }

    /**
     * @param extras Bundle received from the CloudMediaProvider with the search results cursor.
     * @return Extracts the rsume page token from the extras and returns it. If it is not present
     * in the extras, returns {@link SearchResultsSyncWorker#SYNC_COMPLETE_RESUME_KEY}
     */
    @NonNull
    private String getResumePageToken(@Nullable Bundle extras) {
        if (extras == null
                || extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN) == null) {
            return SYNC_COMPLETE_RESUME_KEY;
        }

        return extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN);
    }

    /**
     * Get search results from the CloudMediaProvider.
     */
    @NonNull
    private Cursor fetchSearchResultsFromCmp(
            @NonNull PickerSearchProviderClient searchClient,
            @NonNull SearchRequest searchRequest,
            @Nullable String resumePageToken) {
        final String suggestedMediaSetId;
        final String searchText;
        if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            suggestedMediaSetId = searchSuggestionRequest.getMediaSetId();
            searchText = searchSuggestionRequest.getSearchText();
        } else if (searchRequest instanceof SearchTextRequest searchTextRequest) {
            suggestedMediaSetId = null;
            searchText = searchTextRequest.getSearchText();
        } else {
            throw new IllegalArgumentException("Could not recognize the type of SearchRequest");
        }

        final Cursor cursor = searchClient.fetchSearchResultsFromCmp(
                suggestedMediaSetId,
                searchText,
                CloudMediaProviderContract.SORT_ORDER_DESC_DATE_TAKEN,
                PAGE_SIZE,
                resumePageToken,
                mCancellationSignal
        );

        if (cursor == null) {
            throw new IllegalStateException("Cursor returned from provider is null.");
        }

        return cursor;
    }


    /**
     * Validates input data received by the Worker for an immediate album sync.
     */
    private void validateWorkInput(
            int syncSource,
            int searchRequestId,
            @Nullable SearchRequest searchRequest) throws IllegalArgumentException {
        // Search result sync can only happen with either local provider or cloud provider. This
        // information needs to be provided in the {@code inputData}.
        if (syncSource != SYNC_LOCAL_ONLY && syncSource != SYNC_CLOUD_ONLY) {
            throw new IllegalArgumentException("Invalid search results sync source " + syncSource);
        }
        if (searchRequestId == INVALID_SEARCH_REQUEST_ID) {
            throw new IllegalArgumentException("Invalid search request id " + searchRequestId);
        }
        if (searchRequest == null) {
            throw new IllegalArgumentException(
                    "Could not get search request details for search request id "
                            + searchRequestId);
        }
        if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            if (searchSuggestionRequest.getSearchSuggestionType() == SearchSuggestionType.ALBUM) {
                final boolean isLocal = isLocal(searchSuggestionRequest.getAuthority());

                if (isLocal && syncSource == SYNC_CLOUD_ONLY) {
                    throw new IllegalArgumentException(
                            "Cannot sync with cloud provider for local album suggestion. "
                                    + "Search request id: " + searchRequestId);
                } else if (!isLocal && syncSource == SYNC_LOCAL_ONLY) {
                    throw new IllegalArgumentException(
                            "Cannot sync with local provider for cloud album suggestion. "
                                    + "Search request id: " + searchRequestId);
                }
            }
        }
    }

    private String getProviderAuthority(
            int syncSource,
            @NonNull SearchRequest searchRequest) {
        final String authority;
        if (syncSource == SYNC_LOCAL_ONLY) {
            authority = getLocalProviderAuthority();
        } else if (syncSource == SYNC_CLOUD_ONLY) {
            authority = getCurrentCloudProviderAuthority();
        } else {
            throw new IllegalArgumentException("Invalid search results sync source " + syncSource);
        }

        if (authority == null) {
            throw new IllegalArgumentException("Authority of the provider to sync search results "
                    + "with cannot be null");
        }

        // Only in case of ALBUM type search suggestion, we want to explicitly query the source
        // suggestion authority. For the rest of the suggestion types, we can query both
        // available providers - local and cloud.
        if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            if (searchSuggestionRequest.getSearchSuggestionType() == SearchSuggestionType.ALBUM) {
                if (!authority.equals(searchSuggestionRequest.getAuthority())) {
                    throw new IllegalArgumentException(String.format(
                            "Mismatch in the suggestion source authority %s and the "
                                    + "current sync authority %s for album search results sync",
                            searchSuggestionRequest.getAuthority(),
                            authority));
                }
            }
        }

        return authority;
    }

    private void throwIfCloudProviderHasChanged(@NonNull String authority)
            throws RequestObsoleteException {
        // Local provider's authority cannot change.
        if (isLocal(authority)) {
            return;
        }

        final String currentCloudAuthority = getCurrentCloudProviderAuthority();
        if (!authority.equals(currentCloudAuthority)) {
            throw new RequestObsoleteException("Cloud provider authority has changed. "
                    + " Current cloud provider authority: " + currentCloudAuthority
                    + " Cloud provider authority to sync with: " + authority);
        }
    }

    private void throwIfWorkerStopped() throws RequestObsoleteException {
        if (isStopped()) {
            throw new RequestObsoleteException("Work is stopped " + getId());
        }
    }

    private boolean isLocal(@NonNull String authority) {
        return getLocalProviderAuthority().equals(authority);
    }

    @Nullable
    private String getLocalProviderAuthority() {
        return PickerSyncController.getInstanceOrThrow().getLocalProvider();
    }

    @Nullable
    private String getCurrentCloudProviderAuthority() {
        return PickerSyncController.getInstanceOrThrow().getCloudProvider();
    }

    private SQLiteDatabase getDatabase() {
        return PickerSyncController.getInstanceOrThrow().getDbFacade().getDatabase();
    }
}
