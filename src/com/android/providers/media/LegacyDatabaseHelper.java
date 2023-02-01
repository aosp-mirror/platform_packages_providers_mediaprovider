/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Environment;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.Logging;
import com.android.providers.media.util.MimeUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Wrapper class for a specific database (associated with one particular
 * external card, or with internal storage).  Can open the actual database
 * on demand, create and upgrade the schema, etc.
 */
public class LegacyDatabaseHelper extends SQLiteOpenHelper implements AutoCloseable {
    @VisibleForTesting
    static final String TEST_RECOMPUTE_DB = "test_recompute";
    @VisibleForTesting
    static final String TEST_UPGRADE_DB = "test_upgrade";
    @VisibleForTesting
    static final String TEST_DOWNGRADE_DB = "test_downgrade";
    @VisibleForTesting
    public static final String TEST_CLEAN_DB = "test_clean";

    static final String INTERNAL_DATABASE_NAME = "internal.db";
    static final String EXTERNAL_DATABASE_NAME = "external.db";

    final Context mContext;
    final String mName;
    final boolean mLegacyProvider;
    final Set<String> mFilterVolumeNames = new ArraySet<>();

    /**
     * Unfortunately we can have multiple instances of LegacyDatabaseHelper, causing
     * onUpgrade() to be called multiple times if those instances happen to run in
     * parallel. To prevent that, keep track of which databases we've already upgraded.
     */
    static final Set<String> sDatabaseUpgraded = new HashSet<>();
    static final Object sLock = new Object();
    /**
     * Lock used to guard against deadlocks in SQLite; the write lock is used to
     * guard any schema changes, and the read lock is used for all other
     * database operations.
     * <p>
     * As a concrete example: consider the case where the primary database
     * connection is performing a schema change inside a transaction, while a
     * secondary connection is waiting to begin a transaction. When the primary
     * database connection changes the schema, it attempts to close all other
     * database connections, which then deadlocks.
     */
    private final ReentrantReadWriteLock mSchemaLock = new ReentrantReadWriteLock();

    public LegacyDatabaseHelper(Context context, String name, boolean legacyProvider) {
        super(context, name, null, VERSION_LATEST);
        mContext = context;
        mName = name;
        if (!isInternal() && !isExternal()) {
            throw new IllegalStateException("Db must be internal/external");
        }
        mLegacyProvider = legacyProvider;

        // Configure default filters until we hear differently
        if (isInternal()) {
            mFilterVolumeNames.add(MediaStore.VOLUME_INTERNAL);
        } else if (isExternal()) {
            mFilterVolumeNames.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }

        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        throw new UnsupportedOperationException("All database operations must be routed through"
                + " runWithTransaction() or runWithoutTransaction() to avoid deadlocks");
    }

