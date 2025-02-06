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
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.CloudMediaProviderContract;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.v2.sqlite.MediaInMediaSetsDatabaseUtil;
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
import java.util.concurrent.ExecutionException;

public class MediaSetsResetWorkerTest {

    private SQLiteDatabase mDatabase;
    private PickerDbFacade mFacade;
    private Context mContext;
    private final String mMediaSetId = "mediaSetId";
    private final String mCategoryId = "categoryId";
    private final String mAuthority = "auth";
    private final String mMimeType = "img";
    private final String mDisplayName = "name";
    private final String mCoverId = "id";
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private SyncTracker mLocalMediaSetsSyncTracker;
    @Mock
    private SyncTracker mCloudMediaSetsSyncTracker;

    @Before
    public void setup() {
        initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PickerSyncController.setInstance(mMockSyncController);
        SyncTrackerRegistry.setCloudMediaSetsSyncTracker(mCloudMediaSetsSyncTracker);
        SyncTrackerRegistry.setLocalMediaSetsSyncTracker(mLocalMediaSetsSyncTracker);
        initializeTestWorkManager(mContext);
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
    public void testMediaSetsAndMediaSetsContentCacheReset() throws
            ExecutionException, InterruptedException   {
        Cursor c = getCursorForMediaSetInsertionTest();
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        Long mediaSetPickerId = 1L;

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaSetsResetWorker.class)
                        .build();

        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor cursorFromMediaSetTable = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursorFromMediaSetTable)
                    .isNotNull();
            assertEquals(/*expected*/ 0, /*actual*/ cursorFromMediaSetTable.getCount());
        }

        try (Cursor cursorFromMediaInMediaSetTable = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursorFromMediaInMediaSetTable)
                    .isNotNull();
            assertEquals(/*expected */ 0, /*actual*/ cursorFromMediaInMediaSetTable.getCount());
        }
    }

    private Cursor getCursorForMediaSetInsertionTest() {
        String[] columns = new String[]{
                CloudMediaProviderContract.MediaSetColumns.ID,
                CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME,
                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID
        };

        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(new Object[] { mMediaSetId, mDisplayName, mCoverId });

        return cursor;
    }

    private ContentValues getContentValues(
            String localId, String cloudId, Long mediaSetPickerId) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(
                PickerSQLConstants.MediaInMediaSetsTableColumns.CLOUD_ID.getColumnName(), cloudId);
        contentValues.put(
                PickerSQLConstants.MediaInMediaSetsTableColumns.LOCAL_ID.getColumnName(), localId);
        contentValues.put(
                PickerSQLConstants.MediaInMediaSetsTableColumns.MEDIA_SETS_PICKER_ID
                        .getColumnName(),
                mediaSetPickerId);
        return contentValues;
    }

}
