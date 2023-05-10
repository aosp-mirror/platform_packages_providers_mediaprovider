/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.providers.media.DatabaseBackupAndRecovery.getXattr;
import static com.android.providers.media.DatabaseBackupAndRecovery.setXattr;
import static com.android.providers.media.util.DatabaseUtils.bindList;
import static com.android.providers.media.util.Logging.LOGV;
import static com.android.providers.media.util.Logging.TAG;

import android.annotation.SuppressLint;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Downloads;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.dao.FileRow;
import com.android.providers.media.playlist.Playlist;
import com.android.providers.media.util.DatabaseUtils;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.Logging;
import com.android.providers.media.util.MimeUtils;

import com.google.common.collect.Iterables;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;

/**
 * Wrapper class for a specific database (associated with one particular
 * external card, or with internal storage).  Can open the actual database
 * on demand, create and upgrade the schema, etc.
 */
public class DatabaseHelper extends SQLiteOpenHelper implements AutoCloseable {
    @VisibleForTesting
    static final String TEST_RECOMPUTE_DB = "test_recompute";
    @VisibleForTesting
    static final String TEST_UPGRADE_DB = "test_upgrade";
    @VisibleForTesting
    static final String TEST_DOWNGRADE_DB = "test_downgrade";
    @VisibleForTesting
    public static final String TEST_CLEAN_DB = "test_clean";

    /**
     * Key name of xattr used to set next row id for internal DB.
     */
    private static final String INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY = "user.intdbnextrowid".concat(
            String.valueOf(UserHandle.myUserId()));

    /**
     * Key name of xattr used to set next row id for external DB.
     */
    private static final String EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY = "user.extdbnextrowid".concat(
            String.valueOf(UserHandle.myUserId()));

    /**
     * Key name of xattr used to set session id for internal DB.
     */
    private static final String INTERNAL_DB_SESSION_ID_XATTR_KEY = "user.intdbsessionid".concat(
            String.valueOf(UserHandle.myUserId()));

    /**
     * Key name of xattr used to set session id for external DB.
     */
    private static final String EXTERNAL_DB_SESSION_ID_XATTR_KEY = "user.extdbsessionid".concat(
            String.valueOf(UserHandle.myUserId()));

    /** Indicates a billion value used when next row id is not present in respective xattr. */
    private static final Long NEXT_ROW_ID_DEFAULT_BILLION_VALUE = Double.valueOf(
            Math.pow(10, 9)).longValue();

    private static final Long INVALID_ROW_ID = -1L;

    /**
     * Path used for setting next row id and database session id for each user profile. Storing here
     * because media provider does not have required permission on path /data/media/<user-id> for
     * work profiles.
     * For devices with adoptable storage support, opting for adoptable storage will not delete
     * /data/media/0 directory.
     */
    private static final String DATA_MEDIA_XATTR_DIRECTORY_PATH = "/data/media/0";

    static final String INTERNAL_DATABASE_NAME = "internal.db";
    static final String EXTERNAL_DATABASE_NAME = "external.db";

    /**
     * Raw SQL clause that can be used to obtain the current generation, which
     * is designed to be populated into {@link MediaColumns#GENERATION_ADDED} or
     * {@link MediaColumns#GENERATION_MODIFIED}.
     */
    public static final String CURRENT_GENERATION_CLAUSE = "SELECT generation FROM local_metadata";

    private static final int NOTIFY_BATCH_SIZE = 256;

    final Context mContext;
    final String mName;
    final int mVersion;
    final String mVolumeName;
    final boolean mEarlyUpgrade;
    final boolean mLegacyProvider;
    private final ProjectionHelper mProjectionHelper;
    final @Nullable OnSchemaChangeListener mSchemaListener;
    final @Nullable OnFilesChangeListener mFilesListener;
    final @Nullable OnLegacyMigrationListener mMigrationListener;
    final @Nullable UnaryOperator<String> mIdGenerator;
    final Set<String> mFilterVolumeNames = new ArraySet<>();
    private final String mMigrationFileName;
    long mScanStartTime;
    long mScanStopTime;
    private boolean mEnableNextRowIdRecovery;
    private final DatabaseBackupAndRecovery mDatabaseBackupAndRecovery;

    /**
     * Unfortunately we can have multiple instances of DatabaseHelper, causing
     * onUpgrade() to be called multiple times if those instances happen to run in
     * parallel. To prevent that, keep track of which databases we've already upgraded.
     *
     */
    static final Set<String> sDatabaseUpgraded = new HashSet<>();
    static final Object sLock = new Object();
    /**
     * Lock used to guard against deadlocks in SQLite; the write lock is used to
     * guard any schema changes, and the read lock is used for all other
     * database operations.
     * <p>
     * As a concrete example: consider the case where the primary database
     * connection is performing a schema change inside a transaction, while a
     * secondary connection is waiting to begin a transaction. When the primary
     * database connection changes the schema, it attempts to close all other
     * database connections, which then deadlocks.
     */
    private final ReentrantReadWriteLock mSchemaLock = new ReentrantReadWriteLock();

    private static Object sMigrationLockInternal = new Object();
    private static Object sMigrationLockExternal = new Object();

    /**
     * Object used to synchronise sequence of next row id in database.
     */
    private static final Object sRecoveryLock = new Object();

    /** Stores cached value of next row id of the database which optimises new id inserts. */
    private AtomicLong mNextRowIdBackup = new AtomicLong(INVALID_ROW_ID);

    /** Indicates whether the database is recovering from a rollback or not. */
    private AtomicBoolean mIsRecovering =  new AtomicBoolean(false);

    public interface OnSchemaChangeListener {
        void onSchemaChange(@NonNull String volumeName, int versionFrom, int versionTo,
                long itemCount, long durationMillis, String databaseUuid);
    }

    public interface OnFilesChangeListener {
        void onInsert(@NonNull DatabaseHelper helper, @NonNull FileRow insertedRow);

        void onUpdate(@NonNull DatabaseHelper helper, @NonNull FileRow oldRow,
                @NonNull FileRow newRow);

        /** Method invoked on database row delete. */
        void onDelete(@NonNull DatabaseHelper helper, @NonNull FileRow deletedRow);
    }

    public interface OnLegacyMigrationListener {
        void onStarted(ContentProviderClient client, String volumeName);

        void onProgress(ContentProviderClient client, String volumeName,
                long progress, long total);

        void onFinished(ContentProviderClient client, String volumeName);
    }

    public DatabaseHelper(Context context, String name,
            boolean earlyUpgrade, boolean legacyProvider,
            ProjectionHelper projectionHelper,
            @Nullable OnSchemaChangeListener schemaListener,
            @Nullable OnFilesChangeListener filesListener,
            @NonNull OnLegacyMigrationListener migrationListener,
            @Nullable UnaryOperator<String> idGenerator, boolean enableNextRowIdRecovery,
            DatabaseBackupAndRecovery databaseBackupAndRecovery) {
        this(context, name, getDatabaseVersion(context), earlyUpgrade, legacyProvider,
                projectionHelper, schemaListener, filesListener,
                migrationListener, idGenerator, enableNextRowIdRecovery, databaseBackupAndRecovery);
    }

    public DatabaseHelper(Context context, String name, int version,
            boolean earlyUpgrade, boolean legacyProvider,
            ProjectionHelper projectionHelper,
            @Nullable OnSchemaChangeListener schemaListener,
            @Nullable OnFilesChangeListener filesListener,
            @NonNull OnLegacyMigrationListener migrationListener,
            @Nullable UnaryOperator<String> idGenerator, boolean enableNextRowIdRecovery,
            DatabaseBackupAndRecovery databaseBackupAndRecovery) {
        super(context, name, null, version);
        mContext = context;
        mName = name;
        mVersion = version;
        if (isInternal()) {
            mVolumeName = MediaStore.VOLUME_INTERNAL;
        } else if (isExternal()) {
            mVolumeName = MediaStore.VOLUME_EXTERNAL;
        } else {
            throw new IllegalStateException("Db must be internal/external");
        }
        mEarlyUpgrade = earlyUpgrade;
        mLegacyProvider = legacyProvider;
        mProjectionHelper = projectionHelper;
        mSchemaListener = schemaListener;
        mFilesListener = filesListener;
        mMigrationListener = migrationListener;
        mIdGenerator = idGenerator;
        mMigrationFileName = "." + mVolumeName;
        this.mEnableNextRowIdRecovery = enableNextRowIdRecovery;
        this.mDatabaseBackupAndRecovery = databaseBackupAndRecovery;

        // Configure default filters until we hear differently
        if (isInternal()) {
            mFilterVolumeNames.add(MediaStore.VOLUME_INTERNAL);
        } else if (isExternal()) {
            mFilterVolumeNames.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }

        setWriteAheadLoggingEnabled(true);
    }

