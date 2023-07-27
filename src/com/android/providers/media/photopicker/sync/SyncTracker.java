/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * This class tracks all pending syncs in a synchronized map.
 */
public class SyncTracker {
    private static final String TAG = "PickerSyncTracker";
    private static final int SYNC_FUTURE_TIMEOUT = 20; // Minutes
    private static final Object FUTURE_RESULT = new Object(); // Placeholder result object
    private final Map<UUID, CompletableFuture<Object>> mFutureMap =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * Use this method to create a picker sync future and track its progress. This should be
     * called either when a new sync request is enqueued, or when a new sync request starts
     * processing.
     * @param workRequestID the work request id of a picker sync.
     */
    public void createSyncFuture(UUID workRequestID) {
        // Create a CompletableFuture that tracks a sync operation. The future will
        // automatically be marked as finished after a given timeout. This is important because
        // we're not able to track all WorkManager failures. In case of a failure to run the
        // sync, we'll need to ensure that the future expires automatically after a given
        // timeout.
        final CompletableFuture<Object> syncFuture = new CompletableFuture<>();
        syncFuture.completeOnTimeout(FUTURE_RESULT, SYNC_FUTURE_TIMEOUT, TimeUnit.MINUTES);
        mFutureMap.put(workRequestID, syncFuture);
        Log.i(TAG, String.format("Created new sync future %s. Future map: %s",
                syncFuture, mFutureMap));
    }

    /**
     * Use this method to mark a picker sync future as complete. If this is not invoked within a
     * configured time limit, the future will automatically be set as done.
     * @param workRequestID the work request id of a picker sync.
     */
    public void markSyncCompleted(UUID workRequestID) {
        if (mFutureMap.containsKey(workRequestID)) {
            mFutureMap.get(workRequestID).complete(FUTURE_RESULT);
            mFutureMap.remove(workRequestID);
            Log.i(TAG, String.format(
                    "Marked sync future complete for work id: %s. Future map: %s",
                    workRequestID, mFutureMap));
        } else {
            Log.w(TAG, String.format("Attempted to complete sync future that is not currently "
                            + "tracked for work id: %s. Future map: %s",
                    workRequestID, mFutureMap));
        }
    }

    /**
     * Use this method to check if any sync request is still pending.
     * @return a {@link Collection} of {@link CompletableFuture} of pending syncs. This can be
     * used to track when all pending are complete.
     */
    public Collection<CompletableFuture<Object>> pendingSyncFutures() {
        Log.i(TAG, String.format("Returning pending sync future map: %s", mFutureMap));
        return mFutureMap.values();
    }
}
