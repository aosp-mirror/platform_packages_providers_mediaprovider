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


import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_AND_CLOUD;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.getCloudSyncTracker;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.getLocalSyncTracker;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markSyncAsComplete;

import android.content.Context;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;

/**
 * This is a {@link Worker} class responsible for syncing with the correct sync source.
 */
public class ImmediateSyncWorker extends Worker {
    private static final String TAG = "ISyncWorker";
    private final Context mContext;
    private final CancellationSignal mCancellationSignal = new CancellationSignal();

    /**
     * Creates an instance of the {@link Worker}.
     *
     * @param context the application {@link Context}
     * @param workerParams the set of {@link WorkerParameters}
     */
    public ImmediateSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mContext = context;
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
        final int syncSource = getInputData()
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, /* defaultValue */ SYNC_LOCAL_ONLY);

        Log.i(TAG, String.format(
                "Starting immediate picker sync from sync source: %s", syncSource));

        try {
            // No need to instantiate a work request tracker for immediate syncs in the worker.
            // For immediate syncs, the work request tracker is initiated before enqueueing the
            // request in WorkManager.
            if (syncSource == SYNC_LOCAL_AND_CLOUD || syncSource == SYNC_LOCAL_ONLY) {
                checkIsWorkerStopped();
                PickerSyncController.getInstanceOrThrow()
                        .syncAllMediaFromLocalProvider(mCancellationSignal);
                getLocalSyncTracker().markSyncCompleted(getId());
                Log.i(TAG, "Completed immediate picker sync from local provider.");
            }
            if (syncSource == SYNC_LOCAL_AND_CLOUD || syncSource == SYNC_CLOUD_ONLY) {
                checkIsWorkerStopped();
                PickerSyncController.getInstanceOrThrow()
                        .syncAllMediaFromCloudProvider(mCancellationSignal);
                getCloudSyncTracker().markSyncCompleted(getId());
                Log.i(TAG, "Completed immediate picker sync from cloud provider.");
            }
            return ListenableWorker.Result.success();
        } catch (IllegalStateException | RequestObsoleteException e) {
            Log.i(TAG, String.format(
                    "Could not complete immediate sync from sync source: %s", syncSource), e);

            // Mark all pending syncs as finished and set failure result.
            markSyncAsComplete(syncSource, getId());
            return ListenableWorker.Result.failure();
        }
    }

    private void checkIsWorkerStopped() throws RequestObsoleteException {
        if (isStopped()) {
            throw new RequestObsoleteException("Work is stopped " + getId());
        }
    }

    @Override
    @NonNull
    public ForegroundInfo getForegroundInfo() {
        return PickerSyncNotificationHelper.getForegroundInfo(mContext);
    }

    @Override
    public void onStopped() {
        Log.w(TAG, "Worker is stopped. Clearing all pending futures. It's possible that the sync "
                + "still finishes running if it has started already.");
        // Send CancellationSignal to any running tasks.
        mCancellationSignal.cancel();
        final int syncSource = getInputData()
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, /* defaultValue */ SYNC_LOCAL_AND_CLOUD);
        markSyncAsComplete(syncSource, getId());
    }

    @VisibleForTesting
    CancellationSignal getCancellationSignal() {
        return mCancellationSignal;
    }
}
