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

import static com.android.providers.media.util.Logging.TAG;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.leveldb.LevelDBEntry;
import com.android.providers.media.leveldb.LevelDBInstance;
import com.android.providers.media.leveldb.LevelDBManager;
import com.android.providers.media.leveldb.LevelDBResult;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

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

    private static final String AND_CONNECTOR = " AND ";

    /**
     * Key corresponding to which last backed up generation number is stored.
     */
    private static final String LAST_BACKED_GENERATION_NUMBER_KEY = "LAST_BACKED_GENERATION_NUMBER";

    /**
     * String separator used for separating key, value pairs.
     */
    @VisibleForTesting
    static final String FIELD_SEPARATOR = ":::";

    /**
     * String separator used for key and value.
     */
    @VisibleForTesting
    static final String KEY_VALUE_SEPARATOR = "=";

    /**
     * Backup directory under file's directory.
     */
    private static final String BACKUP_DIRECTORY_NAME = "backup";

    /**
     * Name of files table in MediaProvider database.
     */
    private static final String FILES_TABLE_NAME = "files";

    /**
     * Array of columns backed up for restore in the future.
     */
    static final String[] BACKUP_COLUMNS = new String[]{
            FileColumns.IS_FAVORITE,
            FileColumns.MEDIA_TYPE,
            FileColumns.MIME_TYPE,
            FileColumns._USER_ID,
            FileColumns.SIZE,
            MediaColumns.DATE_TAKEN,
            MediaColumns.CD_TRACK_NUMBER,
            MediaColumns.ALBUM,
            MediaColumns.ARTIST,
            MediaColumns.AUTHOR,
            MediaColumns.COMPOSER,
            MediaColumns.GENRE,
            MediaColumns.TITLE,
            MediaColumns.YEAR,
            MediaColumns.DURATION,
            MediaColumns.NUM_TRACKS,
            MediaColumns.WRITER,
            MediaColumns.ALBUM_ARTIST,
            MediaColumns.DISC_NUMBER,
            MediaColumns.COMPILATION,
            MediaColumns.BITRATE,
            MediaColumns.CAPTURE_FRAMERATE,
            AudioColumns.TRACK,
            MediaColumns.DOCUMENT_ID,
            MediaColumns.INSTANCE_ID,
            MediaColumns.ORIGINAL_DOCUMENT_ID,
            MediaColumns.RESOLUTION,
            MediaColumns.ORIENTATION,
            VideoColumns.COLOR_STANDARD,
            VideoColumns.COLOR_TRANSFER,
            VideoColumns.COLOR_RANGE,
            FileColumns._VIDEO_CODEC_TYPE,
            MediaColumns.WIDTH,
            MediaColumns.HEIGHT,
            ImageColumns.DESCRIPTION,
            ImageColumns.EXPOSURE_TIME,
            ImageColumns.F_NUMBER,
            ImageColumns.ISO,
            ImageColumns.SCENE_CAPTURE_TYPE,
            FileColumns._SPECIAL_FORMAT,
            FileColumns.OWNER_PACKAGE_NAME,
            // Keeping at the last as it is a BLOB type and can have separator used in our
            // serialisation
            MediaColumns.XMP,
    };

    /**
     * Map used to store key id for given column and vice versa.
     */
    private static BiMap<String, String> sColumnToKeyBiMap;

    private final Context mContext;

    private final DatabaseHelper mExternalDatabaseHelper;

    private LevelDBInstance mLevelDBInstance;

    public BackupExecutor(Context context, DatabaseHelper databaseHelper) {
        mContext = context;
        mExternalDatabaseHelper = databaseHelper;
        mLevelDBInstance = LevelDBManager.getInstance(getBackupFilePath());
        createColumnToKeyMap();
    }


    private void createColumnToKeyMap() {
        // TODO(b/356340730): Move to xml definition
        sColumnToKeyBiMap = HashBiMap.create();
        sColumnToKeyBiMap.put("0", FileColumns.IS_FAVORITE);
        sColumnToKeyBiMap.put("1", FileColumns.MEDIA_TYPE);
        sColumnToKeyBiMap.put("2", FileColumns.MIME_TYPE);
        sColumnToKeyBiMap.put("3", FileColumns._USER_ID);
        sColumnToKeyBiMap.put("4", FileColumns.SIZE);
        sColumnToKeyBiMap.put("5", MediaColumns.DATE_TAKEN);
        sColumnToKeyBiMap.put("6", MediaColumns.CD_TRACK_NUMBER);
        sColumnToKeyBiMap.put("7", MediaColumns.ALBUM);
        sColumnToKeyBiMap.put("8", MediaColumns.ARTIST);
        sColumnToKeyBiMap.put("9", MediaColumns.AUTHOR);
        sColumnToKeyBiMap.put("10", MediaColumns.COMPOSER);
        sColumnToKeyBiMap.put("11", MediaColumns.GENRE);
        sColumnToKeyBiMap.put("12", MediaColumns.TITLE);
        sColumnToKeyBiMap.put("13", MediaColumns.YEAR);
        sColumnToKeyBiMap.put("14", MediaColumns.DURATION);
        sColumnToKeyBiMap.put("15", MediaColumns.NUM_TRACKS);
        sColumnToKeyBiMap.put("16", MediaColumns.WRITER);
        sColumnToKeyBiMap.put("17", MediaColumns.ALBUM_ARTIST);
        sColumnToKeyBiMap.put("18", MediaColumns.DISC_NUMBER);
        sColumnToKeyBiMap.put("19", MediaColumns.COMPILATION);
        sColumnToKeyBiMap.put("20", MediaColumns.BITRATE);
        sColumnToKeyBiMap.put("21", MediaColumns.CAPTURE_FRAMERATE);
        sColumnToKeyBiMap.put("22", AudioColumns.TRACK);
        sColumnToKeyBiMap.put("23", MediaColumns.DOCUMENT_ID);
        sColumnToKeyBiMap.put("24", MediaColumns.INSTANCE_ID);
        sColumnToKeyBiMap.put("25", MediaColumns.ORIGINAL_DOCUMENT_ID);
        sColumnToKeyBiMap.put("26", MediaColumns.RESOLUTION);
        sColumnToKeyBiMap.put("27", MediaColumns.ORIENTATION);
        sColumnToKeyBiMap.put("28", VideoColumns.COLOR_STANDARD);
        sColumnToKeyBiMap.put("29", VideoColumns.COLOR_TRANSFER);
        sColumnToKeyBiMap.put("30", VideoColumns.COLOR_RANGE);
        sColumnToKeyBiMap.put("31", FileColumns._VIDEO_CODEC_TYPE);
        sColumnToKeyBiMap.put("32", MediaColumns.WIDTH);
        sColumnToKeyBiMap.put("33", MediaColumns.HEIGHT);
        sColumnToKeyBiMap.put("34", ImageColumns.DESCRIPTION);
        sColumnToKeyBiMap.put("35", ImageColumns.EXPOSURE_TIME);
        sColumnToKeyBiMap.put("36", ImageColumns.F_NUMBER);
        sColumnToKeyBiMap.put("37", ImageColumns.ISO);
        sColumnToKeyBiMap.put("38", ImageColumns.SCENE_CAPTURE_TYPE);
        sColumnToKeyBiMap.put("39", FileColumns._SPECIAL_FORMAT);
        sColumnToKeyBiMap.put("40", FileColumns.OWNER_PACKAGE_NAME);
        // Adding number gap to allow addition of new values
        sColumnToKeyBiMap.put("80", MediaColumns.XMP);
    }

    /**
     * Addresses the following:-
     * 1. Gets last backed generation number from leveldb
     * 2. Backs up data for rows greater than last backed generation number
     * 3. Updates the new backed up generation number
     */
    public void doBackup(CancellationSignal signal) {
        final long lastBackedUpGenerationNumberFromLevelDb = getLastBackedUpGenerationNumber();
        final long currentDbGenerationNumber = mExternalDatabaseHelper.runWithoutTransaction(
                DatabaseHelper::getGeneration);
        final long lastBackedUpGenerationNumber = clearBackupIfNeededAndReturnLastBackedUpNumber(
                currentDbGenerationNumber, lastBackedUpGenerationNumberFromLevelDb);
        Log.i(TAG, "Last backed up generation number: " + lastBackedUpGenerationNumber);
        long lastGenerationNumber = backupData(lastBackedUpGenerationNumber, signal);
        updateLastBackedUpGenerationNumber(lastGenerationNumber);
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
        BiMap<String, String> inverseMap = sColumnToKeyBiMap.inverse();
        for (String backupColumn : BACKUP_COLUMNS) {
            Optional<String> optionalValue = extractValue(c, backupColumn);
            if (!optionalValue.isPresent()) {
                continue;
            }

            sb.append(inverseMap.get(backupColumn)).append(KEY_VALUE_SEPARATOR).append(
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
        if (path != null) {
            mLevelDBInstance.delete(path);
        }
    }
}
