/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_CATEGORY_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_PROVIDER;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.platform.test.annotations.EnableFlags;
import android.provider.CloudMediaProviderContract;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@EnableFlags(Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH)
public class MediaSetsSyncWorkerTest {

    private SQLiteDatabase mDatabase;
    private PickerDbFacade mFacade;
    private Context mContext;
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private SyncTracker mLocalMediaSetsSyncTracker;
    @Mock
    private SyncTracker mCloudMediaSetsSyncTracker;
    private final String mCategoryId = "categoryId";

    @Before
    public void setup() {
        initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        initializeTestWorkManager(mContext);
        PickerSyncController.setInstance(mMockSyncController);
        SyncTrackerRegistry.setCloudMediaSetsSyncTracker(mCloudMediaSetsSyncTracker);
        SyncTrackerRegistry.setLocalMediaSetsSyncTracker(mLocalMediaSetsSyncTracker);
        final File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        final PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
        mFacade = new PickerDbFacade(
                mContext, new PickerSyncLockManager(), LOCAL_PICKER_PROVIDER_AUTHORITY);
        mFacade.setCloudProvider(CLOUD_PROVIDER);
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getLocalProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
    }

    @After
    public void teardown() {
        mDatabase.close();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
    }

    @Test
    public void testMediaSetsSyncWithInvalidSyncSource()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, 56,
                                        SYNC_WORKER_INPUT_CATEGORY_ID, mCategoryId)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaSetsSyncWithMissingSyncSource()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(
                                        SYNC_WORKER_INPUT_CATEGORY_ID, mCategoryId,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaSetsSyncWithInvalidCategoryId()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(
                                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                                        SYNC_WORKER_INPUT_CATEGORY_ID, "",
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaSetsSyncWithMissingCategoryId()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(
                                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaSetsSyncWithMissingCategoryAuthority()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(
                                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY,
                                        SYNC_WORKER_INPUT_CATEGORY_ID, mCategoryId)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaSetsSyncWithValidSyncSourceAndCategoryIdForCloudAuth() throws
            ExecutionException, InterruptedException {

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY,
                                        SYNC_WORKER_INPUT_CATEGORY_ID, mCategoryId,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        Cursor cursorFromSearchProvider = SearchProvider.getCursorForMediaSetSyncTest();

        try (Cursor cursorFromMediaSetTable = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursorFromMediaSetTable)
                    .isNotNull();
            assertEquals(cursorFromMediaSetTable.getCount(), cursorFromSearchProvider.getCount());

            compareMediaSetCursorsForMediaSetProperties(
                    cursorFromMediaSetTable, cursorFromSearchProvider);
        }

        verify(mLocalMediaSetsSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mLocalMediaSetsSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mCloudMediaSetsSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mCloudMediaSetsSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testMediaSetsSyncWithValidSyncSourceAndCategoryIdForLocalAuth() throws
            ExecutionException, InterruptedException {

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(
                                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                                        SYNC_WORKER_INPUT_CATEGORY_ID, mCategoryId,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        Cursor cursorFromSearchProvider = SearchProvider.getCursorForMediaSetSyncTest();

        try (Cursor cursorFromMediaSetTable = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursorFromMediaSetTable)
                    .isNotNull();
            assertEquals(cursorFromMediaSetTable.getCount(), cursorFromSearchProvider.getCount());

            compareMediaSetCursorsForMediaSetProperties(
                    cursorFromMediaSetTable, cursorFromSearchProvider);
        }
    }


    private void compareMediaSetCursorsForMediaSetProperties(
            Cursor cursorFromMediaSetTable, Cursor cursorFromSearchProvider) {

        if (cursorFromMediaSetTable.moveToFirst() && cursorFromSearchProvider.moveToFirst()) {

            assertEquals(/*expected*/cursorFromMediaSetTable.getString(
                            cursorFromMediaSetTable.getColumnIndex(
                                    PickerSQLConstants.MediaSetsTableColumns.CATEGORY_ID
                                            .getColumnName())),
                    /*actual*/mCategoryId);

            assertEquals(cursorFromMediaSetTable.getString(
                            cursorFromMediaSetTable.getColumnIndex(
                                    PickerSQLConstants.MediaSetsTableColumns.DISPLAY_NAME
                                            .getColumnName())),
                    cursorFromSearchProvider.getString(
                            cursorFromSearchProvider.getColumnIndex(
                                    CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME
                            ))
            );

            assertEquals(cursorFromMediaSetTable.getString(
                            cursorFromMediaSetTable.getColumnIndex(
                                    PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID
                                            .getColumnName())),
                    cursorFromSearchProvider.getString(
                            cursorFromSearchProvider.getColumnIndex(
                                    CloudMediaProviderContract.MediaSetColumns.ID
                            ))
            );

            assertEquals(cursorFromMediaSetTable.getString(
                            cursorFromMediaSetTable.getColumnIndex(
                                    PickerSQLConstants.MediaSetsTableColumns.COVER_ID
                                            .getColumnName())),
                    cursorFromSearchProvider.getString(
                            cursorFromSearchProvider.getColumnIndex(
                                    CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID
                            ))
            );
        }
    }
}
