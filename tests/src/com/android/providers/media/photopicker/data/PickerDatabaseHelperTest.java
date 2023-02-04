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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.CloudMediaProviderContract;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.IsolatedContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerDatabaseHelperTest {
    private static final String TAG = "PickerDatabaseHelperTest";

    private static final String TEST_PICKER_DB = "test_picker";
    static final String MEDIA_TABLE = "media";
    static final String ALBUM_MEDIA_TABLE = "album_media";

    private static final String KEY_LOCAL_ID = "local_id";
    private static final String KEY_CLOUD_ID = "cloud_id";
    private static final String KEY_IS_VISIBLE = "is_visible";
    private static final String KEY_ALBUM_ID = "album_id";
    private static final String KEY_DATE_TAKEN_MS = "date_taken_ms";
    private static final String KEY_SYNC_GENERATION = "sync_generation";
    private static final String KEY_SIZE_BYTES = "size_bytes";
    private static final String KEY_DURATION_MS = "duration_ms";
    private static final String KEY_MIME_TYPE = "mime_type";
    private static final String KEY_STANDARD_MIME_TYPE_EXTENSION = "standard_mime_type_extension";

    private static final long LOCAL_ID = 50;
    private static final long SIZE_BYTES = 7000;
    private static final long DATE_TAKEN_MS = 1623852851911L;
    private static final long GENERATION_MODIFIED = 1L;
    private static final String CLOUD_ID = "asdfghjkl;";
    private static final String ALBUM_ID = "testAlbum;";
    private static final String MIME_TYPE = "video/mp4";
    private static final int STANDARD_MIME_TYPE_EXTENSION =
            CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF;
    private static final long DURATION_MS = 0;

    private static Context sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
    }

    @Test
    public void testMediaColumns() throws Exception {
        String[] projection = new String[] {
            KEY_LOCAL_ID,
            KEY_CLOUD_ID,
            KEY_IS_VISIBLE,
            KEY_DATE_TAKEN_MS,
            KEY_SYNC_GENERATION,
            KEY_SIZE_BYTES,
            KEY_DURATION_MS,
            KEY_MIME_TYPE,
            KEY_STANDARD_MIME_TYPE_EXTENSION
        };

        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // All fields specified
            ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            try (Cursor cr = db.query(MEDIA_TABLE, projection, null, null, null, null, null)) {
                assertThat(cr.getCount()).isEqualTo(1);
                while (cr.moveToNext()) {
                    assertThat(cr.getLong(0)).isEqualTo(LOCAL_ID);
                    assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
                    assertThat(cr.getInt(2)).isEqualTo(1);
                    assertThat(cr.getLong(3)).isEqualTo(DATE_TAKEN_MS);
                    assertThat(cr.getLong(4)).isEqualTo(GENERATION_MODIFIED);
                    assertThat(cr.getLong(5)).isEqualTo(SIZE_BYTES);
                    assertThat(cr.getLong(6)).isEqualTo(DURATION_MS);
                    assertThat(cr.getString(7)).isEqualTo(MIME_TYPE);
                    assertThat(cr.getInt(8)).isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
                }
            }
        }
    }


    @Test
    public void testAlbumMediaColumns() throws Exception {
        String[] projection = new String[] {
                KEY_LOCAL_ID,
                KEY_CLOUD_ID,
                KEY_ALBUM_ID,
                KEY_DATE_TAKEN_MS,
                KEY_SYNC_GENERATION,
                KEY_SIZE_BYTES,
                KEY_DURATION_MS,
                KEY_MIME_TYPE,
                KEY_STANDARD_MIME_TYPE_EXTENSION
        };

        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // All fields specified
            ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_ALBUM_ID, ALBUM_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            try (Cursor cr = db.query(ALBUM_MEDIA_TABLE, projection, null, null, null, null,
                    null)) {
                assertThat(cr.getCount()).isEqualTo(1);
                while (cr.moveToNext()) {
                    assertThat(cr.getLong(0)).isEqualTo(LOCAL_ID);
                    assertThat(cr.getString(1)).isEqualTo(null);
                    assertThat(cr.getString(2)).isEqualTo(ALBUM_ID);
                    assertThat(cr.getLong(3)).isEqualTo(DATE_TAKEN_MS);
                    assertThat(cr.getLong(4)).isEqualTo(GENERATION_MODIFIED);
                    assertThat(cr.getLong(5)).isEqualTo(SIZE_BYTES);
                    assertThat(cr.getLong(6)).isEqualTo(DURATION_MS);
                    assertThat(cr.getString(7)).isEqualTo(MIME_TYPE);
                    assertThat(cr.getInt(8)).isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
                }
            }
        }
    }

    @Test
    public void testCheck_cloudOrLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Visible but no cloud or local specified
            ContentValues values = getBasicContentValues();
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // Hidden but no cloud or local specified
            values = getBasicContentValues();
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testUniqueConstraint_local() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Hidden local only
            ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Another hidden local only
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Visible local only
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Another visible local only
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testUniqueConstraintAlbumMedia() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Local Album Media
            ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_ALBUM_ID, ALBUM_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Another local for Album Media
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_ALBUM_ID, ALBUM_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);

            // Cloud for Album Media
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_ALBUM_ID, ALBUM_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Another Cloud for Album Media
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_ALBUM_ID, ALBUM_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testUniqueConstraint_cloud() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Hidden cloud only
            ContentValues values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Another hidden cloud only
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // Visible cloud only
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testUniqueConstraint_localAndCloudPlusLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Visible local only
            ContentValues values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);

            // Visible Cloud+Local (same local_id)
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID + "1");
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_VISIBLE, 1);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // Hidden Cloud+Local (same local_id)
            values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID + "1");
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.putNull(KEY_IS_VISIBLE);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isNotEqualTo(-1);
        }
    }

    @Test
    public void testCheck_IsVisible() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // is_visible < 1
            ContentValues values = getBasicContentValues();
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            values.put(KEY_IS_VISIBLE, 0);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // is_visible > 1
            values = getBasicContentValues();
            values.put(KEY_LOCAL_ID, LOCAL_ID);
            values.put(KEY_IS_VISIBLE, 2);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testCheck_Size() throws Exception {
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

            // size_bytes=NULL for Album Media Table
            values = getBasicContentValues();
            values.remove(KEY_SIZE_BYTES);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);

            // size_bytes=0 for Album Media Table
            values = getBasicContentValues();
            values.put(KEY_SIZE_BYTES, 0);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testCheck_MimeType() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // mime_type=NULL
            ContentValues values = getBasicContentValues();
            values.remove(KEY_MIME_TYPE);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // mime_type=NULL for Album Media
            values = getBasicContentValues();
            values.remove(KEY_MIME_TYPE);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testCheck_DateTaken() throws Exception {
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

            // date_taken_ms=NULL for Album Media
            values = getBasicContentValues();
            values.remove(KEY_DATE_TAKEN_MS);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);

            // date_taken_ms=-1 for Album Media
            values = getBasicContentValues();
            values.put(KEY_DATE_TAKEN_MS, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testCheck_GenerationModified() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // generation_modified=NULL
            ContentValues values = getBasicContentValues();
            values.remove(KEY_SYNC_GENERATION);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // generation_modified=-1
            values = getBasicContentValues();
            values.put(KEY_SYNC_GENERATION, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // generation_modified=NULL for Album Media
            values = getBasicContentValues();
            values.remove(KEY_SYNC_GENERATION);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);

            // generation_modified=-1 for Album Media
            values = getBasicContentValues();
            values.put(KEY_SYNC_GENERATION, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);
        }
    }

    @Test
    public void testCheck_Duration() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelperT(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // duration=-1
            ContentValues values = getBasicContentValues();
            values.put(KEY_DURATION_MS, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(MEDIA_TABLE, null, values)).isEqualTo(-1);

            // duration=-1
            values = getBasicContentValues();
            values.put(KEY_DURATION_MS, -1);
            values.put(KEY_CLOUD_ID, CLOUD_ID);
            assertThat(db.insert(ALBUM_MEDIA_TABLE, null, values)).isEqualTo(-1);
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
        values.put(KEY_SYNC_GENERATION, GENERATION_MODIFIED);
        values.put(KEY_DURATION_MS, DURATION_MS);
        values.put(KEY_MIME_TYPE, MIME_TYPE);
        values.put(KEY_STANDARD_MIME_TYPE_EXTENSION, STANDARD_MIME_TYPE_EXTENSION);
        values.put(KEY_SIZE_BYTES, SIZE_BYTES);

        return values;
    }
}
