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

import static com.android.providers.media.photopicker.sync.PickerSyncNotificationHelper.NOTIFICATION_CHANNEL_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncNotificationHelper.NOTIFICATION_ID;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.buildGrantsTestWorker;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getGrantsSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.os.Build;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.providers.media.photopicker.PickerSyncController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.concurrent.ExecutionException;


/**
 * Tests to verify sync of grants used in photopicker when invoked with
 * MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.
 *
 * This action is available SDK T and above hence this test has a minSdkVersion to respect this
 * restriction.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class ImmediateGrantsSyncWorkerTest {
    @Mock
    private PickerSyncController mMockPickerSyncController;

    @Mock
    private SyncTracker mMockGrantsSyncTracker;

    private Context mContext;

    @Before
    public void setup() {
        initMocks(this);

        // Inject mock tracker
        SyncTrackerRegistry.setGrantsSyncTracker(mMockGrantsSyncTracker);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        initializeTestWorkManager(mContext);
    }

    @After
    public void teardown() {
        // Reset mock trackers
        SyncTrackerRegistry.setLocalSyncTracker(new SyncTracker());
        SyncTrackerRegistry.setCloudSyncTracker(new SyncTracker());
        SyncTrackerRegistry.setGrantsSyncTracker(new SyncTracker());
    }

    @Test
    public void testGrantsImmediateSync() throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        final OneTimeWorkRequest request  =
                new OneTimeWorkRequest.Builder(ImmediateGrantsSyncWorker.class)
                        .setInputData(getGrantsSyncInputData())
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .executeGrantsSync(true, 1, null);

        verify(mMockGrantsSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockGrantsSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testLocalAndCloudImmediateSyncFailure()
            throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(null);
        final OneTimeWorkRequest request  =
                new OneTimeWorkRequest.Builder(ImmediateGrantsSyncWorker.class)
                        .setInputData(getGrantsSyncInputData())
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .executeGrantsSync(true, 1, null);
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .executeGrantsSync(true, 1, null);

        verify(mMockGrantsSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockGrantsSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testImmediateSyncWorkerOnStopped() {
        // Setup
        final ImmediateGrantsSyncWorker immediateGrantsSyncWorker =
                buildGrantsTestWorker(mContext, ImmediateGrantsSyncWorker.class);

        // Test onStopped
        immediateGrantsSyncWorker.onStopped();

        verify(mMockGrantsSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockGrantsSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testGetForegroundInfo() {
        final ForegroundInfo foregroundInfo =
                buildGrantsTestWorker(mContext, ImmediateGrantsSyncWorker.class)
                        .getForegroundInfo();

        assertThat(foregroundInfo.getNotificationId()).isEqualTo(NOTIFICATION_ID);
        assertThat(foregroundInfo.getNotification().getChannelId())
                .isEqualTo(NOTIFICATION_CHANNEL_ID);
    }
}
