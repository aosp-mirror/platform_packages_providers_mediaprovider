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

import static android.provider.CloudMediaProviderContract.SEARCH_SUGGESTION_ALBUM;

import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SEARCH_REQUEST_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markSearchResultsSyncAsComplete;

import static java.util.Objects.requireNonNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.v2.PickerNotificationSender;
import com.android.providers.media.photopicker.v2.model.SearchRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;
import com.android.providers.media.photopicker.v2.sqlite.SearchRequestDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.SearchResultsDatabaseUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * This is a {@link Worker} class responsible for syncing search results media with the
 * correct sync source.
 */
public class SearchResultsSyncWorker extends Worker {
    private static final String TAG = "SearchSyncWorker";
    private static final int SYNC_PAGE_COUNT = Integer.MAX_VALUE;
    private static final int PAGE_SIZE = 500;
    private static final int INVALID_SYNC_SOURCE = -1;
    private static final int INVALID_SEARCH_REQUEST_ID = -1;
    @VisibleForTesting
    public static final String SYNC_COMPLETE_RESUME_KEY = "SYNCED";
    private final Context mContext;
    private final CancellationSignal mCancellationSignal;
    private boolean mMarkedSyncWorkAsComplete = false;

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
        final int syncSource = getInputData().getInt(SYNC_WORKER_INPUT_SYNC_SOURCE,
                /* defaultValue */ INVALID_SYNC_SOURCE);
        final String syncAuthority = getInputData().getString(SYNC_WORKER_INPUT_AUTHORITY);
        final int searchRequestId = getInputData().getInt(SYNC_WORKER_INPUT_SEARCH_REQUEST_ID,
                /* defaultValue */ INVALID_SEARCH_REQUEST_ID);

