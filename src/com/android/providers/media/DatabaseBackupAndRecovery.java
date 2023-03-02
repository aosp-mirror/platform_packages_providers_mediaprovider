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
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__PUBLIC;
import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.providers.media.dao.FileRow;
import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.stableuris.dao.BackupIdRow;
import com.android.providers.media.util.StringUtils;

import com.google.common.base.Strings;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * To ensure that the ids of MediaStore database uris are stable and reliable.
 */
public class DatabaseBackupAndRecovery {

    private static final String sRecoveryDirectoryPath =
            "/storage/emulated/" + UserHandle.myUserId() + "/.transforms/recovery";

    /**
     * Path for storing owner id to owner package identifier relation and vice versa.
     * Lower file system path is used as upper file system does not support xattrs.
     */
    private static final String sOwnerRelationBackupPath =
            "/data/media/" + UserHandle.myUserId()
                    + "/.transforms/recovery/leveldb-ownership.db";

    /**
     * Frequency at which next value of owner id is backed up in the external storage.
     */
    private static final int NEXT_OWNER_ID_BACKUP_FREQUENCY = 50;

    /**
     * Start value used for next owner id.
     */
    private static final int NEXT_OWNER_ID_DEFAULT_VALUE = 0;

    /**
     * Key name of xattr used to set next owner id on ownership DB.
     */
    private static final String NEXT_OWNER_ID_XATTR_KEY = "user.nextownerid";

    /**
     * External primary storage root path for given user.
     */
    private static final String EXTERNAL_PRIMARY_ROOT_PATH =
            "/storage/emulated/" + UserHandle.myUserId();

    /**
     * Number of records to read from leveldb in a JNI call.
     */
    protected static final int LEVEL_DB_READ_LIMIT = 1000;

    /**
     * Stores cached value of next owner id. This helps in improving performance by backing up next
     * row id less frequently in the external storage.
     */
    private AtomicInteger mNextOwnerId;

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
            fuseDaemon = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
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
                    BackupIdRow backupIdRow = createBackupIdRow(fuseDaemon, id, mediaType,
                            isFavorite, isPending, isTrashed, userId, dateExpires,
                            ownerPackageName);
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

