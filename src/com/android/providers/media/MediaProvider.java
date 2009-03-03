/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.SearchManager;
import android.content.*;
import android.database.Cursor;
import android.database.MergeCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaFile;
import android.media.MediaScanner;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video;
import android.provider.MediaStore.Images.ImageColumns;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.text.Collator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * Media content provider. See {@link android.provider.MediaStore} for details.
 * Separate databases are kept for each external storage card we see (using the
 * card's ID as an index).  The content visible at content://media/external/...
 * changes with the card.
 */
public class MediaProvider extends ContentProvider {
    private static final Uri MEDIA_URI = Uri.parse("content://media");
    private static final Uri ALBUMART_URI = Uri.parse("content://media/external/audio/albumart");
    private static final Uri ALBUMART_THUMB_URI = Uri.parse("content://media/external/audio/albumart_thumb");

    private static final HashMap<String, String> sArtistAlbumsMap = new HashMap<String, String>();

    private BroadcastReceiver mUnmountReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_MEDIA_EJECT)) {
                // Remove the external volume and then notify all cursors backed by
                // data on that volume
                detachVolume(Uri.parse("content://media/external"));
            }
        }
    };

    /**
     * Wrapper class for a specific database (associated with one particular
     * external card, or with internal storage).  Can open the actual database
     * on demand, create and upgrade the schema, etc.
     */
    private static final class DatabaseHelper extends SQLiteOpenHelper {
        final Context mContext;
        final boolean mInternal;  // True if this is the internal database

        // In memory caches of artist and album data.
        HashMap<String, Long> mArtistCache = new HashMap<String, Long>();
        HashMap<String, Long> mAlbumCache = new HashMap<String, Long>();

        public DatabaseHelper(Context context, String name, boolean internal) {
            super(context, name, null, DATABASE_VERSION);
            mContext = context;
            mInternal = internal;
        }

        /**
         * Creates database the first time we try to open it.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            updateDatabase(db, mInternal, 0, DATABASE_VERSION);
        }

        /**
         * Updates the database format when a new content provider is used
         * with an older database format.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
            updateDatabase(db, mInternal, oldV, newV);
        }

        /**
         * Touch this particular database and garbage collect old databases.
         * An LRU cache system is used to clean up databases for old external
         * storage volumes.
         */
        @Override
        public void onOpen(SQLiteDatabase db) {
            if (mInternal) return;  // The internal database is kept separately.

            // touch the database file to show it is most recently used
            File file = new File(db.getPath());
            long now = System.currentTimeMillis();
            file.setLastModified(now);

            // delete least recently used databases if we are over the limit
            String[] databases = mContext.databaseList();
            int count = databases.length;
            int limit = MAX_EXTERNAL_DATABASES;

            // delete external databases that have not been used in the past two months
            long twoMonthsAgo = now - OBSOLETE_DATABASE_DB;
            for (int i = 0; i < databases.length; i++) {
                File other = mContext.getDatabasePath(databases[i]);
                if (INTERNAL_DATABASE_NAME.equals(databases[i]) || file.equals(other)) {
                    databases[i] = null;
                    count--;
                    if (file.equals(other)) {
                        // reduce limit to account for the existence of the database we
                        // are about to open, which we removed from the list.
                        limit--;
                    }
                } else {
                    long time = other.lastModified();
                    if (time < twoMonthsAgo) {
                        if (LOCAL_LOGV) Log.v(TAG, "Deleting old database " + databases[i]);
                        mContext.deleteDatabase(databases[i]);
                        databases[i] = null;
                        count--;
                    }
                }
            }

            // delete least recently used databases until
            // we are no longer over the limit
            while (count > limit) {
                int lruIndex = -1;
                long lruTime = 0;

                for (int i = 0; i < databases.length; i++) {
                    if (databases[i] != null) {
                        long time = mContext.getDatabasePath(databases[i]).lastModified();
                        if (lruTime == 0 || time < lruTime) {
                            lruIndex = i;
                            lruTime = time;
                        }
                    }
                }

                // delete least recently used database
                if (lruIndex != -1) {
                    if (LOCAL_LOGV) Log.v(TAG, "Deleting old database " + databases[lruIndex]);
                    mContext.deleteDatabase(databases[lruIndex]);
                    databases[lruIndex] = null;
                    count--;
                }
            }
        }
    }

    @Override
    public boolean onCreate() {
        sArtistAlbumsMap.put(MediaStore.Audio.Albums._ID, "audio.album_id AS " +
                MediaStore.Audio.Albums._ID);
        sArtistAlbumsMap.put(MediaStore.Audio.Albums.ALBUM, "album");
        sArtistAlbumsMap.put(MediaStore.Audio.Albums.ALBUM_KEY, "album_key");
        sArtistAlbumsMap.put(MediaStore.Audio.Albums.FIRST_YEAR, "MIN(year) AS " +
                MediaStore.Audio.Albums.FIRST_YEAR);
        sArtistAlbumsMap.put(MediaStore.Audio.Albums.LAST_YEAR, "MAX(year) AS " +
                MediaStore.Audio.Albums.LAST_YEAR);
        sArtistAlbumsMap.put(MediaStore.Audio.Media.ARTIST, "artist");
        sArtistAlbumsMap.put(MediaStore.Audio.Media.ARTIST_ID, "artist");
        sArtistAlbumsMap.put(MediaStore.Audio.Media.ARTIST_KEY, "artist_key");
        sArtistAlbumsMap.put(MediaStore.Audio.Albums.NUMBER_OF_SONGS, "count(*) AS " +
                MediaStore.Audio.Albums.NUMBER_OF_SONGS);
        sArtistAlbumsMap.put(MediaStore.Audio.Albums.ALBUM_ART, "album_art._data AS " +
                MediaStore.Audio.Albums.ALBUM_ART);

        mDatabases = new HashMap<String, DatabaseHelper>();
        attachVolume(INTERNAL_VOLUME);

        IntentFilter iFilter = new IntentFilter(Intent.ACTION_MEDIA_EJECT);
        iFilter.addDataScheme("file");
        getContext().registerReceiver(mUnmountReceiver, iFilter);

        // open external database if external storage is mounted
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            attachVolume(EXTERNAL_VOLUME);
        }

        mThumbWorker = new Worker("album thumbs");
        mThumbHandler = new Handler(mThumbWorker.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                makeThumb((ThumbData)msg.obj);
            }
        };

        return true;
    }

    /**
     * This method takes care of updating all the tables in the database to the
     * current version, creating them if necessary.
     * This method can only update databases at schema 63 or higher, which was
     * created August 1, 2008. Older database will be cleared and recreated.
     * @param db Database
     * @param internal True if this is the internal media database
     */
    private static void updateDatabase(SQLiteDatabase db, boolean internal,
            int fromVersion, int toVersion) {

        // sanity checks
        if (toVersion != DATABASE_VERSION) {
            Log.e(TAG, "Illegal update request. Got " + toVersion + ", expected " +
                    DATABASE_VERSION);
            throw new IllegalArgumentException();
        } else if (fromVersion > toVersion) {
            Log.e(TAG, "Illegal update request: can't downgrade from " + fromVersion + 
                    " to " + toVersion + ". Did you forget to wipe data?");
            throw new IllegalArgumentException();
        }

        if (fromVersion < 63) {
            // Drop everything and start over.
            Log.i(TAG, "Upgrading media database from version " +
                    fromVersion + " to " + toVersion + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS images");
            db.execSQL("DROP TRIGGER IF EXISTS images_cleanup");
            db.execSQL("DROP TABLE IF EXISTS thumbnails");
            db.execSQL("DROP TRIGGER IF EXISTS thumbnails_cleanup");
            db.execSQL("DROP TABLE IF EXISTS audio_meta");
            db.execSQL("DROP TABLE IF EXISTS artists");
            db.execSQL("DROP TABLE IF EXISTS albums");
            db.execSQL("DROP TABLE IF EXISTS album_art");
            db.execSQL("DROP VIEW IF EXISTS artist_info");
            db.execSQL("DROP VIEW IF EXISTS album_info");
            db.execSQL("DROP VIEW IF EXISTS artists_albums_map");
            db.execSQL("DROP TRIGGER IF EXISTS audio_meta_cleanup");
            db.execSQL("DROP TABLE IF EXISTS audio_genres");
            db.execSQL("DROP TABLE IF EXISTS audio_genres_map");
            db.execSQL("DROP TRIGGER IF EXISTS audio_genres_cleanup");
            db.execSQL("DROP TABLE IF EXISTS audio_playlists");
            db.execSQL("DROP TABLE IF EXISTS audio_playlists_map");
            db.execSQL("DROP TRIGGER IF EXISTS audio_playlists_cleanup");
            db.execSQL("DROP TRIGGER IF EXISTS albumart_cleanup1");
            db.execSQL("DROP TRIGGER IF EXISTS albumart_cleanup2");
            db.execSQL("DROP TABLE IF EXISTS video");
            db.execSQL("DROP TRIGGER IF EXISTS video_cleanup");

            db.execSQL("CREATE TABLE IF NOT EXISTS images (" +
                    "_id INTEGER PRIMARY KEY," +
                    "_data TEXT," +
                    "_size INTEGER," +
                    "_display_name TEXT," +
                    "mime_type TEXT," +
                    "title TEXT," +
                    "date_added INTEGER," +
                    "date_modified INTEGER," +
                    "description TEXT," +
                    "picasa_id TEXT," +
                    "isprivate INTEGER," +
                    "latitude DOUBLE," +
                    "longitude DOUBLE," +
                    "datetaken INTEGER," +
                    "orientation INTEGER," +
                    "mini_thumb_magic INTEGER," +
                    "bucket_id TEXT," +
                    "bucket_display_name TEXT" +
                   ");");

            db.execSQL("CREATE INDEX IF NOT EXISTS mini_thumb_magic_index on images(mini_thumb_magic);");

            db.execSQL("CREATE TRIGGER IF NOT EXISTS images_cleanup DELETE ON images " +
                    "BEGIN " +
                        "DELETE FROM thumbnails WHERE image_id = old._id;" +
                        "SELECT _DELETE_FILE(old._data);" +
                    "END");

            db.execSQL("CREATE TABLE IF NOT EXISTS thumbnails (" +
                       "_id INTEGER PRIMARY KEY," +
                       "_data TEXT," +
                       "image_id INTEGER," +
                       "kind INTEGER," +
                       "width INTEGER," +
                       "height INTEGER" +
                       ");");

            db.execSQL("CREATE INDEX IF NOT EXISTS image_id_index on thumbnails(image_id);");

            db.execSQL("CREATE TRIGGER IF NOT EXISTS thumbnails_cleanup DELETE ON thumbnails " +
                    "BEGIN " +
                        "SELECT _DELETE_FILE(old._data);" +
                    "END");


            // Contains meta data about audio files
            db.execSQL("CREATE TABLE IF NOT EXISTS audio_meta (" +
                       "_id INTEGER PRIMARY KEY," +
                       "_data TEXT NOT NULL," +
                       "_display_name TEXT," +
                       "_size INTEGER," +
                       "mime_type TEXT," +
                       "date_added INTEGER," +
                       "date_modified INTEGER," +
                       "title TEXT NOT NULL," +
                       "title_key TEXT NOT NULL," +
                       "duration INTEGER," +
                       "artist_id INTEGER," +
                       "composer TEXT," +
                       "album_id INTEGER," +
                       "track INTEGER," +    // track is an integer to allow proper sorting
                       "year INTEGER CHECK(year!=0)," +
                       "is_ringtone INTEGER," +
                       "is_music INTEGER," +
                       "is_alarm INTEGER," +
                       "is_notification INTEGER" +
                       ");");

            // Contains a sort/group "key" and the preferred display name for artists
            db.execSQL("CREATE TABLE IF NOT EXISTS artists (" +
                        "artist_id INTEGER PRIMARY KEY," +
                        "artist_key TEXT NOT NULL UNIQUE," +
                        "artist TEXT NOT NULL" +
                       ");");

            // Contains a sort/group "key" and the preferred display name for albums
            db.execSQL("CREATE TABLE IF NOT EXISTS albums (" +
                        "album_id INTEGER PRIMARY KEY," +
                        "album_key TEXT NOT NULL UNIQUE," +
                        "album TEXT NOT NULL" +
                       ");");

            db.execSQL("CREATE TABLE IF NOT EXISTS album_art (" +
                    "album_id INTEGER PRIMARY KEY," +
                    "_data TEXT" +
                   ");");

            recreateAudioView(db);
            

            // Provides some extra info about artists, like the number of tracks
            // and albums for this artist
            db.execSQL("CREATE VIEW IF NOT EXISTS artist_info AS " +
                        "SELECT artist_id AS _id, artist, artist_key, " +
                        "COUNT(DISTINCT album) AS number_of_albums, " +
                        "COUNT(*) AS number_of_tracks FROM audio WHERE is_music=1 "+
                        "GROUP BY artist_key;");

            // Provides extra info albums, such as the number of tracks
            db.execSQL("CREATE VIEW IF NOT EXISTS album_info AS " +
                    "SELECT audio.album_id AS _id, album, album_key, " +
                    "MIN(year) AS minyear, " +
                    "MAX(year) AS maxyear, artist, artist_id, artist_key, " +
                    "count(*) AS " + MediaStore.Audio.Albums.NUMBER_OF_SONGS +
                    ",album_art._data AS album_art" +
                    " FROM audio LEFT OUTER JOIN album_art ON audio.album_id=album_art.album_id" +
                    " WHERE is_music=1 GROUP BY audio.album_id;");

            // For a given artist_id, provides the album_id for albums on
            // which the artist appears.
            db.execSQL("CREATE VIEW IF NOT EXISTS artists_albums_map AS " +
                    "SELECT DISTINCT artist_id, album_id FROM audio_meta;");

            /*
             * Only external media volumes can handle genres, playlists, etc.
             */
            if (!internal) {
                // Cleans up when an audio file is deleted
                db.execSQL("CREATE TRIGGER IF NOT EXISTS audio_meta_cleanup DELETE ON audio_meta " +
                           "BEGIN " +
                               "DELETE FROM audio_genres_map WHERE audio_id = old._id;" +
                               "DELETE FROM audio_playlists_map WHERE audio_id = old._id;" +
                           "END");

                // Contains audio genre definitions
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_genres (" +
                           "_id INTEGER PRIMARY KEY," +
                           "name TEXT NOT NULL" +
                           ");");

                // Contiains mappings between audio genres and audio files
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_genres_map (" +
                           "_id INTEGER PRIMARY KEY," +
                           "audio_id INTEGER NOT NULL," +
                           "genre_id INTEGER NOT NULL" +
                           ");");

                // Cleans up when an audio genre is delete
                db.execSQL("CREATE TRIGGER IF NOT EXISTS audio_genres_cleanup DELETE ON audio_genres " +
                           "BEGIN " +
                               "DELETE FROM audio_genres_map WHERE genre_id = old._id;" +
                           "END");

                // Contains audio playlist definitions
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_playlists (" +
                           "_id INTEGER PRIMARY KEY," +
                           "_data TEXT," +  // _data is path for file based playlists, or null
                           "name TEXT NOT NULL," +
                           "date_added INTEGER," +
                           "date_modified INTEGER" +
                           ");");

                // Contains mappings between audio playlists and audio files
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_playlists_map (" +
                           "_id INTEGER PRIMARY KEY," +
                           "audio_id INTEGER NOT NULL," +
                           "playlist_id INTEGER NOT NULL," +
                           "play_order INTEGER NOT NULL" +
                           ");");

                // Cleans up when an audio playlist is deleted
                db.execSQL("CREATE TRIGGER IF NOT EXISTS audio_playlists_cleanup DELETE ON audio_playlists " +
                           "BEGIN " +
                               "DELETE FROM audio_playlists_map WHERE playlist_id = old._id;" +
                               "SELECT _DELETE_FILE(old._data);" +
                           "END");

                // Cleans up album_art table entry when an album is deleted
                db.execSQL("CREATE TRIGGER IF NOT EXISTS albumart_cleanup1 DELETE ON albums " +
                        "BEGIN " +
                            "DELETE FROM album_art WHERE album_id = old.album_id;" +
                        "END");

                // Cleans up album_art when an album is deleted
                db.execSQL("CREATE TRIGGER IF NOT EXISTS albumart_cleanup2 DELETE ON album_art " +
                        "BEGIN " +
                            "SELECT _DELETE_FILE(old._data);" +
                        "END");
            }

            // Contains meta data about video files
            db.execSQL("CREATE TABLE IF NOT EXISTS video (" +
                       "_id INTEGER PRIMARY KEY," +
                       "_data TEXT NOT NULL," +
                       "_display_name TEXT," +
                       "_size INTEGER," +
                       "mime_type TEXT," +
                       "date_added INTEGER," +
                       "date_modified INTEGER," +
                       "title TEXT," +
                       "duration INTEGER," +
                       "artist TEXT," +
                       "album TEXT," +
                       "resolution TEXT," +
                       "description TEXT," +
                       "isprivate INTEGER," +   // for YouTube videos
                       "tags TEXT," +           // for YouTube videos
                       "category TEXT," +       // for YouTube videos
                       "language TEXT," +       // for YouTube videos
                       "mini_thumb_data TEXT," +
                       "latitude DOUBLE," +
                       "longitude DOUBLE," +
                       "datetaken INTEGER," +
                       "mini_thumb_magic INTEGER" +
                       ");");

            db.execSQL("CREATE TRIGGER IF NOT EXISTS video_cleanup DELETE ON video " +
                    "BEGIN " +
                        "SELECT _DELETE_FILE(old._data);" +
                    "END");
        }

        // At this point the database is at least at schema version 63 (it was
        // either created at version 63 by the code above, or was already at
        // version 63 or later)

        if (fromVersion < 64) {
            // create the index that updates the database to schema version 64
            db.execSQL("CREATE INDEX IF NOT EXISTS sort_index on images(datetaken ASC, _id ASC);");
        }

        if (fromVersion < 65) {
            // create the index that updates the database to schema version 65
            db.execSQL("CREATE INDEX IF NOT EXISTS titlekey_index on audio_meta(title_key);");
        }

        if (fromVersion < 66) {
            updateBucketNames(db, "images");
        }

        if (fromVersion < 67) {
            // create the indices that update the database to schema version 67
            db.execSQL("CREATE INDEX IF NOT EXISTS albumkey_index on albums(album_key);");
            db.execSQL("CREATE INDEX IF NOT EXISTS artistkey_index on artists(artist_key);");
        }

        if (fromVersion < 68) {
            // Create bucket_id and bucket_display_name columns for the video table.
            db.execSQL("ALTER TABLE video ADD COLUMN bucket_id TEXT;");
            db.execSQL("ALTER TABLE video ADD COLUMN bucket_display_name TEXT");
            updateBucketNames(db, "video");
        }

        if (fromVersion < 69) {
            updateDisplayName(db, "images");
        }

        if (fromVersion < 70) {
            // Create bookmark column for the video table.
            db.execSQL("ALTER TABLE video ADD COLUMN bookmark INTEGER;");
        }
        
        if (fromVersion < 71) {
            // There is no change to the database schema, however a code change
            // fixed parsing of metadata for certain files bought from the
            // iTunes music store, so we want to rescan files that might need it.
            // We do this by clearing the modification date in the database for
            // those files, so that the media scanner will see them as updated
            // and rescan them.
            db.execSQL("UPDATE audio_meta SET date_modified=0 WHERE _id IN (" +
                    "SELECT _id FROM audio where mime_type='audio/mp4' AND " +
                    "artist='" + MediaFile.UNKNOWN_STRING + "' AND " +
                    "album='" + MediaFile.UNKNOWN_STRING + "'" +
                    ");");
        }
        
        if (fromVersion < 72) {
            // Create is_podcast and bookmark columns for the audio table.
            db.execSQL("ALTER TABLE audio_meta ADD COLUMN is_podcast INTEGER;");
            db.execSQL("UPDATE audio_meta SET is_podcast=1 WHERE _data LIKE '%/podcasts/%';");
            db.execSQL("UPDATE audio_meta SET is_music=0 WHERE is_podcast=1" +
                    " AND _data NOT LIKE '%/music/%';");
            db.execSQL("ALTER TABLE audio_meta ADD COLUMN bookmark INTEGER;");

            // New columns added to tables aren't visible in views on those tables
            // without opening and closing the database (or using the 'vacuum' command,
            // which we can't do here because all this code runs inside a transaction).
            // To work around this, we drop and recreate the affected view and trigger.
            recreateAudioView(db);
        }
    }

    private static void recreateAudioView(SQLiteDatabase db) {
        // Provides a unified audio/artist/album info view.
        // Note that views are read-only, so we define a trigger to allow deletes.
        db.execSQL("DROP VIEW IF EXISTS audio");
        db.execSQL("DROP TRIGGER IF EXISTS audio_delete");
        db.execSQL("CREATE VIEW IF NOT EXISTS audio as SELECT * FROM audio_meta " +
                    "LEFT OUTER JOIN artists ON audio_meta.artist_id=artists.artist_id " +
                    "LEFT OUTER JOIN albums ON audio_meta.album_id=albums.album_id;");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS audio_delete INSTEAD OF DELETE ON audio " +
                "BEGIN " +
                    "DELETE from audio_meta where _id=old._id;" +
                    "DELETE from audio_playlists_map where audio_id=old._id;" +
                    "DELETE from audio_genres_map where audio_id=old._id;" +
                "END");
    }
    
    /**
     * Iterate through the rows of a table in a database, ensuring that the bucket_id and
     * bucket_display_name columns are correct.
     * @param db
     * @param tableName
     */
    private static void updateBucketNames(SQLiteDatabase db, String tableName) {
        // Rebuild the bucket_display_name column using the natural case rather than lower case.
        db.beginTransaction();
        try {
            String[] columns = {BaseColumns._ID, MediaColumns.DATA};
            Cursor cursor = db.query(tableName, columns, null, null, null, null, null);
            try {
                final int idColumnIndex = cursor.getColumnIndex(BaseColumns._ID);
                final int dataColumnIndex = cursor.getColumnIndex(MediaColumns.DATA);
                while (cursor.moveToNext()) {
                    String data = cursor.getString(dataColumnIndex);
                    ContentValues values = new ContentValues();
                    computeBucketValues(data, values);
                    int rowId = cursor.getInt(idColumnIndex);
                    db.update(tableName, values, "_id=" + rowId, null);
                }
            } finally {
                cursor.close();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Iterate through the rows of a table in a database, ensuring that the
     * display name column has a value.
     * @param db
     * @param tableName
     */
    private static void updateDisplayName(SQLiteDatabase db, String tableName) {
        // Fill in default values for null displayName values
        db.beginTransaction();
        try {
            String[] columns = {BaseColumns._ID, MediaColumns.DATA, MediaColumns.DISPLAY_NAME};
            Cursor cursor = db.query(tableName, columns, null, null, null, null, null);
            try {
                final int idColumnIndex = cursor.getColumnIndex(BaseColumns._ID);
                final int dataColumnIndex = cursor.getColumnIndex(MediaColumns.DATA);
                final int displayNameIndex = cursor.getColumnIndex(MediaColumns.DISPLAY_NAME);
                ContentValues values = new ContentValues();
                while (cursor.moveToNext()) {
                    String displayName = cursor.getString(displayNameIndex);
                    if (displayName == null) {
                        String data = cursor.getString(dataColumnIndex);
                        values.clear();
                        computeDisplayName(data, values);
                        int rowId = cursor.getInt(idColumnIndex);
                        db.update(tableName, values, "_id=" + rowId, null);
                    }
                }
            } finally {
                cursor.close();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
    /**
     * @param data The input path
     * @param values the content values, where the bucked id name and bucket display name are updated.
     *
     */

    private static void computeBucketValues(String data, ContentValues values) {
        File parentFile = new File(data).getParentFile();
        if (parentFile == null) {
            parentFile = new File("/");
        }

        // Lowercase the path for hashing. This avoids duplicate buckets if the
        // filepath case is changed externally.
        // Keep the original case for display.
        String path = parentFile.toString().toLowerCase();
        String name = parentFile.getName();

        // Note: the BUCKET_ID and BUCKET_DISPLAY_NAME attributes are spelled the
        // same for both images and video. However, for backwards-compatibility reasons
        // there is no common base class. We use the ImageColumns version here
        values.put(ImageColumns.BUCKET_ID, path.hashCode());
        values.put(ImageColumns.BUCKET_DISPLAY_NAME, name);
    }

    /**
     * @param data The input path
     * @param values the content values, where the display name is updated.
     *
     */
    private static void computeDisplayName(String data, ContentValues values) {
        String s = (data == null ? "" : data.toString());
        int idx = s.lastIndexOf('/');
        if (idx >= 0) {
            s = s.substring(idx + 1);
        }
        values.put("_display_name", s);
    }

    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        int table = URI_MATCHER.match(uri);

        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (table == MEDIA_SCANNER) {
            if (mMediaScannerVolume == null) {
                return null;
            } else {
                // create a cursor to return volume currently being scanned by the media scanner
                return new MediaScannerCursor(mMediaScannerVolume);
            }
        }

        String groupBy = null;
        DatabaseHelper database = getDatabaseForUri(uri);
        if (database == null) {
            return null;
        }
        SQLiteDatabase db = database.getReadableDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        switch (table) {
            case IMAGES_MEDIA:
                qb.setTables("images");
                if (uri.getQueryParameter("distinct") != null)
                    qb.setDistinct(true);

                // set the project map so that data dir is prepended to _data.
                //qb.setProjectionMap(mImagesProjectionMap, true);
                break;

            case IMAGES_MEDIA_ID:
                qb.setTables("images");
                if (uri.getQueryParameter("distinct") != null)
                    qb.setDistinct(true);

                // set the project map so that data dir is prepended to _data.
                //qb.setProjectionMap(mImagesProjectionMap, true);
                qb.appendWhere("_id = " + uri.getPathSegments().get(3));
                break;

            case IMAGES_THUMBNAILS:
                qb.setTables("thumbnails");
                break;

            case IMAGES_THUMBNAILS_ID:
                qb.setTables("thumbnails");
                qb.appendWhere("_id = " + uri.getPathSegments().get(3));
                break;

            case AUDIO_MEDIA:
                qb.setTables("audio ");
                break;

            case AUDIO_MEDIA_ID:
                qb.setTables("audio");
                qb.appendWhere("_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_MEDIA_ID_GENRES:
                qb.setTables("audio_genres");
                qb.appendWhere("_id IN (SELECT genre_id FROM " +
                        "audio_genres_map WHERE audio_id = " +
                        uri.getPathSegments().get(3) + ")");
                break;

            case AUDIO_MEDIA_ID_GENRES_ID:
                qb.setTables("audio_genres");
                qb.appendWhere("_id=" + uri.getPathSegments().get(5));
                break;

            case AUDIO_MEDIA_ID_PLAYLISTS:
                qb.setTables("audio_playlists");
                qb.appendWhere("_id IN (SELECT playlist_id FROM " +
                        "audio_playlists_map WHERE audio_id = " +
                        uri.getPathSegments().get(3) + ")");
                break;

            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                qb.setTables("audio_playlists");
                qb.appendWhere("_id=" + uri.getPathSegments().get(5));
                break;

            case AUDIO_GENRES:
                qb.setTables("audio_genres");
                break;

            case AUDIO_GENRES_ID:
                qb.setTables("audio_genres");
                qb.appendWhere("_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_GENRES_ID_MEMBERS:
                qb.setTables("audio");
                qb.appendWhere("_id IN (SELECT audio_id FROM " +
                        "audio_genres_map WHERE genre_id = " +
                        uri.getPathSegments().get(3) + ")");
                break;

            case AUDIO_GENRES_ID_MEMBERS_ID:
                qb.setTables("audio");
                qb.appendWhere("_id=" + uri.getPathSegments().get(5));
                break;

            case AUDIO_PLAYLISTS:
                qb.setTables("audio_playlists");
                break;

            case AUDIO_PLAYLISTS_ID:
                qb.setTables("audio_playlists");
                qb.appendWhere("_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_PLAYLISTS_ID_MEMBERS:
                for (int i = 0; i < projectionIn.length; i++) {
                    if (projectionIn[i].equals("_id")) {
                        projectionIn[i] = "audio_playlists_map._id AS _id";
                    }
                }
                qb.setTables("audio_playlists_map, audio");
                qb.appendWhere("audio._id = audio_id AND playlist_id = "
                        + uri.getPathSegments().get(3));
                break;

            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                qb.setTables("audio");
                qb.appendWhere("_id=" + uri.getPathSegments().get(5));
                break;

            case VIDEO_MEDIA:
                qb.setTables("video");
                break;

            case VIDEO_MEDIA_ID:
                qb.setTables("video");
                qb.appendWhere("_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_ARTISTS:
                qb.setTables("artist_info");
                break;

            case AUDIO_ARTISTS_ID:
                qb.setTables("artist_info");
                qb.appendWhere("_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_ARTISTS_ID_ALBUMS:
                String aid = uri.getPathSegments().get(3);
                qb.setTables("audio LEFT OUTER JOIN album_art ON" +
                        " audio.album_id=album_art.album_id");
                qb.appendWhere("is_music=1 AND audio.album_id IN (SELECT album_id FROM " +
                        "artists_albums_map WHERE artist_id = " +
                         aid + ")");
                groupBy = "audio.album_id";
                sArtistAlbumsMap.put(MediaStore.Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST,
                        "count(CASE WHEN artist_id==" + aid + " THEN 'foo' ELSE NULL END) AS " +
                        MediaStore.Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST);
                qb.setProjectionMap(sArtistAlbumsMap);
                break;

            case AUDIO_ALBUMS:
                qb.setTables("album_info");
                break;

            case AUDIO_ALBUMS_ID:
                qb.setTables("album_info");
                qb.appendWhere("_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_ALBUMART_ID:
                qb.setTables("album_art");
                qb.appendWhere("album_id=" + uri.getPathSegments().get(3));
                break;

            case AUDIO_SEARCH:
                return doAudioSearch(db, qb, uri, projectionIn, selection, selectionArgs, sort);

            default:
                throw new IllegalStateException("Unknown URL: " + uri.toString());
        }

        Cursor c = qb.query(db, projectionIn, selection,
                selectionArgs, groupBy, null, sort);
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    private Cursor doAudioSearch(SQLiteDatabase db, SQLiteQueryBuilder qb,
            Uri uri, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {

        List<String> l = uri.getPathSegments();
        String mSearchString = l.size() == 4 ? l.get(3) : "";
        mSearchString = mSearchString.replaceAll("  ", " ").trim().toLowerCase();
        Cursor mCursor = null;

        String [] searchWords = mSearchString.length() > 0 ?
                mSearchString.split(" ") : new String[0];
        String [] wildcardWords3 = new String[searchWords.length * 3];
        Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        int len = searchWords.length;
        for (int i = 0; i < len; i++) {
            // Because we match on individual words here, we need to remove words
            // like 'a' and 'the' that aren't part of the keys.
            wildcardWords3[i] = wildcardWords3[i + len] = wildcardWords3[i + len + len] =
                (searchWords[i].equals("a") || searchWords[i].equals("an") ||
                        searchWords[i].equals("the")) ? "%" :
                '%' + MediaStore.Audio.keyFor(searchWords[i]) + '%';
        }

        String UQs [] = new String[3];
        HashSet<String> tablecolumns = new HashSet<String>();

        // Direct match artists
        {
            String[] ccols = new String[] {
                    MediaStore.Audio.Artists._ID,
                    "'artist' AS " + MediaStore.Audio.Media.MIME_TYPE,
                    "" + R.drawable.ic_search_category_music_artist + " AS " +
                        SearchManager.SUGGEST_COLUMN_ICON_1,
                    "0 AS " + SearchManager.SUGGEST_COLUMN_ICON_2,
                    MediaStore.Audio.Artists.ARTIST + " AS " + SearchManager.SUGGEST_COLUMN_TEXT_1,
                    MediaStore.Audio.Artists.ARTIST + " AS " + SearchManager.SUGGEST_COLUMN_QUERY,
                    MediaStore.Audio.Artists.NUMBER_OF_ALBUMS + " AS data1",
                    MediaStore.Audio.Artists.NUMBER_OF_TRACKS + " AS data2",
                    MediaStore.Audio.Artists.ARTIST_KEY + " AS ar",
                    "'content://media/external/audio/artists/'||" + MediaStore.Audio.Artists._ID +
                    " AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                    "'1' AS grouporder",
                    "artist_key AS itemorder"
            };


            String where = MediaStore.Audio.Artists.ARTIST_KEY + " != ''";
            for (int i = 0; i < searchWords.length; i++) {
                where += " AND ar LIKE ?";
            }

            qb.setTables("artist_info");
            UQs[0] = qb.buildUnionSubQuery(MediaStore.Audio.Media.MIME_TYPE,
                    ccols, tablecolumns, ccols.length, "artist", where, null, null, null);
        }

        // Direct match albums
        {
            String[] ccols = new String[] {
                    MediaStore.Audio.Albums._ID,
                    "'album' AS " + MediaStore.Audio.Media.MIME_TYPE,
                    "" + R.drawable.ic_search_category_music_album + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1,
                    "0 AS " + SearchManager.SUGGEST_COLUMN_ICON_2,
                    MediaStore.Audio.Albums.ALBUM + " AS " + SearchManager.SUGGEST_COLUMN_TEXT_1,
                    MediaStore.Audio.Albums.ALBUM + " AS " + SearchManager.SUGGEST_COLUMN_QUERY,
                    MediaStore.Audio.Media.ARTIST + " AS data1",
                    "null AS data2",
                    MediaStore.Audio.Media.ARTIST_KEY +
                    "||' '||" +
                    MediaStore.Audio.Media.ALBUM_KEY +
                    " AS ar_al",
                    "'content://media/external/audio/albums/'||" + MediaStore.Audio.Albums._ID +
                    " AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                    "'2' AS grouporder",
                    "album_key AS itemorder"
            };

            String where = MediaStore.Audio.Media.ALBUM_KEY + " != ''";
            for (int i = 0; i < searchWords.length; i++) {
                where += " AND ar_al LIKE ?";
            }

            qb = new SQLiteQueryBuilder();
            qb.setTables("album_info");
            UQs[1] = qb.buildUnionSubQuery(MediaStore.Audio.Media.MIME_TYPE,
                    ccols, tablecolumns, ccols.length, "album", where, null, null, null);
        }

        // Direct match tracks
        {
            String[] ccols = new String[] {
                    "audio._id AS _id",
                    MediaStore.Audio.Media.MIME_TYPE,
                    "" + R.drawable.ic_search_category_music_song + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1,
                    "0 AS " + SearchManager.SUGGEST_COLUMN_ICON_2,
                    MediaStore.Audio.Media.TITLE + " AS " + SearchManager.SUGGEST_COLUMN_TEXT_1,
                    MediaStore.Audio.Media.TITLE + " AS " + SearchManager.SUGGEST_COLUMN_QUERY,
                    MediaStore.Audio.Media.ARTIST + " AS data1",
                    MediaStore.Audio.Media.ALBUM + " AS data2",
                    MediaStore.Audio.Media.ARTIST_KEY +
                    "||' '||" +
                    MediaStore.Audio.Media.ALBUM_KEY +
                    "||' '||" +
                    MediaStore.Audio.Media.TITLE_KEY +
                    " AS ar_al_ti",
                    "'content://media/external/audio/media/'||audio._id AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                    "'3' AS grouporder",
                    "title_key AS itemorder"
            };

            String where = MediaStore.Audio.Media.TITLE + " != ''";

            for (int i = 0; i < searchWords.length; i++) {
                where += " AND ar_al_ti LIKE ?";
            }
            qb = new SQLiteQueryBuilder();
            qb.setTables("audio");
            UQs[2] = qb.buildUnionSubQuery(MediaStore.Audio.Media.MIME_TYPE,
                    ccols, tablecolumns, ccols.length, "audio/", where, null, null, null);
        }

        if (mCursor != null) {
            mCursor.deactivate();
            mCursor = null;
        }
        if (UQs[0] != null && UQs[1] != null && UQs[2] != null) {
            String union = qb.buildUnionQuery(UQs, "grouporder,itemorder", null);
            mCursor = db.rawQuery(union, wildcardWords3);
        }

        return mCursor;
    }

    @Override
    public String getType(Uri url)
    {
        switch (URI_MATCHER.match(url)) {
            case IMAGES_MEDIA_ID:
            case AUDIO_MEDIA_ID:
            case AUDIO_GENRES_ID_MEMBERS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
            case VIDEO_MEDIA_ID:
                Cursor c = query(url, MIME_TYPE_PROJECTION, null, null, null);
                if (c != null && c.getCount() == 1) {
                    c.moveToFirst();
                    String mimeType = c.getString(1);
                    c.deactivate();
                    return mimeType;
                }
                break;

            case IMAGES_MEDIA:
            case IMAGES_THUMBNAILS:
                return Images.Media.CONTENT_TYPE;
            case IMAGES_THUMBNAILS_ID:
                return "image/jpeg";

            case AUDIO_MEDIA:
            case AUDIO_GENRES_ID_MEMBERS:
            case AUDIO_PLAYLISTS_ID_MEMBERS:
                return Audio.Media.CONTENT_TYPE;

            case AUDIO_GENRES:
            case AUDIO_MEDIA_ID_GENRES:
                return Audio.Genres.CONTENT_TYPE;
            case AUDIO_GENRES_ID:
            case AUDIO_MEDIA_ID_GENRES_ID:
                return Audio.Genres.ENTRY_CONTENT_TYPE;
            case AUDIO_PLAYLISTS:
            case AUDIO_MEDIA_ID_PLAYLISTS:
                return Audio.Playlists.CONTENT_TYPE;
            case AUDIO_PLAYLISTS_ID:
            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                return Audio.Playlists.ENTRY_CONTENT_TYPE;

            case VIDEO_MEDIA:
                return Video.Media.CONTENT_TYPE;
        }
        throw new IllegalStateException("Unknown URL");
    }

    /**
     * Ensures there is a file in the _data column of values, if one isn't
     * present a new file is created.
     *
     * @param initialValues the values passed to insert by the caller
     * @return the new values
     */
    private ContentValues ensureFile(boolean internal, ContentValues initialValues,
            String preferredExtension, String directoryName) {
        ContentValues values;
        String file = initialValues.getAsString("_data");
        if (TextUtils.isEmpty(file)) {
            file = generateFileName(internal, preferredExtension, directoryName);
            values = new ContentValues(initialValues);
            values.put("_data", file);
        } else {
            values = initialValues;
        }

        if (!ensureFileExists(file)) {
            throw new IllegalStateException("Unable to create new file: " + file);
        }
        return values;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues values[]) {
        int match = URI_MATCHER.match(uri);
        if (match == VOLUMES) {
            return super.bulkInsert(uri, values);
        }
        DatabaseHelper database = getDatabaseForUri(uri);
        if (database == null) {
            throw new UnsupportedOperationException(
                    "Unknown URI: " + uri);
        }
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        int numInserted = 0;
        try {
            int len = values.length;
            for (int i = 0; i < len; i++) {
                insertInternal(uri, values[i]);
            }
            numInserted = len;
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return numInserted;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues)
    {
        Uri newUri = insertInternal(uri, initialValues);
        if (newUri != null) {
            getContext().getContentResolver().notifyChange(uri, null);
        }

        return newUri;
    }

    private Uri insertInternal(Uri uri, ContentValues initialValues) {
        long rowId;
        int match = URI_MATCHER.match(uri);

        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (match == MEDIA_SCANNER) {
            mMediaScannerVolume = initialValues.getAsString(MediaStore.MEDIA_SCANNER_VOLUME);
            return MediaStore.getMediaScannerUri();
        }

        Uri newUri = null;
        DatabaseHelper database = getDatabaseForUri(uri);
        if (database == null && match != VOLUMES) {
            throw new UnsupportedOperationException(
                    "Unknown URI: " + uri);
        }
        SQLiteDatabase db = (match == VOLUMES ? null : database.getWritableDatabase());

        if (initialValues == null) {
            initialValues = new ContentValues();
        }

        switch (match) {
            case IMAGES_MEDIA: {
                ContentValues values = ensureFile(database.mInternal, initialValues, ".jpg", "DCIM/Camera");

                values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
                String data = values.getAsString(MediaColumns.DATA);
                if (! values.containsKey(MediaColumns.DISPLAY_NAME)) {
                    computeDisplayName(data, values);
                }
                computeBucketValues(data, values);
                rowId = db.insert("images", "name", values);

                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(
                            Images.Media.getContentUri(uri.getPathSegments().get(0)), rowId);
                }
                break;
            }

            case IMAGES_THUMBNAILS: {
                ContentValues values = ensureFile(database.mInternal, initialValues, ".jpg", "DCIM/.thumbnails");
                rowId = db.insert("thumbnails", "name", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Images.Thumbnails.
                            getContentUri(uri.getPathSegments().get(0)), rowId);
                }
                break;
            }

            case AUDIO_MEDIA: {
                // SQLite Views are read-only, so we need to deconstruct this
                // insert and do inserts into the underlying tables.
                // If doing this here turns out to be a performance bottleneck,
                // consider moving this to native code and using triggers on
                // the view.
                ContentValues values = new ContentValues(initialValues);

                // Insert the artist into the artist table and remove it from
                // the input values
                Object so = values.get("artist");
                String s = (so == null ? "" : so.toString());
                values.remove("artist");
                long artistRowId;
                HashMap<String, Long> artistCache = database.mArtistCache;
                synchronized(artistCache) {
                    Long temp = artistCache.get(s);
                    if (temp == null) {
                        artistRowId = getKeyIdForName(db, "artists", "artist_key", "artist",
                                s, null, artistCache, uri);
                    } else {
                        artistRowId = temp.longValue();
                    }
                }

                // Do the same for the album field
                so = values.get("album");
                s = (so == null ? "" : so.toString());
                values.remove("album");
                long albumRowId;
                HashMap<String, Long> albumCache = database.mAlbumCache;
                synchronized(albumCache) {
                    Long temp = albumCache.get(s);
                    if (temp == null) {
                        String path = values.getAsString("_data");
                        albumRowId = getKeyIdForName(db, "albums", "album_key", "album",
                                s, path, albumCache, uri);
                    } else {
                        albumRowId = temp;
                    }
                }

                values.put("artist_id", Integer.toString((int)artistRowId));
                values.put("album_id", Integer.toString((int)albumRowId));
                so = values.getAsString("title");
                s = (so == null ? "" : so.toString());
                values.put("title_key", MediaStore.Audio.keyFor(s));

                computeDisplayName(values.getAsString("_data"), values);
                values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);

                rowId = db.insert("audio_meta", "duration", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Audio.Media.getContentUri(uri.getPathSegments().get(0)), rowId);
                }
                break;
            }

            case AUDIO_MEDIA_ID_GENRES: {
                Long audioId = Long.parseLong(uri.getPathSegments().get(2));
                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Genres.Members.AUDIO_ID, audioId);
                rowId = db.insert("audio_playlists_map", "genre_id", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case AUDIO_MEDIA_ID_PLAYLISTS: {
                Long audioId = Long.parseLong(uri.getPathSegments().get(2));
                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Playlists.Members.AUDIO_ID, audioId);
                rowId = db.insert("audio_playlists_map", "playlist_id",
                        values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case AUDIO_GENRES: {
                rowId = db.insert("audio_genres", "audio_id", initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Audio.Genres.getContentUri(uri.getPathSegments().get(0)), rowId);
                }
                break;
            }

            case AUDIO_GENRES_ID_MEMBERS: {
                Long genreId = Long.parseLong(uri.getPathSegments().get(3));
                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Genres.Members.GENRE_ID, genreId);
                rowId = db.insert("audio_genres_map", "genre_id", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case AUDIO_PLAYLISTS: {
                ContentValues values = new ContentValues(initialValues);
                values.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis() / 1000);
                rowId = db.insert("audio_playlists", "name", initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Audio.Playlists.getContentUri(uri.getPathSegments().get(0)), rowId);
                }
                break;
            }

            case AUDIO_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                Long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Playlists.Members.PLAYLIST_ID, playlistId);
                rowId = db.insert("audio_playlists_map", "playlist_id",
                        values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case VIDEO_MEDIA: {
                ContentValues values = ensureFile(database.mInternal, initialValues, ".3gp", "video");
                String data = values.getAsString("_data");
                computeDisplayName(data, values);
                computeBucketValues(data, values);
                values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
                rowId = db.insert("video", "artist", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Video.Media.getContentUri(uri.getPathSegments().get(0)), rowId);
                }
                break;
            }

            case AUDIO_ALBUMART:
                if (database.mInternal) {
                    throw new UnsupportedOperationException("no internal album art allowed");
                }
                ContentValues values = null;
                try {
                    values = ensureFile(false, initialValues, "", ALBUM_THUMB_FOLDER);
                } catch (IllegalStateException ex) {
                    // probably no more room to store albumthumbs
                    values = initialValues;
                }
                rowId = db.insert("album_art", "_data", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;

            case VOLUMES:
                return attachVolume(initialValues.getAsString("name"));

            default:
                throw new UnsupportedOperationException("Invalid URI " + uri);
        }

        return newUri;
    }

    private String generateFileName(boolean internal, String preferredExtension, String directoryName)
    {
        // create a random file
        String name = String.valueOf(System.currentTimeMillis());

        if (internal) {
            throw new UnsupportedOperationException("Writing to internal storage is not supported.");
//            return Environment.getDataDirectory()
//                + "/" + directoryName + "/" + name + preferredExtension;
        } else {
            return Environment.getExternalStorageDirectory()
                + "/" + directoryName + "/" + name + preferredExtension;
        }
    }

    private boolean ensureFileExists(String path) {
        File file = new File(path);
        if (file.exists()) {
            return true;
        } else {
            // we will not attempt to create the first directory in the path
            // (for example, do not create /sdcard if the SD card is not mounted)
            int secondSlash = path.indexOf('/', 1);
            if (secondSlash < 1) return false;
            String directoryPath = path.substring(0, secondSlash);
            File directory = new File(directoryPath);
            if (!directory.exists())
                return false;
            file.getParentFile().mkdirs();
            try {
                return file.createNewFile();
            } catch(IOException ioe) {
                Log.e(TAG, "File creation failed", ioe);
            }
            return false;
        }
    }

    private static final class GetTableAndWhereOutParameter {
        public String table;
        public String where;
    }

    static final GetTableAndWhereOutParameter sGetTableAndWhereParam =
            new GetTableAndWhereOutParameter();

    private void getTableAndWhere(Uri uri, int match, String userWhere,
            GetTableAndWhereOutParameter out) {
        String where = null;
        switch (match) {
            case IMAGES_MEDIA_ID:
                out.table = "images";
                where = "_id = " + uri.getPathSegments().get(3);
                break;

            case AUDIO_MEDIA:
                out.table = "audio";
                break;

            case AUDIO_MEDIA_ID:
                out.table = "audio";
                where = "_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_MEDIA_ID_GENRES:
                out.table = "audio_genres";
                where = "audio_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_MEDIA_ID_GENRES_ID:
                out.table = "audio_genres";
                where = "audio_id=" + uri.getPathSegments().get(3) +
                        " AND genre_id=" + uri.getPathSegments().get(5);
               break;

            case AUDIO_MEDIA_ID_PLAYLISTS:
                out.table = "audio_playlists";
                where = "audio_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                out.table = "audio_playlists";
                where = "audio_id=" + uri.getPathSegments().get(3) +
                        " AND playlists_id=" + uri.getPathSegments().get(5);
                break;

            case AUDIO_GENRES:
                out.table = "audio_genres";
                break;

            case AUDIO_GENRES_ID:
                out.table = "audio_genres";
                where = "_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_GENRES_ID_MEMBERS:
                out.table = "audio_genres";
                where = "genre_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_GENRES_ID_MEMBERS_ID:
                out.table = "audio_genres";
                where = "genre_id=" + uri.getPathSegments().get(3) +
                        " AND audio_id =" + uri.getPathSegments().get(5);
                break;

            case AUDIO_PLAYLISTS:
                out.table = "audio_playlists";
                break;

            case AUDIO_PLAYLISTS_ID:
                out.table = "audio_playlists";
                where = "_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_PLAYLISTS_ID_MEMBERS:
                out.table = "audio_playlists_map";
                where = "playlist_id=" + uri.getPathSegments().get(3);
                break;

            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                out.table = "audio_playlists_map";
                where = "playlist_id=" + uri.getPathSegments().get(3) +
                        " AND _id=" + uri.getPathSegments().get(5);
                break;

            case AUDIO_ALBUMART_ID:
                out.table = "album_art";
                where = "album_id=" + uri.getPathSegments().get(3);
                break;

            case VIDEO_MEDIA:
                out.table = "video";
                break;

            case VIDEO_MEDIA_ID:
                out.table = "video";
                where = "_id=" + uri.getPathSegments().get(3);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Unknown or unsupported URL: " + uri.toString());
        }

        // Add in the user requested WHERE clause, if needed
        if (!TextUtils.isEmpty(userWhere)) {
            if (!TextUtils.isEmpty(where)) {
                out.where = where + " AND (" + userWhere + ")";
            } else {
                out.where = userWhere;
            }
        } else {
            out.where = where;
        }
    }

    @Override
    public int delete(Uri uri, String userWhere, String[] whereArgs) {
        int count;
        int match = URI_MATCHER.match(uri);

        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (match == MEDIA_SCANNER) {
            if (mMediaScannerVolume == null) {
                return 0;
            }
            mMediaScannerVolume = null;
            return 1;
        }

        if (match != VOLUMES_ID) {
            DatabaseHelper database = getDatabaseForUri(uri);
            if (database == null) {
                throw new UnsupportedOperationException(
                        "Unknown URI: " + uri);
            }
            SQLiteDatabase db = database.getWritableDatabase();

            synchronized (sGetTableAndWhereParam) {
                getTableAndWhere(uri, match, userWhere, sGetTableAndWhereParam);
                switch (match) {
                    case AUDIO_MEDIA:
                    case AUDIO_MEDIA_ID:
                        count = db.delete("audio_meta",
                                sGetTableAndWhereParam.where, whereArgs);
                        break;
                    default:
                        count = db.delete(sGetTableAndWhereParam.table,
                                sGetTableAndWhereParam.where, whereArgs);
                        break;
                }
                getContext().getContentResolver().notifyChange(uri, null);
            }
        } else {
            detachVolume(uri);
            count = 1;
        }

        return count;
    }

    @Override
    public int update(Uri uri, ContentValues initialValues, String userWhere,
            String[] whereArgs) {
        int count;
        int match = URI_MATCHER.match(uri);

        DatabaseHelper database = getDatabaseForUri(uri);
        if (database == null) {
            throw new UnsupportedOperationException(
                    "Unknown URI: " + uri);
        }
        SQLiteDatabase db = database.getWritableDatabase();

        synchronized (sGetTableAndWhereParam) {
            getTableAndWhere(uri, match, userWhere, sGetTableAndWhereParam);

            switch (match) {
                case AUDIO_MEDIA:
                case AUDIO_MEDIA_ID:
                    {
                        ContentValues values = new ContentValues(initialValues);
                        // Insert the artist into the artist table and remove it from
                        // the input values
                        String so = values.getAsString("artist");
                        if (so != null) {
                            String s = so.toString();
                            values.remove("artist");
                            long artistRowId;
                            HashMap<String, Long> artistCache = database.mArtistCache;
                            synchronized(artistCache) {
                                Long temp = artistCache.get(s);
                                if (temp == null) {
                                    artistRowId = getKeyIdForName(db, "artists", "artist_key", "artist",
                                            s, null, artistCache, uri);
                                } else {
                                    artistRowId = temp.longValue();
                                }
                            }
                            values.put("artist_id", Integer.toString((int)artistRowId));
                        }

                        // Do the same for the album field
                        so = values.getAsString("album");
                        if (so != null) {
                            String s = so.toString();
                            values.remove("album");
                            long albumRowId;
                            HashMap<String, Long> albumCache = database.mAlbumCache;
                            synchronized(albumCache) {
                                Long temp = albumCache.get(s);
                                if (temp == null) {
                                    albumRowId = getKeyIdForName(db, "albums", "album_key", "album",
                                            s, null, albumCache, uri);
                                } else {
                                    albumRowId = temp.longValue();
                                }
                            }
                            values.put("album_id", Integer.toString((int)albumRowId));
                        }

                        // don't allow the title_key field to be updated directly
                        values.remove("title_key");
                        // If the title field is modified, update the title_key
                        so = values.getAsString("title");
                        if (so != null) {
                            String s = so.toString();
                            values.put("title_key", MediaStore.Audio.keyFor(s));
                        }

                        count = db.update("audio_meta", values, sGetTableAndWhereParam.where,
                                whereArgs);
                    }
                    break;
                case IMAGES_MEDIA:
                case IMAGES_MEDIA_ID:
                case VIDEO_MEDIA:
                case VIDEO_MEDIA_ID:
                    {
                        ContentValues values = new ContentValues(initialValues);
                        // Don't allow bucket id or display name to be updated directly.
                        // The same names are used for both images and table columns, so
                        // we use the ImageColumns constants here.
                        values.remove(ImageColumns.BUCKET_ID);
                        values.remove(ImageColumns.BUCKET_DISPLAY_NAME);
                        // If the data is being modified update the bucket values
                        String data = values.getAsString(MediaColumns.DATA);
                        if (data != null) {
                            computeBucketValues(data, values);
                        }
                        count = db.update(sGetTableAndWhereParam.table, values,
                                sGetTableAndWhereParam.where, whereArgs);
                    }
                    break;
                default:
                    count = db.update(sGetTableAndWhereParam.table, initialValues,
                        sGetTableAndWhereParam.where, whereArgs);
                    break;
            }
        }
        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    private static final String[] openFileColumns = new String[] {
        MediaStore.MediaColumns.DATA,
    };

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openFileHelper(uri, mode);
        } catch (FileNotFoundException ex) {
            if (URI_MATCHER.match(uri) == AUDIO_ALBUMART_ID) {
                // Tried to open an album art file which does not exist. Regenerate.
                DatabaseHelper database = getDatabaseForUri(uri);
                if (database == null) {
                    throw ex;
                }
                SQLiteDatabase db = database.getReadableDatabase();
                SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
                int albumid = Integer.parseInt(uri.getPathSegments().get(3));
                qb.setTables("audio");
                qb.appendWhere("album_id=" + albumid);
                Cursor c = qb.query(db,
                        new String [] {
                            MediaStore.Audio.Media.DATA },
                        null, null, null, null, null);
                c.moveToFirst();
                if (!c.isAfterLast()) {
                    String audiopath = c.getString(0);
                    makeThumb(db, audiopath, albumid, uri);
                }
                c.close();
            }
            throw ex;
        }
        return pfd;
    }

    private class Worker implements Runnable {
        private final Object mLock = new Object();
        private Looper mLooper;

        Worker(String name) {
            Thread t = new Thread(null, this, name);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }

        public Looper getLooper() {
            return mLooper;
        }

        public void run() {
            synchronized (mLock) {
                Looper.prepare();
                mLooper = Looper.myLooper();
                mLock.notifyAll();
            }
            Looper.loop();
        }

        public void quit() {
            mLooper.quit();
        }
    }

    private class ThumbData {
        SQLiteDatabase db;
        String path;
        long album_id;
        Uri albumart_uri;
    }

    private void makeThumb(SQLiteDatabase db, String path, long album_id,
            Uri albumart_uri) {
        ThumbData d = new ThumbData();
        d.db = db;
        d.path = path;
        d.album_id = album_id;
        d.albumart_uri = albumart_uri;
        Message msg = mThumbHandler.obtainMessage();
        msg.obj = d;
        msg.sendToTarget();
    }

    private void makeThumb(ThumbData d) {
        SQLiteDatabase db = d.db;
        String path = d.path;
        long album_id = d.album_id;
        Uri albumart_uri = d.albumart_uri;

        try {
            File f = new File(path);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_READ_ONLY);

            MediaScanner scanner = new MediaScanner(getContext());
            byte [] art = scanner.extractAlbumArt(pfd.getFileDescriptor());
            pfd.close();

            // if no embedded art exists, look for AlbumArt.jpg in same directory as the media file
            if (art == null && path != null) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash > 0) {
                    String artPath = path.substring(0, lastSlash + 1) + "AlbumArt.jpg";
                    File file = new File(artPath);
                    if (file.exists()) {
                        art = new byte[(int)file.length()];
                        FileInputStream stream = null;
                        try {
                            stream = new FileInputStream(file);
                            stream.read(art);
                        } catch (IOException ex) {
                            art = null;
                        } finally {
                            if (stream != null) {
                                stream.close();
                            }
                        }
                    }
                }
            }

            Bitmap bm = null;
            if (art != null) {
                try {
                    // get the size of the bitmap
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    opts.inSampleSize = 1;
                    BitmapFactory.decodeByteArray(art, 0, art.length, opts);

                    // request a reasonably sized output image
                    // TODO: don't hardcode the size
                    while (opts.outHeight > 320 || opts.outWidth > 320) {
                        opts.outHeight /= 2;
                        opts.outWidth /= 2;
                        opts.inSampleSize *= 2;
                    }

                    // get the image for real now
                    opts.inJustDecodeBounds = false;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    bm = BitmapFactory.decodeByteArray(art, 0, art.length, opts);
                } catch (Exception e) {
                }
            }
            if (bm != null && bm.getConfig() == null) {
                bm = bm.copy(Bitmap.Config.RGB_565, false);
            }
            if (bm != null) {
                // save bitmap
                Uri out = null;
                // TODO: this could be done more efficiently with a call to db.replace(), which
                // replaces or inserts as needed, making it unnecessary to query() first.
                if (albumart_uri != null) {
                    Cursor c = query(albumart_uri, new String [] { "_data" },
                            null, null, null);
                    c.moveToFirst();
                    if (!c.isAfterLast()) {
                        String albumart_path = c.getString(0);
                        if (ensureFileExists(albumart_path)) {
                            out = albumart_uri;
                        }
                    }
                    c.close();
                } else {
                    ContentValues initialValues = new ContentValues();
                    initialValues.put("album_id", album_id);
                    try {
                        ContentValues values = ensureFile(false, initialValues, "", ALBUM_THUMB_FOLDER);
                        long rowId = db.insert("album_art", "_data", values);
                        if (rowId > 0) {
                            out = ContentUris.withAppendedId(ALBUMART_URI, rowId);
                        }
                    } catch (IllegalStateException ex) {
                        Log.e(TAG, "error creating album thumb file");
                    }
                }
                if (out != null) {
                    boolean success = false;
                    try {
                        OutputStream outstream = getContext().getContentResolver().openOutputStream(out);
                        success = bm.compress(Bitmap.CompressFormat.JPEG, 75, outstream);
                        outstream.close();
                    } catch (FileNotFoundException ex) {
                        Log.e(TAG, "error creating file", ex);
                    } catch (IOException ex) {
                        Log.e(TAG, "error creating file", ex);
                    }
                    if (!success) {
                        // the thumbnail was not written successfully, delete the entry that refers to it
                        getContext().getContentResolver().delete(out, null, null);
                    }
                }
                getContext().getContentResolver().notifyChange(MEDIA_URI, null);
            }
        } catch (IOException ex) {
        }

    }

    /**
     * Look up the artist or album entry for the given name, creating that entry
     * if it does not already exists.
     * @param db        The database
     * @param table     The table to store the key/name pair in.
     * @param keyField  The name of the key-column
     * @param nameField The name of the name-column
     * @param rawName   The name that the calling app was trying to insert into the database
     * @param path      The path to the file being inserted in to the audio table
     * @param cache     The cache to add this entry to
     * @param srcuri    The Uri that prompted the call to this method, used for determining whether this is
     *                  the internal or external database
     * @return          The row ID for this artist/album, or -1 if the provided name was invalid
     */
    private long getKeyIdForName(SQLiteDatabase db, String table, String keyField, String nameField,
            String rawName, String path, HashMap<String, Long> cache, Uri srcuri) {
        long rowId;

        if (rawName == null || rawName.length() == 0) {
            return -1;
        }
        String k = MediaStore.Audio.keyFor(rawName);

        if (k == null) {
            return -1;
        }

        String [] selargs = { k };
        Cursor c = db.query(table, null, keyField + "=?", selargs, null, null, null);

        try {
            switch (c.getCount()) {
                case 0: {
                        // insert new entry into table
                        ContentValues otherValues = new ContentValues();
                        otherValues.put(keyField, k);
                        otherValues.put(nameField, rawName);
                        rowId = db.insert(table, "duration", otherValues);
                        if (path != null && table.equals("albums") &&
                                ! rawName.equals(MediaFile.UNKNOWN_STRING)) {
                            // We just inserted a new album. Now create an album art thumbnail for it.
                            makeThumb(db, path, rowId, null);
                        }
                        if (rowId > 0) {
                            String volume = srcuri.toString().substring(16, 24); // extract internal/external
                            Uri uri = Uri.parse("content://media/" + volume + "/audio/" + table + "/" + rowId);
                            getContext().getContentResolver().notifyChange(uri, null);
                        }
                    }
                    break;
                case 1: {
                        // Use the existing entry
                        c.moveToFirst();
                        rowId = c.getLong(0);

                        // Determine whether the current rawName is better than what's
                        // currently stored in the table, and update the table if it is.
                        String currentFancyName = c.getString(2);
                        String bestName = makeBestName(rawName, currentFancyName);
                        if (!bestName.equals(currentFancyName)) {
                            // update the table with the new name
                            ContentValues newValues = new ContentValues();
                            newValues.put(nameField, bestName);
                            db.update(table, newValues, "rowid="+Integer.toString((int)rowId), null);
                            String volume = srcuri.toString().substring(16, 24); // extract internal/external
                            Uri uri = Uri.parse("content://media/" + volume + "/audio/" + table + "/" + rowId);
                            getContext().getContentResolver().notifyChange(uri, null);
                        }
                    }
                    break;
                default:
                    // corrupt database
                    Log.e(TAG, "Multiple entries in table " + table + " for key " + k);
                    rowId = -1;
                    break;
            }
        } finally {
            if (c != null) c.close();
        }

        if (cache != null && ! rawName.equals(MediaFile.UNKNOWN_STRING)) {
            cache.put(rawName, rowId);
        }
        return rowId;
    }

    /**
     * Returns the best string to use for display, given two names.
     * Note that this function does not necessarily return either one
     * of the provided names; it may decide to return a better alternative
     * (for example, specifying the inputs "Police" and "Police, The" will
     * return "The Police")
     *
     * The basic assumptions are:
     * - longer is better ("The police" is better than "Police")
     * - prefix is better ("The Police" is better than "Police, The")
     * - accents are better ("Mot&ouml;rhead" is better than "Motorhead")
     *
     * @param one The first of the two names to consider
     * @param two The last of the two names to consider
     * @return The actual name to use
     */
    String makeBestName(String one, String two) {
        String name;

        // Longer names are usually better.
        if (one.length() > two.length()) {
            name = one;
        } else {
            // Names with accents are usually better, and conveniently sort later
            if (one.toLowerCase().compareTo(two.toLowerCase()) > 0) {
                name = one;
            } else {
                name = two;
            }
        }

        // Prefixes are better than postfixes.
        if (name.endsWith(", the") || name.endsWith(",the") ||
            name.endsWith(", an") || name.endsWith(",an") ||
            name.endsWith(", a") || name.endsWith(",a")) {
            String fix = name.substring(1 + name.lastIndexOf(','));
            name = fix.trim() + " " + name.substring(0, name.lastIndexOf(','));
        }

        // TODO: word-capitalize the resulting name
        return name;
    }


    /**
     * Looks up the database based on the given URI.
     *
     * @param uri The requested URI
     * @returns the database for the given URI
     */
    private DatabaseHelper getDatabaseForUri(Uri uri) {
        synchronized (mDatabases) {
            if (uri.getPathSegments().size() > 1) {
                return mDatabases.get(uri.getPathSegments().get(0));
            }
        }
        return null;
    }

    /**
     * Attach the database for a volume (internal or external).
     * Does nothing if the volume is already attached, otherwise
     * checks the volume ID and sets up the corresponding database.
     *
     * @param volume to attach, either {@link #INTERNAL_VOLUME} or {@link #EXTERNAL_VOLUME}.
     * @return the content URI of the attached volume.
     */
    private Uri attachVolume(String volume) {
        if (Process.supportsProcesses() && Binder.getCallingPid() != Process.myPid()) {
            throw new SecurityException(
                    "Opening and closing databases not allowed.");
        }

        synchronized (mDatabases) {
            if (mDatabases.get(volume) != null) {  // Already attached
                return Uri.parse("content://media/" + volume);
            }

            DatabaseHelper db;
            if (INTERNAL_VOLUME.equals(volume)) {
                db = new DatabaseHelper(getContext(), INTERNAL_DATABASE_NAME, true);
            } else if (EXTERNAL_VOLUME.equals(volume)) {
                String path = Environment.getExternalStorageDirectory().getPath();
                int volumeID = FileUtils.getFatVolumeId(path);
                if (LOCAL_LOGV) Log.v(TAG, path + " volume ID: " + volumeID);

                // generate database name based on volume ID
                String dbName = "external-" + Integer.toHexString(volumeID) + ".db";
                db = new DatabaseHelper(getContext(), dbName, false);
            } else {
                throw new IllegalArgumentException("There is no volume named " + volume);
            }

            mDatabases.put(volume, db);

            if (!db.mInternal) {
                // clean up stray album art files: delete every file not in the database
                File[] files = new File(
                        Environment.getExternalStorageDirectory(),
                        ALBUM_THUMB_FOLDER).listFiles();
                HashSet<String> fileSet = new HashSet();
                for (int i = 0; files != null && i < files.length; i++) {
                    fileSet.add(files[i].getPath());
                }

                Cursor cursor = query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                        new String[] { MediaStore.Audio.Albums.ALBUM_ART }, null, null, null);
                try {
                    while (cursor != null && cursor.moveToNext()) {
                        fileSet.remove(cursor.getString(0));
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }

                Iterator<String> iterator = fileSet.iterator();
                while (iterator.hasNext()) {
                    String filename = iterator.next();
                    if (LOCAL_LOGV) Log.v(TAG, "deleting obsolete album art " + filename);
                    new File(filename).delete();
                }
            }
        }

        if (LOCAL_LOGV) Log.v(TAG, "Attached volume: " + volume);
        return Uri.parse("content://media/" + volume);
    }

    /**
     * Detach the database for a volume (must be external).
     * Does nothing if the volume is already detached, otherwise
     * closes the database and sends a notification to listeners.
     *
     * @param uri The content URI of the volume, as returned by {@link #attachVolume}
     */
    private void detachVolume(Uri uri) {
        if (Process.supportsProcesses() && Binder.getCallingPid() != Process.myPid()) {
            throw new SecurityException(
                    "Opening and closing databases not allowed.");
        }

        String volume = uri.getPathSegments().get(0);
        if (INTERNAL_VOLUME.equals(volume)) {
            throw new UnsupportedOperationException(
                    "Deleting the internal volume is not allowed");
        } else if (!EXTERNAL_VOLUME.equals(volume)) {
            throw new IllegalArgumentException(
                    "There is no volume named " + volume);
        }

        synchronized (mDatabases) {
            DatabaseHelper database = mDatabases.get(volume);
            if (database == null) return;

            try {
                // touch the database file to show it is most recently used
                File file = new File(database.getReadableDatabase().getPath());
                file.setLastModified(System.currentTimeMillis());
            } catch (SQLException e) {
                Log.e(TAG, "Can't touch database file", e);
            }

            mDatabases.remove(volume);
            database.close();
        }

        getContext().getContentResolver().notifyChange(uri, null);
        if (LOCAL_LOGV) Log.v(TAG, "Detached volume: " + volume);
    }

    private static String TAG = "MediaProvider";
    private static final boolean LOCAL_LOGV = true;
    private static final int DATABASE_VERSION = 72;
    private static final String INTERNAL_DATABASE_NAME = "internal.db";

    // maximum number of cached external databases to keep
    private static final int MAX_EXTERNAL_DATABASES = 3;

    // Delete databases that have not been used in two months
    // 60 days in milliseconds (1000 * 60 * 60 * 24 * 60)
    private static final long OBSOLETE_DATABASE_DB = 5184000000L;

    private HashMap<String, DatabaseHelper> mDatabases;

    private Worker mThumbWorker;
    private Handler mThumbHandler;

    // name of the volume currently being scanned by the media scanner (or null)
    private String mMediaScannerVolume;

    static final String INTERNAL_VOLUME = "internal";
    static final String EXTERNAL_VOLUME = "external";
    static final String ALBUM_THUMB_FOLDER = "albumthumbs";

    // path for writing contents of in memory temp database
    private String mTempDatabasePath;

    private static final int IMAGES_MEDIA = 1;
    private static final int IMAGES_MEDIA_ID = 2;
    private static final int IMAGES_THUMBNAILS = 3;
    private static final int IMAGES_THUMBNAILS_ID = 4;

    private static final int AUDIO_MEDIA = 100;
    private static final int AUDIO_MEDIA_ID = 101;
    private static final int AUDIO_MEDIA_ID_GENRES = 102;
    private static final int AUDIO_MEDIA_ID_GENRES_ID = 103;
    private static final int AUDIO_MEDIA_ID_PLAYLISTS = 104;
    private static final int AUDIO_MEDIA_ID_PLAYLISTS_ID = 105;
    private static final int AUDIO_GENRES = 106;
    private static final int AUDIO_GENRES_ID = 107;
    private static final int AUDIO_GENRES_ID_MEMBERS = 108;
    private static final int AUDIO_GENRES_ID_MEMBERS_ID = 109;
    private static final int AUDIO_PLAYLISTS = 110;
    private static final int AUDIO_PLAYLISTS_ID = 111;
    private static final int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    private static final int AUDIO_PLAYLISTS_ID_MEMBERS_ID = 113;
    private static final int AUDIO_ARTISTS = 114;
    private static final int AUDIO_ARTISTS_ID = 115;
    private static final int AUDIO_ALBUMS = 116;
    private static final int AUDIO_ALBUMS_ID = 117;
    private static final int AUDIO_ARTISTS_ID_ALBUMS = 118;
    private static final int AUDIO_ALBUMART = 119;
    private static final int AUDIO_ALBUMART_ID = 120;

    private static final int VIDEO_MEDIA = 200;
    private static final int VIDEO_MEDIA_ID = 201;

    private static final int VOLUMES = 300;
    private static final int VOLUMES_ID = 301;

    private static final int AUDIO_SEARCH = 400;

    private static final int MEDIA_SCANNER = 500;

    private static final UriMatcher URI_MATCHER =
            new UriMatcher(UriMatcher.NO_MATCH);

    private static final String[] MIME_TYPE_PROJECTION = new String[] {
            MediaStore.MediaColumns._ID, // 0
            MediaStore.MediaColumns.MIME_TYPE, // 1
    };

    private static final String[] EXTERNAL_DATABASE_TABLES = new String[] {
        "images",
        "thumbnails",
        "audio_meta",
        "artists",
        "albums",
        "audio_genres",
        "audio_genres_map",
        "audio_playlists",
        "audio_playlists_map",
        "video",
    };

    static
    {
        URI_MATCHER.addURI("media", "*/images/media", IMAGES_MEDIA);
        URI_MATCHER.addURI("media", "*/images/media/#", IMAGES_MEDIA_ID);
        URI_MATCHER.addURI("media", "*/images/thumbnails", IMAGES_THUMBNAILS);
        URI_MATCHER.addURI("media", "*/images/thumbnails/#", IMAGES_THUMBNAILS_ID);

        URI_MATCHER.addURI("media", "*/audio/media", AUDIO_MEDIA);
        URI_MATCHER.addURI("media", "*/audio/media/#", AUDIO_MEDIA_ID);
        URI_MATCHER.addURI("media", "*/audio/media/#/genres", AUDIO_MEDIA_ID_GENRES);
        URI_MATCHER.addURI("media", "*/audio/media/#/genres/#", AUDIO_MEDIA_ID_GENRES_ID);
        URI_MATCHER.addURI("media", "*/audio/media/#/playlists", AUDIO_MEDIA_ID_PLAYLISTS);
        URI_MATCHER.addURI("media", "*/audio/media/#/playlists/#", AUDIO_MEDIA_ID_PLAYLISTS_ID);
        URI_MATCHER.addURI("media", "*/audio/genres", AUDIO_GENRES);
        URI_MATCHER.addURI("media", "*/audio/genres/#", AUDIO_GENRES_ID);
        URI_MATCHER.addURI("media", "*/audio/genres/#/members", AUDIO_GENRES_ID_MEMBERS);
        URI_MATCHER.addURI("media", "*/audio/genres/#/members/#", AUDIO_GENRES_ID_MEMBERS_ID);
        URI_MATCHER.addURI("media", "*/audio/playlists", AUDIO_PLAYLISTS);
        URI_MATCHER.addURI("media", "*/audio/playlists/#", AUDIO_PLAYLISTS_ID);
        URI_MATCHER.addURI("media", "*/audio/playlists/#/members", AUDIO_PLAYLISTS_ID_MEMBERS);
        URI_MATCHER.addURI("media", "*/audio/playlists/#/members/#", AUDIO_PLAYLISTS_ID_MEMBERS_ID);
        URI_MATCHER.addURI("media", "*/audio/artists", AUDIO_ARTISTS);
        URI_MATCHER.addURI("media", "*/audio/artists/#", AUDIO_ARTISTS_ID);
        URI_MATCHER.addURI("media", "*/audio/artists/#/albums", AUDIO_ARTISTS_ID_ALBUMS);
        URI_MATCHER.addURI("media", "*/audio/albums", AUDIO_ALBUMS);
        URI_MATCHER.addURI("media", "*/audio/albums/#", AUDIO_ALBUMS_ID);
        URI_MATCHER.addURI("media", "*/audio/albumart", AUDIO_ALBUMART);
        URI_MATCHER.addURI("media", "*/audio/albumart/#", AUDIO_ALBUMART_ID);

        URI_MATCHER.addURI("media", "*/video/media", VIDEO_MEDIA);
        URI_MATCHER.addURI("media", "*/video/media/#", VIDEO_MEDIA_ID);

        URI_MATCHER.addURI("media", "*/media_scanner", MEDIA_SCANNER);

        URI_MATCHER.addURI("media", "*", VOLUMES_ID);
        URI_MATCHER.addURI("media", null, VOLUMES);

        URI_MATCHER.addURI("media", "*/audio/" + SearchManager.SUGGEST_URI_PATH_QUERY,
                AUDIO_SEARCH);
        URI_MATCHER.addURI("media", "*/audio/" + SearchManager.SUGGEST_URI_PATH_QUERY + "/*",
                AUDIO_SEARCH);
    }
}
