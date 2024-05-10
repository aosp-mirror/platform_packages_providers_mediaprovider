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

import static com.android.providers.media.DatabaseHelper.DATA_MEDIA_XATTR_DIRECTORY_PATH;
import static com.android.providers.media.DatabaseHelper.EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_VOLUME_RECOVERY_REPORTED__VOLUME__PUBLIC;
import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.providers.media.dao.FileRow;
import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.stableuris.dao.BackupIdRow;
import com.android.providers.media.util.StringUtils;

import com.google.common.base.Strings;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * To ensure that the ids of MediaStore database uris are stable and reliable.
 */
public class DatabaseBackupAndRecovery {

    private static final String RECOVERY_DIRECTORY_PATH =
            "/storage/emulated/" + UserHandle.myUserId() + "/.transforms/recovery";

    /**
     * Path for storing owner id to owner package identifier relation and vice versa.
     * Lower file system path is used as upper file system does not support xattrs.
     */
    private static final String OWNER_RELATION_BACKUP_PATH =
            "/data/media/" + UserHandle.myUserId() + "/.transforms/recovery/leveldb-ownership";

    /**
     * Every LevelDB table name starts with this prefix.
     */
    private static final String LEVEL_DB_PREFIX = "leveldb-";

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
     * Key name of xattr used to store last modified generation number.
     */
    private static final String LAST_BACKEDUP_GENERATION_XATTR_KEY = "user.lastbackedgeneration";

    /**
     * External primary storage root path for given user.
     */
    private static final String EXTERNAL_PRIMARY_ROOT_PATH =
            "/storage/emulated/" + UserHandle.myUserId();

    /**
     * Array of columns backed up in external storage.
     */
    private static final String[] QUERY_COLUMNS = new String[]{
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.IS_PENDING,
            MediaStore.Files.FileColumns.IS_TRASHED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns._USER_ID,
            MediaStore.Files.FileColumns.DATE_EXPIRES,
            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
            MediaStore.Files.FileColumns.GENERATION_MODIFIED,
            MediaStore.Files.FileColumns.VOLUME_NAME
    };

    /**
     * Wait time of 10 seconds in millis.
     */
    private static final long WAIT_TIME_10_SECONDS_IN_MILLIS = 10000;

    /**
     * Number of records to read from leveldb in a JNI call.
     */
    protected static final int LEVEL_DB_READ_LIMIT = 1000;

    /**
     * Stores cached value of next owner id. This helps in improving performance by backing up next
     * row id less frequently in the external storage.
     */
    private AtomicInteger mNextOwnerId;

    /**
     * Stores value of next backup of owner id.
     */
    private AtomicInteger mNextOwnerIdBackup;
    private final ConfigStore mConfigStore;
    private final VolumeCache mVolumeCache;
    private Set<String> mSetupCompletePublicVolumes = ConcurrentHashMap.newKeySet();

    private static Map<String, String> sOwnerIdRelationMap;

    protected DatabaseBackupAndRecovery(ConfigStore configStore, VolumeCache volumeCache) {
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
                // public volume
                return isStableUrisEnabled(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        && mConfigStore.isStableUrisForPublicVolumeEnabled()
                        || SystemProperties.getBoolean("persist.sys.fuse.backup.public_db_backup",
                        /* defaultValue */ false);
        }
    }