    @Override
    public SQLiteDatabase getWritableDatabase() {
        throw new UnsupportedOperationException("All database operations must be routed through"
                + " runWithTransaction() or runWithoutTransaction() to avoid deadlocks");
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        Log.v(TAG, "onConfigure() for " + mName);
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        Log.v(TAG, "onCreate() for " + mName);
        mSchemaLock.writeLock().lock();
        try {
            updateDatabase(db, 0);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onUpgrade() for " + mName + " from " + oldV + " to " + newV);
        mSchemaLock.writeLock().lock();
        try {
            synchronized (sLock) {
                if (sDatabaseUpgraded.contains(mName)) {
                    Log.v(TAG, "Skipping onUpgrade() for " + mName +
                            " because it was already upgraded.");
                    return;
                } else {
                    sDatabaseUpgraded.add(mName);
                }
            }
            updateDatabase(db, oldV);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onDowngrade() for " + mName + " from " + oldV + " to " + newV);
        mSchemaLock.writeLock().lock();
        try {
            createLatestSchema(db);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
    }

    @Override
    public void onOpen(final SQLiteDatabase db) {
        Log.v(TAG, "onOpen() for " + mName);
    }

    /**
     * Local state related to any transaction currently active on a specific
     * thread, such as collecting the set of {@link Uri} that should be notified
     * upon transaction success.
     * <p>
     * We suppress Error Prone here because there are multiple
     * {@link LegacyDatabaseHelper} instances within the process, and state needs to
     * be tracked uniquely per-helper.
     */
    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<TransactionState> mTransactionState = new ThreadLocal<>();

    private static class TransactionState {
        /**
         * Flag indicating if this transaction has been marked as being
         * successful.
         */
        public boolean successful;

        /**
         * List of tasks that should be executed in a blocking fashion when this
         * transaction has been successfully finished.
         */
        public final ArrayList<Runnable> blockingTasks = new ArrayList<>();

        /**
         * List of tasks that should be enqueued onto {@link BackgroundThread}
         * after any {@link #notifyChanges} have been dispatched. We keep this
         * as a separate pass to ensure that we don't risk running in parallel
         * with other more important tasks.
         */
        public final ArrayList<Runnable> backgroundTasks = new ArrayList<>();
    }

    public void beginTransaction() {
        Trace.beginSection(traceSectionName("transaction"));
        Trace.beginSection(traceSectionName("beginTransaction"));
        try {
            beginTransactionInternal();
        } finally {
            // Only end the "beginTransaction" section. We'll end the "transaction" section in
            // endTransaction().
            Trace.endSection();
        }
    }

    private void beginTransactionInternal() {
        if (mTransactionState.get() != null) {
            throw new IllegalStateException("Nested transactions not supported");
        }
        mTransactionState.set(new TransactionState());

        final SQLiteDatabase db = super.getWritableDatabase();
        mSchemaLock.readLock().lock();
        db.beginTransaction();
        db.execSQL("UPDATE local_metadata SET generation=generation+1;");
    }

    public void setTransactionSuccessful() {
        final TransactionState state = mTransactionState.get();
        if (state == null) {
            throw new IllegalStateException("No transaction in progress");
        }
        state.successful = true;

        final SQLiteDatabase db = super.getWritableDatabase();
        db.setTransactionSuccessful();
    }

    public void endTransaction() {
        Trace.beginSection(traceSectionName("endTransaction"));
        try {
            endTransactionInternal();
        } finally {
            Trace.endSection();
            // End "transaction" section, which we started in beginTransaction().
            Trace.endSection();
        }
    }

    private void endTransactionInternal() {
        final TransactionState state = mTransactionState.get();
        if (state == null) {
            throw new IllegalStateException("No transaction in progress");
        }
        mTransactionState.remove();

        final SQLiteDatabase db = super.getWritableDatabase();
        db.endTransaction();
        mSchemaLock.readLock().unlock();

        if (state.successful) {
            for (int i = 0; i < state.blockingTasks.size(); i++) {
                state.blockingTasks.get(i).run();
            }
            // We carefully "phase" our two sets of work here to ensure that we
            // completely finish dispatching all change notifications before we
            // process background tasks, to ensure that the background work
            // doesn't steal resources from the more important foreground work
            ForegroundThread.getExecutor().execute(() -> {
                // Now that we've finished with all our important work, we can
                // finally kick off any internal background tasks
                for (int i = 0; i < state.backgroundTasks.size(); i++) {
                    BackgroundThread.getExecutor().execute(state.backgroundTasks.get(i));
                }
            });
        }
    }

    /**
     * Execute the given operation inside a transaction. If the calling thread
     * is not already in an active transaction, this method will wrap the given
     * runnable inside a new transaction.
     */
    public @NonNull
    <T> T runWithTransaction(@NonNull Function<SQLiteDatabase, T> op) {
        // We carefully acquire the database here so that any schema changes can
        // be applied before acquiring the read lock below
        final SQLiteDatabase db = super.getWritableDatabase();

        if (mTransactionState.get() != null) {
            // Already inside a transaction, so we can run directly
            return op.apply(db);
        } else {
            // Not inside a transaction, so we need to make one
            beginTransaction();
            try {
                final T res = op.apply(db);
                setTransactionSuccessful();
                return res;
            } finally {
                endTransaction();
            }
        }
    }

    /**
     * Execute the given operation regardless of the calling thread being in an
     * active transaction or not.
     */
    public @NonNull
    <T> T runWithoutTransaction(@NonNull Function<SQLiteDatabase, T> op) {
        // We carefully acquire the database here so that any schema changes can
        // be applied before acquiring the read lock below
        final SQLiteDatabase db = super.getWritableDatabase();

        if (mTransactionState.get() != null) {
            // Already inside a transaction, so we can run directly
            return op.apply(db);
        } else {
            // We still need to acquire a schema read lock
            mSchemaLock.readLock().lock();
            try {
                return op.apply(db);
            } finally {
                mSchemaLock.readLock().unlock();
            }
        }
    }

    /**
     * This method cleans up any files created by android.media.MiniThumbFile, removed after P.
     * It's triggered during database update only, in order to run only once.
     */
    private static void deleteLegacyThumbnailData() {
        File directory = new File(Environment.getExternalStorageDirectory(), "/DCIM/.thumbnails");

        final FilenameFilter filter = (dir, filename) -> filename.startsWith(".thumbdata");
        final File[] files = directory.listFiles(filter);
        for (File f : (files != null) ? files : new File[0]) {
            if (!f.delete()) {
                Log.e(TAG, "Failed to delete legacy thumbnail data " + f.getAbsolutePath());
            }
        }
    }

    @Deprecated
    public static int getDatabaseVersion() {
        // We now use static versions defined internally instead of the
        // versionCode from the manifest
        return VERSION_LATEST;
    }

    @VisibleForTesting
    static void makePristineSchema(SQLiteDatabase db) {
        // We are dropping all tables and recreating new schema. This
        // is a clear indication of major change in MediaStore version.
        // Hence reset the Uuid whenever we change the schema.
        resetAndGetUuid(db);

        // drop all triggers
        Cursor c = db.query("sqlite_master", new String[]{"name"}, "type is 'trigger'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TRIGGER IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all views
        c = db.query("sqlite_master", new String[]{"name"}, "type is 'view'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP VIEW IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all indexes
        c = db.query("sqlite_master", new String[]{"name"}, "type is 'index'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all tables
        c = db.query("sqlite_master", new String[]{"name"}, "type is 'table'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TABLE IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createLatestSchema(SQLiteDatabase db) {
        // We're about to start all ID numbering from scratch, so revoke any
        // outstanding permission grants to ensure we don't leak data
        try {
            final PackageInfo pkg = mContext.getPackageManager().getPackageInfo(
                    mContext.getPackageName(), PackageManager.GET_PROVIDERS);
            if (pkg != null && pkg.providers != null) {
                for (ProviderInfo provider : pkg.providers) {
                    mContext.revokeUriPermission(Uri.parse("content://" + provider.authority),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to revoke permissions", e);
        }

        makePristineSchema(db);

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

        if (isExternal()) {
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
        }

        createLatestViews(db);
        createLatestIndexes(db);
    }

    private static void makePristineViews(SQLiteDatabase db) {
        // drop all views
        Cursor c = db.query("sqlite_master", new String[]{"name"}, "type is 'view'",
                null, null, null, null);
        while (c.moveToNext()) {
            db.execSQL("DROP VIEW IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createLatestViews(SQLiteDatabase db) {
        makePristineViews(db);
    }

    private static void makePristineIndexes(SQLiteDatabase db) {
        // drop all indexes
        Cursor c = db.query("sqlite_master", new String[]{"name"}, "type is 'index'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private static void createLatestIndexes(SQLiteDatabase db) {
        makePristineIndexes(db);

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

    private static void updateCollationKeys(SQLiteDatabase db) {
        // Delete albums and artists, then clear the modification time on songs, which
        // will cause the media scanner to rescan everything, rebuilding the artist and
        // album tables along the way, while preserving playlists.
        // We need this rescan because ICU also changed, and now generates different
        // collation keys
        db.execSQL("DELETE from albums");
        db.execSQL("DELETE from artists");
        db.execSQL("UPDATE files SET date_modified=0;");
    }

    private static void updateAddTitleResource(SQLiteDatabase db) {
        // Add the column used for title localization, and force a rescan of any
        // ringtones, alarms and notifications that may be using it.
        db.execSQL("ALTER TABLE files ADD COLUMN title_resource_uri TEXT");
        db.execSQL("UPDATE files SET date_modified=0"
                + " WHERE (is_alarm IS 1) OR (is_ringtone IS 1) OR (is_notification IS 1)");
    }

    private static void updateAddOwnerPackageName(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN owner_package_name TEXT DEFAULT NULL");

        // Derive new column value based on well-known paths
        try (Cursor c = db.query("files", new String[]{FileColumns._ID, FileColumns.DATA},
                FileColumns.DATA + " REGEXP '" + FileUtils.PATTERN_OWNED_PATH.pattern() + "'",
                null, null, null, null, null)) {
            Log.d(TAG, "Updating " + c.getCount() + " entries with well-known owners");

            final Matcher m = FileUtils.PATTERN_OWNED_PATH.matcher("");
            final ContentValues values = new ContentValues();

            while (c.moveToNext()) {
                final long id = c.getLong(0);
                final String data = c.getString(1);
                m.reset(data);
                if (m.matches()) {
                    final String packageName = m.group(1);
                    values.clear();
                    values.put(FileColumns.OWNER_PACKAGE_NAME, packageName);
                    db.update("files", values, "_id=" + id, null);
                }
            }
        }
    }

    private static void updateAddColorSpaces(SQLiteDatabase db) {
        // Add the color aspects related column used for HDR detection etc.
        db.execSQL("ALTER TABLE files ADD COLUMN color_standard INTEGER;");
        db.execSQL("ALTER TABLE files ADD COLUMN color_transfer INTEGER;");
        db.execSQL("ALTER TABLE files ADD COLUMN color_range INTEGER;");
    }

    private static void updateAddHashAndPending(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _hash BLOB DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_pending INTEGER DEFAULT 0;");
    }

    private static void updateAddDownloadInfo(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_download INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN download_uri TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN referer_uri TEXT DEFAULT NULL;");
    }

    private static void updateAddAudiobook(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_audiobook INTEGER DEFAULT 0;");
    }

    private static void updateAddRecording(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_recording INTEGER DEFAULT 0;");
        // We add the column is_recording, rescan all music files
        db.execSQL("UPDATE files SET date_modified=0 WHERE is_music=1;");
    }

    private static void updateAddRedactedUriId(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN redacted_uri_id TEXT DEFAULT NULL;");
    }

    private static void updateClearLocation(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET latitude=NULL, longitude=NULL;");
    }

    private static void updateSetIsDownload(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET is_download=1 WHERE _data REGEXP '"
                + FileUtils.PATTERN_DOWNLOADS_FILE + "'");
    }

    private static void updateAddExpiresAndTrashed(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN date_expires INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_trashed INTEGER DEFAULT 0;");
    }

    private static void updateAddGroupId(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN group_id INTEGER DEFAULT NULL;");
    }

    private static void updateAddDirectories(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN primary_directory TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN secondary_directory TEXT DEFAULT NULL;");
    }

    private static void updateAddXmpMm(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN document_id TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN instance_id TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN original_document_id TEXT DEFAULT NULL;");
    }

    private static void updateAddPath(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN relative_path TEXT DEFAULT NULL;");
    }

    private static void updateAddVolumeName(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN volume_name TEXT DEFAULT NULL;");
    }

    private static void updateDirsMimeType(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET mime_type=NULL WHERE format="
                + MtpConstants.FORMAT_ASSOCIATION);
    }

    private static void updateRelativePath(SQLiteDatabase db) {
        db.execSQL("UPDATE files"
                + " SET " + MediaColumns.RELATIVE_PATH + "=" + MediaColumns.RELATIVE_PATH + "||'/'"
                + " WHERE " + MediaColumns.RELATIVE_PATH + " IS NOT NULL"
                + " AND " + MediaColumns.RELATIVE_PATH + " NOT LIKE '%/';");
    }

    private static void updateAddTranscodeSatus(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _transcode_status INTEGER DEFAULT 0;");
    }

    private static void updateAddSpecialFormat(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _special_format INTEGER DEFAULT NULL;");
    }

    private static void updateSpecialFormatToNotDetected(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET _special_format=NULL WHERE _special_format=0");
    }

    private static void updateAddVideoCodecType(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _video_codec_type TEXT DEFAULT NULL;");
    }

    private static void updateClearDirectories(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET primary_directory=NULL, secondary_directory=NULL;");
    }

    private static void updateRestructureAudio(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN artist_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN album_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre_id INTEGER;");

        db.execSQL("DROP TABLE IF EXISTS artists;");
        db.execSQL("DROP TABLE IF EXISTS albums;");
        db.execSQL("DROP TABLE IF EXISTS audio_genres;");
        db.execSQL("DROP TABLE IF EXISTS audio_genres_map;");

        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");

        db.execSQL("DROP INDEX IF EXISTS album_idx");
        db.execSQL("DROP INDEX IF EXISTS albumkey_index");
        db.execSQL("DROP INDEX IF EXISTS artist_idx");
        db.execSQL("DROP INDEX IF EXISTS artistkey_index");

        // Since we're radically changing how the schema is defined, the
        // simplest path forward is to rescan all audio files
        db.execSQL("UPDATE files SET date_modified=0 WHERE media_type=2;");
    }

    private static void updateAddMetadata(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN author TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN bitrate INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN capture_framerate REAL DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN cd_track_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN compilation INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN disc_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_favorite INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN num_tracks INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN writer TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN exposure_time TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN f_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN iso INTEGER DEFAULT NULL;");
    }

    private static void updateAddSceneCaptureType(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN scene_capture_type INTEGER DEFAULT NULL;");
    }

    private static void updateMigrateLogs(SQLiteDatabase db) {
        // Migrate any existing logs to new system
        try (Cursor c = db.query("log", new String[]{"time", "message"},
                null, null, null, null, null)) {
            while (c.moveToNext()) {
                final String time = c.getString(0);
                final String message = c.getString(1);
                Logging.logPersistent("Historical log " + time + " " + message);
            }
        }
        db.execSQL("DELETE FROM log;");
    }

    private static void updateAddLocalMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");
    }

    private static void updateAddGeneration(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN generation_added INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN generation_modified INTEGER DEFAULT 0;");
    }

    private static void updateAddXmp(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN xmp BLOB DEFAULT NULL;");
    }

    private static void updateAudioAlbumId(SQLiteDatabase db) {
        // We change the logic for generating album id, rescan all audio files
        db.execSQL("UPDATE files SET date_modified=0 WHERE media_type=2;");
    }

    private static void updateAddModifier(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _modifier INTEGER DEFAULT 0;");
        // For existing files, set default value as _MODIFIER_MEDIA_SCAN
        db.execSQL("UPDATE files SET _modifier=3;");
    }

    private static void updateAddDeletedMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deleted_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "old_id INTEGER UNIQUE, generation_modified INTEGER NOT NULL)");
    }

    private void updateUserId(SQLiteDatabase db) {
        db.execSQL(String.format(Locale.ROOT,
                "ALTER TABLE files ADD COLUMN _user_id INTEGER DEFAULT %d;",
                UserHandle.myUserId()));
    }

    private static void recomputeDataValues(SQLiteDatabase db) {
        try (Cursor c = db.query("files", new String[]{FileColumns._ID, FileColumns.DATA},
                null, null, null, null, null, null)) {
            Log.d(TAG, "Recomputing " + c.getCount() + " data values");

            final ContentValues values = new ContentValues();
            while (c.moveToNext()) {
                values.clear();
                final long id = c.getLong(0);
                final String data = c.getString(1);
                values.put(FileColumns.DATA, data);
                FileUtils.computeValuesFromData(values, /*isForFuse*/ false);
                values.remove(FileColumns.DATA);
                if (!values.isEmpty()) {
                    db.update("files", values, "_id=" + id, null);
                }
            }
        }
    }

    private static void recomputeMediaTypeValues(SQLiteDatabase db) {
        // Only update the files with MEDIA_TYPE_NONE.
        final String selection = FileColumns.MEDIA_TYPE + "=?";
        final String[] selectionArgs = new String[]{String.valueOf(FileColumns.MEDIA_TYPE_NONE)};

        ArrayMap<Long, Integer> newMediaTypes = new ArrayMap<>();
        try (Cursor c = db.query("files", new String[]{FileColumns._ID, FileColumns.MIME_TYPE},
                selection, selectionArgs, null, null, null, null)) {
            Log.d(TAG, "Recomputing " + c.getCount() + " MediaType values");

            // Accumulate all the new MEDIA_TYPE updates.
            while (c.moveToNext()) {
                final long id = c.getLong(0);
                final String mimeType = c.getString(1);
                // Only update Document and Subtitle media type
                if (MimeUtils.isSubtitleMimeType(mimeType)) {
                    newMediaTypes.put(id, FileColumns.MEDIA_TYPE_SUBTITLE);
                } else if (MimeUtils.isDocumentMimeType(mimeType)) {
                    newMediaTypes.put(id, FileColumns.MEDIA_TYPE_DOCUMENT);
                }
            }
        }
        // Now, update all the new MEDIA_TYPE values.
        final ContentValues values = new ContentValues();
        for (long id : newMediaTypes.keySet()) {
            values.clear();
            values.put(FileColumns.MEDIA_TYPE, newMediaTypes.get(id));
            db.update("files", values, "_id=" + id, null);
        }
    }

    // Leave some gaps in database version tagging to allow T schema changes
    // to go independent of U schema changes.
    static final int VERSION_U = 1400;
    public static final int VERSION_LATEST = VERSION_U;

    /**
     * This method takes care of updating all the tables in the database to the
     * current version, creating them if necessary.
     * This method can only update databases at schema 700 or higher, which was
     * used by the KitKat release. Older database will be cleared and recreated.
     *
     * @param db Database
     */
    private void updateDatabase(SQLiteDatabase db, int fromVersion) {
        if (fromVersion < 700) {
            // Anything older than KK is recreated from scratch
            createLatestSchema(db);
        } else {
            boolean recomputeDataValues = false;
            if (fromVersion < 800) {
                updateCollationKeys(db);
            }
            if (fromVersion < 900) {
                updateAddTitleResource(db);
            }
            if (fromVersion < 1000) {
                updateAddOwnerPackageName(db);
            }
            if (fromVersion < 1003) {
                updateAddColorSpaces(db);
            }
            if (fromVersion < 1004) {
                updateAddHashAndPending(db);
            }
            if (fromVersion < 1005) {
                updateAddDownloadInfo(db);
            }
            if (fromVersion < 1006) {
                updateAddAudiobook(db);
            }
            if (fromVersion < 1007) {
                updateClearLocation(db);
            }
            if (fromVersion < 1008) {
                updateSetIsDownload(db);
            }
            if (fromVersion < 1009) {
                // This database version added "secondary_bucket_id", but that
                // column name was refactored in version 1013 below, so this
                // update step is no longer needed.
            }
            if (fromVersion < 1010) {
                updateAddExpiresAndTrashed(db);
            }
            if (fromVersion < 1012) {
                recomputeDataValues = true;
            }
            if (fromVersion < 1013) {
                updateAddGroupId(db);
                updateAddDirectories(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1014) {
                updateAddXmpMm(db);
            }
            if (fromVersion < 1015) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1016) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1017) {
                updateSetIsDownload(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1018) {
                updateAddPath(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1019) {
                // Only trigger during "external", so that it runs only once.
                if (isExternal()) {
                    deleteLegacyThumbnailData();
                }
            }
            if (fromVersion < 1020) {
                updateAddVolumeName(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1021) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1022) {
                updateDirsMimeType(db);
            }
            if (fromVersion < 1023) {
                updateRelativePath(db);
            }
            if (fromVersion < 1100) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1101) {
                updateClearDirectories(db);
            }
            if (fromVersion < 1102) {
                updateRestructureAudio(db);
            }
            if (fromVersion < 1103) {
                updateAddMetadata(db);
            }
            if (fromVersion < 1104) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1105) {
                recomputeDataValues = true;
            }
            if (fromVersion < 1106) {
                updateMigrateLogs(db);
            }
            if (fromVersion < 1107) {
                updateAddSceneCaptureType(db);
            }
            if (fromVersion < 1108) {
                updateAddLocalMetadata(db);
            }
            if (fromVersion < 1109) {
                updateAddGeneration(db);
            }
            if (fromVersion < 1110) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1111) {
                recomputeMediaTypeValues(db);
            }
            if (fromVersion < 1112) {
                updateAddXmp(db);
            }
            if (fromVersion < 1113) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1114) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1115) {
                updateAudioAlbumId(db);
            }
            if (fromVersion < 1200) {
                updateAddTranscodeSatus(db);
            }
            if (fromVersion < 1201) {
                updateAddVideoCodecType(db);
            }
            if (fromVersion < 1202) {
                updateAddModifier(db);
            }
            if (fromVersion < 1203) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1204) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1205) {
                updateAddRecording(db);
            }
            if (fromVersion < 1206) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1207) {
                updateAddRedactedUriId(db);
            }
            if (fromVersion < 1208) {
                updateUserId(db);
            }
            if (fromVersion < 1209) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1301) {
                updateAddDeletedMediaTable(db);
            }
            if (fromVersion < 1302) {
                updateAddSpecialFormat(db);
            }
            if (fromVersion < 1303) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1304) {
                updateSpecialFormatToNotDetected(db);
            }
            if (fromVersion < 1305) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1306) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1307) {
                // This is to ensure Animated Webp files are tagged
                updateSpecialFormatToNotDetected(db);
            }
            if (fromVersion < 1308) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1400) {
                // Empty version bump to ensure triggers are recreated
            }

            // If this is the legacy database, it's not worth recomputing data
            // values locally, since they'll be recomputed after the migration
            if (mLegacyProvider) {
                recomputeDataValues = false;
            }

            if (recomputeDataValues) {
                recomputeDataValues(db);
            }
        }

        // Always recreate latest views and triggers during upgrade; they're
        // cheap and it's an easy way to ensure they're defined consistently
        createLatestViews(db);

        getOrCreateUuid(db);
    }

    private static final String XATTR_UUID = "user.uuid";

    /**
     * Return a UUID for the given database. If the database is deleted or
     * otherwise corrupted, then a new UUID will automatically be generated.
     */
    public static @NonNull
    String getOrCreateUuid(@NonNull SQLiteDatabase db) {
        try {
            return new String(Os.getxattr(db.getPath(), XATTR_UUID));
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENODATA) {
                // Doesn't exist yet, so generate and persist a UUID
                return resetAndGetUuid(db);
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private static @NonNull
    String resetAndGetUuid(SQLiteDatabase db) {
        final String uuid = UUID.randomUUID().toString();
        try {
            Os.setxattr(db.getPath(), XATTR_UUID, uuid.getBytes(), 0);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
        return uuid;
    }

    public boolean isInternal() {
        return mName.equals(INTERNAL_DATABASE_NAME);
    }

    public boolean isExternal() {
        // Matches test dbs as external
        switch (mName) {
            case EXTERNAL_DATABASE_NAME:
            case TEST_RECOMPUTE_DB:
            case TEST_UPGRADE_DB:
            case TEST_DOWNGRADE_DB:
            case TEST_CLEAN_DB:
                return true;
            default:
                return false;
        }
    }

    private String traceSectionName(@NonNull String method) {
        return "LegacyDH[" + getDatabaseName() + "]." + method;
    }
}
