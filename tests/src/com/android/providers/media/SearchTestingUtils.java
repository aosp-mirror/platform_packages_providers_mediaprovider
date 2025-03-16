/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import com.android.providers.media.search.SearchUtilConstants;

/**
 * Holds utility functions for testing picker search workers
 */
public class SearchTestingUtils {

    private final DatabaseHelper mDatabaseHelper;
    private final SearchUtilConstants mSearchUtilConstants;

    public SearchTestingUtils(DatabaseHelper databaseHelper) {
        mDatabaseHelper = databaseHelper;
        mSearchUtilConstants = new SearchUtilConstants();
    }

    /**
     * Returns the processing statuses from the status table for the test file id
     */
    public Cursor getMediaItemDataFromStatusTable(Long testFileId) {
        return mDatabaseHelper.runWithoutTransaction(database -> {
            return database.query(
                    /* tableName */ mSearchUtilConstants.MEDIA_STATUS_TABLE,
                    /* columns */ new String[] {
                            mSearchUtilConstants.METADATA_PROCESSING_STATUS_COLUMN,
                            mSearchUtilConstants.LABEL_PROCESSING_STATUS_COLUMN,
                            mSearchUtilConstants.LOCATION_PROCESSING_STATUS_COLUMN,
                            mSearchUtilConstants.OCR_PROCESSING_STATUS_COLUMN,
                            mSearchUtilConstants.DISPLAY_NAME_COLUMN
                    },
                    /* selection */ mSearchUtilConstants.MEDIA_ID_COLUMN + " = ?",
                    /* selectionArgs */ new String[] { String.valueOf(testFileId) },
                    /* groupBy */ null, /* having */ null, /*orderBy*/ null, /* limit */null);
        });

    }

    /**
     * Marks the testFileId as trashed in the files table
     */
    public void trashMediaItem(String testFileId) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Files.FileColumns.IS_TRASHED, 1);
        String updateColumn = MediaStore.Files.FileColumns._ID + " = ?";
        String[] updateArguments = new String[] { testFileId };
        mDatabaseHelper.runWithTransaction(database -> {
            return database.update(
                    MediaStore.Files.TABLE, contentValues, updateColumn, updateArguments);
        });
    }

    /**
     * Inserts the given values into the status table
     */
    public void insertMediaItemIntoMediaStatusTable(
            DatabaseHelper databaseHelper, ContentValues insertValues) {
        databaseHelper.runWithTransaction(database -> {
            return database.insert(
                    mSearchUtilConstants.MEDIA_STATUS_TABLE,
                    /* nullColumnHack */ null,
                    insertValues);
        });
    }

    /**
     * Updates the given media item data in the specified table with the given values
     */
    public void updateMediaItem(String tableName, String mediaId, ContentValues updateValues) {
        String updateColumn = getUpdateColumn(tableName);
        String[] updateArguments = new String[] { mediaId };
        mDatabaseHelper.runWithTransaction(database -> {
            return database.update(tableName, updateValues, updateColumn, updateArguments);
        });
    }

    private String getUpdateColumn(String tableName) {
        if (tableName.equals(mSearchUtilConstants.MEDIA_STATUS_TABLE)) {
            return mSearchUtilConstants.MEDIA_ID_COLUMN + " = ?";
        } else if (tableName.equals(MediaStore.Files.TABLE)) {
            return MediaStore.Files.FileColumns._ID + " = ?";
        }
        throw new IllegalArgumentException("Invalid table name");
    }

    /**
     * Deletes the created test file after the tests have executed
     */
    public void deleteTestFile(ContentResolver isolatedResolver, long testFileId) {
        Uri testFileUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, testFileId);
        isolatedResolver.delete(testFileUri, Bundle.EMPTY);
    }
}
