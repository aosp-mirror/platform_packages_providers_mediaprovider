/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.providers.media.DatabaseHelper.INTERNAL_DATABASE_NAME;
import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.dao.FileRow;
import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.stableuris.dao.BackupIdRow;
import com.android.providers.media.util.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * To ensure that the ids of MediaStore database uris are stable and reliable.
 */
public class DatabaseBackupAndRecovery {

    private static final String sRecoveryDirectoryPath =
            "/storage/emulated/" + UserHandle.myUserId() + "/.transforms/recovery";

    private final MediaProvider mMediaProvider;
    private final ConfigStore mConfigStore;
    private final VolumeCache mVolumeCache;

    protected DatabaseBackupAndRecovery(MediaProvider mediaProvider, ConfigStore configStore,
            VolumeCache volumeCache) {
        mMediaProvider = mediaProvider;
        mConfigStore = configStore;
        mVolumeCache = volumeCache;
    }

    /**
     * Returns true if migration and recovery code flow for stable uris is enabled for given volume.
     */
    protected boolean isStableUrisEnabled(String volumeName) {
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
                return mConfigStore.isStableUrisForInternalVolumeEnabled()
                        || SystemProperties.getBoolean("persist.sys.fuse.backup.internal_db_backup",
                        /* defaultValue */ false);
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return mConfigStore.isStableUrisForExternalVolumeEnabled()
                        || SystemProperties.getBoolean(
                        "persist.sys.fuse.backup.external_volume_backup",
                        /* defaultValue */ false);
            default:
                return false;
        }
    }

    protected void onConfigPropertyChangeListener() {
        if (mConfigStore.isStableUrisForInternalVolumeEnabled()
                && mVolumeCache.getExternalVolumeNames().contains(
                MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            Log.i(TAG,
                    "On device config change, found stable uri support enabled. Attempting backup"
                            + " and recovery setup.");
            setupVolumeDbBackupForInternalIfMissing();
        }
    }

    /**
     * On device boot, leveldb setup is done as part of attachVolume call for primary external.
     * Also, on device config flag change, we check if flag is enabled, if yes, we proceed to
     * setup(no-op if connection already exists). So, we setup backup and recovery for internal
     * volume on Media mount signal of EXTERNAL_PRIMARY.
     */
    protected void setupVolumeDbBackupAndRecovery(MediaVolume volume) {
        // We are setting up leveldb instance only for internal volume as of now. Since internal
        // volume does not have any fuse daemon thread, leveldb instance is created by fuse
        // daemon thread of EXTERNAL_PRIMARY.
        if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volume.getName())) {
            // Set backup only for external primary for now.
            return;
        }
        // Do not create leveldb instance if stable uris is not enabled for internal volume.
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)) {
            // Return if we are not supporting backup for internal volume
            return;
        }

        try {
            if (!new File(sRecoveryDirectoryPath).exists()) {
                new File(sRecoveryDirectoryPath).mkdirs();
            }
            MediaProvider.getFuseDaemonForFile(volume.getPath(), mVolumeCache)
                    .setupVolumeDbBackup();
        } catch (IOException e) {
            Log.e(TAG, "Failure in setting up backup and recovery for volume: " + volume.getName(),
                    e);
        }
    }

    /**
     * Backs up databases to external storage to ensure stable URIs.
     */
    public void backupDatabases(CancellationSignal signal) {
        Log.i(TAG, "Triggering database backup");
        backupInternalDatabase(signal);
    }

    protected Optional<BackupIdRow> readDataFromBackup(String volumeName, String filePath) {
        if (!isStableUrisEnabled(volumeName)) {
            return Optional.empty();
        }

        final String fuseDaemonFilePath = getFuseDaemonFilePath(filePath);
        try {
            final String data = getFuseDaemonForPath(fuseDaemonFilePath).readBackedUpData(filePath);
            return Optional.of(BackupIdRow.deserialize(data));
        } catch (Exception e) {
            Log.e(TAG, "Failure in getting backed up data for filePath: " + filePath, e);
            return Optional.empty();
        }
    }

    protected void backupInternalDatabase(CancellationSignal signal) {
        final Optional<DatabaseHelper> dbHelper =
                mMediaProvider.getDatabaseHelper(INTERNAL_DATABASE_NAME);

        if (!dbHelper.isPresent()) {
            Log.e(TAG, "Unable to backup internal db");
            return;
        }

        final DatabaseHelper internalDbHelper = dbHelper.get();

        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)
                || internalDbHelper.isDatabaseRecovering()) {
            return;
        }
        setupVolumeDbBackupForInternalIfMissing();
        FuseDaemon fuseDaemon;
        try {
            fuseDaemon = getFuseDaemonForPath("/storage/emulated/" + UserHandle.myUserId());
        } catch (FileNotFoundException e) {
            Log.e(TAG,
                    "Fuse Daemon not found for primary external storage, skipping backing up of "
                            + "internal database.",
                    e);
            return;
        }

        internalDbHelper.runWithTransaction((db) -> {
            try (Cursor c = db.query(true, "files",
                    new String[]{
                            MediaStore.Files.FileColumns._ID,
                            MediaStore.Files.FileColumns.DATA,
                            MediaStore.Files.FileColumns.IS_FAVORITE,
                            MediaStore.Files.FileColumns.IS_PENDING,
                            MediaStore.Files.FileColumns.IS_TRASHED,
                            MediaStore.Files.FileColumns.MEDIA_TYPE,
                            MediaStore.Files.FileColumns._USER_ID,
                            MediaStore.Files.FileColumns.DATE_EXPIRES,
                            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                    }, null, null, null, null, null, null, signal)) {
                while (c.moveToNext()) {
                    final long id = c.getLong(0);
                    final String data = c.getString(1);
                    final boolean isFavorite = c.getInt(2) != 0;
                    final boolean isPending = c.getInt(3) != 0;
                    final boolean isTrashed = c.getInt(4) != 0;
                    final int mediaType = c.getInt(5);
                    final int userId = c.getInt(6);
                    final String dateExpires = c.getString(7);
                    final String ownerPackageName = c.getString(8);
                    BackupIdRow backupIdRow = createBackupIdRow(id, mediaType, isFavorite,
                            isPending, isTrashed, userId, dateExpires, ownerPackageName);
                    fuseDaemon.backupVolumeDbData(data, BackupIdRow.serialize(backupIdRow));
                }
                Log.d(TAG,
                        "Backed up data of internal database to external storage on idle "
                                + "maintenance.");
            } catch (Exception e) {
                Log.e(TAG, "Failure in backing up internal database to external storage.", e);
            }
            return null;
        });
    }

    protected void deleteBackupForVolume(String volumeName) {
        File dbFilePath = new File(
                String.format(Locale.ROOT, "%s/%s.db", sRecoveryDirectoryPath, volumeName));
        if (dbFilePath.exists()) {
            dbFilePath.delete();
        }
    }

    protected boolean isFuseDaemonReadyForFilePath(@NonNull String filePath) {
        FuseDaemon daemon = null;
        try {
            daemon = getFuseDaemonForPath(filePath);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "No fuse daemon exists for path:" + filePath);
        }
        return daemon != null;
    }

    protected String[] readBackedUpFilePaths(String volumeName, String lastReadValue, int limit) {
        if (!isStableUrisEnabled(volumeName)) {
            return new String[0];
        }

        final String fuseDaemonFilePath = "/storage/emulated/" + UserHandle.myUserId();
        try {
            return getFuseDaemonForPath(fuseDaemonFilePath).readBackedUpFilePaths(volumeName,
                    lastReadValue, limit);
        } catch (IOException e) {
            Log.e(TAG, "Failure in reading backed up file paths for volume: " + volumeName, e);
            return new String[0];
        }
    }

    protected void updateNextRowIdXattr(DatabaseHelper helper, long id) {
        if (helper.isInternal()) {
            updateNextRowIdForInternal(helper, id);
            return;
        }

        if (!helper.isNextRowIdBackupEnabled()) {
            return;
        }

        Optional<Long> nextRowIdBackupOptional = helper.getNextRowId();
        if (!nextRowIdBackupOptional.isPresent()) {
            throw new RuntimeException(
                    String.format(Locale.ROOT, "Cannot find next row id xattr for %s.",
                            helper.getDatabaseName()));
        }

        if (id >= nextRowIdBackupOptional.get()) {
            helper.backupNextRowId(id);
        }
    }

    @NonNull
    private FuseDaemon getFuseDaemonForPath(@NonNull String path)
            throws FileNotFoundException {
        return MediaProvider.getFuseDaemonForFile(new File(path), mVolumeCache);
    }

    protected void updateNextRowIdAndSetDirtyIfRequired(@NonNull DatabaseHelper helper,
            @NonNull FileRow oldRow, @NonNull FileRow newRow) {
        updateNextRowIdXattr(helper, newRow.getId());
        if (backedUpValuesChanged(helper, oldRow, newRow)) {
            markBackupAsDirty(helper, oldRow);
        }
    }

    /**
     * Backs up DB data in external storage to recover in case of DB rollback.
     */
    protected void backupVolumeDbData(DatabaseHelper databaseHelper, String volumeName,
            String insertedFilePath, FileRow insertedRow) {
        if (!isBackupUpdateRequired(databaseHelper, insertedRow)) {
            return;
        }

        // For all internal file paths, redirect to external primary fuse daemon.
        final String fuseDaemonFilePath = getFuseDaemonFilePath(insertedFilePath);
        try {
            final BackupIdRow value = createBackupIdRow(insertedRow);
            getFuseDaemonForPath(fuseDaemonFilePath).backupVolumeDbData(insertedFilePath,
                    BackupIdRow.serialize(value));
        } catch (IOException e) {
            Log.e(TAG, "Failure in backing up data to external storage", e);
        }
    }

    private String getFuseDaemonFilePath(String filePath) {
        return filePath.startsWith("/storage") ? filePath
                : "/storage/emulated/" + UserHandle.myUserId();
    }

    private BackupIdRow createBackupIdRow(FileRow insertedRow) {
        return createBackupIdRow(insertedRow.getId(), insertedRow.getMediaType(),
                insertedRow.isFavorite(), insertedRow.isPending(), insertedRow.isTrashed(),
                insertedRow.getUserId(), insertedRow.getDateExpires(),
                insertedRow.getOwnerPackageName());
    }

    private BackupIdRow createBackupIdRow(long id, int mediaType, boolean isFavorite,
            boolean isPending, boolean isTrashed, int userId, String dateExpires,
            String ownerPackageName) {
        BackupIdRow.Builder builder = BackupIdRow.newBuilder(id);
        builder.setMediaType(mediaType);
        builder.setIsFavorite(isFavorite ? 1 : 0);
        builder.setIsPending(isPending ? 1 : 0);
        builder.setIsTrashed(isTrashed ? 1 : 0);
        builder.setUserId(userId);
        builder.setDateExpires(dateExpires);
        // TODO(b/259258592): Set owner id based on owner package name and user_id(needed for app
        //  cloning)
        return builder.setIsDirty(false).build();
    }


    /**
     * Deletes backed up data(needed for recovery) from external storage.
     */
    protected void deleteFromDbBackup(DatabaseHelper databaseHelper, FileRow deletedRow) {
        if (!isBackupUpdateRequired(databaseHelper, deletedRow)) {
            return;
        }

        String deletedFilePath = deletedRow.getPath();
        // For all internal file paths, redirect to external primary fuse daemon.
        String fuseDaemonFilePath = getFuseDaemonFilePath(deletedFilePath);
        try {
            getFuseDaemonForPath(fuseDaemonFilePath).deleteDbBackup(deletedFilePath);
        } catch (IOException e) {
            Log.w(TAG, "Failure in deleting backup data for key: " + deletedFilePath, e);
        }
    }

    protected boolean isBackupUpdateRequired(DatabaseHelper databaseHelper, FileRow row) {
        if (isStableUrisEnabled(row.getVolumeName()) && !databaseHelper.isDatabaseRecovering()) {
            return true;
        }

        return false;
    }


    private void updateNextRowIdForInternal(DatabaseHelper helper, long id) {
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)) {
            return;
        }

        Optional<Long> nextRowIdBackupOptional = helper.getNextRowId();

        if (!nextRowIdBackupOptional.isPresent()) {
            return;
        }

        if (id >= nextRowIdBackupOptional.get()) {
            helper.backupNextRowId(id);
        }
    }

    private void setupVolumeDbBackupForInternalIfMissing() {
        try {
            if (!new File(sRecoveryDirectoryPath).exists()) {
                new File(sRecoveryDirectoryPath).mkdirs();
            }
            final String fuseDaemonFilePath = "/storage/emulated/" + UserHandle.myUserId();
            getFuseDaemonForPath(fuseDaemonFilePath).setupVolumeDbBackup();
        } catch (IOException e) {
            Log.e(TAG, "Failure in setting up backup and recovery for internal database.", e);
        }
    }

    private boolean backedUpValuesChanged(DatabaseHelper helper, FileRow oldRow, FileRow newRow) {
        if (helper.isInternal()) {
            return backedUpValuesChangedForInternal(oldRow, newRow);
        }

        return false;
    }

    private boolean backedUpValuesChangedForInternal(FileRow oldRow, FileRow newRow) {
        if (oldRow.getId() == newRow.getId() && oldRow.isFavorite() == newRow.isFavorite()) {
            return false;
        }

        return true;
    }

    private void markBackupAsDirty(DatabaseHelper databaseHelper, FileRow updatedRow) {
        if (!isStableUrisEnabled(updatedRow.getVolumeName())) {
            return;
        }

        if (databaseHelper.isDatabaseRecovering()) {
            return;
        }

        final String updatedFilePath = updatedRow.getPath();
        // For all internal file paths, redirect to external primary fuse daemon.
        final String fuseDaemonFilePath = getFuseDaemonFilePath(updatedFilePath);
        try {
            getFuseDaemonForPath(fuseDaemonFilePath).backupVolumeDbData(updatedFilePath,
                    BackupIdRow.serialize(BackupIdRow.newBuilder(updatedRow.getId()).setIsDirty(
                            true).build()));
        } catch (IOException e) {
            Log.e(TAG, "Failure in marking data as dirty to external storage for path:"
                    + updatedFilePath, e);
        }
    }

    protected static void insertDataInDatabase(SQLiteDatabase db, BackupIdRow row, String filePath,
            String volumeName) {
        final ContentValues values = createValuesFromFileRow(row, filePath, volumeName);
        if (db.insert("files", null, values) == -1) {
            Log.e(TAG, "Failed to insert " + values + "; continuing");
        }
    }

    private static ContentValues createValuesFromFileRow(BackupIdRow row, String filePath,
            String volumeName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns._ID, row.getId());
        values.put(MediaStore.Files.FileColumns.IS_FAVORITE, row.getIsFavorite());
        values.put(MediaStore.Files.FileColumns.IS_PENDING, row.getIsPending());
        values.put(MediaStore.Files.FileColumns.IS_TRASHED, row.getIsTrashed());
        values.put(MediaStore.Files.FileColumns._USER_ID, row.getUserId());
        values.put(MediaStore.Files.FileColumns.DATA, filePath);
        values.put(MediaStore.Files.FileColumns.VOLUME_NAME, volumeName);
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE, row.getMediaType());
        if (!StringUtils.isNullOrEmpty(row.getDateExpires())) {
            values.put(MediaStore.Files.FileColumns.DATE_EXPIRES,
                    Long.valueOf(row.getDateExpires()));
        }
        // TODO(b/259258592): Recover owner package name from owner id
        return values;
    }

    /**
     * Returns list of backed up files from external storage.
     */
    protected List<File> getBackupFiles() {
        return Arrays.asList(new File(sRecoveryDirectoryPath).listFiles());
    }
}
