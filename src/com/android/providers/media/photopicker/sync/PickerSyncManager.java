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

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.android.providers.media.ConfigStore;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


/**
 * This class manages all the triggers for Picker syncs.
 * <p></p>
 * There are different use cases for triggering a sync:
 * <p>
 * 1. Proactive sync - these syncs are proactively performed to minimize the changes that need to be
 *      synced when the user opens the Photo Picker. The sync should only be performed if the device
 *      state allows it.
 * <p>
 * 2. Reactive sync - these syncs are triggered by the user opening the Photo Picker. These should
 *      be run immediately since the user is likely to be waiting for the sync response on the UI.
 */
public class PickerSyncManager {
    private static final String TAG = "SyncWorkManager";
    public static final int SYNC_LOCAL_ONLY = 1;
    public static final int SYNC_CLOUD_ONLY = 2;
    public static final int SYNC_LOCAL_AND_CLOUD = 3;

    @IntDef(value = { SYNC_LOCAL_ONLY, SYNC_CLOUD_ONLY, SYNC_LOCAL_AND_CLOUD })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncSource {}
    static final String SYNC_WORKER_INPUT_SYNC_SOURCE = "INPUT_SYNC_TYPE";
    static final String SYNC_WORKER_INPUT_ALBUM_ID = "INPUT_ALBUM_ID";
    private static final int SYNC_MEDIA_PERIODIC_WORK_INTERVAL = 4; // Time unit is hours.
    private static final String SYNC_MEDIA_PERIODIC_SYNC_PREFIX = "SYNC_MEDIA_PERIODIC_";
    private static final String SYNC_MEDIA_PROACTIVE_WORK_PREFIX = "SYNC_MEDIA_PROACTIVE_";
    private static final String SYNC_ALL_WORK_SUFFIX = "ALL";
    private final WorkManager mWorkManager;

    public PickerSyncManager(@NonNull WorkManager workManager,
            @NonNull ConfigStore configStore,
            boolean schedulePeriodicSyncs) {
        mWorkManager = workManager;

        if (schedulePeriodicSyncs && configStore.isCloudMediaInPhotoPickerEnabled()) {
            schedulePeriodicSyncs();
        }
    }

    private void schedulePeriodicSyncs() {
        Log.i(TAG, "Scheduling periodic proactive syncs");

        final Data inputData =
                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
        final PeriodicWorkRequest periodicSyncRequest = getPeriodicProactiveSyncRequest(inputData);

        try {
            // Note that the first execution of periodic work happens immediately or as soon as the
            // given Constraints are met.
            Operation enqueueOperation = mWorkManager
                    .enqueueUniquePeriodicWork(
                            SYNC_MEDIA_PERIODIC_SYNC_PREFIX + SYNC_ALL_WORK_SUFFIX,
                            ExistingPeriodicWorkPolicy.KEEP,
                            periodicSyncRequest
                    );

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue periodic proactive picker sync request", e);
        }
    }

    /**
     * Use this method for proactive syncs. The sync might take a while to start. Some device state
     * conditions may apply before the sync can start like battery level etc.
     */
    public void syncAllMediaProactively() {
        Data inputData = new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
        OneTimeWorkRequest syncRequest = getOneTimeProactiveSyncRequest(inputData);

        String workName = SYNC_MEDIA_PROACTIVE_WORK_PREFIX + SYNC_ALL_WORK_SUFFIX;
        try {
            Operation enqueueOperation = mWorkManager
                    .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, syncRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue proactive picker sync request", e);
        }
    }

    /**
     * Use this method for reactive syncs which are user triggered.
     *
     * @param isLocal is true when the authority when the sync type is local.
     *                    For cloud syncs, this is false.
     */
    public void syncMediaForProviderImmediately(boolean isLocal) {
        // TODO
        throw new UnsupportedOperationException(
                "syncMediaForProviderImmediately is not supported.");
    }

    /**
     * Use this method for reactive syncs which are user triggered.
     *
     * @param albumId is the id of the album that needs to be synced.
     * @param isLocal is true when the authority when the sync type is local.
     *                    For cloud syncs, this is false.
     */
    public void syncAlbumMediaForProviderImmediately(
            @NonNull String albumId,
            boolean isLocal) {
        // TODO
        throw new UnsupportedOperationException(
                "syncAlbumMediaForProviderImmediately is not supported.");
    }

    @NotNull
    private PeriodicWorkRequest getPeriodicProactiveSyncRequest(@NotNull Data inputData) {
        return new PeriodicWorkRequest.Builder(
                ProactiveSyncWorker.class, SYNC_MEDIA_PERIODIC_WORK_INTERVAL, TimeUnit.HOURS)
                .setInputData(inputData)
                .setConstraints(getProactiveSyncConstraints())
                .build();
    }

    @NotNull
    private OneTimeWorkRequest getOneTimeProactiveSyncRequest(@NotNull Data inputData) {
        return new OneTimeWorkRequest.Builder(ProactiveSyncWorker.class)
                .setInputData(inputData)
                .setConstraints(getProactiveSyncConstraints())
                .build();
    }

    @NotNull
    private static Constraints getProactiveSyncConstraints() {
        // TODO these constraints are not finalised.
        return new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
    }
}
