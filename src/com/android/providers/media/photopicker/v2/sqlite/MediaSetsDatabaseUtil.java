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

package com.android.providers.media.photopicker.v2.sqlite;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.provider.CloudMediaProviderContract;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility class which holds functionality for inserting and querying media set data
 */
public class MediaSetsDatabaseUtil {

    private static final String TAG = "MediaSetsDatabaseUtil";
    public static final String DELIMITER = ";";

    /**
     * Inserts metadata about a media set into the media sets table. Insertions that violate the
     * schema constraints are ignored and not inserted.
     * @param database The database to insert into
     * @param mediaSetMetadataCursor Cursor received from CMP which holds the metadata of the media
     *                               sets
     * @param categoryId Id of the category to which the media set belongs
     * @param authority Authority of the media set
     * @param mimeTypes Mime type filters for the provided query
     * @return the number rows inserted
     */
    public static int cacheMediaSets(
            @NonNull SQLiteDatabase database, @NonNull Cursor mediaSetMetadataCursor,
            @NonNull String categoryId, @NonNull String authority,
            @Nullable List<String> mimeTypes) {

        Objects.requireNonNull(database);
        Objects.requireNonNull(mediaSetMetadataCursor);
        Objects.requireNonNull(categoryId);
        Objects.requireNonNull(authority);

        String mimeTypesAsString = getMimeTypesAsString(mimeTypes);
        List<ContentValues> insertValues = getMediaSetContentValues(
                mediaSetMetadataCursor, categoryId, authority, mimeTypesAsString);

        if (insertValues.isEmpty()) {
            Log.e(TAG, "Cursor received from CMP is empty, nothing to cache.");
            return 0;
        }

        try {

            database.beginTransaction();
            int numberOfMediaSetsInserted = 0;

            for (ContentValues contentValue: insertValues) {
                try {
                    final long insertResult = database.insertWithOnConflict(
                            /* tableName */ PickerSQLConstants.Table.MEDIA_SETS.name(),
                            /* nullColumnHack */ null,
                            contentValue,
                            CONFLICT_IGNORE
                    );

                    if (insertResult == -1) {
                        Log.d(TAG, "Could not save MediaSet data due to a conflict constraint");
                    } else {
                        numberOfMediaSetsInserted++;
                    }

                } catch (SQLException e) {
                    Log.e(TAG, "Could not insert media set row into the media sets table "
                            + contentValue, e);
                }
            }
            // Mark transaction as successful so that it gets committed after it ends.
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }

            return numberOfMediaSetsInserted;
        } catch (RuntimeException e) {
            throw new RuntimeException("Couldn't insert values into the database ",  e);
        } finally {
            // Mark transaction as ended. The inserted items will either be committed if the
            // transaction has been set as successful, or roll-backed otherwise.
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }

