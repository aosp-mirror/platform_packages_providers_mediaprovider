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

import static android.content.ContentResolver.EXTRA_HONORED_ARGS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;

import static com.android.providers.media.photopicker.data.ExternalDbFacade.COLUMN_OLD_ID;
import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_DELETED_MEDIA;
import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_FILES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Environment;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.IsolatedContext;
import com.android.providers.media.ProjectionHelper;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.TestDatabaseBackupAndRecovery;
import com.android.providers.media.VolumeCache;
import com.android.providers.media.util.UserCache;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

@RunWith(AndroidJUnit4.class)
public class ExternalDbFacadeTest {
    private static final String TAG = "ExternalDbFacadeTest";

    private static final long ID1 = 1;
    private static final long ID2 = 2;
    private static final long ID3 = 3;
    private static final long ID4 = 4;
    private static final long ID5 = 5;
    private static final long DATE_TAKEN_MS1 = 1624886050566L;
    private static final long DATE_TAKEN_MS2 = 1624886050567L;
    private static final long DATE_TAKEN_MS3 = 1624886050568L;
    private static final long DATE_TAKEN_MS4 = 1624886050569L;
    private static final long DATE_TAKEN_MS5 = 1624886050570L;
    private static final long GENERATION_MODIFIED1 = 1;
    private static final long GENERATION_MODIFIED2 = 2;
    private static final long GENERATION_MODIFIED3 = 3;
    private static final long GENERATION_MODIFIED4 = 4;
    private static final long GENERATION_MODIFIED5 = 5;
    private static final long SIZE = 8000;
    private static final long HEIGHT = 500;
    private static final long WIDTH = 700;
    private static final long ORIENTATION = 1;
    private static final String IMAGE_MIME_TYPE = "image/jpeg";
    private static final String[] IMAGE_MIME_TYPES_QUERY = new String[]{"image/jpeg"};
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String[] VIDEO_MIME_TYPES_QUERY = new String[]{"video/mp4"};
    private static final long DURATION_MS = 5;
    private static final int IS_FAVORITE = 0;

