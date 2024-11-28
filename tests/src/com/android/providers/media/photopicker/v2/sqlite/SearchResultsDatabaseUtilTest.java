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

package com.android.providers.media.photopicker.v2.sqlite;

import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GENERATION_MODIFIED;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GIF_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.JPEG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.MP4_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.PNG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.STANDARD_MIME_TYPE_EXTENSION;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.toMediaStoreUri;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.SearchState;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.v2.PickerDataLayerV2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SearchResultsDatabaseUtilTest {
    @Mock
    private PickerSyncController mMockSyncController;
    private SQLiteDatabase mDatabase;
    private Context mContext;
    private PickerDbFacade mFacade;

    @Before
    public void setUp() {
        initMocks(this);
        PickerSyncController.setInstance(mMockSyncController);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
        mFacade = new PickerDbFacade(mContext, new PickerSyncLockManager(), LOCAL_PROVIDER);
        mFacade.setCloudProvider(CLOUD_PROVIDER);

        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsSearchFeatureEnabled(true);
        final SearchState searchState = new SearchState(configStore);

        doReturn(LOCAL_PROVIDER).when(mMockSyncController).getLocalProvider();
        doReturn(CLOUD_PROVIDER).when(mMockSyncController).getCloudProvider();
        doReturn(CLOUD_PROVIDER).when(mMockSyncController).getCloudProviderOrDefault(any());
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(searchState).when(mMockSyncController).getSearchState();
        doReturn(new PickerSyncLockManager()).when(mMockSyncController).getPickerSyncLockManager();
    }

    @After
    public void teardown() {
        mDatabase.close();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
    }

    @Test
    public void testQuerySearchResultsLocalItems() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        final int searchRequestId1 = 1;

        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1)));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(1);

        final long localRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, LOCAL_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_1, null, searchRequestId1)));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(localRowsInsertedCount)
                .isEqualTo(1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(2);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_1);
        }
    }

    @Test
    public void testQuerySearchResultsCloudItems() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(3);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_3);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_1);
        }
    }

    @Test
    public void testQuerySearchResultsIdFilter() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        final int searchRequestId1 = 1;
        final int searchRequestId2 = 2;
        final int searchRequestId3 = 3;

        // Batch insert items in the search results table.
        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId3),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId2),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId2)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(1);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_2);
        }
    }

    @Test
    public void testQuerySearchResultsSortOrder() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final long dateTaken = 0L;

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, dateTaken + 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, dateTaken);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, dateTaken - 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, dateTaken);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, LOCAL_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_4, null, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(4);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_1);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_4);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_3);
        }
    }

    @Test
    public void testQuerySearchResultsPagination() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final long dateTaken = 0L;

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, dateTaken + 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, dateTaken);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, dateTaken - 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, dateTaken);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, LOCAL_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_4, null, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 2);
        extras.putLong("picker_id", 2);
        extras.putLong("date_taken_millis", dateTaken);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(2);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_3);
        }
    }

    @Test
    public void testQuerySearchResultsMimeTypeFilter() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ toMediaStoreUri(LOCAL_ID_2), /* sizeBytes */ 1,
                PNG_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ toMediaStoreUri(LOCAL_ID_3), /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ toMediaStoreUri(LOCAL_ID_4), /* sizeBytes */ 1,
                JPEG_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, LOCAL_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_4, null, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        extras.putStringArrayList("mime_types", new ArrayList<>(List.of("video/mp4", "image/gif")));

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(2);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_3);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_1);
        }
    }

    @Test
    public void testQuerySearchResultsLocalProvidersFilter() {
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long cloudRowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, LOCAL_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_4, null, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(List.of(LOCAL_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(1);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_4);
        }
    }


    @Test
    public void testQuerySearchResultsCloudProvidersFilter() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor4, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_3, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, searchRequestId1),
                        getContentValues(LOCAL_ID_4, null, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(4);

        final Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers", new ArrayList<>(List.of(CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(3);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_3);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_1);
        }
    }


    @Test
    public void testInsertLocalItems() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);

        final int searchRequestId1 = 1;
        final int searchRequestId2 = 2;

        // Batch insert items in the search results table.
        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, LOCAL_PROVIDER, List.of(
                        getContentValues(LOCAL_ID_1, null, searchRequestId1),
                        getContentValues(LOCAL_ID_1, null, searchRequestId2),
                        getContentValues(LOCAL_ID_2, null, searchRequestId2),
                        getContentValues(LOCAL_ID_2, null, searchRequestId2)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(4);

        final Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(List.of(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        // Query items for searchRequestId1
        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(1);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_1);
        }

        // Query items for searchRequestId2
        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId2)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(2);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_1);
        }
    }

    @Test
    public void testInsertCloudItems() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        final int searchRequestId1 = 1;
        final int searchRequestId2 = 2;

        // Batch insert items in the search results table.
        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_1, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId2),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(3);

        final Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(List.of(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        // Query items for searchRequestId1
        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(2);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_1);
        }

        // Query items for searchRequestId2
        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId2)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(1);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_2);
        }
    }

    @Test
    public void testInsertLocalAndCloudItems() {
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        final int searchRequestId1 = 1;

        // Batch insert items in the search results table.
        final long rowsInsertedCount = SearchResultsDatabaseUtil.cacheSearchResults(
                mDatabase, CLOUD_PROVIDER, List.of(
                        getContentValues(null, CLOUD_ID_1, searchRequestId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, searchRequestId1),
                        getContentValues(LOCAL_ID_2, null, searchRequestId1)
                ));

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(rowsInsertedCount)
                .isEqualTo(2);

        final Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(List.of(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        // Query items for searchRequestId
        try (Cursor cursor =
                     PickerDataLayerV2.querySearchMedia(mContext, extras, searchRequestId1)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(2);

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(LOCAL_ID_2);

            cursor.moveToNext();
            assertWithMessage("Media ID is not as expected in the search results")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                    .isEqualTo(CLOUD_ID_1);
        }
    }

    private ContentValues getContentValues(String localId, String cloudId, int searchRequestId) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(
                PickerSQLConstants.SearchResultMediaTableColumns.CLOUD_ID.getColumnName(), cloudId);
        contentValues.put(
                PickerSQLConstants.SearchResultMediaTableColumns.LOCAL_ID.getColumnName(), localId);
        contentValues.put(
                PickerSQLConstants.SearchResultMediaTableColumns.SEARCH_REQUEST_ID.getColumnName(),
                searchRequestId);
        return contentValues;
    }
}
