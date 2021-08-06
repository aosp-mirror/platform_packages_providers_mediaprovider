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

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DURATION_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_IS_VISIBLE;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_MIME_TYPE;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_SIZE_BYTES;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import java.io.File;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerDbFacadeTest {
    private static final long SIZE_BYTES = 7000;
    private static final long DATE_TAKEN_MS = 1623852851911L;
    private static final long DURATION_MS = 5;
    private static final String LOCAL_ID = "50";
    private static final String MEDIA_STORE_URI = "content://media/external/file/" + LOCAL_ID;
    private static final String CLOUD_ID = "asdfghjkl;";
    private static final String MIME_TYPE = "video/mp4";

    private static final String LOCAL_PROVIDER = "com.local.provider";
    private static final String CLOUD_PROVIDER = "com.cloud.provider";

    private PickerDbFacade mFacade;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        File dbPath = context.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        mFacade = new PickerDbFacade(context, LOCAL_PROVIDER);
        mFacade.setCloudProvider(CLOUD_PROVIDER);
    }

    @Test
    public void testAddLocalOnly() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

        assertThat(mFacade.addMedia(cursor1, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row
        assertThat(mFacade.addMedia(cursor2, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudPlusLocal() throws Exception {
        Cursor cursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(cursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudOnly() throws Exception {
        Cursor cursor1 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 2,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

        assertThat(mFacade.addMedia(cursor1, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, /* localId */ null, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row
        assertThat(mFacade.addMedia(cursor2, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, /* localId */ null, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddLocalAndCloud_Dedupe() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudAndLocal_Dedupe() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 1);
        }
    }

    @Test
    public void testRemoveLocal() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(LOCAL_ID), 0, LOCAL_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveLocal_promote() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            assertThat(cr.getString(1)).isNull();
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(LOCAL_ID), 0, LOCAL_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
        }
    }

    @Test
    public void testRemoveCloud() throws Exception {
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(CLOUD_ID), 0, CLOUD_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveCloud_promote() throws Exception {
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(cloudCursor1, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor2, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            assertThat(cr.getString(1)).isEqualTo(CLOUD_ID + 1);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(CLOUD_ID + "1"), 0, CLOUD_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            assertThat(cr.getString(1)).isEqualTo(CLOUD_ID + "2");
        }
    }

    @Test
    public void testRemoveHidden() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            assertThat(cr.getString(1)).isNull();
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(CLOUD_ID), 0, CLOUD_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            assertThat(cr.getString(1)).isNull();
        }
    }


    @Test
    public void testLocalUpdate() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

        assertThat(mFacade.addMedia(localCursor1, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(localCursor2, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 2);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(LOCAL_ID), 0, LOCAL_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCloudUpdate_withoutLocal() throws Exception {
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 2);

        assertThat(mFacade.addMedia(cloudCursor1, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor2, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(CLOUD_ID), 0, CLOUD_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCloudUpdate_withLocal() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 2);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor1, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor2, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(LOCAL_ID), 0, LOCAL_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);
        }

        assertThat(mFacade.removeMedia(getDeletedMediaCursor(CLOUD_ID), 0, CLOUD_PROVIDER))
                .isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testResetLocal() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        // Add two cloud_ids mapping to the same local_id to verify that
        // only one gets promoted
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor1, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor2, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertThat(mFacade.resetMedia(LOCAL_PROVIDER)).isEqualTo(1);

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
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertThat(mFacade.resetMedia(CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithDateTakenFilter() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);
        }

        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS - 1,
                        /* id */ 5, /* limit */ 5, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }

        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS + 1,
                        /* id */ 5, /* limit */ 5, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testQueryWithIdFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
        Cursor cursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

        assertThat(mFacade.addMedia(cursor1, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cursor2, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS,
                        /* id */ 2, /* limit */ 5, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "1");
        }

        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS,
                        /* id */ 1, /* limit */ 5, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "2");
        }
    }

    @Test
    public void testQueryWithLimit() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
        Cursor cursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
        Cursor cursor3 = getMediaCursor(LOCAL_ID + "3", DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

        assertThat(mFacade.addMedia(cursor1, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cursor2, CLOUD_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cursor3, LOCAL_PROVIDER)).isEqualTo(1);

        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                        /* limit */ 1, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "3");
        }

        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                        /* limit */ 1, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "3");
        }

        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "3");
        }
    }

    @Test
    public void testQueryWithSizeFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MIME_TYPE);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MIME_TYPE);

        assertThat(mFacade.addMedia(cursor1, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cursor2, CLOUD_PROVIDER)).isEqualTo(1);

        // Verify all
        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 10)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }
        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 1)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
        }

        // Verify after
        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                        /* limit */ 1000, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 10)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }
        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                        /* limit */ 1000, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 1)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
        }

        // Verify before
        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                        /* limit */ 1000, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 10)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }
        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                        /* limit */ 1000, /* mimeTypeFilter */ null,
                        /* sizeBytesMax */ 1)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
        }
    }

    @Test
    public void testQueryWithMimeTypeFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, "video/webm");
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, "video/mp4");

        assertThat(mFacade.addMedia(cursor1, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cursor2, CLOUD_PROVIDER)).isEqualTo(1);

        // Verify all
        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1000, "*/*",
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }
        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1000, "video/mp4",
                        /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
        }

        // Verify after
        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                        /* limit */ 1000, "video/*", /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }
        try (Cursor cr = mFacade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                        /* limit */ 1000, "video/webm", /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
        }

        // Verify before
        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                        /* limit */ 1000, "video/*", /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }
        try (Cursor cr = mFacade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                        /* limit */ 1000, "video/mp4", /* sizeBytesMax */ 0)) {
            assertThat(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
        }
    }

    @Test
    public void testQueryWithSizeAndMimeTypeFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, "video/webm");
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, "video/mp4");

        assertThat(mFacade.addMedia(cursor1, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cursor2, CLOUD_PROVIDER)).isEqualTo(1);

        // mime_type and size filter matches all
        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1000, "*/*",
                        /* sizeBytesMax */ 10)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }

        // mime_type and size filter matches none
        try (Cursor cr = mFacade.queryMediaAll(/* limit */ 1000, "video/webm",
                        /* sizeBytesMax */ 1)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testSetCloudProvider() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

        assertThat(mFacade.addMedia(localCursor, LOCAL_PROVIDER)).isEqualTo(1);
        assertThat(mFacade.addMedia(cloudCursor, CLOUD_PROVIDER)).isEqualTo(1);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(2);

            Bundle bundle = cr.getExtras();
            String localProvider = bundle.getString(PickerDbFacade.KEY_LOCAL_PROVIDER);
            String cloudProvider = bundle.getString(PickerDbFacade.KEY_CLOUD_PROVIDER);

            assertThat(localProvider).isEqualTo(LOCAL_PROVIDER);
            assertThat(cloudProvider).isEqualTo(CLOUD_PROVIDER);

            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Clearing the cloud provider hides cloud media
        mFacade.setCloudProvider(null);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(1);

            Bundle bundle = cr.getExtras();
            String localProvider = bundle.getString(PickerDbFacade.KEY_LOCAL_PROVIDER);
            String cloudProvider = bundle.getString(PickerDbFacade.KEY_CLOUD_PROVIDER);

            assertThat(localProvider).isEqualTo(LOCAL_PROVIDER);
            assertThat(cloudProvider).isNull();

            cr.moveToFirst();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Setting the cloud provider unhides cloud media
        mFacade.setCloudProvider(CLOUD_PROVIDER);

        try (Cursor cr = queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertCursor(cr, CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    private Cursor queryMediaAll() {
        return mFacade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                /* sizeBytesMax */ 0);
    }

    // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
    private static Cursor getDeletedMediaCursor(String id) {
        MatrixCursor c =
                new MatrixCursor(new String[] {"id"});
        c.addRow(new String[] {id});
        return c;
    }

    // TODO(b/190713331): Use CloudMediaProviderContract#MediaColumns
    private static Cursor getMediaCursor(String id, long dateTakenMs, String mediaStoreUri,
            long sizeBytes, String mimeType) {
        String[] projectionKey = new String[] {
            "id",
            "media_store_uri",
            "date_taken_ms",
            "size_bytes",
            "mime_type",
            "duration_ms"
        };

        String[] projectionValue = new String[] {
            id,
            mediaStoreUri,
            String.valueOf(dateTakenMs),
            String.valueOf(sizeBytes),
            mimeType,
            String.valueOf(DURATION_MS)
        };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    private static Cursor getMediaCursor(String id, long dateTakenMs) {
        return getMediaCursor(id, dateTakenMs, MEDIA_STORE_URI, SIZE_BYTES, MIME_TYPE);
    }

    private static void assertCursor(Cursor cursor, String cloudId, String localId,
            long dateTakenMs) {
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_LOCAL_ID))).isEqualTo(localId);
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_CLOUD_ID))).isEqualTo(cloudId);
        assertThat(cursor.getLong(cursor.getColumnIndex(KEY_DATE_TAKEN_MS))).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(cursor.getColumnIndex(KEY_SIZE_BYTES))).isEqualTo(SIZE_BYTES);
        assertThat(cursor.getLong(cursor.getColumnIndex(KEY_DURATION_MS))).isEqualTo(DURATION_MS);
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_MIME_TYPE))).isEqualTo(MIME_TYPE);
    }
}
