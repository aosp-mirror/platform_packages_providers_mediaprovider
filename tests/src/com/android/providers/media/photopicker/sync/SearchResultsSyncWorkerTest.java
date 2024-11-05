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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SearchResultsSyncWorker.SYNC_COMPLETE_RESUME_KEY;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getCloudSearchResultsSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getInvalidSearchResultsSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.getLocalSearchResultsSyncInputData;
import static com.android.providers.media.photopicker.sync.SyncWorkerTestUtils.initializeTestWorkManager;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.platform.test.annotations.EnableFlags;
import android.provider.CloudMediaProviderContract;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.providers.media.TestConfigStore;
import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.SearchState;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionRequest;
import com.android.providers.media.photopicker.v2.model.SearchSuggestionType;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SearchRequestDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@EnableFlags(Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH)
public class SearchResultsSyncWorkerTest {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private SyncTracker mMockLocalSearchSyncTracker;
    @Mock
    private SyncTracker mMockCloudSearchSyncTracker;
    private Context mContext;
    private SQLiteDatabase mDatabase;
    private PickerDbFacade mFacade;

    @Before
    public void setup() {
        initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        initializeTestWorkManager(mContext);

        SyncTrackerRegistry.setLocalSearchSyncTracker(mMockLocalSearchSyncTracker);
        SyncTrackerRegistry.setCloudSearchSyncTracker(mMockCloudSearchSyncTracker);
        PickerSyncController.setInstance(mMockSyncController);

        final File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        final PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
        mFacade = new PickerDbFacade(
                mContext, new PickerSyncLockManager(), LOCAL_PICKER_PROVIDER_AUTHORITY);
        mFacade.setCloudProvider(SearchProvider.AUTHORITY);

        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsSearchFeatureEnabled(true);
        final SearchState searchState = new SearchState(configStore);

        doReturn(LOCAL_PICKER_PROVIDER_AUTHORITY).when(mMockSyncController).getLocalProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(searchState).when(mMockSyncController).getSearchState();
        doReturn(new PickerSyncLockManager()).when(mMockSyncController).getPickerSyncLockManager();
    }

    @Test
    public void testInvalidSyncSource()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(
                                getInvalidSearchResultsSyncInputData(/* searchRequestId */ 10))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testMissingSearchRequestId()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(
                                new Data(Map.of(SYNC_WORKER_INPUT_SYNC_SOURCE, SYNC_LOCAL_ONLY)))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testInvalidSearchRequestId()
            throws ExecutionException, InterruptedException {
        // Setup
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getLocalSearchResultsSyncInputData(/* searchRequestId */ 10))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testInvalidAlbumSuggestionsSearchRequestId()
            throws ExecutionException, InterruptedException {
        // Setup cloud search results sync for local album
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                null,
                "search text",
                "media-set-id",
                SearchProvider.AUTHORITY,
                SearchSuggestionType.ALBUM,
                null
        );

        SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        final int searchRequestId =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);

        assertWithMessage("Could not find search request is the database " + searchRequest)
                .that(searchRequestId)
                .isNotEqualTo(-1);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getLocalSearchResultsSyncInputData(searchRequestId))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.FAILED);
    }

    @Test
    public void testTextSearchSyncWithCloudProvider()
            throws ExecutionException, InterruptedException {
        // Setup
        SearchTextRequest searchRequest = new SearchTextRequest(
                null,
                "search text",
                null
        );

        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();

        SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        final int searchRequestId =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);

        assertWithMessage("Could not find search request is the database " + searchRequest)
                .that(searchRequestId)
                .isNotEqualTo(-1);

        final Cursor inputCursor = SearchProvider.DEFAULT_CLOUD_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getCloudSearchResultsSyncInputData(searchRequestId))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor cursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(inputCursor.getCount());

            if (cursor.moveToFirst() && inputCursor.moveToFirst()) {
                do {
                    final ContentValues dbValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, dbValues);

                    final ContentValues inputValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(inputCursor, inputValues);

                    assertWithMessage("Cloud id is not as expected")
                            .that(dbValues.getAsString(PickerSQLConstants
                                    .SearchResultMediaTableColumns.CLOUD_ID.getColumnName()))
                            .isEqualTo(inputValues.get(CloudMediaProviderContract.MediaColumns.ID));

                    assertWithMessage("Search request id is not as expected")
                            .that(dbValues.getAsInteger(
                                    PickerSQLConstants.SearchResultMediaTableColumns
                                            .SEARCH_REQUEST_ID.getColumnName()))
                            .isEqualTo(searchRequestId);

                    final String inputMediaStoreUri = inputValues
                            .getAsString(CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI);
                    if (inputMediaStoreUri == null) {
                        assertWithMessage("Local id is not null")
                                .that(dbValues.getAsInteger(
                                        PickerSQLConstants.SearchResultMediaTableColumns
                                                .LOCAL_ID.getColumnName()))
                                .isNull();
                    } else {
                        assertWithMessage("Local id is not as expected")
                                .that(dbValues.getAsInteger(
                                        PickerSQLConstants.SearchResultMediaTableColumns
                                                .LOCAL_ID.getColumnName()))
                                .isEqualTo(ContentUris.parseId(Uri.parse(inputMediaStoreUri)));
                    }
                } while (cursor.moveToNext() && inputCursor.moveToNext());
            }
        }

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testTextSearchSyncWithLocalProvider()
            throws ExecutionException, InterruptedException {
        // Setup
        SearchTextRequest searchRequest = new SearchTextRequest(
                null,
                "search text",
                null
        );

        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getLocalProvider();

        SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        final int searchRequestId =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);

        assertWithMessage("Could not find search request is the database " + searchRequest)
                .that(searchRequestId)
                .isNotEqualTo(-1);

        final Cursor inputCursor = SearchProvider.DEFAULT_LOCAL_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getLocalSearchResultsSyncInputData(searchRequestId))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor cursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(inputCursor.getCount());

            if (cursor.moveToFirst() && inputCursor.moveToFirst()) {
                do {
                    final ContentValues dbValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, dbValues);

                    final ContentValues inputValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(inputCursor, inputValues);

                    assertWithMessage("Local id is not as expected")
                            .that(dbValues.getAsString(PickerSQLConstants
                                    .SearchResultMediaTableColumns.LOCAL_ID.getColumnName()))
                            .isEqualTo(inputValues.get(CloudMediaProviderContract.MediaColumns.ID));

                    assertWithMessage("Cloud id is not null")
                            .that(dbValues.getAsString(PickerSQLConstants
                                    .SearchResultMediaTableColumns.CLOUD_ID.getColumnName()))
                            .isNull();

                    assertWithMessage("Search request id is not as expected")
                            .that(dbValues.getAsInteger(
                                    PickerSQLConstants.SearchResultMediaTableColumns
                                            .SEARCH_REQUEST_ID.getColumnName()))
                            .isEqualTo(searchRequestId);
                } while (cursor.moveToNext() && inputCursor.moveToNext());
            }
        }

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testSuggestionSearchSyncWithCloudProvider()
            throws ExecutionException, InterruptedException {
        // Setup
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                null,
                "search text",
                "media-set-id",
                LOCAL_PICKER_PROVIDER_AUTHORITY,
                SearchSuggestionType.FACE,
                null
        );

        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();

        SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        final int searchRequestId =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);

        assertWithMessage("Could not find search request is the database " + searchRequest)
                .that(searchRequestId)
                .isNotEqualTo(-1);

        final Cursor inputCursor = SearchProvider.DEFAULT_CLOUD_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getCloudSearchResultsSyncInputData(searchRequestId))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor cursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(inputCursor.getCount());

            if (cursor.moveToFirst() && inputCursor.moveToFirst()) {
                do {
                    final ContentValues dbValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, dbValues);

                    final ContentValues inputValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(inputCursor, inputValues);

                    assertWithMessage("Cloud id is not as expected")
                            .that(dbValues.getAsString(PickerSQLConstants
                                    .SearchResultMediaTableColumns.CLOUD_ID.getColumnName()))
                            .isEqualTo(inputValues.get(CloudMediaProviderContract.MediaColumns.ID));

                    assertWithMessage("Search request id is not as expected")
                            .that(dbValues.getAsInteger(
                                    PickerSQLConstants.SearchResultMediaTableColumns
                                            .SEARCH_REQUEST_ID.getColumnName()))
                            .isEqualTo(searchRequestId);

                    final String inputMediaStoreUri = inputValues
                            .getAsString(CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI);
                    if (inputMediaStoreUri == null) {
                        assertWithMessage("Local id is not null")
                                .that(dbValues.getAsInteger(
                                        PickerSQLConstants.SearchResultMediaTableColumns
                                                .LOCAL_ID.getColumnName()))
                                .isNull();
                    } else {
                        assertWithMessage("Local id is not as expected")
                                .that(dbValues.getAsInteger(
                                        PickerSQLConstants.SearchResultMediaTableColumns
                                                .LOCAL_ID.getColumnName()))
                                .isEqualTo(ContentUris.parseId(Uri.parse(inputMediaStoreUri)));
                    }
                } while (cursor.moveToNext() && inputCursor.moveToNext());
            }
        }

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }

    @Test
    public void testSuggestionSearchSyncWithLocalProvider()
            throws ExecutionException, InterruptedException {
        // Setup
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                null,
                "search text",
                "media-set-id",
                SearchProvider.AUTHORITY,
                SearchSuggestionType.FACE,
                "Random-resume-key"
        );

        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getLocalProvider();

        SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        final int searchRequestId =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);

        assertWithMessage("Could not find search request is the database " + searchRequest)
                .that(searchRequestId)
                .isNotEqualTo(-1);

        final Cursor inputCursor = SearchProvider.DEFAULT_LOCAL_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getLocalSearchResultsSyncInputData(searchRequestId))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor cursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(inputCursor.getCount());

            if (cursor.moveToFirst() && inputCursor.moveToFirst()) {
                do {
                    final ContentValues dbValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, dbValues);

                    final ContentValues inputValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(inputCursor, inputValues);

                    assertWithMessage("Local id is not as expected")
                            .that(dbValues.getAsString(PickerSQLConstants
                                    .SearchResultMediaTableColumns.LOCAL_ID.getColumnName()))
                            .isEqualTo(inputValues.get(CloudMediaProviderContract.MediaColumns.ID));

                    assertWithMessage("Cloud id is not null")
                            .that(dbValues.getAsString(PickerSQLConstants
                                    .SearchResultMediaTableColumns.CLOUD_ID.getColumnName()))
                            .isNull();

                    assertWithMessage("Search request id is not as expected")
                            .that(dbValues.getAsInteger(
                                    PickerSQLConstants.SearchResultMediaTableColumns
                                            .SEARCH_REQUEST_ID.getColumnName()))
                            .isEqualTo(searchRequestId);
                } while (cursor.moveToNext() && inputCursor.moveToNext());
            }
        }

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());
    }

    @Test
    public void testSyncWasAlreadyComplete()
            throws ExecutionException, InterruptedException {
        // Setup
        SearchSuggestionRequest searchRequest = new SearchSuggestionRequest(
                null,
                "search text",
                "media-set-id",
                LOCAL_PICKER_PROVIDER_AUTHORITY,
                SearchSuggestionType.FACE,
                SYNC_COMPLETE_RESUME_KEY
        );

        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();

        SearchRequestDatabaseUtil.saveSearchRequest(mDatabase, searchRequest);
        final int searchRequestId =
                SearchRequestDatabaseUtil.getSearchRequestID(mDatabase, searchRequest);

        assertWithMessage("Could not find search request is the database " + searchRequest)
                .that(searchRequestId)
                .isNotEqualTo(-1);

        final Cursor inputCursor = SearchProvider.DEFAULT_CLOUD_SEARCH_RESULTS;
        SearchProvider.setSearchResults(inputCursor);

        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(SearchResultsSyncWorker.class)
                        .setInputData(getCloudSearchResultsSyncInputData(searchRequestId))
                        .build();

        // Test run
        final WorkManager workManager = WorkManager.getInstance(mContext);
        workManager.enqueue(request).getResult().get();

        // Verify
        final WorkInfo workInfo = workManager.getWorkInfoById(request.getId()).get();
        assertThat(workInfo.getState()).isEqualTo(WorkInfo.State.SUCCEEDED);

        try (Cursor cursor = mDatabase.rawQuery(
                new SelectSQLiteQueryBuilder(mDatabase).setTables(
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name()
                ).buildQuery(), null
        )) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(0);
        }

        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockLocalSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .markSyncCompleted(any());

        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 0))
                .createSyncFuture(any());
        verify(mMockCloudSearchSyncTracker, times(/* wantedNumberOfInvocations */ 1))
                .markSyncCompleted(any());
    }
}
