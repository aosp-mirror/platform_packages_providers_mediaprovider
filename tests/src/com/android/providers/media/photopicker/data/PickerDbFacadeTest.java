/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.data;

import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;

import static com.android.providers.media.util.MimeUtils.getExtensionFromMimeType;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.provider.Column;
import android.provider.ExportedSince;
import android.provider.MediaStore.PickerMediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.ProjectionHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class PickerDbFacadeTest {
    private static final long SIZE_BYTES = 7000;
    private static final long DATE_TAKEN_MS = 1623852851911L;
    private static final long GENERATION_MODIFIED = 1L;
    private static final long DURATION_MS = 5;
    private static final int HEIGHT = 720;
    private static final int WIDTH = 1080;
    private static final int ORIENTATION = 90;
    private static final String LOCAL_ID = "50";
    private static final String LOCAL_ID_1 = "501";
    private static final String LOCAL_ID_2 = "502";
    private static final String LOCAL_ID_3 = "503";
    private static final String LOCAL_ID_4 = "504";
    private static final String CLOUD_ID = "asdfghjkl;";
    private static final String CLOUD_ID_1 = "asdfghjkl;1";
    private static final String CLOUD_ID_2 = "asdfghjkl;2";
    private static final String CLOUD_ID_3 = "asdfghjkl;3";
    private static final String CLOUD_ID_4 = "asdfghjkl;4";
    private static final String ALBUM_ID = "testAlbum";
    private static final String MP4_VIDEO_MIME_TYPE = "video/mp4";
    private static final String WEBM_VIDEO_MIME_TYPE = "video/webm";
    private static final String MPEG_VIDEO_MIME_TYPE = "video/mpeg";
    private static final String M4V_VIDEO_MIME_TYPE = "video/m4v";
    private static final String[] VIDEO_MIME_TYPES_QUERY = new String[]{"video/mp4"};
    private static final String JPEG_IMAGE_MIME_TYPE = "image/jpeg";
    private static final String GIF_IMAGE_MIME_TYPE = "image/gif";
    private static final String PNG_IMAGE_MIME_TYPE = "image/png";
    private static final String[] IMAGE_MIME_TYPES_QUERY = new String[]{"image/jpeg"};
    private static final int STANDARD_MIME_TYPE_EXTENSION =
            MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF;

    private static final String LOCAL_PROVIDER = "com.local.provider";
    private static final String CLOUD_PROVIDER = "com.cloud.provider";

    private PickerDbFacade mFacade;
    private Context mContext;
    private ProjectionHelper mProjectionHelper;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        mFacade = new PickerDbFacade(mContext, LOCAL_PROVIDER);
        mFacade.setCloudProvider(CLOUD_PROVIDER);
        mProjectionHelper = new ProjectionHelper(Column.class, ExportedSince.class);
    }

    @After
    public void tearDown() {
        if (mFacade != null) {
            mFacade.setCloudProvider(null);
        }
    }

    @Test
    public void testAddLocalOnlyMedia() throws Exception {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(LOCAL_PROVIDER, cursor1, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row
        assertAddMediaOperation(LOCAL_PROVIDER, cursor2, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudPlusLocal() throws Exception {
        Cursor cursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(CLOUD_PROVIDER, cursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudOnly() throws Exception {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID, null, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getCloudMediaCursor(CLOUD_ID, null, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(CLOUD_PROVIDER, cursor1, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row
        assertAddMediaOperation(CLOUD_PROVIDER, cursor2, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddLocalAndCloud_Dedupe() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudAndLocal_Dedupe() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 1);
        }
    }

    @Test
    public void testAddLocalAlbumMedia() {
        Cursor cursor1 = getAlbumMediaCursor(LOCAL_ID, /* cloud id */ null, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getAlbumMediaCursor(LOCAL_ID, /* cloud id */ null, DATE_TAKEN_MS + 2);

        assertAddAlbumMediaOperation(LOCAL_PROVIDER, cursor1, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID, true)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row. We always do a full sync for album media files.
        assertResetAlbumMediaOperation(LOCAL_PROVIDER, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(LOCAL_PROVIDER, cursor2, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID, true)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudAlbumMedia() {
        Cursor cursor1 = getAlbumMediaCursor(/* local id */ null, CLOUD_ID, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getAlbumMediaCursor(/* local id */ null, CLOUD_ID, DATE_TAKEN_MS + 2);

        assertAddAlbumMediaOperation(CLOUD_PROVIDER, cursor1, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID, false)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row. We always do a full sync for album media files.
        assertResetAlbumMediaOperation(CLOUD_PROVIDER, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(CLOUD_PROVIDER, cursor2, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID, false)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudAlbumMediaAvailableOnDevice() {
        // Add local row for a media item in media table.
        final Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);

        // Attempt to insert a media item available locally and on cloud in album_media table.
        final Cursor cloudCursor =
                getAlbumMediaCursor(LOCAL_ID, CLOUD_ID, DATE_TAKEN_MS + 1);
        assertAddAlbumMediaOperation(CLOUD_PROVIDER, cloudCursor, 1, ALBUM_ID);

        // Assert that preference was given to the local media item over cloud media item at the
        // time of insertion in album_media table.
        try (Cursor albumCursor = queryAlbumMedia(ALBUM_ID, false)) {
            assertThat(albumCursor.getCount()).isEqualTo(1);
            albumCursor.moveToFirst();
            assertCloudMediaCursor(albumCursor, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudAlbumMediaDeletedFromDevice() {
        // Attempt to insert a media item deleted from device and available on cloud in the
        // album_media table.
        final Cursor cloudCursor =
                getAlbumMediaCursor(LOCAL_ID, CLOUD_ID, DATE_TAKEN_MS);
        assertAddAlbumMediaOperation(CLOUD_PROVIDER, cloudCursor, 1, ALBUM_ID);

        // Assert that cloud media metadata was inserted in the database as local_id points to a
        // deleted item.
        try (Cursor albumCursor = queryAlbumMedia(ALBUM_ID, false)) {
            assertThat(albumCursor.getCount()).isEqualTo(1);
            albumCursor.moveToFirst();
            assertCloudMediaCursor(albumCursor, CLOUD_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testRemoveLocal() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
        }

        assertRemoveMediaOperation(LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveLocal_promote() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertRemoveMediaOperation(LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testRemoveCloud() throws Exception {
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
        }

        assertRemoveMediaOperation(CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveCloud_promote() throws Exception {
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID + "1", LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID + "2", LOCAL_ID, DATE_TAKEN_MS + 2);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID + "1", DATE_TAKEN_MS + 1);
        }

        assertRemoveMediaOperation(CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID + "1"), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID + "2", DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testRemoveHidden() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertRemoveMediaOperation(CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }


    @Test
    public void testLocalUpdate() throws Exception {
        Cursor localCursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor localCursor2 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 2);
        }

        assertRemoveMediaOperation(LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCloudUpdate_withoutLocal() throws Exception {
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor1, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor2, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }

        assertRemoveMediaOperation(CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCloudUpdate_withLocal() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor1, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor2, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertRemoveMediaOperation(LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }

        assertRemoveMediaOperation(CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testResetLocal() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        // Add two cloud_ids mapping to the same local_id to verify that
        // only one gets promoted
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID + "1", LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID + "2", LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor1, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor2, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertResetMediaOperation(LOCAL_PROVIDER, null, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();

            // Verify that local_id was deleted and either of cloudCursor1 or cloudCursor2
            // was promoted
            assertThat(cr.getString(1)).isNotNull();
        }
    }

    @Test
    public void testResetCloud() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertResetMediaOperation(CLOUD_PROVIDER, null, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithDateTakenFilter() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
        }

        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(5);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS - 1);
        qfbBefore.setId(5);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(0);
        }

        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(5);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS + 1);
        qfbAfter.setId(5);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testQueryWithIdFilter() throws Exception {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, cursor1, 1);
            assertWriteOperation(operation, cursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(5);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS);
        qfbBefore.setId(2);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "1", DATE_TAKEN_MS);
        }

        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(5);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS);
        qfbAfter.setId(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "2", DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithLimit() throws Exception {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS);
        Cursor cursor2 = getCloudMediaCursor(CLOUD_ID + "2", null, DATE_TAKEN_MS);
        Cursor cursor3 = getLocalMediaCursor(LOCAL_ID + "3", DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, cursor3, 1);

        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "3", DATE_TAKEN_MS);
        }

        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "3", DATE_TAKEN_MS);
        }

        try (Cursor cr = mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1).build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "3", DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithSizeFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor2, 1);

        // Verify all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
        }

        qfbAll.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, MP4_VIDEO_MIME_TYPE);
        }

        // Verify after
        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, MP4_VIDEO_MIME_TYPE);
        }

        // Verify before
        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryWithMimeTypesFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, WEBM_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor5 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor6 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor4, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor5, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, cursor6, 1);

        // Verify all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(6);
        }

        qfbAll.setMimeTypes(new String[]{"image/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(4);

            assertAllMediaCursor(cr, new String[] {CLOUD_ID_2, CLOUD_ID_1, LOCAL_ID_2,
                    CLOUD_ID_3}, new long[] {DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS,
                        DATE_TAKEN_MS - 1}, new String[] {GIF_IMAGE_MIME_TYPE, PNG_IMAGE_MIME_TYPE,
                            JPEG_IMAGE_MIME_TYPE, PNG_IMAGE_MIME_TYPE});
        }

        qfbAll.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr, new String[] {LOCAL_ID_3, LOCAL_ID_1}, new long[]
                    {DATE_TAKEN_MS + 1, DATE_TAKEN_MS}, new String[] {MP4_VIDEO_MIME_TYPE,
                        WEBM_VIDEO_MIME_TYPE});
        }

        // Verify after
        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{"image/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(3);
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{PNG_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr, new String[] {CLOUD_ID_1, CLOUD_ID_3}, new long[]
                    {DATE_TAKEN_MS, DATE_TAKEN_MS - 1}, new String[] {PNG_IMAGE_MIME_TYPE,
                        PNG_IMAGE_MIME_TYPE});
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr, new String[] {LOCAL_ID_3, LOCAL_ID_1}, new long[]
                    {DATE_TAKEN_MS + 1, DATE_TAKEN_MS}, new String[] {MP4_VIDEO_MIME_TYPE,
                        WEBM_VIDEO_MIME_TYPE});
        }

        // Verify before
        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(5);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID_1, DATE_TAKEN_MS, WEBM_VIDEO_MIME_TYPE);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 2);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr, new String[] {LOCAL_ID_3, LOCAL_ID_1}, new long[]
                    {DATE_TAKEN_MS + 1, DATE_TAKEN_MS}, new String[] {MP4_VIDEO_MIME_TYPE,
                        WEBM_VIDEO_MIME_TYPE});
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{PNG_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr, new String[] {CLOUD_ID_1, CLOUD_ID_3}, new long[]
                    {DATE_TAKEN_MS, DATE_TAKEN_MS - 1}, new String[] {PNG_IMAGE_MIME_TYPE ,
                        PNG_IMAGE_MIME_TYPE});
        }
    }

    @Test
    public void testQueryWithMultipleMimeTypesFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, WEBM_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor5 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor6 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MPEG_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor7 = getMediaCursor(CLOUD_ID_4, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor8 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor4, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor5, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor6, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor7, 1);
        assertAddMediaOperation(LOCAL_PROVIDER, cursor8, 1);

        // Verify all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(8);
        }

        qfbAll.setMimeTypes(new String[]{"image/*", PNG_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(6);
        }

        qfbAll.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE, MPEG_VIDEO_MIME_TYPE,
                WEBM_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(3);

            assertAllMediaCursor(cr, new String[] {CLOUD_ID_3, CLOUD_ID_2, LOCAL_ID_1},
                    new long[] {DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS}, new String[] {
                            MPEG_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        // Verify after
        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1000);

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE,
                MPEG_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE, M4V_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(3);

            assertAllMediaCursor(cr, new String[] {CLOUD_ID_3, CLOUD_ID_2, LOCAL_ID_1},
                    new long[] {DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS}, new String[] {
                            MPEG_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertThat(cr.getCount()).isEqualTo(3);
        }

        // Verify before
        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(7);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"image/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID_4, DATE_TAKEN_MS - 1, PNG_IMAGE_MIME_TYPE);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 2);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{MP4_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertThat(cr.getCount()).isEqualTo(3);

            assertAllMediaCursor(cr, new String[] {LOCAL_ID_4, CLOUD_ID_2, LOCAL_ID_3},
                    new long[] {DATE_TAKEN_MS + 1, DATE_TAKEN_MS, DATE_TAKEN_MS}, new String[] {
                            MP4_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE});
        }
    }

    @Test
    public void testQueryWithSizeAndMimeTypesFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, WEBM_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cursor2, 1);

        // mime_type and size filter matches all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setMimeTypes(new String[]{"*/*"});
        qfbAll.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
        }

        // mime_type and size filter matches none
        qfbAll.setMimeTypes(new String[]{WEBM_VIDEO_MIME_TYPE});
        qfbAll.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testQueryMediaId() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        // Assert all projection columns
        final String[] allProjection = mProjectionHelper.getProjectionMap(
                PickerMediaColumns.class).keySet().toArray(new String[0]);
        try (Cursor cr = mFacade.queryMediaIdForApps(LOCAL_PROVIDER, LOCAL_ID,
                allProjection)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaStoreCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Assert one projection column
        final String[] oneProjection = new String[] { PickerMediaColumns.DATE_TAKEN };

        try (Cursor cr = mFacade.queryMediaIdForApps(CLOUD_PROVIDER, CLOUD_ID,
                oneProjection)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getLong(cr.getColumnIndex(PickerMediaColumns.DATE_TAKEN)))
                    .isEqualTo(DATE_TAKEN_MS);
        }

        // Assert invalid projection column
        final String invalidColumn = "testInvalidColumn";
        final String[] invalidProjection = new String[] {
                PickerMediaColumns.DATE_TAKEN,
                invalidColumn
        };

        try (Cursor cr = mFacade.queryMediaIdForApps(CLOUD_PROVIDER, CLOUD_ID,
                invalidProjection)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getLong(cr.getColumnIndex(invalidColumn)))
                    .isEqualTo(0);
            assertThat(cr.getLong(cr.getColumnIndex(PickerMediaColumns.DATE_TAKEN)))
                    .isEqualTo(DATE_TAKEN_MS);
        }
    }

    /**
     * Tests {@link PickerDbFacade#queryMediaForUi(PickerDbFacade.QueryFilter)}
     * to ensure columns not required for the UI are not present.
     */
    @Test
    public void testQueryMediaForUi() throws Exception {

        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {

            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));

            cr.moveToNext();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));
        }
    }

    /**
     * Tests {@link PickerDbFacade#queryAlbumMediaForUi(PickerDbFacade.QueryFilter, String)} to
     * ensure columns not required for the UI are not present.
     */
    @Test
    public void testQueryAlbumMediaForUi() throws Exception {

        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

        assertAddAlbumMediaOperation(LOCAL_PROVIDER, localCursor, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(CLOUD_PROVIDER, cloudCursor, 1, ALBUM_ID);

        PickerDbFacade.QueryFilterBuilder localQfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr =
                     mFacade.queryAlbumMediaForUi(
                             localQfb.setAlbumId(ALBUM_ID).build(), LOCAL_PROVIDER)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));
        }

        PickerDbFacade.QueryFilterBuilder cloudQfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr =
                     mFacade.queryAlbumMediaForUi(
                             cloudQfb.setAlbumId(ALBUM_ID).build(), CLOUD_PROVIDER)) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));
        }
    }

    @Test
    public void testSetCloudProvider() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, null, DATE_TAKEN_MS);

        assertAddMediaOperation(LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Clearing the cloud provider hides cloud media
        mFacade.setCloudProvider(null);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Setting the cloud provider unhides cloud media
        mFacade.setCloudProvider(CLOUD_PROVIDER);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testFavorites() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        qfb.setIsFavorite(true);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID + 1, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID + 1, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testGetFavoritesAlbumWithoutFilter() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }
    }

    @Test
    public void testGetFavoritesAlbumWithMimeTypesFilter() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }

        qfb.setMimeTypes(IMAGE_MIME_TYPES_QUERY);
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    CLOUD_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }

        qfb.setMimeTypes(VIDEO_MIME_TYPES_QUERY);
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }

        qfb.setMimeTypes(new String[]{"foo"});
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testFetchLocalOnly() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        // Item Info:
        // 2 items - local - one of them in favorite album
        // 2 items - cloud - one in favorite album, one in video album
        // Albums Info:
        // Videos     - Merged Album - 1 Video File (1 cloud)
        // Favorites  - Merged Album - 2 files (1 local + 1 cloud)

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        // Verify that we see all(local + cloud) items.
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        // Verify that we only see local items with isLocalOnly=true
        qfb.setIsLocalOnly(true);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(2);

            cr.moveToNext();
            assertThat(cr.getString(cr.getColumnIndex(MediaColumns.ID))).isEqualTo(LOCAL_ID + "2");
            cr.moveToNext();
            assertThat(cr.getString(cr.getColumnIndex(MediaColumns.ID))).isEqualTo(LOCAL_ID + "1");
        }

        // Verify that we see all available merged albums and their respective media count
        qfb.setIsLocalOnly(false);
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    CLOUD_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    CLOUD_ID + "2",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }

        qfb.setIsLocalOnly(true);
        // Verify that with isLocalOnly=true, we only see one album with only one local item.
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build())) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }
    }

    @Test
    public void testDataColumn() throws Exception {
        Cursor imageCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor videoCursor = getMediaCursor(LOCAL_ID + 1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, imageCursor, 1);
            assertWriteOperation(operation, videoCursor, 1);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + 1, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID, JPEG_IMAGE_MIME_TYPE);
        }
    }

    @Test
    public void testAddMediaFailure() throws Exception {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertThrows(Exception.class, () -> operation.execute(null /* cursor */));
        }
    }

    @Test
    public void testRemoveMediaFailure() throws Exception {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginRemoveMediaOperation(CLOUD_PROVIDER)) {
            assertThrows(Exception.class, () -> operation.execute(null /* cursor */));
        }
    }

    @Test
    public void testUpdateMediaSuccess() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            operation.execute(localCursor);
            operation.setSuccess();
        }

        try (PickerDbFacade.UpdateMediaOperation operation =
                     mFacade.beginUpdateMediaOperation(LOCAL_PROVIDER)) {
            ContentValues values = new ContentValues();
            values.put(PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION,
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP);
            assertThat(operation.execute(LOCAL_ID, values)).isTrue();
            operation.setSuccess();
        }

        try (Cursor cursor = queryMediaAll()) {
            assertThat(cursor.getCount()).isEqualTo(1);

            // Assert that STANDARD_MIME_TYPE_EXTENSION has been updated
            cursor.moveToFirst();
            assertThat(cursor.getInt(cursor.getColumnIndex(
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                    .isEqualTo(MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP);
        }
    }

    @Test
    public void testUpdateMediaFailure() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            operation.execute(localCursor);
            operation.setSuccess();
        }

        try (PickerDbFacade.UpdateMediaOperation operation =
                     mFacade.beginUpdateMediaOperation(LOCAL_PROVIDER)) {
            ContentValues values = new ContentValues();
            values.put(PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION,
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP);
            assertThat(operation.execute(CLOUD_ID, values)).isFalse();
            operation.setSuccess();
        }

        try (Cursor cursor = queryMediaAll()) {
            assertThat(cursor.getCount()).isEqualTo(1);

            // Assert that STANDARD_MIME_TYPE_EXTENSION is same as before
            cursor.moveToFirst();
            assertThat(cursor.getInt(cursor.getColumnIndex(
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                    .isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
        }
    }

    private Cursor queryMediaAll() {
        return mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    private Cursor queryAlbumMedia(String albumId, boolean isLocal) {
        final String authority = isLocal ? LOCAL_PROVIDER : CLOUD_PROVIDER;

        return mFacade.queryAlbumMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).setAlbumId(albumId).build(), authority);
    }

    private void assertAddMediaOperation(String authority, Cursor cursor, int writeCount) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(authority)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    private void assertAddAlbumMediaOperation(String authority, Cursor cursor, int writeCount,
            String albumId) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddAlbumMediaOperation(authority, albumId)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    private void assertRemoveMediaOperation(String authority, Cursor cursor, int writeCount) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginRemoveMediaOperation(authority)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    private void assertResetMediaOperation(String authority, Cursor cursor, int writeCount) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginResetMediaOperation(authority)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    private void assertResetAlbumMediaOperation(String authority, int writeCount,
            String albumId) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginResetAlbumMediaOperation(authority, albumId)) {
            assertWriteOperation(operation, null, writeCount);
            operation.setSuccess();
        }
    }

    private static void assertWriteOperation(PickerDbFacade.DbWriteOperation operation,
            Cursor cursor, int expectedWriteCount) {
        final int writeCount = operation.execute(cursor);
        assertThat(writeCount).isEqualTo(expectedWriteCount);
    }

    // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
    private static Cursor getDeletedMediaCursor(String id) {
        MatrixCursor c =
                new MatrixCursor(new String[] {"id"});
        c.addRow(new String[] {id});
        return c;
    }

    private static Cursor getMediaCursor(String id, long dateTakenMs, long generationModified,
            String mediaStoreUri, long sizeBytes, String mimeType, int standardMimeTypeExtension,
            boolean isFavorite) {
        String[] projectionKey = new String[] {
                MediaColumns.ID,
                MediaColumns.MEDIA_STORE_URI,
                MediaColumns.DATE_TAKEN_MILLIS,
                MediaColumns.SYNC_GENERATION,
                MediaColumns.SIZE_BYTES,
                MediaColumns.MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
                MediaColumns.DURATION_MILLIS,
                MediaColumns.IS_FAVORITE,
                MediaColumns.HEIGHT,
                MediaColumns.WIDTH,
                MediaColumns.ORIENTATION,
        };

        String[] projectionValue = new String[] {
                id,
                mediaStoreUri,
                String.valueOf(dateTakenMs),
                String.valueOf(generationModified),
                String.valueOf(sizeBytes),
                mimeType,
                String.valueOf(standardMimeTypeExtension),
                String.valueOf(DURATION_MS),
                String.valueOf(isFavorite ? 1 : 0),
                String.valueOf(HEIGHT),
                String.valueOf(WIDTH),
                String.valueOf(ORIENTATION),
        };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    private static Cursor getAlbumMediaCursor(
            String id,
            long dateTakenMs,
            long generationModified,
            String mediaStoreUri,
            long sizeBytes,
            String mimeType,
            int standardMimeTypeExtension) {
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

        String[] projectionValue =
                new String[] {
                        id,
                        mediaStoreUri,
                        String.valueOf(dateTakenMs),
                        String.valueOf(generationModified),
                        String.valueOf(sizeBytes),
                        mimeType,
                        String.valueOf(standardMimeTypeExtension),
                        String.valueOf(DURATION_MS),
                };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    private static Cursor getLocalMediaCursor(String localId, long dateTakenMs) {
        return getMediaCursor(localId, dateTakenMs, GENERATION_MODIFIED, toMediaStoreUri(localId),
                SIZE_BYTES, MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION,
                /* isFavorite */ false);
    }

    private static Cursor getAlbumMediaCursor(String localId, String cloudId, long dateTakenMs) {
        return getAlbumMediaCursor(cloudId, dateTakenMs, GENERATION_MODIFIED,
                toMediaStoreUri(localId), SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION);
    }

    private static Cursor getCloudMediaCursor(String cloudId, String localId,
            long dateTakenMs) {
        return getMediaCursor(cloudId, dateTakenMs, GENERATION_MODIFIED, toMediaStoreUri(localId),
                SIZE_BYTES, MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION,
                /* isFavorite */ false);
    }

    private static String toMediaStoreUri(String localId) {
        if (localId == null) {
            return null;
        }
        return "content://media/external/file/" + localId;
    }

    private static String getDisplayName(String mediaId, String mimeType) {
        return mediaId + getExtensionFromMimeType(mimeType);
    }

    private static String getData(String authority, String displayName) {
        return "/sdcard/.transforms/synthetic/picker/0/" + authority + "/media/"
                + displayName;
    }

    private static void assertCloudAlbumCursor(Cursor cursor, String albumId, String displayName,
            String mediaCoverId, long dateTakenMs, long mediaCount) {
        assertThat(cursor.getString(cursor.getColumnIndex(AlbumColumns.ID)))
                .isEqualTo(albumId);
        assertThat(cursor.getString(cursor.getColumnIndex(AlbumColumns.DISPLAY_NAME)))
                .isEqualTo(displayName);
        assertThat(cursor.getString(cursor.getColumnIndex(AlbumColumns.MEDIA_COVER_ID)))
                .isEqualTo(mediaCoverId);
        assertThat(cursor.getLong(cursor.getColumnIndex(AlbumColumns.DATE_TAKEN_MILLIS)))
                .isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(cursor.getColumnIndex(AlbumColumns.MEDIA_COUNT)))
                .isEqualTo(mediaCount);
    }

    private static void assertCloudMediaCursor(Cursor cursor, String id, String mimeType) {
        final String displayName = getDisplayName(id, mimeType);
        final String localData = getData(LOCAL_PROVIDER, displayName);
        final String cloudData = getData(CLOUD_PROVIDER, displayName);

        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.ID)))
                .isEqualTo(id);
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.AUTHORITY)))
                .isEqualTo(id.startsWith(LOCAL_ID) ? LOCAL_PROVIDER : CLOUD_PROVIDER);
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.DATA)))
                .isEqualTo(id.startsWith(LOCAL_ID) ? localData : cloudData);
    }

    private static void assertCloudMediaCursor(Cursor cursor, String id, long dateTakenMs) {
        assertCloudMediaCursor(cursor, id, MP4_VIDEO_MIME_TYPE);

        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.MIME_TYPE)))
                .isEqualTo(MP4_VIDEO_MIME_TYPE);
        assertThat(cursor.getInt(cursor.getColumnIndex(MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                .isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.DATE_TAKEN_MILLIS)))
                .isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.SYNC_GENERATION)))
                .isEqualTo(GENERATION_MODIFIED);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.SIZE_BYTES)))
                .isEqualTo(SIZE_BYTES);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.DURATION_MILLIS)))
                .isEqualTo(DURATION_MS);
    }

    private static void assertCloudMediaCursor(
            Cursor cursor, String id, long dateTakenMs, String mimeType) {
        assertCloudMediaCursor(cursor, id, mimeType);

        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.MIME_TYPE)))
                .isEqualTo(mimeType);
        assertThat(cursor.getInt(cursor.getColumnIndex(MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                .isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.DATE_TAKEN_MILLIS)))
                .isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.SYNC_GENERATION)))
                .isEqualTo(GENERATION_MODIFIED);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.SIZE_BYTES)))
                .isEqualTo(SIZE_BYTES);
        assertThat(cursor.getLong(cursor.getColumnIndex(MediaColumns.DURATION_MILLIS)))
                .isEqualTo(DURATION_MS);
    }

    private static void assertAllMediaCursor(
            Cursor cursor, String[] mediaIds, long[] dateTakenMs, String[] mimeTypes) {
        int mediaCount = cursor.getCount();
        for (int mediaNo = 0; mediaNo < mediaCount; mediaNo = mediaNo + 1) {
            if (mediaNo == 0) cursor.moveToFirst();
            else cursor.moveToNext();
            assertCloudMediaCursor(cursor, mediaIds[mediaNo], dateTakenMs[mediaNo],
                    mimeTypes[mediaNo]);
        }
    }

    private static void assertMediaStoreCursor(Cursor cursor, String id, long dateTakenMs) {
        final String displayName = getDisplayName(id, MP4_VIDEO_MIME_TYPE);
        final String localData = getData(LOCAL_PROVIDER, displayName);
        final String cloudData = getData(CLOUD_PROVIDER, displayName);

        assertThat(cursor.getString(cursor.getColumnIndex(PickerMediaColumns.DISPLAY_NAME)))
                .isEqualTo(displayName);
        assertThat(cursor.getString(cursor.getColumnIndex(PickerMediaColumns.DATA)))
                .isEqualTo(id.startsWith(LOCAL_ID) ? localData : cloudData);
        assertThat(cursor.getString(cursor.getColumnIndex(PickerMediaColumns.MIME_TYPE)))
                .isEqualTo(MP4_VIDEO_MIME_TYPE);
        assertThat(cursor.getLong(cursor.getColumnIndex(PickerMediaColumns.DATE_TAKEN)))
                .isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(cursor.getColumnIndex(PickerMediaColumns.SIZE)))
                .isEqualTo(SIZE_BYTES);
        assertThat(cursor.getLong(cursor.getColumnIndex(PickerMediaColumns.DURATION_MILLIS)))
                .isEqualTo(DURATION_MS);
        assertThat(cursor.getInt(cursor.getColumnIndex(PickerMediaColumns.HEIGHT)))
                .isEqualTo(HEIGHT);
        assertThat(cursor.getInt(cursor.getColumnIndex(PickerMediaColumns.WIDTH)))
                .isEqualTo(WIDTH);
        assertThat(cursor.getInt(cursor.getColumnIndex(PickerMediaColumns.ORIENTATION)))
                .isEqualTo(ORIENTATION);
    }
}