    /**
     * On device boot, leveldb setup is done as part of attachVolume call for primary external.
     * Also, on device config flag change, we check if flag is enabled, if yes, we proceed to
     * setup(no-op if connection already exists). So, we setup backup and recovery for internal
     * volume on Media mount signal of EXTERNAL_PRIMARY.
     */
    protected synchronized void setupVolumeDbBackupAndRecovery(String volumeName, File volumePath) {
        // Since internal volume does not have any fuse daemon thread, leveldb instance
        // for internal volume is created by fuse daemon thread of EXTERNAL_PRIMARY.
        if (MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)) {
            // Set backup only for external primary for now.
            return;
        }
        // Do not create leveldb instance if stable uris is not enabled for internal volume.
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)) {
            // Return if we are not supporting backup for internal volume
            return;
        }

        if (mSetupCompletePublicVolumes.contains(volumeName)) {
            // Return if setup is already done
            return;
        }

        final long startTime = SystemClock.elapsedRealtime();
        try {
            if (!new File(RECOVERY_DIRECTORY_PATH).exists()) {
                new File(RECOVERY_DIRECTORY_PATH).mkdirs();
            }
            FuseDaemon fuseDaemon = getFuseDaemonForFileWithWait(new File(
                    DatabaseBackupAndRecovery.EXTERNAL_PRIMARY_ROOT_PATH));
            Log.d(TAG, "Received db backup Fuse Daemon for: " + volumeName);
            if (isStableUrisEnabled(volumeName)) {
                if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)) {
                    // Setup internal and external volumes
                    fuseDaemon.setupVolumeDbBackup();
                } else {
                    // Setup public volume
                    fuseDaemon.setupPublicVolumeDbBackup(volumeName);
                }
                mSetupCompletePublicVolumes.add(volumeName);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failure in setting up backup and recovery for volume: " + volumeName, e);
            return;
        } finally {
            Log.i(TAG, "Backup and recovery setup time taken in milliseconds:" + (
                    SystemClock.elapsedRealtime() - startTime));
        }
        Log.i(TAG, "Successfully set up backup and recovery for volume: " + volumeName);
    }

    /**
     * Backs up databases to external storage to ensure stable URIs.
     */
    public void backupDatabases(DatabaseHelper internalDatabaseHelper,
            DatabaseHelper externalDatabaseHelper, CancellationSignal signal) {
        setupVolumeDbBackupAndRecovery(MediaStore.VOLUME_EXTERNAL_PRIMARY,
          new File(EXTERNAL_PRIMARY_ROOT_PATH));
        Log.i(TAG, "Triggering database backup");
        backupInternalDatabase(internalDatabaseHelper, signal);
        backupExternalDatabase(externalDatabaseHelper, MediaStore.VOLUME_EXTERNAL_PRIMARY, signal);

        for (MediaVolume mediaVolume : mVolumeCache.getExternalVolumes()) {
            if (mediaVolume.isPublicVolume()) {
                setupVolumeDbBackupAndRecovery(mediaVolume.getName(),
                        new File(EXTERNAL_PRIMARY_ROOT_PATH));
                backupExternalDatabase(externalDatabaseHelper, mediaVolume.getName(), signal);
            }
        }
    }

    protected Optional<BackupIdRow> readDataFromBackup(String volumeName, String filePath) {
        if (!isStableUrisEnabled(volumeName)) {
            return Optional.empty();
        }

        try {
            final String data = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH)
                    .readBackedUpData(filePath);
            if (data == null || data.isEmpty()) {
                Log.w(TAG, "No backup found for path: " + filePath);
                return Optional.empty();
            }

            return Optional.of(BackupIdRow.deserialize(data));
        } catch (Exception e) {
            Log.e(TAG, "Failure in getting backed up data for filePath: " + filePath, e);
            return Optional.empty();
        }
    }

    protected synchronized void backupInternalDatabase(DatabaseHelper internalDbHelper,
            CancellationSignal signal) {
        if (!isStableUrisEnabled(MediaStore.VOLUME_INTERNAL)
                || internalDbHelper.isDatabaseRecovering()) {
            return;
        }

        if (!mSetupCompletePublicVolumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            return;
        }

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
            try (Cursor c = db.query(true, "files", QUERY_COLUMNS, null, null, null, null, null,
                    null, signal)) {
                while (c.moveToNext()) {
                    backupDataValues(fuseDaemon, c);
                }
                Log.d(TAG, String.format(Locale.ROOT,
                        "Backed up %d rows of internal database to external storage on idle "
                                + "maintenance.",
                        c.getCount()));
            } catch (Exception e) {
                Log.e(TAG, "Failure in backing up internal database to external storage.", e);
            }
            return null;
        });
    }

    protected synchronized void backupExternalDatabase(DatabaseHelper externalDbHelper,
            String volumeName, CancellationSignal signal) {
        if (!isStableUrisEnabled(volumeName)
                || externalDbHelper.isDatabaseRecovering()) {
            return;
        }

        if (!mSetupCompletePublicVolumes.contains(volumeName)) {
            return;
        }

        FuseDaemon fuseDaemon;
        try {
            fuseDaemon = getFuseDaemonForFileWithWait(new File(EXTERNAL_PRIMARY_ROOT_PATH));
        } catch (FileNotFoundException e) {
            Log.e(TAG,
                    "Fuse Daemon not found for primary external storage, skipping backing up of "
                            + volumeName,
                    e);
            return;
        }

        final String backupPath = RECOVERY_DIRECTORY_PATH + "/" + LEVEL_DB_PREFIX + volumeName;
        long lastBackedGenerationNumber = getLastBackedGenerationNumber(backupPath);

        final String generationClause = MediaStore.Files.FileColumns.GENERATION_MODIFIED + " >= "
                + lastBackedGenerationNumber;
        final String volumeClause = MediaStore.Files.FileColumns.VOLUME_NAME + " = '"
                + volumeName + "'";
        final String selectionClause = generationClause + " AND " + volumeClause;

        externalDbHelper.runWithTransaction((db) -> {
            long maxGeneration = lastBackedGenerationNumber;
            Log.d(TAG, "Started to back up " + volumeName
                    + ", maxGeneration:" + maxGeneration);
            try (Cursor c = db.query(true, "files", QUERY_COLUMNS, selectionClause, null, null,
                    null, MediaStore.MediaColumns._ID + " ASC", null, signal)) {
                while (c.moveToNext()) {
                    if (signal != null && signal.isCanceled()) {
                        Log.i(TAG, "Received a cancellation signal during the DB "
                                + "backup process");
                        break;
                    }
                    backupDataValues(fuseDaemon, c);
                    maxGeneration = Math.max(maxGeneration, c.getLong(9));
                }
                setXattr(backupPath, LAST_BACKEDUP_GENERATION_XATTR_KEY,
                        String.valueOf(maxGeneration - 1));
                Log.d(TAG, String.format(Locale.ROOT,
                        "Backed up %d rows of " + volumeName + " to external storage on idle "
                                + "maintenance.",
                        c.getCount()));
            } catch (Exception e) {
                Log.e(TAG, "Failure in backing up " + volumeName + " to external storage.", e);
                return null;
            }
            return null;
        });
    }

    private void backupDataValues(FuseDaemon fuseDaemon, Cursor c) throws IOException {
        final long id = c.getLong(0);
        final String data = c.getString(1);
        final boolean isFavorite = c.getInt(2) != 0;
        final boolean isPending = c.getInt(3) != 0;
        final boolean isTrashed = c.getInt(4) != 0;
        final int mediaType = c.getInt(5);
        final int userId = c.getInt(6);
        final String dateExpires = c.getString(7);
        final String ownerPackageName = c.getString(8);
        final String volumeName = c.getString(10);
        BackupIdRow backupIdRow = createBackupIdRow(fuseDaemon, id, mediaType,
                isFavorite, isPending, isTrashed, userId, dateExpires,
                ownerPackageName);
        fuseDaemon.backupVolumeDbData(volumeName, data, BackupIdRow.serialize(backupIdRow));
    }

    protected void deleteBackupForVolume(String volumeName) {
        File dbFilePath = new File(
                String.format(Locale.ROOT, "%s/%s.db", RECOVERY_DIRECTORY_PATH, volumeName));
        if (dbFilePath.exists()) {
            dbFilePath.delete();
        }
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

    private long getLastBackedGenerationNumber(String backupPath) {
        // Read last backed up generation number
        Optional<Long> lastBackedUpGenNum = getXattrOfLongValue(
                backupPath, LAST_BACKEDUP_GENERATION_XATTR_KEY);
        long lastBackedGenerationNumber = lastBackedUpGenNum.isPresent()
                ? lastBackedUpGenNum.get() : 0;
        if (lastBackedGenerationNumber > 0) {
            Log.i(TAG, "Last backed up generation number for " + backupPath + " is "
                    + lastBackedGenerationNumber);
        }
        return lastBackedGenerationNumber;
    }

    @NonNull
    private FuseDaemon getFuseDaemonForPath(@NonNull String path)
            throws FileNotFoundException {
        return MediaProvider.getFuseDaemonForFile(new File(path), mVolumeCache);
    }

    protected void updateNextRowIdAndSetDirty(@NonNull DatabaseHelper helper,
            @NonNull FileRow oldRow, @NonNull FileRow newRow) {
        updateNextRowIdXattr(helper, newRow.getId());
        markBackupAsDirty(helper, oldRow);
    }

    /**
     * Backs up DB data in external storage to recover in case of DB rollback.
     */
    protected void backupVolumeDbData(DatabaseHelper databaseHelper, FileRow insertedRow) {
        if (!isBackupUpdateAllowed(databaseHelper, insertedRow.getVolumeName())) {
            return;
        }

        try {
            FuseDaemon fuseDaemon = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
            final BackupIdRow value = createBackupIdRow(fuseDaemon, insertedRow);
            fuseDaemon.backupVolumeDbData(insertedRow.getVolumeName(), insertedRow.getPath(),
                    BackupIdRow.serialize(value));
        } catch (Exception e) {
            Log.e(TAG, "Failure in backing up data to external storage", e);
        }
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
        if (Strings.isNullOrEmpty(ownerPackageName) || ownerPackageName.equalsIgnoreCase("null")) {
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

        int nextOwnerId = getAndIncrementNextOwnerId();
        fuseDaemon.createOwnerIdRelation(String.valueOf(nextOwnerId), ownerPackageIdentifier);
        Log.v(TAG, "Created relation b/w " + nextOwnerId + " and " + ownerPackageIdentifier);
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

    private synchronized int getAndIncrementNextOwnerId() {
        // In synchronized block to avoid use of same owner id for multiple owner package relations
        if (mNextOwnerId == null) {
            Optional<Integer> nextOwnerIdOptional = getXattrOfIntegerValue(
                    OWNER_RELATION_BACKUP_PATH,
                    NEXT_OWNER_ID_XATTR_KEY);
            mNextOwnerId = nextOwnerIdOptional.map(AtomicInteger::new).orElseGet(
                    () -> new AtomicInteger(NEXT_OWNER_ID_DEFAULT_VALUE));
            mNextOwnerIdBackup = new AtomicInteger(mNextOwnerId.get());
        }
        if (mNextOwnerId.get() >= mNextOwnerIdBackup.get()) {
            int nextBackup = mNextOwnerId.get() + NEXT_OWNER_ID_BACKUP_FREQUENCY;
            updateNextOwnerId(nextBackup);
            mNextOwnerIdBackup = new AtomicInteger(nextBackup);
        }
        int returnValue = mNextOwnerId.get();
        mNextOwnerId.set(returnValue + 1);
        return returnValue;
    }

    private void updateNextOwnerId(int val) {
        setXattr(OWNER_RELATION_BACKUP_PATH, NEXT_OWNER_ID_XATTR_KEY, String.valueOf(val));
        Log.d(TAG, "Updated next owner id to: " + val);
    }

    protected void removeOwnerIdToPackageRelation(String packageName, int userId) {
        if (Strings.isNullOrEmpty(packageName) || packageName.equalsIgnoreCase("null")
                || !isStableUrisEnabled(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                || !new File(OWNER_RELATION_BACKUP_PATH).exists()) {
            return;
        }

        try {
            FuseDaemon fuseDaemon = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
            String ownerPackageIdentifier = createOwnerPackageIdentifier(packageName, userId);
            String ownerId = fuseDaemon.readFromOwnershipBackup(ownerPackageIdentifier);

            fuseDaemon.removeOwnerIdRelation(ownerId, ownerPackageIdentifier);
        } catch (Exception e) {
            Log.e(TAG, "Failure in removing owner id to package relation", e);
        }
    }

    /**
     * Deletes backed up data(needed for recovery) from external storage.
     */
    protected void deleteFromDbBackup(DatabaseHelper databaseHelper, FileRow deletedRow) {
        if (!isBackupUpdateAllowed(databaseHelper, deletedRow.getVolumeName())) {
            return;
        }

        String deletedFilePath = deletedRow.getPath();
        if (deletedFilePath == null) {
            return;
        }

        try {
            getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH).deleteDbBackup(
                    deletedFilePath);
        } catch (IOException e) {
            Log.w(TAG, "Failure in deleting backup data for key: " + deletedFilePath, e);
        }
    }

    protected boolean isBackupUpdateAllowed(DatabaseHelper databaseHelper, String volumeName) {
        // Backup only if stable uris is enabled, db is not recovering and backup setup is complete.
        return isStableUrisEnabled(volumeName) && !databaseHelper.isDatabaseRecovering()
                && mSetupCompletePublicVolumes.contains(volumeName);
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

    private void markBackupAsDirty(DatabaseHelper databaseHelper, FileRow updatedRow) {
        if (!isBackupUpdateAllowed(databaseHelper, updatedRow.getVolumeName())) {
            return;
        }

        final String updatedFilePath = updatedRow.getPath();
        try {
            getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH).backupVolumeDbData(
                    updatedRow.getVolumeName(),
                    updatedFilePath,
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
    static Optional<String> getXattr(String path, String key) {
        try {
            return Optional.of(Arrays.toString(Os.getxattr(path, key)));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Reads long value corresponding to given key from xattr on given path.
     */
    static Optional<Long> getXattrOfLongValue(String path, String key) {
        try {
            return Optional.of(Long.parseLong(new String(Os.getxattr(path, key))));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Reads integer value corresponding to given key from xattr on given path.
     */
    static Optional<Integer> getXattrOfIntegerValue(String path, String key) {
        try {
            return Optional.of(Integer.parseInt(new String(Os.getxattr(path, key))));
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Exception encountered while reading xattr:%s from path:%s.", key, path));
            return Optional.empty();
        }
    }

    /**
     * Sets key and value as xattr on given path.
     */
    static boolean setXattr(String path, String key, String value) {
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

    /**
     * Deletes xattr with given key on given path. Becomes a no-op when xattr is not present.
     */
    static boolean removeXattr(String path, String key) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            Os.removexattr(path, key);
            Os.fsync(pfd.getFileDescriptor());
            Log.d(TAG, String.format("xattr key:%s removed on path: %s.", key, path));
            return true;
        } catch (Exception e) {
            if (e instanceof ErrnoException) {
                ErrnoException exception = (ErrnoException) e;
                if (exception.errno == OsConstants.ENODATA) {
                    Log.w(TAG, String.format(Locale.ROOT,
                            "xattr:%s is not removed as it is not found on path: %s.", key, path));
                    return true;
                }
            }

            Log.e(TAG, String.format(Locale.ROOT, "Failed to remove xattr:%s for path: %s.", key,
                    path), e);
            return false;
        }
    }

    /**
     * Lists xattrs of given path.
     */
    static List<String> listXattr(String path) {
        try {
            return Arrays.asList(Os.listxattr(path));
        } catch (Exception e) {
            Log.e(TAG, "Exception in reading xattrs on path: " + path, e);
            return new ArrayList<>();
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

    protected Pair<String, Integer> getOwnerPackageNameAndUidPair(int ownerPackageId) {
        if (sOwnerIdRelationMap == null) {
            try {
                sOwnerIdRelationMap = readOwnerIdRelationsFromLevelDb();
                Log.v(TAG, "Cached owner id map");
            } catch (IOException e) {
                Log.e(TAG, "Failure in reading owner details for owner id:" + ownerPackageId, e);
                return Pair.create(null, null);
            }
        }

        if (sOwnerIdRelationMap.containsKey(String.valueOf(ownerPackageId))) {
            return getPackageNameAndUserId(sOwnerIdRelationMap.get(String.valueOf(ownerPackageId)));
        }

        return Pair.create(null, null);
    }

    protected Map<String, String> readOwnerIdRelationsFromLevelDb() throws IOException {
        return getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH).readOwnerIdRelations();
    }

    protected String readOwnerPackageName(String ownerId) throws IOException {
        Map<String, String> ownerIdRelationMap = readOwnerIdRelationsFromLevelDb();
        if (ownerIdRelationMap.containsKey(String.valueOf(ownerId))) {
            return getPackageNameAndUserId(ownerIdRelationMap.get(ownerId)).first;
        }

        return null;
    }

    protected void recoverData(SQLiteDatabase db, String volumeName) {
        if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)
                && !MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volumeName)) {
            // todo: implement for public volume
            return;
        }
        final long startTime = SystemClock.elapsedRealtime();
        final String fuseFilePath = getFuseFilePathFromVolumeName(volumeName);
        // Wait for external primary to be attached as we use same thread for internal volume.
        // Maximum wait for 10s
        try {
            getFuseDaemonForFileWithWait(new File(fuseFilePath));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Could not recover data as fuse daemon could not serve requests.", e);
            return;
        }

        if (!isBackupPresent()) {
            Log.w(TAG, "Backup is not present for " + volumeName);
            return;
        }
        Log.d(TAG, "Backup is present for " + volumeName);

        long rowsRecovered = 0;
        long dirtyRowsCount = 0;
        String[] backedUpFilePaths;
        String lastReadValue = "";

        while (true) {
            backedUpFilePaths = readBackedUpFilePaths(volumeName, lastReadValue,
                    LEVEL_DB_READ_LIMIT);
            if (backedUpFilePaths.length == 0) {
                break;
            }

            // Reset cached owner id relation map
            sOwnerIdRelationMap = null;
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
                getVolumeNameForStatsLog(volumeName), recoveryTime, rowsRecovered, dirtyRowsCount);
        Log.i(TAG, String.format(Locale.ROOT, "%d rows recovered for volume:%s.", rowsRecovered,
                volumeName));
        if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volumeName)) {
            // Resetting generation number
            setXattr(RECOVERY_DIRECTORY_PATH + "/" + LEVEL_DB_PREFIX
                            + MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    LAST_BACKEDUP_GENERATION_XATTR_KEY, String.valueOf(0));
        }
        Log.i(TAG, String.format(Locale.ROOT, "Recovery time: %d ms", recoveryTime));
    }

    protected boolean isBackupPresent() {
        return new File(RECOVERY_DIRECTORY_PATH).exists();
    }

    protected FuseDaemon getFuseDaemonForFileWithWait(File fuseFilePath)
            throws FileNotFoundException {
        pollForExternalStorageMountedState();
        return MediaProvider.getFuseDaemonForFileWithWait(fuseFilePath, mVolumeCache,
                WAIT_TIME_10_SECONDS_IN_MILLIS);
    }

    private int getVolumeNameForStatsLog(String volumeName) {
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
        return Arrays.asList(new File(RECOVERY_DIRECTORY_PATH).listFiles());
    }

    /**
     * Updates backup in external storage to the latest values. Deletes backup of old file path if
     * file path has changed.
     */
    public void updateBackup(DatabaseHelper helper, FileRow oldRow, FileRow newRow) {
        if (!isBackupUpdateAllowed(helper, newRow.getVolumeName())) {
            return;
        }

        FuseDaemon fuseDaemon;
        try {
            fuseDaemon = getFuseDaemonForPath(EXTERNAL_PRIMARY_ROOT_PATH);
        } catch (FileNotFoundException e) {
            Log.e(TAG,
                    "Fuse Daemon not found for primary external storage, skipping update of "
                            + "backup.",
                    e);
            return;
        }

        helper.runWithTransaction((db) -> {
            try (Cursor c = db.query(true, "files", QUERY_COLUMNS, "_id=?",
                    new String[]{String.valueOf(newRow.getId())}, null, null, null,
                    null, null)) {
                if (c.moveToFirst()) {
                    backupDataValues(fuseDaemon, c);
                    String newPath = c.getString(1);
                    if (oldRow.getPath() != null && !oldRow.getPath().equalsIgnoreCase(newPath)) {
                        // If file path has changed, update leveldb backup to delete old path.
                        deleteFromDbBackup(helper, oldRow);
                        Log.v(TAG, "Deleted backup of old file path: " + oldRow.getPath());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failure in updating row in external storage backup.", e);
            }
            return null;
        });
    }

    /**
     * Removes database recovery data for given user id. This is done when a user is removed.
     */
    protected void removeRecoveryDataForUserId(int removedUserId) {
        String removeduserIdString = String.valueOf(removedUserId);
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.concat(
                        removeduserIdString));
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.concat(
                        removeduserIdString));
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.concat(removeduserIdString));
        removeXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.concat(removeduserIdString));
        Log.v(TAG, "Removed recovery data for user id: " + removedUserId);
    }

    /**
     * Removes database recovery data for obsolete user id. It accepts list of valid/active users
     * and removes the recovery data for ones not present in this list.
     * This is done during an idle maintenance.
     */
    protected void removeRecoveryDataExceptValidUsers(List<String> validUsers) {
        List<String> xattrList = listXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH);
        Log.i(TAG, "Xattr list is " + xattrList);
        if (xattrList.isEmpty()) {
            return;
        }

        Log.i(TAG, "Valid users list is " + validUsers);
        List<String> invalidUsers = getInvalidUsersList(xattrList, validUsers);
        Log.i(TAG, "Invalid users list is " + invalidUsers);
        for (String userIdToBeRemoved : invalidUsers) {
            if (userIdToBeRemoved != null && !userIdToBeRemoved.trim().isEmpty()) {
                removeRecoveryDataForUserId(Integer.parseInt(userIdToBeRemoved));
            }
        }
    }

    protected static List<String> getInvalidUsersList(List<String> recoveryData,
            List<String> validUsers) {
        Set<String> presentUserIdsAsXattr = new HashSet<>();
        for (String xattr : recoveryData) {
            if (xattr.startsWith(INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.length()));
            } else if (xattr.startsWith(EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX.length()));
            } else if (xattr.startsWith(INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(INTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.length()));
            } else if (xattr.startsWith(EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX)) {
                presentUserIdsAsXattr.add(
                        xattr.substring(EXTERNAL_DB_SESSION_ID_XATTR_KEY_PREFIX.length()));
            }
        }
        // Remove valid users
        validUsers.forEach(presentUserIdsAsXattr::remove);
        return presentUserIdsAsXattr.stream().collect(Collectors.toList());
    }

    private static void pollForExternalStorageMountedState() {
        final File target = Environment.getExternalStorageDirectory();
        for (int i = 0; i < WAIT_TIME_10_SECONDS_IN_MILLIS / 100; i++) {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(target))) {
                return;
            }
            Log.v(TAG, "Waiting for external storage...");
            SystemClock.sleep(100);
        }
        throw new RuntimeException("Timed out while waiting for ExternalStorageState "
                + "to be MEDIA_MOUNTED");
    }
}
