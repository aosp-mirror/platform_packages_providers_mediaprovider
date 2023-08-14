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

package com.android.providers.media.scan;

import static android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM;
import static android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST;
import static android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST;
import static android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR;
import static android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_RANGE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COMPILATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_DATE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_DURATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_GENRE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT;
import static android.media.MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH;
import static android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS;
import static android.media.MediaMetadataRetriever.METADATA_KEY_TITLE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_CODEC_MIME_TYPE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH;
import static android.media.MediaMetadataRetriever.METADATA_KEY_WRITER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_YEAR;
import static android.provider.MediaStore.AUTHORITY;
import static android.provider.MediaStore.UNKNOWN_STRING;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.providers.media.util.Metrics.translateReason;

import static java.util.Objects.requireNonNull;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.drm.DrmManagerClient;
import android.drm.DrmSupportInfo;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Audio.PlaylistsColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.MediaVolume;
import com.android.providers.media.util.DatabaseUtils;
import com.android.providers.media.util.ExifUtils;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.IsoInterface;
import com.android.providers.media.util.LongArray;
import com.android.providers.media.util.Metrics;
import com.android.providers.media.util.MimeUtils;
import com.android.providers.media.util.SpecialFormatDetector;
import com.android.providers.media.util.XmpInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modern implementation of media scanner.
 * <p>
 * This is a bug-compatible reimplementation of the legacy media scanner, but
 * written purely in managed code for better testability and long-term
 * maintainability.
 * <p>
 * Initial tests shows it performing roughly on-par with the legacy scanner.
 * <p>
 * In general, we start by populating metadata based on file attributes, and
 * then overwrite with any valid metadata found using
 * {@link MediaMetadataRetriever}, {@link ExifInterface}, and
 * {@link XmpInterface}, each with increasing levels of trust.
 */
public class ModernMediaScanner implements MediaScanner {
    private static final String TAG = "ModernMediaScanner";
    private static final boolean LOGW = Log.isLoggable(TAG, Log.WARN);
    private static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // TODO: refactor to use UPSERT once we have SQLite 3.24.0

    // TODO: deprecate playlist editing
    // TODO: deprecate PARENT column, since callers can't see directories

    @GuardedBy("S_DATE_FORMAT")
    private static final SimpleDateFormat S_DATE_FORMAT;
    @GuardedBy("S_DATE_FORMAT_WITH_MILLIS")
    private static final SimpleDateFormat S_DATE_FORMAT_WITH_MILLIS;

