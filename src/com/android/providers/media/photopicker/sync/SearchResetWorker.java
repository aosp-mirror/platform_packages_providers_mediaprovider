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

import static com.android.providers.media.photopicker.sync.PickerSyncManager.EXPIRED_SUGGESTIONS_RESET;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SEARCH_RESULTS_PARTIAL_CACHE_RESET;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SEARCH_RESULTS_FULL_CACHE_RESET;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_RESET_TYPE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.v2.sqlite.SearchRequestDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.SearchResultsDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsDatabaseUtils;

import java.util.List;

/**
 * This worker is responsible to cleaning up search results or suggestions related data in
 * picker database.
 */
public class SearchResetWorker extends Worker {
    private static final String TAG = "SearchResetWorker";

    private final int mSyncSource;
    private final int mResetType;

    public SearchResetWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParameters) {
        super(context, workerParameters);

        mSyncSource = getInputData().getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1);
        mResetType = getInputData().getInt(SYNC_WORKER_INPUT_RESET_TYPE, -1);
    }

    @Override
    public ListenableWorker.Result doWork() {
        // Do not allow endless re-runs of this worker, if this isn't the original run,
        // just succeed and wait until the next scheduled run.
        if (getRunAttemptCount() > 0) {
            Log.w(TAG, "Worker retry was detected, ending this run in failure.");
            return ListenableWorker.Result.failure();
        }

        switch (mResetType) {
            case SEARCH_RESULTS_FULL_CACHE_RESET:
                return searchResultsFullCacheReset();

            case SEARCH_RESULTS_PARTIAL_CACHE_RESET:
                return searchResultsPartialCacheReset();

            case EXPIRED_SUGGESTIONS_RESET:
                return expiredSuggestionsReset();

            default:
                Log.e(TAG, "Could not recognize reset type: " + mResetType);
                return ListenableWorker.Result.failure();
        }
    }

    /**
     * Clears search results for either local or cloud provider based on the input data.
     * Also clears the sync resume information in the database.
     *
     * @return ListenableWorker.Result that indicates whether the work was either a success
     * or a failure.
     */
    @NonNull
    private ListenableWorker.Result searchResultsPartialCacheReset() {
        final SQLiteDatabase database = getDatabase();

        try {
            checkIfWorkerIsStopped();

            if (mSyncSource != SYNC_LOCAL_ONLY && mSyncSource != SYNC_CLOUD_ONLY) {
                throw new IllegalArgumentException(
                        "Cannot perform partial search results reset with sync source "
                                + mSyncSource);
            }
            final boolean isLocal = mSyncSource == SYNC_LOCAL_ONLY;

            // Start a database transaction with EXCLUSIVE lock.
            database.beginTransaction();

            final List<Integer> syncedSearchRequestIds =
                    SearchRequestDatabaseUtil.getSyncedRequestIds(database, isLocal);
            SearchRequestDatabaseUtil.clearSyncResumeInfo(
                    database, syncedSearchRequestIds, isLocal);
            SearchResultsDatabaseUtil.clearObsoleteSearchResults(
                    database, syncedSearchRequestIds, isLocal);


            // Check if this work has been cancelled before committing these changes to
            // the database.
            checkIfWorkerIsStopped();

            // Mark transaction as successful so that the changes get committed to the database.
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }

            return ListenableWorker.Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Could not perform search results partial cache reset.", e);
            return ListenableWorker.Result.failure();
        } finally {
            // Ensure that the transaction ends and the DB lock is released.
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Clears all search results and search requests from the cache.
     *
     * @return ListenableWorker.Result that indicates whether the work was either a success
     * or a failure.
     */
    @NonNull
    private ListenableWorker.Result searchResultsFullCacheReset() {
        final SQLiteDatabase database = getDatabase();

        try {
            // Don't proceed with this if any local or cloud search results sync is pending,
            // or if the worker is stopped.
            checkPendingLocalSearchSync();
            checkPendingCloudSearchSync();
            checkIfWorkerIsStopped();

            // Start a database transaction with EXCLUSIVE lock.
            database.beginTransaction();

            // Clear all search requests and their associated results.
            SearchRequestDatabaseUtil.clearAllSearchRequests(database);
            SearchResultsDatabaseUtil.clearAllSearchResults(database);

            // Check again if this work has been cancelled/made obsolete before committing these
            // changes to the database.
            checkPendingLocalSearchSync();
            checkPendingCloudSearchSync();
            checkIfWorkerIsStopped();

            // Mark transaction as successful so that the changes get committed to the database.
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }
            return ListenableWorker.Result.success();
        } catch (RequestObsoleteException | RuntimeException e) {
            Log.e(TAG, "Could not clear search request and/or results cache", e);
            return ListenableWorker.Result.failure();
        } finally {
            // Ensure that the transaction ends and the DB lock is released.
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Clears all expired history search suggestions and cached search suggestions.
     *
     * @return ListenableWorker.Result that indicates whether the work was either a success
     * or a failure.
     */
    @NonNull
    private ListenableWorker.Result expiredSuggestionsReset() {
        final SQLiteDatabase database = getDatabase();

        try {
            // Don't proceed if the worker is stopped.
            checkIfWorkerIsStopped();

            // Clear all expired history and caches search suggestions.
            SearchSuggestionsDatabaseUtils.clearExpiredCachedSearchSuggestions(database);
            SearchSuggestionsDatabaseUtils.clearExpiredHistorySearchSuggestions(database);

            // Check again if the worker is stopped before committing the changes.
            checkIfWorkerIsStopped();

            return ListenableWorker.Result.success();
        } catch (RequestObsoleteException | RuntimeException e) {
            Log.e(TAG, "Could not clear expired cached/history suggestions", e);
            return ListenableWorker.Result.failure();
        }
    }

    private void checkPendingLocalSearchSync() throws RequestObsoleteException {
        if (!SyncTrackerRegistry.getLocalSearchSyncTracker().pendingSyncFutures().isEmpty()) {
            throw new RequestObsoleteException("Local search sync is pending.");
        }
    }

    private void checkPendingCloudSearchSync() throws RequestObsoleteException {
        if (!SyncTrackerRegistry.getCloudSearchSyncTracker().pendingSyncFutures().isEmpty()) {
            throw new RequestObsoleteException("Cloud search sync is pending.");
        }
    }

    private void checkIfWorkerIsStopped() throws RequestObsoleteException {
        if (isStopped()) {
            throw new RequestObsoleteException("Worker is stopped.");
        }
    }

    @NonNull
    private SQLiteDatabase getDatabase() {
        return PickerSyncController.getInstanceOrThrow().getDbFacade().getDatabase();
    }
}
