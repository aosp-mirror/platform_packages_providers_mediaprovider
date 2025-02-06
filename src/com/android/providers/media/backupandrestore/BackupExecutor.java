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

package com.android.providers.media.backupandrestore;

import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.BACKUP_COLUMNS;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.BACKUP_DIRECTORY_NAME;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.FIELD_SEPARATOR;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.KEY_VALUE_SEPARATOR;
import static com.android.providers.media.flags.Flags.enableBackupAndRestore;
import static com.android.providers.media.util.Logging.TAG;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.leveldb.LevelDBEntry;
import com.android.providers.media.leveldb.LevelDBInstance;
import com.android.providers.media.leveldb.LevelDBManager;
import com.android.providers.media.leveldb.LevelDBResult;

import com.google.common.collect.BiMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Class containing implementation details for backing up files table data to leveldb.
 */
public final class BackupExecutor {

    private static final String EXTERNAL_PRIMARY_VOLUME_CLAUSE =
            FileColumns.VOLUME_NAME + " = '" + VOLUME_EXTERNAL_PRIMARY + "'";

    private static final String SCANNER_AS_MODIFIER_CLAUSE = FileColumns._MODIFIER + " = 3";

    private static final String FILE_NOT_PENDING_CLAUSE = FileColumns.IS_PENDING + " = 0";

    private static final String MIME_TYPE_CLAUSE = FileColumns.MIME_TYPE + " IS NOT NULL";

    private static final String AND_CONNECTOR = " AND ";

    /**
     * Key corresponding to which last backed up generation number is stored.
     */
    private static final String LAST_BACKED_GENERATION_NUMBER_KEY = "LAST_BACKED_GENERATION_NUMBER";

    /**
     * Name of files table in MediaProvider database.
     */
    private static final String FILES_TABLE_NAME = "files";

    private final Context mContext;

    private final DatabaseHelper mExternalDatabaseHelper;

    private LevelDBInstance mLevelDBInstance;

    public BackupExecutor(Context context, DatabaseHelper databaseHelper) {
        mContext = context;
        mExternalDatabaseHelper = databaseHelper;
    }

    /**
     * Addresses the following:-
     * 1. Gets last backed generation number from leveldb
     * 2. Backs up data for rows greater than last backed generation number
     * 3. Updates the new backed up generation number
     */
    public void doBackup(CancellationSignal signal) {
        if (!isBackupAndRestoreSupported(mContext)) {
            return;
        }
        Log.v(TAG, "Backup is enabled");

        if (mLevelDBInstance == null) {
            mLevelDBInstance = LevelDBManager.getInstance(getBackupFilePath());
        }
        final long lastBackedUpGenerationNumberFromLevelDb = getLastBackedUpGenerationNumber();
        final long currentDbGenerationNumber = mExternalDatabaseHelper.runWithoutTransaction(
                DatabaseHelper::getGeneration);
        final long lastBackedUpGenerationNumber = clearBackupIfNeededAndReturnLastBackedUpNumber(
                currentDbGenerationNumber, lastBackedUpGenerationNumberFromLevelDb);
        Log.v(TAG, "Last backed up generation number: " + lastBackedUpGenerationNumber);
        long lastGenerationNumber = backupData(lastBackedUpGenerationNumber, signal);
        updateLastBackedUpGenerationNumber(lastGenerationNumber);
    }

