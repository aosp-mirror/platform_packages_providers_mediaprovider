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
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getCloudSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAndCloudSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAndCloudSyncTestWorkParams;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.os.Build;
import android.os.CancellationSignal;

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

// TODO enable tests in Android R after fixing b/293390235
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public class ImmediateSyncWorkerTest {
    @Mock
    private PickerSyncController mMockPickerSyncController;
    @Mock
    private SyncTracker mMockLocalSyncTracker;
    @Mock
    private SyncTracker mMockCloudSyncTracker;
    private Context mContext;

    @Before
    public void setup() {
        initMocks(this);

        // Inject mock trackers
        SyncTrackerRegistry.setLocalSyncTracker(mMockLocalSyncTracker);
        SyncTrackerRegistry.setCloudSyncTracker(mMockCloudSyncTracker);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        initializeTestWorkManager(mContext);
    }

    @After
    public void teardown() {
        // Reset mock trackers
        SyncTrackerRegistry.setLocalSyncTracker(new SyncTracker());
        SyncTrackerRegistry.setCloudSyncTracker(new SyncTracker());
    }

    @Test
    public void testLocalImmediateSync() throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        final OneTimeWorkRequest request  =
                new OneTimeWorkRequest.Builder(ImmediateSyncWorker.class)
                        .setInputData(getLocalSyncInputData())
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromLocalProvider(any(CancellationSignal.class));
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromCloudProvider(any(CancellationSignal.class));

        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testCloudImmediateSync() throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        OneTimeWorkRequest request  = new OneTimeWorkRequest.Builder(ImmediateSyncWorker.class)
                .setInputData(getCloudSyncInputData())
                .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromLocalProvider(any(CancellationSignal.class));
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromCloudProvider(any(CancellationSignal.class));

        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testLocalAndCloudImmediateSync() throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        final OneTimeWorkRequest request  =
                new OneTimeWorkRequest.Builder(ImmediateSyncWorker.class)
                        .setInputData(getLocalAndCloudSyncInputData())
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromLocalProvider(any(CancellationSignal.class));
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAllMediaFromCloudProvider(any(CancellationSignal.class));

        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testLocalAndCloudImmediateSyncFailure()
            throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(null);
        final OneTimeWorkRequest request  =
                new OneTimeWorkRequest.Builder(ImmediateSyncWorker.class)
                        .setInputData(getLocalAndCloudSyncInputData())
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromLocalProvider(any(CancellationSignal.class));
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAllMediaFromCloudProvider(any(CancellationSignal.class));

        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testImmediateSyncWorkerOnStopped() {
        // Setup
        final ImmediateSyncWorker immediateSyncWorker =
                new ImmediateSyncWorker(mContext, getLocalAndCloudSyncTestWorkParams());

        // Test onStopped
        immediateSyncWorker.onStopped();

        // Verify
        assertThat(immediateSyncWorker.getCancellationSignal().isCanceled()).isTrue();

        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testGetForegroundInfo() {
        final ForegroundInfo foregroundInfo = new ImmediateSyncWorker(
                mContext, getLocalAndCloudSyncTestWorkParams()).getForegroundInfo();

        assertThat(foregroundInfo.getNotificationId()).isEqualTo(NOTIFICATION_ID);
        assertThat(foregroundInfo.getNotification().getChannelId())
                .isEqualTo(NOTIFICATION_CHANNEL_ID);
    }
}
