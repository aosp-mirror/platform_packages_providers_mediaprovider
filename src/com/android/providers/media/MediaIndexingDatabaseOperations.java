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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.search.SearchUtilConstants;
import com.android.providers.media.search.exceptions.SqliteCheckedException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides all the database functionality for indexing media metadata
 */
public class MediaIndexingDatabaseOperations {

    private final SearchUtilConstants mSearchUtilConstants;

    public MediaIndexingDatabaseOperations() {
        mSearchUtilConstants = new SearchUtilConstants();
    }

    /**
     * Queries the specified table in the external database for the given selection and
     * selection arguments.
     */
    @VisibleForTesting
    Cursor queryExternalDatabaseTable(
            @NonNull DatabaseHelper databaseHelper,
            String tableName,
            String[] columns,
            String selection,
            String[] selectionArgs,
            String limit,
            String orderBy
    ) throws SqliteCheckedException {
        Objects.requireNonNull(databaseHelper, "DatabaseHelper object found to be null."
                + "Cannot query for items from external database");
        try {
            return databaseHelper.runWithoutTransaction(database -> {
                return database.query(tableName, columns, selection,
                        selectionArgs, /* groupBy */ null, /* having */ null, /* orderBy */ orderBy,
                        /* limit */ limit
                );
            });
        } catch (SQLiteException exception) {
            throw new SqliteCheckedException("Couldn't query " + tableName, exception);
        }
    }

    /**
     * Updates the status table columns with the given parameter values. The values to be updated
     * can be the processing statuses or they can be the metadata about the media item itself that
     * we hold in the status table.
     */
    public void updateStatusTableValues(
            @NonNull DatabaseHelper databaseHelper, String[] mediaItemIds, ContentValues values)
            throws SqliteCheckedException {
        Objects.requireNonNull(databaseHelper, "DatabaseHelper object found to be null."
                + "Cannot update status table values.");
        try {
            databaseHelper.runWithTransaction(database-> {
                StringBuilder whereClauseBuilder = new StringBuilder(
                        mSearchUtilConstants.MEDIA_ID_COLUMN + " in (");
                for (int i = 0; i < mediaItemIds.length; i++) {
                    whereClauseBuilder.append("?");
                    if (i < mediaItemIds.length - 1) {
                        whereClauseBuilder.append(", ");
                    }
                }
                whereClauseBuilder.append(")");
                return database.update(
                        mSearchUtilConstants.MEDIA_STATUS_TABLE, values,
                        whereClauseBuilder.toString(), mediaItemIds);
            });
        } catch (SQLiteException exception) {
            throw new SqliteCheckedException(
                    "Couldn't update status table values ", exception);
        }
    }

    /**
     * Inserts the provided values into the status table. The inserted values are the metadata
     * values of the media items such as the name, mime_type and item size to name a few.
     */
    public void insertIntoMediaStatusTable(
            @NonNull DatabaseHelper databaseHelper, List<ContentValues> insertValuesList)
            throws SqliteCheckedException {
        Objects.requireNonNull(databaseHelper, "DatabaseHelper object found to be null."
                + "Cannot insert into media status table.");
        Objects.requireNonNull(insertValuesList, "insertValuesList found to be null."
                + "Cannot insert into media status table.");
        try {
            databaseHelper.runWithTransaction(database -> {
                insertValuesList.forEach(insertValues ->
                        database.insert(
                                mSearchUtilConstants.MEDIA_STATUS_TABLE,
                                /* nullColumnHack */ null,
                                insertValues));
                return true;
            });
        } catch (SQLiteException exception) {
            throw new SqliteCheckedException(
                    "Couldn't insert into the status table ", exception);
        }
    }

    /**
     * Deletes the row corresponding to the given media item id from the status table
     */
    public void deleteMediaItemFromMediaStatusTable(
            @NonNull DatabaseHelper databaseHelper, String mediaItemId)
            throws SqliteCheckedException {
        Objects.requireNonNull(databaseHelper, "DatabaseHelper object found to be null."
                + "Cannot delete media item from status table.");
        try {
            databaseHelper.runWithTransaction(database -> {
                String deleteWhereClause = mSearchUtilConstants.MEDIA_ID_COLUMN + " = ?";
                String[] deleteArguments = new String[] { mediaItemId };
                return database.delete(
                        mSearchUtilConstants.MEDIA_STATUS_TABLE,
                        deleteWhereClause, deleteArguments);
            });
        } catch (SQLiteException exception) {
            throw new SqliteCheckedException("Couldn't delete media item with id "
                    + mediaItemId + " from the status table", exception);
        }
    }

    /**
     * Returns a DatabaseHelper object reference held by the MediaProvider. This will get called
     * only once per search worker instance. Hence, there is no need to keep a reference of the
     * same.
     */
    public DatabaseHelper getDatabaseHelper(Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        try (ContentProviderClient client =
                     contentResolver.acquireContentProviderClient(MediaStore.AUTHORITY)) {
            MediaProvider mediaProvider = (MediaProvider) client.getLocalContentProvider();
            Optional<DatabaseHelper> databaseHelper =
                    mediaProvider.getDatabaseHelper(DatabaseHelper.EXTERNAL_DATABASE_NAME);
            if (databaseHelper.isPresent()) {
                return databaseHelper.get();
            } else {
                throw new RuntimeException("Unable to retrieve MediaProvider database helper");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to acquire reference to MediaProvider due to "
                    + exception);
        }
    }
}