    /**
     * Returns mediaSetId corresponding to the given mediaSetPickerId
     * @param database Database to read from
     * @param mediaSetPickerId Unique pickerDB id
     * @return mediaSetId and mimeTypes for the given mediaSetPickerId wrapped in a Pair object
     */
    public static Pair<String, String[]> getMediaSetIdAndMimeType(
            @NonNull SQLiteDatabase database,
            @NonNull String mediaSetPickerId) {
        Objects.requireNonNull(database);
        Objects.requireNonNull(mediaSetPickerId);

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.MEDIA_SETS.name())
                .setProjection(List.of(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID.getColumnName(),
                        PickerSQLConstants.MediaSetsTableColumns.MIME_TYPE_FILTER.getColumnName()
                ));
        queryBuilder.appendWhereStandalone(String.format(
                Locale.ROOT,
                "%s = '%s'",
                PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName(),
                mediaSetPickerId
        ));
        try (Cursor cursor = database.rawQuery(queryBuilder.buildQuery(), /*selectionArgs*/null)) {
            String mediaSetId = "";
            String mimeTypes = "";
            if (cursor.moveToFirst()) {
                mediaSetId = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID.getColumnName()));
                mimeTypes = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.MIME_TYPE_FILTER.getColumnName()));
                return new Pair<>(mediaSetId, mimeTypes.split(MediaSetsDatabaseUtil.DELIMITER));
            } else {
                throw new IllegalArgumentException(
                        "No entry found in the database corresponding to "
                                + " the given mediaSetPickerId " + mediaSetPickerId
                                + ". Cannot fetch mediaSetId and mimeTypes.");
            }
        }
    }


    /**
     * Fetches the metadata of all the media sets under the given category
     * @param database The database to query on
     * @param categoryId The id of the category for which the media sets are to be queried
     * @return Cursor containing metadata of all the media sets under the given category
     */
    public static Cursor getMediaSetsForCategory(
            @NonNull SQLiteDatabase database, @NonNull String categoryId,
            @NonNull String authority, @Nullable List<String> mimeTypes) {
        Objects.requireNonNull(database);
        Objects.requireNonNull(categoryId);
        Objects.requireNonNull(authority);

        final List<String> projection = List.of(
                PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName(),
                PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_AUTHORITY.getColumnName(),
                PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID.getColumnName(),
                PickerSQLConstants.MediaSetsTableColumns.DISPLAY_NAME.getColumnName(),
                PickerSQLConstants.MediaSetsTableColumns.COVER_ID.getColumnName()
        );
        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.MEDIA_SETS.name())
                .setProjection(projection)
                .setSortOrder(String.format(
                        Locale.ROOT,
                        "%s ASC",
                        PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()
                ));
        queryBuilder
                .appendWhereStandalone(
                        String.format(Locale.ROOT, " %s = '%s' ",
                                PickerSQLConstants.MediaSetsTableColumns.CATEGORY_ID
                                        .getColumnName(), categoryId))
                .appendWhereStandalone(
                        String.format(Locale.ROOT, " %s = '%s' ",
                                PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_AUTHORITY
                                        .getColumnName(), authority)
                )
                .appendWhereStandalone(
                        String.format(Locale.ROOT, " %s = '%s' ",
                                PickerSQLConstants.MediaSetsTableColumns.MIME_TYPE_FILTER
                                        .getColumnName(), getMimeTypesAsString(mimeTypes)));

        return database.rawQuery(queryBuilder.buildQuery(), /*selectionArgs*/ null);
    }

    /**
     * Fetches the media resume key for the media under the media set identified by the
     * mediaPickerId
     * @param database The database to query on
     * @param mediaPickerId The pickerId of the media set in the media set table for which the
     *                      key needs to be updated
     * @return The cursor which contains the media resume key for the media in that media set
     */
    public static String getMediaResumeKey(
            @NonNull SQLiteDatabase database, @NonNull String mediaPickerId) {
        Objects.requireNonNull(database);
        Objects.requireNonNull(mediaPickerId);

        final List<String> projection = List.of(
                PickerSQLConstants.MediaSetsTableColumns.MEDIA_IN_MEDIA_SET_SYNC_RESUME_KEY
                        .getColumnName());
        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.MEDIA_SETS.name())
                .setProjection(projection);
        queryBuilder.appendWhereStandalone(
                String.format(Locale.ROOT, " %s = '%s' ",
                        PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName(),
                        mediaPickerId)
        );

        Cursor cursor = database.rawQuery(queryBuilder.buildQuery(), /*selectionArgs*/ null);

        if (cursor.moveToFirst()) {
            return cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.MediaSetsTableColumns.MEDIA_IN_MEDIA_SET_SYNC_RESUME_KEY
                            .getColumnName()));
        } else {
            // Cursor is empty
            // No match for the mediaSetPickerId found, throwing an error
            throw new RuntimeException("mediaSetPickerId " + mediaPickerId + " not found in "
                    + "the media_sets table");
        }
    }

    /**
     * Updates the resume key used to help with syncing of media items under a given media set
     * @param database The database to query on
     * @param mediaSetPickerId The pickerId of the media set in the media set table for which the
     *                         key needs to be updated
     * @param resumeKey The new value of the resume key
     */
    public static void updateMediaInMediaSetSyncResumeKey(@NonNull SQLiteDatabase database,
            @NonNull String mediaSetPickerId, @Nullable String resumeKey) {
        Objects.requireNonNull(database);
        Objects.requireNonNull(mediaSetPickerId);

        String table = PickerSQLConstants.Table.MEDIA_SETS.name();

        ContentValues updateValues = new ContentValues();
        updateValues.put(
                PickerSQLConstants.MediaSetsTableColumns.MEDIA_IN_MEDIA_SET_SYNC_RESUME_KEY
                .getColumnName(),
                resumeKey);

        database.update(
                table,
                updateValues,
                String.format(
                        Locale.ROOT,
                        "%s = '%s'",
                        PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName(),
                        mediaSetPickerId
                ),
                null
        );
    }

    private static List<ContentValues> getMediaSetContentValues(
            Cursor mediaSetCursor, String categoryId, String authority, String mimeTypes) {

        List<ContentValues> contentValuesList = new ArrayList<>();

        // Extract all properties of the media sets returned by the CMP from the cursor
        if (mediaSetCursor.moveToFirst()) {
            do {
                int mediaSetIdIndex = mediaSetCursor.getColumnIndex(
                        CloudMediaProviderContract.MediaSetColumns.ID);
                String mediaSetId = mediaSetCursor.getString(mediaSetIdIndex);
                if (mediaSetId == null || mediaSetId.isEmpty()) {
                    Log.e(TAG, "Retrieved mediaSetId was empty. Skipping this set.");
                    continue;
                }
                int mediaSetDisplayNameIndex = mediaSetCursor.getColumnIndex(
                        CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME);
                String mediaSetDisplayName = mediaSetCursor.getString(mediaSetDisplayNameIndex);
                int mediaSetCoverIdIndex = mediaSetCursor.getColumnIndex(CloudMediaProviderContract
                        .MediaSetColumns.MEDIA_COVER_ID);
                String mediaCoverId = mediaSetCursor.getString(mediaSetCoverIdIndex);

                ContentValues insertValues = new ContentValues();
                insertValues.put(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID.getColumnName(),
                        mediaSetId
                );
                insertValues.put(
                        PickerSQLConstants.MediaSetsTableColumns.DISPLAY_NAME.getColumnName(),
                        mediaSetDisplayName
                );
                insertValues.put(
                        PickerSQLConstants.MediaSetsTableColumns.COVER_ID.getColumnName(),
                        mediaCoverId
                );
                insertValues.put(
                        PickerSQLConstants.MediaSetsTableColumns.CATEGORY_ID.getColumnName(),
                        categoryId
                );
                insertValues.put(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_AUTHORITY
                                .getColumnName(), authority
                );
                insertValues.put(
                        PickerSQLConstants.MediaSetsTableColumns.MIME_TYPE_FILTER.getColumnName(),
                        mimeTypes
                );
                contentValuesList.add(insertValues);
            } while (mediaSetCursor.moveToNext());
        }
        return contentValuesList;
    }


    private static String getMimeTypesAsString(@Nullable List<String> mimeTypes) {
        if (mimeTypes == null || mimeTypes.isEmpty()) {
            return "";
        }
        List<String> modifiableList = new ArrayList<>(mimeTypes);
        modifiableList.replaceAll(s -> s.toLowerCase(Locale.ROOT));
        modifiableList.sort(Comparator.naturalOrder());
        return String.join(DELIMITER, modifiableList);
    }
}