        try {
            return getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH).readBackedUpFilePaths(
                    volumeName, lastReadValue, limit);
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
        if (backedUpValuesChanged(oldRow, newRow)) {
            markBackupAsDirty(helper, oldRow);
        }
    }

    /**
     * Backs up DB data in external storage to recover in case of DB rollback.
     */
    protected void backupVolumeDbData(DatabaseHelper databaseHelper, FileRow insertedRow) {
        if (!isBackupUpdateRequired(databaseHelper, insertedRow)) {
            return;
        }

        // For all internal file paths, redirect to external primary fuse daemon.
        final String fuseDaemonFilePath = getFuseDaemonFilePath(insertedRow.getPath());
        try {
            FuseDaemon fuseDaemon = getFuseDaemonForPath(fuseDaemonFilePath);
            final BackupIdRow value = createBackupIdRow(fuseDaemon, insertedRow);
            fuseDaemon.backupVolumeDbData(insertedRow.getPath(), BackupIdRow.serialize(value));
        } catch (Exception e) {
            Log.e(TAG, "Failure in backing up data to external storage", e);
        }
    }

    private String getFuseDaemonFilePath(String filePath) {
        return filePath.startsWith("/storage") ? filePath : EXTERNAL_PRIMARY_ROOT_PATH;
    }

    private BackupIdRow createBackupIdRow(FuseDaemon fuseDaemon, FileRow insertedRow)
            throws IOException {
        return createBackupIdRow(fuseDaemon, insertedRow.getId(), insertedRow.getMediaType(),
                insertedRow.isFavorite(), insertedRow.isPending(), insertedRow.isTrashed(),
                insertedRow.getUserId(), insertedRow.getDateExpires(),
                insertedRow.getOwnerPackageName());
    }

    private BackupIdRow createBackupIdRow(FuseDaemon fuseDaemon, long id, int mediaType,
            boolean isFavorite,
            boolean isPending, boolean isTrashed, int userId, String dateExpires,
            String ownerPackageName) throws IOException {
        BackupIdRow.Builder builder = BackupIdRow.newBuilder(id);
        builder.setMediaType(mediaType);
        builder.setIsFavorite(isFavorite ? 1 : 0);
        builder.setIsPending(isPending ? 1 : 0);
        builder.setIsTrashed(isTrashed ? 1 : 0);
        builder.setUserId(userId);
        builder.setDateExpires(dateExpires);
        // We set owner package id instead of owner package name in the backup. When an
        // application is uninstalled, all media rows corresponding to it will be orphaned and
        // would have owner package name as null. This should not change if application is
        // installed again. Therefore, we are storing owner id instead of owner package name. On
        // package uninstallation, we delete the owner id relation from the backup. All rows
        // recovered for orphaned owner ids will have package name as null. Since we also need to
        // support cloned apps, we are storing a combination of owner package name and user id to
        // uniquely identify a package.
        builder.setOwnerPackagedId(getOwnerPackageId(fuseDaemon, ownerPackageName, userId));
        return builder.setIsDirty(false).build();
    }


    private int getOwnerPackageId(FuseDaemon fuseDaemon, String ownerPackageName, int userId)
            throws IOException {
        if (Strings.isNullOrEmpty(ownerPackageName)) {
            // We store -1 in the backup if owner package name is null.
            return -1;
        }

        // Create identifier of format "owner_pkg_name::user_id". Tightly coupling owner package
        // name and user id helps in handling app cloning scenarios.
        String ownerPackageIdentifier = createOwnerPackageIdentifier(ownerPackageName, userId);
        // Read any existing entry for given owner package name and user id
        String ownerId = fuseDaemon.readFromOwnershipBackup(ownerPackageIdentifier);
        if (!ownerId.trim().isEmpty()) {
            // Use existing owner id if found and is positive
            int val = Integer.parseInt(ownerId);
            if (val >= 0) {
                return val;
            }
        }

        // Create new owner id for given owner package name and uid combination
        int nextOwnerId = getAndIncrementNextOwnerId();
        fuseDaemon.createOwnerIdRelation(String.valueOf(nextOwnerId), ownerPackageIdentifier);
        return nextOwnerId;
    }

    private String createOwnerPackageIdentifier(String ownerPackageName, int userId) {
        return ownerPackageName.trim().concat("::").concat(String.valueOf(userId));
    }

    private Pair<String, Integer> getPackageNameAndUserId(String ownerPackageIdentifier) {
        if (ownerPackageIdentifier.trim().isEmpty()) {
            return Pair.create(null, null);
        }

        String[] arr = ownerPackageIdentifier.trim().split("::");
        return Pair.create(arr[0], Integer.valueOf(arr[1]));
    }

    private int getAndIncrementNextOwnerId() {
        if (mNextOwnerId == null) {
            Optional<String> nextOwnerIdOptional = getXattr(sOwnerRelationBackupPath,
                    NEXT_OWNER_ID_XATTR_KEY);
            mNextOwnerId = nextOwnerIdOptional.map(
                    s -> new AtomicInteger(Integer.parseInt(s))).orElseGet(
                    () -> new AtomicInteger(NEXT_OWNER_ID_DEFAULT_VALUE));
        }
        if (mNextOwnerId.get() % NEXT_OWNER_ID_BACKUP_FREQUENCY == 0) {
            updateNextOwnerId(mNextOwnerId.get() + NEXT_OWNER_ID_BACKUP_FREQUENCY);
        }
        int returnValue = mNextOwnerId.get();
        mNextOwnerId.set(returnValue + 1);
        return returnValue;
    }

    private void updateNextOwnerId(int val) {
        setXattr(sOwnerRelationBackupPath, NEXT_OWNER_ID_XATTR_KEY, String.valueOf(val));
        Log.d(TAG, "Updated next owner id to: " + val);
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
            getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH).setupVolumeDbBackup();
        } catch (IOException e) {
            Log.e(TAG, "Failure in setting up backup and recovery for internal database.", e);
        }
    }

    private static boolean backedUpValuesChanged(FileRow oldRow, FileRow newRow) {
        return oldRow.getId() != newRow.getId() || oldRow.isTrashed() != newRow.isTrashed()
                || oldRow.isFavorite() != newRow.isFavorite()
                || oldRow.isPending() != newRow.isPending()
                || !Objects.equals(oldRow.getOwnerPackageName(), newRow.getOwnerPackageName())
                || oldRow.getMediaType() != newRow.getMediaType()
                || !Objects.equals(oldRow.getDateExpires(), newRow.getDateExpires())
                || oldRow.getUserId() != newRow.getUserId();
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

    /**
     * Reads value corresponding to given key from xattr on given path.
     */
    public static Optional<String> getXattr(String path, String key) {
        try {
            return Optional.of(Arrays.toString(Os.getxattr(path, key)));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Sets key and value as xattr on given path.
     */
    public static boolean setXattr(String path, String key, String value) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            // Map id value to xattr key
            Os.setxattr(path, key, value.getBytes(), 0);
            Os.fsync(pfd.getFileDescriptor());
            Log.d(TAG, String.format("xattr set to %s for key:%s on path: %s.", value, key, path));
            return true;
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.ROOT, "Failed to set xattr:%s to %s for path: %s.", key,
                    value, path), e);
            return false;
        }
    }

    protected void insertDataInDatabase(SQLiteDatabase db, BackupIdRow row, String filePath,
            String volumeName) {
        final ContentValues values = createValuesFromFileRow(row, filePath, volumeName);
        if (db.insert("files", null, values) == -1) {
            Log.e(TAG, "Failed to insert " + values + "; continuing");
        }
    }

    private ContentValues createValuesFromFileRow(BackupIdRow row, String filePath,
            String volumeName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns._ID, row.getId());
        values.put(MediaStore.Files.FileColumns.IS_FAVORITE, row.getIsFavorite());
        values.put(MediaStore.Files.FileColumns.IS_PENDING, row.getIsPending());
        values.put(MediaStore.Files.FileColumns.IS_TRASHED, row.getIsTrashed());
        values.put(MediaStore.Files.FileColumns.DATA, filePath);
        values.put(MediaStore.Files.FileColumns.VOLUME_NAME, volumeName);
        values.put(MediaStore.Files.FileColumns._USER_ID, row.getUserId());
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE, row.getMediaType());
        if (!StringUtils.isNullOrEmpty(row.getDateExpires())) {
            values.put(MediaStore.Files.FileColumns.DATE_EXPIRES,
                    Long.valueOf(row.getDateExpires()));
        }
        if (row.getOwnerPackageId() >= 0) {
            Pair<String, Integer> ownerPackageNameAndUidPair = getOwnerPackageNameAndUidPair(
                    row.getOwnerPackageId());
            if (ownerPackageNameAndUidPair.first != null) {
                values.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                        ownerPackageNameAndUidPair.first);
            }
            if (ownerPackageNameAndUidPair.second != null) {
                values.put(MediaStore.Files.FileColumns._USER_ID,
                        ownerPackageNameAndUidPair.second);
            }
        }

        return values;
    }

    private Pair<String, Integer> getOwnerPackageNameAndUidPair(int ownerPackageId) {
        try {
            String ownerPackageIdentifier = getFuseDaemonForPath(
                    EXTERNAL_PRIMARY_ROOT_PATH).readFromOwnershipBackup(
                    String.valueOf(ownerPackageId));
            return getPackageNameAndUserId(ownerPackageIdentifier);
        } catch (IOException e) {
            Log.e(TAG, "Failure in reading owner details for owner id:" + ownerPackageId, e);
            return Pair.create(null, null);
        }
    }

    protected void recoverData(SQLiteDatabase db, String volumeName) {
        final long startTime = SystemClock.elapsedRealtime();
        int i = 0;
        final String fuseFilePath = getFuseFilePathFromVolumeName(volumeName);
        // Wait for external primary to be attached as we use same thread for internal volume.
        // Maximum wait for 10s
        while (!isFuseDaemonReadyForFilePath(fuseFilePath) && i < 1000) {
            Log.d(TAG, "Waiting for fuse daemon to be ready.");
            // Poll after every 10ms
            SystemClock.sleep(10);
            i++;
        }
        if (!isFuseDaemonReadyForFilePath(fuseFilePath)) {
            Log.e(TAG, "Could not recover data as fuse daemon could not serve requests.");
            return;
        }

        long rowsRecovered = 0;
        long dirtyRowsCount = 0;
        String[] backedUpFilePaths;
        String lastReadValue = "";

        while (true) {
            backedUpFilePaths = readBackedUpFilePaths(volumeName, lastReadValue,
                    LEVEL_DB_READ_LIMIT);
            if (backedUpFilePaths.length <= 0) {
                break;
            }

            for (String filePath : backedUpFilePaths) {
                Optional<BackupIdRow> fileRow = readDataFromBackup(volumeName, filePath);
                if (fileRow.isPresent()) {
                    if (fileRow.get().getIsDirty()) {
                        dirtyRowsCount++;
                        continue;
                    }

                    insertDataInDatabase(db, fileRow.get(), filePath, volumeName);
                    rowsRecovered++;
                }
            }

            // Read less rows than expected
            if (backedUpFilePaths.length < LEVEL_DB_READ_LIMIT) {
                break;
            }
            lastReadValue = backedUpFilePaths[backedUpFilePaths.length - 1];
        }
        long recoveryTime = SystemClock.elapsedRealtime() - startTime;
        MediaProviderStatsLog.write(MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED,
                getVolumeName(volumeName), recoveryTime, rowsRecovered, dirtyRowsCount);
        Log.i(TAG, String.format(Locale.ROOT, "%d rows recovered for volume:%s.", rowsRecovered,
                volumeName));
        Log.i(TAG, String.format(Locale.ROOT, "Recovery time: %d ms", recoveryTime));
    }

    private int getVolumeName(String volumeName) {
        if (volumeName.equalsIgnoreCase(MediaStore.VOLUME_INTERNAL)) {
            return MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__INTERNAL;
        } else if (volumeName.equalsIgnoreCase(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            return MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__EXTERNAL_PRIMARY;
        }

        return MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__PUBLIC;
    }

    private static String getFuseFilePathFromVolumeName(String volumeName) {
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return EXTERNAL_PRIMARY_ROOT_PATH;
            default:
                return "/storage/" + volumeName;
        }
    }

    /**
     * Returns list of backed up files from external storage.
     */
    protected List<File> getBackupFiles() {
        return Arrays.asList(new File(sRecoveryDirectoryPath).listFiles());
    }
}
