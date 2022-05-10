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

package com.android.providers.media.photopicker;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import android.net.Uri;
import android.util.Log;

import com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper;
import com.android.providers.media.util.SQLiteQueryBuilder;

import java.util.List;

public class UnreliableVolumeFacade {
    private static final String TAG = "UnreliableVolumeFacade";
    private static final String TABLE_NAME = "media";

    private static final int FAIL = -1;
    private static final int SUCCESS = 1;

    private SQLiteDatabase mDatabase;
    private SQLiteQueryBuilder mQueryBuilder;

    private static final String[] mColumns = new String[]{
            UnreliableVolumeDatabaseHelper.MediaColumns._ID,
            UnreliableVolumeDatabaseHelper.MediaColumns._DATA,
            UnreliableVolumeDatabaseHelper.MediaColumns.DATE_MODIFIED,
            UnreliableVolumeDatabaseHelper.MediaColumns.DISPLAY_NAME,
            UnreliableVolumeDatabaseHelper.MediaColumns.SIZE_BYTES,
            UnreliableVolumeDatabaseHelper.MediaColumns.MIME_TYPE
    };

    public UnreliableVolumeFacade(Context context) {
        UnreliableVolumeDatabaseHelper dbHelper = new UnreliableVolumeDatabaseHelper(context);
        mDatabase = dbHelper.getWritableDatabase();
        mQueryBuilder = createQueryBuilder();
    }

    /**
     * @return the media item from the media table with given {@code uri}
     */
    public Cursor queryMediaId(Uri uri) {
        String id = String.valueOf(ContentUris.parseId(uri));
        final String selection = UnreliableVolumeDatabaseHelper.MediaColumns._ID + " = " + id;
        return mDatabase.query(TABLE_NAME, mColumns, selection, /* selectionArgs */ null,
                /* groupBy */ null, /* having */ null, /* orderBy */ null);
    }

    /**
     * @return {@link Cursor} with all the rows in media table
     */
    public Cursor queryMediaAll() {
        return mDatabase.query(TABLE_NAME, mColumns, /* selection */ null, /* selectionArgs */ null,
                /* groupBy */ null, /* having */ null, /* orderBy */ null);
    }

    private SQLiteQueryBuilder createQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_NAME);

        return qb;
    }

    private int insertFile(ContentValues value) {
        try {
            if (mQueryBuilder.insert(mDatabase, value) > 0) {
                return SUCCESS;
            }
        } catch (SQLiteConstraintException e) {
            Log.e(TAG, "Failed to insert picker db media. ContentValues: " + value, e);
        }
        return FAIL;
    }

    public int insertMedia(List<ContentValues> values) {
        int numberItemsInserted = 0;
        mDatabase.beginTransaction();
        try {
            for (ContentValues value : values) {
                if (insertFile(value) == SUCCESS) {
                    numberItemsInserted++;
                }
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        return numberItemsInserted;
    }

    public void deleteMedia() {
        mDatabase.delete(TABLE_NAME, /* whereClause */null, /* whereArgs */null);
    }
}