/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.providers.media;

import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static com.android.providers.media.DatabaseHelper.TEST_CLEAN_DB;
import static com.android.providers.media.DatabaseHelper.TEST_DOWNGRADE_DB;
import static com.android.providers.media.DatabaseHelper.TEST_RECOMPUTE_DB;
import static com.android.providers.media.DatabaseHelper.TEST_UPGRADE_DB;
import static com.android.providers.media.DatabaseHelper.makePristineSchema;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.UserHandle;
import android.provider.Column;
import android.provider.ExportedSince;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.stableuris.dao.BackupIdRow;
import com.android.providers.media.util.UserCache;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class DatabaseHelperTest {
    private static final String TAG = "DatabaseHelperTest";
    private static final String SQLITE_MASTER_ORDER_BY = "type,name,tbl_name";

    private static Context sIsolatedContext;
    private static ContentResolver sIsolatedResolver;
    private static ProjectionHelper sProjectionHelper;

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.INTERACT_ACROSS_USERS);
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        sProjectionHelper = new ProjectionHelper(Column.class, ExportedSince.class);
    }

    @Test
    public void testFilterVolumeNames() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperU(sIsolatedContext, TEST_CLEAN_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
                values.put(FileColumns.DATA, "/storage/emulated/0/Coldplay-Clocks.mp3");
                values.put(AudioColumns.TITLE, "Clocks");
                values.put(AudioColumns.ALBUM, "A Rush of Blood");
                values.put(AudioColumns.ARTIST, "Coldplay");
                values.put(AudioColumns.GENRE, "Rock");
                values.put(AudioColumns.IS_MUSIC, true);
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, "0000-0000");
                values.put(FileColumns.DATA, "/storage/0000-0000/Coldplay-SpeedOfSound.mp3");
                values.put(AudioColumns.TITLE, "Speed of Sound");
                values.put(AudioColumns.ALBUM, "X&Y");
                values.put(AudioColumns.ARTIST, "Coldplay");
                values.put(AudioColumns.GENRE, "Alternative rock");
                values.put(AudioColumns.IS_MUSIC, true);
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, "0000-0000");
                values.put(FileColumns.DATA, "/storage/0000-0000/U2-BeautifulDay.mp3");
                values.put(AudioColumns.TITLE, "Beautiful Day");
                values.put(AudioColumns.ALBUM, "All That You Can't Leave Behind");
                values.put(AudioColumns.ARTIST, "U2");
                values.put(AudioColumns.GENRE, "Rock");
                values.put(AudioColumns.IS_MUSIC, true);
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }

            // Confirm that raw view knows everything
            assertThat(queryValues(helper, "audio", "title"))
                    .containsExactly("Clocks", "Speed of Sound", "Beautiful Day");

            // By default, database only knows about primary storage
            assertThat(queryValues(helper, "audio_artists", "artist"))
                    .containsExactly("Coldplay");
            assertThat(queryValues(helper, "audio_albums", "album"))
                    .containsExactly("A Rush of Blood");
            assertThat(queryValues(helper, "audio_genres", "name"))
                    .containsExactly("Rock");

            // Once we broaden mounted volumes, we know a lot more
            helper.setFilterVolumeNames(ImmutableSet.of(VOLUME_EXTERNAL_PRIMARY, "0000-0000"));
            assertThat(queryValues(helper, "audio_artists", "artist"))
                    .containsExactly("Coldplay", "U2");
            assertThat(queryValues(helper, "audio_albums", "album"))
                    .containsExactly("A Rush of Blood", "X&Y", "All That You Can't Leave Behind");
            assertThat(queryValues(helper, "audio_genres", "name"))
                    .containsExactly("Rock", "Alternative rock");

            // And unmounting primary narrows us the other way
            helper.setFilterVolumeNames(ImmutableSet.of("0000-0000"));
            assertThat(queryValues(helper, "audio_artists", "artist"))
                    .containsExactly("Coldplay", "U2");
            assertThat(queryValues(helper, "audio_albums", "album"))
                    .containsExactly("X&Y", "All That You Can't Leave Behind");
            assertThat(queryValues(helper, "audio_genres", "name"))
                    .containsExactly("Rock", "Alternative rock");

            // Finally fully unmounted means nothing
            helper.setFilterVolumeNames(ImmutableSet.of());
            assertThat(queryValues(helper, "audio_artists", "artist")).isEmpty();
            assertThat(queryValues(helper, "audio_albums", "album")).isEmpty();
            assertThat(queryValues(helper, "audio_genres", "name")).isEmpty();
        }
    }

    @Test
    public void testArtistsAndAlbumsIncludeOnlyMusic() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperR(sIsolatedContext, TEST_CLEAN_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
                values.put(FileColumns.DATA, "/storage/emulated/0/Coldplay-Clocks.mp3");
                values.put(AudioColumns.TITLE, "Clocks");
                values.put(AudioColumns.ALBUM, "A Rush of Blood");
                values.put(AudioColumns.ARTIST, "Coldplay");
                values.put(AudioColumns.GENRE, "Rock");
                values.put(AudioColumns.IS_MUSIC, true);
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
                values.put(FileColumns.DATA, "/storage/emulated/0/My-podcast.mp3");
                values.put(AudioColumns.TITLE, "My podcast title with false is_music");
                values.put(AudioColumns.ALBUM, "My podcast album");
                values.put(AudioColumns.ARTIST, "My podcast artist");
                values.put(AudioColumns.GENRE, "Podcast");
                values.put(AudioColumns.IS_MUSIC, false);
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
                values.put(FileColumns.DATA, "/storage/emulated/0/My-podcast-2.mp3");
                values.put(AudioColumns.TITLE, "My podcast title with not set is_music");
                values.put(AudioColumns.ALBUM, "My podcast album");
                values.put(AudioColumns.ARTIST, "My podcast artist");
                values.put(AudioColumns.GENRE, "Podcast 2");
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }

            // Raw view shows everything.
            assertThat(queryValues(helper, "audio", "title"))
                    .containsExactly(
                            "Clocks",
                            "My podcast title with false is_music",
                            "My podcast title with not set is_music");

            // Artists and albums show only music files.
            assertThat(queryValues(helper, "audio_artists", "artist"))
                    .containsExactly("Coldplay");
            assertThat(queryValues(helper, "audio_albums", "album"))
                    .containsExactly("A Rush of Blood");

            // Genres should show all genres.
            assertThat(queryValues(helper, "audio_genres", "name"))
                    .containsExactly("Rock", "Podcast", "Podcast 2");
        }
    }

    @Test
    public void testTransactions() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperR(sIsolatedContext, TEST_CLEAN_DB)) {
            helper.beginTransaction();
            try {
                helper.setTransactionSuccessful();
            } finally {
                helper.endTransaction();
            }

            helper.runWithTransaction((db) -> {
                return 0;
            });
        }
    }

    @Test
    public void testUtoR() throws Exception {
        assertDowngrade(DatabaseHelperU.class, DatabaseHelperR.class);
    }

    @Test
    public void testUtoS() throws Exception {
        assertDowngrade(DatabaseHelperU.class, DatabaseHelperS.class);
    }

    @Test
    public void testUtoT() throws Exception {
        assertDowngrade(DatabaseHelperU.class, DatabaseHelperT.class);
    }

    private void assertDowngrade(Class<? extends DatabaseHelper> before,
            Class<? extends DatabaseHelper> after) throws Exception {
        try (DatabaseHelper helper = before.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            assertThat(sIsolatedContext.getDatabasePath(TEST_DOWNGRADE_DB).exists()).isTrue();
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA,
                        "/storage/emulated/0/DCIM/global.jpg");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                values.put(FileColumns.DISPLAY_NAME, "global.jpg");
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
                assertFalse(db.insert("files", FileColumns.DATA, values) == -1);
            }
            try (Cursor c = db.query("files", null, null, null, null, null, null, null)) {
                assertEquals(1, c.getCount());
            }
        }

        // Downgrade will wipe data, but at least we don't crash
        try (DatabaseHelper helper = after.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            try (Cursor c = db.query("files", null, null, null, null, null, null, null)) {
                assertEquals(0, c.getCount());
            }
        }
    }

    @Test
    public void testUtoTDowngradeWithStableUrisEnabledRecoversData() throws Exception {
        assertDowngradeWithStableUrisEnabledRecoversData(DatabaseHelperU.class,
                DatabaseHelperT.class);
    }

    private void assertDowngradeWithStableUrisEnabledRecoversData(
            Class<? extends DatabaseHelper> before,
            Class<? extends DatabaseHelper> after) throws Exception {
        Map<String, BackupIdRow> backedUpData = new HashMap<>();
        backedUpData.put("/product/media/audio/alarms/a.ogg", BackupIdRow.newBuilder(1).setIsDirty(
                false).build());
        backedUpData.put("/product/media/audio/alarms/b.ogg",
                BackupIdRow.newBuilder(2).setIsDirty(false).build());
        backedUpData.put("/product/media/audio/alarms/c.ogg", BackupIdRow.newBuilder(3).setIsDirty(
                false).build());
        backedUpData.put("/product/media/audio/alarms/d.ogg", BackupIdRow.newBuilder(4).setIsDirty(
                false).build());
        backedUpData.put("/product/media/audio/alarms/e.ogg", BackupIdRow.newBuilder(5).setIsDirty(
                true).build());
        sIsolatedContext = new IsolatedContext(
                InstrumentationRegistry.getTargetContext(), TAG, /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        Map<String, Long> pathToIdMap = new HashMap<>();
        DatabaseBackupAndRecovery testDatabaseBackupAndRecovery = new TestDatabaseBackupAndRecovery(
                new TestConfigStore(),
                new VolumeCache(sIsolatedContext, new UserCache(sIsolatedContext)), backedUpData);
        try (DatabaseHelper helper = before.getConstructor(Context.class, String.class,
                        DatabaseBackupAndRecovery.class)
                .newInstance(sIsolatedContext, "internal.db", testDatabaseBackupAndRecovery)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            assertThat(sIsolatedContext.getDatabasePath("internal.db").exists()).isTrue();

            // Insert 5 files
            pathToIdMap.put("/product/media/audio/alarms/a.ogg",
                    insertInInternal(db, "/product/media/audio/alarms/a.ogg", "a.ogg"));
            pathToIdMap.put("/product/media/audio/alarms/b.ogg",
                    insertInInternal(db, "/product/media/audio/alarms/b.ogg", "b.ogg"));
            pathToIdMap.put("/product/media/audio/alarms/c.ogg",
                    insertInInternal(db, "/product/media/audio/alarms/c.ogg", "c.ogg"));
            pathToIdMap.put("/product/media/audio/alarms/d.ogg",
                    insertInInternal(db, "/product/media/audio/alarms/d.ogg", "d.ogg"));
            pathToIdMap.put("/product/media/audio/alarms/e.ogg",
                    insertInInternal(db, "/product/media/audio/alarms/e.ogg", "e.ogg"));

            try (Cursor c = db.query("files", null, null, null, null, null, null, null)) {
                assertEquals(5, c.getCount());
            }
        }

        // Downgrade will wipe data, and recover non-dirty rows from backup
        try (DatabaseHelper helper = after.getConstructor(Context.class, String.class,
                        DatabaseBackupAndRecovery.class)
                .newInstance(sIsolatedContext, "internal.db", testDatabaseBackupAndRecovery)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            assertThat(sIsolatedContext.getDatabasePath("internal.db").exists()).isTrue();
            try (Cursor c = db.query("files", new String[]{FileColumns._ID, FileColumns.DATA}, null,
                    null, null, null, null, null)) {
                assertEquals(4, c.getCount());
                while (c.moveToNext()) {
                    assertThat(c.getLong(0)).isEqualTo(pathToIdMap.get(c.getString(1)));
                }
            }
        }
    }

    private long insertInInternal(SQLiteDatabase db, String path, String displayName) {
        final ContentValues values = new ContentValues();
        values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
        values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
        values.put(FileColumns.DISPLAY_NAME, displayName);
        values.put(FileColumns.VOLUME_NAME, "internal");
        long id = db.insert("files", FileColumns.DATA, values);
        assertFalse(id == -1);
        return id;
    }

    @Test
    public void testRtoU() throws Exception {
        assertUpgrade(DatabaseHelperR.class, DatabaseHelperU.class);
    }

    @Test
    public void testStoU() throws Exception {
        assertUpgrade(DatabaseHelperS.class, DatabaseHelperU.class);
    }

    @Test
    public void testTtoU() throws Exception {
        assertUpgrade(DatabaseHelperT.class, DatabaseHelperU.class);
    }

    private void assertRecompute(Class<? extends DatabaseHelper> before,
            Class<? extends DatabaseHelper> after) throws Exception {
        try (DatabaseHelper helper = before.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_RECOMPUTE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA,
                        "/storage/emulated/0/DCIM/global.jpg");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                values.put(FileColumns.DISPLAY_NAME, "global.jpg");
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
                values.put(FileColumns.MIME_TYPE, "image/jpeg");
                assertFalse(db.insert("files", FileColumns.DATA, values) == -1);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA,
                        "/storage/emulated/0/Android/media/com.example/app.jpg");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                values.put(FileColumns.DISPLAY_NAME, "app.jpg");
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
                values.put(FileColumns.MIME_TYPE, "image/jpeg");
                assertFalse(db.insert("files", FileColumns.DATA, values) == -1);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA,
                        "/storage/emulated/0/Download/colors.txt");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                values.put(FileColumns.DISPLAY_NAME, "colors.txt");
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_NONE);
                values.put(FileColumns.MIME_TYPE, "text/plain");
                assertFalse(db.insert("files", FileColumns.DATA, values) == -1);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA,
                        "/storage/emulated/0/Download/foo");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                assertFalse(db.insert("files", FileColumns.DATA, values) == -1);
            }
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA, "/storage/emulated/0/Download/bar");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                assertFalse(db.insert("files", FileColumns.DATA, values) == -1);
            }
        }

        try (DatabaseHelper helper = after.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_RECOMPUTE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            try (Cursor c = db.query("files", null, FileColumns.DISPLAY_NAME + "='global.jpg'",
                    null, null, null, null)) {
                assertEquals(1, c.getCount());
                assertTrue(c.moveToFirst());
                assertEquals("/storage/emulated/0/DCIM/global.jpg",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.DATA)));
                assertEquals(null,
                        c.getString(c.getColumnIndexOrThrow(FileColumns.OWNER_PACKAGE_NAME)));
                assertEquals("0", c.getString(c.getColumnIndexOrThrow(FileColumns.IS_DOWNLOAD)));
            }
            try (Cursor c = db.query("files", null, FileColumns.DISPLAY_NAME + "='app.jpg'",
                    null, null, null, null)) {
                assertEquals(1, c.getCount());
                assertTrue(c.moveToFirst());
                assertEquals("/storage/emulated/0/Android/media/com.example/app.jpg",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.DATA)));
                assertEquals("com.example",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.OWNER_PACKAGE_NAME)));
                assertEquals("0", c.getString(c.getColumnIndexOrThrow(FileColumns.IS_DOWNLOAD)));
            }
            try (Cursor c = db.query("files", null, FileColumns.DISPLAY_NAME + "='colors.txt'",
                    null, null, null, null)) {
                assertEquals(1, c.getCount());
                assertTrue(c.moveToFirst());
                assertEquals("/storage/emulated/0/Download/colors.txt",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.DATA)));
                assertEquals("text/plain",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)));
                assertEquals(null,
                        c.getString(c.getColumnIndexOrThrow(FileColumns.OWNER_PACKAGE_NAME)));
                assertEquals("1", c.getString(c.getColumnIndexOrThrow(FileColumns.IS_DOWNLOAD)));
            }
            try (Cursor c = db.query("files", null,
                    FileColumns.DATA + "='/storage/emulated/0/Download/foo'",
                    null, null, null, null)) {
                assertEquals(1, c.getCount());
                assertTrue(c.moveToFirst());
                assertNull(c.getString(c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)));
                assertEquals("foo", c.getString(c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)));
                assertEquals("1", c.getString(c.getColumnIndexOrThrow(FileColumns.IS_DOWNLOAD)));
            }
            try (Cursor c = db.query("files", null,
                    FileColumns.DATA + "='/storage/emulated/0/Download/bar'",
                    null, null, null, null)) {
                assertEquals(1, c.getCount());
                assertTrue(c.moveToFirst());
                assertNull(c.getString(c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)));
                assertEquals("bar", c.getString(c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)));
                assertEquals("1", c.getString(c.getColumnIndexOrThrow(FileColumns.IS_DOWNLOAD)));
            }
        }
    }

    private void assertUpgrade(Class<? extends DatabaseHelper> before,
            Class<? extends DatabaseHelper> after) throws Exception {
        try (DatabaseHelper helper = before.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
        }

        try (DatabaseHelper helper = after.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();

            // Create a second isolated instance from scratch and assert that
            // upgraded schema is identical
            try (DatabaseHelper helper2 = after.getConstructor(Context.class, String.class)
                    .newInstance(sIsolatedContext, TEST_CLEAN_DB)) {
                SQLiteDatabase db2 = helper2.getWritableDatabaseForTest();

                try (Cursor c1 = db.query("sqlite_master",
                        null, null, null, null, null, SQLITE_MASTER_ORDER_BY);
                        Cursor c2 = db2.query("sqlite_master",
                                null, null, null, null, null, SQLITE_MASTER_ORDER_BY)) {
                    while (c1.moveToNext() && c2.moveToNext()) {
                        final String sql1 = normalize(c1.getString(4));
                        final String sql2 = normalize(c2.getString(4));
                        Log.v(TAG, String.valueOf(sql1));
                        Log.v(TAG, String.valueOf(sql2));
                        assertEquals(sql2, sql1);

                        assertEquals(c2.getString(0), c1.getString(0));
                        assertEquals(c2.getString(1), c1.getString(1));
                        assertEquals(c2.getString(2), c1.getString(2));
                    }
                    assertEquals(c1.getCount(), c2.getCount());
                }
            }
        }
    }

    /**
     * Test that existing database rows will default to _modifier=MODIFIER_MEDIA_SCAN
     * after database upgrade.
     */
    @Test
    public void testUpgradeAndAddModifier() throws Exception {
        Class<? extends DatabaseHelper> beforeModifier = DatabaseHelperR.class;
        Class<? extends DatabaseHelper> afterModifier = DatabaseHelperS.class;

        try (DatabaseHelper helper = beforeModifier.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            {
                // Insert a row before database upgrade.
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA, "/storage/emulated/0/DCIM/test.jpg");
                assertThat(db.insert("files", FileColumns.DATA, values)).isNotEqualTo(-1);
            }
        }

        try (DatabaseHelper helper = afterModifier.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();

            try (Cursor cr = db.query("files", new String[]{FileColumns._MODIFIER}, null, null,
                    null, null, null)) {
                assertEquals(cr.getCount(), 1);
                while (cr.moveToNext()) {
                    // Verify that after db upgrade, for existing database rows, we set value of
                    // _modifier=MODIFIER_MEDIA_SCAN
                    assertThat(cr.getInt(0)).isEqualTo(FileColumns._MODIFIER_MEDIA_SCAN);
                }
            }
        }
    }

    @Test
    public void testAddUserId() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperR(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            {
                // Insert a row before database upgrade.
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA, "/storage/emulated/0/DCIM/test.jpg");
                assertThat(db.insert("files", FileColumns.DATA, values)).isNotEqualTo(-1);
            }
        }

        try (DatabaseHelper helper = new DatabaseHelperS(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            // Insert a row in the new version as well
            final ContentValues values = new ContentValues();
            values.put(FileColumns.DATA, "/storage/emulated/0/DCIM/test2.jpg");
            assertThat(db.insert("files", FileColumns.DATA, values)).isNotEqualTo(-1);

            try (Cursor cr = db.query("files", new String[]{FileColumns._USER_ID}, null, null,
                    null, null, null)) {
                assertEquals(2, cr.getCount());
                while (cr.moveToNext()) {
                    // Verify that after db upgrade, for all database rows (new inserts and
                    // upgrades), we set the _user_id
                    assertThat(cr.getInt(0)).isEqualTo(UserHandle.myUserId());
                }
            }
        }
    }

    @Test
    public void testAddSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperS(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            {
                // Insert a row before database upgrade.
                final ContentValues values = new ContentValues();
                values.put(FileColumns.DATA, "/storage/emulated/0/DCIM/test.jpg");
                assertThat(db.insert("files", FileColumns.DATA, values)).isNotEqualTo(-1);
            }
        }

        try (DatabaseHelper helper = new DatabaseHelperT(sIsolatedContext, TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            // Insert a row in the new version as well
            final ContentValues values = new ContentValues();
            values.put(FileColumns.DATA, "/storage/emulated/0/DCIM/test2.jpg");
            assertThat(db.insert("files", FileColumns.DATA, values)).isNotEqualTo(-1);

            try (Cursor cr = db.query("files", new String[]{FileColumns._SPECIAL_FORMAT}, null,
                    null, null, null, null)) {
                assertEquals(2, cr.getCount());
                while (cr.moveToNext()) {
                    // Verify that after db upgrade, for all database rows (new inserts and
                    // upgrades), we set _special_format column as NULL
                    assertThat(cr.isNull(0)).isTrue();
                }
            }
        }
    }

    /**
     * Test that database downgrade changed the UUID saved in database file.
     */
    @Test
    public void testDowngradeChangesUUID() throws Exception {
        Class<? extends DatabaseHelper> dbVersionHigher = DatabaseHelperT.class;
        Class<? extends DatabaseHelper> dbVersionLower = DatabaseHelperS.class;
        String originalUUID;
        int originalVersion;
        // Create the database with database version = dbVersionLower
        try (DatabaseHelper helper = dbVersionLower.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            originalUUID = DatabaseHelper.getOrCreateUuid(db);
            originalVersion = db.getVersion();
            // Verify that original version of the database is dbVersionLower.
            assertWithMessage("Current database version")
                    .that(db.getVersion()).isEqualTo(DatabaseHelper.VERSION_S);
        }
        // Upgrade the database by changing the version to dbVersionHigher
        try (DatabaseHelper helper = dbVersionHigher.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            // Verify that upgrade resulted in database version change.
            assertWithMessage("Current database version after upgrade")
                    .that(db.getVersion()).isNotEqualTo(originalVersion);
            // Verify that upgrade resulted in database version same as latest version.
            assertWithMessage("Current database version after upgrade")
                    .that(db.getVersion()).isEqualTo(DatabaseHelper.VERSION_T);
            // Verify that upgrade didn't change UUID
            assertWithMessage("Current database UUID after upgrade")
                    .that(DatabaseHelper.getOrCreateUuid(db)).isEqualTo(originalUUID);
        }
        // Downgrade the database by changing the version to dbVersionLower
        try (DatabaseHelper helper = dbVersionLower.getConstructor(Context.class, String.class)
                .newInstance(sIsolatedContext, TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabaseForTest();
            // Verify that downgraded version is same as original database version before upgrade
            assertWithMessage("Current database version after downgrade")
                    .that(db.getVersion()).isEqualTo(originalVersion);
            // Verify that downgrade changed UUID
            assertWithMessage("Current database UUID after downgrade")
                    .that(DatabaseHelper.getOrCreateUuid(db)).isNotEqualTo(originalUUID);
        }
    }

    private static String normalize(String sql) {
        return sql != null ? sql.replace(", ", ",") : null;
    }

    private static Set<String> queryValues(@NonNull DatabaseHelper helper, @NonNull String table,
            @NonNull String columnName) {
        try (Cursor c = helper.getWritableDatabaseForTest().query(table,
                new String[]{columnName}, null, null, null, null, null)) {
            final ArraySet<String> res = new ArraySet<>();
            while (c.moveToNext()) {
                res.add(c.getString(0));
            }
            return res;
        }
    }

    private static class DatabaseHelperO extends DatabaseHelper {
        public DatabaseHelperO(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_O, false, false, sProjectionHelper, null,
                    null, null, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createOSchema(db, false);
        }
    }

    private static class DatabaseHelperP extends DatabaseHelper {
        public DatabaseHelperP(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_P, false, false, sProjectionHelper, null,
                    null, null, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createPSchema(db, false);
        }
    }

    private static class DatabaseHelperQ extends DatabaseHelper {
        public DatabaseHelperQ(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_Q, false, false, sProjectionHelper, null,
                    null, null, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createQSchema(db, false);
        }
    }

    private static class DatabaseHelperR extends DatabaseHelper {
        public DatabaseHelperR(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_R, false, false, sProjectionHelper, null,
                    null, MediaProvider.MIGRATION_LISTENER, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createRSchema(db, false);
        }
    }

    private static class DatabaseHelperS extends DatabaseHelper {
        public DatabaseHelperS(Context context, String name) {
            super(context, name, VERSION_S, false, false, sProjectionHelper, null,
                    null, MediaProvider.MIGRATION_LISTENER, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createSSchema(db, false);
        }
    }

    private static class DatabaseHelperT extends DatabaseHelper {
        public DatabaseHelperT(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_T, false, false, sProjectionHelper, null,
                    null, MediaProvider.MIGRATION_LISTENER, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        public DatabaseHelperT(Context context, String name,
                DatabaseBackupAndRecovery databaseBackupAndRecovery) {
            super(context, name, DatabaseHelper.VERSION_T, false, false, sProjectionHelper, null,
                    null, MediaProvider.MIGRATION_LISTENER, null, false, databaseBackupAndRecovery);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createTSchema(db, false);
        }

        @Override
        protected String getExternalStorageDbXattrPath() {
            return mContext.getFilesDir().getPath();
        }
    }

    private static class DatabaseHelperU extends DatabaseHelper {
        public DatabaseHelperU(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_U, false, false, sProjectionHelper, null,
                    null, MediaProvider.MIGRATION_LISTENER, null, false,
                    new DatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context))));
        }

        public DatabaseHelperU(Context context, String name,
                DatabaseBackupAndRecovery databaseBackupAndRecovery) {
            super(context, name, DatabaseHelper.VERSION_U, false, false, sProjectionHelper, null,
                    null, MediaProvider.MIGRATION_LISTENER, null, false, databaseBackupAndRecovery);
        }

        @Override
        protected String getExternalStorageDbXattrPath() {
            return mContext.getFilesDir().getPath();
        }
    }

    /**
     * Snapshot of {@link MediaProvider#createLatestSchema} as of
     * {@link android.os.Build.VERSION_CODES#O}.
     */
    private static void createOSchema(SQLiteDatabase db, boolean internal) {
        makePristineSchema(db);

        // CAUTION: THIS IS A SNAPSHOTTED GOLDEN SCHEMA THAT SHOULD NEVER BE
        // DIRECTLY MODIFIED, SINCE IT REPRESENTS A DEVICE IN THE WILD THAT WE
        // MUST SUPPORT. IF TESTS ARE FAILING, THE CORRECT FIX IS TO ADJUST THE
        // DATABASE UPGRADE LOGIC TO MIGRATE THIS SNAPSHOTTED GOLDEN SCHEMA TO
        // THE LATEST SCHEMA.

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE artists (artist_id INTEGER PRIMARY KEY,"
                + "artist_key TEXT NOT NULL UNIQUE,artist TEXT NOT NULL)");
        db.execSQL("CREATE TABLE albums (album_id INTEGER PRIMARY KEY,"
                + "album_key TEXT NOT NULL UNIQUE,album TEXT NOT NULL)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER)");
        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!internal) {
            db.execSQL("CREATE TABLE audio_genres (_id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            db.execSQL("CREATE TABLE audio_genres_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,genre_id INTEGER NOT NULL,"
                    + "UNIQUE (audio_id,genre_id) ON CONFLICT IGNORE)");
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
            db.execSQL("CREATE TRIGGER audio_genres_cleanup DELETE ON audio_genres BEGIN DELETE"
                    + " FROM audio_genres_map WHERE genre_id = old._id;END");
            db.execSQL("CREATE TRIGGER audio_playlists_cleanup DELETE ON files"
                    + " WHEN old.media_type=4"
                    + " BEGIN DELETE FROM audio_playlists_map WHERE playlist_id = old._id;"
                    + "SELECT _DELETE_FILE(old._data);END");
            db.execSQL("CREATE TRIGGER files_cleanup DELETE ON files"
                    + " BEGIN SELECT _OBJECT_REMOVED(old._id);END");
            db.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,date_modified"
                    + " FROM files WHERE media_type=4");
        }

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX album_idx on albums(album)");
        db.execSQL("CREATE INDEX albumkey_index on albums(album_key)");
        db.execSQL("CREATE INDEX artist_idx on artists(artist)");
        db.execSQL("CREATE INDEX artistkey_index on artists(artist_key)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");

        db.execSQL("CREATE VIEW audio_meta AS SELECT _id,_data,_display_name,_size,mime_type,"
                + "date_added,is_drm,date_modified,title,title_key,duration,artist_id,composer,"
                + "album_id,track,year,is_ringtone,is_music,is_alarm,is_notification,is_podcast,"
                + "bookmark,album_artist FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW artists_albums_map AS SELECT DISTINCT artist_id, album_id"
                + " FROM audio_meta");
        db.execSQL("CREATE VIEW audio as SELECT * FROM audio_meta LEFT OUTER JOIN artists"
                + " ON audio_meta.artist_id=artists.artist_id LEFT OUTER JOIN albums"
                + " ON audio_meta.album_id=albums.album_id");
        db.execSQL("CREATE VIEW album_info AS SELECT audio.album_id AS _id, album, album_key,"
                + " MIN(year) AS minyear, MAX(year) AS maxyear, artist, artist_id, artist_key,"
                + " count(*) AS numsongs,album_art._data AS album_art FROM audio"
                + " LEFT OUTER JOIN album_art ON audio.album_id=album_art.album_id WHERE is_music=1"
                + " GROUP BY audio.album_id");
        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW artist_info AS SELECT artist_id AS _id, artist, artist_key,"
                + " COUNT(DISTINCT album_key) AS number_of_albums, COUNT(*) AS number_of_tracks"
                + " FROM audio"
                + " WHERE is_music=1 GROUP BY artist_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");
        db.execSQL("CREATE VIEW audio_genres_map_noid AS SELECT audio_id,genre_id"
                + " FROM audio_genres_map");
        db.execSQL("CREATE VIEW images AS SELECT _id,_data,_size,_display_name,mime_type,title,"
                + "date_added,date_modified,description,picasa_id,isprivate,latitude,longitude,"
                + "datetaken,orientation,mini_thumb_magic,bucket_id,bucket_display_name,width,"
                + "height FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW video AS SELECT _id,_data,_display_name,_size,mime_type,"
                + "date_added,date_modified,title,duration,artist,album,resolution,description,"
                + "isprivate,tags,category,language,mini_thumb_data,latitude,longitude,datetaken,"
                + "mini_thumb_magic,bucket_id,bucket_display_name,bookmark,width,height"
                + " FROM files WHERE media_type=3");

        db.execSQL("CREATE TRIGGER albumart_cleanup1 DELETE ON albums BEGIN DELETE FROM album_art"
                + " WHERE album_id = old.album_id;END");
        db.execSQL("CREATE TRIGGER albumart_cleanup2 DELETE ON album_art"
                + " BEGIN SELECT _DELETE_FILE(old._data);END");
    }

    /**
     * Snapshot of {@link MediaProvider#createLatestSchema} as of
     * {@link android.os.Build.VERSION_CODES#P}.
     */
    private static void createPSchema(SQLiteDatabase db, boolean internal) {
        makePristineSchema(db);

        // CAUTION: THIS IS A SNAPSHOTTED GOLDEN SCHEMA THAT SHOULD NEVER BE
        // DIRECTLY MODIFIED, SINCE IT REPRESENTS A DEVICE IN THE WILD THAT WE
        // MUST SUPPORT. IF TESTS ARE FAILING, THE CORRECT FIX IS TO ADJUST THE
        // DATABASE UPGRADE LOGIC TO MIGRATE THIS SNAPSHOTTED GOLDEN SCHEMA TO
        // THE LATEST SCHEMA.

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE artists (artist_id INTEGER PRIMARY KEY,"
                + "artist_key TEXT NOT NULL UNIQUE,artist TEXT NOT NULL)");
        db.execSQL("CREATE TABLE albums (album_id INTEGER PRIMARY KEY,"
                + "album_key TEXT NOT NULL UNIQUE,album TEXT NOT NULL)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT)");
        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!internal) {
            db.execSQL("CREATE TABLE audio_genres (_id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            db.execSQL("CREATE TABLE audio_genres_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,genre_id INTEGER NOT NULL,"
                    + "UNIQUE (audio_id,genre_id) ON CONFLICT IGNORE)");
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
            db.execSQL("CREATE TRIGGER audio_genres_cleanup DELETE ON audio_genres BEGIN DELETE"
                    + " FROM audio_genres_map WHERE genre_id = old._id;END");
            db.execSQL("CREATE TRIGGER audio_playlists_cleanup DELETE ON files"
                    + " WHEN old.media_type=4"
                    + " BEGIN DELETE FROM audio_playlists_map WHERE playlist_id = old._id;"
                    + "SELECT _DELETE_FILE(old._data);END");
            db.execSQL("CREATE TRIGGER files_cleanup DELETE ON files"
                    + " BEGIN SELECT _OBJECT_REMOVED(old._id);END");
            db.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,date_modified"
                    + " FROM files WHERE media_type=4");
        }

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX album_idx on albums(album)");
        db.execSQL("CREATE INDEX albumkey_index on albums(album_key)");
        db.execSQL("CREATE INDEX artist_idx on artists(artist)");
        db.execSQL("CREATE INDEX artistkey_index on artists(artist_key)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");

        db.execSQL("CREATE VIEW audio_meta AS SELECT _id,_data,_display_name,_size,mime_type,"
                + "date_added,is_drm,date_modified,title,title_key,duration,artist_id,composer,"
                + "album_id,track,year,is_ringtone,is_music,is_alarm,is_notification,is_podcast,"
                + "bookmark,album_artist FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW artists_albums_map AS SELECT DISTINCT artist_id, album_id"
                + " FROM audio_meta");
        db.execSQL("CREATE VIEW audio as SELECT * FROM audio_meta LEFT OUTER JOIN artists"
                + " ON audio_meta.artist_id=artists.artist_id LEFT OUTER JOIN albums"
                + " ON audio_meta.album_id=albums.album_id");
        db.execSQL("CREATE VIEW album_info AS SELECT audio.album_id AS _id, album, album_key,"
                + " MIN(year) AS minyear, MAX(year) AS maxyear, artist, artist_id, artist_key,"
                + " count(*) AS numsongs,album_art._data AS album_art FROM audio"
                + " LEFT OUTER JOIN album_art ON audio.album_id=album_art.album_id WHERE is_music=1"
                + " GROUP BY audio.album_id");
        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW artist_info AS SELECT artist_id AS _id, artist, artist_key,"
                + " COUNT(DISTINCT album_key) AS number_of_albums, COUNT(*) AS number_of_tracks"
                + " FROM audio"
                + " WHERE is_music=1 GROUP BY artist_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");
        db.execSQL("CREATE VIEW audio_genres_map_noid AS SELECT audio_id,genre_id"
                + " FROM audio_genres_map");
        db.execSQL("CREATE VIEW images AS SELECT _id,_data,_size,_display_name,mime_type,title,"
                + "date_added,date_modified,description,picasa_id,isprivate,latitude,longitude,"
                + "datetaken,orientation,mini_thumb_magic,bucket_id,bucket_display_name,width,"
                + "height FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW video AS SELECT _id,_data,_display_name,_size,mime_type,"
                + "date_added,date_modified,title,duration,artist,album,resolution,description,"
                + "isprivate,tags,category,language,mini_thumb_data,latitude,longitude,datetaken,"
                + "mini_thumb_magic,bucket_id,bucket_display_name,bookmark,width,height"
                + " FROM files WHERE media_type=3");

        db.execSQL("CREATE TRIGGER albumart_cleanup1 DELETE ON albums BEGIN DELETE FROM album_art"
                + " WHERE album_id = old.album_id;END");
        db.execSQL("CREATE TRIGGER albumart_cleanup2 DELETE ON album_art"
                + " BEGIN SELECT _DELETE_FILE(old._data);END");
    }

    /**
     * Snapshot of {@link MediaProvider#createLatestSchema} as of
     * {@link android.os.Build.VERSION_CODES#Q}.
     */
    private static void createQSchema(SQLiteDatabase db, boolean internal) {
        makePristineSchema(db);

        // CAUTION: THIS IS A SNAPSHOTTED GOLDEN SCHEMA THAT SHOULD NEVER BE
        // DIRECTLY MODIFIED, SINCE IT REPRESENTS A DEVICE IN THE WILD THAT WE
        // MUST SUPPORT. IF TESTS ARE FAILING, THE CORRECT FIX IS TO ADJUST THE
        // DATABASE UPGRADE LOGIC TO MIGRATE THIS SNAPSHOTTED GOLDEN SCHEMA TO
        // THE LATEST SCHEMA.

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE artists (artist_id INTEGER PRIMARY KEY,"
                + "artist_key TEXT NOT NULL UNIQUE,artist TEXT NOT NULL)");
        db.execSQL("CREATE TABLE albums (album_id INTEGER PRIMARY KEY,"
                + "album_key TEXT NOT NULL UNIQUE,album TEXT NOT NULL)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,"
                + "_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,"
                + "is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,"
                + "referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,"
                + "date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,"
                + "group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,"
                + "secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,"
                + "instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,"
                + "relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL)");

        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!internal) {
            db.execSQL("CREATE TABLE audio_genres (_id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            db.execSQL("CREATE TABLE audio_genres_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,genre_id INTEGER NOT NULL,"
                    + "UNIQUE (audio_id,genre_id) ON CONFLICT IGNORE)");
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
            db.execSQL("CREATE TRIGGER audio_genres_cleanup DELETE ON audio_genres BEGIN DELETE"
                    + " FROM audio_genres_map WHERE genre_id = old._id;END");
            db.execSQL("CREATE TRIGGER audio_playlists_cleanup DELETE ON files"
                    + " WHEN old.media_type=4"
                    + " BEGIN DELETE FROM audio_playlists_map WHERE playlist_id = old._id;"
                    + "SELECT _DELETE_FILE(old._data);END");
            db.execSQL("CREATE TRIGGER files_cleanup DELETE ON files"
                    + " BEGIN SELECT _OBJECT_REMOVED(old._id);END");
        }

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX album_idx on albums(album)");
        db.execSQL("CREATE INDEX albumkey_index on albums(album_key)");
        db.execSQL("CREATE INDEX artist_idx on artists(artist)");
        db.execSQL("CREATE INDEX artistkey_index on artists(artist_key)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");

        db.execSQL("CREATE TRIGGER albumart_cleanup1 DELETE ON albums BEGIN DELETE FROM album_art"
                + " WHERE album_id = old.album_id;END");
        db.execSQL("CREATE TRIGGER albumart_cleanup2 DELETE ON album_art"
                + " BEGIN SELECT _DELETE_FILE(old._data);END");

        if (!internal) {
            db.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,"
                    + "date_modified,owner_package_name,_hash,is_pending,date_expires,is_trashed,"
                    + "volume_name FROM files WHERE media_type=4");
        }

        db.execSQL("CREATE VIEW audio_meta AS SELECT _id,_data,_display_name,_size,mime_type,"
                + "date_added,is_drm,date_modified,title,title_key,duration,artist_id,composer,"
                + "album_id,track,year,is_ringtone,is_music,is_alarm,is_notification,is_podcast,"
                + "bookmark,album_artist,owner_package_name,_hash,is_pending,is_audiobook,"
                + "date_expires,is_trashed,group_id,primary_directory,secondary_directory,"
                + "document_id,instance_id,original_document_id,title_resource_uri,relative_path,"
                + "volume_name,datetaken,bucket_id,bucket_display_name,group_id,orientation"
                + " FROM files WHERE media_type=2");

        db.execSQL("CREATE VIEW artists_albums_map AS SELECT DISTINCT artist_id, album_id"
                + " FROM audio_meta");
        db.execSQL("CREATE VIEW audio as SELECT *, NULL AS width, NULL as height"
                + " FROM audio_meta LEFT OUTER JOIN artists"
                + " ON audio_meta.artist_id=artists.artist_id LEFT OUTER JOIN albums"
                + " ON audio_meta.album_id=albums.album_id");
        db.execSQL("CREATE VIEW album_info AS SELECT audio.album_id AS _id, album, album_key,"
                + " MIN(year) AS minyear, MAX(year) AS maxyear, artist, artist_id, artist_key,"
                + " count(*) AS numsongs,album_art._data AS album_art FROM audio"
                + " LEFT OUTER JOIN album_art ON audio.album_id=album_art.album_id WHERE is_music=1"
                + " GROUP BY audio.album_id");
        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW artist_info AS SELECT artist_id AS _id, artist, artist_key,"
                + " COUNT(DISTINCT album_key) AS number_of_albums, COUNT(*) AS number_of_tracks"
                + " FROM audio"
                + " WHERE is_music=1 GROUP BY artist_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");
        db.execSQL("CREATE VIEW audio_genres_map_noid AS SELECT audio_id,genre_id"
                + " FROM audio_genres_map");

        db.execSQL("CREATE VIEW video AS SELECT "
                + "instance_id,duration,description,language,resolution,latitude,orientation,artist,color_transfer,color_standard,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,date_expires,_display_name,datetaken,mime_type,_id,tags,category,_data,_hash,_size,album,title,width,longitude,is_trashed,group_id,document_id,is_pending,date_added,mini_thumb_magic,color_range,primary_directory,secondary_directory,isprivate,original_document_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=3");
        db.execSQL("CREATE VIEW images AS SELECT "
                + "instance_id,duration,description,picasa_id,latitude,orientation,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,date_expires,_display_name,datetaken,mime_type,_id,_data,_hash,_size,title,width,longitude,is_trashed,group_id,document_id,is_pending,date_added,mini_thumb_magic,primary_directory,secondary_directory,isprivate,original_document_id,bucket_id,relative_path"
                + " FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW downloads AS SELECT "
                + "instance_id,duration,description,orientation,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,date_expires,_display_name,datetaken,mime_type,referer_uri,_id,_data,_hash,_size,title,width,is_trashed,group_id,document_id,is_pending,date_added,download_uri,primary_directory,secondary_directory,original_document_id,bucket_id,relative_path"
                + " FROM files WHERE is_download=1");
    }


    /**
     * Snapshot of {@link DatabaseHelper#createLatestSchema} as of
     * {@link android.os.Build.VERSION_CODES#R}.
     */
    private static void createRSchema(SQLiteDatabase db, boolean internal) {
        makePristineSchema(db);

        // CAUTION: THIS IS A SNAPSHOTTED GOLDEN SCHEMA THAT SHOULD NEVER BE
        // DIRECTLY MODIFIED, SINCE IT REPRESENTS A DEVICE IN THE WILD THAT WE
        // MUST SUPPORT. IF TESTS ARE FAILING, THE CORRECT FIX IS TO ADJUST THE
        // DATABASE UPGRADE LOGIC TO MIGRATE THIS SNAPSHOTTED GOLDEN SCHEMA TO
        // THE LATEST SCHEMA.

        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,"
                + "_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,"
                + "is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,"
                + "referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,"
                + "date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,"
                + "group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,"
                + "secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,"
                + "instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,"
                + "relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL,"
                + "artist_key TEXT DEFAULT NULL,album_key TEXT DEFAULT NULL,"
                + "genre TEXT DEFAULT NULL,genre_key TEXT DEFAULT NULL,genre_id INTEGER,"
                + "author TEXT DEFAULT NULL, bitrate INTEGER DEFAULT NULL,"
                + "capture_framerate REAL DEFAULT NULL, cd_track_number TEXT DEFAULT NULL,"
                + "compilation INTEGER DEFAULT NULL, disc_number TEXT DEFAULT NULL,"
                + "is_favorite INTEGER DEFAULT 0, num_tracks INTEGER DEFAULT NULL,"
                + "writer TEXT DEFAULT NULL, exposure_time TEXT DEFAULT NULL,"
                + "f_number TEXT DEFAULT NULL, iso INTEGER DEFAULT NULL,"
                + "scene_capture_type INTEGER DEFAULT NULL, generation_added INTEGER DEFAULT 0,"
                + "generation_modified INTEGER DEFAULT 0, xmp BLOB DEFAULT NULL)");

        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!internal) {
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
        }

        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");

        db.execSQL("CREATE VIEW audio AS SELECT "
                + "title_key,instance_id,compilation,disc_number,duration,is_ringtone,album_artist,resolution,orientation,artist,author,height,is_drm,bucket_display_name,is_audiobook,owner_package_name,volume_name,title_resource_uri,date_modified,writer,date_expires,composer,_display_name,datetaken,mime_type,is_notification,bitrate,cd_track_number,_id,xmp,year,_data,_size,album,genre,is_alarm,title,track,width,is_music,album_key,is_favorite,is_trashed,group_id,document_id,artist_id,generation_added,artist_key,genre_key,is_download,generation_modified,is_pending,date_added,is_podcast,capture_framerate,album_id,num_tracks,original_document_id,genre_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW video AS SELECT"
                +  " instance_id,compilation,disc_number,duration,album_artist,description,language,resolution,latitude,orientation,artist,color_transfer,author,color_standard,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,writer,date_expires,composer,_display_name,datetaken,mime_type,bitrate,cd_track_number,_id,xmp,tags,year,category,_data,_size,album,genre,title,width,longitude,is_favorite,is_trashed,group_id,document_id,generation_added,is_download,generation_modified,is_pending,date_added,mini_thumb_magic,capture_framerate,color_range,num_tracks,isprivate,original_document_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=3");
        db.execSQL("CREATE VIEW images AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,picasa_id,resolution,latitude,orientation,artist,author,height,is_drm,bucket_display_name,owner_package_name,f_number,volume_name,date_modified,writer,date_expires,composer,_display_name,scene_capture_type,datetaken,mime_type,bitrate,cd_track_number,_id,iso,xmp,year,_data,_size,album,genre,title,width,longitude,is_favorite,is_trashed,exposure_time,group_id,document_id,generation_added,is_download,generation_modified,is_pending,date_added,mini_thumb_magic,capture_framerate,num_tracks,isprivate,original_document_id,bucket_id,relative_path"
                + " FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW downloads AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,resolution,orientation,artist,author,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,writer,date_expires,composer,_display_name,datetaken,mime_type,bitrate,cd_track_number,referer_uri,_id,xmp,year,_data,_size,album,genre,title,width,is_favorite,is_trashed,group_id,document_id,generation_added,is_download,generation_modified,is_pending,date_added,download_uri,capture_framerate,num_tracks,original_document_id,bucket_id,relative_path"
                + " FROM files WHERE is_download=1");

        db.execSQL("CREATE VIEW audio_artists AS SELECT "
                + "  artist_id AS " + "_id"
                + ", MIN(artist) AS " + "artist"
                + ", artist_key AS " + "artist_key"
                + ", COUNT(DISTINCT album_id) AS " + "number_of_albums"
                + ", COUNT(DISTINCT _id) AS " + "number_of_tracks"
                + " FROM audio"
                + " WHERE is_music=1"
                + " GROUP BY artist_id");

        db.execSQL("CREATE VIEW audio_albums AS SELECT "
                + "  album_id AS " + "_id"
                + ", album_id AS " + "album_id"
                + ", MIN(album) AS " + "album"
                + ", album_key AS " + "album_key"
                + ", artist_id AS " + "artist_id"
                + ", artist AS " + "artist"
                + ", artist_key AS " + "artist_key"
                + ", COUNT(DISTINCT _id) AS " + "numsongs"
                + ", COUNT(DISTINCT _id) AS " + "numsongs_by_artist"
                + ", MIN(year) AS " + "minyear"
                + ", MAX(year) AS " + "maxyear"
                + ", NULL AS " + "album_art"
                + " FROM audio"
                + " WHERE is_music=1"
                + " GROUP BY album_id");

        db.execSQL("CREATE VIEW audio_genres AS SELECT "
                + "  genre_id AS " + "_id"
                + ", MIN(genre) AS " + "name"
                + " FROM audio"
                + " GROUP BY genre_id");

        final String insertArg =
                "new.volume_name||':'||new._id||':'||new.media_type||':'||new.is_download";
        final String updateArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||new._id||':'||new.media_type||':'||new.is_download"
                        + "||':'||ifnull(old.owner_package_name,'null')"
                        + "||':'||ifnull(new.owner_package_name,'null')||':'||old._data";
        final String deleteArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||ifnull(old.owner_package_name,'null')||':'||old._data";

        db.execSQL("CREATE TRIGGER files_insert AFTER INSERT ON files"
                + " BEGIN SELECT _INSERT(" + insertArg + "); END");
        db.execSQL("CREATE TRIGGER files_update AFTER UPDATE ON files"
                + " BEGIN SELECT _UPDATE(" + updateArg + "); END");
        db.execSQL("CREATE TRIGGER files_delete AFTER DELETE ON files"
                + " BEGIN SELECT _DELETE(" + deleteArg + "); END");

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");
    }

    /**
     * Snapshot of {@link DatabaseHelper#createLatestSchema} as of
     * {@link android.os.Build.VERSION_CODES#S}.
     */
    private static void createSSchema(SQLiteDatabase db, boolean internal) {
        makePristineSchema(db);

        // CAUTION: THIS IS A SNAPSHOTTED GOLDEN SCHEMA THAT SHOULD NEVER BE
        // DIRECTLY MODIFIED, SINCE IT REPRESENTS A DEVICE IN THE WILD THAT WE
        // MUST SUPPORT. IF TESTS ARE FAILING, THE CORRECT FIX IS TO ADJUST THE
        // DATABASE UPGRADE LOGIC TO MIGRATE THIS SNAPSHOTTED GOLDEN SCHEMA TO
        // THE LATEST SCHEMA.

        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,"
                + "_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,"
                + "is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,"
                + "referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,"
                + "date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,"
                + "group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,"
                + "secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,"
                + "instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,"
                + "relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL,"
                + "artist_key TEXT DEFAULT NULL,album_key TEXT DEFAULT NULL,"
                + "genre TEXT DEFAULT NULL,genre_key TEXT DEFAULT NULL,genre_id INTEGER,"
                + "author TEXT DEFAULT NULL, bitrate INTEGER DEFAULT NULL,"
                + "capture_framerate REAL DEFAULT NULL, cd_track_number TEXT DEFAULT NULL,"
                + "compilation INTEGER DEFAULT NULL, disc_number TEXT DEFAULT NULL,"
                + "is_favorite INTEGER DEFAULT 0, num_tracks INTEGER DEFAULT NULL,"
                + "writer TEXT DEFAULT NULL, exposure_time TEXT DEFAULT NULL,"
                + "f_number TEXT DEFAULT NULL, iso INTEGER DEFAULT NULL,"
                + "scene_capture_type INTEGER DEFAULT NULL, generation_added INTEGER DEFAULT 0,"
                + "generation_modified INTEGER DEFAULT 0, xmp BLOB DEFAULT NULL,"
                + "_transcode_status INTEGER DEFAULT 0, _video_codec_type TEXT DEFAULT NULL,"
                + "_modifier INTEGER DEFAULT 0, is_recording INTEGER DEFAULT 0,"
                + "redacted_uri_id TEXT DEFAULT NULL, _user_id INTEGER DEFAULT "
                + UserHandle.myUserId() + ")");

        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!internal) {
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
        }

        if (!internal) {
            db.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,"
                    + "date_modified,owner_package_name,_hash,is_pending,date_expires,is_trashed,"
                    + "volume_name FROM files WHERE media_type=4");
        }

        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");

        db.execSQL("CREATE VIEW audio AS SELECT "
                + "title_key,instance_id,compilation,disc_number,duration,is_ringtone,album_artist,resolution,orientation,artist,author,height,is_drm,bucket_display_name,is_audiobook,owner_package_name,volume_name,title_resource_uri,date_modified,writer,date_expires,composer,_display_name,datetaken,mime_type,is_notification,bitrate,cd_track_number,_id,xmp,year,_data,_size,album,genre,is_alarm,title,track,width,is_music,album_key,is_favorite,is_trashed,group_id,document_id,artist_id,generation_added,artist_key,genre_key,is_download,generation_modified,is_pending,date_added,is_podcast,capture_framerate,album_id,num_tracks,original_document_id,genre_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW video AS SELECT"
                +  " instance_id,compilation,disc_number,duration,album_artist,description,language,resolution,latitude,orientation,artist,color_transfer,author,color_standard,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,writer,date_expires,composer,_display_name,datetaken,mime_type,bitrate,cd_track_number,_id,xmp,tags,year,category,_data,_size,album,genre,title,width,longitude,is_favorite,is_trashed,group_id,document_id,generation_added,is_download,generation_modified,is_pending,date_added,mini_thumb_magic,capture_framerate,color_range,num_tracks,isprivate,original_document_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=3");
        db.execSQL("CREATE VIEW images AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,picasa_id,resolution,latitude,orientation,artist,author,height,is_drm,bucket_display_name,owner_package_name,f_number,volume_name,date_modified,writer,date_expires,composer,_display_name,scene_capture_type,datetaken,mime_type,bitrate,cd_track_number,_id,iso,xmp,year,_data,_size,album,genre,title,width,longitude,is_favorite,is_trashed,exposure_time,group_id,document_id,generation_added,is_download,generation_modified,is_pending,date_added,mini_thumb_magic,capture_framerate,num_tracks,isprivate,original_document_id,bucket_id,relative_path"
                + " FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW downloads AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,resolution,orientation,artist,author,height,is_drm,bucket_display_name,owner_package_name,volume_name,date_modified,writer,date_expires,composer,_display_name,datetaken,mime_type,bitrate,cd_track_number,referer_uri,_id,xmp,year,_data,_size,album,genre,title,width,is_favorite,is_trashed,group_id,document_id,generation_added,is_download,generation_modified,is_pending,date_added,download_uri,capture_framerate,num_tracks,original_document_id,bucket_id,relative_path"
                + " FROM files WHERE is_download=1");

        db.execSQL("CREATE VIEW audio_artists AS SELECT "
                + "  artist_id AS " + Audio.Artists._ID
                + ", MIN(artist) AS " + Audio.Artists.ARTIST
                + ", artist_key AS " + Audio.Artists.ARTIST_KEY
                + ", COUNT(DISTINCT album_id) AS " + Audio.Artists.NUMBER_OF_ALBUMS
                + ", COUNT(DISTINCT _id) AS " + Audio.Artists.NUMBER_OF_TRACKS
                + " FROM audio"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + "()"
                + " GROUP BY artist_id");

        db.execSQL("CREATE VIEW audio_artists_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", MIN(album) AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", (SELECT COUNT(*) FROM audio WHERE " + Audio.Albums.ALBUM_ID
                + " = TEMP.album_id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio TEMP"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + "()"
                + " GROUP BY album_id, artist_id");

        db.execSQL("CREATE VIEW audio_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", MIN(album) AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + "()"
                + " GROUP BY album_id");

        db.execSQL("CREATE VIEW audio_genres AS SELECT "
                + "  genre_id AS " + Audio.Genres._ID
                + ", MIN(genre) AS " + Audio.Genres.NAME
                + " FROM audio"
                + " WHERE is_pending=0 AND is_trashed=0 AND volume_name IN " + "()"
                + " GROUP BY genre_id");

        final String insertArg =
                "new.volume_name||':'||new._id||':'||new.media_type||':'||new.is_download";
        final String updateArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||new._id||':'||new.media_type||':'||new.is_download"
                        + "||':'||ifnull(old.owner_package_name,'null')"
                        + "||':'||ifnull(new.owner_package_name,'null')||':'||old._data";
        final String deleteArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||ifnull(old.owner_package_name,'null')||':'||old._data";

        db.execSQL("CREATE TRIGGER files_insert AFTER INSERT ON files"
                + " BEGIN SELECT _INSERT(" + insertArg + "); END");
        db.execSQL("CREATE TRIGGER files_update AFTER UPDATE ON files"
                + " BEGIN SELECT _UPDATE(" + updateArg + "); END");
        db.execSQL("CREATE TRIGGER files_delete AFTER DELETE ON files"
                + " BEGIN SELECT _DELETE(" + deleteArg + "); END");

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");
    }

    /**
     * Snapshot of {@link DatabaseHelper#createLatestSchema} as of
     * {@link android.os.Build.VERSION_CODES#T}.
     */
    private static void createTSchema(SQLiteDatabase db, boolean internal) {
        makePristineSchema(db);

        // CAUTION: THIS IS A SNAPSHOTTED GOLDEN SCHEMA THAT SHOULD NEVER BE
        // DIRECTLY MODIFIED, SINCE IT REPRESENTS A DEVICE IN THE WILD THAT WE
        // MUST SUPPORT. IF TESTS ARE FAILING, THE CORRECT FIX IS TO ADJUST THE
        // DATABASE UPGRADE LOGIC TO MIGRATE THIS SNAPSHOTTED GOLDEN SCHEMA TO
        // THE LATEST SCHEMA.

        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,"
                + "_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,"
                + "is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,"
                + "referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,"
                + "date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,"
                + "group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,"
                + "secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,"
                + "instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,"
                + "relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL,"
                + "artist_key TEXT DEFAULT NULL,album_key TEXT DEFAULT NULL,"
                + "genre TEXT DEFAULT NULL,genre_key TEXT DEFAULT NULL,genre_id INTEGER,"
                + "author TEXT DEFAULT NULL, bitrate INTEGER DEFAULT NULL,"
                + "capture_framerate REAL DEFAULT NULL, cd_track_number TEXT DEFAULT NULL,"
                + "compilation INTEGER DEFAULT NULL, disc_number TEXT DEFAULT NULL,"
                + "is_favorite INTEGER DEFAULT 0, num_tracks INTEGER DEFAULT NULL,"
                + "writer TEXT DEFAULT NULL, exposure_time TEXT DEFAULT NULL,"
                + "f_number TEXT DEFAULT NULL, iso INTEGER DEFAULT NULL,"
                + "scene_capture_type INTEGER DEFAULT NULL, generation_added INTEGER DEFAULT 0,"
                + "generation_modified INTEGER DEFAULT 0, xmp BLOB DEFAULT NULL,"
                + "_transcode_status INTEGER DEFAULT 0, _video_codec_type TEXT DEFAULT NULL,"
                + "_modifier INTEGER DEFAULT 0, is_recording INTEGER DEFAULT 0,"
                + "redacted_uri_id TEXT DEFAULT NULL, _user_id INTEGER DEFAULT "
                + UserHandle.myUserId() + ", _special_format INTEGER DEFAULT NULL)");
        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        db.execSQL("CREATE TABLE deleted_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "old_id INTEGER UNIQUE, generation_modified INTEGER NOT NULL)");

        if (!internal) {
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
        }

        if (!internal) {
            db.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,"
                    + "date_modified,owner_package_name,_hash,is_pending,date_expires,is_trashed,"
                    + "volume_name FROM files WHERE media_type=4");
        }
        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");

        db.execSQL("CREATE VIEW audio AS SELECT "
                + "title_key,instance_id,compilation,disc_number,duration,is_ringtone,"
                + "album_artist,resolution,orientation,artist,author,height,is_drm,"
                + "bucket_display_name,is_audiobook,owner_package_name,volume_name,"
                + "title_resource_uri,date_modified,writer,date_expires,composer,_display_name,"
                + "datetaken,mime_type,is_notification,bitrate,cd_track_number,_id,xmp,year,"
                + "_data,_size,album,genre,is_alarm,title,track,width,is_music,album_key,"
                + "is_favorite,is_trashed,group_id,document_id,artist_id,generation_added,"
                + "artist_key,genre_key,is_download,generation_modified,is_pending,date_added,"
                + "is_podcast,capture_framerate,album_id,num_tracks,original_document_id,"
                + "genre_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW video AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,"
                + "language,resolution,latitude,orientation,artist,color_transfer,author,"
                + "color_standard,height,is_drm,bucket_display_name,owner_package_name,"
                + "volume_name,date_modified,writer,date_expires,composer,_display_name,"
                + "datetaken,mime_type,bitrate,cd_track_number,_id,xmp,tags,year,category,_data,"
                + "_size,album,genre,title,width,longitude,is_favorite,is_trashed,group_id,"
                + "document_id,generation_added,is_download,generation_modified,is_pending,"
                + "date_added,mini_thumb_magic,capture_framerate,color_range,num_tracks,"
                + "isprivate,original_document_id,bucket_id,bookmark,relative_path"
                + " FROM files WHERE media_type=3");
        db.execSQL("CREATE VIEW images AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,"
                + "picasa_id,resolution,latitude,orientation,artist,author,height,is_drm,"
                + "bucket_display_name,owner_package_name,f_number,volume_name,date_modified,"
                + "writer,date_expires,composer,_display_name,scene_capture_type,datetaken,"
                + "mime_type,bitrate,cd_track_number,_id,iso,xmp,year,_data,_size,album,genre,"
                + "title,width,longitude,is_favorite,is_trashed,exposure_time,group_id,"
                + "document_id,generation_added,is_download,generation_modified,is_pending,"
                + "date_added,mini_thumb_magic,capture_framerate,num_tracks,isprivate,"
                + "original_document_id,bucket_id,relative_path"
                + " FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW downloads AS SELECT"
                + " instance_id,compilation,disc_number,duration,album_artist,description,"
                + "resolution,orientation,artist,author,height,is_drm,bucket_display_name,"
                + "owner_package_name,volume_name,date_modified,writer,date_expires,composer,"
                + "_display_name,datetaken,mime_type,bitrate,cd_track_number,referer_uri,_id,xmp,"
                + "year,_data,_size,album,genre,title,width,is_favorite,is_trashed,group_id,"
                + "document_id,generation_added,is_download,generation_modified,is_pending,"
                + "date_added,download_uri,capture_framerate,num_tracks,original_document_id,"
                + "bucket_id,relative_path"
                + " FROM files WHERE is_download=1");

        db.execSQL("CREATE VIEW audio_artists AS SELECT "
                + "  artist_id AS " + Audio.Artists._ID
                + ", MIN(artist) AS " + Audio.Artists.ARTIST
                + ", artist_key AS " + Audio.Artists.ARTIST_KEY
                + ", COUNT(DISTINCT album_id) AS " + Audio.Artists.NUMBER_OF_ALBUMS
                + ", COUNT(DISTINCT _id) AS " + Audio.Artists.NUMBER_OF_TRACKS
                + " FROM audio"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + "()"
                + " GROUP BY artist_id");

        db.execSQL("CREATE VIEW audio_artists_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", MIN(album) AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", (SELECT COUNT(*) FROM audio WHERE " + Audio.Albums.ALBUM_ID
                + " = TEMP.album_id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio TEMP"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + "()"
                + " GROUP BY album_id, artist_id");

        db.execSQL("CREATE VIEW audio_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", MIN(album) AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + "()" + "GROUP BY album_id");

        db.execSQL("CREATE VIEW audio_genres AS SELECT "
                + "  genre_id AS " + Audio.Genres._ID
                + ", MIN(genre) AS " + Audio.Genres.NAME
                + " FROM audio"
                + " WHERE is_pending=0 AND is_trashed=0 AND volume_name IN " + "()"
                + " GROUP BY genre_id");

        final String insertArg =
                "new.volume_name||':'||new._id||':'||new.media_type||':'||new.is_download"
                        + "||':'||new.is_pending";
        final String updateArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||new._id||':'||new.media_type||':'||new.is_download"
                        + "||':'||old.is_trashed||':'||new.is_trashed"
                        + "||':'||old.is_pending||':'||new.is_pending"
                        + "||':'||ifnull(old.is_favorite,0)"
                        + "||':'||ifnull(new.is_favorite,0)"
                        + "||':'||ifnull(old._special_format,0)"
                        + "||':'||ifnull(new._special_format,0)"
                        + "||':'||ifnull(old.owner_package_name,'null')"
                        + "||':'||ifnull(new.owner_package_name,'null')||':'||old._data";
        final String deleteArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||ifnull(old.owner_package_name,'null')||':'||old._data";

        db.execSQL("CREATE TRIGGER files_insert AFTER INSERT ON files"
                + " BEGIN SELECT _INSERT(" + insertArg + "); END");
        db.execSQL("CREATE TRIGGER files_update AFTER UPDATE ON files"
                + " BEGIN SELECT _UPDATE(" + updateArg + "); END");
        db.execSQL("CREATE TRIGGER files_delete AFTER DELETE ON files"
                + " BEGIN SELECT _DELETE(" + deleteArg + "); END");

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");
    }
}
