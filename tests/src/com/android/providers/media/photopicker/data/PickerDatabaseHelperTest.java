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

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerDatabaseHelperTest {
    private static final String TAG = "PickerDatabaseHelperTest";

    private static final String SQLITE_MASTER_ORDER_BY = "type,name,tbl_name";
    private static final String TEST_PICKER_DB = "test_picker";
    static final String MEDIA_TABLE = "media";

    private static final String KEY_LOCAL_ID = "local_id";
    private static final String KEY_CLOUD_ID = "cloud_id";
    private static final String KEY_IS_LOCAL_VERIFIED = "is_local_verified";
    private static final String KEY_LOCAL_DEDUPE_KEY = "local_dedupe_key";
    private static final String KEY_DATE_TAKEN_MS = "date_taken_ms";
    private static final String KEY_SIZE_BYTES = "size_bytes";
    private static final String KEY_DURATION_MS = "duration_ms";
    private static final String KEY_MIME_TYPE = "mime_type";

    private static final long LOCAL_ID = 50;
    // date_modified_ms+size
    private static final String LOCAL_DEDUPE_KEY = "1623852851911+7000";
    private static final long SIZE_BYTES = 7000;
    private static final long DATE_TAKEN_MS = 1623852851911L;
    private static final String CLOUD_ID = "asdfghjkl;";
    private static final String MIME_TYPE = "video/mp4";
    private static final long DURATION_MS = 0;

    private static Context sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
    }

    @Test
    public void testPickerValidInput_CloudAndLocal() throws Exception {
        String[] projection = new String[] {
            KEY_LOCAL_ID,
            KEY_CLOUD_ID,
            KEY_IS_LOCAL_VERIFIED,
            KEY_LOCAL_DEDUPE_KEY,
            KEY_DATE_TAKEN_MS,
            KEY_SIZE_BYTES,
            KEY_DURATION_MS,
            KEY_MIME_TYPE
        };

        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // All fields specified
            ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_IS_LOCAL_VERIFIED, 1);
            values.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            try (Cursor cr = db.query(MEDIA_TABLE, projection, null, null, null, null, null)) {
                assertThat(cr.getCount()).isEqualTo(1);
                while (cr.moveToNext()) {
                    assertThat(cr.getLong(0)).isEqualTo(LOCAL_ID);
                    assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
                    assertThat(cr.getInt(2)).isEqualTo(1);
                    assertThat(cr.getString(3)).isEqualTo(LOCAL_DEDUPE_KEY);
                    assertThat(cr.getLong(4)).isEqualTo(DATE_TAKEN_MS);
                    assertThat(cr.getLong(5)).isEqualTo(SIZE_BYTES);
                    assertThat(cr.getLong(6)).isEqualTo(DURATION_MS);
                    assertThat(cr.getString(7)).isEqualTo(MIME_TYPE);
                }
            }

            // Remove cloud_id to avoid hitting cloud_id unique constraint
            values.remove(KEY_CLOUD_ID);

            // Same local_dedupe_key with different local_id can be inserted
            ContentValues valuesLocalId = new ContentValues(values);
            valuesLocalId.put(KEY_LOCAL_ID, LOCAL_ID + 1);
            assertThat(db.insert(MEDIA_TABLE, null, valuesLocalId)).isNotEqualTo(-1);

            // Same local_id with different local_dedupe_key can be inserted
            ContentValues valuesLocalDateModifiedMs = new ContentValues(values);
            valuesLocalDateModifiedMs.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY + "1");
            assertThat(db.insert(MEDIA_TABLE, null, valuesLocalDateModifiedMs)).isNotEqualTo(-1);
        }
    }

    @Test
    public void testPickerValidInput_CloudOrLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Cloud only
            ContentValues values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Local only
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_LOCAL_VERIFIED, 1);
            values.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_Size() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // size_bytes=NULL
            ContentValues values = getBasicContentValues();
            values.remove(KEY_SIZE_BYTES);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // size_bytes=0
            values = getBasicContentValues();
            values.put(KEY_SIZE_BYTES, 0);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_MimeType() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // mime_type=NULL
            ContentValues values = getBasicContentValues();
            values.remove(KEY_MIME_TYPE);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_DateTaken() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // date_taken_ms=NULL
            ContentValues values = getBasicContentValues();
            values.remove(KEY_DATE_TAKEN_MS);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // date_taken_ms=-1
            values = getBasicContentValues();
            values.put(KEY_DATE_TAKEN_MS, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_Duration() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // duration=-1
            ContentValues values = getBasicContentValues();
            values.put(KEY_DURATION_MS, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_Local() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // is_local_verified=1 && local_id=NULL
            ContentValues values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_IS_LOCAL_VERIFIED, 1);
            values.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // is_local_verified=1 && local_dedupe_key=NULL
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_IS_LOCAL_VERIFIED, 1);
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_Cloud() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // is_local_verified=0 && cloud_id=NULL
            ContentValues values = getBasicContentValues();
            values.put(KEY_IS_LOCAL_VERIFIED, 0);
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // is_local_verified=NULL && cloud_id=NULL
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_UniqueConstraintLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            final ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_LOCAL_VERIFIED, 1);
            values.put(KEY_LOCAL_DEDUPE_KEY, LOCAL_DEDUPE_KEY);

            // Insert <local_id, local_dedupe_key>: success
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Insert <local_id, local_dedupe_key, size> again: failure
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testPickerInvalidInput_UniqueConstraintCloud() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            final ContentValues values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);

            // Insert <cloud_id>: success
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Insert <cloud_id> again: failure
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    private static class PickerDatabaseHelperT extends PickerDatabaseHelper {
        public PickerDatabaseHelperT(Context context) {
            super(context, TEST_PICKER_DB, 1);
        }
    }

    private static ContentValues getBasicContentValues() {
        ContentValues values = new ContentValues();
        values.put(KEY_DATE_TAKEN_MS, DATE_TAKEN_MS);
        values.put(KEY_DURATION_MS, DURATION_MS);
        values.put(KEY_MIME_TYPE, MIME_TYPE);
        values.put(KEY_SIZE_BYTES, SIZE_BYTES);

        return values;
    }
}