        try {
            // Do not allow endless re-runs of this worker, if this isn't the original run,
            // just succeed and wait until the next scheduled run.
            if (getRunAttemptCount() > 0) {
                Log.w(TAG, "Worker retry was detected, ending this run in failure.");
                return ListenableWorker.Result.failure();
            }

            Log.i(TAG, String.format(
                    Locale.ROOT,
                    "Starting search results sync from sync source: %s, "
                            + "sync authority: %s, search request id: %s",
                    syncSource, syncAuthority, searchRequestId));

            throwIfWorkerStopped();

            final SearchRequest searchRequest = SearchRequestDatabaseUtil
                    .getSearchRequestDetails(getDatabase(), searchRequestId);
            validateWorkInput(syncSource, syncAuthority, searchRequestId, searchRequest);

            syncWithSource(syncSource, syncAuthority, searchRequestId, searchRequest);

            Log.i(TAG, String.format(
                    "Completed search results sync from sync source: %s search request id: %s",
                    syncSource, searchRequestId));
            return ListenableWorker.Result.success();
        } catch (RuntimeException | RequestObsoleteException e) {
            Log.e(TAG, String.format("Could not complete search results sync sync from "
                            + "sync source: %s search request id: %s",
                    syncSource, searchRequestId), e);
            return ListenableWorker.Result.failure();
        } finally {
            if (!mMarkedSyncWorkAsComplete) {
                markSearchResultsSyncAsComplete(syncSource, getId());
            }
        }
    }

    /**
     * Sync search results with the given sync source.
     *
     * @param syncSource      Identifies if we need to sync source type. this could be the
     *                        local provider or cloud provider.
     * @param authority   Input authority of the CMP.
     * @param searchRequestId Identifier for the search request.
     * @param searchRequest   Details of the search request.
     * @throws IllegalArgumentException If the search request could not be identified.
     * @throws RequestObsoleteException If the search request has become obsolete.
     */
    private void syncWithSource(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull String authority,
            int searchRequestId,
            @Nullable SearchRequest searchRequest)
            throws IllegalArgumentException, RequestObsoleteException {
        final PickerSearchProviderClient searchClient =
                PickerSearchProviderClient.create(mContext, authority);

        final boolean resetResumeKey =
                maybeResetResumeKey(searchRequestId, searchRequest, authority, syncSource);
        if (resetResumeKey) {
            searchRequest = requireNonNull(SearchRequestDatabaseUtil
                    .getSearchRequestDetails(getDatabase(), searchRequestId));
        }

        final Pair<String, String> resumeKey = getResumeKey(searchRequest, syncSource);

        if (SYNC_COMPLETE_RESUME_KEY.equals(resumeKey.first)) {
            Log.i(TAG, "Sync was already complete.");
            return;
        }

        final Set<String> knownTokens = new HashSet<>();
        String nextPageToken = resumeKey.first;
        if (nextPageToken != null) {
            knownTokens.add(nextPageToken);
        }

        try {
            for (int iteration = 0; iteration < SYNC_PAGE_COUNT; iteration++) {
                throwIfWorkerStopped();
                throwIfCloudProviderHasChanged(authority);

                try (Cursor cursor = fetchSearchResultsFromCmp(
                        searchClient, authority, searchRequest, nextPageToken,
                        searchRequest.getMimeTypes())) {

                    List<ContentValues> contentValues =
                            SearchResultsDatabaseUtil.extractContentValuesList(
                                    searchRequestId, cursor, isLocal(authority));

                    int numberOfRowsInserted = SearchResultsDatabaseUtil
                            .cacheSearchResults(getDatabase(), authority, contentValues,
                                    mCancellationSignal);

                    nextPageToken = getResumePageToken(cursor.getExtras());
                    if (SYNC_COMPLETE_RESUME_KEY.equals(nextPageToken)) {
                        Log.d(TAG, "Number of search results pages synced: " + (iteration + 1));
                        // Stop syncing if there are no more pages to sync.
                        break;
                    } else if (knownTokens.contains(nextPageToken)) {
                        Log.e(TAG, "Loop detected! CMP has sent the same page token twice: "
                                + nextPageToken);
                        break;
                    }
                    knownTokens.add(nextPageToken);

                    // Mark sync as completed after getting the first page to start returning
                    // search results to the UI.
                    if (mMarkedSyncWorkAsComplete) {
                        // Notify the UI that a change has been made in the DB
                        if (numberOfRowsInserted > 0) {
                            PickerNotificationSender
                                    .notifySearchResultsChange(mContext, searchRequestId);
                        }
                    } else {
                        markSearchResultsSyncAsComplete(syncSource, getId());
                        mMarkedSyncWorkAsComplete = true;
                    }
                }
            }
        } finally {
            // Save sync resume key till the point it was performed successfully
            if (nextPageToken != null) {
                setResumeKey(searchRequest, nextPageToken, syncSource);
                SearchRequestDatabaseUtil
                        .updateResumeKey(getDatabase(), searchRequestId, nextPageToken,
                                authority, isLocal(authority));
            }
        }
    }

    private boolean maybeResetResumeKey(
            int searchRequestId,
            @NonNull SearchRequest searchRequest,
            @NonNull String authority,
            @PickerSyncManager.SyncSource int syncSource) throws RequestObsoleteException {

        final Pair<String, String> resumeKey = getResumeKey(searchRequest, syncSource);
        if (resumeKey.second != null && !authority.equals(resumeKey.second)) {
            Log.w(TAG, String.format(
                    Locale.ROOT,
                    "Search request is already (fully or partially) synced with %s "
                            + "when a sync has been triggered with %s",
                    resumeKey.second,
                    authority));

            // Check if this worker has stopped and the current sync request is obsolete
            throwIfWorkerStopped();
            throwIfCloudProviderHasChanged(authority);

            try {
                getDatabase().beginTransaction();

                SearchRequestDatabaseUtil.clearSyncResumeInfo(
                        getDatabase(), List.of(searchRequestId), isLocal(authority));
                SearchResultsDatabaseUtil.clearObsoleteSearchResults(
                        getDatabase(), List.of(searchRequestId), isLocal(authority));

                // Check if this worker has stopped and the current sync request is obsolete before
                // committing the change.
                throwIfWorkerStopped();
                throwIfCloudProviderHasChanged(authority);

                if (getDatabase().inTransaction()) {
                    getDatabase().setTransactionSuccessful();
                }
                return true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not clear sync resume info", e);
            } finally {
                if (getDatabase().inTransaction()) {
                    getDatabase().endTransaction();
                }
            }
        }

        return false;
    }

    private void setResumeKey(
            @NonNull SearchRequest searchRequest,
            @NonNull String resumePageToken,
            @PickerSyncManager.SyncSource int syncSource) {
        if (syncSource == SYNC_LOCAL_ONLY) {
            searchRequest.setCloudResumeKey(resumePageToken);
        } else {
            searchRequest.setLocalSyncResumeKey(resumePageToken);
        }
    }

    @NonNull
    private Pair<String, String> getResumeKey(
            @NonNull SearchRequest searchRequest,
            @PickerSyncManager.SyncSource int syncSource) {
        return syncSource == SYNC_LOCAL_ONLY
                ? new Pair(searchRequest.getLocalSyncResumeKey(), searchRequest.getLocalAuthority())
                : new Pair(searchRequest.getCloudSyncResumeKey(),
                        searchRequest.getCloudAuthority());
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
            @NonNull String authority,
            @NonNull SearchRequest searchRequest,
            @Nullable String resumePageToken,
            @Nullable List<String> mimeTypes) {
        final String suggestedMediaSetId;
        final String searchText;
        if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            // Only media set id to the CMP if it is the suggestion source.
            if (authority.equals(searchSuggestionRequest.getSearchSuggestion().getAuthority())) {
                suggestedMediaSetId = searchSuggestionRequest.getSearchSuggestion().getMediaSetId();
            } else {
                suggestedMediaSetId = null;
            }

            searchText = searchSuggestionRequest.getSearchSuggestion().getSearchText();
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
                mimeTypes,
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
     * Validates input data received by the Worker for an immediate search results sync.
     */
    private void validateWorkInput(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull String authority,
            int searchRequestId,
            @Nullable SearchRequest searchRequest)
            throws IllegalArgumentException, RequestObsoleteException {
        requireNonNull(authority);

        // Search result sync can only happen with either local provider or cloud provider. This
        // information needs to be provided in the {@code inputData}.
        if (syncSource != SYNC_LOCAL_ONLY && syncSource != SYNC_CLOUD_ONLY) {
            throw new IllegalArgumentException("Invalid search results sync source " + syncSource);
        }

        // Check if the input authority matches the current provider.
        if (syncSource == SYNC_LOCAL_ONLY) {
            final String localAuthority = getLocalProviderAuthority();
            if (!authority.equals(localAuthority)) {
                throw new RequestObsoleteException(String.format(
                        Locale.ROOT,
                        "Input authority %s does not match current authority %s for sync source %d",
                        authority,
                        localAuthority,
                        syncSource)
                );
            }
        } else {
            final String cloudAuthority = getCurrentCloudProviderAuthority();
            if (!authority.equals(cloudAuthority)) {
                throw new RequestObsoleteException(String.format(
                        Locale.ROOT,
                        "Input authority %s does not match current authority %s for sync source %d",
                        authority,
                        cloudAuthority,
                        syncSource)
                );
            }
        }

        // Check if input search request id is valid.
        if (searchRequestId == INVALID_SEARCH_REQUEST_ID) {
            throw new IllegalArgumentException("Invalid search request id " + searchRequestId);
        }

        // Check search request details pulled from the database are valid.
        if (searchRequest == null) {
            throw new IllegalArgumentException(
                    "Could not get search request details for search request id "
                            + searchRequestId);
        }

        // If the search request is an ALBUM type suggestion, check that we're only syncing with the
        // album suggestion source CMP.
        if (searchRequest instanceof SearchSuggestionRequest searchSuggestionRequest) {
            if (searchSuggestionRequest.getSearchSuggestion().getSearchSuggestionType()
                    == SEARCH_SUGGESTION_ALBUM) {
                final boolean isLocal =
                        isLocal(searchSuggestionRequest.getSearchSuggestion().getAuthority());

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
        final String localAuthority = getLocalProviderAuthority();
        return localAuthority != null && localAuthority.equals(authority);
    }

    @Nullable
    private String getLocalProviderAuthority() {
        return PickerSyncController.getInstanceOrThrow().getLocalProvider();
    }

    @Nullable
    private String getCurrentCloudProviderAuthority() {
        return PickerSyncController.getInstanceOrThrow()
                .getCloudProviderOrDefault(/* defaultValue */ null);
    }

    private SQLiteDatabase getDatabase() {
        return PickerSyncController.getInstanceOrThrow().getDbFacade().getDatabase();
    }

    @Override
    public void onStopped() {
        // Mark the operation as cancelled so that the cancellation can be propagated to subtasks.
        mCancellationSignal.cancel();
    }
}
