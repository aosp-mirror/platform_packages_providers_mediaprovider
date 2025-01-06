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

import static com.android.providers.media.photopicker.sync.PickerSyncManager.SHOULD_SYNC_GRANTS;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_AND_CLOUD;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_RESET_ALBUM;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_ALBUM_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_RESET_TYPE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SEARCH_REQUEST_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestWorkerBuilder;
import androidx.work.testing.WorkManagerTestInitHelper;

import java.util.Map;
import java.util.Objects;

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
    public static Data getGrantsSyncInputData() {
        return new Data(Map.of(
                Intent.EXTRA_UID, /* test uid */ 1,
                SHOULD_SYNC_GRANTS, true
        ));
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
     * Returns input data for the SearchResultsSyncWorker to perform sync with the
     * local provider.
     */
    public static Data getLocalSearchResultsSyncInputData(int searchRequestId,
                                                          @NonNull String authority) {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                SYNC_WORKER_INPUT_SEARCH_REQUEST_ID, searchRequestId,
                SYNC_WORKER_INPUT_AUTHORITY, authority));
    }

    /**
     * Returns input data for the SearchResultsSyncWorker to perform sync with the
     * cloud provider.
     */
    public static Data getCloudSearchResultsSyncInputData(int searchRequestId,
                                                          @NonNull String authority) {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY,
                SYNC_WORKER_INPUT_SEARCH_REQUEST_ID, searchRequestId,
                SYNC_WORKER_INPUT_AUTHORITY, authority));
    }

    /**
     * Returns input data for the SearchResultsSyncWorker to perform sync with the
     * an invalid sync source
     */
    public static Data getInvalidSearchResultsSyncInputData(int searchRequestId,
                                                            @Nullable String authority) {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD,
                SYNC_WORKER_INPUT_SEARCH_REQUEST_ID, searchRequestId,
                SYNC_WORKER_INPUT_AUTHORITY, authority));
    }

    static <W extends Worker> W buildTestWorker(@NonNull Context context,
            @NonNull Class<W> workerClass) {
        return TestWorkerBuilder.from(context, workerClass)
                .setInputData(getLocalAndCloudSyncInputData())
                .build();
    }

    static <W extends Worker> W buildGrantsTestWorker(@NonNull Context context,
            @NonNull Class<W> workerClass) {
        return TestWorkerBuilder.from(context, workerClass)
                .setInputData(getGrantsSyncInputData())
                .build();
    }
}
