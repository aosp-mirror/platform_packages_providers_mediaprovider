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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_RESET_ALBUM;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_RESET_TYPE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_TAG_IS_PERIODIC;
import static com.android.providers.media.photopicker.sync.PickerSyncNotificationHelper.NOTIFICATION_CHANNEL_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncNotificationHelper.NOTIFICATION_ID;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getAlbumResetInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalAndCloudSyncTestWorkParams;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.provider.CloudMediaProviderContract.MediaColumns;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutionException;

// TODO enable tests in Android R after fixing b/293390235
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public class MediaResetWorkerTest {

    private PickerSyncController mExistingPickerSyncController;

    @Mock private PickerSyncController mMockPickerSyncController;
    @Mock private SyncTracker mMockLocalAlbumSyncTracker;
    @Mock private SyncTracker mMockCloudAlbumSyncTracker;

    private PickerDbFacade mDbFacade;
    private Context mContext;

    private static final String TEST_ALBUM_ID_1 = "test-album-id-1";
    private static final String TEST_ALBUM_ID_2 = "test-album-id-2";
    private static final String TEST_ALBUM_ID_3 = "test-album-id-3";
    private static final String TEST_ALBUM_ID_4 = "test-album-id-4";
    private static final String TEST_LOCAL_AUTHORITY = "com.android.media.photopicker";
    private static final String TEST_CLOUD_AUTHORITY = "com.hooli.super.awesome.cloud.provider";

    @Before
    public void setup() {
        initMocks(this);

        try {
            mExistingPickerSyncController = PickerSyncController.getInstanceOrThrow();
        } catch (IllegalStateException ignored) {
        }

        // Inject mock trackers
        SyncTrackerRegistry.setLocalAlbumSyncTracker(mMockLocalAlbumSyncTracker);
        SyncTrackerRegistry.setCloudAlbumSyncTracker(mMockCloudAlbumSyncTracker);

        doReturn(new PickerSyncLockManager())
                .when(mMockPickerSyncController).getPickerSyncLockManager();
        doReturn(TEST_CLOUD_AUTHORITY).when(mMockPickerSyncController).getCloudProvider();
        doReturn(TEST_LOCAL_AUTHORITY).when(mMockPickerSyncController).getLocalProvider();

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Cleanup previous test run databases.
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();

        mDbFacade = new PickerDbFacade(mContext, new PickerSyncLockManager(), TEST_LOCAL_AUTHORITY);
        mDbFacade.setCloudProvider(TEST_CLOUD_AUTHORITY);

        initializeTestWorkManager(mContext);
        PickerSyncController.setInstance(mMockPickerSyncController);
    }

    @After
    public void teardown() {
        if (mExistingPickerSyncController != null) {
            PickerSyncController.setInstance(mExistingPickerSyncController);
        }

        // Reset mock trackers
        SyncTrackerRegistry.setLocalAlbumSyncTracker(new SyncTracker());
        SyncTrackerRegistry.setCloudAlbumSyncTracker(new SyncTracker());
    }

    @Test
    public void testResetCloudAlbumMediaForAlbumId()
            throws ExecutionException, InterruptedException {

        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_1, TEST_CLOUD_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_2, TEST_CLOUD_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_3, TEST_LOCAL_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_4, TEST_LOCAL_AUTHORITY);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaResetWorker.class)
                        .setInputData(
                                getAlbumResetInputData(
                                        TEST_ALBUM_ID_1, TEST_CLOUD_AUTHORITY, false))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        // We should have deleted just the rows related to the TEST_ALBUM_ID_1 album.
        Cursor cursor = queryAlbumMediaAll(TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(60);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_ALBUM_ID_1, TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_ALBUM_ID_2, TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(20);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_ALBUM_ID_3, TEST_LOCAL_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(20);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_ALBUM_ID_4, TEST_LOCAL_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(20);
        cursor.close();

        // The sync future is created by the PickerSyncManager before the request is
        // enqueued.
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());

        // The worker should resolve its own sync future.
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testResetLocalAlbumMediaForAlbumId()
            throws ExecutionException, InterruptedException {

        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_1, TEST_LOCAL_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_2, TEST_CLOUD_AUTHORITY);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaResetWorker.class)
                        .setInputData(
                                getAlbumResetInputData(TEST_ALBUM_ID_1, TEST_CLOUD_AUTHORITY, true))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);


        // We should have deleted just the rows related to the TEST_ALBUM_ID_1 album.
        Cursor cursor = queryAlbumMediaAll(TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(20);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_ALBUM_ID_1, TEST_LOCAL_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_ALBUM_ID_2, TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(20);
        cursor.close();

        // The sync future is created by the PickerSyncManager before the request is
        // enqueued.
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());

        // The worker should resolve its own sync future.
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testResetAllAlbumMedia() throws ExecutionException, InterruptedException {

        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_1, TEST_CLOUD_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_2, TEST_CLOUD_AUTHORITY);

        final Data requestData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_AUTHORITY,
                                TEST_CLOUD_AUTHORITY,
                                SYNC_WORKER_INPUT_RESET_TYPE,
                                SYNC_RESET_ALBUM,
                                SYNC_WORKER_INPUT_SYNC_SOURCE,
                                SYNC_LOCAL_AND_CLOUD));

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaResetWorker.class)
                        .setInputData(requestData)
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        Cursor cursor = queryAlbumMediaAll(TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_LOCAL_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();

        // The sync future is created by the PickerSyncManager before the request is
        // enqueued.
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());

        // The worker should resolve its own sync future.
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testPeriodicWorkerAlbumReset_WithCloudProvider()
            throws ExecutionException, InterruptedException {

        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_1, TEST_LOCAL_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_2, TEST_CLOUD_AUTHORITY);

        final Data requestData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_RESET_TYPE,
                                SYNC_RESET_ALBUM,
                                SYNC_WORKER_INPUT_SYNC_SOURCE,
                                SYNC_LOCAL_AND_CLOUD));

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaResetWorker.class)
                        .setInputData(requestData)
                        .addTag(SYNC_WORKER_TAG_IS_PERIODIC)
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        // The sync future is created by the PickerSyncManager before the request is
        // enqueued.
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        Cursor cursor = queryAlbumMediaAll(TEST_CLOUD_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();

        cursor = queryAlbumMediaAll(TEST_LOCAL_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();
    }

    @Test
    public void testPeriodicWorkerAlbumReset_WithLocalProvider()
            throws ExecutionException, InterruptedException {

        doReturn(null).when(mMockPickerSyncController).getCloudProvider();

        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_1, TEST_LOCAL_AUTHORITY);
        assertAddAlbumMediaWithAlbumId(TEST_ALBUM_ID_2, TEST_LOCAL_AUTHORITY);

        final Data requestData =
                new Data(
                        Map.of(
                                SYNC_WORKER_INPUT_RESET_TYPE,
                                SYNC_RESET_ALBUM,
                                SYNC_WORKER_INPUT_SYNC_SOURCE,
                                SYNC_LOCAL_AND_CLOUD));

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaResetWorker.class)
                        .setInputData(requestData)
                        .addTag(SYNC_WORKER_TAG_IS_PERIODIC)
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        // The sync future is created by the PickerSyncManager before the request is
        // enqueued.
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .createSyncFuture(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .createSyncFuture(any());
        verify(mMockCloudAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
        verify(mMockLocalAlbumSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        Cursor cursor = queryAlbumMediaAll(TEST_LOCAL_AUTHORITY);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();
    }

    @Test
    public void testMediaResetWorkerOnStopped() {
        new MediaResetWorker(mContext, getLocalAndCloudSyncTestWorkParams()).onStopped();

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
        final ForegroundInfo foregroundInfo = new MediaResetWorker(
                mContext, getLocalAndCloudSyncTestWorkParams()).getForegroundInfo();

        assertThat(foregroundInfo.getNotificationId()).isEqualTo(NOTIFICATION_ID);
        assertThat(foregroundInfo.getNotification().getChannelId())
                .isEqualTo(NOTIFICATION_CHANNEL_ID);
    }

    /**
     * Builds a suitible mock Album media cursor that could be returned from a provider.
     *
     * @param id a base id for each file. will be appended with the current loop count.
     */
    private static Cursor getAlbumMediaCursor(String id) {
        String[] projectionKey =
                new String[] {
                    MediaColumns.ID,
                    MediaColumns.MEDIA_STORE_URI,
                    MediaColumns.DATE_TAKEN_MILLIS,
                    MediaColumns.SYNC_GENERATION,
                    MediaColumns.SIZE_BYTES,
                    MediaColumns.MIME_TYPE,
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
                    MediaColumns.DURATION_MILLIS,
                };

        MatrixCursor c = new MatrixCursor(projectionKey);
        int counter = 0;

        while (++counter <= 20) {

            String[] projectionValue =
                    new String[] {
                        id + counter,
                        "content://media/external/file/1234" + counter,
                        String.valueOf(System.nanoTime()),
                        String.valueOf(1),
                        String.valueOf(1234),
                        "image/png",
                        String.valueOf(MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE),
                        String.valueOf(1234),
                    };

            c.addRow(projectionValue);
        }
        return c;
    }

    /**
     * Query all records in the Album media table.
     *
     * @param authority provider's authority
     */
    private Cursor queryAlbumMediaAll(String authority) {
        return mDbFacade.queryAlbumMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build(), authority);
    }

    /**
     * @param albumId limit the results to just files present in this album
     * @param authority provider's authority
     */
    private Cursor queryAlbumMediaAll(String albumId, String authority) {
        return mDbFacade.queryAlbumMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).setAlbumId(albumId).build(), authority);
    }

    /**
     * Creates a fake Album with the given Album ID and adds 20 fake files to it.
     *
     * @param albumId the id to use in creating the fake album
     * @param authority the provider that owns the fake album.
     */
    private void assertAddAlbumMediaWithAlbumId(String albumId, String authority) {

        try (PickerDbFacade.DbWriteOperation operation =
                mDbFacade.beginAddAlbumMediaOperation(authority, albumId)) {
            operation.execute(getAlbumMediaCursor("1234-" + albumId));
            operation.setSuccess();
        }

        Cursor cr = queryAlbumMediaAll(albumId, authority);
        assertThat(cr.getCount()).isEqualTo(20);
    }
}