    /**
     * Configure the set of {@link MediaColumns#VOLUME_NAME} that we should use
     * for filtering query results.
     * <p>
     * This is typically set to the list of storage volumes which are currently
     * mounted, so that we don't leak cached indexed metadata from volumes which
     * are currently ejected.
     */
    public void setFilterVolumeNames(@NonNull Set<String> filterVolumeNames) {
        synchronized (mFilterVolumeNames) {
            // Skip update if identical, to help avoid database churn
            if (mFilterVolumeNames.equals(filterVolumeNames)) {
                return;
            }

            mFilterVolumeNames.clear();
            mFilterVolumeNames.addAll(filterVolumeNames);
        }

        // Recreate all views to apply this filter
        final SQLiteDatabase db = super.getWritableDatabase();
        mSchemaLock.writeLock().lock();
        try {
            db.beginTransaction();
            createLatestViews(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            mSchemaLock.writeLock().unlock();
        }
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        throw new UnsupportedOperationException("All database operations must be routed through"
                + " runWithTransaction() or runWithoutTransaction() to avoid deadlocks");
    }

    @Override
    public SQLiteDatabase getWritableDatabase() {
        throw new UnsupportedOperationException("All database operations must be routed through"
                + " runWithTransaction() or runWithoutTransaction() to avoid deadlocks");
    }

    @VisibleForTesting
    SQLiteDatabase getWritableDatabaseForTest() {
        // Tests rely on creating multiple instances of DatabaseHelper to test upgrade
        // scenarios; so clear this state before returning databases to test.
        synchronized (sLock) {
            sDatabaseUpgraded.clear();
        }
        return super.getWritableDatabase();
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        Log.v(TAG, "onConfigure() for " + mName);

        if (isExternal()) {
            db.setForeignKeyConstraintsEnabled(true);
        }

        db.setCustomScalarFunction("_INSERT", (arg) -> {
            if (arg != null && mFilesListener != null
                    && !mSchemaLock.isWriteLockedByCurrentThread()) {
                final String[] split = arg.split(":", 11);
                final String volumeName = split[0];
                final long id = Long.parseLong(split[1]);
                final int mediaType = Integer.parseInt(split[2]);
                final boolean isDownload = Integer.parseInt(split[3]) != 0;
                final boolean isPending = Integer.parseInt(split[4]) != 0;
                final boolean isTrashed = Integer.parseInt(split[5]) != 0;
                final boolean isFavorite = Integer.parseInt(split[6]) != 0;
                final int userId = Integer.parseInt(split[7]);
                final String dateExpires = split[8];
                final String ownerPackageName = split[9];
                final String path = split[10];

                FileRow insertedRow = FileRow.newBuilder(id)
                        .setVolumeName(volumeName)
                        .setMediaType(mediaType)
                        .setIsDownload(isDownload)
                        .setIsPending(isPending)
                        .setIsTrashed(isTrashed)
                        .setIsFavorite(isFavorite)
                        .setUserId(userId)
                        .setDateExpires(dateExpires)
                        .setOwnerPackageName(ownerPackageName)
                        .setPath(path)
                        .build();
                Trace.beginSection(traceSectionName("_INSERT"));
                try {
                    mFilesListener.onInsert(DatabaseHelper.this, insertedRow);
                } finally {
                    Trace.endSection();
                }
            }
            return null;
        });
        db.setCustomScalarFunction("_UPDATE", (arg) -> {
            if (arg != null && mFilesListener != null
                    && !mSchemaLock.isWriteLockedByCurrentThread()) {
                final String[] split = arg.split(":", 22);
                final String volumeName = split[0];
                final long oldId = Long.parseLong(split[1]);
                final int oldMediaType = Integer.parseInt(split[2]);
                final boolean oldIsDownload = Integer.parseInt(split[3]) != 0;
                final long newId = Long.parseLong(split[4]);
                final int newMediaType = Integer.parseInt(split[5]);
                final boolean newIsDownload = Integer.parseInt(split[6]) != 0;
                final boolean oldIsTrashed = Integer.parseInt(split[7]) != 0;
                final boolean newIsTrashed = Integer.parseInt(split[8]) != 0;
                final boolean oldIsPending = Integer.parseInt(split[9]) != 0;
                final boolean newIsPending = Integer.parseInt(split[10]) != 0;
                final boolean oldIsFavorite = Integer.parseInt(split[11]) != 0;
                final boolean newIsFavorite = Integer.parseInt(split[12]) != 0;
                final int oldSpecialFormat = Integer.parseInt(split[13]);
                final int newSpecialFormat = Integer.parseInt(split[14]);
                final String oldOwnerPackage = split[15];
                final String newOwnerPackage = split[16];
                final int oldUserId = Integer.parseInt(split[17]);
                final int newUserId = Integer.parseInt(split[18]);
                final String oldDateExpires = split[19];
                final String newDateExpires = split[20];
                final String oldPath = split[21];

                FileRow oldRow = FileRow.newBuilder(oldId)
                        .setVolumeName(volumeName)
                        .setMediaType(oldMediaType)
                        .setIsDownload(oldIsDownload)
                        .setIsTrashed(oldIsTrashed)
                        .setIsPending(oldIsPending)
                        .setIsFavorite(oldIsFavorite)
                        .setSpecialFormat(oldSpecialFormat)
                        .setOwnerPackageName(oldOwnerPackage)
                        .setUserId(oldUserId)
                        .setDateExpires(oldDateExpires)
                        .setPath(oldPath)
                        .build();
                FileRow newRow = FileRow.newBuilder(newId)
                        .setVolumeName(volumeName)
                        .setMediaType(newMediaType)
                        .setIsDownload(newIsDownload)
                        .setIsTrashed(newIsTrashed)
                        .setIsPending(newIsPending)
                        .setIsFavorite(newIsFavorite)
                        .setSpecialFormat(newSpecialFormat)
                        .setOwnerPackageName(newOwnerPackage)
                        .setUserId(newUserId)
                        .setDateExpires(newDateExpires)
                        .build();

                Trace.beginSection(traceSectionName("_UPDATE"));
                try {
                    mFilesListener.onUpdate(DatabaseHelper.this, oldRow, newRow);
                } finally {
                    Trace.endSection();
                }
            }
            return null;
        });
        db.setCustomScalarFunction("_DELETE", (arg) -> {
            if (arg != null && mFilesListener != null
                    && !mSchemaLock.isWriteLockedByCurrentThread()) {
                final String[] split = arg.split(":", 6);
                final String volumeName = split[0];
                final long id = Long.parseLong(split[1]);
                final int mediaType = Integer.parseInt(split[2]);
                final boolean isDownload = Integer.parseInt(split[3]) != 0;
                final String ownerPackage = split[4];
                final String path = split[5];

                FileRow deletedRow = FileRow.newBuilder(id)
                        .setVolumeName(volumeName)
                        .setMediaType(mediaType)
                        .setIsDownload(isDownload)
                        .setOwnerPackageName(ownerPackage)
                        .setPath(path)
                        .build();
                Trace.beginSection(traceSectionName("_DELETE"));
                try {
                    mFilesListener.onDelete(DatabaseHelper.this, deletedRow);
                } finally {
                    Trace.endSection();
                }
            }
            return null;
        });
        db.setCustomScalarFunction("_GET_ID", (arg) -> {
            if (mIdGenerator != null && !mSchemaLock.isWriteLockedByCurrentThread()) {
                Trace.beginSection(traceSectionName("_GET_ID"));
                try {
                    return mIdGenerator.apply(arg);
                } finally {
                    Trace.endSection();
                }
            }
            return null;
        });
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        Log.v(TAG, "onCreate() for " + mName);
        mSchemaLock.writeLock().lock();
        try {
            updateDatabase(db, 0, mVersion);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onUpgrade() for " + mName + " from " + oldV + " to " + newV);
        mSchemaLock.writeLock().lock();
        try {
            synchronized (sLock) {
                if (sDatabaseUpgraded.contains(mName)) {
                    Log.v(TAG, "Skipping onUpgrade() for " + mName +
                            " because it was already upgraded.");
                    return;
                } else {
                    sDatabaseUpgraded.add(mName);
                }
            }
            updateDatabase(db, oldV, newV);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onDowngrade() for " + mName + " from " + oldV + " to " + newV);
        mSchemaLock.writeLock().lock();
        try {
            downgradeDatabase(db, oldV, newV);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
        // In case of a bad MP release which decreases the DB version, we would end up downgrading
        // database. We are explicitly setting a new session id on database to trigger recovery
        // in onOpen() call.
        setXattr(db.getPath(), getSessionIdXattrKeyForDatabase(), UUID.randomUUID().toString());
    }

    @Override
    public void onOpen(final SQLiteDatabase db) {
        Log.v(TAG, "onOpen() for " + mName);
        // Recovering before migration from legacy because recovery process will clear up data to
        // read from xattrs once ids are persisted in xattrs.
        tryRecoverDatabase(db);
        tryRecoverRowIdSequence(db);
        tryMigrateFromLegacy(db);
    }

    private void tryRecoverDatabase(SQLiteDatabase db) {
        String volumeName =
                isInternal() ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL_PRIMARY;
        if (!mDatabaseBackupAndRecovery.isStableUrisEnabled(volumeName)) {
            return;
        }

        synchronized (sRecoveryLock) {
            // Read last used session id from /data/media/0.
            Optional<String> lastUsedSessionIdFromExternalStoragePathXattr = getXattr(
                    getExternalStorageDbXattrPath(), getSessionIdXattrKeyForDatabase());
            if (!lastUsedSessionIdFromExternalStoragePathXattr.isPresent()) {
                // First time scenario will have no session id at /data/media/0.
                // Trigger database backup to external storage because
                // StableUrisIdleMaintenanceService will be attempted to run only once in 7days.
                // Any rollback before that will not recover DB rows.
                if (isInternal()) {
                    BackgroundThread.getExecutor().execute(
                            () -> mDatabaseBackupAndRecovery.backupInternalDatabase(this, null));
                }
                // Set next row id in External Storage to handle rollback in future.
                backupNextRowId(NEXT_ROW_ID_DEFAULT_BILLION_VALUE);
                updateSessionIdInDatabaseAndExternalStorage(db);
                return;
            }

            Optional<Long> nextRowIdFromXattrOptional = getNextRowIdFromXattr();
            // Check if session is same as last used.
            if (isLastUsedDatabaseSession(db) && nextRowIdFromXattrOptional.isPresent()) {
                // Same session id present as xattr on DB and External Storage
                Log.i(TAG, String.format(Locale.ROOT,
                        "No database change across sequential open calls for %s.", mName));
                mNextRowIdBackup.set(nextRowIdFromXattrOptional.get());
                updateSessionIdInDatabaseAndExternalStorage(db);
                return;
            }

            Log.w(TAG, String.format(Locale.ROOT, "%s database inconsistency identified.", mName));
            // Delete old data and create new schema.
            recreateLatestSchema(db);
            // Recover data from backup
            // Ensure we do not back up in case of recovery.
            mIsRecovering.set(true);
            mDatabaseBackupAndRecovery.recoverData(db, volumeName);
            updateNextRowIdInDatabaseAndExternalStorage(db);
            mIsRecovering.set(false);
            updateSessionIdInDatabaseAndExternalStorage(db);
        }
    }

    protected String getExternalStorageDbXattrPath() {
        return DATA_MEDIA_XATTR_DIRECTORY_PATH;
    }

    @GuardedBy("sRecoveryLock")
    private void recreateLatestSchema(SQLiteDatabase db) {
        mSchemaLock.writeLock().lock();
        try {
            createLatestSchema(db);
        } finally {
            mSchemaLock.writeLock().unlock();
        }
    }

    private void tryRecoverRowIdSequence(SQLiteDatabase db) {
        if (isInternal()) {
            // Database row id recovery for internal is handled in tryRecoverDatabase()
            return;
        }

        if (!isNextRowIdBackupEnabled()) {
            Log.d(TAG, "Skipping row id recovery as backup is not enabled.");
            return;
        }

        if (mDatabaseBackupAndRecovery.isStableUrisEnabled(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            // Row id change would have been taken care by tryRecoverDatabase method
            return;
        }

        synchronized (sRecoveryLock) {
            boolean isLastUsedDatabaseSession = isLastUsedDatabaseSession(db);
            Optional<Long> nextRowIdFromXattrOptional = getNextRowIdFromXattr();
            if (isLastUsedDatabaseSession && nextRowIdFromXattrOptional.isPresent()) {
                Log.i(TAG, String.format(Locale.ROOT,
                        "No database change across sequential open calls for %s.", mName));
                mNextRowIdBackup.set(nextRowIdFromXattrOptional.get());
                updateSessionIdInDatabaseAndExternalStorage(db);
                return;
            }

            Log.w(TAG, String.format(Locale.ROOT,
                    "%s database inconsistent: isLastUsedDatabaseSession:%b, "
                            + "nextRowIdOptionalPresent:%b", mName, isLastUsedDatabaseSession,
                    nextRowIdFromXattrOptional.isPresent()));
            // TODO(b/222313219): Add an assert to ensure that next row id xattr is always
            // present when DB session id matches across sequential open calls.
            updateNextRowIdInDatabaseAndExternalStorage(db);
            updateSessionIdInDatabaseAndExternalStorage(db);
        }
    }

    @GuardedBy("sRecoveryLock")
    private boolean isLastUsedDatabaseSession(SQLiteDatabase db) {
        Optional<String> lastUsedSessionIdFromDatabasePathXattr = getXattr(db.getPath(),
                getSessionIdXattrKeyForDatabase());
        Optional<String> lastUsedSessionIdFromExternalStoragePathXattr = getXattr(
                getExternalStorageDbXattrPath(), getSessionIdXattrKeyForDatabase());

        return lastUsedSessionIdFromDatabasePathXattr.isPresent()
                && lastUsedSessionIdFromExternalStoragePathXattr.isPresent()
                && lastUsedSessionIdFromDatabasePathXattr.get().equals(
                lastUsedSessionIdFromExternalStoragePathXattr.get());
    }

    @GuardedBy("sRecoveryLock")
    private void updateSessionIdInDatabaseAndExternalStorage(SQLiteDatabase db) {
        final String uuid = UUID.randomUUID().toString();
        boolean setOnDatabase = setXattr(db.getPath(), getSessionIdXattrKeyForDatabase(), uuid);
        boolean setOnExternalStorage = setXattr(getExternalStorageDbXattrPath(),
                getSessionIdXattrKeyForDatabase(), uuid);
        if (setOnDatabase && setOnExternalStorage) {
            Log.i(TAG, String.format(Locale.ROOT, "SessionId set to %s on paths %s and %s.", uuid,
                    db.getPath(), getExternalStorageDbXattrPath()));
        }
    }

    private void tryMigrateFromLegacy(SQLiteDatabase db) {
        final Object migrationLock;
        if (isInternal()) {
            migrationLock = sMigrationLockInternal;
        } else if (isExternal()) {
            migrationLock = sMigrationLockExternal;
        } else {
            throw new IllegalStateException("Db migration only supported for internal/external db");
        }

        final File migration = new File(mContext.getFilesDir(), mMigrationFileName);
        // Another thread entering migration block will be blocked until the
        // migration is complete from current thread.
        synchronized (migrationLock) {
            if (!migration.exists()) {
                Log.v(TAG, "onOpen() finished for " + mName);
                return;
            }

            mSchemaLock.writeLock().lock();
            try {
                // Temporarily drop indexes to improve migration performance
                makePristineIndexes(db);
                migrateFromLegacy(db);
                createLatestIndexes(db);
            } finally {
                mSchemaLock.writeLock().unlock();
                // Clear flag, since we should only attempt once
                migration.delete();
                Log.v(TAG, "onOpen() finished for " + mName);
            }
        }
    }

   /**
     * Local state related to any transaction currently active on a specific
     * thread, such as collecting the set of {@link Uri} that should be notified
     * upon transaction success.
     * <p>
     * We suppress Error Prone here because there are multiple
     * {@link DatabaseHelper} instances within the process, and state needs to
     * be tracked uniquely per-helper.
     */
    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<TransactionState> mTransactionState = new ThreadLocal<>();

    private static class TransactionState {
        /**
         * Flag indicating if this transaction has been marked as being
         * successful.
         */
        public boolean successful;

        /**
         * List of tasks that should be executed in a blocking fashion when this
         * transaction has been successfully finished.
         */
        public final ArrayList<Runnable> blockingTasks = new ArrayList<>();

        /**
         * Map from {@code flags} value to set of {@link Uri} that would have
         * been sent directly via {@link ContentResolver#notifyChange}, but are
         * instead being collected due to this ongoing transaction.
         */
        public final SparseArray<ArraySet<Uri>> notifyChanges = new SparseArray<>();

        /**
         * List of tasks that should be enqueued onto {@link BackgroundThread}
         * after any {@link #notifyChanges} have been dispatched. We keep this
         * as a separate pass to ensure that we don't risk running in parallel
         * with other more important tasks.
         */
        public final ArrayList<Runnable> backgroundTasks = new ArrayList<>();
    }

    public boolean isTransactionActive() {
        return (mTransactionState.get() != null);
    }

    public void beginTransaction() {
        Trace.beginSection(traceSectionName("transaction"));
        Trace.beginSection(traceSectionName("beginTransaction"));
        try {
            beginTransactionInternal();
        } finally {
            // Only end the "beginTransaction" section. We'll end the "transaction" section in
            // endTransaction().
            Trace.endSection();
        }
    }

    private void beginTransactionInternal() {
        if (mTransactionState.get() != null) {
            throw new IllegalStateException("Nested transactions not supported");
        }
        mTransactionState.set(new TransactionState());

        final SQLiteDatabase db = super.getWritableDatabase();
        mSchemaLock.readLock().lock();
        db.beginTransaction();
        db.execSQL("UPDATE local_metadata SET generation=generation+1;");
    }

    public void setTransactionSuccessful() {
        final TransactionState state = mTransactionState.get();
        if (state == null) {
            throw new IllegalStateException("No transaction in progress");
        }
        state.successful = true;

        final SQLiteDatabase db = super.getWritableDatabase();
        db.setTransactionSuccessful();
    }

    public void endTransaction() {
        Trace.beginSection(traceSectionName("endTransaction"));
        try {
            endTransactionInternal();
        } finally {
            Trace.endSection();
            // End "transaction" section, which we started in beginTransaction().
            Trace.endSection();
        }
    }

    private void endTransactionInternal() {
        final TransactionState state = mTransactionState.get();
        if (state == null) {
            throw new IllegalStateException("No transaction in progress");
        }
        mTransactionState.remove();

        final SQLiteDatabase db = super.getWritableDatabase();
        db.endTransaction();
        mSchemaLock.readLock().unlock();

        if (state.successful) {
            for (int i = 0; i < state.blockingTasks.size(); i++) {
                state.blockingTasks.get(i).run();
            }
            // We carefully "phase" our two sets of work here to ensure that we
            // completely finish dispatching all change notifications before we
            // process background tasks, to ensure that the background work
            // doesn't steal resources from the more important foreground work
            ForegroundThread.getExecutor().execute(() -> {
                for (int i = 0; i < state.notifyChanges.size(); i++) {
                    notifyChangeInternal(state.notifyChanges.valueAt(i),
                            state.notifyChanges.keyAt(i));
                }

                // Now that we've finished with all our important work, we can
                // finally kick off any internal background tasks
                for (int i = 0; i < state.backgroundTasks.size(); i++) {
                    BackgroundThread.getExecutor().execute(state.backgroundTasks.get(i));
                }
            });
        }
    }

    /**
     * Execute the given operation inside a transaction. If the calling thread
     * is not already in an active transaction, this method will wrap the given
     * runnable inside a new transaction.
     */
    public @NonNull <T> T runWithTransaction(@NonNull Function<SQLiteDatabase, T> op) {
        // We carefully acquire the database here so that any schema changes can
        // be applied before acquiring the read lock below
        final SQLiteDatabase db = super.getWritableDatabase();

        if (mTransactionState.get() != null) {
            // Already inside a transaction, so we can run directly
            return op.apply(db);
        } else {
            // Not inside a transaction, so we need to make one
            beginTransaction();
            try {
                final T res = op.apply(db);
                setTransactionSuccessful();
                return res;
            } finally {
                endTransaction();
            }
        }
    }

    /**
     * Execute the given operation regardless of the calling thread being in an
     * active transaction or not.
     */
    public @NonNull <T> T runWithoutTransaction(@NonNull Function<SQLiteDatabase, T> op) {
        // We carefully acquire the database here so that any schema changes can
        // be applied before acquiring the read lock below
        final SQLiteDatabase db = super.getWritableDatabase();

        if (mTransactionState.get() != null) {
            // Already inside a transaction, so we can run directly
            return op.apply(db);
        } else {
            // We still need to acquire a schema read lock
            mSchemaLock.readLock().lock();
            try {
                return op.apply(db);
            } finally {
                mSchemaLock.readLock().unlock();
            }
        }
    }

    public void notifyInsert(@NonNull Uri uri) {
        notifyChange(uri, ContentResolver.NOTIFY_INSERT);
    }

    public void notifyUpdate(@NonNull Uri uri) {
        notifyChange(uri, ContentResolver.NOTIFY_UPDATE);
    }

    public void notifyDelete(@NonNull Uri uri) {
        notifyChange(uri, ContentResolver.NOTIFY_DELETE);
    }

    /**
     * Notify that the given {@link Uri} has changed. This enqueues the
     * notification if currently inside a transaction, and they'll be
     * clustered and sent when the transaction completes.
     */
    public void notifyChange(@NonNull Uri uri, int flags) {
        if (LOGV) Log.v(TAG, "Notifying " + uri);

        // Also sync change to the network.
        final int notifyFlags = flags | ContentResolver.NOTIFY_SYNC_TO_NETWORK;

        final TransactionState state = mTransactionState.get();
        if (state != null) {
            ArraySet<Uri> set = state.notifyChanges.get(notifyFlags);
            if (set == null) {
                set = new ArraySet<>();
                state.notifyChanges.put(notifyFlags, set);
            }
            set.add(uri);
        } else {
            ForegroundThread.getExecutor().execute(() -> {
                notifySingleChangeInternal(uri, notifyFlags);
            });
        }
    }

    private void notifySingleChangeInternal(@NonNull Uri uri, int flags) {
        Trace.beginSection(traceSectionName("notifySingleChange"));
        try {
            mContext.getContentResolver().notifyChange(uri, null, flags);
        } finally {
            Trace.endSection();
        }
    }

    private void notifyChangeInternal(@NonNull Collection<Uri> uris, int flags) {
        Trace.beginSection(traceSectionName("notifyChange"));
        try {
            for (List<Uri> partition : Iterables.partition(uris, NOTIFY_BATCH_SIZE)) {
                mContext.getContentResolver().notifyChange(partition, null, flags);
            }
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Post the given task to be run in a blocking fashion after any current
     * transaction has finished. If there is no active transaction, the task is
     * immediately executed.
     */
    public void postBlocking(@NonNull Runnable command) {
        final TransactionState state = mTransactionState.get();
        if (state != null) {
            state.blockingTasks.add(command);
        } else {
            command.run();
        }
    }

    /**
     * Post the given task to be run in background after any current transaction
     * has finished. If there is no active transaction, the task is immediately
     * dispatched to run in the background.
     */
    public void postBackground(@NonNull Runnable command) {
        final TransactionState state = mTransactionState.get();
        if (state != null) {
            state.backgroundTasks.add(command);
        } else {
            BackgroundThread.getExecutor().execute(command);
        }
    }

    /**
     * This method cleans up any files created by android.media.MiniThumbFile, removed after P.
     * It's triggered during database update only, in order to run only once.
     */
    private static void deleteLegacyThumbnailData() {
        File directory = new File(Environment.getExternalStorageDirectory(), "/DCIM/.thumbnails");

        final FilenameFilter filter = (dir, filename) -> filename.startsWith(".thumbdata");
        final File[] files = directory.listFiles(filter);
        for (File f : (files != null) ? files : new File[0]) {
            if (!f.delete()) {
                Log.e(TAG, "Failed to delete legacy thumbnail data " + f.getAbsolutePath());
            }
        }
    }

    @Deprecated
    public static int getDatabaseVersion(Context context) {
        // We now use static versions defined internally instead of the
        // versionCode from the manifest
        return VERSION_LATEST;
    }

    @VisibleForTesting
    static void makePristineSchema(SQLiteDatabase db) {
        // We are dropping all tables and recreating new schema. This
        // is a clear indication of major change in MediaStore version.
        // Hence reset the Uuid whenever we change the schema.
        resetAndGetUuid(db);

        // drop all triggers
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'trigger'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TRIGGER IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all views
        c = db.query("sqlite_master", new String[] {"name"}, "type is 'view'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP VIEW IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all indexes
        c = db.query("sqlite_master", new String[] {"name"}, "type is 'index'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all tables
        c = db.query("sqlite_master", new String[] {"name"}, "type is 'table'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TABLE IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createLatestSchema(SQLiteDatabase db) {
        // We're about to start all ID numbering from scratch, so revoke any
        // outstanding permission grants to ensure we don't leak data
        try {
            final PackageInfo pkg = mContext.getPackageManager().getPackageInfo(
                    mContext.getPackageName(), PackageManager.GET_PROVIDERS);
            if (pkg != null && pkg.providers != null) {
                for (ProviderInfo provider : pkg.providers) {
                    mContext.revokeUriPermission(Uri.parse("content://" + provider.authority),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to revoke permissions", e);
        }

        makePristineSchema(db);

        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,"
                + "_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,"
                + "is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,"
                + "referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,"
                + "date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,"
                + "group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,"
                + "secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,"
                + "instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,"
                + "relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL,"
                + "artist_key TEXT DEFAULT NULL,album_key TEXT DEFAULT NULL,"
                + "genre TEXT DEFAULT NULL,genre_key TEXT DEFAULT NULL,genre_id INTEGER,"
                + "author TEXT DEFAULT NULL, bitrate INTEGER DEFAULT NULL,"
                + "capture_framerate REAL DEFAULT NULL, cd_track_number TEXT DEFAULT NULL,"
                + "compilation INTEGER DEFAULT NULL, disc_number TEXT DEFAULT NULL,"
                + "is_favorite INTEGER DEFAULT 0, num_tracks INTEGER DEFAULT NULL,"
                + "writer TEXT DEFAULT NULL, exposure_time TEXT DEFAULT NULL,"
                + "f_number TEXT DEFAULT NULL, iso INTEGER DEFAULT NULL,"
                + "scene_capture_type INTEGER DEFAULT NULL, generation_added INTEGER DEFAULT 0,"
                + "generation_modified INTEGER DEFAULT 0, xmp BLOB DEFAULT NULL,"
                + "_transcode_status INTEGER DEFAULT 0, _video_codec_type TEXT DEFAULT NULL,"
                + "_modifier INTEGER DEFAULT 0, is_recording INTEGER DEFAULT 0,"
                + "redacted_uri_id TEXT DEFAULT NULL, _user_id INTEGER DEFAULT "
                + UserHandle.myUserId() + ", _special_format INTEGER DEFAULT NULL)");
        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        db.execSQL("CREATE TABLE deleted_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "old_id INTEGER UNIQUE, generation_modified INTEGER NOT NULL)");

        if (isExternal()) {
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
            updateAddMediaGrantsTable(db);
        }

        createLatestViews(db);
        createLatestTriggers(db);
        createLatestIndexes(db);

        // Since this code is used by both the legacy and modern providers, we
        // only want to migrate when we're running as the modern provider
        if (!mLegacyProvider) {
            try {
                new File(mContext.getFilesDir(), mMigrationFileName).createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create a migration file: ." + mVolumeName, e);
            }
        }
    }

    /**
     * Migrate important information from {@link MediaStore#AUTHORITY_LEGACY},
     * if present on this device. We only do this once during early database
     * creation, to help us preserve information like {@link MediaColumns#_ID}
     * and {@link MediaColumns#IS_FAVORITE}.
     */
    private void migrateFromLegacy(SQLiteDatabase db) {
        // TODO: focus this migration on secondary volumes once we have separate
        // databases for each volume; for now only migrate primary storage

        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY_LEGACY)) {
            if (client == null) {
                Log.d(TAG, "No legacy provider available for migration");
                return;
            }

            final Uri queryUri = MediaStore
                    .rewriteToLegacy(MediaStore.Files.getContentUri(mVolumeName));

            final Bundle extras = new Bundle();
            extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE);

            db.beginTransaction();
            Log.d(TAG, "Starting migration from legacy provider");
            if (mMigrationListener != null) {
                mMigrationListener.onStarted(client, mVolumeName);
            }
            try (Cursor c = client.query(queryUri, sMigrateColumns.toArray(new String[0]),
                    extras, null)) {
                final ContentValues values = new ContentValues();
                while (c.moveToNext()) {
                    values.clear();

                    // Start by deriving all values from migrated data column,
                    // then overwrite with other migrated columns
                    final String data = c.getString(c.getColumnIndex(MediaColumns.DATA));
                    values.put(MediaColumns.DATA, data);
                    FileUtils.computeValuesFromData(values, /*isForFuse*/ false);
                    final String volumeNameFromPath = values.getAsString(MediaColumns.VOLUME_NAME);
                    for (String column : sMigrateColumns) {
                        DatabaseUtils.copyFromCursorToContentValues(column, c, values);
                    }
                    final String volumeNameMigrated = values.getAsString(MediaColumns.VOLUME_NAME);
                    // While upgrading from P OS or below, VOLUME_NAME can be NULL in legacy
                    // database. When VOLUME_NAME is NULL, extract VOLUME_NAME from
                    // MediaColumns.DATA
                    if (volumeNameMigrated == null || volumeNameMigrated.isEmpty()) {
                        values.put(MediaColumns.VOLUME_NAME, volumeNameFromPath);
                    }

                    final String volumePath = FileUtils.extractVolumePath(data);

                    // Handle playlist files which may need special handling if
                    // there are no "real" playlist files.
                    final int mediaType = c.getInt(c.getColumnIndex(FileColumns.MEDIA_TYPE));
                    if (isExternal() && volumePath != null &&
                            mediaType == FileColumns.MEDIA_TYPE_PLAYLIST) {
                        File playlistFile = new File(data);

                        if (!playlistFile.exists()) {
                            if (LOGV) Log.v(TAG, "Migrating playlist file " + playlistFile);

                            // Migrate virtual playlists to a "real" playlist file.
                            // Also change playlist file name and path to adapt to new
                            // default primary directory.
                            String playlistFilePath = data;
                            try {
                                playlistFilePath = migratePlaylistFiles(client,
                                        c.getLong(c.getColumnIndex(FileColumns._ID)));
                                // Either migration didn't happen or is not necessary because
                                // playlist file already exists
                                if (playlistFilePath == null) playlistFilePath = data;
                            } catch (Exception e) {
                                // We only have one shot to migrate data, so log and
                                // keep marching forward.
                                Log.w(TAG, "Couldn't migrate playlist file " + data);
                            }

                            values.put(FileColumns.DATA, playlistFilePath);
                            FileUtils.computeValuesFromData(values, /*isForFuse*/ false);
                        }
                    }

                    // When migrating pending or trashed files, we might need to
                    // rename them on disk to match new schema
                    if (volumePath != null) {
                        final String oldData = values.getAsString(MediaColumns.DATA);
                        FileUtils.computeDataFromValues(values, new File(volumePath),
                                /*isForFuse*/ false);
                        final String recomputedData = values.getAsString(MediaColumns.DATA);
                        if (!Objects.equals(oldData, recomputedData)) {
                            try {
                                renameWithRetry(oldData, recomputedData);
                            } catch (IOException e) {
                                // We only have one shot to migrate data, so log and
                                // keep marching forward
                                Log.w(TAG, "Failed to rename " + values + "; continuing", e);
                                FileUtils.computeValuesFromData(values, /*isForFuse*/ false);
                            }
                        }
                    }

                    if (db.insert("files", null, values) == -1) {
                        // We only have one shot to migrate data, so log and
                        // keep marching forward
                        Log.w(TAG, "Failed to insert " + values + "; continuing");
                    }

                    // To avoid SQLITE_NOMEM errors, we need to periodically
                    // flush the current transaction and start another one
                    if ((c.getPosition() % 2_000) == 0) {
                        db.setTransactionSuccessful();
                        db.endTransaction();
                        db.beginTransaction();

                        // And announce that we're actively making progress
                        final int progress = c.getPosition();
                        final int total = c.getCount();
                        Log.v(TAG, "Migrated " + progress + " of " + total + "...");
                        if (mMigrationListener != null) {
                            mMigrationListener.onProgress(client, mVolumeName, progress, total);
                        }
                    }
                }

                Log.d(TAG, "Finished migration from legacy provider");
            } catch (Exception e) {
                // We have to guard ourselves against any weird behavior of the
                // legacy provider by trying to catch everything
                Log.w(TAG, "Failed migration from legacy provider", e);
            }

            // We tried our best above to migrate everything we could, and we
            // only have one possible shot, so mark everything successful
            db.setTransactionSuccessful();
            db.endTransaction();
            if (mMigrationListener != null) {
                mMigrationListener.onFinished(client, mVolumeName);
            }
        }

    }

    @Nullable
    private String migratePlaylistFiles(ContentProviderClient client, long playlistId)
            throws IllegalStateException {
        final String selection = FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_PLAYLIST
                + " AND " + FileColumns._ID + "=" + playlistId;
        final String[] projection = new String[]{
                FileColumns._ID,
                FileColumns.DATA,
                MediaColumns.MIME_TYPE,
                MediaStore.Audio.PlaylistsColumns.NAME,
        };
        final Uri queryUri = MediaStore
                .rewriteToLegacy(MediaStore.Files.getContentUri(mVolumeName));

        try (Cursor cursor = client.query(queryUri, projection, selection, null, null)) {
            if (!cursor.moveToFirst()) {
                throw new IllegalStateException("Couldn't find database row for playlist file"
                        + playlistId);
            }

            final String data = cursor.getString(cursor.getColumnIndex(MediaColumns.DATA));
            File playlistFile = new File(data);
            if (playlistFile.exists()) {
                throw new IllegalStateException("Playlist file exists " + data);
            }

            String mimeType = cursor.getString(cursor.getColumnIndex(MediaColumns.MIME_TYPE));
            // Sometimes, playlists in Q may have mimeType as
            // "application/octet-stream". Ensure that playlist rows have the
            // right playlist mimeType. These rows will be committed to a file
            // and hence they should have correct playlist mimeType for
            // Playlist#write to identify the right child playlist class.
            if (!MimeUtils.isPlaylistMimeType(mimeType)) {
                // Playlist files should always have right mimeType, default to
                // audio/mpegurl when mimeType doesn't match playlist media_type.
                mimeType = "audio/mpegurl";
            }

            // If the directory is Playlists/ change the directory to Music/
            // since defaultPrimary for playlists is Music/. This helps
            // resolve any future app-compat issues around renaming playlist
            // files.
            File parentFile = playlistFile.getParentFile();
            if (parentFile.getName().equalsIgnoreCase("Playlists")) {
                parentFile = new File(parentFile.getParentFile(), Environment.DIRECTORY_MUSIC);
            }
            final String playlistName = cursor.getString(
                    cursor.getColumnIndex(MediaStore.Audio.PlaylistsColumns.NAME));

            try {
                // Build playlist file path with a file extension that matches
                // playlist mimeType.
                playlistFile = FileUtils.buildUniqueFile(parentFile, mimeType, playlistName);
            } catch(FileNotFoundException e) {
                Log.e(TAG, "Couldn't create unique file for " + playlistFile +
                        ", using actual playlist file name", e);
            }

            final long rowId = cursor.getLong(cursor.getColumnIndex(FileColumns._ID));
            final Uri playlistMemberUri = MediaStore.rewriteToLegacy(
                    MediaStore.Audio.Playlists.Members.getContentUri(mVolumeName, rowId));
            createPlaylistFile(client, playlistMemberUri, playlistFile);
            return playlistFile.getAbsolutePath();
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates "real" playlist files on disk from the playlist data from the database.
     */
    private void createPlaylistFile(ContentProviderClient client, @NonNull Uri playlistMemberUri,
            @NonNull File playlistFile) throws IllegalStateException {
        final String[] projection = new String[] {
                MediaStore.Audio.Playlists.Members.AUDIO_ID,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER,
        };

        final Playlist playlist = new Playlist();
        // Migrating music->playlist association.
        try (Cursor c = client.query(playlistMemberUri, projection, null, null,
                Audio.Playlists.Members.DEFAULT_SORT_ORDER)) {
            while (c.moveToNext()) {
                // Write these values to the playlist file
                final long audioId = c.getLong(0);
                final int playOrder = c.getInt(1);

                final Uri audioFileUri = MediaStore.rewriteToLegacy(ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(mVolumeName), audioId));
                final String audioFilePath = queryForData(client, audioFileUri);
                if (audioFilePath == null)  {
                    // This shouldn't happen, we should always find audio file
                    // unless audio file is removed, and database has stale db
                    // row. However this shouldn't block creating playlist
                    // files;
                    Log.e(TAG, "Couldn't find audio file for " + audioId + ", continuing..");
                    continue;
                }
                playlist.add(playOrder, playlistFile.toPath().getParent().
                        relativize(new File(audioFilePath).toPath()));
            }

            try {
                writeToPlaylistFileWithRetry(playlistFile, playlist);
            } catch (IOException e) {
                // We only have one shot to migrate data, so log and
                // keep marching forward.
                Log.w(TAG, "Couldn't migrate playlist file " + playlistFile);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Return the {@link MediaColumns#DATA} field for the given {@code uri}.
     */
    private String queryForData(ContentProviderClient client, @NonNull Uri uri) {
        try (Cursor c = client.query(uri, new String[] {FileColumns.DATA}, Bundle.EMPTY, null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception occurred while querying for data file for " + uri, e);
        }
        return null;
    }

    /**
     * Set of columns that should be migrated from the legacy provider,
     * including core information to identify each media item, followed by
     * columns that can be edited by users. (We omit columns here that are
     * marked as "readOnly" in the {@link MediaStore} annotations, since those
     * will be regenerated by the first scan after upgrade.)
     */
    private static final ArraySet<String> sMigrateColumns = new ArraySet<>();

    {
        sMigrateColumns.add(MediaStore.MediaColumns._ID);
        sMigrateColumns.add(MediaStore.MediaColumns.DATA);
        sMigrateColumns.add(MediaStore.MediaColumns.VOLUME_NAME);
        sMigrateColumns.add(MediaStore.Files.FileColumns.MEDIA_TYPE);

        sMigrateColumns.add(MediaStore.MediaColumns.DATE_ADDED);
        sMigrateColumns.add(MediaStore.MediaColumns.DATE_EXPIRES);
        sMigrateColumns.add(MediaStore.MediaColumns.IS_PENDING);
        sMigrateColumns.add(MediaStore.MediaColumns.IS_TRASHED);
        sMigrateColumns.add(MediaStore.MediaColumns.IS_FAVORITE);
        sMigrateColumns.add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);

        sMigrateColumns.add(MediaStore.MediaColumns.ORIENTATION);
        sMigrateColumns.add(MediaStore.Files.FileColumns.PARENT);

        sMigrateColumns.add(MediaStore.Audio.AudioColumns.BOOKMARK);

        sMigrateColumns.add(MediaStore.Video.VideoColumns.TAGS);
        sMigrateColumns.add(MediaStore.Video.VideoColumns.CATEGORY);
        sMigrateColumns.add(MediaStore.Video.VideoColumns.BOOKMARK);

        // This also migrates MediaStore.Images.ImageColumns.IS_PRIVATE
        // as they both have the same value "isprivate".
        sMigrateColumns.add(MediaStore.Video.VideoColumns.IS_PRIVATE);

        sMigrateColumns.add(MediaStore.DownloadColumns.DOWNLOAD_URI);
        sMigrateColumns.add(MediaStore.DownloadColumns.REFERER_URI);
    }

    private static void makePristineViews(SQLiteDatabase db) {
        // drop all views
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'view'",
                null, null, null, null);
        while (c.moveToNext()) {
            db.execSQL("DROP VIEW IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createLatestViews(SQLiteDatabase db) {
        makePristineViews(db);

        if (!mProjectionHelper.hasColumnAnnotation()) {
            Log.w(TAG, "No column annotation provided; not creating views");
            return;
        }

        final String filterVolumeNames;
        synchronized (mFilterVolumeNames) {
            filterVolumeNames = bindList(mFilterVolumeNames.toArray());
        }

        if (isExternal()) {
            db.execSQL("CREATE VIEW audio_playlists AS SELECT "
                    + getColumnsForCollection(Audio.Playlists.class)
                    + " FROM files WHERE media_type=4");
        }

        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");

        db.execSQL("CREATE VIEW audio AS SELECT "
                + getColumnsForCollection(Audio.Media.class)
                + " FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW video AS SELECT "
                + getColumnsForCollection(Video.Media.class)
                + " FROM files WHERE media_type=3");
        db.execSQL("CREATE VIEW images AS SELECT "
                + getColumnsForCollection(Images.Media.class)
                + " FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW downloads AS SELECT "
                + getColumnsForCollection(Downloads.class)
                + " FROM files WHERE is_download=1");

        db.execSQL("CREATE VIEW audio_artists AS SELECT "
                + "  artist_id AS " + Audio.Artists._ID
                + ", MIN(artist) AS " + Audio.Artists.ARTIST
                + ", artist_key AS " + Audio.Artists.ARTIST_KEY
                + ", COUNT(DISTINCT album_id) AS " + Audio.Artists.NUMBER_OF_ALBUMS
                + ", COUNT(DISTINCT _id) AS " + Audio.Artists.NUMBER_OF_TRACKS
                + " FROM audio"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + filterVolumeNames
                + " GROUP BY artist_id");

        db.execSQL("CREATE VIEW audio_artists_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", MIN(album) AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", (SELECT COUNT(*) FROM audio WHERE " + Audio.Albums.ALBUM_ID
                + " = TEMP.album_id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio TEMP"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + filterVolumeNames
                + " GROUP BY album_id, artist_id");

        db.execSQL("CREATE VIEW audio_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", MIN(album) AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio"
                + " WHERE is_music=1 AND is_pending=0 AND is_trashed=0"
                + " AND volume_name IN " + filterVolumeNames
                + " GROUP BY album_id");

        db.execSQL("CREATE VIEW audio_genres AS SELECT "
                + "  genre_id AS " + Audio.Genres._ID
                + ", MIN(genre) AS " + Audio.Genres.NAME
                + " FROM audio"
                + " WHERE is_pending=0 AND is_trashed=0 AND volume_name IN " + filterVolumeNames
                + " GROUP BY genre_id");
    }

    private String getColumnsForCollection(Class<?> collection) {
        return String.join(",", mProjectionHelper.getProjectionMap(collection).keySet())
                + ",_modifier";
    }

    private static void makePristineTriggers(SQLiteDatabase db) {
        // drop all triggers
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'trigger'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TRIGGER IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private static void createLatestTriggers(SQLiteDatabase db) {
        makePristineTriggers(db);

        final String insertArg =
                "new.volume_name||':'||new._id||':'||new.media_type||':'||new"
                        + ".is_download||':'||new.is_pending||':'||new.is_trashed||':'||new"
                        + ".is_favorite||':'||new._user_id||':'||ifnull(new.date_expires,'null')"
                        + "||':'||ifnull(new.owner_package_name,'null')||':'||new._data";
        final String updateArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||new._id||':'||new.media_type||':'||new.is_download"
                        + "||':'||old.is_trashed||':'||new.is_trashed"
                        + "||':'||old.is_pending||':'||new.is_pending"
                        + "||':'||ifnull(old.is_favorite,0)"
                        + "||':'||ifnull(new.is_favorite,0)"
                        + "||':'||ifnull(old._special_format,0)"
                        + "||':'||ifnull(new._special_format,0)"
                        + "||':'||ifnull(old.owner_package_name,'null')"
                        + "||':'||ifnull(new.owner_package_name,'null')"
                        + "||':'||ifnull(old._user_id,0)"
                        + "||':'||ifnull(new._user_id,0)"
                        + "||':'||ifnull(old.date_expires,'null')"
                        + "||':'||ifnull(new.date_expires,'null')"
                        + "||':'||old._data";
        final String deleteArg =
                "old.volume_name||':'||old._id||':'||old.media_type||':'||old.is_download"
                        + "||':'||ifnull(old.owner_package_name,'null')||':'||old._data";

        db.execSQL("CREATE TRIGGER files_insert AFTER INSERT ON files"
                + " BEGIN SELECT _INSERT(" + insertArg + "); END");
        db.execSQL("CREATE TRIGGER files_update AFTER UPDATE ON files"
                + " BEGIN SELECT _UPDATE(" + updateArg + "); END");
        db.execSQL("CREATE TRIGGER files_delete AFTER DELETE ON files"
                + " BEGIN SELECT _DELETE(" + deleteArg + "); END");
    }

    private static void makePristineIndexes(SQLiteDatabase db) {
        // drop all indexes
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'index'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private static void createLatestIndexes(SQLiteDatabase db) {
        makePristineIndexes(db);

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");
    }

    private static void updateCollationKeys(SQLiteDatabase db) {
        // Delete albums and artists, then clear the modification time on songs, which
        // will cause the media scanner to rescan everything, rebuilding the artist and
        // album tables along the way, while preserving playlists.
        // We need this rescan because ICU also changed, and now generates different
        // collation keys
        db.execSQL("DELETE from albums");
        db.execSQL("DELETE from artists");
        db.execSQL("UPDATE files SET date_modified=0;");
    }

    private static void updateAddTitleResource(SQLiteDatabase db) {
        // Add the column used for title localization, and force a rescan of any
        // ringtones, alarms and notifications that may be using it.
        db.execSQL("ALTER TABLE files ADD COLUMN title_resource_uri TEXT");
        db.execSQL("UPDATE files SET date_modified=0"
                + " WHERE (is_alarm IS 1) OR (is_ringtone IS 1) OR (is_notification IS 1)");
    }

    private static void updateAddOwnerPackageName(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN owner_package_name TEXT DEFAULT NULL");

        // Derive new column value based on well-known paths
        try (Cursor c = db.query("files", new String[] { FileColumns._ID, FileColumns.DATA },
                FileColumns.DATA + " REGEXP '" + FileUtils.PATTERN_OWNED_PATH.pattern() + "'",
                null, null, null, null, null)) {
            Log.d(TAG, "Updating " + c.getCount() + " entries with well-known owners");

            final Matcher m = FileUtils.PATTERN_OWNED_PATH.matcher("");
            final ContentValues values = new ContentValues();

            while (c.moveToNext()) {
                final long id = c.getLong(0);
                final String data = c.getString(1);
                m.reset(data);
                if (m.matches()) {
                    final String packageName = m.group(1);
                    values.clear();
                    values.put(FileColumns.OWNER_PACKAGE_NAME, packageName);
                    db.update("files", values, "_id=" + id, null);
                }
            }
        }
    }

    private static void updateAddColorSpaces(SQLiteDatabase db) {
        // Add the color aspects related column used for HDR detection etc.
        db.execSQL("ALTER TABLE files ADD COLUMN color_standard INTEGER;");
        db.execSQL("ALTER TABLE files ADD COLUMN color_transfer INTEGER;");
        db.execSQL("ALTER TABLE files ADD COLUMN color_range INTEGER;");
    }

    private static void updateAddHashAndPending(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _hash BLOB DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_pending INTEGER DEFAULT 0;");
    }

    private static void updateAddDownloadInfo(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_download INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN download_uri TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN referer_uri TEXT DEFAULT NULL;");
    }

    private static void updateAddAudiobook(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_audiobook INTEGER DEFAULT 0;");
    }

    private static void updateAddRecording(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_recording INTEGER DEFAULT 0;");
        // We add the column is_recording, rescan all music files
        db.execSQL("UPDATE files SET date_modified=0 WHERE is_music=1;");
    }

    private static void updateAddRedactedUriId(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN redacted_uri_id TEXT DEFAULT NULL;");
    }

    private static void updateClearLocation(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET latitude=NULL, longitude=NULL;");
    }

    private static void updateSetIsDownload(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET is_download=1 WHERE _data REGEXP '"
                + FileUtils.PATTERN_DOWNLOADS_FILE + "'");
    }

    private static void updateAddExpiresAndTrashed(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN date_expires INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_trashed INTEGER DEFAULT 0;");
    }

    private static void updateAddGroupId(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN group_id INTEGER DEFAULT NULL;");
    }

    private static void updateAddDirectories(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN primary_directory TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN secondary_directory TEXT DEFAULT NULL;");
    }

    private static void updateAddXmpMm(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN document_id TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN instance_id TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN original_document_id TEXT DEFAULT NULL;");
    }

    private static void updateAddPath(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN relative_path TEXT DEFAULT NULL;");
    }

    private static void updateAddVolumeName(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN volume_name TEXT DEFAULT NULL;");
    }

    private static void updateDirsMimeType(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET mime_type=NULL WHERE format="
                + MtpConstants.FORMAT_ASSOCIATION);
    }

    private static void updateRelativePath(SQLiteDatabase db) {
        db.execSQL("UPDATE files"
                + " SET " + MediaColumns.RELATIVE_PATH + "=" + MediaColumns.RELATIVE_PATH + "||'/'"
                + " WHERE " + MediaColumns.RELATIVE_PATH + " IS NOT NULL"
                + " AND " + MediaColumns.RELATIVE_PATH + " NOT LIKE '%/';");
    }

    private static void updateAddTranscodeSatus(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _transcode_status INTEGER DEFAULT 0;");
    }

    private static void updateAddSpecialFormat(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _special_format INTEGER DEFAULT NULL;");
    }

    private static void updateSpecialFormatToNotDetected(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET _special_format=NULL WHERE _special_format=0");
    }

    private static void updateAddVideoCodecType(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _video_codec_type TEXT DEFAULT NULL;");
    }

    private static void updateClearDirectories(SQLiteDatabase db) {
        db.execSQL("UPDATE files SET primary_directory=NULL, secondary_directory=NULL;");
    }

    private static void updateRestructureAudio(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN artist_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN album_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre_id INTEGER;");

        db.execSQL("DROP TABLE IF EXISTS artists;");
        db.execSQL("DROP TABLE IF EXISTS albums;");
        db.execSQL("DROP TABLE IF EXISTS audio_genres;");
        db.execSQL("DROP TABLE IF EXISTS audio_genres_map;");

        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");

        db.execSQL("DROP INDEX IF EXISTS album_idx");
        db.execSQL("DROP INDEX IF EXISTS albumkey_index");
        db.execSQL("DROP INDEX IF EXISTS artist_idx");
        db.execSQL("DROP INDEX IF EXISTS artistkey_index");

        // Since we're radically changing how the schema is defined, the
        // simplest path forward is to rescan all audio files
        db.execSQL("UPDATE files SET date_modified=0 WHERE media_type=2;");
    }

    private static void updateAddMetadata(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN author TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN bitrate INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN capture_framerate REAL DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN cd_track_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN compilation INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN disc_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_favorite INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN num_tracks INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN writer TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN exposure_time TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN f_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN iso INTEGER DEFAULT NULL;");
    }

    private static void updateAddSceneCaptureType(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN scene_capture_type INTEGER DEFAULT NULL;");
    }

    private static void updateMigrateLogs(SQLiteDatabase db) {
        // Migrate any existing logs to new system
        try (Cursor c = db.query("log", new String[] { "time", "message" },
                null, null, null, null, null)) {
            while (c.moveToNext()) {
                final String time = c.getString(0);
                final String message = c.getString(1);
                Logging.logPersistent("Historical log " + time + " " + message);
            }
        }
        db.execSQL("DELETE FROM log;");
    }

    private static void updateAddLocalMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");
    }

    private static void updateAddGeneration(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN generation_added INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN generation_modified INTEGER DEFAULT 0;");
    }

    private static void updateAddXmp(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN xmp BLOB DEFAULT NULL;");
    }

    private static void updateAudioAlbumId(SQLiteDatabase db) {
        // We change the logic for generating album id, rescan all audio files
        db.execSQL("UPDATE files SET date_modified=0 WHERE media_type=2;");
    }

    private static void updateAddModifier(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE files ADD COLUMN _modifier INTEGER DEFAULT 0;");
        // For existing files, set default value as _MODIFIER_MEDIA_SCAN
        db.execSQL("UPDATE files SET _modifier=3;");
    }

    private static void updateAddDeletedMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deleted_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "old_id INTEGER UNIQUE, generation_modified INTEGER NOT NULL)");
    }

    private static void updateAddMediaGrantsTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS media_grants");
        db.execSQL(
                "CREATE TABLE media_grants ("
                        + "owner_package_name TEXT,"
                        + "file_id INTEGER,"
                        + "package_user_id INTEGER,"
                        + "UNIQUE(owner_package_name, file_id, package_user_id)"
                        + "  ON CONFLICT IGNORE "
                        + "FOREIGN KEY (file_id)"
                        + "  REFERENCES files(_id)"
                        + "  ON DELETE CASCADE"
                        + ")");
    }

    private void updateUserId(SQLiteDatabase db) {
        db.execSQL(String.format(Locale.ROOT,
                "ALTER TABLE files ADD COLUMN _user_id INTEGER DEFAULT %d;",
                UserHandle.myUserId()));
    }

    private static void recomputeDataValues(SQLiteDatabase db) {
        try (Cursor c = db.query("files", new String[] { FileColumns._ID, FileColumns.DATA },
                null, null, null, null, null, null)) {
            Log.d(TAG, "Recomputing " + c.getCount() + " data values");

            final ContentValues values = new ContentValues();
            while (c.moveToNext()) {
                values.clear();
                final long id = c.getLong(0);
                final String data = c.getString(1);
                values.put(FileColumns.DATA, data);
                FileUtils.computeValuesFromData(values, /*isForFuse*/ false);
                values.remove(FileColumns.DATA);
                if (!values.isEmpty()) {
                    db.update("files", values, "_id=" + id, null);
                }
            }
        }
    }

    private static void recomputeMediaTypeValues(SQLiteDatabase db) {
        // Only update the files with MEDIA_TYPE_NONE.
        final String selection = FileColumns.MEDIA_TYPE + "=?";
        final String[] selectionArgs = new String[]{String.valueOf(FileColumns.MEDIA_TYPE_NONE)};

        ArrayMap<Long, Integer> newMediaTypes = new ArrayMap<>();
        try (Cursor c = db.query("files", new String[] { FileColumns._ID, FileColumns.MIME_TYPE },
                selection, selectionArgs, null, null, null, null)) {
            Log.d(TAG, "Recomputing " + c.getCount() + " MediaType values");

            // Accumulate all the new MEDIA_TYPE updates.
            while (c.moveToNext()) {
                final long id = c.getLong(0);
                final String mimeType = c.getString(1);
                // Only update Document and Subtitle media type
                if (MimeUtils.isSubtitleMimeType(mimeType)) {
                    newMediaTypes.put(id, FileColumns.MEDIA_TYPE_SUBTITLE);
                } else if (MimeUtils.isDocumentMimeType(mimeType)) {
                    newMediaTypes.put(id, FileColumns.MEDIA_TYPE_DOCUMENT);
                }
            }
        }
        // Now, update all the new MEDIA_TYPE values.
        final ContentValues values = new ContentValues();
        for (long id: newMediaTypes.keySet()) {
            values.clear();
            values.put(FileColumns.MEDIA_TYPE, newMediaTypes.get(id));
            db.update("files", values, "_id=" + id, null);
        }
    }

    static final int VERSION_J = 509;
    static final int VERSION_K = 700;
    static final int VERSION_L = 700;
    static final int VERSION_M = 800;
    static final int VERSION_N = 800;
    static final int VERSION_O = 800;
    static final int VERSION_P = 900;
    static final int VERSION_Q = 1023;
    static final int VERSION_R = 1115;
    static final int VERSION_S = 1209;
    static final int VERSION_T = 1308;
    // Leave some gaps in database version tagging to allow T schema changes
    // to go independent of U schema changes.
    static final int VERSION_U = 1407;
    public static final int VERSION_LATEST = VERSION_U;

    /**
     * This method takes care of updating all the tables in the database to the
     * current version, creating them if necessary.
     * This method can only update databases at schema 700 or higher, which was
     * used by the KitKat release. Older database will be cleared and recreated.
     * @param db Database
     */
    private void updateDatabase(SQLiteDatabase db, int fromVersion, int toVersion) {
        final long startTime = SystemClock.elapsedRealtime();

        if (fromVersion < 700) {
            // Anything older than KK is recreated from scratch
            createLatestSchema(db);
        } else {
            boolean recomputeDataValues = false;
            if (fromVersion < 800) {
                updateCollationKeys(db);
            }
            if (fromVersion < 900) {
                updateAddTitleResource(db);
            }
            if (fromVersion < 1000) {
                updateAddOwnerPackageName(db);
            }
            if (fromVersion < 1003) {
                updateAddColorSpaces(db);
            }
            if (fromVersion < 1004) {
                updateAddHashAndPending(db);
            }
            if (fromVersion < 1005) {
                updateAddDownloadInfo(db);
            }
            if (fromVersion < 1006) {
                updateAddAudiobook(db);
            }
            if (fromVersion < 1007) {
                updateClearLocation(db);
            }
            if (fromVersion < 1008) {
                updateSetIsDownload(db);
            }
            if (fromVersion < 1009) {
                // This database version added "secondary_bucket_id", but that
                // column name was refactored in version 1013 below, so this
                // update step is no longer needed.
            }
            if (fromVersion < 1010) {
                updateAddExpiresAndTrashed(db);
            }
            if (fromVersion < 1012) {
                recomputeDataValues = true;
            }
            if (fromVersion < 1013) {
                updateAddGroupId(db);
                updateAddDirectories(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1014) {
                updateAddXmpMm(db);
            }
            if (fromVersion < 1015) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1016) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1017) {
                updateSetIsDownload(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1018) {
                updateAddPath(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1019) {
                // Only trigger during "external", so that it runs only once.
                if (isExternal()) {
                    deleteLegacyThumbnailData();
                }
            }
            if (fromVersion < 1020) {
                updateAddVolumeName(db);
                recomputeDataValues = true;
            }
            if (fromVersion < 1021) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1022) {
                updateDirsMimeType(db);
            }
            if (fromVersion < 1023) {
                updateRelativePath(db);
            }
            if (fromVersion < 1100) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1101) {
                updateClearDirectories(db);
            }
            if (fromVersion < 1102) {
                updateRestructureAudio(db);
            }
            if (fromVersion < 1103) {
                updateAddMetadata(db);
            }
            if (fromVersion < 1104) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1105) {
                recomputeDataValues = true;
            }
            if (fromVersion < 1106) {
                updateMigrateLogs(db);
            }
            if (fromVersion < 1107) {
                updateAddSceneCaptureType(db);
            }
            if (fromVersion < 1108) {
                updateAddLocalMetadata(db);
            }
            if (fromVersion < 1109) {
                updateAddGeneration(db);
            }
            if (fromVersion < 1110) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1111) {
                recomputeMediaTypeValues(db);
            }
            if (fromVersion < 1112) {
                updateAddXmp(db);
            }
            if (fromVersion < 1113) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1114) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1115) {
                updateAudioAlbumId(db);
            }
            if (fromVersion < 1200) {
                updateAddTranscodeSatus(db);
            }
            if (fromVersion < 1201) {
                updateAddVideoCodecType(db);
            }
            if (fromVersion < 1202) {
                updateAddModifier(db);
            }
            if (fromVersion < 1203) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1204) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1205) {
                updateAddRecording(db);
            }
            if (fromVersion < 1206) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1207) {
                updateAddRedactedUriId(db);
            }
            if (fromVersion < 1208) {
                updateUserId(db);
            }
            if (fromVersion < 1209) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1301) {
                updateAddDeletedMediaTable(db);
            }
            if (fromVersion < 1302) {
                updateAddSpecialFormat(db);
            }
            if (fromVersion < 1303) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1304) {
                updateSpecialFormatToNotDetected(db);
            }
            if (fromVersion < 1305) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1306) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1307) {
                // This is to ensure Animated Webp files are tagged
                updateSpecialFormatToNotDetected(db);
            }
            if (fromVersion < 1308) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1400) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1404) {
                // Empty version bump to ensure triggers are recreated
            }

            if (fromVersion < 1406) {
                // Empty version bump to ensure triggers are recreated
            }

            if (fromVersion < 1407) {
                if (isExternal()) {
                    updateAddMediaGrantsTable(db);
                }
            }

            // If this is the legacy database, it's not worth recomputing data
            // values locally, since they'll be recomputed after the migration
            if (mLegacyProvider) {
                recomputeDataValues = false;
            }

            if (recomputeDataValues) {
                recomputeDataValues(db);
            }
        }

        // Always recreate latest views and triggers during upgrade; they're
        // cheap and it's an easy way to ensure they're defined consistently
        createLatestViews(db);
        createLatestTriggers(db);

        getOrCreateUuid(db);

        final long elapsedMillis = (SystemClock.elapsedRealtime() - startTime);
        if (mSchemaListener != null) {
            mSchemaListener.onSchemaChange(mVolumeName, fromVersion, toVersion,
                    getItemCount(db), elapsedMillis, getOrCreateUuid(db));
        }
    }

    private void downgradeDatabase(SQLiteDatabase db, int fromVersion, int toVersion) {
        final long startTime = SystemClock.elapsedRealtime();

        // The best we can do is wipe and start over
        createLatestSchema(db);

        final long elapsedMillis = (SystemClock.elapsedRealtime() - startTime);
        if (mSchemaListener != null) {
            mSchemaListener.onSchemaChange(mVolumeName, fromVersion, toVersion,
                    getItemCount(db), elapsedMillis, getOrCreateUuid(db));
        }
    }

    private static final String XATTR_UUID = "user.uuid";

    /**
     * Return a UUID for the given database. If the database is deleted or
     * otherwise corrupted, then a new UUID will automatically be generated.
     */
    public static @NonNull String getOrCreateUuid(@NonNull SQLiteDatabase db) {
        try {
            return new String(Os.getxattr(db.getPath(), XATTR_UUID));
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENODATA) {
                // Doesn't exist yet, so generate and persist a UUID
                return resetAndGetUuid(db);
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private static @NonNull String resetAndGetUuid(SQLiteDatabase db) {
        final String uuid = UUID.randomUUID().toString();
        try {
            Os.setxattr(db.getPath(), XATTR_UUID, uuid.getBytes(), 0);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
        return uuid;
    }

    private static final long PASSTHROUGH_WAIT_TIMEOUT = 10 * DateUtils.SECOND_IN_MILLIS;

    /**
     * When writing to playlist files during migration, the underlying
     * pass-through view of storage may not be mounted yet, so we're willing
     * to retry several times before giving up.
     * The retry logic is mainly added to avoid test flakiness.
     */
    private static void writeToPlaylistFileWithRetry(@NonNull File playlistFile,
            @NonNull Playlist playlist) throws IOException {
        final long start = SystemClock.elapsedRealtime();
        while (true) {
            if (SystemClock.elapsedRealtime() - start > PASSTHROUGH_WAIT_TIMEOUT) {
                throw new IOException("Passthrough failed to mount");
            }

            try {
                playlistFile.getParentFile().mkdirs();
                playlistFile.createNewFile();
                playlist.write(playlistFile);
                return;
            } catch (IOException e) {
                Log.i(TAG, "Failed to migrate playlist file, retrying " + e);
            }
            Log.i(TAG, "Waiting for passthrough to be mounted...");
            SystemClock.sleep(100);
        }
    }

    /**
     * When renaming files during migration, the underlying pass-through view of
     * storage may not be mounted yet, so we're willing to retry several times
     * before giving up.
     */
    private static void renameWithRetry(@NonNull String oldPath, @NonNull String newPath)
            throws IOException {
        final long start = SystemClock.elapsedRealtime();
        while (true) {
            if (SystemClock.elapsedRealtime() - start > PASSTHROUGH_WAIT_TIMEOUT) {
                throw new IOException("Passthrough failed to mount");
            }

            try {
                Os.rename(oldPath, newPath);
                return;
            } catch (ErrnoException e) {
                Log.i(TAG, "Failed to rename: " + e);
            }

            Log.i(TAG, "Waiting for passthrough to be mounted...");
            SystemClock.sleep(100);
        }
    }

    /**
     * Return the current generation that will be populated into
     * {@link MediaColumns#GENERATION_ADDED} or
     * {@link MediaColumns#GENERATION_MODIFIED}.
     */
    public static long getGeneration(@NonNull SQLiteDatabase db) {
        return android.database.DatabaseUtils.longForQuery(db,
                CURRENT_GENERATION_CLAUSE + ";", null);
    }

    /**
     * Return total number of items tracked inside this database. This includes
     * only real media items, and does not include directories.
     */
    public static long getItemCount(@NonNull SQLiteDatabase db) {
        return android.database.DatabaseUtils.longForQuery(db,
                "SELECT COUNT(_id) FROM files WHERE " + FileColumns.MIME_TYPE + " IS NOT NULL",
                null);
    }

    public boolean isInternal() {
        return mName.equals(INTERNAL_DATABASE_NAME);
    }

    public boolean isExternal() {
        // Matches test dbs as external
        switch (mName) {
            case EXTERNAL_DATABASE_NAME:
                return true;
            case TEST_RECOMPUTE_DB:
                return true;
            case TEST_UPGRADE_DB:
                return true;
            case TEST_DOWNGRADE_DB:
                return true;
            case TEST_CLEAN_DB:
                return true;
            default:
                return false;
        }
    }

    @SuppressLint("DefaultLocale")
    @GuardedBy("sRecoveryLock")
    private void updateNextRowIdInDatabaseAndExternalStorage(SQLiteDatabase db) {
        Optional<Long> nextRowIdOptional = getNextRowIdFromXattr();
        // Use a billion as the next row id if not found on external storage.
        long nextRowId = nextRowIdOptional.orElse(NEXT_ROW_ID_DEFAULT_BILLION_VALUE);

        backupNextRowId(nextRowId);
        // Insert and delete a row to update sqlite_sequence counter
        db.execSQL(String.format(Locale.ROOT, "INSERT INTO files(_ID) VALUES (%d)", nextRowId));
        db.execSQL(String.format(Locale.ROOT, "DELETE FROM files WHERE _ID=%d", nextRowId));
        Log.i(TAG, String.format(Locale.ROOT, "Updated sqlite counter of Files table of %s to %d.",
                mName, nextRowId));
    }

    /**
     * Backs up next row id value in xattr to {@code nextRowId} + BackupFrequency. Also updates
     * respective in-memory next row id cached value.
     */
    protected void backupNextRowId(long nextRowId) {
        long backupId = nextRowId + getNextRowIdBackupFrequency();
        boolean setOnExternalStorage = setXattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                getNextRowIdXattrKeyForDatabase(),
                String.valueOf(backupId));
        if (setOnExternalStorage) {
            mNextRowIdBackup.set(backupId);
            Log.i(TAG, String.format(Locale.ROOT, "Backed up next row id as:%d on path:%s for %s.",
                    backupId, DATA_MEDIA_XATTR_DIRECTORY_PATH, mName));
        }
    }

    protected Optional<Long> getNextRowIdFromXattr() {
        try {
            return Optional.of(Long.parseLong(new String(
                    Os.getxattr(DATA_MEDIA_XATTR_DIRECTORY_PATH,
                            getNextRowIdXattrKeyForDatabase()))));
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.ROOT, "Xattr:%s not found on external storage: %s",
                    getNextRowIdXattrKeyForDatabase(), e));
            return Optional.empty();
        }
    }

    protected String getNextRowIdXattrKeyForDatabase() {
        if (isInternal()) {
            return INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY;
        } else if (isExternal()) {
            return EXTERNAL_DB_NEXT_ROW_ID_XATTR_KEY;
        }
        throw new RuntimeException(
                String.format(Locale.ROOT, "Next row id xattr key not defined for database:%s.",
                        mName));
    }

    private String getSessionIdXattrKeyForDatabase() {
        if (isInternal()) {
            return INTERNAL_DB_SESSION_ID_XATTR_KEY;
        } else if (isExternal()) {
            return EXTERNAL_DB_SESSION_ID_XATTR_KEY;
        }
        throw new RuntimeException(
                String.format(Locale.ROOT, "Session id xattr key not defined for database:%s.",
                        mName));
    }

    protected Optional<Long> getNextRowId() {
        if (mNextRowIdBackup.get() == INVALID_ROW_ID) {
            return getNextRowIdFromXattr();
        }

        return Optional.of(mNextRowIdBackup.get());
    }

    boolean isNextRowIdBackupEnabled() {
        if (!mEnableNextRowIdRecovery) {
            return false;
        }

        if (mVersion < VERSION_R) {
            // Do not back up next row id if DB version is less than R. This is unlikely to hit
            // as we will backport row id backup changes till Android R.
            Log.v(TAG, "Skipping next row id backup for android versions less than R.");
            return false;
        }

        if (!(new File(DATA_MEDIA_XATTR_DIRECTORY_PATH)).exists()) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "Skipping row id recovery as path:%s does not exist.",
                    DATA_MEDIA_XATTR_DIRECTORY_PATH));
            return false;
        }

        return SystemProperties.getBoolean("persist.sys.fuse.backup.nextrowid_enabled",
                true);
    }

    public static int getNextRowIdBackupFrequency() {
        return SystemProperties.getInt("persist.sys.fuse.backup.nextrowid_backup_frequency",
                1000);
    }

    boolean isDatabaseRecovering() {
        return mIsRecovering.get();
    }

    private String traceSectionName(@NonNull String method) {
        return "DH[" + getDatabaseName() + "]." + method;
    }
}
