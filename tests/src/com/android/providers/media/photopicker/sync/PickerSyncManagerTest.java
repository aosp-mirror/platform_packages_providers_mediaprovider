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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.res.Resources;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.android.providers.media.TestConfigStore;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.List;

public class PickerSyncManagerTest {
    private PickerSyncManager mPickerSyncManager;
    private TestConfigStore mConfigStore;
    @Mock
    private WorkManager mMockWorkManager;
    @Mock
    private Operation mMockOperation;
    @Mock
    private ListenableFuture<Operation.State.SUCCESS> mMockFuture;
    @Mock
    private Context mMockContext;
    @Mock
    private Resources mResources;
    @Captor
    ArgumentCaptor<PeriodicWorkRequest> mPeriodicWorkRequestArgumentCaptor;
    @Captor
    ArgumentCaptor<OneTimeWorkRequest> mOneTimeWorkRequestArgumentCaptor;

    @Before
    public void setUp() {
        initMocks(this);
        doReturn(mResources).when(mMockContext).getResources();
        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                "com.hooli.super.awesome.cloudpicker");
    }

    @Test
    public void testSchedulePeriodicSyncs() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ true);

        verify(mMockWorkManager, times(1))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getValue();
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
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
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isTrue();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
    }

    @Test
    public void testImmediateLocalSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncMediaImmediately(true);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
    }

    @Test
    public void testImmediateCloudSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncMediaImmediately(false);
        verify(mMockWorkManager, times(2))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        WorkRequest localWorkRequest = workRequestList.get(0);
        assertThat(localWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(localWorkRequest.getWorkSpec().expedited).isTrue();
        assertThat(localWorkRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(localWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(localWorkRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(localWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);

        WorkRequest cloudWorkRequest = workRequestList.get(1);
        assertThat(cloudWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(cloudWorkRequest.getWorkSpec().expedited).isTrue();
        assertThat(cloudWorkRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(cloudWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(cloudWorkRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(cloudWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
    }

    @Test
    public void testImmediateLocalAlbumSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncAlbumMediaForProviderImmediately("Not_null", true);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateAlbumSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
    }

    @Test
    public void testImmediateCloudAlbumSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncAlbumMediaForProviderImmediately("Not_null", false);
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateAlbumSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);
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
                new PickerSyncManager(mMockWorkManager, mMockContext,
                        mConfigStore, schedulePeriodicSyncs);
    }
}
