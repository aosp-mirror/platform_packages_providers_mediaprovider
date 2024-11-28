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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MediaInMediaSetsDatabaseUtilTest {

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
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(LOCAL_PROVIDER).when(mMockSyncController).getLocalProvider();
    }

    @After
    public void teardown() {
        mDatabase.close();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
    }

    @Test
    public void testQueryLocalMediaInMediaSet() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        int cloudRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId)
                ), CLOUD_PROVIDER
        );
        assertEquals(
                "Number of rows inserted should be equal to the number of items in the cursor,",
                /*expected*/cloudRowsInserted,
                /*actual*/1);

        int localRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_1, null, mediaSetPickerId)
                ), LOCAL_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                localRowsInserted,
                1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 2);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(LOCAL_ID_2);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(LOCAL_ID_1);
        mediaCursor.moveToNext();
    }

    @Test
    public void testQueryCloudMediaInMediaSet() {
        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 3);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_3);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_2);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_1);
    }

    @Test
    public void testQueryMediaInMediaSetForSpecificMediaSetPickerId() {
        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        String mediaSetPickerId1 = "ms1";
        String mediaSetPickerId2 = "ms2";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId1),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId2),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId2)
                ), CLOUD_PROVIDER);

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId2);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 2);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_2);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_1);
    }

    @Test
    public void testQueryMediaInMediaSetsSortOrder() {
        final long dateTaken = 0L;

        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, dateTaken + 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, dateTaken);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, dateTaken - 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, dateTaken);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        int localRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_4, null, mediaSetPickerId)
                ), LOCAL_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                localRowsInserted,
                1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 4);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_1);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(LOCAL_ID_4);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_2);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_3);
    }

    @Test
    public void testQueryMediaInMediaSetsPagination() {
        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);

        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 2);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 2);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_3);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_2);
        mediaCursor.moveToNext();
    }

    @Test
    public void testQueryMediaInMediaSetsMimeTypeFilter() {
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

        String mediaSetPickerId = "mediaSetPickerId";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);
        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        int localRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_4, null, mediaSetPickerId)
                ), LOCAL_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                localRowsInserted,
                1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        extras.putStringArrayList("mime_types", new ArrayList<>(List.of("video/mp4", "image/gif")));
        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 2);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_3);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_1);
        mediaCursor.moveToNext();
    }

    @Test
    public void testQueryMediaInMediaSetsLocalProviderFilter() {
        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);
        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        int localRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_4, null, mediaSetPickerId)
                ), LOCAL_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                localRowsInserted,
                1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, LOCAL_PROVIDER, null);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 1);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(LOCAL_ID_4);
    }

    @Test
    public void testQueryMediaInMediaSetsCloudProviderFilter() {
        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_3, LOCAL_ID_3, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        final Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_4, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        final long cloudRowsInsertedCount = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_3, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId),
                        getContentValues(LOCAL_ID_1, CLOUD_ID_1, mediaSetPickerId)
                ), CLOUD_PROVIDER);
        assertWithMessage("Unexpected number of rows inserted in the search results table")
                .that(cloudRowsInsertedCount)
                .isEqualTo(3);

        int localRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_4, null, mediaSetPickerId)
                ), LOCAL_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                localRowsInserted,
                1);

        Bundle extras = new Bundle();
        extras.putInt("page_size", 100);
        extras.putStringArrayList("providers",
                new ArrayList<>(Arrays.asList(CLOUD_PROVIDER)));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);

        MediaInMediaSetsQuery mediaInMediaSetQuery = new MediaInMediaSetsQuery(
                extras, mediaSetPickerId);
        Cursor mediaCursor = MediaInMediaSetsDatabaseUtil.queryMediaInMediaSet(
                mMockSyncController, mediaInMediaSetQuery, null, CLOUD_PROVIDER);
        assertNotNull(mediaCursor);
        assertEquals(mediaCursor.getCount(), 3);

        mediaCursor.moveToFirst();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_3);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_2);
        mediaCursor.moveToNext();

        assertWithMessage("Media ID is not as expected in the search results")
                .that(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(CLOUD_ID_1);
        mediaCursor.moveToNext();
    }

    @Test
    public void testCacheMediaInMediaSet() {
        final Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, null, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        final Cursor cursor3 = getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        String mediaSetPickerId = "mediaSetPickerId";

        int cloudRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(null, CLOUD_ID_1, mediaSetPickerId),
                        getContentValues(LOCAL_ID_2, CLOUD_ID_2, mediaSetPickerId)
                ), CLOUD_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                cloudRowsInserted,
                2);

        // Try to insert the item with same LOCAL_ID as before
        int localRowsInserted = MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                mDatabase, List.of(
                        getContentValues(LOCAL_ID_2, null, mediaSetPickerId)
                ), LOCAL_PROVIDER
        );
        assertEquals(
                "Number of rows inserted is incorrect",
                localRowsInserted,
                1);
    }

    private ContentValues getContentValues(
            String localId, String cloudId, String mediaSetPickerId) {
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