    private static Context sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
    }

    @Test
    public void testDeletedMedia_addAndRemove() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            assertThat(facade.addDeletedMedia(ID1)).isTrue();
            assertThat(facade.addDeletedMedia(ID2)).isTrue();

            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                ArrayList<Long> ids = new ArrayList<>();
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0));
                }

                assertThat(ids).contains(ID1);
                assertThat(ids).contains(ID2);
            }

            // Filter by generation should only return ID2
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertThat(cursor.getLong(0)).isEqualTo(ID2);
            }

            // Adding ids again should succeed but bump generation_modified of ID1 and ID2
            assertThat(facade.addDeletedMedia(ID1)).isTrue();
            assertThat(facade.addDeletedMedia(ID2)).isTrue();

            // Filter by generation again, now returns both ids since their generation_modified was
            // bumped
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertThat(cursor.getCount()).isEqualTo(2);
            }

            // Remove ID2 should succeed
            assertThat(facade.removeDeletedMedia(ID2)).isTrue();
            // Remove ID2 again should fail
            assertThat(facade.removeDeletedMedia(ID2)).isFalse();

            // Verify only ID1 left
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertThat(cursor.getLong(0)).isEqualTo(ID1);
            }
        }
    }

    @Test
    public void testDeletedMedia_onInsert() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

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
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Non-media -> non-media: no-op
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isFalse();
            assertDeletedMediaEmpty(facade);

            // Media -> non-media: added to deleted_media
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMedia(facade, ID1);

            // Non-media -> non-media: no-op
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isFalse();
            assertDeletedMedia(facade, ID1);

            // Non-media -> media: remove from deleted_media
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Non-media -> media: no-op
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isFalse();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_trashed() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was trashed but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Was not trashed but is now trashed
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ true,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMedia(facade, ID1);

            // Was trashed but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_pending() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was pending but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Was not pending but is now pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ true,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMedia(facade, ID1);

            // Was pending but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testOnUpdate_visibleFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was favorite but is now not favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ true, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();

            // Was not favorite but is now favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ true,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
        }
    }

    @Test
    public void testOnUpdate_hiddenFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was favorite but is now not favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ true,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ true, /* newIsFavorite */ false,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isFalse();

            // Was not favorite but is now favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ true,
                            /* oldIsFavorite */ false, /* newIsFavorite */ true,
                            /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                            /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isFalse();
        }
    }

    @Test
    public void testOnUpdate_visibleSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            assertThat(facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_GIF)).isTrue();

            // Was _SPECIAL_FORMAT_GIF but is now _SPECIAL_FORMAT_NONE
            assertThat(facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_GIF,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isTrue();
        }
    }

    @Test
    public void testOnUpdate_hiddenSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            assertThat(facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_GIF)).isFalse();

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            assertThat(facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_GIF,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)).isFalse();
        }
    }

    @Test
    public void testDeletedMedia_onDelete() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            assertThat(facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)).isFalse();
            assertDeletedMediaEmpty(facade);

            assertThat(facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_IMAGE)).isTrue();
            assertDeletedMedia(facade, ID1);

            assertThat(facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)).isFalse();
            assertDeletedMedia(facade, ID1);
        }
    }

    @Test
    public void testQueryMedia_match() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Intentionally associate <date_taken_ms2 with generation_modifed1>
            // and <date_taken_ms1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(2);
                assertCursorExtras(cursor);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(GENERATION_MODIFIED1,
                            /* albumId */ null, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);
                assertCursorExtras(cursor, EXTRA_SYNC_GENERATION);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_noMatch() throws Exception {
        ContentValues cvPending = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cvPending.put(MediaColumns.IS_PENDING, 1);

        ContentValues cvTrashed = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cvTrashed.put(MediaColumns.IS_TRASHED, 1);

        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvPending));
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvTrashed));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMedia_withDateModified() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));
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

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, dateModifiedSeconds2 * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds1 * 1000);
            }

            try (Cursor cursor = facade.queryMedia(GENERATION_MODIFIED1,
                            /* albumId */ null, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds1 * 1000);
            }
        }
    }

    @Test
    public void testQueryMedia_withMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                            /* albumId */ null, VIDEO_MIME_TYPES_QUERY)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                            /* albumId */ null, IMAGE_MIME_TYPES_QUERY)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_withAlbum() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                            ALBUM_ID_CAMERA, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);
                assertCursorExtras(cursor, EXTRA_ALBUM_ID);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                            ALBUM_ID_SCREENSHOTS, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);
                assertCursorExtras(cursor, EXTRA_ALBUM_ID);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                            ALBUM_ID_DOWNLOADS, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);
                assertCursorExtras(cursor, EXTRA_ALBUM_ID);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, DATE_TAKEN_MS3);
            }
        }
    }

    @Test
    public void testQueryMedia_withAlbumAndMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                            ALBUM_ID_SCREENSHOTS, IMAGE_MIME_TYPES_QUERY)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                            ALBUM_ID_CAMERA, VIDEO_MIME_TYPES_QUERY)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                            ALBUM_ID_CAMERA, IMAGE_MIME_TYPES_QUERY)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testGetMediaCollectionInfoFiltering() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            Bundle bundle = facade.getMediaCollectionInfo(/* generation */ 0);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 2);

            bundle = facade.getMediaCollectionInfo(GENERATION_MODIFIED1);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 2);

            bundle = facade.getMediaCollectionInfo(GENERATION_MODIFIED2);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 0);
        }
    }

    @Test
    public void testGetMediaCollectionInfoVolumeNames() throws Exception {
        VolumeCache mockVolumeCache = mock(VolumeCache.class);
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mockVolumeCache);

            HashSet<String> volumes = new HashSet<>();
            volumes.add("foo");
            volumes.add("bar");
            when(mockVolumeCache.getExternalVolumeNames()).thenReturn(volumes);

            final String expectedMediaCollectionId = MediaStore.getVersion(sIsolatedContext)
                    + ":" + "bar:foo";

            final Bundle bundle = facade.getMediaCollectionInfo(/* generation */ 0);
            final String mediaCollectionId = bundle.getString(
                    MediaCollectionInfo.MEDIA_COLLECTION_ID);

            assertThat(mediaCollectionId).isEqualTo(expectedMediaCollectionId);
        }
    }

    @Test
    public void testGetMediaCollectionInfoWithDeleted() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            ContentValues cvDeleted = new ContentValues();
            cvDeleted.put(COLUMN_OLD_ID, ID2);
            cvDeleted.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_DELETED_MEDIA, null, cvDeleted));

            Bundle bundle = facade.getMediaCollectionInfo(/* generation */ 0);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 2);
        }
    }

    @Test
    public void testQueryAlbumsEmpty() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(1);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryAlbums() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(3);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(3);

                // We verify the order of the albums:
                // Camera, Screenshots and Downloads
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_SCREENSHOTS, DATE_TAKEN_MS2,
                        /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_DOWNLOADS, DATE_TAKEN_MS3,
                        /* count */ 1);
            }
        }
    }

    @Test
    public void testQueryAlbumsMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image in camera album
            ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

            // Insert video in camera album
            ContentValues cv2 = getContentValues(DATE_TAKEN_MS5, GENERATION_MODIFIED5);
            cv2.put(FileColumns.MIME_TYPE, VIDEO_MIME_TYPE);
            cv2.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(2);
            }

            try (Cursor cursor = facade.queryAlbums(IMAGE_MIME_TYPES_QUERY)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                // We verify the order of the albums only the image in camera is shown
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);
            }
        }
    }

    @Test
    public void testOrderOfLocalAlbumIds() {
        // Camera, ScreenShots, Downloads
        assertThat(ExternalDbFacade.LOCAL_ALBUM_IDS[0]).isEqualTo(ALBUM_ID_CAMERA);
        assertThat(ExternalDbFacade.LOCAL_ALBUM_IDS[1])
                .isEqualTo(ALBUM_ID_SCREENSHOTS);
        assertThat(ExternalDbFacade.LOCAL_ALBUM_IDS[2])
                .isEqualTo(ALBUM_ID_DOWNLOADS);
    }

    private static void initMediaInAllAlbums(DatabaseHelper helper) {
        // Insert in camera album
        ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

        // Insert in screenshots ablum
        ContentValues cv2 = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cv2.put(
                MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + Environment.DIRECTORY_SCREENSHOTS + "/");
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

        // Insert in download ablum
        ContentValues cv3 = getContentValues(DATE_TAKEN_MS3, GENERATION_MODIFIED3);
        cv3.put(MediaColumns.IS_DOWNLOAD, 1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv3));
    }

    private static void assertDeletedMediaEmpty(ExternalDbFacade facade) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void assertDeletedMedia(ExternalDbFacade facade, long id) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertThat(cursor.getCount()).isEqualTo(1);

            cursor.moveToFirst();
            assertThat(cursor.getLong(0)).isEqualTo(id);
            assertThat(cursor.getColumnName(0)).isEqualTo(
                    CloudMediaProviderContract.MediaColumns.ID);
        }
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs) {
        assertMediaColumns(facade, cursor, id, dateTakenMs, IS_FAVORITE);
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs, int isFavorite) {
        assertMediaColumns(facade, cursor, id, dateTakenMs, isFavorite, IMAGE_MIME_TYPE);
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs, int isFavorite, String mimeType) {
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.ID);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS);
        int sizeIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.SIZE_BYTES);
        int mimeTypeIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.MIME_TYPE);
        int durationIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DURATION_MILLIS);
        int isFavoriteIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.IS_FAVORITE);
        int heightIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.HEIGHT);
        int widthIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.WIDTH);
        int orientationIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.ORIENTATION);

        assertThat(cursor.getLong(idIndex)).isEqualTo(id);
        assertThat(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(sizeIndex)).isEqualTo(SIZE);
        assertThat(cursor.getString(mimeTypeIndex)).isEqualTo(mimeType);
        assertThat(cursor.getLong(durationIndex)).isEqualTo(DURATION_MS);
        assertThat(cursor.getInt(isFavoriteIndex)).isEqualTo(isFavorite);
        assertThat(cursor.getInt(heightIndex)).isEqualTo(HEIGHT);
        assertThat(cursor.getInt(widthIndex)).isEqualTo(WIDTH);
        assertThat(cursor.getInt(orientationIndex)).isEqualTo(ORIENTATION);
    }

    private static void assertCursorExtras(Cursor cursor, String... honoredArg) {
        final Bundle bundle = cursor.getExtras();

        assertThat(bundle.getString(EXTRA_MEDIA_COLLECTION_ID))
                .isEqualTo(MediaStore.getVersion(sIsolatedContext));
        if (honoredArg != null) {
            assertThat(bundle.getStringArrayList(EXTRA_HONORED_ARGS))
                    .containsExactlyElementsIn(Arrays.asList(honoredArg));
        }
    }

    private static void assertAlbumColumns(ExternalDbFacade facade, Cursor cursor,
            String displayName, long dateTakenMs, long count) {
        int displayNameIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME);
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS);
        int countIndex = cursor.getColumnIndex(CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT);

        assertThat(cursor.getString(displayNameIndex)).isEqualTo(displayName);
        assertThat(cursor.getString(idIndex)).isNotNull();
        assertThat(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(countIndex)).isEqualTo(count);
    }

    private static void assertMediaCollectionInfo(ExternalDbFacade facade, Bundle bundle,
            long expectedGeneration) {
        long generation = bundle.getLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);
        String mediaCollectionId = bundle.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);

        assertThat(generation).isEqualTo(expectedGeneration);
        assertThat(mediaCollectionId).isEqualTo(MediaStore.getVersion(sIsolatedContext));
    }

    private static Cursor queryAllMedia(ExternalDbFacade facade) {
        return facade.queryMedia(/* generation */ -1, /* albumId */ null,
                /* mimeType */ null);
    }

    private static ContentValues getContentValues(long dateTakenMs, long generation) {
        ContentValues cv = new ContentValues();
        cv.put(MediaColumns.SIZE, SIZE);
        cv.put(MediaColumns.DATE_TAKEN, dateTakenMs);
        cv.put(FileColumns.MIME_TYPE, IMAGE_MIME_TYPE);
        cv.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
        cv.put(MediaColumns.DURATION, DURATION_MS);
        cv.put(MediaColumns.GENERATION_MODIFIED, generation);
        cv.put(MediaColumns.HEIGHT, HEIGHT);
        cv.put(MediaColumns.WIDTH, WIDTH);
        cv.put(MediaColumns.ORIENTATION, ORIENTATION);
        return cv;
    }

    private static class TestDatabaseHelper extends DatabaseHelper {
        public TestDatabaseHelper(Context context) {
            super(context, TEST_CLEAN_DB, 1, false, false, new ProjectionHelper(null, null), null,
                    null, null, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context)), null));
        }
    }
}
