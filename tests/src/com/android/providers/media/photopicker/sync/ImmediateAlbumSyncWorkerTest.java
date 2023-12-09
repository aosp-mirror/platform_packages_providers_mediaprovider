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
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getCloudAlbumSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAlbumSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAndCloudAlbumSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAndCloudSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAndCloudSyncTestWorkParams;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
public class ImmediateAlbumSyncWorkerTest {
    @Mock
    private PickerSyncController mMockPickerSyncController;
    @Mock
    private SyncTracker mMockLocalAlbumSyncTracker;
    @Mock
    private SyncTracker mMockCloudAlbumSyncTracker;
    private Context mContext;

    @Before
    public void setup() {
        initMocks(this);

        // Inject mock trackers
        SyncTrackerRegistry.setLocalAlbumSyncTracker(mMockLocalAlbumSyncTracker);
        SyncTrackerRegistry.setCloudAlbumSyncTracker(mMockCloudAlbumSyncTracker);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        initializeTestWorkManager(mContext);
    }

    @After
    public void teardown() {
        // Reset mock trackers
        SyncTrackerRegistry.setLocalAlbumSyncTracker(new SyncTracker());
        SyncTrackerRegistry.setCloudAlbumSyncTracker(new SyncTracker());
    }

    @Test
    public void testLocalAlbumImmediateSync() throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ImmediateAlbumSyncWorker.class)
                        .setInputData(getLocalAlbumSyncInputData(/* albumId */ "Not_null"))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAlbumMediaFromLocalProvider(anyString(), any(CancellationSignal.class));
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAlbumMediaFromCloudProvider(anyString(), any(CancellationSignal.class));

        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testCloudAlbumImmediateSync() throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ImmediateAlbumSyncWorker.class)
                        .setInputData(getCloudAlbumSyncInputData(/* albumId */ "Not_null"))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 0))
                .syncAlbumMediaFromLocalProvider(anyString(), any(CancellationSignal.class));
        verify(mMockPickerSyncController, times(/* wantedNumberOfInvocations */ 1))
                .syncAlbumMediaFromCloudProvider(anyString(), any(CancellationSignal.class));

        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testInvalidSyncSourceImmediateAlbumSync()
            throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(mMockPickerSyncController);
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ImmediateAlbumSyncWorker.class)
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

        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testLocalAndCloudImmediateSyncFailure()
            throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(null);
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ImmediateAlbumSyncWorker.class)
                        .setInputData(getLocalAndCloudAlbumSyncInputData(/* albumId */ "Not_null"))
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

        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testInvalidAlbumIdImmediateSyncFailure()
            throws ExecutionException, InterruptedException {
        // Setup
        PickerSyncController.setInstance(null);
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ImmediateAlbumSyncWorker.class)
                        .setInputData(getLocalAlbumSyncInputData(/* albumId */ ""))
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

        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testImmediateAlbumSyncWorkerOnStopped() {
        // Setup
        final ImmediateAlbumSyncWorker immediateAlbumSyncWorker =
                new ImmediateAlbumSyncWorker(mContext, getLocalAndCloudSyncTestWorkParams());

        // Test onStopped
        immediateAlbumSyncWorker.onStopped();

        // Verify
        assertThat(immediateAlbumSyncWorker.getCancellationSignal().isCanceled()).isTrue();

        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testGetForegroundInfo() {
        final ForegroundInfo foregroundInfo = new ImmediateAlbumSyncWorker(
                mContext, getLocalAndCloudSyncTestWorkParams()).getForegroundInfo();

        assertThat(foregroundInfo.getNotificationId()).isEqualTo(NOTIFICATION_ID);
        assertThat(foregroundInfo.getNotification().getChannelId())
                .isEqualTo(NOTIFICATION_CHANNEL_ID);
    }
}
