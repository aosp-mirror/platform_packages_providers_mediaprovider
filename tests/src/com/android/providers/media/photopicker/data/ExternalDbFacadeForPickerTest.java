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

import static com.android.providers.media.photopicker.data.ExternalDbFacadeForPicker.TABLE_FILES;
import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExternalDbFacadeForPickerTest {
    private static final String TAG = "ExternalDbFacadeForPickerTest";

    private static final long OLD_ID1 = 1;
    private static final long OLD_ID2 = 2;

    private static final long DATE_TAKEN_MS1 = 1624886050566L;
    private static final long DATE_TAKEN_MS2 = 1624886050567L;
    private static final long GENERATION_MODIFIED1 = 1;
    private static final long GENERATION_MODIFIED2 = 2;
    private static final long SIZE = 8000;
    private static final String MIME_TYPE = "video/mp4";
    private static final long DURATION_MS = 5;

    private static Context sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
    }

    @Test
    public void testDeletedMedia_addAndRemove() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            assertThat(facade.addDeletedMedia(OLD_ID1)).isTrue();
            assertThat(facade.addDeletedMedia(OLD_ID2)).isTrue();

            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                ArrayList<Long> ids = new ArrayList<>();
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0));
                }

                assertThat(ids).contains(OLD_ID1);
                assertThat(ids).contains(OLD_ID2);
            }

            // Filter by generation should only return OLD_ID2
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertThat(cursor.getLong(0)).isEqualTo(OLD_ID2);
            }

            // Adding ids again should succeed but bump generation_modified of OLD_ID1 and OLD_ID2
            assertThat(facade.addDeletedMedia(OLD_ID1)).isTrue();
            assertThat(facade.addDeletedMedia(OLD_ID2)).isTrue();

            // Filter by generation again, now returns both ids since their generation_modified was
            // bumped
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertThat(cursor.getCount()).isEqualTo(2);
            }

            // Remove OLD_ID2 should succeed
            assertThat(facade.removeDeletedMedia(OLD_ID2)).isTrue();
            // Remove OLD_ID2 again should fail
            assertThat(facade.removeDeletedMedia(OLD_ID2)).isFalse();

            // Verify only OLD_ID1 left
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertThat(cursor.getLong(0)).isEqualTo(OLD_ID1);
            }
        }
    }

    @Test
    public void testDeletedMedia_onInsert() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_VIDEO, /* isPending */ false))
                    .isTrue();
            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ false))
                    .isTrue();
            assertDeletedMediaEmpty(facade);

            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_AUDIO, /* isPending */ false))
                    .isFalse();
            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_NONE, /* isPending */ false))
                    .isFalse();
            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ true))
                    .isFalse();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_mediaType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            // Non-media -> non-media: no-op
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isFalse();
            assertDeletedMediaEmpty(facade);

            // Media -> non-media: added to deleted_media
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isTrue();
            assertDeletedMedia(facade, OLD_ID1);

            // Non-media -> non-media: no-op
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isFalse();
            assertDeletedMedia(facade, OLD_ID1);

            // Non-media -> media: remove from deleted_media
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Non-media -> media: no-op
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isFalse();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_trashed() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            // Was trashed but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isFalse();
            assertDeletedMediaEmpty(facade);

            // Was not trashed but is now trashed
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ true,
                            /* oldIsPending */ false, /* newIsPending */ false)).isTrue();
            assertDeletedMedia(facade, OLD_ID1);

            // Was trashed but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false)).isTrue();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_pending() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            // Was pending but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ false)).isFalse();
            assertDeletedMediaEmpty(facade);

            // Was not pending but is now pending
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ true)).isTrue();
            assertDeletedMedia(facade, OLD_ID1);

            // Was pending but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(OLD_ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ false)).isTrue();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onDelete() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            assertThat(facade.onFileDeleted(OLD_ID1, FileColumns.MEDIA_TYPE_NONE)).isFalse();
            assertDeletedMediaEmpty(facade);

            assertThat(facade.onFileDeleted(OLD_ID1, FileColumns.MEDIA_TYPE_IMAGE)).isTrue();
            assertDeletedMedia(facade, OLD_ID1);

            assertThat(facade.onFileDeleted(OLD_ID1, FileColumns.MEDIA_TYPE_NONE)).isFalse();
            assertDeletedMedia(facade, OLD_ID1);
        }
    }

    @Test
    public void testQueryMediaGeneration_match() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            // Intentionally associate <date_taken_ms2 with generation_modifed1>
            // and <date_taken_ms1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, OLD_ID1, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, OLD_ID2, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaGeneration(GENERATION_MODIFIED1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, OLD_ID2, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_noMatch() throws Exception {
        ContentValues cvPending = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cvPending.put(MediaColumns.IS_PENDING, 1);

        ContentValues cvTrashed = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cvTrashed.put(MediaColumns.IS_TRASHED, 1);

        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvPending));
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvTrashed));

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_withDateModified() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);
            long dateModifiedSeconds1 = DATE_TAKEN_MS1 / 1000;
            long dateModifiedSeconds2 = DATE_TAKEN_MS2 / 1000;
            // Intentionally associate <dateModifiedSeconds2 with generation_modifed1>
            // and <dateModifiedSeconds1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            cv.remove(MediaColumns.DATE_TAKEN);
            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, OLD_ID1, dateModifiedSeconds2 * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, OLD_ID2, dateModifiedSeconds1 * 1000);
            }

            try (Cursor cursor = facade.queryMediaGeneration(GENERATION_MODIFIED1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, OLD_ID2, dateModifiedSeconds1 * 1000);
            }
        }
    }

    @Test
    public void testQueryMediaId_match() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaId(OLD_ID1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, OLD_ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaId_noMatch() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            ContentValues cvPending = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cvPending.put(MediaColumns.IS_PENDING, 1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvPending));

            ContentValues cvTrashed = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
            cvTrashed.put(MediaColumns.IS_TRASHED, 1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvTrashed));

            try (Cursor cursor = facade.queryMediaId(OLD_ID1)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaId_withDateModified() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            long dateModifiedSeconds = DATE_TAKEN_MS1 / 1000;
            ContentValues cv = new ContentValues();
            cv.put(MediaColumns.SIZE, SIZE);
            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds);
            cv.put(FileColumns.MIME_TYPE, MIME_TYPE);
            cv.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
            cv.put(MediaColumns.DURATION, DURATION_MS);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaId(OLD_ID1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, OLD_ID1, dateModifiedSeconds * 1000);
            }
        }
    }

    @Test
    public void testGetMediaInfo() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacadeForPicker facade = new ExternalDbFacadeForPicker(helper);

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.getMediaInfo(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaInfo(facade, cursor, /* count */ 2, /* generation */ 2);
            }

            try (Cursor cursor = facade.getMediaInfo(GENERATION_MODIFIED1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaInfo(facade, cursor, /* count */ 1, GENERATION_MODIFIED2);
            }

            try (Cursor cursor = facade.getMediaInfo(GENERATION_MODIFIED2)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaInfo(facade, cursor, /* count */ 0, /* generation */ 0);
            }
        }
    }

    private static void assertDeletedMediaEmpty(ExternalDbFacadeForPicker facade) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void assertDeletedMedia(ExternalDbFacadeForPicker facade, long id) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertThat(cursor.getCount()).isEqualTo(1);

            cursor.moveToFirst();
            assertThat(cursor.getLong(0)).isEqualTo(id);
            // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
            assertThat(cursor.getColumnName(0)).isEqualTo("id");
        }
    }

    private static void assertMediaColumns(ExternalDbFacadeForPicker facade, Cursor cursor, long id,
            long dateTakenMs) {
        // TODO(b/190713331): Use CloudMediaProviderContract#MediaColumns
        int idIndex = cursor.getColumnIndex("id");
        int dateTakenIndex = cursor.getColumnIndex("date_taken_ms");
        int sizeIndex = cursor.getColumnIndex("size_bytes");
        int mimeTypeIndex = cursor.getColumnIndex("mime_type");
        int durationIndex = cursor.getColumnIndex("duration_ms");

        assertThat(cursor.getLong(idIndex)).isEqualTo(id);
        assertThat(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(sizeIndex)).isEqualTo(SIZE);
        assertThat(cursor.getString(mimeTypeIndex)).isEqualTo(MIME_TYPE);
        assertThat(cursor.getLong(durationIndex)).isEqualTo(DURATION_MS);
    }

    private static void assertMediaInfo(ExternalDbFacadeForPicker facade, Cursor cursor,
            long count, long generation) {
        // TODO(b/190713331): Use CloudMediaProviderContract#MediaColumns
        int countIndex = cursor.getColumnIndex("media_count");
        int generationIndex = cursor.getColumnIndex("media_generation");

        assertThat(cursor.getLong(countIndex)).isEqualTo(count);
        assertThat(cursor.getLong(generationIndex)).isEqualTo(generation);
    }

    private static ContentValues getContentValues(long dateTakenMs, long generation) {
        ContentValues cv = new ContentValues();
        cv.put(MediaColumns.SIZE, SIZE);
        cv.put(MediaColumns.DATE_TAKEN, dateTakenMs);
        cv.put(FileColumns.MIME_TYPE, MIME_TYPE);
        cv.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
        cv.put(MediaColumns.DURATION, DURATION_MS);
        cv.put(MediaColumns.GENERATION_MODIFIED, generation);

        return cv;
    }

    private static class TestDatabaseHelper extends DatabaseHelper {
        public TestDatabaseHelper(Context context) {
            super(context, TEST_CLEAN_DB, 1,
                    false, false, null, null, null, null, null);
        }
    }
}
