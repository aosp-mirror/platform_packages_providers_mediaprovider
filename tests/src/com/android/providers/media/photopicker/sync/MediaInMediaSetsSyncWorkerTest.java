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
import static com.android.providers.media.photopicker.sync.MediaInMediaSetsSyncWorker.SYNC_COMPLETE_RESUME_KEY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
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
import com.android.providers.media.photopicker.v2.model.MediaSetsSyncRequestParams;
import com.android.providers.media.photopicker.v2.sqlite.MediaSetsDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@EnableFlags(Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH)
public class MediaInMediaSetsSyncWorkerTest {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private SyncTracker mMockLocalMediaInMediaSetTracker;
    @Mock
    private SyncTracker mMockCloudMediaInMediaSetTracker;
    private Context mContext;
    private SQLiteDatabase mDatabase;
    private PickerDbFacade mFacade;

    @Before
    public void setup() {
        initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        initializeTestWorkManager(mContext);

        SyncTrackerRegistry.setLocalMediaInMediaSetTracker(mMockLocalMediaInMediaSetTracker);
        SyncTrackerRegistry.setCloudMediaInMediaSetTracker(mMockCloudMediaInMediaSetTracker);
        PickerSyncController.setInstance(mMockSyncController);

        final File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        final PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
        mFacade = new PickerDbFacade(
                mContext, new PickerSyncLockManager(), LOCAL_PICKER_PROVIDER_AUTHORITY);
        mFacade.setCloudProvider(SearchProvider.AUTHORITY);

        doReturn(LOCAL_PICKER_PROVIDER_AUTHORITY).when(mMockSyncController).getLocalProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(new PickerSyncLockManager()).when(mMockSyncController).getPickerSyncLockManager();
    }

    @After
    public void teardown() {
        mDatabase.close();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
    }

