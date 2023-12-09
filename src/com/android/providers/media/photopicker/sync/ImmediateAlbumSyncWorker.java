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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_ALBUM_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markAlbumMediaSyncAsComplete;

import android.content.Context;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;

/**
 * This is a {@link Worker} class responsible for syncing album media with the correct sync source.
 */
public class ImmediateAlbumSyncWorker extends Worker {
    private static final String TAG = "IASyncWorker";
    private static final int INVALID_SYNC_SOURCE = -1;
    private final Context mContext;
    private final CancellationSignal mCancellationSignal = new CancellationSignal();

    /**
     * Creates an instance of the {@link Worker}.
     *
     * @param context the application {@link Context}
     * @param workerParams the set of {@link WorkerParameters}
     */
    public ImmediateAlbumSyncWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams) {
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
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, /* defaultValue */ INVALID_SYNC_SOURCE);
        final String albumId = getInputData().getString(SYNC_WORKER_INPUT_ALBUM_ID);

        Log.i(TAG, String.format(
                "Starting picker immediate album sync from sync source: %s album id: %s",
                syncSource, albumId));

        try {
            validateWorkInput(syncSource, albumId);

            // No need to instantiate a work request tracker for immediate syncs in the worker.
            // For immediate syncs, the work request tracker is initiated before enqueueing the
            // request in WorkManager.
            checkIsWorkerStopped();
            if (syncSource == SYNC_LOCAL_ONLY) {
                PickerSyncController.getInstanceOrThrow()
                        .syncAlbumMediaFromLocalProvider(albumId, mCancellationSignal);
            } else {
                PickerSyncController.getInstanceOrThrow()
                        .syncAlbumMediaFromCloudProvider(albumId, mCancellationSignal);
            }

            Log.i(TAG, String.format(
                    "Completed picker immediate album sync from sync source: %s album id: %s",
                    syncSource, albumId));
            return ListenableWorker.Result.success();
        } catch (IllegalArgumentException | IllegalStateException | RequestObsoleteException e) {
            Log.e(TAG, String.format("Could not complete picker immediate album sync from "
                            + "sync source: %s album id: %s",
                    syncSource, albumId), e);
            return ListenableWorker.Result.failure();
        } finally {
            markAlbumMediaSyncAsComplete(syncSource, getId());
        }
    }

    /**
     * Validates input data received by the Worker for an immediate album sync.
     */
    private void validateWorkInput(int syncSource, @Nullable String albumId)
            throws IllegalArgumentException {
        // Album syncs can only happen with either local provider or cloud provider. This
        // information needs to be provided in the {@code inputData}.
        if (syncSource != SYNC_LOCAL_ONLY && syncSource != SYNC_CLOUD_ONLY) {
            throw new IllegalArgumentException("Invalid album sync source " + syncSource);
        }
        if (albumId == null || TextUtils.isEmpty(albumId)) {
            throw new IllegalArgumentException("Invalid album id " + albumId);
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
                + "will continue to run if it has started already.");
        // Send CancellationSignal to any running tasks.
        mCancellationSignal.cancel();
        final int syncSource = getInputData()
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, /* defaultValue */ SYNC_LOCAL_ONLY);
        markAlbumMediaSyncAsComplete(syncSource, getId());
    }

    @VisibleForTesting
    CancellationSignal getCancellationSignal() {
        return mCancellationSignal;
    }
}
