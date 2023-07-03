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

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;

/**
 * This is a {@link Worker} class responsible for syncing with the correct sync source.
 */
public class ImmediateSyncWorker extends Worker {
    private static final String TAG = "ISyncWorker";

    /**
     * Creates an instance of the {@link Worker}.
     *
     * @param context the application {@link Context}
     * @param workerParams the set of {@link WorkerParameters}
     */
    public ImmediateSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {
        final int syncSource = getInputData()
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, /* defaultValue */ SYNC_LOCAL_AND_CLOUD);

        Log.i(TAG,
                String.format("Starting immediate picker sync from sync source: %s", syncSource));

        try {
            // No need to instantiate a work request tracker for immediate syncs in the worker.
            // For immediate syncs, the work request tracker is initiated before enqueueing the
            // request in WorkManager.
            if (syncSource == SYNC_LOCAL_AND_CLOUD || syncSource == SYNC_LOCAL_ONLY) {
                PickerSyncController.getInstanceOrThrow().syncAllMediaFromLocalProvider();
                getLocalSyncTracker().markSyncCompleted(getId());
                Log.i(TAG, "Completed immediate picker sync from local provider.");
            }
            if (syncSource == SYNC_LOCAL_AND_CLOUD || syncSource == SYNC_CLOUD_ONLY) {
                PickerSyncController.getInstanceOrThrow().syncAllMediaFromCloudProvider();
                getCloudSyncTracker().markSyncCompleted(getId());
                Log.i(TAG, "Completed immediate picker sync from cloud provider.");
            }
            return ListenableWorker.Result.success();
        } catch (IllegalStateException e) {
            Log.i(TAG, String.format(
                    "Could not complete immediate sync from sync source: %s", syncSource), e);

            // Mark all pending syncs as finished and set failure result.
            getLocalSyncTracker().markSyncCompleted(getId());
            getCloudSyncTracker().markSyncCompleted(getId());
            return ListenableWorker.Result.failure();
        }
    }
}
