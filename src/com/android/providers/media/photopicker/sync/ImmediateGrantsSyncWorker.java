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


import static com.android.providers.media.photopicker.sync.PickerSyncManager.EXTRA_MIME_TYPES;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SHOULD_SYNC_GRANTS;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_MEDIA_GRANTS;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markSyncAsComplete;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;

/**
 * This is a {@link Worker} class responsible for syncing with the correct sync
 * source[SYNC_MEDIA_GRANTS]
 */
public class ImmediateGrantsSyncWorker extends Worker {
    private static final String TAG = "ISyncGrantsWorker";
    private final Context mContext;

    /**
     * Creates an instance of the {@link Worker}.
     *
     * @param context the application {@link Context}
     * @param workerParams the set of {@link WorkerParameters}
     */
    public ImmediateGrantsSyncWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mContext = context;
    }

    @NonNull
    @Override
    public Result doWork() {
        // Do not allow endless re-runs of this worker, if this isn't the original run,
        // just succeed and wait until the next scheduled run.
        if (getRunAttemptCount() > 0) {
            Log.w(TAG, "Worker retry was detected, ending this run in failure.");
            return Result.failure();
        }

        Log.i(TAG, "Starting immediate picker grants sync from external database.");

        try {
            final int callingPackageUid = getInputData().getInt(Intent.EXTRA_UID, -1);
            final boolean shouldSyncGrants = getInputData().getBoolean(SHOULD_SYNC_GRANTS, false);
            final String[] mimeTypes = getInputData().getStringArray(EXTRA_MIME_TYPES);
            checkIsWorkerStopped();
            if (callingPackageUid != -1) {
                PickerSyncController.getInstanceOrThrow().executeGrantsSync(
                        shouldSyncGrants,
                        callingPackageUid,
                        mimeTypes);
                Log.i(TAG, "Completed immediate picker grants sync from external database.");
            } else {
                // Having package uid is a must to execute sync for grants.
                return Result.failure();
            }
            return Result.success();
        } catch (IllegalStateException | RequestObsoleteException e) {
            Log.i(TAG, "Could not complete immediate sync for grants");
            return Result.failure();
        } finally {
            // Mark all pending syncs as finished.
            markSyncAsComplete(SYNC_MEDIA_GRANTS, getId());
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
        markSyncAsComplete(SYNC_MEDIA_GRANTS, getId());
    }
}
