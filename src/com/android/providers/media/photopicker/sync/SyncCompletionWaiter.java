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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SyncCompletionWaiter {
    private static final String TAG = "SyncCompletionWaiter";

    /**
     * Will try it's best to wait for the existing sync requests to complete. It may not wait for
     * new sync requests received after this method starts running.
     */
    public static void waitForSync(
            @NonNull WorkManager workManager,
            @NonNull SyncTracker syncTracker,
            @NonNull String uniqueWorkName) {
        try {
            final CompletableFuture<Void> completableFuture =
                    CompletableFuture.allOf(
                            syncTracker.pendingSyncFutures().toArray(new CompletableFuture[0]));

            waitForSync(workManager, completableFuture, uniqueWorkName, /* retryCount */ 30);
        } catch (ExecutionException | InterruptedException e) {
            Log.w(TAG, "Could not wait for the sync to finish: " + e);
        }
    }

    /**
     * Wait for sync tracked by the input future to complete. In case the future takes an unusually
     * long time to complete, check the relevant unique work status from Work Manager.
     */
    @VisibleForTesting
    public static int waitForSync(
            @NonNull WorkManager workManager,
            @NonNull CompletableFuture<Void> completableFuture,
            @NonNull String uniqueWorkName,
            int retryCount) throws ExecutionException, InterruptedException {
        for (; retryCount > 0; retryCount--) {
            try {
                completableFuture.get(/* timeout */ 3, TimeUnit.SECONDS);
                return retryCount;
            } catch (TimeoutException e) {
                if (isUniqueWorkPending(workManager, uniqueWorkName)) {
                    Log.i(TAG, "Waiting for the sync again."
                            + " Unique work name: " + uniqueWorkName
                            + " Retry count: " + retryCount);
                } else {
                    Log.e(TAG, "Either immediate unique work is complete and the sync futures "
                            + "were not cleared, or a proactive sync might be blocking the query. "
                            + "Unblocking the query now for " + uniqueWorkName);
                    return retryCount;
                }
            }
        }

        if (retryCount == 0) {
            Log.e(TAG, "Retry count exhausted, could not wait for sync anymore.");
        }
        return retryCount;
    }

    /**
     * Will wait for the existing sync requests to complete till the provided timeout. It may
     * not wait for new sync requests received after this method starts running.
     */
    public static boolean waitForSyncWithTimeout(
            @NonNull SyncTracker syncTracker,
            int timeoutInMillis) {
        try {
            final CompletableFuture<Void> completableFuture =
                    CompletableFuture.allOf(
                            syncTracker.pendingSyncFutures().toArray(new CompletableFuture[0]));
            completableFuture.get(timeoutInMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            Log.w(TAG, "Could not wait for the sync with timeout to finish: " + e);
            return false;
        }
    }

    /**
     * Returns true if the given unique work is pending. In case the unique work is complete or
     * there was an error in getting the work state, it returns false.
     */
    public static boolean isUniqueWorkPending(WorkManager workManager, String uniqueWorkName) {
        ListenableFuture<List<WorkInfo>> future =
                workManager.getWorkInfosForUniqueWork(uniqueWorkName);
        try {
            List<WorkInfo> workInfos = future.get();
            for (WorkInfo workInfo : workInfos) {
                if (!workInfo.getState().isFinished()) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Error occurred in fetching work info - ignore pending work");
            return false;
        }
    }
}
