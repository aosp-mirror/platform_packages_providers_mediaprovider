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

import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID;

import static com.android.providers.media.photopicker.PickerSyncController.PICKER_SYNC_PREFS_FILE_NAME;
import static com.android.providers.media.photopicker.PickerSyncController.getPrefsKey;
import static com.android.providers.media.util.MimeUtils.getExtensionFromMimeType;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.PickerSyncController;

/**
 * Wrapper class for the photo picker database. Can open the actual database
 * on demand, create and upgrade the schema, etc.
 *
 * @see DatabaseHelper
 */
public class PickerDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "PickerDatabaseHelper";

    public static final String PICKER_DATABASE_NAME = "picker.db";

    private static final int VERSION_U = 11;
    @VisibleForTesting
    public static final int VERSION_INTRODUCING_MEDIA_GRANTS_TABLE = 12;
    @VisibleForTesting
    public static final int VERSION_INTRODUCING_DE_SELECTIONS_TABLE = 13;
    public static final int VERSION_INTRODUCING_CATEGORY_TABLES = 15;
    public static final int VERSION_INTRODUCING_SEARCH_SUGGESTION_TABLES = 16;
    public static final int VERSION_UPDATING_SEARCH_TABLES = 17;
    private static final int VERSION_INTRODUCING_OWNED_PHOTOS = 18;
    public static final int VERSION_LATEST = VERSION_INTRODUCING_OWNED_PHOTOS;

    final Context mContext;
    final String mName;
    final int mVersion;

    public PickerDatabaseHelper(Context context) {
        this(context, PICKER_DATABASE_NAME, VERSION_LATEST);
    }

    public PickerDatabaseHelper(Context context, String name, int version) {
        super(context, name, null, version);
        mContext = context;
        mName = name;
        mVersion = version;

        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        Log.v(TAG, "onCreate() for " + mName);

        resetData(db);
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onUpgrade() for " + mName + " from " + oldV + " to " + newV);

        // Minimum compatible version with database migrations is VERSION_U.
        // Any database lower than VERSION_U needs to be reset with latest schema.
        if (oldV < VERSION_U) {
            resetData(db);
            return;
        }

        // If the version is at least VERSION_U (see block above), then the
        // database schema is fine, and all that's required is to add the
        // new media_grants table.
        if (oldV < VERSION_INTRODUCING_MEDIA_GRANTS_TABLE) {
            createMediaGrantsTable(db);
        }
        if (oldV < VERSION_INTRODUCING_DE_SELECTIONS_TABLE) {
            // Create de_selection table in picker.db if we are upgrading from a version where
            // de_selection table did not exist.
            createDeselectionTable(db);
        }
        if (oldV < VERSION_INTRODUCING_CATEGORY_TABLES) {
            createMediaSetsTable(db);
            createMediaInMediaSetsTable(db);
        }
        if (oldV < VERSION_INTRODUCING_SEARCH_SUGGESTION_TABLES) {
            // Create picker search suggestion tables if the database does not already contain it.
            createSearchSuggestionsTable(db);
            createSearchHistoryTable(db);
        }
        if (oldV < VERSION_UPDATING_SEARCH_TABLES) {
            // Create picker search tables if the database does not already contain the
            // latest version of it.
            createSearchRequestTable(db);
            createSearchResultMediaTable(db);
        }
        if (oldV < VERSION_INTRODUCING_OWNED_PHOTOS) {
            db.execSQL("ALTER TABLE media ADD COLUMN owner_package_name TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE media ADD COLUMN _user_id INTEGER");

            db.execSQL(
                    "ALTER TABLE album_media ADD COLUMN owner_package_name TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE album_media ADD COLUMN _user_id INTEGER");

            mContext.getSharedPreferences(PICKER_SYNC_PREFS_FILE_NAME, Context.MODE_PRIVATE)
                    .edit().remove(getPrefsKey(true, MEDIA_COLLECTION_ID)).apply();
        }
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onDowngrade() for " + mName + " from " + oldV + " to " + newV);

        resetData(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        Log.v(TAG, "onConfigure() for " + mName);

        db.setCustomScalarFunction("_GET_EXTENSION", (arg) -> {
            Trace.beginSection("_GET_EXTENSION");
            try {
                return getExtensionFromMimeType(arg);
            } finally {
                Trace.endSection();
            }
        });
    }

    private void resetData(SQLiteDatabase db) {
        clearPickerPrefs(mContext);

        dropAllTables(db);

        createLatestSchema(db);
        createLatestIndexes(db);
    }

    @VisibleForTesting
    static void dropAllTables(SQLiteDatabase db) {
        // drop all tables
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'table'", null, null,
                null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TABLE IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private static void createLatestSchema(SQLiteDatabase db) {

        db.execSQL("CREATE TABLE media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "local_id TEXT,"
                + "cloud_id TEXT UNIQUE,"
                + "is_visible INTEGER CHECK(is_visible == 1),"
                + "date_taken_ms INTEGER NOT NULL,"
                + "sync_generation INTEGER NOT NULL CHECK(sync_generation >= 0),"
                + "width INTEGER,"
                + "height INTEGER,"
                + "orientation INTEGER,"
                + "size_bytes INTEGER NOT NULL CHECK(size_bytes > 0),"
                + "duration_ms INTEGER CHECK(duration_ms >= 0),"
                + "mime_type TEXT NOT NULL,"
                + "standard_mime_type_extension INTEGER,"
                + "is_favorite INTEGER,"
                + "_user_id INTEGER,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "CHECK(local_id IS NOT NULL OR cloud_id IS NOT NULL),"
                + "UNIQUE(local_id, is_visible))");

        db.execSQL("CREATE TABLE album_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "local_id TEXT,"
                + "cloud_id TEXT,"
                + "album_id TEXT,"
                + "date_taken_ms INTEGER NOT NULL,"
                + "sync_generation INTEGER NOT NULL CHECK(sync_generation >= 0),"
                + "size_bytes INTEGER NOT NULL CHECK(size_bytes > 0),"
                + "duration_ms INTEGER CHECK(duration_ms >= 0),"
                + "mime_type TEXT NOT NULL,"
                + "standard_mime_type_extension INTEGER,"
                + "_user_id INTEGER,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "CHECK((local_id IS NULL AND cloud_id IS NOT NULL) "
                + "OR (local_id IS NOT NULL AND cloud_id IS NULL)),"
                + "UNIQUE(local_id,  album_id),"
                + "UNIQUE(cloud_id, album_id))");
        createMediaGrantsTable(db);
        createDeselectionTable(db);

        createSearchRequestTable(db);
        createSearchResultMediaTable(db);

        createMediaSetsTable(db);
        createMediaInMediaSetsTable(db);

        createSearchSuggestionsTable(db);
        createSearchHistoryTable(db);
    }

    private static void createMediaGrantsTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS media_grants");
        db.execSQL("CREATE TABLE media_grants ("
                + "owner_package_name TEXT,"
                + "file_id INTEGER,"
                + "package_user_id INTEGER,"
                + "UNIQUE(owner_package_name, file_id, package_user_id)"
                + "  ON CONFLICT IGNORE "
                + ")");
    }

    private static void createDeselectionTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS de_selections");
        db.execSQL("CREATE TABLE de_selections ("
                + "owner_package_name TEXT,"
                + "file_id INTEGER,"
                + "package_user_id INTEGER,"
                + "UNIQUE(owner_package_name, file_id, package_user_id)"
                + "  ON CONFLICT IGNORE "
                + ")");
    }

    /**
     * Creates a table to cache for Search Request details and their corresponding
     * Search Request ID.
     * @param db Wrapper that holds SQLite database connections and exposes methods to manage it.
     */
    private static void createSearchRequestTable(@NonNull SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS search_request");

        // Note that SQLite treats all null values as different. So, if you apply a
        // UNIQUE(...) constraint on some columns and if any of those columns holds a null value,
        // the unique constraint will not be applied. This is why in the search request table,
        // a placeholder value will be used instead of null so that the unique constraint gets
        // applied to all search requests saved in the table.
        db.execSQL("CREATE TABLE search_request"
                // Unique identifier of the search request.
                + "(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                // Resume key saved for the local sync. This will be NULL initially when the request
                // has been created but the sync has not started.
                + "local_sync_resume_key TEXT,"
                // Source authority of the local sync resume key.
                + "local_authority TEXT,"
                // Resume key saved for the cloud sync. This will be NULL initially when the request
                // has been created but the sync has not started.
                + "cloud_sync_resume_key TEXT,"
                // Source authority of the cloud sync resume key.
                + "cloud_authority TEXT,"
                // MIME types applied to the session.
                + "mime_types TEXT NOT NULL,"
                // Search text either entered by the user, or equal to the display text of a
                // search suggestion.
                + "search_text TEXT NOT NULL,"
                // Media Set ID of the search suggestion. In case the search request was not made
                // by selecting a search suggestion, this will be an empty string.
                + "media_set_id TEXT NOT NULL,"
                // Type of the search suggestion.
                + "suggestion_type TEXT NOT NULL,"
                // Source authority of the search suggestion.
                + "suggestion_authority TEXT NOT NULL,"
                + "UNIQUE(mime_types, search_text, media_set_id, "
                + "suggestion_type, suggestion_authority))");
    }

    /**
     * Creates a table to cache for Search Result media mapped with their corresponding
     * Search Request IDs.
     * @param db Wrapper that holds SQLite database connections and exposes methods to manage it.
     */
    private static void createSearchResultMediaTable(@NonNull SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS search_result_media");
        db.execSQL("CREATE TABLE search_result_media"
                + "(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "search_request_id INTEGER NOT NULL,"
                + "local_id TEXT,"
                + "cloud_id TEXT,"
                + "CHECK(local_id IS NOT NULL OR cloud_id IS NOT NULL),"
                + "UNIQUE(search_request_id,  local_id),"
                + "UNIQUE(search_request_id,  cloud_id))");
    }

    /**
     * Creates a table to cache the MediaSets and their corresponding metadata under the various
     * categories provided by the CloudMediaProvider.
     * @param db Database wrapper that holds SQLite database connections and exposes methods to
     *           manage it.
     */
    private static void createMediaSetsTable(@NonNull SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS media_sets");
        db.execSQL("CREATE TABLE media_sets("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "category_id TEXT NOT NULL,"
                + "media_set_id TEXT NOT NULL,"
                + "display_name TEXT,"
                + "cover_id TEXT,"
                + "media_set_authority TEXT NOT NULL,"
                + "mime_type_filter TEXT NOT NULL,"
                + "media_in_media_set_sync_resume_key TEXT,"
                + "UNIQUE(category_id, media_set_id, mime_type_filter))");
    }


    /**
     * Creates a table to cache the media items in various MediaSets and their corresponding
     * metadata.
     * @param db Database wrapper that holds SQLite database connections and exposes methods to
     *           manage it.
     */
    private static void createMediaInMediaSetsTable(@NonNull SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS media_in_media_sets");
        db.execSQL("CREATE TABLE media_in_media_sets("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "cloud_id TEXT,"
                + "local_id TEXT,"
                + "media_set_picker_id INTEGER,"
                + "CHECK(local_id IS NOT NULL OR cloud_id IS NOT NULL))");
    }

    /**
     * Creates a table to cache for Search Suggestions for zero-state.
     * @param db Wrapper that holds SQLite database connections and exposes methods to manage it.
     */
    private static void createSearchSuggestionsTable(@NonNull SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS search_suggestion");
        db.execSQL("CREATE TABLE search_suggestion"
                + "(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "authority TEXT NOT NULL,"
                + "search_text TEXT,"
                + "media_set_id TEXT NOT NULL,"
                + "suggestion_type TEXT NOT NULL,"
                + "cover_media_id TEXT,"
                + "creation_time_ms INTEGER NOT NULL,"
                + "UNIQUE(search_text, media_set_id))");
    }

    /**
     * Creates a table to save the Search History.
     * @param db Wrapper that holds SQLite database connections and exposes methods to manage it.
     */
    private static void createSearchHistoryTable(@NonNull SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS search_history");
        db.execSQL("CREATE TABLE search_history"
                + "(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "authority TEXT,"
                + "search_text TEXT,"
                + "media_set_id TEXT,"
                + "cover_media_id TEXT,"
                + "creation_time_ms INTEGER NOT NULL,"
                + "CHECK(search_text IS NOT NULL OR media_set_id IS NOT NULL),"
                + "UNIQUE(search_text, media_set_id))");
    }

    private static void createLatestIndexes(SQLiteDatabase db) {

        db.execSQL("CREATE INDEX local_id_index on media(local_id)");
        db.execSQL("CREATE INDEX cloud_id_index on media(cloud_id)");
        db.execSQL("CREATE INDEX is_visible_index on media(is_visible)");
        db.execSQL("CREATE INDEX size_index on media(size_bytes)");
        db.execSQL("CREATE INDEX mime_type_index on media(mime_type)");
        db.execSQL("CREATE INDEX is_favorite_index on media(is_favorite)");
        db.execSQL("CREATE INDEX date_taken_row_id_index on media(date_taken_ms, _id)");

        db.execSQL("CREATE INDEX local_id_album_index on album_media(local_id)");
        db.execSQL("CREATE INDEX cloud_id_album_index on album_media(cloud_id)");
        db.execSQL("CREATE INDEX size_album_index on album_media(size_bytes)");
        db.execSQL("CREATE INDEX mime_type_album_index on album_media(mime_type)");
        db.execSQL("CREATE INDEX date_taken_album_row_id_index on album_media(date_taken_ms,_id)");
    }

    private static void clearPickerPrefs(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(
                PickerSyncController.PICKER_SYNC_PREFS_FILE_NAME, Context.MODE_PRIVATE);
        final SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.commit();
    }
}
