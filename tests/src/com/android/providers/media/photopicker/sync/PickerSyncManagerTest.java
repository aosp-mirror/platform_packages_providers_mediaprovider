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

import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_AND_CLOUD;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.android.providers.media.TestConfigStore;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

public class PickerSyncManagerTest {
    private PickerSyncManager mPickerSyncManager;
    private WorkManager mMockWorkManager;
    private Operation mMockOperation;
    private ListenableFuture<Operation.State.SUCCESS> mMockFuture;
    private TestConfigStore mConfigStore;
    @Captor
    ArgumentCaptor<PeriodicWorkRequest> mPeriodicWorkRequestArgumentCaptor;
    @Captor
    ArgumentCaptor<OneTimeWorkRequest> mOneTimeWorkRequestArgumentCaptor;

    @Before
    public void setUp() {
        mConfigStore = new TestConfigStore();
        mMockWorkManager = mock(WorkManager.class);
        mMockOperation = mock(Operation.class);
        mMockFuture = mock(ListenableFuture.class);
        mPeriodicWorkRequestArgumentCaptor = ArgumentCaptor.forClass(PeriodicWorkRequest.class);
        mOneTimeWorkRequestArgumentCaptor = ArgumentCaptor.forClass(OneTimeWorkRequest.class);

        mConfigStore.enableCloudMediaFeature();
    }

    @Test
    public void testSchedulePeriodicSyncs() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ true);

        verify(mMockWorkManager, times(1))
                .enqueueUniquePeriodicWork(anyString(),
                        eq(ExistingPeriodicWorkPolicy.KEEP),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        PeriodicWorkRequest periodicWorkRequest = mPeriodicWorkRequestArgumentCaptor.getValue();
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
    }

    @Test
    public void testAdhocProactiveSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncAllMediaProactively();
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        eq(ExistingWorkPolicy.KEEP),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isTrue();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

    }

    private void setupPickerSyncManager(boolean schedulePeriodicSyncs) {
        doReturn(mMockOperation).when(mMockWorkManager)
                .enqueueUniquePeriodicWork(anyString(),
                        any(ExistingPeriodicWorkPolicy.class),
                        any(PeriodicWorkRequest.class));
        doReturn(mMockOperation).when(mMockWorkManager)
                .enqueueUniqueWork(anyString(),
                        any(ExistingWorkPolicy.class),
                        any(OneTimeWorkRequest.class));
        doReturn(mMockFuture).when(mMockOperation).getResult();

        mPickerSyncManager =
                new PickerSyncManager(mMockWorkManager, mConfigStore, schedulePeriodicSyncs);
    }
}
