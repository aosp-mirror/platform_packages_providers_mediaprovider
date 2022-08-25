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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import static com.android.providers.media.DatabaseHelper.VERSION_LATEST;

import androidx.annotation.VisibleForTesting;

public class UnreliableVolumeDatabaseHelper extends SQLiteOpenHelper implements AutoCloseable {
    final Context mContext;
    final String mName;
    final int mVersion;

    private static final String UNRELIABLE_VOLUME_DATABASE_NAME = "pickerUnreliableVolume.db";
    private static final String TAG = "PickerUnreliableVolumeHelper";

    public static final class MediaColumns {
        private MediaColumns() {}
        public static final String _ID = "_id";
        public static final String DISPLAY_NAME = "display_name";
        public static final String _DATA = "_data";
        public static final String DATE_MODIFIED = "date_modified";
        public static final String SIZE_BYTES = "size_bytes";
        public static final String MIME_TYPE = "mime_type";
    }

    public  UnreliableVolumeDatabaseHelper(Context context) {
        this(context, UNRELIABLE_VOLUME_DATABASE_NAME, VERSION_LATEST);
    }

    public UnreliableVolumeDatabaseHelper(Context context, String name, int version) {
        super(context, name, null, version);
        mContext = context;
        mName = name;
        mVersion = version;

        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.v(TAG, "onCreate() for " + mName);

        createSchema(db);
        createIndexes(db);
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onUpgrade() for " + mName + " from " + oldV + " to " + newV);
    }

    @VisibleForTesting
    static void makePristineSchema(SQLiteDatabase db) {
        // drop all tables
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'table'", null, null,
                null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TABLE IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    @VisibleForTesting
    static void makePristineIndexes(SQLiteDatabase db) {
        // drop all indexes
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'index'", null,
                null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createSchema(SQLiteDatabase db) {
        makePristineSchema(db);

        db.execSQL("CREATE TABLE media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "date_modified INTEGER NOT NULL CHECK(date_modified >= 0),"
                + "size_bytes INTEGER NOT NULL CHECK(size_bytes > 0),"
                + "display_name TEXT NOT NULL,"
                + "_data TEXT NOT NULL UNIQUE COLLATE NOCASE,"
                + "mime_type TEXT NOT NULL)");
    }

    private void createIndexes(SQLiteDatabase db) {
        makePristineIndexes(db);

        db.execSQL("CREATE INDEX path_index on media(_data)");
        db.execSQL("CREATE INDEX display_name_index on media(display_name)");
        db.execSQL("CREATE INDEX date_modified_index on media(date_modified)");
        db.execSQL("CREATE INDEX size_index on media(size_bytes)");
        db.execSQL("CREATE INDEX mime_type_index on media(mime_type)");
    }
}
