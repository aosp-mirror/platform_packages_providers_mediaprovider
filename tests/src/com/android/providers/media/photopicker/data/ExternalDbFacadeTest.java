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
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_SIZE;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;
import static android.provider.MediaStore.MediaColumns.DATE_TAKEN;

import static com.android.providers.media.photopicker.data.ExternalDbFacade.COLUMN_OLD_ID;
import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_DELETED_MEDIA;
import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_FILES;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

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
    private static final long DATE_MODIFIED_MS1 = 1625000011L;
    private static final long DATE_MODIFIED_MS2 = 1625000012L;
    private static final long DATE_MODIFIED_MS3 = 1625000013L;
    private static final long GENERATION_MODIFIED1 = 1;
    private static final long GENERATION_MODIFIED2 = 2;
    private static final long GENERATION_MODIFIED3 = 3;
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

            if (!facade.addDeletedMedia(ID1)) {
                assertWithMessage("Adding item with ID %d failed",
                        ID1).fail();
            }
            if (!facade.addDeletedMedia(ID2)) {
                assertWithMessage("Adding item with ID %d failed",
                        ID2).fail();
            }

            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 0"
                                + " was")
                        .that(cursor.getCount()).isEqualTo(2);
                ArrayList<Long> ids = new ArrayList<>();
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0));
                }
                assertWithMessage("The list of ids from delete_media table")
                        .that(ids).contains(ID1);
                assertWithMessage("The list of ids from delete_media table")
                        .that(ids).contains(ID2);
            }

            // Filter by generation should only return ID2
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 1"
                                + " is")
                        .that(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertWithMessage("ID fro row having generation greater than 1")
                        .that(cursor.getLong(0)).isEqualTo(ID2);
            }

            // Adding ids again should succeed but bump generation_modified of ID1 and ID2
            if (!facade.addDeletedMedia(ID1)) {
                assertWithMessage("Adding item with ID %d failed",
                        ID1).fail();
            }
            if (!facade.addDeletedMedia(ID2)) {
                assertWithMessage("Adding item with ID %d failed",
                        ID2).fail();
            }

            // Filter by generation again, now returns both ids since their generation_modified was
            // bumped
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 1"
                                + " is")
                        .that(cursor.getCount()).isEqualTo(2);
            }

            // Remove ID2 should succeed
            if (!facade.removeDeletedMedia(ID2)) {
                assertWithMessage("Removing item with ID %d failed", ID2).fail();
            }
            // Remove ID2 again should fail
            if (facade.removeDeletedMedia(ID2)) {
                assertWithMessage("Removing item with ID %d should have failed", ID2).fail();
            }

            // Verify only ID1 left
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 0"
                                + " is")
                        .that(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertWithMessage(
                        "ID of the item left in the deleted_media table after deleting row with "
                                + "id=ID2 is")
                        .that(cursor.getLong(0)).isEqualTo(ID1);
            }
        }
    }

    @Test
    public void testDeletedMedia_onInsert() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            if (!facade.onFileInserted(FileColumns.MEDIA_TYPE_VIDEO, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return true but returned false on Insert of "
                                + "MEDIA_TYPE_VIDEO").fail();
            }
            if (!facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return true but returned false on Insert of "
                                + "MEDIA_TYPE_IMAGE").fail();
            }
            assertDeletedMediaEmpty(facade);

            if (facade.onFileInserted(FileColumns.MEDIA_TYPE_AUDIO, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return false but returned true on Insert of "
                                + "MEDIA_TYPE_AUDIO").fail();
            }
            if (facade.onFileInserted(FileColumns.MEDIA_TYPE_NONE, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return false but returned true on Insert of "
                                + "MEDIA_TYPE_NONE").fail();
            }
            if (facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ true)) {
                assertWithMessage(
                        "Expected to return false but returned true on Insert of "
                                + " MEDIA_TYPE_IMAGE with isPending true").fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_mediaType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Non-media -> non-media: no-op
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMediaEmpty(facade);

            // Media -> non-media: added to deleted_media
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on Update from "
                                + "MEDIA_TYPE_IMAGE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Non-media -> non-media: no-op
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Non-media -> media: remove from deleted_media
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_IMAGE").fail();
            }
            assertDeletedMediaEmpty(facade);

            // Non-media -> Non-media: no-op
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_trashed() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was trashed but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was trashed but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);

            // Was not trashed but is now trashed
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was not trashed but the newMedia is trashed.").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Was trashed but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was trashed but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_pending() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was pending but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was pending but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);

            // Was not pending but is now pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was not pending but the newMedia is pending.").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Was pending but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was pending but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testOnUpdate_visibleFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was favorite but is now not favorited
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ true, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "favorite, when the oldMedia "
                                + "was favorite but the newMedia is not favorite.").fail();
            }

            // Was not favorite but is now favorited
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ true,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "favorite, when the oldMedia "
                                + "was not favorite but the newMedia is favorite.").fail();
            }
        }
    }

    @Test
    public void testOnUpdate_hiddenFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was favorite but is now not favorited
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ true, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with hidden "
                                + "favorite, when the oldMedia was favorite but the newMedia is "
                                + "not favorite.").fail();
            }

            // Was not favorite but is now favorited
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ true,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on update with hidden "
                                + "favorite, when the oldMedia was not favorite but the newMedia "
                                + "is favorite.").fail();
            }
        }
    }

    @Test
    public void testOnUpdate_visibleSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_GIF)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "special format, when the oldSpecialFormat was NONE but the "
                                + "newSpecialFormat is GIF.").fail();
            }

            // Was _SPECIAL_FORMAT_GIF but is now _SPECIAL_FORMAT_NONE
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_GIF,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "special format, when the oldSpecialFormat was GIF but the "
                                + "newSpecialFormat is NONE.").fail();
            }
        }
    }

    @Test
    public void testOnUpdate_hiddenSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_GIF)) {
                assertWithMessage(
                        "Expected to return false but returned true on update with hidden special"
                                + " format, when the oldSpecialFormat was NONE but the "
                                + "newSpecialFormat is GIF.").fail();
            }

            // Was _SPECIAL_FORMAT_GIF but is now _SPECIAL_FORMAT_NONE
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_GIF,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on update with hidden special"
                                + " format, when the oldSpecialFormat was GIF but the "
                                + "newSpecialFormat is NONE.").fail();
            }
        }
    }

    @Test
    public void testDeletedMedia_onDelete() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            if (facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)) {
                assertWithMessage(
                        "Expected to return false when the mediaType is NONE, but returned true "
                                + "on delete.").fail();
            }
            assertDeletedMediaEmpty(facade);

            if (!facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_IMAGE)) {
                assertWithMessage(
                        "Expected to return true when the mediaType is IMAGE, but returned false "
                                + "on delete.").fail();
            }
            assertDeletedMedia(facade, ID1);

            if (facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)) {
                assertWithMessage(
                        "Expected to return false when the mediaType is NONE, but returned true "
                                + "on delete.").fail();
            }
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
                assertWithMessage("Number of rows on querying TABLE_FILES for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(2);
                assertCursorExtras(cursor);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(GENERATION_MODIFIED1,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 10,
                    /*pageToken */ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLE_FILES for (generation: "
                                + "GENERATION_MODIFIED1, albumId: null, mimeType: null, pageSize:"
                                + " 10) is")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtras(cursor, EXTRA_SYNC_GENERATION, EXTRA_PAGE_SIZE,
                        EXTRA_PAGE_TOKEN);

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
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES with cvPending and cvTrashed "
                                + "inserted is")
                        .that(cursor.getCount()).isEqualTo(0);
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
            // is based on date_taken and _id and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            cv.remove(MediaColumns.DATE_TAKEN);
            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES with modified date for all media"
                                + " is")
                        .that(cursor.getCount())
                        .isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds2 * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID1, dateModifiedSeconds1 * 1000);
            }

            try (Cursor cursor = facade.queryMedia(GENERATION_MODIFIED1,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ -1,
                    /*pageToken */ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLE_FILES with modified date for "
                                + "(generation: "
                                + "GENERATION_MODIFIED1, albumId: null, mimeType: null, pageSize:"
                                + " -1) is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

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
                assertWithMessage("Number of rows on querying TABLES_FILES for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, VIDEO_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with mime type VIDEO is")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, IMAGE_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with mime type IMAGE is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

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
                assertWithMessage("Number of rows on querying TABLES_FILES for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                    ALBUM_ID_CAMERA, /* mimeType */ null, /* pageSize*/ 20,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with ALBUM_ID_CAMERA is")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtras(cursor, EXTRA_ALBUM_ID, EXTRA_PAGE_SIZE, EXTRA_PAGE_TOKEN);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                    ALBUM_ID_SCREENSHOTS, /* mimeType */ null, /* pageSize*/ -1,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with "
                                + "ALBUM_ID_SCREENSHOTS is")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                assertCursorExtras(cursor, EXTRA_ALBUM_ID);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                    ALBUM_ID_DOWNLOADS, /* mimeType */ null, /* pageSize*/ 10,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with "
                                + "ALBUM_ID_DOWNLOADS is")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtras(cursor, EXTRA_ALBUM_ID, EXTRA_PAGE_SIZE, EXTRA_PAGE_TOKEN);

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
                assertWithMessage("Number of rows on querying TABLES_FILES for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    ALBUM_ID_SCREENSHOTS, IMAGE_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with "
                                + "ALBUM_ID_SCREENSHOTS and IMAGE_MIME_TYPES_QUERY is")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    ALBUM_ID_CAMERA, VIDEO_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with ALBUM_ID_CAMERA "
                                + "and VIDEO_MIME_TYPES_QUERY is")
                        .that(cursor.getCount())
                        .isEqualTo(0);

            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    ALBUM_ID_CAMERA, IMAGE_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with ALBUM_ID_CAMERA "
                                + "and IMAGE_MIME_TYPES_QUERY is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_withPageSize_returnsCorrectSortOrder() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images with date_taken non-null
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS3);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS4);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS5);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            // Verify that media returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ null)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID5, DATE_TAKEN_MS5);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID4, DATE_TAKEN_MS4);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 3,
                    /* pageToken*/ DATE_TAKEN_MS4 + "|" + ID4)) {
                assertThat(cursor.getCount()).isEqualTo(3);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, DATE_TAKEN_MS3);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_withPageSizeMissingPageToken_returnsCorrectSortOrder()
            throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images, 2 with date_taken non-null and 3 with date_taken null
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.remove(DATE_TAKEN);

            cv.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS3);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            // Verify that media returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ null)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID5, Long.valueOf(DATE_MODIFIED_MS3) * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID4, Long.valueOf(DATE_MODIFIED_MS2) * 1000);
            }

            String pageToken =  Long.valueOf(DATE_MODIFIED_MS2) * 1000 + "|" + ID4;
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ pageToken)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, Long.valueOf(DATE_MODIFIED_MS1) * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            pageToken =  DATE_TAKEN_MS2 + "|" + ID2;
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ pageToken)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            pageToken = DATE_MODIFIED_MS1 + "|" + ID1;
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ pageToken)) {
                assertThat(cursor.getCount()).isEqualTo(0);
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

            assertWithMessage("The mediaCollectionId is")
                    .that(mediaCollectionId).isEqualTo(expectedMediaCollectionId);
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
                assertWithMessage("Number of rows on querying TABLES_FILES with for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertWithMessage("Number of rows on querying TABLES_FILES for albums is")
                        .that(cursor.getCount())
                        .isEqualTo(0);
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
                assertWithMessage("Number of rows on querying TABLES_FILES for all media")
                        .that(cursor.getCount())
                        .isEqualTo(2);
            }

            try (Cursor cursor = facade.queryAlbums(IMAGE_MIME_TYPES_QUERY)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for albums with "
                                + "IMAGE_MIME_TYPES_QUERY")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                // We verify the order of the albums only the image in camera is shown
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);
            }
        }
    }

    @Test
    public void testOrderOfLocalAlbumIds() {
        // Camera, ScreenShots, Downloads
        assertWithMessage("Local album at 0th index is")
                .that(ExternalDbFacade.LOCAL_ALBUM_IDS[0])
                .isEqualTo(ALBUM_ID_CAMERA);
        assertWithMessage("Local album at 1st index is")
                .that(ExternalDbFacade.LOCAL_ALBUM_IDS[1])
                .isEqualTo(ALBUM_ID_SCREENSHOTS);
        assertWithMessage("Local album at 2nd index is")
                .that(ExternalDbFacade.LOCAL_ALBUM_IDS[2])
                .isEqualTo(ALBUM_ID_DOWNLOADS);
    }

    private static void initMediaInAllAlbums(DatabaseHelper helper) {
        // Insert in camera album
        ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

        // Insert in screenshots album
        ContentValues cv2 = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cv2.put(
                MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + Environment.DIRECTORY_SCREENSHOTS + "/");
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

        // Insert in download album
        ContentValues cv3 = getContentValues(DATE_TAKEN_MS3, GENERATION_MODIFIED3);
        cv3.put(MediaColumns.IS_DOWNLOAD, 1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv3));
    }

    private static void assertDeletedMediaEmpty(ExternalDbFacade facade) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertWithMessage(
                    "Number of rows in the deleted_media table is")
                    .that(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void assertDeletedMedia(ExternalDbFacade facade, long id) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertWithMessage("Number of rows in the deleted_media table is")
                    .that(cursor.getCount())
                    .isEqualTo(1);

            cursor.moveToFirst();
            assertWithMessage("Row id for the deleted media is")
                    .that(cursor.getLong(0))
                    .isEqualTo(id);
            assertWithMessage("Name of the column at index 0 is")
                    .that(cursor.getColumnName(0))
                    .isEqualTo(CloudMediaProviderContract.MediaColumns.ID);
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

        assertWithMessage("MediaColumns.ID is")
                .that(cursor.getLong(idIndex))
                .isEqualTo(id);
        assertWithMessage("MediaColumns.DATE_TAKEN_MILLIS is")
                .that(cursor.getLong(dateTakenIndex))
                .isEqualTo(dateTakenMs);
        assertWithMessage("MediaColumns.SIZE_BYTES is")
                .that(cursor.getLong(sizeIndex))
                .isEqualTo(SIZE);
        assertWithMessage("MediaColumns.MIME_TYPE is")
                .that(cursor.getString(mimeTypeIndex))
                .isEqualTo(mimeType);
        assertWithMessage("MediaColumns.DURATION_MILLIS is")
                .that(cursor.getLong(durationIndex))
                .isEqualTo(DURATION_MS);
        assertWithMessage("MediaColumns.IS_FAVORITE is")
                .that(cursor.getInt(isFavoriteIndex))
                .isEqualTo(isFavorite);
        assertWithMessage("MediaColumns.HEIGHT is")
                .that(cursor.getInt(heightIndex))
                .isEqualTo(HEIGHT);
        assertWithMessage("MediaColumns.WIDTH is")
                .that(cursor.getInt(widthIndex))
                .isEqualTo(WIDTH);
        assertWithMessage("MediaColumns.ORIENTATION is")
                .that(cursor.getInt(orientationIndex))
                .isEqualTo(ORIENTATION);
    }

    private static void assertCursorExtras(Cursor cursor, String... honoredArg) {
        final Bundle bundle = cursor.getExtras();

        assertWithMessage("Cursor extras is")
                .that(bundle.getString(EXTRA_MEDIA_COLLECTION_ID))
                .isEqualTo(MediaStore.getVersion(sIsolatedContext));
        if (honoredArg != null) {
            assertWithMessage("Honored args are")
                    .that(bundle.getStringArrayList(EXTRA_HONORED_ARGS))
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

        assertWithMessage("AlbumColumns.DISPLAY_NAME is")
                .that(cursor.getString(displayNameIndex)).isEqualTo(displayName);
        assertWithMessage("AlbumColumns.MEDIA_COVER_ID is")
                .that(cursor.getString(idIndex)).isNotNull();
        assertWithMessage("AlbumColumns.DATE_TAKEN_MILLIS is")
                .that(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertWithMessage("AlbumColumns.MEDIA_COUNT is")
                .that(cursor.getLong(countIndex)).isEqualTo(count);
    }

    private static void assertMediaCollectionInfo(ExternalDbFacade facade, Bundle bundle,
            long expectedGeneration) {
        long generation = bundle.getLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);
        String mediaCollectionId = bundle.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);

        assertWithMessage("LAST_MEDIA_SYNC_GENERATION is")
                .that(generation).isEqualTo(expectedGeneration);
        assertWithMessage("MEDIA_COLLECTION_ID is")
                .that(mediaCollectionId).isEqualTo(MediaStore.getVersion(sIsolatedContext));
    }

    private static Cursor queryAllMedia(ExternalDbFacade facade) {
        return facade.queryMedia(/* generation */ -1, /* albumId */ null,
                /* mimeType */ null, /* pageSize*/ -1, /* pageToken*/ null);
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
