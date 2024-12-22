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

import static com.android.providers.media.util.MimeUtils.getExtensionFromMimeType;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Trace;
import android.util.Log;

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
    public static final int VERSION_LATEST = VERSION_INTRODUCING_DE_SELECTIONS_TABLE;

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
                + "CHECK((local_id IS NULL AND cloud_id IS NOT NULL) "
                + "OR (local_id IS NOT NULL AND cloud_id IS NULL)),"
                + "UNIQUE(local_id,  album_id),"
                + "UNIQUE(cloud_id, album_id))");
        createMediaGrantsTable(db);
        createDeselectionTable(db);
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
