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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.util.BackgroundThreadUtils.waitForIdle;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkContinuation;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerSyncRequestExtras;

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
    private WorkContinuation mMockWorkContinuation;
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
    @Captor
    ArgumentCaptor<List<OneTimeWorkRequest>> mOneTimeWorkRequestListArgumentCaptor;

    @Before
    public void setUp() {
        initMocks(this);
        doReturn(mResources).when(mMockContext).getResources();
        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                "com.hooli.super.awesome.cloudpicker");
    }


    @Test
    public void testScheduleEndlessWorker() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        // The third call here comes from the EndlessWorker
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(),
                        any(),
                        mOneTimeWorkRequestArgumentCaptor.capture());

        final OneTimeWorkRequest workRequest = mOneTimeWorkRequestArgumentCaptor.getValue();
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(EndlessWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isFalse();
    }

    @Test
    public void testSchedulePeriodicSyncs() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ true);

        // The third call here comes from the EndlessWorker
        verify(mMockWorkManager, times(2))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(0);
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest periodicResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(1);
        assertThat(periodicResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(periodicResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);
    }

    @Test
    public void testPeriodicWorkIsScheduledOnDeviceConfigChanges() {

        mConfigStore.disableCloudMediaFeature();


        setupPickerSyncManager(true);

        // Ensure no syncs have been scheduled yet.
        verify(mMockWorkManager, times(0))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                "com.hooli.some.cloud.provider");

        waitForIdle();

        // Ensure the syncs are now scheduled.
        verify(mMockWorkManager, times(2))
                .enqueueUniquePeriodicWork(anyString(),
                        any(),
                        mPeriodicWorkRequestArgumentCaptor.capture());

        final PeriodicWorkRequest periodicWorkRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(0);
        assertThat(periodicWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ProactiveSyncWorker.class.getName());
        assertThat(periodicWorkRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicWorkRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        final PeriodicWorkRequest periodicResetRequest =
                mPeriodicWorkRequestArgumentCaptor.getAllValues().get(1);
        assertThat(periodicResetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(periodicResetRequest.getWorkSpec().expedited).isFalse();
        assertThat(periodicResetRequest.getWorkSpec().isPeriodic()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().id).isNotNull();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresCharging()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().constraints.requiresDeviceIdle()).isTrue();
        assertThat(periodicResetRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_AND_CLOUD);

        clearInvocations(mMockWorkManager);

        mConfigStore.disableCloudMediaFeature();
        waitForIdle();

        verify(mMockWorkManager, times(2)).cancelUniqueWork(anyString());
    }

    @Test
    public void testAdhocProactiveSyncLocalOnly() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);
        reset(mMockWorkManager);

        mPickerSyncManager.syncMediaProactively(/* localOnly */ true);
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
                .isEqualTo(SYNC_LOCAL_ONLY);
        assertThat(workRequest.getWorkSpec().initialDelay)
                .isEqualTo(PickerSyncManager.PROACTIVE_SYNC_DELAY_MS);
    }

    @Test
    public void testAdhocProactiveSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        reset(mMockWorkManager);

        mPickerSyncManager.syncMediaProactively(/* localOnly */ false);
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
        assertThat(workRequest.getWorkSpec().initialDelay)
                .isEqualTo(PickerSyncManager.PROACTIVE_SYNC_DELAY_MS);
    }

    @Test
    public void testImmediateGrantsSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mConfigStore.setIsModernPickerEnabled(true);
        reset(mMockWorkManager);
        mPickerSyncManager.syncMediaImmediately(new PickerSyncRequestExtras(/* albumId */null,
                /* albumAuthority */ null, /* initLocalDataOnly */ true,
                /* callingPackageUid */ 0, /* shouldSyncGrants */ true, null));
        verify(mMockWorkManager, times(2))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        // work request 0 is for grants sync.
        WorkRequest workRequest = workRequestList.get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateGrantsSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input
                .getInt(Intent.EXTRA_UID, -1))
                .isEqualTo(0);
        assertThat(workRequest.getWorkSpec().input
                .getBoolean(SHOULD_SYNC_GRANTS, false))
                .isEqualTo(true);
    }

    @Test
    public void testImmediateLocalSync() {
        mConfigStore.setIsModernPickerEnabled(true);
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        reset(mMockWorkManager);
        mPickerSyncManager.syncMediaImmediately(new PickerSyncRequestExtras(/* albumId */null,
                /* albumAuthority */ null, /* initLocalDataOnly */ true,
                /* callingPackageUid */ 0, /* shouldSyncGrants */ false, null));
        verify(mMockWorkManager, times(2))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(2);

        // work request 0 is for grants sync, so use request number 1 for local syncs.
        WorkRequest workRequest = workRequestList.get(1);
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
        mConfigStore.setIsModernPickerEnabled(true);
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        reset(mMockWorkManager);

        mPickerSyncManager.syncMediaImmediately(new PickerSyncRequestExtras(/* albumId */null,
                /* albumAuthority */ null, /* initLocalDataOnly */ false,
                /* callingPackageUid */ 0, /* shouldSyncGrants */ false, null));
        verify(mMockWorkManager, times(3))
                .enqueueUniqueWork(anyString(), any(), mOneTimeWorkRequestArgumentCaptor.capture());

        final List<OneTimeWorkRequest> workRequestList =
                mOneTimeWorkRequestArgumentCaptor.getAllValues();
        assertThat(workRequestList.size()).isEqualTo(3);

        // work request 0 is for grants sync, 1 for local syncs and 2 for cloud syncs.

        WorkRequest localWorkRequest = workRequestList.get(1);
        assertThat(localWorkRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateSyncWorker.class.getName());
        assertThat(localWorkRequest.getWorkSpec().expedited).isTrue();
        assertThat(localWorkRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(localWorkRequest.getWorkSpec().id).isNotNull();
        assertThat(localWorkRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(localWorkRequest.getWorkSpec().input
                .getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);

        WorkRequest cloudWorkRequest = workRequestList.get(2);
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

        mPickerSyncManager.syncAlbumMediaForProviderImmediately(
                "Not_null", PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,
                /* isLocal= */ true);
        verify(mMockWorkManager, times(1))
                .beginUniqueWork(
                        anyString(),
                        any(ExistingWorkPolicy.class),
                        mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation, times(1))
                .then(mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation).enqueue();

        final OneTimeWorkRequest resetRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(0).get(0);
        assertThat(resetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(resetRequest.getWorkSpec().expedited).isTrue();
        assertThat(resetRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(resetRequest.getWorkSpec().id).isNotNull();
        assertThat(resetRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(resetRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);

        final OneTimeWorkRequest workRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(1).get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateAlbumSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_LOCAL_ONLY);
    }

    @Test
    public void testImmediateCloudAlbumSync() {
        setupPickerSyncManager(/* schedulePeriodicSyncs */ false);

        mPickerSyncManager.syncAlbumMediaForProviderImmediately(
                "Not_null", "com.hooli.cloudpicker", /* isLocal= */ false);
        verify(mMockWorkManager, times(1))
                .beginUniqueWork(
                        anyString(),
                        any(ExistingWorkPolicy.class),
                        mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation, times(1))
                .then(mOneTimeWorkRequestListArgumentCaptor.capture());
        verify(mMockWorkContinuation).enqueue();

        final OneTimeWorkRequest resetRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(0).get(0);
        assertThat(resetRequest.getWorkSpec().workerClassName)
                .isEqualTo(MediaResetWorker.class.getName());
        assertThat(resetRequest.getWorkSpec().expedited).isTrue();
        assertThat(resetRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(resetRequest.getWorkSpec().id).isNotNull();
        assertThat(resetRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(resetRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
                .isEqualTo(SYNC_CLOUD_ONLY);

        final OneTimeWorkRequest workRequest =
                mOneTimeWorkRequestListArgumentCaptor.getAllValues().get(1).get(0);
        assertThat(workRequest.getWorkSpec().workerClassName)
                .isEqualTo(ImmediateAlbumSyncWorker.class.getName());
        assertThat(workRequest.getWorkSpec().expedited).isTrue();
        assertThat(workRequest.getWorkSpec().isPeriodic()).isFalse();
        assertThat(workRequest.getWorkSpec().id).isNotNull();
        assertThat(workRequest.getWorkSpec().constraints.requiresBatteryNotLow()).isFalse();
        assertThat(workRequest.getWorkSpec().input.getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1))
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
        doReturn(mMockWorkContinuation)
                .when(mMockWorkManager)
                .beginUniqueWork(
                        anyString(), any(ExistingWorkPolicy.class), any(List.class));
        // Handle .then chaining
        doReturn(mMockWorkContinuation)
                .when(mMockWorkContinuation)
                .then(any(List.class));
        doReturn(mMockOperation).when(mMockWorkContinuation).enqueue();
        doReturn(mMockFuture).when(mMockOperation).getResult();

        mPickerSyncManager =
                new PickerSyncManager(mMockWorkManager, mMockContext,
                        mConfigStore, schedulePeriodicSyncs, /* periodicSyncInitialDelay */ 0L);
        waitForIdle();
    }
}
