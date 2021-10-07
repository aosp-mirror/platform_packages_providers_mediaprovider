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
import androidx.annotation.VisibleForTesting;

/**
 * Wrapper class for the photo picker database. Can open the actual database
 * on demand, create and upgrade the schema, etc.
 *
 * @See DatabaseHelper
 */
public class PickerDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "PickerDatabaseHelper";
    @VisibleForTesting
    static final String PICKER_DATABASE_NAME = "picker.db";

    private static final int VERSION_T = 1;
    private static final int VERSION_LATEST = VERSION_T;

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

        createLatestSchema(db);
        createLatestIndexes(db);
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onUpgrade() for " + mName + " from " + oldV + " to " + newV);
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onDowngrade() for " + mName + " from " + oldV + " to " + newV);

        createLatestSchema(db);
        createLatestIndexes(db);
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
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'index'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private static void createLatestSchema(SQLiteDatabase db) {
        makePristineSchema(db);

        db.execSQL("CREATE TABLE media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "local_id TEXT,"
                + "cloud_id TEXT UNIQUE,"
                + "is_visible INTEGER CHECK(is_visible == 1),"
                + "date_taken_ms INTEGER NOT NULL CHECK(date_taken_ms >= 0),"
                + "size_bytes INTEGER NOT NULL CHECK(size_bytes > 0),"
                + "duration_ms INTEGER CHECK(duration_ms >= 0),"
                + "mime_type TEXT NOT NULL,is_favorite INTEGER,"
                + "CHECK(local_id IS NOT NULL OR cloud_id IS NOT NULL),"
                + "UNIQUE(local_id, is_visible))");
    }

    private static void createLatestIndexes(SQLiteDatabase db) {
        makePristineIndexes(db);

        db.execSQL("CREATE INDEX local_id_index on media(local_id)");
        db.execSQL("CREATE INDEX cloud_id_index on media(cloud_id)");
        db.execSQL("CREATE INDEX is_visible_index on media(is_visible)");
        db.execSQL("CREATE INDEX date_taken_index on media(date_taken_ms)");
        db.execSQL("CREATE INDEX size_index on media(size_bytes)");
        db.execSQL("CREATE INDEX mime_type_index on media(mime_type)");
        db.execSQL("CREATE INDEX is_favorite_index on media(is_favorite)");
    }
}
