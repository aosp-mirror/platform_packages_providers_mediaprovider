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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;

/**
 * This is a {@link Worker} class responsible for proactively syncing media with the correct sync
 * source.
 */
public class ProactiveSyncWorker extends Worker {
    private static final String TAG = "PSyncWorker";
    private final Context mContext;

    /**
     * Creates an instance of the {@link Worker}.
     *
     * @param context the application {@link Context}
     * @param workerParams the set of {@link WorkerParameters}
     */
    public ProactiveSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mContext = context;
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {
        final int syncSource = getInputData()
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, /* defaultValue */ SYNC_LOCAL_AND_CLOUD);

        Log.i(TAG,
                String.format("Starting proactive picker sync from sync source: %s", syncSource));

        try {
            if (syncSource == SYNC_LOCAL_AND_CLOUD || syncSource == SYNC_LOCAL_ONLY) {
                // Instantiate sync state tracker.
                final SyncTracker localSyncTracker = getLocalSyncTracker();
                localSyncTracker.createSyncFuture(getId());

                // Complete sync and mark work tracker as finished.
                PickerSyncController.getInstanceOrThrow().syncAllMediaFromLocalProvider();
                localSyncTracker.markSyncCompleted(getId());
                Log.i(TAG, "Completed picker proactive sync complete from local provider.");
            }
            if (syncSource == SYNC_LOCAL_AND_CLOUD || syncSource == SYNC_CLOUD_ONLY) {
                // Instantiate sync state tracker.
                final SyncTracker cloudSyncTracker = getCloudSyncTracker();
                cloudSyncTracker.createSyncFuture(getId());

                // Complete sync and mark work tracker as finished.
                PickerSyncController.getInstanceOrThrow().syncAllMediaFromCloudProvider();
                cloudSyncTracker.markSyncCompleted(getId());
                Log.i(TAG, "Completed picker proactive sync complete from cloud provider.");
            }
            return ListenableWorker.Result.success();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Could not complete proactive sync for sync source: " + syncSource, e);

            // Mark all pending syncs as finished and set failure result.
            markSyncAsComplete(syncSource, getId());
            return ListenableWorker.Result.failure();
        }
    }

    @Override
    @NonNull
    public ForegroundInfo getForegroundInfo() {
        Log.e(TAG, "Proactive Sync Worker should not run as an expedited task");
        return PickerSyncNotificationHelper.getForegroundInfo(mContext);
    }
}