    static {
        S_DATE_FORMAT = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        S_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));

        S_DATE_FORMAT_WITH_MILLIS = new SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS");
        S_DATE_FORMAT_WITH_MILLIS.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static final int BATCH_SIZE = 32;
    private static final int MAX_XMP_SIZE_BYTES = 1024 * 1024;
    // |excludeDirs * 2| < 1000 which is the max SQL expression size
    // Because we add |excludeDir| and |excludeDir/| in the SQL expression to match dir and subdirs
    // See SQLITE_MAX_EXPR_DEPTH in sqlite3.c
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static final int MAX_EXCLUDE_DIRS = 450;

    private static final Pattern PATTERN_YEAR = Pattern.compile("([1-9][0-9][0-9][0-9])");

    private static final Pattern PATTERN_ALBUM_ART = Pattern.compile(
            "(?i)(?:(?:^folder|(?:^AlbumArt(?:(?:_\\{.*\\}_)?(?:small|large))?))(?:\\.jpg$)|(?:\\._.*))");

    private final Context mContext;
    private final DrmManagerClient mDrmClient;
    @GuardedBy("mPendingCleanDirectories")
    private final Set<String> mPendingCleanDirectories = new ArraySet<>();

    /**
     * List of active scans.
     */
    @GuardedBy("mActiveScans")

    private final List<Scan> mActiveScans = new ArrayList<>();

    /**
     * Holder that contains a reference count of the number of threads
     * interested in a specific directory, along with a lock to ensure that
     * parallel scans don't overlap and confuse each other.
     */
    private static class DirectoryLock {
        public int count;
        public final Lock lock = new ReentrantLock();
    }

    /**
     * Map from directory to locks designed to ensure that parallel scans don't
     * overlap and confuse each other.
     */
    @GuardedBy("mDirectoryLocks")
    private final Map<Path, DirectoryLock> mDirectoryLocks = new ArrayMap<>();

    /**
     * Set of MIME types that should be considered to be DRM, meaning we need to
     * consult {@link DrmManagerClient} to obtain the actual MIME type.
     */
    private final Set<String> mDrmMimeTypes = new ArraySet<>();

    public ModernMediaScanner(Context context) {
        mContext = context;
        mDrmClient = new DrmManagerClient(context);

        // Dynamically collect the set of MIME types that should be considered
        // to be DRM, as this can vary between devices
        for (DrmSupportInfo info : mDrmClient.getAvailableDrmSupportInfo()) {
            Iterator<String> mimeTypes = info.getMimeTypeIterator();
            while (mimeTypes.hasNext()) {
                mDrmMimeTypes.add(mimeTypes.next());
            }
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public void scanDirectory(@NonNull File file, int reason) {
        requireNonNull(file);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            Log.e(TAG, "Couldn't canonicalize directory to scan" + file, e);
            return;
        }

        try (Scan scan = new Scan(file, reason)) {
            scan.run();
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Couldn't find directory to scan", e);
        } catch (OperationCanceledException ignored) {
            // No-op.
        }
    }

    @Override
    @Nullable
    public Uri scanFile(@NonNull File file, int reason) {
        requireNonNull(file);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            Log.e(TAG, "Couldn't canonicalize file to scan" + file, e);
            return null;
        }

        try (Scan scan = new Scan(file, reason)) {
            scan.run();
            return scan.getFirstResult();
        } catch (OperationCanceledException ignored) {
            return null;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Couldn't find file to scan", e) ;
            return null;
        }
    }

    @Override
    public void onDetachVolume(MediaVolume volume) {
        synchronized (mActiveScans) {
            for (Scan scan : mActiveScans) {
                if (volume.equals(scan.mVolume)) {
                    scan.mSignal.cancel();
                }
            }
        }
    }

    @Override
    public void onIdleScanStopped() {
        synchronized (mActiveScans) {
            for (Scan scan : mActiveScans) {
                if (scan.mReason == REASON_IDLE) {
                    scan.mSignal.cancel();
                }
            }
        }
    }

    @Override
    public void onDirectoryDirty(@NonNull File dir) {
        requireNonNull(dir);
        try {
            dir = dir.getCanonicalFile();
        } catch (IOException e) {
            Log.e(TAG, "Couldn't canonicalize directory" + dir, e);
            return;
        }

        synchronized (mPendingCleanDirectories) {
            mPendingCleanDirectories.remove(dir.getPath());
            FileUtils.setDirectoryDirty(dir, /* isDirty */ true);
        }
    }

    private void addActiveScan(Scan scan) {
        synchronized (mActiveScans) {
            mActiveScans.add(scan);
        }
    }

    private void removeActiveScan(Scan scan) {
        synchronized (mActiveScans) {
            mActiveScans.remove(scan);
        }
    }

    /**
     * Individual scan request for a specific file or directory. When run it
     * will traverse all included media files under the requested location,
     * reconciling them against {@link MediaStore}.
     */
    private class Scan implements Runnable, FileVisitor<Path>, AutoCloseable {
        private final ContentProviderClient mClient;
        private final ContentResolver mResolver;

        private final File mRoot;
        private final int mReason;
        private final MediaVolume mVolume;
        private final String mVolumeName;
        private final Uri mFilesUri;
        private final CancellationSignal mSignal;
        private final List<String> mExcludeDirs;

        private final long mStartGeneration;
        private final boolean mSingleFile;
        private final Set<Path> mAcquiredDirectoryLocks = new ArraySet<>();
        private final ArrayList<ContentProviderOperation> mPending = new ArrayList<>();
        private LongArray mScannedIds = new LongArray();
        private LongArray mUnknownIds = new LongArray();

        private long mFirstId = -1;

        private int mFileCount;
        private int mInsertCount;
        private int mUpdateCount;
        private int mDeleteCount;

        /**
         * Tracks hidden directory and hidden subdirectories in a directory tree. A positive count
         * indicates that one or more of the current file's parents is a hidden directory.
         */
        private int mHiddenDirCount;
        /**
         * Indicates if the nomedia directory tree is dirty. When a nomedia directory is dirty, we
         * mark the top level nomedia as dirty. Hence if one of the sub directory in the nomedia
         * directory is dirty, we consider the whole top level nomedia directory tree as dirty.
         */
        private boolean mIsDirectoryTreeDirty;

        public Scan(File root, int reason)
                throws FileNotFoundException {
            Trace.beginSection("ctor");

            mClient = mContext.getContentResolver()
                    .acquireContentProviderClient(MediaStore.AUTHORITY);
            mResolver = ContentResolver.wrap(mClient.getLocalContentProvider());

            mRoot = root;
            mReason = reason;

            if (FileUtils.contains(Environment.getStorageDirectory(), root)) {
                mVolume = MediaVolume.fromStorageVolume(FileUtils.getStorageVolume(mContext, root));
            } else {
                mVolume = MediaVolume.fromInternal();
            }
            mVolumeName = mVolume.getName();
            mFilesUri = MediaStore.Files.getContentUri(mVolumeName);
            mSignal = new CancellationSignal();

            mStartGeneration = MediaStore.getGeneration(mResolver, mVolumeName);
            mSingleFile = mRoot.isFile();
            mExcludeDirs = new ArrayList<>();

            Trace.endSection();
        }

        @Override
        public void run() {
            addActiveScan(this);
            try {
                runInternal();
            } finally {
                removeActiveScan(this);
            }
        }

        private void runInternal() {
            final long startTime = SystemClock.elapsedRealtime();

            // First, scan everything that should be visible under requested
            // location, tracking scanned IDs along the way
            walkFileTree();

            // Second, reconcile all items known in the database against all the
            // items we scanned above
            if (mSingleFile && mScannedIds.size() == 1) {
                // We can safely skip this step if the scan targeted a single
                // file which we scanned above
            } else {
                reconcileAndClean();
            }

            // Third, resolve any playlists that we scanned
            resolvePlaylists();

            if (!mSingleFile) {
                final long durationMillis = SystemClock.elapsedRealtime() - startTime;
                Metrics.logScan(mVolumeName, mReason, mFileCount, durationMillis,
                        mInsertCount, mUpdateCount, mDeleteCount);
            }
        }

        private void walkFileTree() {
            mSignal.throwIfCanceled();
            final Pair<Boolean, Boolean> isDirScannableAndHidden =
                    shouldScanPathAndIsPathHidden(mSingleFile ? mRoot.getParentFile() : mRoot);
            if (isDirScannableAndHidden.first) {
                // This directory is scannable.
                Trace.beginSection("walkFileTree");

                if (isDirScannableAndHidden.second) {
                    // This directory is hidden
                    mHiddenDirCount++;
                }
                if (mSingleFile) {
                    acquireDirectoryLock(mRoot.getParentFile().toPath());
                }
                try {
                    Files.walkFileTree(mRoot.toPath(), this);
                    applyPending();
                } catch (IOException e) {
                    // This should never happen, so yell loudly
                    throw new IllegalStateException(e);
                } finally {
                    if (mSingleFile) {
                        releaseDirectoryLock(mRoot.getParentFile().toPath());
                    }
                    Trace.endSection();
                }
            }
        }

        private String buildExcludeDirClause(int count) {
            if (count == 0) {
                return "";
            }
            String notLikeClause = FileColumns.DATA + " NOT LIKE ? ESCAPE '\\'";
            String andClause = " AND ";
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for (int i = 0; i < count; i++) {
                // Append twice because we want to match the path itself and the expanded path
                // using the SQL % LIKE operator. For instance, to exclude /sdcard/foo and all
                // subdirs, we need the following:
                // "NOT LIKE '/sdcard/foo/%' AND "NOT LIKE '/sdcard/foo'"
                // The first clause matches *just* subdirs, and the second clause matches the dir
                // itself
                sb.append(notLikeClause);
                sb.append(andClause);
                sb.append(notLikeClause);
                if (i != count - 1) {
                    sb.append(andClause);
                }
            }
            sb.append(")");
            return sb.toString();
        }

        private void addEscapedAndExpandedPath(String path, List<String> paths) {
            String escapedPath = DatabaseUtils.escapeForLike(path);
            paths.add(escapedPath + "/%");
            paths.add(escapedPath);
        }

        private String[] buildSqlSelectionArgs() {
            List<String> escapedPaths = new ArrayList<>();

            addEscapedAndExpandedPath(mRoot.getAbsolutePath(), escapedPaths);
            for (String dir : mExcludeDirs) {
                addEscapedAndExpandedPath(dir, escapedPaths);
            }

            return escapedPaths.toArray(new String[0]);
        }

        private void reconcileAndClean() {
            final long[] scannedIds = mScannedIds.toArray();
            Arrays.sort(scannedIds);

            // The query phase is split from the delete phase so that our query
            // remains stable if we need to paginate across multiple windows.
            mSignal.throwIfCanceled();
            Trace.beginSection("reconcile");

            // Ignore abstract playlists which don't have files on disk
            final String formatClause = "ifnull(" + FileColumns.FORMAT + ","
                    + MtpConstants.FORMAT_UNDEFINED + ") != "
                    + MtpConstants.FORMAT_ABSTRACT_AV_PLAYLIST;
            final String dataClause = "(" + FileColumns.DATA + " LIKE ? ESCAPE '\\' OR "
                    + FileColumns.DATA + " LIKE ? ESCAPE '\\')";
            final String excludeDirClause = buildExcludeDirClause(mExcludeDirs.size());
            final String generationClause = FileColumns.GENERATION_ADDED + " <= "
                    + mStartGeneration;
            final String sqlSelection = formatClause + " AND " + dataClause + " AND "
                    + generationClause
                    + (excludeDirClause.isEmpty() ? "" : " AND " + excludeDirClause);
            final Bundle queryArgs = new Bundle();
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sqlSelection);
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    buildSqlSelectionArgs());
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    FileColumns._ID + " DESC");
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE);

            final int[] countPerMediaType = new int[FileColumns.MEDIA_TYPE_COUNT];
            try (Cursor c = mResolver.query(mFilesUri,
                    new String[]{FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_EXPIRES,
                            FileColumns.IS_PENDING}, queryArgs, mSignal)) {
                while (c.moveToNext()) {
                    final long id = c.getLong(0);
                    if (Arrays.binarySearch(scannedIds, id) < 0) {
                        final long dateExpire = c.getLong(2);
                        final boolean isPending = c.getInt(3) == 1;
                        // Don't delete the pending item which is not expired.
                        // If the scan is triggered between invoking
                        // ContentResolver#insert() and ContentResolver#openFileDescriptor(),
                        // it raises the FileNotFoundException b/166063754.
                        if (isPending && dateExpire > System.currentTimeMillis() / 1000) {
                            continue;
                        }
                        mUnknownIds.add(id);
                        final int mediaType = c.getInt(1);
                        // Avoid ArrayIndexOutOfBounds if more mediaTypes are added,
                        // but mediaTypeSize is not updated
                        if (mediaType < countPerMediaType.length) {
                            countPerMediaType[mediaType]++;
                        }
                    }
                }
            } finally {
                Trace.endSection();
            }

            // Third, clean all the unknown database entries found above
            mSignal.throwIfCanceled();
            Trace.beginSection("clean");
            try {
                for (int i = 0; i < mUnknownIds.size(); i++) {
                    final long id = mUnknownIds.get(i);
                    if (LOGV) Log.v(TAG, "Cleaning " + id);
                    final Uri uri = MediaStore.Files.getContentUri(mVolumeName, id).buildUpon()
                            .appendQueryParameter(MediaStore.PARAM_DELETE_DATA, "false")
                            .build();
                    addPending(ContentProviderOperation.newDelete(uri).build());
                    maybeApplyPending();
                }
                applyPending();
            } finally {
                if (mUnknownIds.size() > 0) {
                    String scanReason = "scan triggered by reason: " + translateReason(mReason);
                    Metrics.logDeletionPersistent(mVolumeName, scanReason, countPerMediaType);
                }
                Trace.endSection();
            }
        }

        private void resolvePlaylists() {
            mSignal.throwIfCanceled();

            // Playlists aren't supported on internal storage, so bail early
            if (MediaStore.VOLUME_INTERNAL.equals(mVolumeName)) return;

            final Uri playlistsUri = MediaStore.Audio.Playlists.getContentUri(mVolumeName);
            final Bundle queryArgs = new Bundle();
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    FileColumns.GENERATION_MODIFIED + " > " + mStartGeneration);
            try (Cursor c = mResolver.query(playlistsUri, new String[] { FileColumns._ID },
                    queryArgs, mSignal)) {
                while (c.moveToNext()) {
                    final long id = c.getLong(0);
                    MediaStore.resolvePlaylistMembers(mResolver,
                            ContentUris.withAppendedId(playlistsUri, id));
                }
            } finally {
                Trace.endSection();
            }
        }

        /**
         * Create and acquire a lock on the given directory, giving the calling
         * thread exclusive access to ensure that parallel scans don't overlap
         * and confuse each other.
         */
        private void acquireDirectoryLock(@NonNull Path dir) {
            Trace.beginSection("acquireDirectoryLock");
            DirectoryLock lock;
            synchronized (mDirectoryLocks) {
                lock = mDirectoryLocks.get(dir);
                if (lock == null) {
                    lock = new DirectoryLock();
                    mDirectoryLocks.put(dir, lock);
                }
                lock.count++;
            }
            lock.lock.lock();
            mAcquiredDirectoryLocks.add(dir);
            Trace.endSection();
        }

        /**
         * Release a currently held lock on the given directory, releasing any
         * other waiting parallel scans to proceed, and cleaning up data
         * structures if no other threads are waiting.
         */
        private void releaseDirectoryLock(@NonNull Path dir) {
            Trace.beginSection("releaseDirectoryLock");
            DirectoryLock lock;
            synchronized (mDirectoryLocks) {
                lock = mDirectoryLocks.get(dir);
                if (lock == null) {
                    throw new IllegalStateException();
                }
                if (--lock.count == 0) {
                    mDirectoryLocks.remove(dir);
                }
            }
            lock.lock.unlock();
            mAcquiredDirectoryLocks.remove(dir);
            Trace.endSection();
        }

        @Override
        public void close() {
            // Release any locks we're still holding, typically when we
            // encountered an exception; we snapshot the original list so we're
            // not confused as it's mutated by release operations
            for (Path dir : new ArraySet<>(mAcquiredDirectoryLocks)) {
                releaseDirectoryLock(dir);
            }

            mClient.close();
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
            // Possibly bail before digging into each directory
            mSignal.throwIfCanceled();

            if (!shouldScanDirectory(dir.toFile())) {
                return FileVisitResult.SKIP_SUBTREE;
            }

            synchronized (mPendingCleanDirectories) {
                if (mIsDirectoryTreeDirty) {
                    // Directory tree is dirty, continue scanning subtree.
                } else if (FileUtils.getTopLevelNoMedia(dir.toFile()) == null) {
                  // No nomedia file found, continue scanning.
                } else if (FileUtils.isDirectoryDirty(FileUtils.getTopLevelNoMedia(dir.toFile()))) {
                    // Track the directory dirty status for directory tree in mIsDirectoryDirty.
                    // This removes additional dirty state check for subdirectories of nomedia
                    // directory.
                    mIsDirectoryTreeDirty = true;
                    mPendingCleanDirectories.add(dir.toFile().getPath());
                } else {
                    Log.d(TAG, "Skipping preVisitDirectory " + dir.toFile());
                    if (mExcludeDirs.size() <= MAX_EXCLUDE_DIRS) {
                        mExcludeDirs.add(dir.toFile().getPath());
                        return FileVisitResult.SKIP_SUBTREE;
                    } else {
                        Log.w(TAG, "ExcludeDir size exceeded, not skipping preVisitDirectory "
                                + dir.toFile());
                    }
                }
            }

            // Acquire lock on this directory to ensure parallel scans don't
            // overlap and confuse each other
            acquireDirectoryLock(dir);

            if (FileUtils.isDirectoryHidden(dir.toFile())) {
                mHiddenDirCount++;
            }

            // Scan this directory as a normal file so that "parent" database
            // entries are created
            return visitFile(dir, attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
            if (LOGV) Log.v(TAG, "Visiting " + file);
            mFileCount++;

            // Skip files that have already been scanned, and which haven't
            // changed since they were last scanned
            final File realFile = file.toFile();
            long existingId = -1;

            String actualMimeType;
            if (attrs.isDirectory()) {
                actualMimeType = null;
            } else {
                actualMimeType = MimeUtils.resolveMimeType(realFile);
            }

            // Resolve the MIME type of DRM files before scanning them; if we
            // have trouble then we'll continue scanning as a generic file
            final boolean isDrm = mDrmMimeTypes.contains(actualMimeType);
            if (isDrm) {
                actualMimeType = mDrmClient.getOriginalMimeType(realFile.getPath());
            }

            int actualMediaType = mediaTypeFromMimeType(
                    realFile, actualMimeType, FileColumns.MEDIA_TYPE_NONE);

            Trace.beginSection("checkChanged");

            final Bundle queryArgs = new Bundle();
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    FileColumns.DATA + "=?");
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[] { realFile.getAbsolutePath() });
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE);
            final String[] projection = new String[] {FileColumns._ID, FileColumns.DATE_MODIFIED,
                    FileColumns.SIZE, FileColumns.MIME_TYPE, FileColumns.MEDIA_TYPE,
                    FileColumns.IS_PENDING, FileColumns._MODIFIER};

            final Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(realFile.getName());
            // If IS_PENDING is set by FUSE, we should scan the file and update IS_PENDING to zero.
            // Pending files from FUSE will not be rewritten to contain expiry timestamp.
            boolean isPendingFromFuse = !matcher.matches();

            try (Cursor c = mResolver.query(mFilesUri, projection, queryArgs, mSignal)) {
                if (c.moveToFirst()) {
                    existingId = c.getLong(0);
                    final String mimeType = c.getString(3);
                    final int mediaType = c.getInt(4);
                    isPendingFromFuse &= c.getInt(5) != 0;

                    // Remember visiting this existing item, even if we skipped
                    // due to it being unchanged; this is needed so we don't
                    // delete the item during a later cleaning phase
                    mScannedIds.add(existingId);

                    // We also technically found our first result
                    if (mFirstId == -1) {
                        mFirstId = existingId;
                    }

                    if (attrs.isDirectory()) {
                        if (LOGV) Log.v(TAG, "Skipping directory " + file);
                        return FileVisitResult.CONTINUE;
                    }

                    final boolean sameMetadata =
                            hasSameMetadata(attrs, realFile, isPendingFromFuse, c);
                    final boolean sameMediaType = actualMediaType == mediaType;
                    if (sameMetadata && sameMediaType) {
                        if (LOGV) Log.v(TAG, "Skipping unchanged " + file);
                        return FileVisitResult.CONTINUE;
                    }

                    // For this special case we may have changed mime type from the file's metadata.
                    // This is safe because mime_type cannot be changed outside of scanning.
                    if (sameMetadata
                            && "video/mp4".equalsIgnoreCase(actualMimeType)
                            && "audio/mp4".equalsIgnoreCase(mimeType)) {
                        if (LOGV) Log.v(TAG, "Skipping unchanged video/audio " + file);
                        return FileVisitResult.CONTINUE;
                    }
                }

                // Since we allow top-level mime type to be customised, we need to do this early
                // on, so the file is later scanned as the appropriate type (otherwise, this
                // audio filed would be scanned as video and it would be missing the correct
                // metadata).
                actualMimeType = updateM4aMimeType(realFile, actualMimeType);
                actualMediaType =
                        mediaTypeFromMimeType(realFile, actualMimeType, actualMediaType);
            } finally {
                Trace.endSection();
            }

            final ContentProviderOperation.Builder op;
            Trace.beginSection("scanItem");
            try {
                op = scanItem(existingId, realFile, attrs, actualMimeType, actualMediaType,
                        mVolumeName);
            } finally {
                Trace.endSection();
            }
            if (op != null) {
                op.withValue(FileColumns._MODIFIER, FileColumns._MODIFIER_MEDIA_SCAN);

                // Force DRM files to be marked as DRM, since the lower level
                // stack may not set this correctly
                if (isDrm) {
                    op.withValue(MediaColumns.IS_DRM, 1);
                }
                addPending(op.build());
                maybeApplyPending();
            }
            return FileVisitResult.CONTINUE;
        }

        private int mediaTypeFromMimeType(
                File file, String mimeType, int defaultMediaType) {
            if (mimeType != null) {
                return resolveMediaTypeFromFilePath(
                        file, mimeType, /*isHidden*/ mHiddenDirCount > 0);
            }
            return defaultMediaType;
        }

        private boolean hasSameMetadata(
                BasicFileAttributes attrs, File realFile, boolean isPendingFromFuse, Cursor c) {
            final long dateModified = c.getLong(1);
            final boolean sameTime = (lastModifiedTime(realFile, attrs) == dateModified);

            final long size = c.getLong(2);
            final boolean sameSize = (attrs.size() == size);

            final boolean isScanned =
                    c.getInt(6) == FileColumns._MODIFIER_MEDIA_SCAN;

            return sameTime && sameSize && !isPendingFromFuse && isScanned;
        }

        /**
         * For this one very narrow case, we allow mime types to be customised when the top levels
         * differ. This opens the given file, so avoid calling unless really necessary. This
         * returns the defaultMimeType for non-m4a files or if opening the file throws an exception.
         */
        private String updateM4aMimeType(File file, String defaultMimeType) {
            if ("video/mp4".equalsIgnoreCase(defaultMimeType)) {
                try (
                    FileInputStream is = new FileInputStream(file);
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                    mmr.setDataSource(is.getFD());
                    String refinedMimeType = mmr.extractMetadata(METADATA_KEY_MIMETYPE);
                    if ("audio/mp4".equalsIgnoreCase(refinedMimeType)) {
                        return refinedMimeType;
                    }
                } catch (Exception e) {
                    return defaultMimeType;
                }
            }
            return defaultMimeType;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
                throws IOException {
            Log.w(TAG, "Failed to visit " + file + ": " + exc);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
            // We need to drain all pending changes related to this directory
            // before releasing our lock below
            applyPending();

            if (FileUtils.isDirectoryHidden(dir.toFile())) {
                mHiddenDirCount--;
            }

            // Now that we're finished scanning this directory, release lock to
            // allow other parallel scans to proceed
            releaseDirectoryLock(dir);

            if (mIsDirectoryTreeDirty) {
                synchronized (mPendingCleanDirectories) {
                    if (mPendingCleanDirectories.remove(dir.toFile().getPath())) {
                        // If |dir| is still clean, then persist
                        FileUtils.setDirectoryDirty(dir.toFile(), false /* isDirty */);
                        mIsDirectoryTreeDirty = false;
                    }
                }
            }
            return FileVisitResult.CONTINUE;
        }

        private void addPending(ContentProviderOperation op) {
            mPending.add(op);

            if (op.isInsert()) mInsertCount++;
            if (op.isUpdate()) mUpdateCount++;
            if (op.isDelete()) mDeleteCount++;
        }

        private void maybeApplyPending() {
            if (mPending.size() > BATCH_SIZE) {
                applyPending();
            }
        }

        private void applyPending() {
            // Bail early when nothing pending
            if (mPending.isEmpty()) return;

            Trace.beginSection("applyPending");
            try {
                ContentProviderResult[] results = mResolver.applyBatch(AUTHORITY, mPending);
                for (int index = 0; index < results.length; index++) {
                    ContentProviderResult result = results[index];
                    ContentProviderOperation operation = mPending.get(index);

                    if (result.exception != null) {
                        Log.w(TAG, "Failed to apply " + operation, result.exception);
                    }

                    Uri uri = result.uri;
                    if (uri != null) {
                        final long id = ContentUris.parseId(uri);
                        if (mFirstId == -1) {
                            mFirstId = id;
                        }
                        mScannedIds.add(id);
                    }
                }
            } catch (RemoteException | OperationApplicationException e) {
                Log.w(TAG, "Failed to apply", e);
            } finally {
                mPending.clear();
                Trace.endSection();
            }
        }

        /**
         * Return the first item encountered by this scan requested.
         * <p>
         * Internally resolves to the relevant media collection where this item
         * exists based on {@link FileColumns#MEDIA_TYPE}.
         */
        public @Nullable Uri getFirstResult() {
            if (mFirstId == -1) return null;

            final Uri fileUri = MediaStore.Files.getContentUri(mVolumeName, mFirstId);
            try (Cursor c = mResolver.query(fileUri,
                    new String[] { FileColumns.MEDIA_TYPE }, null, null)) {
                if (c.moveToFirst()) {
                    switch (c.getInt(0)) {
                        case FileColumns.MEDIA_TYPE_AUDIO:
                            return MediaStore.Audio.Media.getContentUri(mVolumeName, mFirstId);
                        case FileColumns.MEDIA_TYPE_VIDEO:
                            return MediaStore.Video.Media.getContentUri(mVolumeName, mFirstId);
                        case FileColumns.MEDIA_TYPE_IMAGE:
                            return MediaStore.Images.Media.getContentUri(mVolumeName, mFirstId);
                        case FileColumns.MEDIA_TYPE_PLAYLIST:
                            return ContentUris.withAppendedId(
                                    MediaStore.Audio.Playlists.getContentUri(mVolumeName),
                                    mFirstId);
                    }
                }
            }

            // Worst case, we can always use generic collection
            return fileUri;
        }
    }

    /**
     * Scan the requested file, returning a {@link ContentProviderOperation}
     * containing all indexed metadata, suitable for passing to a
     * {@link SQLiteDatabase#replace} operation.
     */
    private static @Nullable ContentProviderOperation.Builder scanItem(long existingId, File file,
            BasicFileAttributes attrs, String mimeType, int mediaType, String volumeName) {
        if (Objects.equals(file.getName(), ".nomedia")) {
            if (LOGD) Log.d(TAG, "Ignoring .nomedia file: " + file);
            return null;
        }

        if (attrs.isDirectory()) {
            return scanItemDirectory(existingId, file, attrs, mimeType, volumeName);
        }

        switch (mediaType) {
            case FileColumns.MEDIA_TYPE_AUDIO:
                return scanItemAudio(existingId, file, attrs, mimeType, mediaType, volumeName);
            case FileColumns.MEDIA_TYPE_VIDEO:
                return scanItemVideo(existingId, file, attrs, mimeType, mediaType, volumeName);
            case FileColumns.MEDIA_TYPE_IMAGE:
                return scanItemImage(existingId, file, attrs, mimeType, mediaType, volumeName);
            case FileColumns.MEDIA_TYPE_PLAYLIST:
                return scanItemPlaylist(existingId, file, attrs, mimeType, mediaType, volumeName);
            case FileColumns.MEDIA_TYPE_SUBTITLE:
                return scanItemSubtitle(existingId, file, attrs, mimeType, mediaType, volumeName);
            case FileColumns.MEDIA_TYPE_DOCUMENT:
                return scanItemDocument(existingId, file, attrs, mimeType, mediaType, volumeName);
            default:
                return scanItemFile(existingId, file, attrs, mimeType, mediaType, volumeName);
        }
    }

    /**
     * Populate the given {@link ContentProviderOperation} with the generic
     * {@link MediaColumns} values that can be determined directly from the file
     * or its attributes.
     * <p>
     * This is typically the first set of values defined so that we correctly
     * clear any values that had been set by a previous scan and which are no
     * longer present in the media item.
     */
    private static void withGenericValues(ContentProviderOperation.Builder op,
            File file, BasicFileAttributes attrs, String mimeType, Integer mediaType) {
        withOptionalMimeTypeAndMediaType(op, Optional.ofNullable(mimeType),
                Optional.ofNullable(mediaType));

        op.withValue(MediaColumns.DATA, file.getAbsolutePath());
        op.withValue(MediaColumns.SIZE, attrs.size());
        op.withValue(MediaColumns.DATE_MODIFIED, lastModifiedTime(file, attrs));
        op.withValue(MediaColumns.DATE_TAKEN, null);
        op.withValue(MediaColumns.IS_DRM, 0);
        op.withValue(MediaColumns.WIDTH, null);
        op.withValue(MediaColumns.HEIGHT, null);
        op.withValue(MediaColumns.RESOLUTION, null);
        op.withValue(MediaColumns.DOCUMENT_ID, null);
        op.withValue(MediaColumns.INSTANCE_ID, null);
        op.withValue(MediaColumns.ORIGINAL_DOCUMENT_ID, null);
        op.withValue(MediaColumns.ORIENTATION, null);

        op.withValue(MediaColumns.CD_TRACK_NUMBER, null);
        op.withValue(MediaColumns.ALBUM, null);
        op.withValue(MediaColumns.ARTIST, null);
        op.withValue(MediaColumns.AUTHOR, null);
        op.withValue(MediaColumns.COMPOSER, null);
        op.withValue(MediaColumns.GENRE, null);
        op.withValue(MediaColumns.TITLE, FileUtils.extractFileName(file.getName()));
        op.withValue(MediaColumns.YEAR, null);
        op.withValue(MediaColumns.DURATION, null);
        op.withValue(MediaColumns.NUM_TRACKS, null);
        op.withValue(MediaColumns.WRITER, null);
        op.withValue(MediaColumns.ALBUM_ARTIST, null);
        op.withValue(MediaColumns.DISC_NUMBER, null);
        op.withValue(MediaColumns.COMPILATION, null);
        op.withValue(MediaColumns.BITRATE, null);
        op.withValue(MediaColumns.CAPTURE_FRAMERATE, null);
    }

    /**
     * Populate the given {@link ContentProviderOperation} with the generic
     * {@link MediaColumns} values using the given
     * {@link MediaMetadataRetriever}.
     */
    private static void withRetrieverValues(ContentProviderOperation.Builder op,
            MediaMetadataRetriever mmr, String mimeType) {
        withOptionalMimeTypeAndMediaType(op,
                parseOptionalMimeType(mimeType, mmr.extractMetadata(METADATA_KEY_MIMETYPE)),
                /*optionalMediaType*/ Optional.empty());

        withOptionalValue(op, MediaColumns.DATE_TAKEN,
                parseOptionalDate(mmr.extractMetadata(METADATA_KEY_DATE)));
        withOptionalValue(op, MediaColumns.CD_TRACK_NUMBER,
                parseOptional(mmr.extractMetadata(METADATA_KEY_CD_TRACK_NUMBER)));
        withOptionalValue(op, MediaColumns.ALBUM,
                parseOptional(mmr.extractMetadata(METADATA_KEY_ALBUM)));
        withOptionalValue(op, MediaColumns.ARTIST, firstPresent(
                parseOptional(mmr.extractMetadata(METADATA_KEY_ARTIST)),
                parseOptional(mmr.extractMetadata(METADATA_KEY_ALBUMARTIST))));
        withOptionalValue(op, MediaColumns.AUTHOR,
                parseOptional(mmr.extractMetadata(METADATA_KEY_AUTHOR)));
        withOptionalValue(op, MediaColumns.COMPOSER,
                parseOptional(mmr.extractMetadata(METADATA_KEY_COMPOSER)));
        withOptionalValue(op, MediaColumns.GENRE,
                parseOptional(mmr.extractMetadata(METADATA_KEY_GENRE)));
        withOptionalValue(op, MediaColumns.TITLE,
                parseOptional(mmr.extractMetadata(METADATA_KEY_TITLE)));
        withOptionalValue(op, MediaColumns.YEAR,
                parseOptionalYear(mmr.extractMetadata(METADATA_KEY_YEAR)));
        withOptionalValue(op, MediaColumns.DURATION,
                parseOptional(mmr.extractMetadata(METADATA_KEY_DURATION)));
        withOptionalValue(op, MediaColumns.NUM_TRACKS,
                parseOptional(mmr.extractMetadata(METADATA_KEY_NUM_TRACKS)));
        withOptionalValue(op, MediaColumns.WRITER,
                parseOptional(mmr.extractMetadata(METADATA_KEY_WRITER)));
        withOptionalValue(op, MediaColumns.ALBUM_ARTIST,
                parseOptional(mmr.extractMetadata(METADATA_KEY_ALBUMARTIST)));
        withOptionalValue(op, MediaColumns.DISC_NUMBER,
                parseOptional(mmr.extractMetadata(METADATA_KEY_DISC_NUMBER)));
        withOptionalValue(op, MediaColumns.COMPILATION,
                parseOptional(mmr.extractMetadata(METADATA_KEY_COMPILATION)));
        withOptionalValue(op, MediaColumns.BITRATE,
                parseOptional(mmr.extractMetadata(METADATA_KEY_BITRATE)));
        withOptionalValue(op, MediaColumns.CAPTURE_FRAMERATE,
                parseOptional(mmr.extractMetadata(METADATA_KEY_CAPTURE_FRAMERATE)));
    }

    /**
     * Populate the given {@link ContentProviderOperation} with the generic
     * {@link MediaColumns} values using the given XMP metadata.
     */
    private static void withXmpValues(ContentProviderOperation.Builder op,
            XmpInterface xmp, String mimeType) {
        withOptionalMimeTypeAndMediaType(op,
                parseOptionalMimeType(mimeType, xmp.getFormat()),
                /*optionalMediaType*/ Optional.empty());

        op.withValue(MediaColumns.DOCUMENT_ID, xmp.getDocumentId());
        op.withValue(MediaColumns.INSTANCE_ID, xmp.getInstanceId());
        op.withValue(MediaColumns.ORIGINAL_DOCUMENT_ID, xmp.getOriginalDocumentId());
        op.withValue(MediaColumns.XMP, maybeTruncateXmp(xmp));
    }

    private static byte[] maybeTruncateXmp(XmpInterface xmp) {
        byte[] redacted = xmp.getRedactedXmp();
        if (redacted.length > MAX_XMP_SIZE_BYTES) {
            return new byte[0];
        }

        return redacted;
    }

    /**
     * Overwrite a value in the given {@link ContentProviderOperation}, but only
     * when the given {@link Optional} value is present.
     */
    private static void withOptionalValue(@NonNull ContentProviderOperation.Builder op,
            @NonNull String key, @NonNull Optional<?> value) {
        if (value.isPresent()) {
            op.withValue(key, value.get());
        }
    }

    /**
     * Overwrite the {@link MediaColumns#MIME_TYPE} and
     * {@link FileColumns#MEDIA_TYPE} values in the given
     * {@link ContentProviderOperation}, but only when the given
     * {@link Optional} optionalMimeType is present.
     * If {@link Optional} optionalMediaType is not present, {@link FileColumns#MEDIA_TYPE} is
     * resolved from given {@code optionalMimeType} when {@code optionalMimeType} is present.
     *
     * @param optionalMimeType An optional MIME type to apply to this operation.
     * @param optionalMediaType An optional Media type to apply to this operation.
     */
    private static void withOptionalMimeTypeAndMediaType(
            @NonNull ContentProviderOperation.Builder op,
            @NonNull Optional<String> optionalMimeType,
            @NonNull Optional<Integer> optionalMediaType) {
        if (optionalMimeType.isPresent()) {
            final String mimeType = optionalMimeType.get();
            op.withValue(MediaColumns.MIME_TYPE, mimeType);
            if (optionalMediaType.isPresent()) {
                op.withValue(FileColumns.MEDIA_TYPE, optionalMediaType.get());
            } else {
                op.withValue(FileColumns.MEDIA_TYPE, MimeUtils.resolveMediaType(mimeType));
            }
        }
    }

    private static void withResolutionValues(
            @NonNull ContentProviderOperation.Builder op,
            @NonNull ExifInterface exif, @NonNull File file) {
        final Optional<?> width = parseOptionalOrZero(
                exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH));
        final Optional<?> height = parseOptionalOrZero(
                exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH));
        final Optional<String> resolution = parseOptionalResolution(width, height);
        if (resolution.isPresent()) {
            withOptionalValue(op, MediaColumns.WIDTH, width);
            withOptionalValue(op, MediaColumns.HEIGHT, height);
            op.withValue(MediaColumns.RESOLUTION, resolution.get());
        } else {
            withBitmapResolutionValues(op, file);
        }
    }

    private static void withBitmapResolutionValues(
            @NonNull ContentProviderOperation.Builder op,
            @NonNull File file) {
        final BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
        bitmapOptions.inSampleSize = 1;
        bitmapOptions.inJustDecodeBounds = true;
        bitmapOptions.outWidth = 0;
        bitmapOptions.outHeight = 0;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bitmapOptions);

        final Optional<?> width = parseOptionalOrZero(bitmapOptions.outWidth);
        final Optional<?> height = parseOptionalOrZero(bitmapOptions.outHeight);
        withOptionalValue(op, MediaColumns.WIDTH, width);
        withOptionalValue(op, MediaColumns.HEIGHT, height);
        withOptionalValue(op, MediaColumns.RESOLUTION, parseOptionalResolution(width, height));
    }

    private static @NonNull ContentProviderOperation.Builder scanItemDirectory(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        // Directory doesn't have any MIME type or Media Type.
        withGenericValues(op, file, attrs, mimeType, /*mediaType*/ null);

        try {
            op.withValue(FileColumns.FORMAT, MtpConstants.FORMAT_ASSOCIATION);
        } catch (Exception e) {
            logTroubleScanning(file, e);
        }
        return op;
    }

    private static ArrayMap<String, String> sAudioTypes = new ArrayMap<>();

    static {
        sAudioTypes.put(Environment.DIRECTORY_RINGTONES, AudioColumns.IS_RINGTONE);
        sAudioTypes.put(Environment.DIRECTORY_NOTIFICATIONS, AudioColumns.IS_NOTIFICATION);
        sAudioTypes.put(Environment.DIRECTORY_ALARMS, AudioColumns.IS_ALARM);
        sAudioTypes.put(Environment.DIRECTORY_PODCASTS, AudioColumns.IS_PODCAST);
        sAudioTypes.put(Environment.DIRECTORY_AUDIOBOOKS, AudioColumns.IS_AUDIOBOOK);
        sAudioTypes.put(Environment.DIRECTORY_MUSIC, AudioColumns.IS_MUSIC);
        if (SdkLevel.isAtLeastS()) {
            sAudioTypes.put(Environment.DIRECTORY_RECORDINGS, AudioColumns.IS_RECORDING);
        } else {
            sAudioTypes.put(FileUtils.DIRECTORY_RECORDINGS, AudioColumns.IS_RECORDING);
        }
    }

    private static @NonNull ContentProviderOperation.Builder scanItemAudio(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        op.withValue(MediaColumns.ARTIST, UNKNOWN_STRING);
        op.withValue(MediaColumns.ALBUM, file.getParentFile().getName());
        op.withValue(AudioColumns.TRACK, null);

        final String lowPath = file.getAbsolutePath().toLowerCase(Locale.ROOT);
        boolean anyMatch = false;
        for (int i = 0; i < sAudioTypes.size(); i++) {
            final boolean match = lowPath
                    .contains('/' + sAudioTypes.keyAt(i).toLowerCase(Locale.ROOT) + '/');
            op.withValue(sAudioTypes.valueAt(i), match ? 1 : 0);
            anyMatch |= match;
        }
        if (!anyMatch) {
            op.withValue(AudioColumns.IS_MUSIC, 1);
        }

        try (FileInputStream is = new FileInputStream(file)) {
            try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                mmr.setDataSource(is.getFD());

                withRetrieverValues(op, mmr, mimeType);

                withOptionalValue(op, AudioColumns.TRACK,
                        parseOptionalTrack(mmr));
            }

            // Also hunt around for XMP metadata
            final IsoInterface iso = IsoInterface.fromFileDescriptor(is.getFD());
            final XmpInterface xmp = XmpInterface.fromContainer(iso);
            withXmpValues(op, xmp, mimeType);

        } catch (Exception e) {
            logTroubleScanning(file, e);
        }
        return op;
    }

    private static @NonNull ContentProviderOperation.Builder scanItemPlaylist(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        try {
            op.withValue(PlaylistsColumns.NAME, FileUtils.extractFileName(file.getName()));
        } catch (Exception e) {
            logTroubleScanning(file, e);
        }
        return op;
    }

    private static @NonNull ContentProviderOperation.Builder scanItemSubtitle(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        return op;
    }

    private static @NonNull ContentProviderOperation.Builder scanItemDocument(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        return op;
    }

    private static @NonNull ContentProviderOperation.Builder scanItemVideo(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        op.withValue(MediaColumns.ARTIST, UNKNOWN_STRING);
        op.withValue(MediaColumns.ALBUM, file.getParentFile().getName());
        op.withValue(VideoColumns.COLOR_STANDARD, null);
        op.withValue(VideoColumns.COLOR_TRANSFER, null);
        op.withValue(VideoColumns.COLOR_RANGE, null);
        op.withValue(FileColumns._VIDEO_CODEC_TYPE, null);

        try (FileInputStream is = new FileInputStream(file)) {
            try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                mmr.setDataSource(is.getFD());

                withRetrieverValues(op, mmr, mimeType);

                withOptionalValue(op, MediaColumns.WIDTH,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_VIDEO_WIDTH)));
                withOptionalValue(op, MediaColumns.HEIGHT,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)));
                withOptionalValue(op, MediaColumns.RESOLUTION,
                        parseOptionalVideoResolution(mmr));
                withOptionalValue(op, MediaColumns.ORIENTATION,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_VIDEO_ROTATION)));

                withOptionalValue(op, VideoColumns.COLOR_STANDARD,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_COLOR_STANDARD)));
                withOptionalValue(op, VideoColumns.COLOR_TRANSFER,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_COLOR_TRANSFER)));
                withOptionalValue(op, VideoColumns.COLOR_RANGE,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_COLOR_RANGE)));
                withOptionalValue(op, FileColumns._VIDEO_CODEC_TYPE,
                        parseOptional(mmr.extractMetadata(METADATA_KEY_VIDEO_CODEC_MIME_TYPE)));
            }

            // Also hunt around for XMP metadata
            final IsoInterface iso = IsoInterface.fromFileDescriptor(is.getFD());
            final XmpInterface xmp = XmpInterface.fromContainer(iso);
            withXmpValues(op, xmp, mimeType);

        } catch (Exception e) {
            logTroubleScanning(file, e);
        }
        return op;
    }

    private static @NonNull ContentProviderOperation.Builder scanItemImage(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        op.withValue(ImageColumns.DESCRIPTION, null);

        try (FileInputStream is = new FileInputStream(file)) {
            final ExifInterface exif = new ExifInterface(is);

            withResolutionValues(op, exif, file);

            withOptionalValue(op, MediaColumns.DATE_TAKEN,
                    parseOptionalDateTaken(exif, lastModifiedTime(file, attrs) * 1000));
            withOptionalValue(op, MediaColumns.ORIENTATION,
                    parseOptionalOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED)));

            withOptionalValue(op, ImageColumns.DESCRIPTION,
                    parseOptional(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)));
            withOptionalValue(op, ImageColumns.EXPOSURE_TIME,
                    parseOptional(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)));
            withOptionalValue(op, ImageColumns.F_NUMBER,
                    parseOptional(exif.getAttribute(ExifInterface.TAG_F_NUMBER)));
            withOptionalValue(op, ImageColumns.ISO,
                    parseOptional(exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)));
            withOptionalValue(op, ImageColumns.SCENE_CAPTURE_TYPE,
                    parseOptional(exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE)));

            // Also hunt around for XMP metadata
            final XmpInterface xmp = XmpInterface.fromContainer(exif);
            withXmpValues(op, xmp, mimeType);

            op.withValue(FileColumns._SPECIAL_FORMAT, SpecialFormatDetector.detect(exif, file));
        } catch (Exception e) {
            logTroubleScanning(file, e);
        }
        return op;
    }

    private static @NonNull ContentProviderOperation.Builder scanItemFile(long existingId,
            File file, BasicFileAttributes attrs, String mimeType, int mediaType,
            String volumeName) {
        final ContentProviderOperation.Builder op = newUpsert(volumeName, existingId);
        withGenericValues(op, file, attrs, mimeType, mediaType);

        return op;
    }

    private static @NonNull ContentProviderOperation.Builder newUpsert(
            @NonNull String volumeName, long existingId) {
        final Uri uri = MediaStore.Files.getContentUri(volumeName);
        if (existingId == -1) {
            return ContentProviderOperation.newInsert(uri)
                    .withExceptionAllowed(true);
        } else {
            return ContentProviderOperation.newUpdate(ContentUris.withAppendedId(uri, existingId))
                    .withExpectedCount(1)
                    .withExceptionAllowed(true);
        }
    }

    /**
     * Pick the first present {@link Optional} value from the given list.
     */
    @SafeVarargs
    private static @NonNull <T> Optional<T> firstPresent(@NonNull Optional<T>... options) {
        for (Optional<T> option : options) {
            if (option.isPresent()) {
                return option;
            }
        }
        return Optional.empty();
    }

    @VisibleForTesting
    static @NonNull <T> Optional<T> parseOptional(@Nullable T value) {
        if (value == null) {
            return Optional.empty();
        } else if (value instanceof String && ((String) value).length() == 0) {
            return Optional.empty();
        } else if (value instanceof String && ((String) value).equals("-1")) {
            return Optional.empty();
        } else if (value instanceof String && ((String) value).trim().length() == 0) {
            return Optional.empty();
        } else if (value instanceof Number && ((Number) value).intValue() == -1) {
            return Optional.empty();
        } else {
            return Optional.of(value);
        }
    }

    @VisibleForTesting
    static @NonNull <T> Optional<T> parseOptionalOrZero(@Nullable T value) {
        if (value instanceof String && isZero((String) value)) {
            return Optional.empty();
        } else if (value instanceof Number && ((Number) value).intValue() == 0) {
            return Optional.empty();
        } else {
            return parseOptional(value);
        }
    }

    @VisibleForTesting
    static @NonNull Optional<Integer> parseOptionalNumerator(@Nullable String value) {
        final Optional<String> parsedValue = parseOptional(value);
        if (parsedValue.isPresent()) {
            value = parsedValue.get();
            final int fractionIndex = value.indexOf('/');
            if (fractionIndex != -1) {
                value = value.substring(0, fractionIndex);
            }
            try {
                return Optional.of(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    /**
     * Try our best to calculate {@link MediaColumns#DATE_TAKEN} in reference to
     * the epoch, making our best guess from unrelated fields when offset
     * information isn't directly available.
     */
    @VisibleForTesting
    static @NonNull Optional<Long> parseOptionalDateTaken(@NonNull ExifInterface exif,
            long lastModifiedTime) {
        final long originalTime = ExifUtils.getDateTimeOriginal(exif);
        if (exif.hasAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) {
            // We have known offset information, return it directly!
            return Optional.of(originalTime);
        } else {
            // Otherwise we need to guess the offset from unrelated fields
            final long smallestZone = 15 * MINUTE_IN_MILLIS;
            final long gpsTime = ExifUtils.getGpsDateTime(exif);
            if (gpsTime > 0) {
                final long offset = gpsTime - originalTime;
                if (Math.abs(offset) < 24 * HOUR_IN_MILLIS) {
                    final long rounded = Math.round((float) offset / smallestZone) * smallestZone;
                    return Optional.of(originalTime + rounded);
                }
            }
            if (lastModifiedTime > 0) {
                final long offset = lastModifiedTime - originalTime;
                if (Math.abs(offset) < 24 * HOUR_IN_MILLIS) {
                    final long rounded = Math.round((float) offset / smallestZone) * smallestZone;
                    return Optional.of(originalTime + rounded);
                }
            }
            return Optional.empty();
        }
    }

    @VisibleForTesting
    static @NonNull Optional<Integer> parseOptionalOrientation(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL: return Optional.of(0);
            case ExifInterface.ORIENTATION_ROTATE_90: return Optional.of(90);
            case ExifInterface.ORIENTATION_ROTATE_180: return Optional.of(180);
            case ExifInterface.ORIENTATION_ROTATE_270: return Optional.of(270);
            default: return Optional.empty();
        }
    }

    @VisibleForTesting
    static @NonNull Optional<String> parseOptionalVideoResolution(
            @NonNull MediaMetadataRetriever mmr) {
        final Optional<?> width = parseOptional(mmr.extractMetadata(METADATA_KEY_VIDEO_WIDTH));
        final Optional<?> height = parseOptional(mmr.extractMetadata(METADATA_KEY_VIDEO_HEIGHT));
        return parseOptionalResolution(width, height);
    }

    @VisibleForTesting
    static @NonNull Optional<String> parseOptionalImageResolution(
            @NonNull MediaMetadataRetriever mmr) {
        final Optional<?> width = parseOptional(mmr.extractMetadata(METADATA_KEY_IMAGE_WIDTH));
        final Optional<?> height = parseOptional(mmr.extractMetadata(METADATA_KEY_IMAGE_HEIGHT));
        return parseOptionalResolution(width, height);
    }

    @VisibleForTesting
    static @NonNull Optional<String> parseOptionalResolution(
            @NonNull ExifInterface exif) {
        final Optional<?> width = parseOptionalOrZero(
                exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH));
        final Optional<?> height = parseOptionalOrZero(
                exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH));
        return parseOptionalResolution(width, height);
    }

    private static @NonNull Optional<String> parseOptionalResolution(
            @NonNull Optional<?> width, @NonNull Optional<?> height) {
        if (width.isPresent() && height.isPresent()) {
            return Optional.of(width.get() + "\u00d7" + height.get());
        }
        return Optional.empty();
    }

    @VisibleForTesting
    static @NonNull Optional<Long> parseOptionalDate(@Nullable String date) {
        if (TextUtils.isEmpty(date)) return Optional.empty();
        try {
            synchronized (S_DATE_FORMAT_WITH_MILLIS) {
                return parseDateWithFormat(date, S_DATE_FORMAT_WITH_MILLIS);
            }
        } catch (ParseException e) {
            // Log and try without millis as well
            Log.d(TAG, String.format(
                    "Parsing date with millis failed for [%s]. We will retry without millis",
                    date));
        }
        try {
            synchronized (S_DATE_FORMAT) {
                return parseDateWithFormat(date, S_DATE_FORMAT);
            }
        } catch (ParseException e) {
            Log.d(TAG, String.format("Parsing date without millis failed for [%s]", date));
            return Optional.empty();
        }
    }

    private static Optional<Long> parseDateWithFormat(
            @Nullable String date, SimpleDateFormat dateFormat) throws ParseException {
        final long value = dateFormat.parse(date).getTime();
        return (value > 0) ? Optional.of(value) : Optional.empty();
    }

    @VisibleForTesting
    static @NonNull Optional<Integer> parseOptionalYear(@Nullable String value) {
        final Optional<String> parsedValue = parseOptional(value);
        if (parsedValue.isPresent()) {
            final Matcher m = PATTERN_YEAR.matcher(parsedValue.get());
            if (m.find()) {
                return Optional.of(Integer.parseInt(m.group(1)));
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    @VisibleForTesting
    static @NonNull Optional<Integer> parseOptionalTrack(
            @NonNull MediaMetadataRetriever mmr) {
        final Optional<Integer> disc = parseOptionalNumerator(
                mmr.extractMetadata(METADATA_KEY_DISC_NUMBER));
        final Optional<Integer> track = parseOptionalNumerator(
                mmr.extractMetadata(METADATA_KEY_CD_TRACK_NUMBER));
        if (disc.isPresent() && track.isPresent()) {
            return Optional.of((disc.get() * 1000) + track.get());
        } else {
            return track;
        }
    }

    /**
     * Maybe replace the MIME type from extension with the MIME type from the
     * refined metadata, but only when the top-level MIME type agrees.
     */
    @VisibleForTesting
    static @NonNull Optional<String> parseOptionalMimeType(@NonNull String fileMimeType,
            @Nullable String refinedMimeType) {
        // Ignore when missing
        if (TextUtils.isEmpty(refinedMimeType)) return Optional.empty();

        // Ignore when invalid
        final int refinedSplit = refinedMimeType.indexOf('/');
        if (refinedSplit == -1) return Optional.empty();

        if (fileMimeType.regionMatches(true, 0, refinedMimeType, 0, refinedSplit + 1)) {
            return Optional.of(refinedMimeType);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Return last modified time of given file. This value is typically read
     * from the given {@link BasicFileAttributes}, except in the case of
     * read-only partitions, where {@link Build#TIME} is used instead.
     */
    public static long lastModifiedTime(@NonNull File file,
            @NonNull BasicFileAttributes attrs) {
        if (FileUtils.contains(Environment.getStorageDirectory(), file)) {
            return attrs.lastModifiedTime().toMillis() / 1000;
        } else {
            return Build.TIME / 1000;
        }
    }

    /**
     * Test if any parents of given path should be scanned and test if any parents of given
     * path should be considered hidden.
     */
    static Pair<Boolean, Boolean> shouldScanPathAndIsPathHidden(@NonNull File dir) {
        Trace.beginSection("shouldScanPathAndIsPathHiodden");
        try {
            boolean isPathHidden = false;
            while (dir != null) {
                if (!shouldScanDirectory(dir)) {
                    // When the path is not scannable, we don't care if it's hidden or not.
                    return Pair.create(false, false);
                }
                isPathHidden = isPathHidden || FileUtils.isDirectoryHidden(dir);
                dir = dir.getParentFile();
            }
            return Pair.create(true, isPathHidden);
        } finally {
            Trace.endSection();
        }
    }

    @VisibleForTesting
    static boolean shouldScanDirectory(@NonNull File dir) {
        final File nomedia = new File(dir, ".nomedia");

        // Handle well-known paths that should always be visible or invisible,
        // regardless of .nomedia presence
        if (FileUtils.shouldBeVisible(dir.getAbsolutePath())) {
            // Well known paths can never be a hidden directory. Delete any non-standard nomedia
            // presence in well known path.
            nomedia.delete();
            return true;
        }

        if (FileUtils.shouldBeInvisible(dir.getAbsolutePath())) {
            // Create the .nomedia file in paths that are not scannable. This is useful when user
            // ejects the SD card and brings it to an older device and its media scanner can
            // now correctly identify these paths as not scannable.
            try {
                nomedia.createNewFile();
            } catch (IOException ignored) {
            }
            return false;
        }
        return true;
    }

    /**
     * @return {@link FileColumns#MEDIA_TYPE}, resolved based on the file path and given
     * {@code mimeType}.
     */
    private static int resolveMediaTypeFromFilePath(@NonNull File file, @NonNull String mimeType,
            boolean isHidden) {
        int mediaType = MimeUtils.resolveMediaType(mimeType);

        if (isHidden || FileUtils.isFileHidden(file)) {
            mediaType = FileColumns.MEDIA_TYPE_NONE;
        }
        if (mediaType == FileColumns.MEDIA_TYPE_IMAGE && isFileAlbumArt(file)) {
            mediaType = FileColumns.MEDIA_TYPE_NONE;
        }
        return mediaType;
    }

    @VisibleForTesting
    static boolean isFileAlbumArt(@NonNull File file) {
        return PATTERN_ALBUM_ART.matcher(file.getName()).matches();
    }

    static boolean isZero(@NonNull String value) {
        if (value.length() == 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    static void logTroubleScanning(@NonNull File file, @NonNull Exception e) {
        if (LOGW) Log.w(TAG, "Trouble scanning " + file + ": " + e);
    }
}
