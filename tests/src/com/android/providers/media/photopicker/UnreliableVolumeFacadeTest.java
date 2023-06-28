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

package com.android.providers.media.photopicker;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import static org.junit.Assert.*;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UnreliableVolumeFacadeTest {
    private static final int BATCH_SIZE = 100;
    private static final long SIZE_BYTES = 7000;
    private static final String DISPLAY_NAME = "random test image";
    private static final long DATE_MODIFIED = 1623852851911L;
    private static final String MIME_TYPE = "image/jpg";
    private static final String DATA_PREFIX = "mnt/media_rw/A678954/";

    private static UnreliableVolumeFacade mFacade;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mFacade = new UnreliableVolumeFacade(context);
    }

    @After
    public void tearDown() {
        mFacade.deleteMedia();
    }

    @Test
    public void queryAllMedia() {
        int counter = mFacade.insertMedia(generateContentDb());
        try (Cursor cr = mFacade.queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(counter);
            cr.moveToFirst();
            assertThat(cr.getString(1)).isEqualTo(DATA_PREFIX + "1");
            cr.moveToNext();
            assertThat(cr.getString(1)).isEqualTo(DATA_PREFIX + "2");
        }
    }

    @Test
    public void testUniqueConstraint() {
        List<ContentValues> values = generateContentDb();
        int initialCounter = mFacade.insertMedia(values);
        int sameInsertionAttempt = mFacade.insertMedia(values);
        assertEquals(100, initialCounter);
        assertEquals(0, sameInsertionAttempt);
        try (Cursor cr = mFacade.queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(initialCounter);
        }
    }

    @Test
    public void deleteAllMedia() {
        mFacade.deleteMedia();
        try (Cursor cr = mFacade.queryMediaAll()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void queryMediaId() {
        List<ContentValues> values = generateContentDb();
        int counter = mFacade.insertMedia(values);
        String uriString = "content://media/external/file/10";
        Uri uri = Uri.parse(uriString);
        try (Cursor cr = mFacade.queryMediaId(uri)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            String id = "10";
            assertThat(cr.getString(0)).isEqualTo(id);
        }
    }

    private static ContentValues generateAndGetContentValues(int index) {
        ContentValues values = new ContentValues();
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.DATE_MODIFIED, DATE_MODIFIED);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.SIZE_BYTES, SIZE_BYTES);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.DISPLAY_NAME, DISPLAY_NAME);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns._DATA, DATA_PREFIX + index);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.MIME_TYPE, MIME_TYPE);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns._ID, index);

        return values;
    }

    public List<ContentValues> generateContentDb() {
        List<ContentValues> list = new ArrayList<>();
        for (int i = 1; i <= BATCH_SIZE; i++) {
            list.add(generateAndGetContentValues(i));
        }
        return list;
    }
}
