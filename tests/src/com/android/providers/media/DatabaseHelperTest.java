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

import static com.android.providers.media.DatabaseHelper.makePristineSchema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Column;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class DatabaseHelperTest {
    private static final String TAG = "DatabaseHelperTest";

    private static final String TEST_RECOMPUTE_DB = "test_recompute";
    private static final String TEST_UPGRADE_DB = "test_upgrade";
    private static final String TEST_DOWNGRADE_DB = "test_downgrade";
    private static final String TEST_CLEAN_DB = "test_clean";

    private static final String SQLITE_MASTER_ORDER_BY = "type,name,tbl_name";

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Before
    @After
    public void deleteDatabase() throws Exception {
        getContext().deleteDatabase(TEST_RECOMPUTE_DB);
        getContext().deleteDatabase(TEST_UPGRADE_DB);
        getContext().deleteDatabase(TEST_DOWNGRADE_DB);
        getContext().deleteDatabase(TEST_CLEAN_DB);
    }

    @Test
    public void testFilterVolumeNames() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperR(getContext(), TEST_CLEAN_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            {
                final ContentValues values = new ContentValues();
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
                values.put(FileColumns.VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
                values.put(FileColumns.DATA, "/storage/emulated/0/Coldplay-Clocks.mp3");
                values.put(AudioColumns.TITLE, "Clocks");
                values.put(AudioColumns.ALBUM, "A Rush of Blood");
                values.put(AudioColumns.ARTIST, "Coldplay");
                values.put(AudioColumns.GENRE, "Rock");
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
                MediaProvider.computeAudioKeyValues(values);
                db.insert("files", FileColumns.DATA, values);
            }

            // Confirm that raw view knows everything
            assertEquals(asSet("Clocks", "Speed of Sound", "Beautiful Day"),
                    queryValues(db, "audio", "title"));

            // By default, database only knows about primary storage
            assertEquals(asSet("Coldplay"),
                    queryValues(db, "audio_artists", "artist"));
            assertEquals(asSet("A Rush of Blood"),
                    queryValues(db, "audio_albums", "album"));
            assertEquals(asSet("Rock"),
                    queryValues(db, "audio_genres", "name"));

            // Once we broaden mounted volumes, we know a lot more
            helper.setFilterVolumeNames(asSet(VOLUME_EXTERNAL_PRIMARY, "0000-0000"));
            assertEquals(asSet("Coldplay", "U2"),
                    queryValues(db, "audio_artists", "artist"));
            assertEquals(asSet("A Rush of Blood", "X&Y", "All That You Can't Leave Behind"),
                    queryValues(db, "audio_albums", "album"));
            assertEquals(asSet("Rock", "Alternative rock"),
                    queryValues(db, "audio_genres", "name"));

            // And unmounting primary narrows us the other way
            helper.setFilterVolumeNames(asSet("0000-0000"));
            assertEquals(asSet("Coldplay", "U2"),
                    queryValues(db, "audio_artists", "artist"));
            assertEquals(asSet("X&Y", "All That You Can't Leave Behind"),
                    queryValues(db, "audio_albums", "album"));
            assertEquals(asSet("Rock", "Alternative rock"),
                    queryValues(db, "audio_genres", "name"));

            // Finally fully unmounted means nothing
            helper.setFilterVolumeNames(asSet());
            assertEquals(asSet(),
                    queryValues(db, "audio_artists", "artist"));
            assertEquals(asSet(),
                    queryValues(db, "audio_albums", "album"));
            assertEquals(asSet(),
                    queryValues(db, "audio_genres", "name"));
        }
    }

    @Test
    public void testTransactions() throws Exception {
        try (DatabaseHelper helper = new DatabaseHelperR(getContext(), TEST_CLEAN_DB)) {
            helper.beginTransaction();
            try {
                helper.setTransactionSuccessful();
            } finally {
                helper.endTransaction();
            }

            helper.runWithTransaction(() -> {
                return 0;
            });
        }
    }

    @Test
    public void testRtoO() throws Exception {
        assertDowngrade(DatabaseHelperR.class, DatabaseHelperO.class);
    }

    @Test
    public void testRtoP() throws Exception {
        assertDowngrade(DatabaseHelperR.class, DatabaseHelperP.class);
    }

    @Test
    public void testRtoQ() throws Exception {
        assertDowngrade(DatabaseHelperR.class, DatabaseHelperQ.class);
    }

    private void assertDowngrade(Class<? extends DatabaseHelper> before,
            Class<? extends DatabaseHelper> after) throws Exception {
        try (DatabaseHelper helper = before.getConstructor(Context.class, String.class)
                .newInstance(getContext(), TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();
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
                .newInstance(getContext(), TEST_DOWNGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            try (Cursor c = db.query("files", null, null, null, null, null, null, null)) {
                assertEquals(0, c.getCount());
            }
        }
    }

    @Test
    public void testOtoR() throws Exception {
        assertRecompute(DatabaseHelperO.class, DatabaseHelperR.class);
        assertUpgrade(DatabaseHelperO.class, DatabaseHelperR.class);
    }

    @Test
    public void testPtoR() throws Exception {
        assertRecompute(DatabaseHelperP.class, DatabaseHelperR.class);
        assertUpgrade(DatabaseHelperP.class, DatabaseHelperR.class);
    }

    @Test
    public void testQtoR() throws Exception {
        assertUpgrade(DatabaseHelperQ.class, DatabaseHelperR.class);
    }

    private void assertRecompute(Class<? extends DatabaseHelper> before,
            Class<? extends DatabaseHelper> after) throws Exception {
        try (DatabaseHelper helper = before.getConstructor(Context.class, String.class)
                .newInstance(getContext(), TEST_RECOMPUTE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();
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
                        "/storage/0000-0000/Android/sandbox/com.example2/Download/dir/foo.mp4");
                values.put(FileColumns.DATE_ADDED, System.currentTimeMillis());
                values.put(FileColumns.DATE_MODIFIED, System.currentTimeMillis());
                values.put(FileColumns.DISPLAY_NAME, "foo.mp4");
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
                values.put(FileColumns.MIME_TYPE, "video/mp4");
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
                .newInstance(getContext(), TEST_RECOMPUTE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();
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
            try (Cursor c = db.query("files", null, FileColumns.DISPLAY_NAME + "='foo.mp4'",
                    null, null, null, null)) {
                assertEquals(1, c.getCount());
                assertTrue(c.moveToFirst());
                assertEquals("/storage/0000-0000/Android/sandbox/com.example2/Download/dir/foo.mp4",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.DATA)));
                assertEquals("video/mp4",
                        c.getString(c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)));
                assertEquals("com.example2",
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
                .newInstance(getContext(), TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();
        }

        try (DatabaseHelper helper = after.getConstructor(Context.class, String.class)
                .newInstance(getContext(), TEST_UPGRADE_DB)) {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Create a second isolated instance from scratch and assert that
            // upgraded schema is identical
            try (DatabaseHelper helper2 = after.getConstructor(Context.class, String.class)
                    .newInstance(getContext(), TEST_CLEAN_DB)) {
                SQLiteDatabase db2 = helper2.getWritableDatabase();

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

    private static String normalize(String sql) {
        return sql != null ? sql.replace(", ", ",") : null;
    }

    private static Set<String> asSet(String... vars) {
        return new ArraySet<>(Arrays.asList(vars));
    }

    private static Set<String> queryValues(@NonNull SQLiteDatabase db, @NonNull String table,
            @NonNull String columnName) {
        try (Cursor c = db.query(table, new String[] { columnName },
                null, null, null, null, null)) {
            final ArraySet<String> res = new ArraySet<>();
            while (c.moveToNext()) {
                res.add(c.getString(0));
            }
            return res;
        }
    }

    private static class DatabaseHelperO extends DatabaseHelper {
        public DatabaseHelperO(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_O,
                    false, false, true, Column.class, null, null, null);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createOSchema(db, false);
        }
    }

    private static class DatabaseHelperP extends DatabaseHelper {
        public DatabaseHelperP(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_P,
                    false, false, true, Column.class, null, null, null);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createPSchema(db, false);
        }
    }

    private static class DatabaseHelperQ extends DatabaseHelper {
        public DatabaseHelperQ(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_Q,
                    false, false, true, Column.class, null, null, null);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createQSchema(db, false);
        }
    }

    private static class DatabaseHelperR extends DatabaseHelper {
        public DatabaseHelperR(Context context, String name) {
            super(context, name, DatabaseHelper.VERSION_R,
                    false, false, true, Column.class, null, null, null);
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
}
