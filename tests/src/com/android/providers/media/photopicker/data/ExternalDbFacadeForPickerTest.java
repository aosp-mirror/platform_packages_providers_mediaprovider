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

import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore.Files.FileColumns;

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
            try (Cursor cursor = facade.queryDeletedMedia(0 /* generation */)) {
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

    private static void assertDeletedMediaEmpty(ExternalDbFacadeForPicker facade) {
        try (Cursor cursor = facade.queryDeletedMedia(0 /* generation */)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void assertDeletedMedia(ExternalDbFacadeForPicker facade, long id) {
        try (Cursor cursor = facade.queryDeletedMedia(0 /* generation */)) {
            assertThat(cursor.getCount()).isEqualTo(1);

            cursor.moveToFirst();
            assertThat(cursor.getLong(0)).isEqualTo(id);
            // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
            assertThat(cursor.getColumnName(0)).isEqualTo("id");
        }
    }

    private static class TestDatabaseHelper extends DatabaseHelper {
        public TestDatabaseHelper(Context context) {
            super(context, TEST_CLEAN_DB, 1,
                    false, false, null, null, null, null, null);
        }
    }
}
