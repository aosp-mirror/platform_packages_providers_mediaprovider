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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_RESET_ALBUM;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_ALBUM_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_RESET_TYPE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.ForegroundUpdater;
import androidx.work.ProgressUpdater;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.TaskExecutor;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

public class SyncWorkerTestUtils {
    public static void initializeTestWorkManager(@NonNull Context context) {
        Configuration workManagerConfig = new Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(new SynchronousExecutor()) // This runs WM synchronously.
                .build();

        WorkManagerTestInitHelper.initializeTestWorkManager(
                context, workManagerConfig);
    }

    @NonNull
    public static Data getLocalSyncInputData() {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY));
    }

    @NonNull
    public static Data getLocalAlbumSyncInputData(@NonNull String albumId) {
        Objects.requireNonNull(albumId);
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                SYNC_WORKER_INPUT_ALBUM_ID, albumId));
    }

    @NonNull
    public static Data getCloudSyncInputData() {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY));
    }

    @NonNull
    public static Data getAlbumResetInputData(
            @NonNull String albumId, String authority, boolean isLocal) {
        Objects.requireNonNull(albumId);
        Objects.requireNonNull(authority);
        return new Data(
                Map.of(
                        SYNC_WORKER_INPUT_AUTHORITY, authority,
                        SYNC_WORKER_INPUT_SYNC_SOURCE, isLocal ? SYNC_LOCAL_ONLY : SYNC_CLOUD_ONLY,
                        SYNC_WORKER_INPUT_RESET_TYPE, SYNC_RESET_ALBUM,
                        SYNC_WORKER_INPUT_ALBUM_ID, albumId));
    }

    @NonNull
    public static Data getCloudAlbumSyncInputData(@NonNull String albumId) {
        Objects.requireNonNull(albumId);
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY,
                SYNC_WORKER_INPUT_ALBUM_ID, albumId));
    }

    @NonNull
    public static Data getLocalAndCloudSyncInputData() {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
    }

    public static Data getLocalAndCloudAlbumSyncInputData(@NonNull String albumId) {
        Objects.requireNonNull(albumId);
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD,
                SYNC_WORKER_INPUT_ALBUM_ID, albumId));
    }

    /**
     * All the values used in the construction of {@link WorkerParameters} here are default
     * {@link NonNull} values except the {@link Data inputData} which is derived from
     * {@link SyncWorkerTestUtils#getLocalAndCloudSyncInputData()}.
     */
    static WorkerParameters getLocalAndCloudSyncTestWorkParams() {
        return new WorkerParameters(
                UUID.randomUUID(),
                getLocalAndCloudSyncInputData(),
                /* tags= */ Collections.emptyList(),
                new WorkerParameters.RuntimeExtras(),
                /* runAttemptCount= */ 0,
                /* generation= */ 0,
                mock(Executor.class),
                mock(TaskExecutor.class),
                mock(WorkerFactory.class),
                mock(ProgressUpdater.class),
                mock(ForegroundUpdater.class));
    }
}
