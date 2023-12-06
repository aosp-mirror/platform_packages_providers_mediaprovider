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

import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markAlbumMediaSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewAlbumMediaSyncRequests;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewSyncRequests;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.PickerSyncController;

import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
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

    public static final int SYNC_RESET_MEDIA = 1;
    public static final int SYNC_RESET_ALBUM = 2;

    @IntDef(value = {SYNC_RESET_MEDIA, SYNC_RESET_ALBUM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncResetType {}

    static final String SYNC_WORKER_INPUT_AUTHORITY = "INPUT_AUTHORITY";
    static final String SYNC_WORKER_INPUT_SYNC_SOURCE = "INPUT_SYNC_TYPE";
    static final String SYNC_WORKER_INPUT_RESET_TYPE = "INPUT_RESET_TYPE";
    static final String SYNC_WORKER_INPUT_ALBUM_ID = "INPUT_ALBUM_ID";
    static final String SYNC_WORKER_TAG_IS_PERIODIC = "PERIODIC";
    private static final int SYNC_MEDIA_PERIODIC_WORK_INTERVAL = 4; // Time unit is hours.
    private static final int RESET_ALBUM_MEDIA_PERIODIC_WORK_INTERVAL = 12; // Time unit is hours.

    private static final String PERIODIC_SYNC_WORK_NAME;
    private static final String PROACTIVE_LOCAL_SYNC_WORK_NAME;
    private static final String PROACTIVE_SYNC_WORK_NAME;
    public static final String IMMEDIATE_LOCAL_SYNC_WORK_NAME;
    private static final String IMMEDIATE_CLOUD_SYNC_WORK_NAME;
    public static final String IMMEDIATE_ALBUM_SYNC_WORK_NAME;
    private static final String PERIODIC_ALBUM_RESET_WORK_NAME;

    static {
        final String syncPeriodicPrefix = "SYNC_MEDIA_PERIODIC_";
        final String syncProactivePrefix = "SYNC_MEDIA_PROACTIVE_";
        final String syncImmediatePrefix = "SYNC_MEDIA_IMMEDIATE_";
        final String syncAllSuffix = "ALL";
        final String syncLocalSuffix = "LOCAL";
        final String syncCloudSuffix = "CLOUD";

        PERIODIC_ALBUM_RESET_WORK_NAME = "RESET_ALBUM_MEDIA_PERIODIC";
        PERIODIC_SYNC_WORK_NAME = syncPeriodicPrefix + syncAllSuffix;
        PROACTIVE_LOCAL_SYNC_WORK_NAME = syncProactivePrefix + syncLocalSuffix;
        PROACTIVE_SYNC_WORK_NAME = syncProactivePrefix + syncAllSuffix;
        IMMEDIATE_LOCAL_SYNC_WORK_NAME = syncImmediatePrefix + syncLocalSuffix;
        IMMEDIATE_CLOUD_SYNC_WORK_NAME = syncImmediatePrefix + syncCloudSuffix;
        IMMEDIATE_ALBUM_SYNC_WORK_NAME = "SYNC_ALBUM_MEDIA_IMMEDIATE";
    }

    private final WorkManager mWorkManager;
    private final ConfigStore mConfigStore;
    private final Context mContext;

    public PickerSyncManager(@NonNull WorkManager workManager,
            @NonNull Context context,
            @NonNull ConfigStore configStore,
            boolean shouldSchedulePeriodicSyncs) {
        mWorkManager = requireNonNull(workManager);
        mConfigStore = requireNonNull(configStore);
        mContext = requireNonNull(context);

        if (shouldSchedulePeriodicSyncs) {
            setUpPeriodicWork();
        }

        // Subscribe to device config changes so we can enable periodic workers if Cloud
        // Photopicker is enabled.
        mConfigStore.addOnChangeListener(BackgroundThread.getExecutor(), this::setUpPeriodicWork);
    }

    /**
     * Will register new unique {@link Worker} for periodic sync and picker database maintenance if
     * the cloud photopicker experiment is currently enabled.
     */
    private void setUpPeriodicWork() {

        if (mConfigStore.isCloudMediaInPhotoPickerEnabled()) {
            PickerSyncNotificationHelper.createNotificationChannel(mContext);

            schedulePeriodicSyncs();
            schedulePeriodicAlbumReset();
        } else {
            // Disable any scheduled ongoing work if the feature is disabled.
            mWorkManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME);
            mWorkManager.cancelUniqueWork(PERIODIC_ALBUM_RESET_WORK_NAME);
        }
    }

    /**
     * Returns true if the given unique work is pending. In case the unique work is complete or
     * there was an error in getting the work state, it returns false.
     */
    public boolean isUniqueWorkPending(String uniqueWorkName) {
        ListenableFuture<List<WorkInfo>> future =
                mWorkManager.getWorkInfosForUniqueWork(uniqueWorkName);
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

    private void schedulePeriodicSyncs() {
        Log.i(TAG, "Scheduling periodic proactive syncs");

        final Data inputData =
                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
        final PeriodicWorkRequest periodicSyncRequest = getPeriodicProactiveSyncRequest(inputData);

        try {
            // Note that the first execution of periodic work happens immediately or as soon as the
            // given Constraints are met.
            final Operation enqueueOperation = mWorkManager
                    .enqueueUniquePeriodicWork(
                            PERIODIC_SYNC_WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            periodicSyncRequest
                    );

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue periodic proactive picker sync request", e);
        }
    }

    private void schedulePeriodicAlbumReset() {
        Log.i(TAG, "Scheduling periodic picker album data resets");

        final Data inputData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_SYNC_SOURCE,
                                SYNC_LOCAL_AND_CLOUD,
                                SYNC_WORKER_INPUT_RESET_TYPE,
                                SYNC_RESET_ALBUM));
        final PeriodicWorkRequest periodicAlbumResetRequest =
                getPeriodicAlbumResetRequest(inputData);

        try {
            // Note that the first execution of periodic work happens immediately or as soon
            // as the given Constraints are met.
            Operation enqueueOperation =
                    mWorkManager.enqueueUniquePeriodicWork(
                            PERIODIC_ALBUM_RESET_WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            periodicAlbumResetRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Could not enqueue periodic picker album resets request", e);
        }
    }

    /**
     * Use this method for proactive syncs. The sync might take a while to start. Some device state
     * conditions may apply before the sync can start like battery level etc.
     *
     * @param localOnly - whether the proactive sync should only sync with the local provider.
     */
    public void syncMediaProactively(Boolean localOnly) {

        final int syncSource = localOnly ? SYNC_LOCAL_ONLY : SYNC_LOCAL_AND_CLOUD;
        final String workName =
                localOnly ? PROACTIVE_LOCAL_SYNC_WORK_NAME : PROACTIVE_SYNC_WORK_NAME;

        final Data inputData = new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource));
        final OneTimeWorkRequest syncRequest = getOneTimeProactiveSyncRequest(inputData);

        // Don't wait for the sync operation to enqueue so that Picker sync enqueue
        // requests in
        // order to avoid adding latency to critical MP code paths.

        mWorkManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, syncRequest);
    }

    /**
     * Use this method for reactive syncs which are user triggered.
     *
     * @param shouldSyncLocalOnlyData if true indicates that the sync should only be triggered with
     *                                the local provider. Otherwise, sync will be triggered for both
     *                                local and cloud provider.
     */
    public void syncMediaImmediately(boolean shouldSyncLocalOnlyData) {
        syncMediaImmediately(PickerSyncManager.SYNC_LOCAL_ONLY, IMMEDIATE_LOCAL_SYNC_WORK_NAME);
        if (!shouldSyncLocalOnlyData) {
            syncMediaImmediately(PickerSyncManager.SYNC_CLOUD_ONLY, IMMEDIATE_CLOUD_SYNC_WORK_NAME);
        }
    }

    /**
     * Use this method for reactive syncs with either, local and cloud providers, or both.
     */
    private void syncMediaImmediately(@SyncSource int syncSource, @NonNull String workName) {
        final Data inputData = new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource));
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(ImmediateSyncWorker.class, inputData);

        // Track the new sync request(s)
        trackNewSyncRequests(syncSource, syncRequest.getId());

        // Enqueue local or cloud sync request
        try {
            final Operation enqueueOperation = mWorkManager
                    .enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest);

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited picker sync request", e);
            markSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    /**
     * Use this method for reactive syncs which are user action triggered.
     *
     * @param albumId is the id of the album that needs to be synced.
     * @param authority The authority of the album media.
     */
    public void syncAlbumMediaForProviderImmediately(
            @NonNull String albumId, @NonNull String authority) {
        boolean isLocal = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY.equals(authority);
        syncAlbumMediaForProviderImmediately(albumId, getSyncSource(isLocal), authority);
    }

    /**
     * Use this method for reactive syncs which are user action triggered.
     *
     * @param albumId is the id of the album that needs to be synced.
     * @param syncSource indicates if the sync is required with local provider or cloud provider or
     *     both.
     */
    private void syncAlbumMediaForProviderImmediately(
            @NonNull String albumId, @SyncSource int syncSource, String authority) {
        final Data inputData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_AUTHORITY, authority,
                                SYNC_WORKER_INPUT_SYNC_SOURCE, syncSource,
                                SYNC_WORKER_INPUT_RESET_TYPE, SYNC_RESET_ALBUM,
                                SYNC_WORKER_INPUT_ALBUM_ID, albumId));
        final OneTimeWorkRequest resetRequest =
                buildOneTimeWorkerRequest(MediaResetWorker.class, inputData);
        final OneTimeWorkRequest syncRequest =
                buildOneTimeWorkerRequest(ImmediateAlbumSyncWorker.class, inputData);

        // Track the new sync request(s)
        trackNewAlbumMediaSyncRequests(syncSource, resetRequest.getId());
        trackNewAlbumMediaSyncRequests(syncSource, syncRequest.getId());

        // Enqueue local or cloud sync requests
        try {
            final Operation enqueueOperation =
                    mWorkManager
                            .beginUniqueWork(
                                    IMMEDIATE_ALBUM_SYNC_WORK_NAME,
                                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                                    resetRequest)
                            .then(syncRequest).enqueue();

            // Check that the request has been successfully enqueued.
            enqueueOperation.getResult().get();
        } catch (Exception e) {
            Log.e(TAG, "Could not enqueue expedited picker sync request", e);
            markAlbumMediaSyncAsComplete(syncSource, resetRequest.getId());
            markAlbumMediaSyncAsComplete(syncSource, syncRequest.getId());
        }
    }

    @NotNull
    private OneTimeWorkRequest buildOneTimeWorkerRequest(
            @NotNull Class<? extends Worker> workerClass, @NonNull Data inputData) {
        return new OneTimeWorkRequest.Builder(workerClass)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
    }

    @NotNull
    private PeriodicWorkRequest getPeriodicProactiveSyncRequest(@NotNull Data inputData) {
        return new PeriodicWorkRequest.Builder(
                ProactiveSyncWorker.class, SYNC_MEDIA_PERIODIC_WORK_INTERVAL, TimeUnit.HOURS)
                .setInputData(inputData)
                .setConstraints(getRequiresChargingAndIdleConstraints())
                .build();
    }

    @NotNull
    private PeriodicWorkRequest getPeriodicAlbumResetRequest(@NotNull Data inputData) {

        return new PeriodicWorkRequest.Builder(
                        MediaResetWorker.class,
                        RESET_ALBUM_MEDIA_PERIODIC_WORK_INTERVAL,
                        TimeUnit.HOURS)
                .setInputData(inputData)
                .setConstraints(getRequiresChargingAndIdleConstraints())
                .addTag(SYNC_WORKER_TAG_IS_PERIODIC)
                .build();
    }

    @NotNull
    private OneTimeWorkRequest getOneTimeProactiveSyncRequest(@NotNull Data inputData) {
        Constraints constraints =  new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();

        return new OneTimeWorkRequest.Builder(ProactiveSyncWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .build();
    }

    @NotNull
    private static Constraints getRequiresChargingAndIdleConstraints() {
        return new Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build();
    }

    @SyncSource
    private static int getSyncSource(boolean isLocal) {
        return isLocal
                ? SYNC_LOCAL_ONLY
                : SYNC_CLOUD_ONLY;
    }
}
