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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ForegroundUpdater;
import androidx.work.ListenableWorker;
import androidx.work.ProgressUpdater;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.TaskExecutor;

import com.android.providers.media.photopicker.PickerSyncController;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

public class ProactiveSyncWorkerTest {
    PickerSyncController mMockPickerSyncController;
    Context mMockContext;

    @Before
    public void setup() {
        mMockContext = mock(Context.class);
        mMockPickerSyncController = mock(PickerSyncController.class);
    }

    @Test
    public void testLocalProactiveSync() {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        WorkerParameters workerParameters = getWorkerParams(getLocalSyncInputData());

        // Test run
        ProactiveSyncWorker proactiveSyncWorker =
                new ProactiveSyncWorker(mMockContext, workerParameters);
        ListenableWorker.Result proactiveSyncResult = proactiveSyncWorker.doWork();

        // Verify
        assertThat(proactiveSyncResult).isEqualTo(ListenableWorker.Result.success());
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromLocalProvider();
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromCloudProvider();
    }

    @Test
    public void testCloudProactiveSync() {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        WorkerParameters workerParameters = getWorkerParams(getCloudSyncInputData());

        // Test run
        ProactiveSyncWorker proactiveSyncWorker =
                new ProactiveSyncWorker(mMockContext, workerParameters);
        ListenableWorker.Result proactiveSyncResult = proactiveSyncWorker.doWork();

        // Verify
        assertThat(proactiveSyncResult).isEqualTo(ListenableWorker.Result.success());
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromLocalProvider();
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromCloudProvider();
    }

    @Test
    public void testLocalAndCloudProactiveSync() {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        WorkerParameters workerParameters = getWorkerParams(getLocalAndCloudSyncInputData());

        // Test run
        ProactiveSyncWorker proactiveSyncWorker =
                new ProactiveSyncWorker(mMockContext, workerParameters);
        ListenableWorker.Result proactiveSyncResult = proactiveSyncWorker.doWork();

        // Verify
        assertThat(proactiveSyncResult).isEqualTo(ListenableWorker.Result.success());
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromLocalProvider();
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromCloudProvider();
    }

    @Test
    public void testProactiveSyncFailure() {
        // Setup
        PickerSyncController.setInstance(null);
        WorkerParameters workerParameters = getWorkerParams(getLocalAndCloudSyncInputData());

        // Test run
        ProactiveSyncWorker proactiveSyncWorker =
                new ProactiveSyncWorker(mMockContext, workerParameters);
        ListenableWorker.Result proactiveSyncResult = proactiveSyncWorker.doWork();

        // Verify
        assertThat(proactiveSyncResult).isEqualTo(ListenableWorker.Result.failure());
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromLocalProvider();
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromCloudProvider();
    }

    private WorkerParameters getWorkerParams(Data inputData) {
        return new WorkerParameters(UUID.randomUUID(), inputData, new HashSet<>(),
                mock(WorkerParameters.RuntimeExtras.class), /* runAttempt */ 1, /* generation */ 1,
                mock(Executor.class), mock(TaskExecutor.class), mock(WorkerFactory.class),
                mock(ProgressUpdater.class), mock(ForegroundUpdater.class));
    }

    private Data getLocalSyncInputData() {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY));
    }

    private Data getCloudSyncInputData() {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY));
    }

    private Data getLocalAndCloudSyncInputData() {
        return new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_AND_CLOUD));
    }
}