    /**
     * Checks whether backup and restore operations are supported and enabled on the current device.
     *
     * <p>This method verifies that the required backup and restore flag is enabled, SDK version is
     * S+ and ensures the device hardware is suitable for these operations. Backup and restore are
     * supported only on mobile phones and tablets, excluding devices like automotive systems, TVs,
     * PCs, and smartwatches.</p>
     *
     * @param context the application {@link Context}, used to access system resources.
     * @return {@code true} if backup and restore is enabled and supported on the device,
     *         {@code false} otherwise.
     */
    public static boolean isBackupAndRestoreSupported(Context context) {
        if (!enableBackupAndRestore() || !SdkLevel.isAtLeastS()) {
            return false;
        }

        if (context == null || context.getPackageManager() == null) {
            return false;
        }

        final PackageManager pm = context.getPackageManager();
        return !pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                && !pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                && !pm.hasSystemFeature(PackageManager.FEATURE_PC)
                && !pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    private long clearBackupIfNeededAndReturnLastBackedUpNumber(long currentDbGenerationNumber,
            long lastBackedUpGenerationNumber) {
        if (currentDbGenerationNumber < lastBackedUpGenerationNumber) {
            // If DB generation number is lesser than last backed, we would have to re-sync
            // everything
            mLevelDBInstance = LevelDBManager.recreate(getBackupFilePath());
            return 0;
        }

        return lastBackedUpGenerationNumber;
    }

    @SuppressLint("Range")
    private long backupData(long lastBackedUpGenerationNumber, CancellationSignal signal) {
        List<String> queryColumns = new ArrayList<>(Arrays.asList(BACKUP_COLUMNS));
        queryColumns.addAll(Arrays.asList(FileColumns.DATA, FileColumns.GENERATION_MODIFIED));
        final String selectionClause = prepareSelectionClause(lastBackedUpGenerationNumber);
        return mExternalDatabaseHelper.runWithTransaction((db) -> {
            long maxGeneration = lastBackedUpGenerationNumber;
            try (Cursor c = db.query(true, FILES_TABLE_NAME,
                    queryColumns.stream().toArray(String[]::new),
                    selectionClause, null, null, null, MediaColumns.GENERATION_MODIFIED + " ASC",
                    null, signal)) {
                while (c.moveToNext()) {
                    if (signal != null && signal.isCanceled()) {
                        Log.i(TAG, "Received a cancellation signal during the backup process");
                        break;
                    }

                    backupDataValues(c);
                    maxGeneration = Math.max(maxGeneration,
                            c.getLong(c.getColumnIndex(FileColumns.GENERATION_MODIFIED)));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failure in backing up for B&R ", e);
            }
            return maxGeneration;
        });
    }

    @SuppressLint("Range")
    private void backupDataValues(Cursor c) {
        String data = c.getString(c.getColumnIndex(FileColumns.DATA));
        // Skip backing up directories
        if (new File(data).isDirectory()) {
            return;
        }

        mLevelDBInstance.insert(new LevelDBEntry(data, serialiseValueString(c)));
    }

    private static String serialiseValueString(Cursor c) {
        StringBuilder sb = new StringBuilder();
        BiMap<String, String> columnToIdBiMap =  BackupAndRestoreUtils.sIdToColumnBiMap.inverse();
        for (String backupColumn : BACKUP_COLUMNS) {
            Optional<String> optionalValue = extractValue(c, backupColumn);
            if (!optionalValue.isPresent()) {
                continue;
            }

            sb.append(columnToIdBiMap.get(backupColumn)).append(KEY_VALUE_SEPARATOR).append(
                    optionalValue.get());
            sb.append(FIELD_SEPARATOR);
        }
        return sb.toString();
    }

    @SuppressLint("Range")
    static Optional<String> extractValue(Cursor c, String col) {
        int columnIndex = c.getColumnIndex(col);
        int fieldType = c.getType(columnIndex);
        switch (fieldType) {
            case Cursor.FIELD_TYPE_STRING -> {
                String stringValue = c.getString(columnIndex);
                if (stringValue == null || stringValue.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(stringValue);
            }
            case Cursor.FIELD_TYPE_INTEGER -> {
                long longValue = c.getLong(columnIndex);
                return Optional.of(String.valueOf(longValue));
            }
            case Cursor.FIELD_TYPE_FLOAT -> {
                float floatValue = c.getFloat(columnIndex);
                return Optional.of(String.valueOf(floatValue));
            }
            case Cursor.FIELD_TYPE_BLOB -> {
                byte[] bytes = c.getBlob(columnIndex);
                if (bytes == null || bytes.length == 0) {
                    return Optional.empty();
                }
                return Optional.of(new String(bytes));
            }
            case Cursor.FIELD_TYPE_NULL -> {
                return Optional.empty();
            }
            default -> {
                Log.e(TAG, "Column type not supported for backup: " + col);
                return Optional.empty();
            }
        }
    }

    private String prepareSelectionClause(long lastBackedUpGenerationNumber) {
        // Last scan might have not finished for last gen number if cancellation signal is triggered
        final String generationClause = FileColumns.GENERATION_MODIFIED + " >= "
                + lastBackedUpGenerationNumber;
        // Only scanned files are expected to have corresponding metadata in DB, hence this check.
        return generationClause
                + AND_CONNECTOR
                + EXTERNAL_PRIMARY_VOLUME_CLAUSE
                + AND_CONNECTOR
                + FILE_NOT_PENDING_CLAUSE
                + AND_CONNECTOR
                + MIME_TYPE_CLAUSE
                + AND_CONNECTOR
                + SCANNER_AS_MODIFIER_CLAUSE;
    }

    private long getLastBackedUpGenerationNumber() {
        LevelDBResult levelDBResult = mLevelDBInstance.query(LAST_BACKED_GENERATION_NUMBER_KEY);
        if (!levelDBResult.isSuccess() && !levelDBResult.isNotFound()) {
            throw new IllegalStateException("Error in fetching last backed up generation number : "
                    + levelDBResult.getErrorMessage());
        }

        String value = levelDBResult.getValue();
        if (levelDBResult.isNotFound() || value == null || value.isEmpty()) {
            return 0L;
        }

        return Long.parseLong(value);
    }

    private void updateLastBackedUpGenerationNumber(long lastGenerationNumber) {
        LevelDBResult levelDBResult = mLevelDBInstance.insert(
                new LevelDBEntry(LAST_BACKED_GENERATION_NUMBER_KEY,
                        String.valueOf(lastGenerationNumber)));
        if (!levelDBResult.isSuccess()) {
            throw new IllegalStateException("Error in inserting last backed up generation number : "
                    + levelDBResult.getErrorMessage());
        }
    }

    /**
     * Returns backup file path based on the volume name.
     */
    private String getBackupFilePath() {
        String backupDirectory =
                mContext.getFilesDir().getAbsolutePath() + "/" + BACKUP_DIRECTORY_NAME + "/";
        File backupDir = new File(backupDirectory + VOLUME_EXTERNAL_PRIMARY + "/");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        return backupDir.getAbsolutePath();
    }

    /**
     * Removes entry for given file path from Backup.
     */
    public void deleteBackupForPath(String path) {
        if (isBackupAndRestoreSupported(mContext) && path != null && mLevelDBInstance != null) {
            mLevelDBInstance.delete(path);
        }
    }
}
