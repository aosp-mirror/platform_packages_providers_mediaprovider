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

import static android.provider.BaseColumns._ID;
import static android.provider.MediaStore.Files.FileColumns._USER_ID;
import static android.provider.MediaStore.MediaColumns.GENERATION_MODIFIED;
import static android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME;

import android.content.ContentUris;
import android.content.ContentValues;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for revoking owner_grants when user deselects images that were created by the app
 * in picker choice mode
 */
public class FilesOwnershipUtils {

    private static final String FILES_TABLE_NAME = "files";
    private static final String TEMP_TABLE_NAME = "temp_file_ids_table";
    private static final String FILE_ID_COLUMN_NAME = "file_id";
    private static final int CHUNK_SIZE = 50;
    private static final String TAG = FilesOwnershipUtils.class.getSimpleName();

    private final DatabaseHelper mExternalDatabase;

    public FilesOwnershipUtils(DatabaseHelper databaseHelper) {
        mExternalDatabase = databaseHelper;
    }

    /**
     * Revokes the access of the file by setting the owner_package_name as null in the files table.
     * <p>
     * Images or videos can be preselected because the app owns the file and has access to it.
     * If the user deselects such a image/video, we revoke the access of the file by setting the
     * owner_package_name as null in the files table.
     * </p>
     */
    public void removeOwnerPackageNameForUris(@NonNull String[] packages, @NonNull List<Uri> uris,
            int packageUserId) {
        mExternalDatabase.runWithTransaction(db -> {
            db.execSQL("CREATE TEMPORARY TABLE " +  TEMP_TABLE_NAME + " (" + FILE_ID_COLUMN_NAME
                    + " INTEGER)");

            /*
             * Insert all ids in temporary tables in batches.
             * This will be used in update query below for setting owner_package_name to null
             * if this file is currently owned by the app
             */
            List<List<Uri>> uriChunks = splitArrayList(uris, CHUNK_SIZE);
            for (List<Uri> chunk : uriChunks) {
                String sqlQuery = String.format(
                        Locale.ROOT,
                        "INSERT INTO %s (%s) VALUES %s",
                        TEMP_TABLE_NAME,
                        FILE_ID_COLUMN_NAME,
                        getPlaceholderString(chunk.size())
                );

                db.execSQL(sqlQuery, chunk.stream().map(ContentUris::parseId).toArray());
            }

            long generationNumber = DatabaseHelper.getGeneration(db);

            /*
             * sample query for setting owner_package_name as null :
             * UPDATE files SET generation_modified = (SELECT generation from local_metadata),
             * owner_package_name = NULL WHERE (EXISTS (SELECT file_id FROM temp_file_ids_table
             * WHERE files_id = files._id) AND owner_package_name IN (com.example.package1,
             * com.example.package2) AND _user_id = example_user_id)
             */

            String whereClause = "(EXISTS (SELECT " + FILE_ID_COLUMN_NAME + " FROM "
                    + TEMP_TABLE_NAME + " WHERE " + FILE_ID_COLUMN_NAME + " = " + FILES_TABLE_NAME
                    + "." + _ID + ") " + "AND " + OWNER_PACKAGE_NAME + " IN ("
                    + getPlaceholderString(packages.length) + ") " + "AND " + _USER_ID + " = ?)";

            List<String> whereArgs = new ArrayList<>(Arrays.asList(packages));
            whereArgs.add(String.valueOf(packageUserId));

            ContentValues contentValues = new ContentValues();
            contentValues.put(GENERATION_MODIFIED, generationNumber);
            contentValues.putNull(OWNER_PACKAGE_NAME);

            int rowsAffected = db.update(FILES_TABLE_NAME, contentValues, whereClause,
                    whereArgs.toArray(String[]::new));

            Log.i(TAG, "Set owner package name to null for " + rowsAffected + " items for "
                    + "packages " + Arrays.toString(packages));

            db.execSQL("DROP TABLE " + TEMP_TABLE_NAME);

            return null;
        });
    }

    private static String getPlaceholderString(int length) {
        return String.join(", ", Collections.nCopies(length, "(?)"));
    }

    private static <T> List<List<T>> splitArrayList(List<T> list, int chunkSize) {
        List<List<T>> subLists = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            subLists.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return subLists;
    }
}