    @Test
    public void testMediaInMediaSetSyncWithInvalidSyncSource() throws
            ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaInMediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, 56,
                                        SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, "mediaSetPickerId",
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaInMediaSetSyncWithInvalidMediaSetPickerId() throws
            ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaInMediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                                        SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, "",
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaInMediaSetSyncWithInvalidMediaSetAuthority() throws
            ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaInMediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                                        SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, "mediaSetPickerId",
                                        SYNC_WORKER_INPUT_AUTHORITY, "")))
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testMediaInMediaSetSyncWithCloudProvider() throws
            ExecutionException, InterruptedException {

        String categoryId = "categoryId";
        String auth = String.valueOf(SYNC_CLOUD_ONLY);
        String mediaSetPickerId = "";
        Cursor c = getCursorForMediaSetInsertionTest();
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add("img");

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, categoryId, auth, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);
        Bundle extras = new Bundle();
        extras.putString("authority", auth);
        extras.putString("category_id", categoryId);
        extras.putStringArray("mime_types", mimeTypes.toArray(new String[mimeTypes.size()]));
        MediaSetsSyncRequestParams requestParams = new MediaSetsSyncRequestParams(extras);
        Cursor fetchMediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        if (fetchMediaSetCursor.moveToFirst()) {
            mediaSetPickerId = fetchMediaSetCursor.getString(
                    fetchMediaSetCursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()));
        }

        final Cursor inputCursor = SearchProvider.DEFAULT_CLOUD_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaInMediaSetsSyncWorker.class)
                        .setInputData(new Data(
                                Map.of(
                                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY,
                                        SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, mediaSetPickerId,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor mediaInMediaSetsTableCursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(mediaInMediaSetsTableCursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(mediaInMediaSetsTableCursor.getCount())
                    .isEqualTo(inputCursor.getCount());

            if (mediaInMediaSetsTableCursor.moveToFirst() && inputCursor.moveToFirst()) {

                do {

                    assertEquals(mediaInMediaSetsTableCursor.getString(
                                    mediaInMediaSetsTableCursor.getColumnIndex(
                                            PickerSQLConstants.MediaInMediaSetsTableColumns.CLOUD_ID
                                                    .getColumnName())),
                            inputCursor.getString(
                                    inputCursor.getColumnIndex(
                                            CloudMediaProviderContract.MediaColumns.ID
                                    ))
                    );

                    assertEquals(mediaInMediaSetsTableCursor.getString(
                                    mediaInMediaSetsTableCursor.getColumnIndex(
                                            PickerSQLConstants.MediaInMediaSetsTableColumns
                                                    .MEDIA_SETS_PICKER_ID.getColumnName())),
                            mediaSetPickerId
                    );

                    String mediaStoreUri = inputCursor.getString(inputCursor.getColumnIndex(
                            CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI
                    ));

                    if (mediaStoreUri == null) {
                        assertTrue(mediaInMediaSetsTableCursor.isNull(
                                mediaInMediaSetsTableCursor.getColumnIndex(
                                        PickerSQLConstants.MediaInMediaSetsTableColumns
                                                .LOCAL_ID.getColumnName()))
                        );
                    } else {
                        String localId = String.valueOf(
                                ContentUris.parseId(Uri.parse(mediaStoreUri)));
                        assertEquals(mediaInMediaSetsTableCursor.getString(
                                        mediaInMediaSetsTableCursor.getColumnIndex(
                                                PickerSQLConstants.MediaInMediaSetsTableColumns
                                                        .LOCAL_ID.getColumnName())), localId
                        );
                    }

                } while (mediaInMediaSetsTableCursor.moveToNext() && inputCursor.moveToNext());
            }
        }

        verify(mMockLocalMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mMockCloudMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testMediaInMediaSetsSyncLocalProvider() throws
            ExecutionException, InterruptedException {

        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getLocalProvider();

        String categoryId = "categoryId";
        String auth = String.valueOf(SYNC_LOCAL_ONLY);
        String mediaSetPickerId = "";
        Cursor c = getCursorForMediaSetInsertionTest();
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add("img");

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, categoryId, auth, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);
        Bundle extras = new Bundle();
        extras.putString("authority", auth);
        extras.putString("category_id", categoryId);
        extras.putStringArray("mime_types", mimeTypes.toArray(new String[mimeTypes.size()]));
        MediaSetsSyncRequestParams requestParams = new MediaSetsSyncRequestParams(extras);
        Cursor fetchMediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        if (fetchMediaSetCursor.moveToFirst()) {
            mediaSetPickerId = fetchMediaSetCursor.getString(
                    fetchMediaSetCursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()));
        }

        final Cursor inputCursor = SearchProvider.DEFAULT_LOCAL_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaInMediaSetsSyncWorker.class)
                        .setInputData(new Data(
                                Map.of(
                                        SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY,
                                        SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, mediaSetPickerId,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor mediaInMediaSetsTableCursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(mediaInMediaSetsTableCursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(mediaInMediaSetsTableCursor.getCount())
                    .isEqualTo(inputCursor.getCount());

            if (mediaInMediaSetsTableCursor.moveToFirst() && inputCursor.moveToFirst()) {

                do {

                    assertEquals(mediaInMediaSetsTableCursor.getString(
                                    mediaInMediaSetsTableCursor.getColumnIndex(
                                            PickerSQLConstants.MediaInMediaSetsTableColumns.LOCAL_ID
                                                    .getColumnName())),
                            inputCursor.getString(
                                    inputCursor.getColumnIndex(
                                            CloudMediaProviderContract.MediaColumns.ID
                                    ))
                    );

                    assertEquals(mediaInMediaSetsTableCursor.getString(
                                    mediaInMediaSetsTableCursor.getColumnIndex(
                                            PickerSQLConstants.MediaInMediaSetsTableColumns
                                                    .MEDIA_SETS_PICKER_ID.getColumnName())),
                            mediaSetPickerId
                    );

                    assertTrue(mediaInMediaSetsTableCursor.isNull(
                            mediaInMediaSetsTableCursor.getColumnIndex(
                                    PickerSQLConstants.MediaInMediaSetsTableColumns
                                            .CLOUD_ID.getColumnName()))
                    );

                } while (mediaInMediaSetsTableCursor.moveToNext() && inputCursor.moveToNext());
            }
        }
        verify(mMockLocalMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    private Cursor getCursorForMediaSetInsertionTest() {
        String[] columns = new String[]{
                CloudMediaProviderContract.MediaSetColumns.ID,
                CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME,
                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID
        };

        final String mediaSetId = "mediaSetId";
        final String displayName = "name";
        final String coverId = "coverId";
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(new Object[] { mediaSetId, displayName, coverId });

        return cursor;
    }

    @Test
    public void testMediaInMediaSetSyncComplete() throws
            ExecutionException, InterruptedException {

        String categoryId = "categoryId";
        String auth = String.valueOf(SYNC_CLOUD_ONLY);
        String mediaSetPickerId = "";
        Cursor c = getCursorForMediaSetInsertionTest();
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add("img");

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, categoryId, auth, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);
        Bundle extras = new Bundle();
        extras.putString("authority", auth);
        extras.putString("category_id", categoryId);
        extras.putStringArray("mime_types", mimeTypes.toArray(new String[mimeTypes.size()]));
        MediaSetsSyncRequestParams requestParams = new MediaSetsSyncRequestParams(extras);
        Cursor fetchMediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        if (fetchMediaSetCursor.moveToFirst()) {
            mediaSetPickerId = fetchMediaSetCursor.getString(
                    fetchMediaSetCursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()));
        }
        MediaSetsDatabaseUtil.updateMediaInMediaSetSyncResumeKey(
                mDatabase, mediaSetPickerId, SYNC_COMPLETE_RESUME_KEY);

        final Cursor inputCursor = SearchProvider.DEFAULT_CLOUD_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaInMediaSetsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_CLOUD_ONLY,
                                        SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID, mediaSetPickerId,
                                        SYNC_WORKER_INPUT_AUTHORITY, SearchProvider.AUTHORITY)))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor mediaInMediaSetsTableCursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(mediaInMediaSetsTableCursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(mediaInMediaSetsTableCursor.getCount())
                    .isEqualTo(0);

            verify(mMockLocalMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                    .createSyncFuture(any());
            verify(mMockLocalMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                    .markSyncCompleted(any());

            verify(mMockCloudMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 0))
                    .createSyncFuture(any());
            verify(mMockCloudMediaInMediaSetTracker, times(/* wantedNumberOfInvocations */ 1))
                    .markSyncCompleted(any());
        }
    }
}
