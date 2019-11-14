/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.MediaStore.getVolumeName;
import static android.provider.MediaStore.Downloads.isDownload;

import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_SYSTEM;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_AUDIO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_IMAGES;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_VIDEO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_AUDIO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_IMAGES;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_VIDEO;
import static com.android.providers.media.util.FileUtils.extractDisplayName;
import static com.android.providers.media.util.FileUtils.extractFileName;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpActiveChangedListener;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.icu.util.ULocale;
import android.media.ExifInterface;
import android.media.MediaFile;
import android.media.ThumbnailUtils;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.RedactingFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.Column;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Audio.Playlists;
import android.provider.MediaStore.Downloads;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Size;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.BackgroundThread;
import com.android.providers.media.util.CachedSupplier;
import com.android.providers.media.util.DatabaseUtils;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.IsoInterface;
import com.android.providers.media.util.LongArray;
import com.android.providers.media.util.MimeUtils;
import com.android.providers.media.util.XmpInterface;

import com.google.common.hash.Hashing;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Media content provider. See {@link android.provider.MediaStore} for details.
 * Separate databases are kept for each external storage card we see (using the
 * card's ID as an index).  The content visible at content://media/external/...
 * changes with the card.
 */
public class MediaProvider extends ContentProvider {
    /**
     * Regex that matches any valid path in external storage,
     * and captures the top-level directory as the first group.
     */
    static final Pattern PATTERN_TOP_LEVEL_DIR = Pattern.compile(
            "(?i)^/storage/[^/]+/[0-9]+/?([^/]+)/.*");
    /**
     * Regex that matches paths in all well-known package-specific directories,
     * and which captures the package name as the first group.
     */
    static final Pattern PATTERN_OWNED_PATH = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|media|obb|sandbox)/([^/]+)/.*");

    /**
     * Regex that matches paths for {@link MediaColumns#RELATIVE_PATH}; it
     * captures both top-level paths and sandboxed paths.
     */
    static final Pattern PATTERN_RELATIVE_PATH = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?(Android/sandbox/([^/]+)/)?");

    /**
     * Regex that matches paths under well-known storage paths.
     */
    static final Pattern PATTERN_VOLUME_NAME = Pattern.compile(
            "(?i)^/storage/([^/]+)");

    /**
     * Regex of a selection string that matches a specific ID.
     */
    static final Pattern PATTERN_SELECTION_ID = Pattern.compile(
            "(?:image_id|video_id)\\s*=\\s*(\\d+)");

    /**
     * These directory names aren't declared in Environment as final variables, and so we need to
     * have the same values in separate final variables in order to have them considered constant
     * expressions.
     */
    private static final String DIRECTORY_MUSIC = "Music";
    private static final String DIRECTORY_PODCASTS = "Podcasts";
    private static final String DIRECTORY_RINGTONES = "Ringtones";
    private static final String DIRECTORY_ALARMS = "Alarms";
    private static final String DIRECTORY_NOTIFICATIONS = "Notifications";
    private static final String DIRECTORY_PICTURES = "Pictures";
    private static final String DIRECTORY_MOVIES = "Movies";
    private static final String DIRECTORY_DOWNLOADS = "Download";
    private static final String DIRECTORY_DCIM = "DCIM";
    private static final String DIRECTORY_DOCUMENTS = "Documents";
    private static final String DIRECTORY_AUDIOBOOKS = "Audiobooks";

    /**
     * Set of {@link Cursor} columns that refer to raw filesystem paths.
     */
    private static final ArrayMap<String, Object> sDataColumns = new ArrayMap<>();

    {
        sDataColumns.put(MediaStore.MediaColumns.DATA, null);
        sDataColumns.put(MediaStore.Images.Thumbnails.DATA, null);
        sDataColumns.put(MediaStore.Video.Thumbnails.DATA, null);
        sDataColumns.put(MediaStore.Audio.PlaylistsColumns.DATA, null);
        sDataColumns.put(MediaStore.Audio.AlbumColumns.ALBUM_ART, null);
    }

    private static final Object sCacheLock = new Object();

    @GuardedBy("sCacheLock")
    private static final List<VolumeInfo> sCachedVolumes = new ArrayList<>();
    @GuardedBy("sCacheLock")
    private static final Set<String> sCachedExternalVolumeNames = new ArraySet<>();
    @GuardedBy("sCacheLock")
    private static final Map<String, Collection<File>> sCachedVolumeScanPaths = new ArrayMap<>();

    static {
        System.loadLibrary("fuse_jni");
    }

    private void updateVolumes() {
        synchronized (sCacheLock) {
            sCachedVolumes.clear();
            sCachedVolumes.addAll(mStorageManager.getVolumes());

            sCachedExternalVolumeNames.clear();
            sCachedExternalVolumeNames.addAll(MediaStore.getExternalVolumeNames(getContext()));

            sCachedVolumeScanPaths.clear();
            try {
                sCachedVolumeScanPaths.put(MediaStore.VOLUME_INTERNAL,
                        MediaStore.getVolumeScanPaths(MediaStore.VOLUME_INTERNAL));
                for (String volumeName : sCachedExternalVolumeNames) {
                    sCachedVolumeScanPaths.put(volumeName,
                            MediaStore.getVolumeScanPaths(volumeName));
                }
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }
    }

    public static File getVolumePath(String volumeName) throws FileNotFoundException {
        // TODO(b/144275217): A more performant invocation is
        // MediaStore#getVolumePath(sCachedVolumes, volumeName) since we avoid a binder
        // to StorageManagerService to getVolumeList. We need to delay the mount broadcasts
        // from StorageManagerService so that sCachedVolumes is up to date in
        // onVolumeStateChanged before we to call this method, otherwise we would crash
        // when we don't find volumeName yet
        return MediaStore.getVolumePath(volumeName);
    }

    public static Set<String> getExternalVolumeNames() {
        synchronized (sCacheLock) {
            return new ArraySet<>(sCachedExternalVolumeNames);
        }
    }

    public static Collection<File> getVolumeScanPaths(String volumeName) {
        synchronized (sCacheLock) {
            return new ArrayList<>(sCachedVolumeScanPaths.get(volumeName));
        }
    }

    private StorageManager mStorageManager;
    private AppOpsManager mAppOpsManager;
    private PackageManager mPackageManager;

    private Size mThumbSize;

    /**
     * Map from UID to cached {@link LocalCallingIdentity}. Values are only
     * maintained in this map while the UID is actively working with a
     * performance-critical component, such as camera.
     */
    @GuardedBy("mCachedCallingIdentity")
    private final SparseArray<LocalCallingIdentity> mCachedCallingIdentity = new SparseArray<>();

    private final OnOpActiveChangedListener mActiveListener = (code, uid, packageName, active) -> {
        synchronized (mCachedCallingIdentity) {
            if (active) {
                // TODO moltmann: Set correct featureId
                mCachedCallingIdentity.put(uid,
                        LocalCallingIdentity.fromExternal(getContext(), uid, packageName,
                                null));
            } else {
                mCachedCallingIdentity.remove(uid);
            }
        }
    };

    /**
     * Calling identity state about on the current thread. Populated on demand,
     * and invalidated by {@link #onCallingPackageChanged()} when each remote
     * call is finished.
     */
    private final ThreadLocal<LocalCallingIdentity> mCallingIdentity = ThreadLocal
            .withInitial(() -> {
                synchronized (mCachedCallingIdentity) {
                    final LocalCallingIdentity cached = mCachedCallingIdentity
                            .get(Binder.getCallingUid());
                    return (cached != null) ? cached
                            : LocalCallingIdentity.fromBinder(getContext(), this);
                }
            });

    // In memory cache of path<->id mappings, to speed up inserts during media scan
    @GuardedBy("mDirectoryCache")
    private final ArrayMap<String, Long> mDirectoryCache = new ArrayMap<>();

    private static final String[] sMediaTableColumns = new String[] {
            FileColumns._ID,
            FileColumns.MEDIA_TYPE,
    };

    private static final String[] sDataOnlyColumn = new String[] {
        FileColumns.DATA
    };

    private static final String[] sPlaylistIdPlayOrder = new String[] {
        Playlists.Members.PLAYLIST_ID,
        Playlists.Members.PLAY_ORDER
    };

    private static final String ID_NOT_PARENT_CLAUSE =
            "_id NOT IN (SELECT parent FROM files)";

    private static final String CANONICAL = "canonical";

    private BroadcastReceiver mMediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final StorageVolume sv = intent.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
            if (sv != null) {
                final String volumeName;
                if (sv.isPrimary()) {
                    volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;
                } else {
                    try {
                        volumeName = MediaStore.checkArgumentVolumeName(sv.getNormalizedUuid());
                    } catch (IllegalArgumentException ignored) {
                        return;
                    }
                }

                switch (intent.getAction()) {
                    case Intent.ACTION_MEDIA_MOUNTED:
                        attachVolume(volumeName);
                        break;
                    case Intent.ACTION_MEDIA_UNMOUNTED:
                    case Intent.ACTION_MEDIA_EJECT:
                    case Intent.ACTION_MEDIA_REMOVED:
                    case Intent.ACTION_MEDIA_BAD_REMOVAL:
                        detachVolume(volumeName);
                        break;
                }
            }
        }
    };

    /**
     * Apply {@link Consumer#accept} to the given {@link Uri}.
     * <p>
     * Since media items can be exposed through multiple collections or views,
     * this method expands the single item being accepted to also accept all
     * relevant views.
     */
    public void acceptWithExpansion(Consumer<Uri> consumer, Uri uri) {
        final int match = matchUri(uri, true);
        acceptWithExpansionInternal(consumer, uri, match);

        try {
            // When targeting a specific volume, we need to expand to also
            // notify the top-level view
            final String volumeName = getVolumeName(uri);
            switch (volumeName) {
                case MediaStore.VOLUME_INTERNAL:
                case MediaStore.VOLUME_EXTERNAL:
                    // Already a top-level view, no need to expand
                    break;
                default:
                    final List<String> segments = new ArrayList<>(uri.getPathSegments());
                    segments.set(0, MediaStore.VOLUME_EXTERNAL);
                    final Uri.Builder builder = uri.buildUpon().path(null);
                    for (String segment : segments) {
                        builder.appendPath(segment);
                    }
                    acceptWithExpansionInternal(consumer, builder.build(), match);
                    break;
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void acceptWithExpansionInternal(Consumer<Uri> consumer, Uri uri, int match) {
        // Start by always notifying the base item
        consumer.accept(uri);

        // Some items can be exposed through multiple collections,
        // so we need to notify all possible views of those items
        switch (match) {
            case AUDIO_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID: {
                final String volumeName = getVolumeName(uri);
                final long id = ContentUris.parseId(uri);
                consumer.accept(Files.getContentUri(volumeName, id));
                consumer.accept(Downloads.getContentUri(volumeName, id));
                break;
            }
            case AUDIO_MEDIA:
            case VIDEO_MEDIA:
            case IMAGES_MEDIA: {
                final String volumeName = getVolumeName(uri);
                consumer.accept(Files.getContentUri(volumeName));
                consumer.accept(Downloads.getContentUri(volumeName));
                break;
            }
            case FILES_ID:
            case DOWNLOADS_ID: {
                final String volumeName = getVolumeName(uri);
                final long id = ContentUris.parseId(uri);
                consumer.accept(Audio.Media.getContentUri(volumeName, id));
                consumer.accept(Video.Media.getContentUri(volumeName, id));
                consumer.accept(Images.Media.getContentUri(volumeName, id));
                break;
            }
            case FILES:
            case DOWNLOADS: {
                final String volumeName = getVolumeName(uri);
                consumer.accept(Audio.Media.getContentUri(volumeName));
                consumer.accept(Video.Media.getContentUri(volumeName));
                consumer.accept(Images.Media.getContentUri(volumeName));
                break;
            }
        }

        // Any changing audio items mean we probably need to invalidate all
        // indexed views built from that media
        switch (match) {
            case AUDIO_MEDIA:
            case AUDIO_MEDIA_ID: {
                final String volumeName = getVolumeName(uri);
                consumer.accept(Audio.Genres.getContentUri(volumeName));
                consumer.accept(Audio.Playlists.getContentUri(volumeName));
                consumer.accept(Audio.Artists.getContentUri(volumeName));
                consumer.accept(Audio.Albums.getContentUri(volumeName));
                break;
            }
        }
    }

    private static final String[] sDefaultFolderNames = {
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_PODCASTS,
        Environment.DIRECTORY_RINGTONES,
        Environment.DIRECTORY_ALARMS,
        Environment.DIRECTORY_NOTIFICATIONS,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_MOVIES,
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DCIM,
    };

    /**
     * Ensure that default folders are created on mounted primary storage
     * devices. We only do this once per volume so we don't annoy the user if
     * deleted manually.
     */
    private void ensureDefaultFolders(String volumeName, DatabaseHelper helper, SQLiteDatabase db) {
        try {
            final File path = getVolumePath(volumeName);
            final StorageVolume vol = mStorageManager.getStorageVolume(path);
            final String key;
            if (vol == null || vol.isPrimary()) {
                key = "created_default_folders";
            } else {
                key = "created_default_folders_" + vol.getNormalizedUuid();
            }

            final SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getContext());
            if (prefs.getInt(key, 0) == 0) {
                for (String folderName : sDefaultFolderNames) {
                    final File folder = new File(vol.getPathFile(), folderName);
                    if (!folder.exists()) {
                        folder.mkdirs();
                        insertDirectory(helper, db, folder.getAbsolutePath());
                    }
                }

                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt(key, 1);
                editor.commit();
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to ensure default folders for " + volumeName, e);
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);

        Log.v(TAG, "Attached " + info.authority + " from " + info.applicationInfo.packageName);

        mLegacyProvider = Objects.equals(info.authority, MediaStore.AUTHORITY_LEGACY);
        mUriMatcher = new LocalUriMatcher(info.authority);
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        // Shift call statistics back to the original caller
        Binder.setProxyTransactListener(
                new Binder.PropagateWorkSourceTransactListener());

        mStorageManager = context.getSystemService(StorageManager.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();

        // Reasonable thumbnail size is half of the smallest screen edge width
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int thumbSize = Math.min(metrics.widthPixels, metrics.heightPixels) / 2;
        mThumbSize = new Size(thumbSize, thumbSize);

        mMediaScanner = new ModernMediaScanner(context);

        mInternalDatabase = new DatabaseHelper(context, INTERNAL_DATABASE_NAME,
                true, false, mLegacyProvider);
        mExternalDatabase = new DatabaseHelper(context, EXTERNAL_DATABASE_NAME,
                false, false, mLegacyProvider);

        final IntentFilter filter = new IntentFilter();
        filter.setPriority(10);
        filter.addDataScheme("file");
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        context.registerReceiver(mMediaReceiver, filter);

        // Watch for invalidation of cached volumes
        mStorageManager.registerListener(new StorageEventListener() {
            @Override
            public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
                updateVolumes();
            }
        });

        updateVolumes();
        attachVolume(MediaStore.VOLUME_INTERNAL);
        for (String volumeName : getExternalVolumeNames()) {
            attachVolume(volumeName);
        }

        // Watch for performance-sensitive activity
        mAppOpsManager.startWatchingActive(new String[] {
                AppOpsManager.OPSTR_CAMERA
        }, context.getMainExecutor(), mActiveListener);

        return true;
    }

    @Override
    public void onCallingPackageChanged() {
        // Identity of the current thread has changed, so invalidate caches
        mCallingIdentity.remove();
    }

    public LocalCallingIdentity clearLocalCallingIdentity() {
        return clearLocalCallingIdentity(LocalCallingIdentity.fromSelf(getContext()));
    }

    public LocalCallingIdentity clearLocalCallingIdentity(LocalCallingIdentity replacement) {
        final LocalCallingIdentity token = mCallingIdentity.get();
        mCallingIdentity.set(replacement);
        return token;
    }

    public void restoreLocalCallingIdentity(LocalCallingIdentity token) {
        mCallingIdentity.set(token);
    }

    public void onIdleMaintenance(@NonNull CancellationSignal signal) {
        final DatabaseHelper helper = mExternalDatabase;
        final SQLiteDatabase db = helper.getReadableDatabase();

        // Scan all volumes to resolve any staleness
        for (String volumeName : getExternalVolumeNames()) {
            // Possibly bail before digging into each volume
            signal.throwIfCanceled();

            try {
                final File file = getVolumePath(volumeName);
                MediaService.onScanVolume(getContext(), Uri.fromFile(file));
            } catch (IOException e) {
                Log.w(TAG, e);
            }
        }

        // Delete any stale thumbnails
        pruneThumbnails(signal);

        // Finished orphaning any content whose package no longer exists
        final ArraySet<String> unknownPackages = new ArraySet<>();
        try (Cursor c = db.query(true, "files", new String[] { "owner_package_name" },
                null, null, null, null, null, null, signal)) {
            while (c.moveToNext()) {
                final String packageName = c.getString(0);
                if (TextUtils.isEmpty(packageName)) continue;
                try {
                    getContext().getPackageManager().getPackageInfo(packageName,
                            PackageManager.MATCH_UNINSTALLED_PACKAGES);
                } catch (NameNotFoundException e) {
                    unknownPackages.add(packageName);
                }
            }
        }

        Log.d(TAG, "Found " + unknownPackages.size() + " unknown packages");
        for (String packageName : unknownPackages) {
            onPackageOrphaned(packageName);
        }

        // Delete any expired content; we're paranoid about wildly changing
        // clocks, so only delete items within the last week
        final long from = ((System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS) / 1000);
        final long to = (System.currentTimeMillis() / 1000);
        try (Cursor c = db.query(true, "files", new String[] { "volume_name", "_id" },
                FileColumns.DATE_EXPIRES + " BETWEEN " + from + " AND " + to, null,
                null, null, null, null, signal)) {
            while (c.moveToNext()) {
                final String volumeName = c.getString(0);
                final long id = c.getLong(1);
                delete(Files.getContentUri(volumeName, id), null, null);
            }
            Log.d(TAG, "Deleted " + c.getCount() + " expired items on " + helper.mName);
        }

        // Forget any stale volumes
        final long lastWeek = System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS;
        for (VolumeRecord rec : mStorageManager.getVolumeRecords()) {
            // Skip volumes without valid UUIDs
            if (TextUtils.isEmpty(rec.fsUuid)) continue;

            // Skip volumes that are currently mounted
            final VolumeInfo vol = mStorageManager.findVolumeByUuid(rec.fsUuid);
            if (vol != null && vol.isMountedReadable()) continue;

            if (rec.lastSeenMillis > 0 && rec.lastSeenMillis < lastWeek) {
                final int num = db.delete("files", FileColumns.VOLUME_NAME + "=?",
                        new String[] { rec.getNormalizedFsUuid() });
                Log.d(TAG, "Forgot " + num + " stale items from " + rec.fsUuid);
            }
        }

        synchronized (mDirectoryCache) {
            mDirectoryCache.clear();
        }
    }

    public void onPackageOrphaned(String packageName) {
        final DatabaseHelper helper = mExternalDatabase;
        final SQLiteDatabase db = helper.getWritableDatabase();

        final ContentValues values = new ContentValues();
        values.putNull(FileColumns.OWNER_PACKAGE_NAME);

        final int count = db.update("files", values,
                "owner_package_name=?", new String[] { packageName });
        if (count > 0) {
            Log.d(TAG, "Orphaned " + count + " items belonging to "
                    + packageName + " on " + helper.mName);
        }
    }

    public void scanDirectory(File file) {
        mMediaScanner.scanDirectory(file);
    }

    public Uri scanFile(File file) {
        return mMediaScanner.scanFile(file);
    }

    /**
     * Makes MediaScanner scan the given file.
     * @param file path of the file to be scanned
     * @return URI of the item corresponding to the file if it was successfully scanned and indexed,
     * null otherwise.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public Uri scanFile(String file) {
        return scanFile(new File(file));
    }

    private void enforceShellRestrictions() {
        final int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId == android.os.Process.SHELL_UID
                && getContext().getSystemService(UserManager.class)
                        .hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)) {
            throw new SecurityException(
                    "Shell user cannot access files for user " + UserHandle.myUserId());
        }
    }

    @Override
    protected int enforceReadPermissionInner(Uri uri, String callingPkg,
            @Nullable String featureId, IBinder callerToken) throws SecurityException {
        enforceShellRestrictions();
        return super.enforceReadPermissionInner(uri, callingPkg, featureId, callerToken);
    }

    @Override
    protected int enforceWritePermissionInner(Uri uri, String callingPkg,
            @Nullable String featureId, IBinder callerToken) throws SecurityException {
        enforceShellRestrictions();
        return super.enforceWritePermissionInner(uri, callingPkg, featureId, callerToken);
    }

    @VisibleForTesting
    void computeAudioLocalizedValues(ContentValues values) {
        try {
            final String title = values.getAsString(AudioColumns.TITLE);
            final String titleRes = values.getAsString(AudioColumns.TITLE_RESOURCE_URI);

            if (!TextUtils.isEmpty(titleRes)) {
                final String localized = getLocalizedTitle(titleRes);
                if (!TextUtils.isEmpty(localized)) {
                    values.put(AudioColumns.TITLE, localized);
                }
            } else {
                final String localized = getLocalizedTitle(title);
                if (!TextUtils.isEmpty(localized)) {
                    values.put(AudioColumns.TITLE, localized);
                    values.put(AudioColumns.TITLE_RESOURCE_URI, title);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to localize title", e);
        }
    }

    @VisibleForTesting
    static void computeAudioKeyValues(ContentValues values) {
        computeAudioKeyValue(values,
                AudioColumns.TITLE, AudioColumns.TITLE_KEY, null);
        computeAudioKeyValue(values,
                AudioColumns.ALBUM, AudioColumns.ALBUM_KEY, AudioColumns.ALBUM_ID);
        computeAudioKeyValue(values,
                AudioColumns.ARTIST, AudioColumns.ARTIST_KEY, AudioColumns.ARTIST_ID);
        computeAudioKeyValue(values,
                AudioColumns.GENRE, AudioColumns.GENRE_KEY, AudioColumns.GENRE_ID);
    }

    private static void computeAudioKeyValue(@NonNull ContentValues values, @NonNull String focus,
            @Nullable String focusKey, @Nullable String focusId) {
        if (focusKey != null) values.remove(focusKey);
        if (focusId != null) values.remove(focusId);

        final String value = values.getAsString(focus);
        if (TextUtils.isEmpty(value)) return;

        final String key = Audio.keyFor(value);
        if (key == null) return;

        if (focusKey != null) {
            values.put(focusKey, key);
        }
        if (focusId != null) {
            // Many apps break if we generate negative IDs, so trim off the
            // highest bit to ensure we're always unsigned
            final long id = Hashing.farmHashFingerprint64()
                    .hashString(key, StandardCharsets.UTF_8).asLong() & ~(1 << 63);
            values.put(focusId, id);
        }
    }

    @VisibleForTesting
    static void computeDataValues(ContentValues values) {
        // Worst case we have to assume no bucket details
        values.remove(ImageColumns.BUCKET_ID);
        values.remove(ImageColumns.BUCKET_DISPLAY_NAME);
        values.remove(ImageColumns.GROUP_ID);
        values.remove(ImageColumns.VOLUME_NAME);
        values.remove(ImageColumns.RELATIVE_PATH);

        final String data = values.getAsString(MediaColumns.DATA);
        if (TextUtils.isEmpty(data)) return;

        final File file = new File(data);
        final File fileLower = new File(data.toLowerCase(Locale.ROOT));

        values.put(ImageColumns.VOLUME_NAME, extractVolumeName(data));
        values.put(ImageColumns.RELATIVE_PATH, extractRelativePath(data));
        values.put(ImageColumns.DISPLAY_NAME, extractDisplayName(data));

        // Buckets are the parent directory
        final String parent = fileLower.getParent();
        if (parent != null) {
            values.put(ImageColumns.BUCKET_ID, parent.hashCode());
            // The relative path for files in the top directory is "/"
            if (!"/".equals(values.getAsString(ImageColumns.RELATIVE_PATH))) {
                values.put(ImageColumns.BUCKET_DISPLAY_NAME, file.getParentFile().getName());
            }
        }

        // Groups are the first part of name
        final String name = fileLower.getName();
        final int firstDot = name.indexOf('.');
        if (firstDot > 0) {
            values.put(ImageColumns.GROUP_ID,
                    name.substring(0, firstDot).hashCode());
        }
    }

    @Override
    public Uri canonicalize(Uri uri) {
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        // Skip when we have nothing to canonicalize
        if ("1".equals(uri.getQueryParameter(CANONICAL))) {
            return uri;
        }

        try (Cursor c = queryForSingleItem(uri, null, null, null, null)) {
            switch (match) {
                case AUDIO_MEDIA_ID: {
                    final String title = getDefaultTitleFromCursor(c);
                    if (!TextUtils.isEmpty(title)) {
                        final Uri.Builder builder = uri.buildUpon();
                        builder.appendQueryParameter(AudioColumns.TITLE, title);
                        builder.appendQueryParameter(CANONICAL, "1");
                        return builder.build();
                    }
                }
                case VIDEO_MEDIA_ID:
                case IMAGES_MEDIA_ID: {
                    final String documentId = c
                            .getString(c.getColumnIndexOrThrow(MediaColumns.DOCUMENT_ID));
                    if (!TextUtils.isEmpty(documentId)) {
                        final Uri.Builder builder = uri.buildUpon();
                        builder.appendQueryParameter(MediaColumns.DOCUMENT_ID, documentId);
                        builder.appendQueryParameter(CANONICAL, "1");
                        return builder.build();
                    }
                }
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, e.getMessage());
        }
        return null;
    }

    @Override
    public Uri uncanonicalize(Uri uri) {
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        // Skip when we have nothing to uncanonicalize
        if (!"1".equals(uri.getQueryParameter(CANONICAL))) {
            return uri;
        }

        // Extract values and then clear to avoid recursive lookups
        final String title = uri.getQueryParameter(AudioColumns.TITLE);
        final String documentId = uri.getQueryParameter(MediaColumns.DOCUMENT_ID);
        uri = uri.buildUpon().clearQuery().build();

        switch (match) {
            case AUDIO_MEDIA_ID: {
                // First check for an exact match
                try (Cursor c = queryForSingleItem(uri, null, null, null, null)) {
                    if (Objects.equals(title, getDefaultTitleFromCursor(c))) {
                        return uri;
                    }
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Trouble resolving " + uri + "; falling back to search: " + e);
                }

                // Otherwise fallback to searching
                final Uri baseUri = ContentUris.removeId(uri);
                try (Cursor c = queryForSingleItem(baseUri,
                        new String[] { BaseColumns._ID },
                        AudioColumns.TITLE + "=?", new String[] { title }, null)) {
                    return ContentUris.withAppendedId(baseUri, c.getLong(0));
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to resolve " + uri + ": " + e);
                    return null;
                }
            }
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID: {
                // First check for an exact match
                try (Cursor c = queryForSingleItem(uri, null, null, null, null)) {
                    if (Objects.equals(title, getDefaultTitleFromCursor(c))) {
                        return uri;
                    }
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Trouble resolving " + uri + "; falling back to search: " + e);
                }

                // Otherwise fallback to searching
                final Uri baseUri = ContentUris.removeId(uri);
                try (Cursor c = queryForSingleItem(baseUri,
                        new String[] { BaseColumns._ID },
                        MediaColumns.DOCUMENT_ID + "=?", new String[] { documentId }, null)) {
                    return ContentUris.withAppendedId(baseUri, c.getLong(0));
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to resolve " + uri + ": " + e);
                    return null;
                }
            }
        }

        return uri;
    }

    private Uri safeUncanonicalize(Uri uri) {
        Uri newUri = uncanonicalize(uri);
        if (newUri != null) {
            return newUri;
        }
        return uri;
    }

    @Override
    public int checkUriPermission(@NonNull Uri uri, int uid, @Intent.AccessUriMode int modeFlags) {
        final LocalCallingIdentity token = clearLocalCallingIdentity(
                LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            final boolean allowHidden = isCallingPackageAllowedHidden();
            final int table = matchUri(uri, allowHidden);

            final DatabaseHelper helper;
            final SQLiteDatabase db;
            try {
                helper = getDatabaseForUri(uri);
                db = helper.getReadableDatabase();
            } catch (VolumeNotFoundException e) {
                return PackageManager.PERMISSION_DENIED;
            }

            final int type;
            if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                type = TYPE_UPDATE;
            } else {
                type = TYPE_QUERY;
            }

            final SQLiteQueryBuilder qb = getQueryBuilder(type, uri, table, null);
            try (Cursor c = qb.query(db,
                    new String[] { BaseColumns._ID }, null, null, null, null, null)) {
                if (c.getCount() == 1) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        } finally {
            restoreLocalCallingIdentity(token);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return query(uri, projection,
                ContentResolver.createSqlQueryBundle(selection, selectionArgs, sortOrder), null);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
        Trace.beginSection("query");
        try {
            return queryInternal(uri, projection, queryArgs, signal);
        } catch (FallbackException e) {
            return e.translateForQuery(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private Cursor queryInternal(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal signal) throws FallbackException {
        String selection = null;
        String[] selectionArgs = null;
        String sortOrder = null;

        if (queryArgs != null) {
            selection = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
            selectionArgs = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            sortOrder = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
            if (sortOrder == null
                    && queryArgs.containsKey(ContentResolver.QUERY_ARG_SORT_COLUMNS)) {
                sortOrder = createSqlSortClause(queryArgs);
            }
        }

        uri = safeUncanonicalize(uri);

        final String volumeName = getVolumeName(uri);
        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int table = matchUri(uri, allowHidden);

        //Log.v(TAG, "query: uri="+uri+", selection="+selection);
        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (table == MEDIA_SCANNER) {
            // create a cursor to return volume currently being scanned by the media scanner
            MatrixCursor c = new MatrixCursor(new String[] {MediaStore.MEDIA_SCANNER_VOLUME});
            c.addRow(new String[] {mMediaScannerVolume});
            return c;
        }

        // Used temporarily (until we have unique media IDs) to get an identifier
        // for the current sd card, so that the music app doesn't have to use the
        // non-public getFatVolumeId method
        if (table == FS_ID) {
            MatrixCursor c = new MatrixCursor(new String[] {"fsid"});
            c.addRow(new Integer[] {mVolumeId});
            return c;
        }

        if (table == VERSION) {
            MatrixCursor c = new MatrixCursor(new String[] {"version"});
            c.addRow(new Integer[] {DatabaseHelper.getDatabaseVersion(getContext())});
            return c;
        }

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final SQLiteDatabase db = helper.getReadableDatabase();

        if (table == MTP_OBJECT_REFERENCES) {
            final int handle = Integer.parseInt(uri.getPathSegments().get(2));
            return getObjectReferences(helper, db, handle);
        }

        SQLiteQueryBuilder qb = getQueryBuilder(TYPE_QUERY, uri, table, queryArgs);
        String limit = uri.getQueryParameter(MediaStore.PARAM_LIMIT);
        String filter = uri.getQueryParameter("filter");
        String [] keywords = null;
        if (filter != null) {
            filter = Uri.decode(filter).trim();
            if (!TextUtils.isEmpty(filter)) {
                String [] searchWords = filter.split(" ");
                keywords = new String[searchWords.length];
                for (int i = 0; i < searchWords.length; i++) {
                    String key = MediaStore.Audio.keyFor(searchWords[i]);
                    key = key.replace("\\", "\\\\");
                    key = key.replace("%", "\\%");
                    key = key.replace("_", "\\_");
                    keywords[i] = key;
                }
            }
        }

        String keywordColumn = null;
        switch (table) {
            case AUDIO_MEDIA:
            case AUDIO_GENRES_ALL_MEMBERS:
            case AUDIO_GENRES_ID_MEMBERS:
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS:
                keywordColumn = MediaStore.Audio.Media.ARTIST_KEY +
                        "||" + MediaStore.Audio.Media.ALBUM_KEY +
                        "||" + MediaStore.Audio.Media.TITLE_KEY;
                break;
            case AUDIO_ARTISTS_ID_ALBUMS:
            case AUDIO_ALBUMS:
                keywordColumn = MediaStore.Audio.Media.ARTIST_KEY + "||"
                        + MediaStore.Audio.Media.ALBUM_KEY;
                break;
            case AUDIO_ARTISTS:
                keywordColumn = MediaStore.Audio.Media.ARTIST_KEY;
                break;
        }

        if (keywordColumn != null) {
            for (int i = 0; keywords != null && i < keywords.length; i++) {
                appendWhereStandalone(qb, keywordColumn + " LIKE ? ESCAPE '\\'",
                        "%" + keywords[i] + "%");
            }
        }

        String groupBy = null;
        if (getCallingPackageTargetSdkVersion() < Build.VERSION_CODES.Q) {
            // Some apps are abusing the "WHERE" clause by injecting "GROUP BY"
            // clauses; gracefully lift them out.
            final Pair<String, String> selectionAndGroupBy = recoverAbusiveGroupBy(
                    Pair.create(selection, groupBy));
            selection = selectionAndGroupBy.first;
            groupBy = selectionAndGroupBy.second;

            // Some apps are abusing the first column to inject "DISTINCT";
            // gracefully lift them out.
            if ((projection != null) && (projection.length > 0)
                    && projection[0].startsWith("DISTINCT ")) {
                projection[0] = projection[0].substring("DISTINCT ".length());
                qb.setDistinct(true);
            }

            // Some apps are generating thumbnails with getThumbnail(), but then
            // ignoring the returned Bitmap and querying the raw table; give
            // them a row with enough information to find the original image.
            if ((table == IMAGES_THUMBNAILS || table == VIDEO_THUMBNAILS)
                    && !TextUtils.isEmpty(selection)) {
                final Matcher matcher = PATTERN_SELECTION_ID.matcher(selection);
                if (matcher.matches()) {
                    final long id = Long.parseLong(matcher.group(1));

                    final Uri fullUri;
                    if (table == IMAGES_THUMBNAILS) {
                        fullUri = ContentUris.withAppendedId(
                                Images.Media.getContentUri(volumeName), id);
                    } else if (table == VIDEO_THUMBNAILS) {
                        fullUri = ContentUris.withAppendedId(
                                Video.Media.getContentUri(volumeName), id);
                    } else {
                        throw new IllegalArgumentException();
                    }

                    final MatrixCursor cursor = new MatrixCursor(projection);
                    try {
                        String data = null;
                        if (ContentResolver.DEPRECATE_DATA_COLUMNS) {
                            // Go through provider to escape sandbox
                            data = ContentResolver.translateDeprecatedDataPath(
                                    fullUri.buildUpon().appendPath("thumbnail").build());
                        } else {
                            // Go directly to thumbnail file on disk
                            data = ensureThumbnail(fullUri, signal).getAbsolutePath();
                        }
                        cursor.newRow().add(MediaColumns._ID, null)
                                .add(Images.Thumbnails.IMAGE_ID, id)
                                .add(Video.Thumbnails.VIDEO_ID, id)
                                .add(MediaColumns.DATA, data);
                    } catch (FileNotFoundException ignored) {
                        // Return empty cursor if we had thumbnail trouble
                    }
                    return cursor;
                }
            }
        }

        final String having = null;
        final Cursor c = qb.query(db, projection,
                selection, selectionArgs, groupBy, having, sortOrder, limit, signal);

        if (c != null) {
            ((AbstractCursor) c).setNotificationUris(getContext().getContentResolver(),
                    Arrays.asList(uri), UserHandle.myUserId(), false);
        }

        return c;
    }

    @Override
    public String getType(Uri url) {
        final int match = matchUri(url, true);
        switch (match) {
            case IMAGES_MEDIA_ID:
            case AUDIO_MEDIA_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
            case VIDEO_MEDIA_ID:
            case DOWNLOADS_ID:
            case FILES_ID:
                final LocalCallingIdentity token = clearLocalCallingIdentity();
                try (Cursor cursor = queryForSingleItem(url,
                        new String[] { MediaColumns.MIME_TYPE }, null, null, null)) {
                    return cursor.getString(0);
                } catch (FileNotFoundException e) {
                    throw new IllegalArgumentException(e.getMessage());
                } finally {
                     restoreLocalCallingIdentity(token);
                }

            case IMAGES_MEDIA:
            case IMAGES_THUMBNAILS:
                return Images.Media.CONTENT_TYPE;

            case AUDIO_ALBUMART_ID:
            case AUDIO_ALBUMART_FILE_ID:
            case IMAGES_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS_ID:
                return "image/jpeg";

            case AUDIO_MEDIA:
            case AUDIO_GENRES_ID_MEMBERS:
            case AUDIO_PLAYLISTS_ID_MEMBERS:
                return Audio.Media.CONTENT_TYPE;

            case AUDIO_GENRES:
            case AUDIO_MEDIA_ID_GENRES:
                return Audio.Genres.CONTENT_TYPE;
            case AUDIO_GENRES_ID:
            case AUDIO_MEDIA_ID_GENRES_ID:
                return Audio.Genres.ENTRY_CONTENT_TYPE;
            case AUDIO_PLAYLISTS:
            case AUDIO_MEDIA_ID_PLAYLISTS:
                return Audio.Playlists.CONTENT_TYPE;
            case AUDIO_PLAYLISTS_ID:
            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                return Audio.Playlists.ENTRY_CONTENT_TYPE;

            case VIDEO_MEDIA:
                return Video.Media.CONTENT_TYPE;
            case DOWNLOADS:
                return Downloads.CONTENT_TYPE;
        }
        throw new IllegalStateException("Unknown URL : " + url);
    }

    @VisibleForTesting
    static void ensureFileColumns(Uri uri, ContentValues values) throws VolumeArgumentException {
        final LocalUriMatcher matcher = new LocalUriMatcher(MediaStore.AUTHORITY);
        final int match = matcher.matchUri(uri, true);
        ensureNonUniqueFileColumns(match, uri, values, null /* currentPath */);
    }

    private static void ensureUniqueFileColumns(int match, Uri uri, ContentValues values)
            throws VolumeArgumentException {
        ensureFileColumns(match, uri, values, true, null /* currentPath */);
    }

    private static void ensureNonUniqueFileColumns(int match, Uri uri, ContentValues values,
            @Nullable String currentPath) throws VolumeArgumentException {
        ensureFileColumns(match, uri, values, false, currentPath);
    }

    /**
     * Get the various file-related {@link MediaColumns} in the given
     * {@link ContentValues} into sane condition. Also validates that defined
     * columns are valid for the given {@link Uri}, such as ensuring that only
     * {@code image/*} can be inserted into
     * {@link android.provider.MediaStore.Images}.
     */
    private static void ensureFileColumns(int match, Uri uri, ContentValues values,
            boolean makeUnique, @Nullable String currentPath) throws VolumeArgumentException {
        Trace.beginSection("ensureFileColumns");

        // Figure out defaults based on Uri being modified
        String defaultMimeType = ClipDescription.MIMETYPE_UNKNOWN;
        String defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
        String defaultSecondary = null;
        List<String> allowedPrimary = Arrays.asList(
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_DOCUMENTS);
        switch (match) {
            case AUDIO_MEDIA:
            case AUDIO_MEDIA_ID:
                defaultMimeType = "audio/mpeg";
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_ALARMS,
                        Environment.DIRECTORY_MUSIC,
                        Environment.DIRECTORY_NOTIFICATIONS,
                        Environment.DIRECTORY_PODCASTS,
                        Environment.DIRECTORY_RINGTONES);
                break;
            case VIDEO_MEDIA:
            case VIDEO_MEDIA_ID:
                defaultMimeType = "video/mp4";
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_DCIM,
                        Environment.DIRECTORY_MOVIES);
                break;
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
                defaultMimeType = "image/jpeg";
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_DCIM,
                        Environment.DIRECTORY_PICTURES);
                break;
            case AUDIO_ALBUMART:
            case AUDIO_ALBUMART_ID:
                defaultMimeType = "image/jpeg";
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = ".thumbnails";
                break;
            case VIDEO_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
                defaultMimeType = "image/jpeg";
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = ".thumbnails";
                break;
            case IMAGES_THUMBNAILS:
            case IMAGES_THUMBNAILS_ID:
                defaultMimeType = "image/jpeg";
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = ".thumbnails";
                break;
            case AUDIO_PLAYLISTS:
            case AUDIO_PLAYLISTS_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                allowedPrimary = Arrays.asList(defaultPrimary);
                break;
            case DOWNLOADS:
            case DOWNLOADS_ID:
                defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
                allowedPrimary = Arrays.asList(defaultPrimary);
                break;
            case FILES:
            case FILES_ID:
                // Use defaults above
                break;
            default:
                Log.w(TAG, "Unhandled location " + uri + "; assuming generic files");
                break;
        }

        final String resolvedVolumeName = resolveVolumeName(uri);

        if (TextUtils.isEmpty(values.getAsString(MediaColumns.DATA))
                && MediaStore.VOLUME_INTERNAL.equals(resolvedVolumeName)) {
            // TODO: promote this to top-level check
            throw new UnsupportedOperationException(
                    "Writing to internal storage is not supported.");
        }

        // Force values when raw path provided
        if (!TextUtils.isEmpty(values.getAsString(MediaColumns.DATA))) {
            final String data = values.getAsString(MediaColumns.DATA);

            if (TextUtils.isEmpty(values.getAsString(MediaColumns.DISPLAY_NAME))) {
                values.put(MediaColumns.DISPLAY_NAME, extractDisplayName(data));
            }
            if (TextUtils.isEmpty(values.getAsString(MediaColumns.MIME_TYPE))) {
                values.put(MediaColumns.MIME_TYPE, MimeUtils.resolveMimeType(new File(data)));
            }
        }

        // Give ourselves sane defaults when missing
        if (TextUtils.isEmpty(values.getAsString(MediaColumns.DISPLAY_NAME))) {
            values.put(MediaColumns.DISPLAY_NAME,
                    String.valueOf(System.currentTimeMillis()));
        }
        final Integer formatObject = values.getAsInteger(FileColumns.FORMAT);
        final int format = formatObject == null ? 0 : formatObject.intValue();
        if (format == MtpConstants.FORMAT_ASSOCIATION) {
            values.putNull(MediaColumns.MIME_TYPE);
        } else if (TextUtils.isEmpty(values.getAsString(MediaColumns.MIME_TYPE))) {
            values.put(MediaColumns.MIME_TYPE, defaultMimeType);
        }

        // Sanity check MIME type against table
        final String mimeType = values.getAsString(MediaColumns.MIME_TYPE);
        if (mimeType != null && !defaultMimeType.equals(ClipDescription.MIMETYPE_UNKNOWN)) {
            final String[] split = defaultMimeType.split("/");
            if (!mimeType.startsWith(split[0])) {
                throw new IllegalArgumentException(
                        "MIME type " + mimeType + " cannot be inserted into " + uri
                                + "; expected MIME type under " + split[0] + "/*");
            }
        }

        // Generate path when undefined
        if (TextUtils.isEmpty(values.getAsString(MediaColumns.DATA))) {
            if (TextUtils.isEmpty(values.getAsString(MediaColumns.RELATIVE_PATH))) {
                if (defaultPrimary != null) {
                    if (defaultSecondary != null) {
                        values.put(MediaColumns.RELATIVE_PATH,
                                defaultPrimary + '/' + defaultSecondary + '/');
                    } else {
                        values.put(MediaColumns.RELATIVE_PATH,
                                defaultPrimary + '/');
                    }
                }
            }

            final String[] relativePath = sanitizePath(
                    values.getAsString(MediaColumns.RELATIVE_PATH));
            final String displayName = sanitizeDisplayName(
                    values.getAsString(MediaColumns.DISPLAY_NAME));

            // Create result file
            File res;
            try {
                res = getVolumePath(resolvedVolumeName);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
            res = FileUtils.buildPath(res, relativePath);
            try {
                if (makeUnique) {
                    res = FileUtils.buildUniqueFile(res, mimeType, displayName);
                } else {
                    res = FileUtils.buildNonUniqueFile(res, mimeType, displayName);
                }
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(
                        "Failed to build unique file: " + res + " " + displayName + " " + mimeType);
            }

            // Check for shady looking paths

            // Require content live under specific directories, but allow in-place updates of
            // existing content that lives in the invalid directory.
            final String primary = relativePath[0];
            if (!res.getAbsolutePath().equals(currentPath) && !allowedPrimary.contains(primary)) {
                throw new IllegalArgumentException(
                        "Primary directory " + primary + " not allowed for " + uri
                                + "; allowed directories are " + allowedPrimary);
            }

            // Ensure all parent folders of result file exist
            res.getParentFile().mkdirs();
            if (!res.getParentFile().exists()) {
                throw new IllegalStateException("Failed to create directory: " + res);
            }
            values.put(MediaColumns.DATA, res.getAbsolutePath());
        } else {
            assertFileColumnsSane(match, uri, values);
        }

        // Drop columns that aren't relevant for special tables
        switch (match) {
            case AUDIO_ALBUMART:
            case VIDEO_THUMBNAILS:
            case IMAGES_THUMBNAILS:
            case AUDIO_PLAYLISTS:
                values.remove(MediaColumns.DISPLAY_NAME);
                values.remove(MediaColumns.MIME_TYPE);
                break;
        }

        Trace.endSection();
    }

    private static @NonNull String[] sanitizePath(@Nullable String path) {
        if (path == null) {
            return new String[0];
        } else {
            final String[] segments = path.split("/");
            // If the path corresponds to the top level directory, then we return an empty path
            // which denotes the top level directory
            if (segments.length == 0) {
                return new String[] { "" };
            }
            for (int i = 0; i < segments.length; i++) {
                segments[i] = sanitizeDisplayName(segments[i]);
            }
            return segments;
        }
    }

    private static @Nullable String sanitizeDisplayName(@Nullable String name) {
        if (name == null) {
            return null;
        } else if (name.startsWith(".")) {
            // The resulting file must not be hidden.
            return FileUtils.buildValidFatFilename("_" + name);
        } else {
            return FileUtils.buildValidFatFilename(name);
        }
    }

    /**
     * Sanity check that any requested {@link MediaColumns#DATA} paths actually
     * live on the storage volume being targeted.
     */
    private static void assertFileColumnsSane(int match, Uri uri, ContentValues values)
            throws VolumeArgumentException {
        if (!values.containsKey(MediaColumns.DATA)) return;
        try {
            // Sanity check that the requested path actually lives on volume
            final String volumeName = resolveVolumeName(uri);
            final Collection<File> allowed = getVolumeScanPaths(volumeName);
            final File actual = new File(values.getAsString(MediaColumns.DATA))
                    .getCanonicalFile();
            if (!FileUtils.contains(allowed, actual)) {
                throw new VolumeArgumentException(actual, allowed);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues values[]) {
        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        if (match == VOLUMES) {
            return super.bulkInsert(uri, values);
        }

        final DatabaseHelper helper;
        final SQLiteDatabase db;
        try {
            helper = getDatabaseForUri(uri);
            db = helper.getWritableDatabase();
        } catch (VolumeNotFoundException e) {
            return e.translateForUpdateDelete(targetSdkVersion);
        }

        if (match == MTP_OBJECT_REFERENCES) {
            int handle = Integer.parseInt(uri.getPathSegments().get(2));
            return setObjectReferences(helper, db, handle, values);
        }

        helper.beginTransaction();
        try {
            final int result = super.bulkInsert(uri, values);
            helper.setTransactionSuccessful();
            return result;
        } finally {
            helper.endTransaction();
        }
    }

    private int playlistBulkInsert(SQLiteDatabase db, Uri uri, ContentValues values[]) {
        android.database.DatabaseUtils.InsertHelper helper =
            new android.database.DatabaseUtils.InsertHelper(db, "audio_playlists_map");
        int audioidcolidx = helper.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);
        int playlistididx = helper.getColumnIndex(Audio.Playlists.Members.PLAYLIST_ID);
        int playorderidx = helper.getColumnIndex(MediaStore.Audio.Playlists.Members.PLAY_ORDER);
        long playlistId = Long.parseLong(uri.getPathSegments().get(3));

        db.beginTransaction();
        int numInserted = 0;
        try {
            int len = values.length;
            for (int i = 0; i < len; i++) {
                helper.prepareForInsert();
                // getting the raw Object and converting it long ourselves saves
                // an allocation (the alternative is ContentValues.getAsLong, which
                // returns a Long object)
                long audioid = ((Number) values[i].get(
                        MediaStore.Audio.Playlists.Members.AUDIO_ID)).longValue();
                helper.bind(audioidcolidx, audioid);
                helper.bind(playlistididx, playlistId);
                // convert to int ourselves to save an allocation.
                int playorder = ((Number) values[i].get(
                        MediaStore.Audio.Playlists.Members.PLAY_ORDER)).intValue();
                helper.bind(playorderidx, playorder);
                helper.execute();
            }
            numInserted = len;
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            helper.close();
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return numInserted;
    }

    private long insertDirectory(DatabaseHelper helper, SQLiteDatabase db, String path) {
        if (LOCAL_LOGV) Log.v(TAG, "inserting directory " + path);
        ContentValues values = new ContentValues();
        values.put(FileColumns.FORMAT, MtpConstants.FORMAT_ASSOCIATION);
        values.put(FileColumns.DATA, path);
        values.put(FileColumns.PARENT, getParent(helper, db, path));
        values.put(FileColumns.OWNER_PACKAGE_NAME, extractPathOwnerPackageName(path));
        values.put(FileColumns.VOLUME_NAME, extractVolumeName(path));
        values.put(FileColumns.RELATIVE_PATH, extractRelativePath(path));
        values.put(FileColumns.DISPLAY_NAME, extractDisplayName(path));
        values.put(FileColumns.IS_DOWNLOAD, isDownload(path));
        File file = new File(path);
        if (file.exists()) {
            values.put(FileColumns.DATE_MODIFIED, file.lastModified() / 1000);
        }
        long rowId = db.insert("files", FileColumns.DATE_MODIFIED, values);
        return rowId;
    }

    private static @Nullable String extractVolumeName(@Nullable String data) {
        if (data == null) return null;
        final Matcher matcher = PATTERN_VOLUME_NAME.matcher(data);
        if (matcher.find()) {
            final String volumeName = matcher.group(1);
            if (volumeName.equals("emulated")) {
                return MediaStore.VOLUME_EXTERNAL_PRIMARY;
            } else {
                return StorageVolume.normalizeUuid(volumeName);
            }
        } else {
            return MediaStore.VOLUME_INTERNAL;
        }
    }

    private static @Nullable String extractRelativePath(@Nullable String data) {
        if (data == null) return null;
        final Matcher matcher = PATTERN_RELATIVE_PATH.matcher(data);
        if (matcher.find()) {
            final int lastSlash = data.lastIndexOf('/');
            if (lastSlash == -1 || lastSlash < matcher.end()) {
                // This is a file in the top-level directory, so relative path is "/"
                // which is different than null, which means unknown path
                return "/";
            } else {
                return data.substring(matcher.end(), lastSlash + 1);
            }
        } else {
            return null;
        }
    }

    private long getParent(DatabaseHelper helper, SQLiteDatabase db, String path) {
        final String parentPath = new File(path).getParent();
        if (Objects.equals("/", parentPath)) {
            return -1;
        } else {
            synchronized (mDirectoryCache) {
                Long id = mDirectoryCache.get(parentPath);
                if (id != null) {
                    return id;
                }
            }

            final long id;
            try (Cursor c = db.query("files", new String[] { FileColumns._ID },
                    FileColumns.DATA + "=?", new String[] { parentPath }, null, null, null)) {
                if (c.moveToFirst()) {
                    id = c.getLong(0);
                } else {
                    id = insertDirectory(helper, db, parentPath);
                }
            }

            synchronized (mDirectoryCache) {
                mDirectoryCache.put(parentPath, id);
            }
            return id;
        }
    }

    /**
     * @param c the Cursor whose title to retrieve
     * @return the result of {@link #getDefaultTitle(String)} if the result is valid; otherwise
     * the value of the {@code MediaStore.Audio.Media.TITLE} column
     */
    private String getDefaultTitleFromCursor(Cursor c) {
        String title = null;
        final int columnIndex = c.getColumnIndex("title_resource_uri");
        // Necessary to check for existence because we may be reading from an old DB version
        if (columnIndex > -1) {
            final String titleResourceUri = c.getString(columnIndex);
            if (titleResourceUri != null) {
                try {
                    title = getDefaultTitle(titleResourceUri);
                } catch (Exception e) {
                    // Best attempt only
                }
            }
        }
        if (title == null) {
            title = c.getString(c.getColumnIndex(MediaStore.Audio.Media.TITLE));
        }
        return title;
    }

    /**
     * @param title_resource_uri The title resource for which to retrieve the default localization
     * @return The title localized to {@code Locale.US}, or {@code null} if unlocalizable
     * @throws Exception Thrown if the title appears to be localizable, but the localization failed
     * for any reason. For example, the application from which the localized title is fetched is not
     * installed, or it does not have the resource which needs to be localized
     */
    private String getDefaultTitle(String title_resource_uri) throws Exception{
        try {
            return getTitleFromResourceUri(title_resource_uri, false);
        } catch (Exception e) {
            Log.e(TAG, "Error getting default title for " + title_resource_uri, e);
            throw e;
        }
    }

    /**
     * @param title_resource_uri The title resource to localize
     * @return The localized title, or {@code null} if unlocalizable
     * @throws Exception Thrown if the title appears to be localizable, but the localization failed
     * for any reason. For example, the application from which the localized title is fetched is not
     * installed, or it does not have the resource which needs to be localized
     */
    private String getLocalizedTitle(String title_resource_uri) throws Exception {
        try {
            return getTitleFromResourceUri(title_resource_uri, true);
        } catch (Exception e) {
            Log.e(TAG, "Error getting localized title for " + title_resource_uri, e);
            throw e;
        }
    }

    /**
     * Localizable titles conform to this URI pattern:
     *   Scheme: {@link ContentResolver.SCHEME_ANDROID_RESOURCE}
     *   Authority: Package Name of ringtone title provider
     *   First Path Segment: Type of resource (must be "string")
     *   Second Path Segment: Resource name of title
     *
     * @param title_resource_uri The title resource to retrieve
     * @param localize Whether or not to localize the title
     * @return The title, or {@code null} if unlocalizable
     * @throws Exception Thrown if the title appears to be localizable, but the localization failed
     * for any reason. For example, the application from which the localized title is fetched is not
     * installed, or it does not have the resource which needs to be localized
     */
    private String getTitleFromResourceUri(String title_resource_uri, boolean localize)
        throws Exception {
        if (TextUtils.isEmpty(title_resource_uri)) {
            return null;
        }
        final Uri titleUri = Uri.parse(title_resource_uri);
        final String scheme = titleUri.getScheme();
        if (!ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            return null;
        }
        final List<String> pathSegments = titleUri.getPathSegments();
        if (pathSegments.size() != 2) {
            Log.e(TAG, "Error getting localized title for " + title_resource_uri
                + ", must have 2 path segments");
            return null;
        }
        final String type = pathSegments.get(0);
        if (!"string".equals(type)) {
            Log.e(TAG, "Error getting localized title for " + title_resource_uri
                + ", first path segment must be \"string\"");
            return null;
        }
        final String packageName = titleUri.getAuthority();
        final Resources resources;
        if (localize) {
            resources = mPackageManager.getResourcesForApplication(packageName);
        } else {
            final Context packageContext = getContext().createPackageContext(packageName, 0);
            final Configuration configuration = packageContext.getResources().getConfiguration();
            configuration.setLocale(Locale.US);
            resources = packageContext.createConfigurationContext(configuration).getResources();
        }
        final String resourceIdentifier = pathSegments.get(1);
        final int id = resources.getIdentifier(resourceIdentifier, type, packageName);
        return resources.getString(id);
    }

    public void onLocaleChanged() {
        localizeTitles();
    }

    private void localizeTitles() {
        final DatabaseHelper helper = mInternalDatabase;
        final SQLiteDatabase db = helper.getWritableDatabase();

        try (Cursor c = db.query("files", new String[]{"_id", "title_resource_uri"},
            "title_resource_uri IS NOT NULL", null, null, null, null)) {
            while (c.moveToNext()) {
                final String id = c.getString(0);
                final String titleResourceUri = c.getString(1);
                final ContentValues values = new ContentValues();
                try {
                    values.put(AudioColumns.TITLE_RESOURCE_URI, titleResourceUri);
                    computeAudioLocalizedValues(values);
                    computeAudioKeyValues(values);
                    db.update("files", values, "_id=?", new String[]{id});
                } catch (Exception e) {
                    Log.e(TAG, "Error updating localized title for " + titleResourceUri
                        + ", keeping old localization");
                }
            }
        }
    }

    private long insertFile(DatabaseHelper helper, int match, Uri uri, ContentValues values,
            int mediaType, boolean notify) {
        final SQLiteDatabase db = helper.getWritableDatabase();

        boolean wasPathEmpty = !values.containsKey(MediaStore.MediaColumns.DATA)
                || TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DATA));

        // Make sure all file-related columns are defined
        try {
            ensureUniqueFileColumns(match, uri, values);
        } catch (VolumeArgumentException e) {
            if (getCallingPackageTargetSdkVersion() >= Build.VERSION_CODES.Q) {
                throw new IllegalArgumentException(e.getMessage());
            } else {
                Log.w(TAG, e.getMessage());
                return 0;
            }
        }

        switch (mediaType) {
            case FileColumns.MEDIA_TYPE_AUDIO: {
                computeAudioLocalizedValues(values);
                computeAudioKeyValues(values);
                break;
            }
        }

        // compute bucket_id and bucket_display_name for all files
        String path = values.getAsString(MediaStore.MediaColumns.DATA);
        computeDataValues(values);
        values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);

        String title = values.getAsString(MediaStore.MediaColumns.TITLE);
        if (title == null && path != null) {
            title = extractFileName(path);
        }
        values.put(FileColumns.TITLE, title);

        String mimeType = null;
        int format = MtpConstants.FORMAT_ASSOCIATION;
        if (path != null && new File(path).isDirectory()) {
            values.put(FileColumns.FORMAT, MtpConstants.FORMAT_ASSOCIATION);
            values.putNull(MediaStore.MediaColumns.MIME_TYPE);
        } else {
            mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
            final Integer formatObject = values.getAsInteger(FileColumns.FORMAT);
            format = (formatObject == null ? 0 : formatObject.intValue());
        }

        if (format == 0) {
            if (TextUtils.isEmpty(path) || wasPathEmpty) {
                // special case device created playlists
                if (mediaType == FileColumns.MEDIA_TYPE_PLAYLIST) {
                    values.put(FileColumns.FORMAT, MtpConstants.FORMAT_ABSTRACT_AV_PLAYLIST);
                    // create a file path for the benefit of MTP
                    path = Environment.getExternalStorageDirectory()
                            + "/Playlists/" + values.getAsString(Audio.Playlists.NAME);
                    values.put(MediaStore.MediaColumns.DATA, path);
                    values.put(FileColumns.PARENT, 0);
                }
            } else {
                format = MediaFile.getFormatCode(path, mimeType);
            }
        }
        if (path != null && path.endsWith("/")) {
            Log.e(TAG, "directory has trailing slash: " + path);
            return 0;
        }
        if (format != 0) {
            values.put(FileColumns.FORMAT, format);
            if (mimeType == null && format != MtpConstants.FORMAT_ASSOCIATION) {
                mimeType = MediaFile.getMimeTypeForFormatCode(format);
            }
        }

        if (mimeType == null && path != null && format != MtpConstants.FORMAT_ASSOCIATION) {
            mimeType = MimeUtils.resolveMimeType(new File(path));
        }

        if (mimeType != null) {
            values.put(FileColumns.MIME_TYPE, mimeType);

            // If 'values' contained the media type, then the caller wants us
            // to use that exact type, so don't override it based on mimetype
            if (!values.containsKey(FileColumns.MEDIA_TYPE) &&
                    mediaType == FileColumns.MEDIA_TYPE_NONE) {
                if (MimeUtils.isAudioMimeType(mimeType)) {
                    mediaType = FileColumns.MEDIA_TYPE_AUDIO;
                } else if (MimeUtils.isVideoMimeType(mimeType)) {
                    mediaType = FileColumns.MEDIA_TYPE_VIDEO;
                } else if (MimeUtils.isImageMimeType(mimeType)) {
                    mediaType = FileColumns.MEDIA_TYPE_IMAGE;
                } else if (MimeUtils.isPlayListMimeType(mimeType)) {
                    mediaType = FileColumns.MEDIA_TYPE_PLAYLIST;
                }
            }
        }
        values.put(FileColumns.MEDIA_TYPE, mediaType);

        final long rowId;
        {
            if (mediaType == FileColumns.MEDIA_TYPE_PLAYLIST) {
                String name = values.getAsString(Audio.Playlists.NAME);
                if (name == null && path == null) {
                    // MediaScanner will compute the name from the path if we have one
                    throw new IllegalArgumentException(
                            "no name was provided when inserting abstract playlist");
                }
            } else {
                if (path == null) {
                    // path might be null for playlists created on the device
                    // or transfered via MTP
                    throw new IllegalArgumentException(
                            "no path was provided when inserting new file");
                }
            }

            // make sure modification date and size are set
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    values.put(FileColumns.DATE_MODIFIED, file.lastModified() / 1000);
                    if (!values.containsKey(FileColumns.SIZE)) {
                        values.put(FileColumns.SIZE, file.length());
                    }
                }
            }

            Long parent = values.getAsLong(FileColumns.PARENT);
            if (parent == null) {
                if (path != null) {
                    long parentId = getParent(helper, db, path);
                    values.put(FileColumns.PARENT, parentId);
                }
            }

            rowId = db.insert("files", FileColumns.DATE_MODIFIED, values);
        }
        if (format == MtpConstants.FORMAT_ASSOCIATION) {
            synchronized (mDirectoryCache) {
                mDirectoryCache.put(path, rowId);
            }
        }

        return rowId;
    }

    private Cursor getObjectReferences(DatabaseHelper helper, SQLiteDatabase db, int handle) {
        Cursor c = db.query("files", sMediaTableColumns, "_id=?",
                new String[] {  Integer.toString(handle) },
                null, null, null);
        try {
            if (c != null && c.moveToNext()) {
                long playlistId = c.getLong(0);
                int mediaType = c.getInt(1);
                if (mediaType != FileColumns.MEDIA_TYPE_PLAYLIST) {
                    // we only support object references for playlist objects
                    return null;
                }
                return db.rawQuery(OBJECT_REFERENCES_QUERY,
                        new String[] { Long.toString(playlistId) } );
            }
        } finally {
            FileUtils.closeQuietly(c);
        }
        return null;
    }

    private int setObjectReferences(DatabaseHelper helper, SQLiteDatabase db,
            int handle, ContentValues values[]) {
        // first look up the media table and media ID for the object
        long playlistId = 0;
        Cursor c = db.query("files", sMediaTableColumns, "_id=?",
                new String[] {  Integer.toString(handle) },
                null, null, null);
        try {
            if (c != null && c.moveToNext()) {
                int mediaType = c.getInt(1);
                if (mediaType != FileColumns.MEDIA_TYPE_PLAYLIST) {
                    // we only support object references for playlist objects
                    return 0;
                }
                playlistId = c.getLong(0);
            }
        } finally {
            FileUtils.closeQuietly(c);
        }
        if (playlistId == 0) {
            return 0;
        }

        // next delete any existing entries
        db.delete("audio_playlists_map", "playlist_id=?",
                new String[] { Long.toString(playlistId) });

        // finally add the new entries
        int count = values.length;
        int added = 0;
        ContentValues[] valuesList = new ContentValues[count];
        for (int i = 0; i < count; i++) {
            // convert object ID to audio ID
            long audioId = 0;
            long objectId = values[i].getAsLong(MediaStore.MediaColumns._ID);
            c = db.query("files", sMediaTableColumns, "_id=?",
                    new String[] {  Long.toString(objectId) },
                    null, null, null);
            try {
                if (c != null && c.moveToNext()) {
                    int mediaType = c.getInt(1);
                    if (mediaType != FileColumns.MEDIA_TYPE_AUDIO) {
                        // we only allow audio files in playlists, so skip
                        continue;
                    }
                    audioId = c.getLong(0);
                }
            } finally {
                FileUtils.closeQuietly(c);
            }
            if (audioId != 0) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Playlists.Members.PLAYLIST_ID, playlistId);
                v.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);
                v.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, added);
                valuesList[added++] = v;
            }
        }
        if (added < count) {
            // we weren't able to find everything on the list, so lets resize the array
            // and pass what we have.
            ContentValues[] newValues = new ContentValues[added];
            System.arraycopy(valuesList, 0, newValues, 0, added);
            valuesList = newValues;
        }

        int rowsChanged = playlistBulkInsert(db,
                Audio.Playlists.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId),
                valuesList);

        if (rowsChanged > 0) {
            updatePlaylistDateModifiedToNow(db, playlistId);
        }

        return rowsChanged;
    }

    @VisibleForTesting
    static @Nullable String extractPathOwnerPackageName(@Nullable String path) {
        if (path == null) return null;
        final Matcher m = PATTERN_OWNED_PATH.matcher(path);
        if (m.matches()) {
            return m.group(1);
        } else {
            return null;
        }
    }

    private void maybePut(@NonNull ContentValues values, @NonNull String key,
            @Nullable String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private boolean maybeMarkAsDownload(@NonNull ContentValues values) {
        final String path = values.getAsString(MediaColumns.DATA);
        if (path != null && isDownload(path)) {
            values.put(FileColumns.IS_DOWNLOAD, true);
            return true;
        }
        return false;
    }

    private static @NonNull String resolveVolumeName(@NonNull Uri uri) {
        final String volumeName = getVolumeName(uri);
        if (MediaStore.VOLUME_EXTERNAL.equals(volumeName)) {
            return MediaStore.VOLUME_EXTERNAL_PRIMARY;
        } else {
            return volumeName;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        Trace.beginSection("insert");
        try {
            return insertInternal(uri, initialValues);
        } catch (FallbackException e) {
            return e.translateForInsert(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private Uri insertInternal(Uri uri, ContentValues initialValues) throws FallbackException {
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final String originalVolumeName = getVolumeName(uri);
        final String resolvedVolumeName = resolveVolumeName(uri);

        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (match == MEDIA_SCANNER) {
            mMediaScannerVolume = initialValues.getAsString(MediaStore.MEDIA_SCANNER_VOLUME);

            final DatabaseHelper helper = getDatabaseForUri(
                    MediaStore.Files.getContentUri(mMediaScannerVolume));

            helper.mScanStartTime = SystemClock.elapsedRealtime();
            return MediaStore.getMediaScannerUri();
        }

        if (match == VOLUMES) {
            String name = initialValues.getAsString("name");
            Uri attachedVolume = attachVolume(name);
            if (mMediaScannerVolume != null && mMediaScannerVolume.equals(name)) {
                final DatabaseHelper helper = getDatabaseForUri(
                        MediaStore.Files.getContentUri(mMediaScannerVolume));
                helper.mScanStartTime = SystemClock.elapsedRealtime();
            }
            return attachedVolume;
        }

        String path = null;
        String ownerPackageName = null;
        if (initialValues != null) {
            // IDs are forever; nobody should be editing them
            initialValues.remove(MediaColumns._ID);

            // Ignore or augment incoming raw filesystem paths
            for (String column : sDataColumns.keySet()) {
                if (!initialValues.containsKey(column)) continue;

                if (isCallingPackageSystem() || isCallingPackageLegacy()) {
                    // Mutation allowed
                } else {
                    Log.w(TAG, "Ignoring mutation of  " + column + " from "
                            + getCallingPackageOrSelf());
                    initialValues.remove(column);
                }
            }

            path = initialValues.getAsString(MediaStore.MediaColumns.DATA);

            if (!isCallingPackageSystem()) {
                initialValues.remove(FileColumns.IS_DOWNLOAD);
            }

            // We no longer track location metadata
            if (initialValues.containsKey(ImageColumns.LATITUDE)) {
                initialValues.putNull(ImageColumns.LATITUDE);
            }
            if (initialValues.containsKey(ImageColumns.LONGITUDE)) {
                initialValues.putNull(ImageColumns.LONGITUDE);
            }

            if (isCallingPackageSystem()) {
                // When media inserted by ourselves, the best we can do is guess
                // ownership based on path.
                ownerPackageName = initialValues.getAsString(FileColumns.OWNER_PACKAGE_NAME);
                if (TextUtils.isEmpty(ownerPackageName)) {
                    ownerPackageName = extractPathOwnerPackageName(path);
                }
            } else {
                // Remote callers have no direct control over owner column; we force
                // it be whoever is creating the content.
                initialValues.remove(FileColumns.OWNER_PACKAGE_NAME);
                ownerPackageName = getCallingPackageOrSelf();
            }
        }

        long rowId = -1;
        Uri newUri = null;

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final SQLiteDatabase db = helper.getWritableDatabase();

        switch (match) {
            case IMAGES_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(helper, match, uri, initialValues,
                        FileColumns.MEDIA_TYPE_IMAGE, true);
                if (rowId > 0) {
                    MediaDocumentsProvider.onMediaStoreInsert(
                            getContext(), resolvedVolumeName, FileColumns.MEDIA_TYPE_IMAGE, rowId);
                    newUri = ContentUris.withAppendedId(
                            Images.Media.getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case IMAGES_THUMBNAILS: {
                if (helper.mInternal) {
                    throw new UnsupportedOperationException(
                            "Writing to internal storage is not supported.");
                }

                // Require that caller has write access to underlying media
                final long imageId = initialValues.getAsLong(MediaStore.Images.Thumbnails.IMAGE_ID);
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Images.Media.getContentUri(resolvedVolumeName), imageId), true);

                ensureUniqueFileColumns(match, uri, initialValues);

                rowId = db.insert("thumbnails", "name", initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Images.Thumbnails.
                            getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case VIDEO_THUMBNAILS: {
                if (helper.mInternal) {
                    throw new UnsupportedOperationException(
                            "Writing to internal storage is not supported.");
                }

                // Require that caller has write access to underlying media
                final long videoId = initialValues.getAsLong(MediaStore.Video.Thumbnails.VIDEO_ID);
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Video.Media.getContentUri(resolvedVolumeName), videoId), true);

                ensureUniqueFileColumns(match, uri, initialValues);

                rowId = db.insert("videothumbnails", "name", initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Video.Thumbnails.
                            getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case AUDIO_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(helper, match, uri, initialValues,
                        FileColumns.MEDIA_TYPE_AUDIO, true);
                if (rowId > 0) {
                    MediaDocumentsProvider.onMediaStoreInsert(
                            getContext(), resolvedVolumeName, FileColumns.MEDIA_TYPE_AUDIO, rowId);
                    newUri = ContentUris.withAppendedId(
                            Audio.Media.getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case AUDIO_MEDIA_ID_GENRES: {
                throw new FallbackException("Genres are read-only", Build.VERSION_CODES.R);
            }

            case AUDIO_MEDIA_ID_PLAYLISTS: {
                // Require that caller has write access to underlying media
                final long audioId = Long.parseLong(uri.getPathSegments().get(2));
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(resolvedVolumeName), audioId), true);
                final long playlistId = initialValues
                        .getAsLong(MediaStore.Audio.Playlists.Members.PLAYLIST_ID);
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(resolvedVolumeName), playlistId), true);

                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Playlists.Members.AUDIO_ID, audioId);
                rowId = db.insert("audio_playlists_map", "playlist_id",
                        values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                    updatePlaylistDateModifiedToNow(db, playlistId);
                }
                break;
            }

            case AUDIO_GENRES: {
                throw new FallbackException("Genres are read-only", Build.VERSION_CODES.R);
            }

            case AUDIO_GENRES_ID_MEMBERS: {
                throw new FallbackException("Genres are read-only", Build.VERSION_CODES.R);
            }

            case AUDIO_PLAYLISTS: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                ContentValues values = new ContentValues(initialValues);
                values.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis() / 1000);
                rowId = insertFile(helper, match, uri, values,
                        FileColumns.MEDIA_TYPE_PLAYLIST, true);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(
                            Audio.Playlists.getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case AUDIO_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                // Require that caller has write access to underlying media
                final long audioId = initialValues
                        .getAsLong(MediaStore.Audio.Playlists.Members.AUDIO_ID);
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(resolvedVolumeName), audioId), true);
                final long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(resolvedVolumeName), playlistId), true);

                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Playlists.Members.PLAYLIST_ID, playlistId);
                rowId = db.insert("audio_playlists_map", "playlist_id", values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                    updatePlaylistDateModifiedToNow(db, playlistId);
                }
                break;
            }

            case VIDEO_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(helper, match, uri, initialValues,
                        FileColumns.MEDIA_TYPE_VIDEO, true);
                if (rowId > 0) {
                    MediaDocumentsProvider.onMediaStoreInsert(
                            getContext(), resolvedVolumeName, FileColumns.MEDIA_TYPE_VIDEO, rowId);
                    newUri = ContentUris.withAppendedId(
                            Video.Media.getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case AUDIO_ALBUMART: {
                if (helper.mInternal) {
                    throw new UnsupportedOperationException("no internal album art allowed");
                }

                ensureUniqueFileColumns(match, uri, initialValues);

                rowId = db.insert("album_art", MediaStore.MediaColumns.DATA, initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case FILES: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(helper, match, uri, initialValues,
                        FileColumns.MEDIA_TYPE_NONE, true);
                if (rowId > 0) {
                    MediaDocumentsProvider.onMediaStoreInsert(
                            getContext(), resolvedVolumeName, FileColumns.MEDIA_TYPE_NONE, rowId);
                    newUri = Files.getContentUri(originalVolumeName, rowId);
                }
                break;
            }

            case MTP_OBJECTS:
                // We don't send a notification if the insert originated from MTP
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(helper, match, uri, initialValues,
                        FileColumns.MEDIA_TYPE_NONE, false);
                if (rowId > 0) {
                    newUri = Files.getMtpObjectsUri(originalVolumeName, rowId);
                }
                break;

            case FILES_DIRECTORY:
                rowId = insertDirectory(helper, helper.getWritableDatabase(),
                        initialValues.getAsString(FileColumns.DATA));
                if (rowId > 0) {
                    newUri = Files.getContentUri(originalVolumeName, rowId);
                }
                break;

            case DOWNLOADS:
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                initialValues.put(FileColumns.IS_DOWNLOAD, true);
                rowId = insertFile(helper, match, uri, initialValues,
                        FileColumns.MEDIA_TYPE_NONE, false);
                if (rowId > 0) {
                    final int mediaType = initialValues.getAsInteger(FileColumns.MEDIA_TYPE);
                    MediaDocumentsProvider.onMediaStoreInsert(
                            getContext(), resolvedVolumeName, mediaType, rowId);
                    newUri = ContentUris.withAppendedId(
                        MediaStore.Downloads.getContentUri(originalVolumeName), rowId);
                }
                break;

            default:
                throw new UnsupportedOperationException("Invalid URI " + uri);
        }

        // Remember that caller is owner of this item, to speed up future
        // permission checks for this caller
        mCallingIdentity.get().setOwned(rowId, true);

        if (path != null && path.toLowerCase(Locale.ROOT).endsWith("/.nomedia")) {
            mMediaScanner.scanFile(new File(path).getParentFile());
        }

        if (newUri != null) {
            acceptWithExpansion(helper::notifyChange, newUri);
        }
        return newUri;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
                throws OperationApplicationException {
        // Open transactions on databases for requested volumes
        final ArrayMap<String, DatabaseHelper> transactions = new ArrayMap<>();
        try {
            for (ContentProviderOperation op : operations) {
                final String volumeName = MediaStore.getVolumeName(op.getUri());
                if (!transactions.containsKey(volumeName)) {
                    try {
                        final DatabaseHelper helper = getDatabaseForUri(op.getUri());
                        helper.beginTransaction();
                        transactions.put(volumeName, helper);
                    } catch (VolumeNotFoundException e) {
                        Log.w(TAG, e.getMessage());
                    }
                }
            }

            final ContentProviderResult[] result = super.applyBatch(operations);
            for (DatabaseHelper helper : transactions.values()) {
                helper.setTransactionSuccessful();
            }
            return result;
        } finally {
            for (DatabaseHelper helper : transactions.values()) {
                helper.endTransaction();
            }
        }
    }

    private static void appendWhereStandalone(@NonNull SQLiteQueryBuilder qb,
            @Nullable String selection, @Nullable Object... selectionArgs) {
        qb.appendWhereStandalone(DatabaseUtils.bindSelection(selection, selectionArgs));
    }

    static @NonNull String bindList(@NonNull Object... args) {
        final StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < args.length; i++) {
            sb.append('?');
            if (i < args.length - 1) {
                sb.append(',');
            }
        }
        sb.append(')');
        return DatabaseUtils.bindSelection(sb.toString(), args);
    }

    private static boolean parseBoolean(String value) {
        if (value == null) return false;
        if ("1".equals(value)) return true;
        if ("true".equalsIgnoreCase(value)) return true;
        return false;
    }

    @Deprecated
    private String getSharedPackages(String callingPackage) {
        final String[] sharedPackageNames = mCallingIdentity.get().getSharedPackageNames();
        return bindList((Object[]) sharedPackageNames);
    }

    private static final int TYPE_QUERY = 0;
    private static final int TYPE_UPDATE = 1;
    private static final int TYPE_DELETE = 2;

    /**
     * Generate a {@link SQLiteQueryBuilder} that is filtered based on the
     * runtime permissions and/or {@link Uri} grants held by the caller.
     * <ul>
     * <li>If caller holds a {@link Uri} grant, access is allowed according to
     * that grant.
     * <li>If caller holds the write permission for a collection, they can
     * read/write all contents of that collection.
     * <li>If caller holds the read permission for a collection, they can read
     * all contents of that collection, but writes are limited to content they
     * own.
     * <li>If caller holds no permissions for a collection, all reads/write are
     * limited to content they own.
     * </ul>
     */
    private SQLiteQueryBuilder getQueryBuilder(int type, Uri uri, int match, Bundle queryArgs) {
        Trace.beginSection("getQueryBuilder");
        try {
            return getQueryBuilderInternal(type, uri, match, queryArgs);
        } finally {
            Trace.endSection();
        }
    }

    private SQLiteQueryBuilder getQueryBuilderInternal(int type, Uri uri, int match,
            Bundle queryArgs) {
        final boolean forWrite;
        switch (type) {
            case TYPE_QUERY: forWrite = false; break;
            case TYPE_UPDATE: forWrite = true; break;
            case TYPE_DELETE: forWrite = true; break;
            default: throw new IllegalStateException();
        }

        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        if (parseBoolean(uri.getQueryParameter("distinct"))) {
            qb.setDistinct(true);
        }
        qb.setProjectionAggregationAllowed(true);
        qb.setStrict(true);

        final String callingPackage = getCallingPackageOrSelf();

        // TODO: throw when requesting a currently unmounted volume
        final String volumeName = MediaStore.getVolumeName(uri);
        final String includeVolumes;
        if (MediaStore.VOLUME_EXTERNAL.equals(volumeName)) {
            includeVolumes = bindList(getExternalVolumeNames().toArray());
        } else {
            includeVolumes = bindList(volumeName);
        }
        final String sharedPackages = getSharedPackages(callingPackage);
        final boolean allowGlobal = checkCallingPermissionGlobal(uri, forWrite);
        final boolean allowLegacy = checkCallingPermissionLegacy(uri, forWrite, callingPackage);
        final boolean allowLegacyRead = allowLegacy && !forWrite;

        boolean includePending = MediaStore.getIncludePending(uri);
        boolean includeTrashed = false;
        boolean includeAllVolumes = false;

        switch (match) {
            case IMAGES_MEDIA_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                includePending = true;
                includeTrashed = true;
                // fall-through
            case IMAGES_MEDIA:
                if (type == TYPE_QUERY) {
                    qb.setTables("images");
                    qb.setProjectionMap(getProjectionMap(Images.Media.class));
                } else {
                    qb.setTables("files");
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_IMAGE);
                }
                if (!allowGlobal && !checkCallingPermissionImages(forWrite, callingPackage)) {
                    appendWhereStandalone(qb, FileColumns.OWNER_PACKAGE_NAME + " IN "
                            + sharedPackages);
                }
                if (!includePending) {
                    appendWhereStandalone(qb, FileColumns.IS_PENDING + "=?", 0);
                }
                if (!includeTrashed) {
                    appendWhereStandalone(qb, FileColumns.IS_TRASHED + "=?", 0);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;

            case IMAGES_THUMBNAILS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case IMAGES_THUMBNAILS: {
                qb.setTables("thumbnails");

                final ArrayMap<String, String> projectionMap = new ArrayMap<>(
                        getProjectionMap(Images.Thumbnails.class));
                projectionMap.put(Images.Thumbnails.THUMB_DATA,
                        "NULL AS " + Images.Thumbnails.THUMB_DATA);
                qb.setProjectionMap(projectionMap);

                if (!allowGlobal && !checkCallingPermissionImages(forWrite, callingPackage)) {
                    appendWhereStandalone(qb,
                            "image_id IN (SELECT _id FROM images WHERE owner_package_name IN "
                                    + sharedPackages + ")");
                }
                break;
            }

            case AUDIO_MEDIA_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                includePending = true;
                includeTrashed = true;
                // fall-through
            case AUDIO_MEDIA:
                if (type == TYPE_QUERY) {
                    qb.setTables("audio");
                    qb.setProjectionMap(getProjectionMap(Audio.Media.class));
                } else {
                    qb.setTables("files");
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_AUDIO);
                }
                if (!allowGlobal && !checkCallingPermissionAudio(forWrite, callingPackage)) {
                    // Apps without Audio permission can only see their own
                    // media, but we also let them see ringtone-style media to
                    // support legacy use-cases.
                    appendWhereStandalone(qb,
                            DatabaseUtils.bindSelection(FileColumns.OWNER_PACKAGE_NAME
                                    + " IN " + sharedPackages
                                    + " OR is_ringtone=1 OR is_alarm=1 OR is_notification=1"));
                }
                if (!includePending) {
                    appendWhereStandalone(qb, FileColumns.IS_PENDING + "=?", 0);
                }
                if (!includeTrashed) {
                    appendWhereStandalone(qb, FileColumns.IS_TRASHED + "=?", 0);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;

            case AUDIO_MEDIA_ID_GENRES_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_MEDIA_ID_GENRES:
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_genres");
                    qb.setProjectionMap(getProjectionMap(Audio.Genres.class));
                } else {
                    throw new UnsupportedOperationException("Genres cannot be directly modified");
                }
                appendWhereStandalone(qb, "_id IN (SELECT genre_id FROM " +
                        "audio WHERE _id=?)", uri.getPathSegments().get(3));
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;

            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_MEDIA_ID_PLAYLISTS:
                qb.setTables("audio_playlists");
                qb.setProjectionMap(getProjectionMap(Audio.Playlists.class));
                appendWhereStandalone(qb, "_id IN (SELECT playlist_id FROM " +
                        "audio_playlists_map WHERE audio_id=?)", uri.getPathSegments().get(3));
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;

            case AUDIO_GENRES_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_GENRES:
                qb.setTables("audio_genres");
                qb.setProjectionMap(getProjectionMap(Audio.Genres.class));
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;

            case AUDIO_GENRES_ID_MEMBERS:
                appendWhereStandalone(qb, "genre_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_GENRES_ALL_MEMBERS:
                if (type == TYPE_QUERY) {
                    qb.setTables("audio");

                    final ArrayMap<String, String> projectionMap = new ArrayMap<>(
                            getProjectionMap(Audio.Genres.Members.class));
                    projectionMap.put(Audio.Genres.Members.AUDIO_ID,
                            "_id AS " + Audio.Genres.Members.AUDIO_ID);
                    qb.setProjectionMap(projectionMap);
                } else {
                    throw new UnsupportedOperationException("Genres cannot be directly modified");
                }
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;

            case AUDIO_PLAYLISTS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                includePending = true;
                includeTrashed = true;
                // fall-through
            case AUDIO_PLAYLISTS:
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_playlists");
                    qb.setProjectionMap(getProjectionMap(Audio.Playlists.class));
                } else {
                    qb.setTables("files");
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_PLAYLIST);
                }
                if (!allowGlobal && !checkCallingPermissionAudio(forWrite, callingPackage)) {
                    appendWhereStandalone(qb, FileColumns.OWNER_PACKAGE_NAME + " IN "
                            + sharedPackages);
                }
                if (!includePending) {
                    appendWhereStandalone(qb, FileColumns.IS_PENDING + "=?", 0);
                }
                if (!includeTrashed) {
                    appendWhereStandalone(qb, FileColumns.IS_TRASHED + "=?", 0);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;

            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                appendWhereStandalone(qb, "audio_playlists_map._id=?",
                        uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                appendWhereStandalone(qb, "playlist_id=?", uri.getPathSegments().get(3));
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_playlists_map, audio");

                    final ArrayMap<String, String> projectionMap = new ArrayMap<>(
                            getProjectionMap(Audio.Playlists.Members.class));
                    projectionMap.put(Audio.Playlists.Members._ID,
                            "audio_playlists_map._id AS " + Audio.Playlists.Members._ID);
                    qb.setProjectionMap(projectionMap);

                    appendWhereStandalone(qb, "audio._id = audio_id");
                } else {
                    qb.setTables("audio_playlists_map");
                }
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }

            case AUDIO_ALBUMART_ID:
                appendWhereStandalone(qb, "album_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_ALBUMART: {
                qb.setTables("album_art");

                final ArrayMap<String, String> projectionMap = new ArrayMap<>(
                        getProjectionMap(Audio.Thumbnails.class));
                projectionMap.put(Audio.Thumbnails._ID,
                        "album_id AS " + Audio.Thumbnails._ID);
                qb.setProjectionMap(projectionMap);

                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }
            case AUDIO_ARTISTS_ID_ALBUMS: {
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_albums");
                    qb.setProjectionMap(getProjectionMap(Audio.Artists.Albums.class));

                    final String artistId = uri.getPathSegments().get(3);
                    appendWhereStandalone(qb, "artist_id=?", artistId);
                } else {
                    throw new UnsupportedOperationException("Albums cannot be directly modified");
                }
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }

            case AUDIO_ARTISTS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_ARTISTS:
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_artists");
                    qb.setProjectionMap(getProjectionMap(Audio.Artists.class));
                } else {
                    throw new UnsupportedOperationException("Artists cannot be directly modified");
                }
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;

            case AUDIO_ALBUMS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_ALBUMS: {
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_albums");
                    qb.setProjectionMap(getProjectionMap(Audio.Albums.class));
                } else {
                    throw new UnsupportedOperationException("Albums cannot be directly modified");
                }
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }

            case VIDEO_MEDIA_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                includePending = true;
                includeTrashed = true;
                // fall-through
            case VIDEO_MEDIA:
                if (type == TYPE_QUERY) {
                    qb.setTables("video");
                    qb.setProjectionMap(getProjectionMap(Video.Media.class));
                } else {
                    qb.setTables("files");
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_VIDEO);
                }
                if (!allowGlobal && !checkCallingPermissionVideo(forWrite, callingPackage)) {
                    appendWhereStandalone(qb, FileColumns.OWNER_PACKAGE_NAME + " IN "
                            + sharedPackages);
                }
                if (!includePending) {
                    appendWhereStandalone(qb, FileColumns.IS_PENDING + "=?", 0);
                }
                if (!includeTrashed) {
                    appendWhereStandalone(qb, FileColumns.IS_TRASHED + "=?", 0);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;

            case VIDEO_THUMBNAILS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case VIDEO_THUMBNAILS:
                qb.setTables("videothumbnails");
                qb.setProjectionMap(getProjectionMap(Video.Thumbnails.class));
                if (!allowGlobal && !checkCallingPermissionVideo(forWrite, callingPackage)) {
                    appendWhereStandalone(qb,
                            "video_id IN (SELECT _id FROM video WHERE owner_package_name IN "
                                    + sharedPackages + ")");
                }
                break;

            case FILES_ID:
            case MTP_OBJECTS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(2));
                includePending = true;
                includeTrashed = true;
                // fall-through
            case FILES:
            case FILES_DIRECTORY:
            case MTP_OBJECTS: {
                qb.setTables("files");
                qb.setProjectionMap(getProjectionMap(Files.FileColumns.class));

                final ArrayList<String> options = new ArrayList<>();
                if (!allowGlobal && !allowLegacyRead) {
                    options.add(DatabaseUtils.bindSelection("owner_package_name IN "
                            + sharedPackages));
                    if (allowLegacy) {
                        options.add(DatabaseUtils.bindSelection("volume_name=?",
                                MediaStore.VOLUME_EXTERNAL_PRIMARY));
                    }
                    if (checkCallingPermissionAudio(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_AUDIO));
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_PLAYLIST));
                        options.add("media_type=0 AND mime_type LIKE 'audio/%'");
                    }
                    if (checkCallingPermissionVideo(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_VIDEO));
                        options.add("media_type=0 AND mime_type LIKE 'video/%'");
                    }
                    if (checkCallingPermissionImages(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_IMAGE));
                        options.add("media_type=0 AND mime_type LIKE 'image/%'");
                    }
                }
                if (options.size() > 0) {
                    appendWhereStandalone(qb, TextUtils.join(" OR ", options));
                }

                if (!includePending) {
                    appendWhereStandalone(qb, FileColumns.IS_PENDING + "=?", 0);
                }
                if (!includeTrashed) {
                    appendWhereStandalone(qb, FileColumns.IS_TRASHED + "=?", 0);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
            case DOWNLOADS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(2));
                includePending = true;
                includeTrashed = true;
                // fall-through
            case DOWNLOADS: {
                if (type == TYPE_QUERY) {
                    qb.setTables("downloads");
                    qb.setProjectionMap(getProjectionMap(Downloads.class));
                } else {
                    qb.setTables("files");
                    appendWhereStandalone(qb, FileColumns.IS_DOWNLOAD + "=1");
                }

                final ArrayList<String> options = new ArrayList<>();
                if (!allowGlobal && !allowLegacyRead) {
                    options.add(DatabaseUtils.bindSelection("owner_package_name IN "
                            + sharedPackages));
                    if (allowLegacy) {
                        options.add(DatabaseUtils.bindSelection("volume_name=?",
                                MediaStore.VOLUME_EXTERNAL_PRIMARY));
                    }
                }
                if (options.size() > 0) {
                    appendWhereStandalone(qb, TextUtils.join(" OR ", options));
                }

                if (!includePending) {
                    appendWhereStandalone(qb, FileColumns.IS_PENDING + "=?", 0);
                }
                if (!includeTrashed) {
                    appendWhereStandalone(qb, FileColumns.IS_TRASHED + "=?", 0);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
            default:
                throw new UnsupportedOperationException(
                        "Unknown or unsupported URL: " + uri.toString());
        }

        if (type == TYPE_QUERY) {
            // To ensure we're enforcing our security model, all queries must
            // have a projection map configured
            if (qb.getProjectionMap() == null) {
                throw new IllegalStateException("All queries must have a projection map");
            }

            // If caller is an older app, we're willing to let through a
            // greylist of technically invalid columns
            if (getCallingPackageTargetSdkVersion() < Build.VERSION_CODES.Q) {
                qb.setProjectionGreylist(sGreylist);
            }

            // If we're the legacy provider, and the caller is the system, then
            // we're willing to let them access any columns they want
            if (mLegacyProvider && isCallingPackageSystem()) {
                qb.setProjectionGreylist(sGreylist);
            }
        }

        return qb;
    }

    /**
     * Determine if given {@link Uri} has a
     * {@link MediaColumns#OWNER_PACKAGE_NAME} column.
     */
    private boolean hasOwnerPackageName(Uri uri) {
        // It's easier to maintain this as an inverted list
        final int table = matchUri(uri, true);
        switch (table) {
            case IMAGES_THUMBNAILS_ID:
            case IMAGES_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS:
            case AUDIO_ALBUMART:
            case AUDIO_ALBUMART_ID:
            case AUDIO_ALBUMART_FILE_ID:
                return false;
            default:
                return true;
        }
    }

    @Override
    public int delete(Uri uri, String userWhere, String[] userWhereArgs) {
        Trace.beginSection("insert");
        try {
            return deleteInternal(uri, userWhere, userWhereArgs);
        } catch (FallbackException e) {
            return e.translateForUpdateDelete(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private int deleteInternal(Uri uri, String userWhere, String[] userWhereArgs)
            throws FallbackException {
        uri = safeUncanonicalize(uri);

        int count;

        final String volumeName = getVolumeName(uri);
        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (match == MEDIA_SCANNER) {
            if (mMediaScannerVolume == null) {
                return 0;
            }

            final DatabaseHelper helper = getDatabaseForUri(
                    MediaStore.Files.getContentUri(mMediaScannerVolume));

            helper.mScanStopTime = SystemClock.elapsedRealtime();
            String msg = dump(helper, false);
            DatabaseHelper.logToDb(helper.getWritableDatabase(), msg);

            mMediaScannerVolume = null;
            return 1;
        }

        if (match == VOLUMES_ID) {
            detachVolume(uri);
            count = 1;
        }

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final SQLiteDatabase db = helper.getWritableDatabase();

        {
            final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_DELETE, uri, match, null);

            // Give callers interacting with a specific media item a chance to
            // escalate access if they don't already have it
            switch (match) {
                case AUDIO_MEDIA_ID:
                case VIDEO_MEDIA_ID:
                case IMAGES_MEDIA_ID:
                    enforceCallingPermission(uri, true);
            }

            final String[] projection = new String[] {
                    FileColumns.MEDIA_TYPE,
                    FileColumns.DATA,
                    FileColumns._ID,
                    FileColumns.IS_DOWNLOAD,
                    FileColumns.MIME_TYPE,
            };
            final LongSparseArray<String> deletedDownloadIds = new LongSparseArray<>();
            if (qb.getTables().equals("files")) {
                String deleteparam = uri.getQueryParameter(MediaStore.PARAM_DELETE_DATA);
                if (deleteparam == null || ! deleteparam.equals("false")) {
                    Cursor c = qb.query(db, projection, userWhere, userWhereArgs,
                            null, null, null, null);
                    String [] idvalue = new String[] { "" };
                    String [] playlistvalues = new String[] { "", "" };
                    try {
                        while (c.moveToNext()) {
                            final int mediaType = c.getInt(0);
                            final String data = c.getString(1);
                            final long id = c.getLong(2);
                            final int isDownload = c.getInt(3);
                            final String mimeType = c.getString(4);

                            // Forget that caller is owner of this item
                            mCallingIdentity.get().setOwned(id, false);

                            // Invalidate thumbnails and revoke all outstanding grants
                            final Uri deletedUri = Files.getContentUri(volumeName, id);
                            invalidateThumbnails(deletedUri);
                            acceptWithExpansion((expandedUri) -> {
                                getContext().revokeUriPermission(expandedUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            }, deletedUri);

                            // Only need to inform DownloadProvider about the downloads deleted on
                            // external volume.
                            if (isDownload == 1) {
                                deletedDownloadIds.put(id, mimeType);
                            }
                            if (mediaType == FileColumns.MEDIA_TYPE_IMAGE) {
                                deleteIfAllowed(uri, data);
                                MediaDocumentsProvider.onMediaStoreDelete(getContext(),
                                        volumeName, FileColumns.MEDIA_TYPE_IMAGE, id);
                            } else if (mediaType == FileColumns.MEDIA_TYPE_VIDEO) {
                                deleteIfAllowed(uri, data);
                                MediaDocumentsProvider.onMediaStoreDelete(getContext(),
                                        volumeName, FileColumns.MEDIA_TYPE_VIDEO, id);
                            } else if (mediaType == FileColumns.MEDIA_TYPE_AUDIO) {
                                if (!helper.mInternal) {
                                    deleteIfAllowed(uri, data);
                                    MediaDocumentsProvider.onMediaStoreDelete(getContext(),
                                            volumeName, FileColumns.MEDIA_TYPE_AUDIO, id);

                                    idvalue[0] = String.valueOf(id);
                                    // for each playlist that the item appears in, move
                                    // all the items behind it forward by one
                                    Cursor cc = db.query("audio_playlists_map",
                                                sPlaylistIdPlayOrder,
                                                "audio_id=?", idvalue, null, null, null);
                                    try {
                                        while (cc.moveToNext()) {
                                            long playlistId = cc.getLong(0);
                                            playlistvalues[0] = String.valueOf(playlistId);
                                            playlistvalues[1] = String.valueOf(cc.getInt(1));
                                            int rowsChanged = db.executeSql("UPDATE audio_playlists_map" +
                                                    " SET play_order=play_order-1" +
                                                    " WHERE playlist_id=? AND play_order>?",
                                                    playlistvalues);

                                            if (rowsChanged > 0) {
                                                updatePlaylistDateModifiedToNow(db, playlistId);
                                            }
                                        }
                                        db.delete("audio_playlists_map", "audio_id=?", idvalue);
                                    } finally {
                                        FileUtils.closeQuietly(cc);
                                    }
                                }
                            } else if (isDownload == 1) {
                                deleteIfAllowed(uri, data);
                                MediaDocumentsProvider.onMediaStoreDelete(getContext(),
                                        volumeName, mediaType, id);
                            } else if (mediaType == FileColumns.MEDIA_TYPE_PLAYLIST) {
                                // TODO, maybe: remove the audio_playlists_cleanup trigger and
                                // implement functionality here (clean up the playlist map)
                            }
                        }
                    } finally {
                        FileUtils.closeQuietly(c);
                    }
                    // Do not allow deletion if the file/object is referenced as parent
                    // by some other entries. It could cause database corruption.
                    appendWhereStandalone(qb, ID_NOT_PARENT_CLAUSE);
                }
            }

            switch (match) {
                case MTP_OBJECTS:
                case MTP_OBJECTS_ID:
                    count = deleteRecursive(qb, db, userWhere, userWhereArgs);
                    break;
                case AUDIO_GENRES_ID_MEMBERS:
                    throw new FallbackException("Genres are read-only", Build.VERSION_CODES.R);

                case IMAGES_THUMBNAILS_ID:
                case IMAGES_THUMBNAILS:
                case VIDEO_THUMBNAILS_ID:
                case VIDEO_THUMBNAILS:
                    // Delete the referenced files first.
                    Cursor c = qb.query(db, sDataOnlyColumn, userWhere, userWhereArgs, null, null,
                            null, null);
                    if (c != null) {
                        try {
                            while (c.moveToNext()) {
                                deleteIfAllowed(uri, c.getString(0));
                            }
                        } finally {
                            FileUtils.closeQuietly(c);
                        }
                    }
                    count = deleteRecursive(qb, db, userWhere, userWhereArgs);
                    break;

                case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                    long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                    count = deleteRecursive(qb, db, userWhere, userWhereArgs);
                    if (count > 0) {
                        updatePlaylistDateModifiedToNow(db, playlistId);
                    }
                    break;
                default:
                    count = deleteRecursive(qb, db, userWhere, userWhereArgs);
                    break;
            }

            if (deletedDownloadIds.size() > 0) {
                final long token = Binder.clearCallingIdentity();
                try (ContentProviderClient client = getContext().getContentResolver()
                     .acquireUnstableContentProviderClient(
                             android.provider.Downloads.Impl.AUTHORITY)) {
                    final Bundle extras = new Bundle();
                    final long[] ids = new long[deletedDownloadIds.size()];
                    final String[] mimeTypes = new String[deletedDownloadIds.size()];
                    for (int i = deletedDownloadIds.size() - 1; i >= 0; --i) {
                        ids[i] = deletedDownloadIds.keyAt(i);
                        mimeTypes[i] = deletedDownloadIds.valueAt(i);
                    }
                    extras.putLongArray(android.provider.Downloads.EXTRA_IDS, ids);
                    extras.putStringArray(android.provider.Downloads.EXTRA_MIME_TYPES, mimeTypes);
                    client.call(android.provider.Downloads.CALL_MEDIASTORE_DOWNLOADS_DELETED,
                            null, extras);
                } catch (RemoteException e) {
                    // Should not happen
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        if (count > 0) {
            acceptWithExpansion(helper::notifyChange, uri);
        }
        return count;
    }

    /**
     * Executes identical delete repeatedly within a single transaction until
     * stability is reached. Combined with {@link #ID_NOT_PARENT_CLAUSE}, this
     * can be used to recursively delete all matching entries, since it only
     * deletes parents when no references remaining.
     */
    private int deleteRecursive(SQLiteQueryBuilder qb, SQLiteDatabase db, String userWhere,
            String[] userWhereArgs) {
        synchronized (mDirectoryCache) {
            mDirectoryCache.clear();

            db.beginTransaction();
            try {
                int n = 0;
                int total = 0;
                do {
                    n = qb.delete(db, userWhere, userWhereArgs);
                    total += n;
                } while (n > 0);
                db.setTransactionSuccessful();
                return total;
            } finally {
                db.endTransaction();
            }
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case MediaStore.WAIT_FOR_IDLE_CALL: {
                final CountDownLatch latch = new CountDownLatch(1);
                BackgroundThread.getExecutor().execute(() -> {
                    latch.countDown();
                });
                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            }
            case MediaStore.SCAN_FILE_CALL:
            case MediaStore.SCAN_VOLUME_CALL: {
                final LocalCallingIdentity token = clearLocalCallingIdentity();
                final CallingIdentity providerToken = clearCallingIdentity();
                try {
                    final Uri uri = extras.getParcelable(Intent.EXTRA_STREAM);
                    final File file = new File(uri.getPath());
                    final Bundle res = new Bundle();
                    switch (method) {
                        case MediaStore.SCAN_FILE_CALL:
                            res.putParcelable(Intent.EXTRA_STREAM, scanFile(file));
                            break;
                        case MediaStore.SCAN_VOLUME_CALL:
                            MediaService.onScanVolume(getContext(), Uri.fromFile(file));
                            break;
                    }
                    return res;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    restoreCallingIdentity(providerToken);
                    restoreLocalCallingIdentity(token);
                }
            }
            case MediaStore.UNHIDE_CALL: {
                throw new UnsupportedOperationException();
            }
            case MediaStore.RETRANSLATE_CALL: {
                localizeTitles();
                return null;
            }
            case MediaStore.GET_VERSION_CALL: {
                final String volumeName = extras.getString(Intent.EXTRA_TEXT);

                final SQLiteDatabase db;
                try {
                    db = getDatabaseForUri(MediaStore.Files.getContentUri(volumeName))
                            .getReadableDatabase();
                } catch (VolumeNotFoundException e) {
                    throw e.rethrowAsIllegalArgumentException();
                }

                final String version = db.getVersion() + ":" + DatabaseHelper.getOrCreateUuid(db);

                final Bundle res = new Bundle();
                res.putString(Intent.EXTRA_TEXT, version);
                return res;
            }
            case MediaStore.GET_DOCUMENT_URI_CALL: {
                final Uri mediaUri = extras.getParcelable(DocumentsContract.EXTRA_URI);
                enforceCallingPermission(mediaUri, false);

                final Uri fileUri;
                final LocalCallingIdentity token = clearLocalCallingIdentity();
                try {
                    fileUri = Uri.fromFile(queryForDataFile(mediaUri, null));
                } catch (FileNotFoundException e) {
                    throw new IllegalArgumentException(e);
                } finally {
                    restoreLocalCallingIdentity(token);
                }

                try (ContentProviderClient client = getContext().getContentResolver()
                        .acquireUnstableContentProviderClient(
                                DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY)) {
                    extras.putParcelable(DocumentsContract.EXTRA_URI, fileUri);
                    return client.call(method, null, extras);
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
            }
            case MediaStore.GET_MEDIA_URI_CALL: {
                final Uri documentUri = extras.getParcelable(DocumentsContract.EXTRA_URI);
                getContext().enforceCallingUriPermission(documentUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION, TAG);

                final Uri fileUri;
                try (ContentProviderClient client = getContext().getContentResolver()
                        .acquireUnstableContentProviderClient(
                                DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY)) {
                    final Bundle res = client.call(method, null, extras);
                    fileUri = res.getParcelable(DocumentsContract.EXTRA_URI);
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }

                final LocalCallingIdentity token = clearLocalCallingIdentity();
                try {
                    final Bundle res = new Bundle();
                    res.putParcelable(DocumentsContract.EXTRA_URI,
                            queryForMediaUri(new File(fileUri.getPath()), null));
                    return res;
                } catch (FileNotFoundException e) {
                    throw new IllegalArgumentException(e);
                } finally {
                    restoreLocalCallingIdentity(token);
                }
            }
            case MediaStore.GET_CONTRIBUTED_MEDIA_CALL: {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA, TAG);

                final String packageName = extras.getString(Intent.EXTRA_PACKAGE_NAME);
                final long totalSize = forEachContributedMedia(packageName, null);
                final Bundle res = new Bundle();
                res.putLong(Intent.EXTRA_INDEX, totalSize);
                return res;
            }
            case MediaStore.DELETE_CONTRIBUTED_MEDIA_CALL: {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA, TAG);

                final String packageName = extras.getString(Intent.EXTRA_PACKAGE_NAME);
                forEachContributedMedia(packageName, (uri) -> {
                    delete(uri, null, null);
                });
                return null;
            }
            default:
                throw new UnsupportedOperationException("Unsupported call: " + method);
        }
    }

    /**
     * Execute the given operation for each media item contributed by given
     * package. The meaning of "contributed" means it won't automatically be
     * deleted when the app is uninstalled.
     */
    private long forEachContributedMedia(String packageName, Consumer<Uri> consumer) {
        final DatabaseHelper helper = mExternalDatabase;
        final SQLiteDatabase db = helper.getReadableDatabase();

        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables("files");
        qb.appendWhere(
                DatabaseUtils.bindSelection(FileColumns.OWNER_PACKAGE_NAME + "=?", packageName)
                        + " AND NOT " + FileColumns.DATA + " REGEXP '"
                        + PATTERN_OWNED_PATH.pattern() + "'");

        long totalSize = 0;
        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try {
            try (Cursor c = qb.query(db, new String[] {
                    FileColumns.VOLUME_NAME, FileColumns._ID, FileColumns.SIZE, FileColumns.DATA
            }, null, null, null, null, null, null)) {
                while (c.moveToNext()) {
                    final String volumeName = c.getString(0);
                    final long id = c.getLong(1);
                    final long size = c.getLong(2);
                    final String data = c.getString(3);

                    Log.d(TAG, "Found " + data + " from " + packageName + " in "
                            + helper.mName + " with size " + size);
                    if (consumer != null) {
                        consumer.accept(Files.getContentUri(volumeName, id));
                    }
                    totalSize += size;
                }
            }
        } finally {
            restoreLocalCallingIdentity(token);
        }
        return totalSize;
    }

    /**
     * Ensure that all local databases have a custom collator registered for the
     * given {@link ULocale} locale.
     *
     * @return the corresponding custom collation name to be used in
     *         {@code ORDER BY} clauses.
     */
    private @NonNull String ensureCustomCollator(@NonNull String locale) {
        // Quick sanity check that requested locale looks sane
        new ULocale(locale);

        final String collationName = "custom_" + locale.replaceAll("[^a-zA-Z]", "");
        synchronized (mCustomCollators) {
            if (!mCustomCollators.contains(collationName)) {
                for (DatabaseHelper helper : new DatabaseHelper[] {
                        mInternalDatabase,
                        mExternalDatabase
                }) {
                    final SQLiteDatabase db = helper.getReadableDatabase();
                    try (Cursor c = db.rawQuery("SELECT icu_load_collation(?, ?);",
                            new String[] { locale, collationName }, null)) {
                        while (c.moveToNext()) {
                        }
                    }
                }
                mCustomCollators.add(collationName);
            }
        }
        return collationName;
    }

    private void pruneThumbnails(@NonNull CancellationSignal signal) {
        final DatabaseHelper helper = mExternalDatabase;
        final SQLiteDatabase db = helper.getReadableDatabase();

        // Determine all known media items
        final LongArray knownIds = new LongArray();
        try (Cursor c = db.query(true, "files", new String[] { BaseColumns._ID },
                null, null, null, null, null, null, signal)) {
            while (c.moveToNext()) {
                knownIds.add(c.getLong(0));
            }
        }

        final long[] knownIdsRaw = knownIds.toArray();
        Arrays.sort(knownIdsRaw);

        for (String volumeName : getExternalVolumeNames()) {
            final File volumePath;
            try {
                volumePath = getVolumePath(volumeName);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to resolve volume " + volumeName, e);
                continue;
            }

            // Reconcile all thumbnails, deleting stale items
            for (File thumbDir : new File[] {
                    FileUtils.buildPath(volumePath, Environment.DIRECTORY_MUSIC, ".thumbnails"),
                    FileUtils.buildPath(volumePath, Environment.DIRECTORY_MOVIES, ".thumbnails"),
                    FileUtils.buildPath(volumePath, Environment.DIRECTORY_PICTURES, ".thumbnails"),
            }) {
                // Possibly bail before digging into each directory
                signal.throwIfCanceled();

                final File[] files = thumbDir.listFiles();
                for (File thumbFile : (files != null) ? files : new File[0]) {
                    final String name = ModernMediaScanner.extractName(thumbFile);
                    try {
                        final long id = Long.parseLong(name);
                        if (Arrays.binarySearch(knownIdsRaw, id) >= 0) {
                            // Thumbnail belongs to known media, keep it
                            continue;
                        }
                    } catch (NumberFormatException e) {
                    }

                    Log.v(TAG, "Deleting stale thumbnail " + thumbFile);
                    thumbFile.delete();
                }
            }
        }

        // Also delete stale items from legacy tables
        db.execSQL("delete from thumbnails "
                + "where image_id not in (select _id from images)");
        db.execSQL("delete from videothumbnails "
                + "where video_id not in (select _id from video)");
    }

    static abstract class Thumbnailer {
        final String directoryName;

        public Thumbnailer(String directoryName) {
            this.directoryName = directoryName;
        }

        private File getThumbnailFile(Uri uri) throws IOException {
            final String volumeName = resolveVolumeName(uri);
            final File volumePath = getVolumePath(volumeName);
            return FileUtils.buildPath(volumePath, directoryName,
                    ".thumbnails", ContentUris.parseId(uri) + ".jpg");
        }

        public abstract Bitmap getThumbnailBitmap(Uri uri, CancellationSignal signal)
                throws IOException;

        public File ensureThumbnail(Uri uri, CancellationSignal signal) throws IOException {
            final File thumbFile = getThumbnailFile(uri);
            thumbFile.getParentFile().mkdirs();
            if (!thumbFile.exists()) {
                final Bitmap thumbnail = getThumbnailBitmap(uri, signal);
                try (OutputStream out = new FileOutputStream(thumbFile)) {
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 75, out);
                }
            }
            return thumbFile;
        }

        public void invalidateThumbnail(Uri uri) throws IOException {
            getThumbnailFile(uri).delete();
        }
    }

    private Thumbnailer mAudioThumbnailer = new Thumbnailer(Environment.DIRECTORY_MUSIC) {
        @Override
        public Bitmap getThumbnailBitmap(Uri uri, CancellationSignal signal) throws IOException {
            return ThumbnailUtils.createAudioThumbnail(queryForDataFile(uri, signal),
                    mThumbSize, signal);
        }
    };

    private Thumbnailer mVideoThumbnailer = new Thumbnailer(Environment.DIRECTORY_MOVIES) {
        @Override
        public Bitmap getThumbnailBitmap(Uri uri, CancellationSignal signal) throws IOException {
            return ThumbnailUtils.createVideoThumbnail(queryForDataFile(uri, signal),
                    mThumbSize, signal);
        }
    };

    private Thumbnailer mImageThumbnailer = new Thumbnailer(Environment.DIRECTORY_PICTURES) {
        @Override
        public Bitmap getThumbnailBitmap(Uri uri, CancellationSignal signal) throws IOException {
            return ThumbnailUtils.createImageThumbnail(queryForDataFile(uri, signal),
                    mThumbSize, signal);
        }
    };

    private void invalidateThumbnails(Uri uri) {
        Trace.beginSection("invalidateThumbnails");
        try {
            invalidateThumbnailsInternal(uri);
        } finally {
            Trace.endSection();
        }
    }

    private void invalidateThumbnailsInternal(Uri uri) {
        final long id = ContentUris.parseId(uri);
        try {
            mAudioThumbnailer.invalidateThumbnail(uri);
            mVideoThumbnailer.invalidateThumbnail(uri);
            mImageThumbnailer.invalidateThumbnail(uri);
        } catch (IOException ignored) {
        }

        final DatabaseHelper helper;
        final SQLiteDatabase db;
        try {
            helper = getDatabaseForUri(uri);
            db = helper.getWritableDatabase();
        } catch (VolumeNotFoundException e) {
            Log.w(TAG, e);
            return;
        }

        final String idString = Long.toString(id);
        try (Cursor c = db.rawQuery("select _data from thumbnails where image_id=?"
                + " union all select _data from videothumbnails where video_id=?",
                new String[] { idString, idString })) {
            while (c.moveToNext()) {
                String path = c.getString(0);
                deleteIfAllowed(uri, path);
            }
        }

        db.execSQL("delete from thumbnails where image_id=?", new String[] { idString });
        db.execSQL("delete from videothumbnails where video_id=?", new String[] { idString });
    }

    @Override
    public int update(Uri uri, ContentValues initialValues, String userWhere,
            String[] userWhereArgs) {
        Trace.beginSection("update");
        try {
            return updateInternal(uri, initialValues, userWhere, userWhereArgs);
        } catch (FallbackException e) {
            return e.translateForUpdateDelete(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private int updateInternal(Uri uri, ContentValues initialValues, String userWhere,
            String[] userWhereArgs) throws FallbackException {
        if ("com.google.android.GoogleCamera".equals(getCallingPackageOrSelf())) {
            if (matchUri(uri, false) == IMAGES_MEDIA_ID) {
                Log.w(TAG, "Working around app bug in b/111966296");
                uri = MediaStore.Files.getContentUri("external", ContentUris.parseId(uri));
            } else if (matchUri(uri, false) == VIDEO_MEDIA_ID) {
                Log.w(TAG, "Working around app bug in b/112246630");
                uri = MediaStore.Files.getContentUri("external", ContentUris.parseId(uri));
            }
        }

        uri = safeUncanonicalize(uri);

        int count;

        final String volumeName = getVolumeName(uri);
        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final SQLiteDatabase db = helper.getWritableDatabase();

        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE, uri, match, null);

        // Give callers interacting with a specific media item a chance to
        // escalate access if they don't already have it
        switch (match) {
            case AUDIO_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
                enforceCallingPermission(uri, true);
        }

        boolean triggerInvalidate = false;
        boolean triggerScan = false;
        if (initialValues != null) {
            // IDs are forever; nobody should be editing them
            initialValues.remove(MediaColumns._ID);

            // Ignore or augment incoming raw filesystem paths
            for (String column : sDataColumns.keySet()) {
                if (!initialValues.containsKey(column)) continue;

                if (isCallingPackageSystem() || isCallingPackageLegacy()) {
                    // Mutation allowed
                } else {
                    Log.w(TAG, "Ignoring mutation of  " + column + " from "
                            + getCallingPackageOrSelf());
                    initialValues.remove(column);
                }
            }

            if (!isCallingPackageSystem()) {
                Trace.beginSection("filter");

                // Remote callers have no direct control over owner column; we
                // force it be whoever is creating the content.
                initialValues.remove(MediaColumns.OWNER_PACKAGE_NAME);

                // We default to filtering mutable columns, except when we know
                // the single item being updated is pending; when it's finally
                // published we'll overwrite these values.
                final Uri finalUri = uri;
                final Supplier<Boolean> isPending = new CachedSupplier<>(() -> {
                    return isPending(finalUri);
                });

                // Column values controlled by media scanner aren't writable by
                // apps, since any edits here don't reflect the metadata on
                // disk, and they'd be overwritten during a rescan.
                for (String column : new ArraySet<>(initialValues.keySet())) {
                    if (sMutableColumns.contains(column)) {
                        // Mutation normally allowed
                    } else if (isPending.get()) {
                        // Mutation relaxed while pending
                    } else {
                        Log.w(TAG, "Ignoring mutation of " + column + " from "
                                + getCallingPackageOrSelf());
                        initialValues.remove(column);

                        switch (match) {
                            default:
                                triggerScan = true;
                                break;
                            // If entry is a playlist, do not re-scan to match previous behavior
                            // and allow persistence of database-only edits until real re-scan
                            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                            case AUDIO_PLAYLISTS_ID:
                                break;
                        }
                    }

                    // If we're publishing this item, perform a blocking scan to
                    // make sure metadata is updated
                    if (MediaColumns.IS_PENDING.equals(column)) {
                        triggerScan = true;
                    }
                }

                Trace.endSection();
            }

            if ("files".equals(qb.getTables())) {
                maybeMarkAsDownload(initialValues);
            }

            // We no longer track location metadata
            if (initialValues.containsKey(ImageColumns.LATITUDE)) {
                initialValues.putNull(ImageColumns.LATITUDE);
            }
            if (initialValues.containsKey(ImageColumns.LONGITUDE)) {
                initialValues.putNull(ImageColumns.LONGITUDE);
            }
        }

        // If we're not updating anything, then we can skip
        if (initialValues.isEmpty()) return 0;

        final boolean isThumbnail;
        switch (match) {
            case IMAGES_THUMBNAILS:
            case IMAGES_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
            case AUDIO_ALBUMART:
            case AUDIO_ALBUMART_ID:
                isThumbnail = true;
                break;
            default:
                isThumbnail = false;
                break;
        }

        // If we're touching columns that would change placement of a file,
        // blend in current values and recalculate path
        if (containsAny(initialValues.keySet(), sPlacementColumns)
                && !initialValues.containsKey(MediaColumns.DATA)
                && !isCallingPackageSystem()
                && !isThumbnail) {
            Trace.beginSection("movement");

            // We only support movement under well-defined collections
            switch (match) {
                case AUDIO_MEDIA_ID:
                case VIDEO_MEDIA_ID:
                case IMAGES_MEDIA_ID:
                case DOWNLOADS_ID:
                    break;
                default:
                    throw new IllegalArgumentException("Movement of " + uri
                            + " which isn't part of well-defined collection not allowed");
            }

            final LocalCallingIdentity token = clearLocalCallingIdentity();
            try (Cursor c = queryForSingleItem(uri,
                    sPlacementColumns.toArray(new String[0]), userWhere, userWhereArgs, null)) {
                for (int i = 0; i < c.getColumnCount(); i++) {
                    final String column = c.getColumnName(i);
                    if (!initialValues.containsKey(column)) {
                        initialValues.put(column, c.getString(i));
                    }
                }
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(e);
            } finally {
                restoreLocalCallingIdentity(token);
            }

            // Regenerate path using blended values; this will throw if caller
            // is attempting to place file into invalid location
            final String beforePath = initialValues.getAsString(MediaColumns.DATA);
            final String beforeVolume = extractVolumeName(beforePath);
            final String beforeOwner = extractPathOwnerPackageName(beforePath);

            initialValues.remove(MediaColumns.DATA);
            ensureNonUniqueFileColumns(match, uri, initialValues, beforePath);

            final String probePath = initialValues.getAsString(MediaColumns.DATA);
            final String probeVolume = extractVolumeName(probePath);
            final String probeOwner = extractPathOwnerPackageName(probePath);
            if (Objects.equals(beforePath, probePath)) {
                Log.d(TAG, "Identical paths " + beforePath + "; not moving");
            } else if (!Objects.equals(beforeVolume, probeVolume)) {
                throw new IllegalArgumentException("Changing volume from " + beforePath + " to "
                        + probePath + " not allowed");
            } else if (!Objects.equals(beforeOwner, probeOwner)) {
                throw new IllegalArgumentException("Changing ownership from " + beforePath + " to "
                        + probePath + " not allowed");
            } else {
                // Now that we've confirmed an actual movement is taking place,
                // ensure we have a unique destination
                initialValues.remove(MediaColumns.DATA);
                ensureUniqueFileColumns(match, uri, initialValues);

                final String afterPath = initialValues.getAsString(MediaColumns.DATA);

                Log.d(TAG, "Moving " + beforePath + " to " + afterPath);
                try {
                    Os.rename(beforePath, afterPath);
                } catch (ErrnoException e) {
                    throw new IllegalStateException(e);
                }
                initialValues.put(MediaColumns.DATA, afterPath);
            }

            Trace.endSection();
        }

        // Make sure any updated paths look sane
        assertFileColumnsSane(match, uri, initialValues);

        // if the media type is being changed, check if it's being changed from image or video
        // to something else
        if (initialValues.containsKey(FileColumns.MEDIA_TYPE)) {
            final int newMediaType = initialValues.getAsInteger(FileColumns.MEDIA_TYPE);

            // If we're changing media types, invalidate any cached "empty"
            // answers for the new collection type.
            MediaDocumentsProvider.onMediaStoreInsert(
                    getContext(), volumeName, newMediaType, -1);

            // If we're changing media types, invalidate any thumbnails
            triggerInvalidate = true;
        }

        if (initialValues.containsKey(FileColumns.DATA)) {
            // If we're changing paths, invalidate any thumbnails
            triggerInvalidate = true;
        }

        // Since the update mutation may prevent us from matching items after
        // it's applied, we need to snapshot affected IDs here
        final LongArray updatedIds = new LongArray();
        if (triggerInvalidate || triggerScan) {
            Trace.beginSection("snapshot");
            final LocalCallingIdentity token = clearLocalCallingIdentity();
            try (Cursor c = qb.query(db, new String[] { FileColumns._ID },
                    userWhere, userWhereArgs, null, null, null)) {
                while (c.moveToNext()) {
                    updatedIds.add(c.getLong(0));
                }
            } finally {
                restoreLocalCallingIdentity(token);
                Trace.endSection();
            }
        }

        // special case renaming directories via MTP.
        // in this case we must update all paths in the database with
        // the directory name as a prefix
        if ((match == MTP_OBJECTS || match == MTP_OBJECTS_ID || match == FILES_DIRECTORY)
                && initialValues != null
                // Is a rename operation
                && ((initialValues.size() == 1 && initialValues.containsKey(FileColumns.DATA))
                // Is a move operation
                || (initialValues.size() == 2 && initialValues.containsKey(FileColumns.DATA)
                && initialValues.containsKey(FileColumns.PARENT)))) {
            String oldPath = null;
            String newPath = initialValues.getAsString(MediaStore.MediaColumns.DATA);
            synchronized (mDirectoryCache) {
                mDirectoryCache.remove(newPath);
            }
            // MtpDatabase will rename the directory first, so we test the new file name
            File f = new File(newPath);
            if (newPath != null && f.isDirectory()) {
                Cursor cursor = qb.query(db, PATH_PROJECTION, userWhere, userWhereArgs, null, null,
                        null, null);
                try {
                    if (cursor != null && cursor.moveToNext()) {
                        oldPath = cursor.getString(1);
                    }
                } finally {
                    FileUtils.closeQuietly(cursor);
                }
                final boolean isDownload = isDownload(newPath);
                if (oldPath != null) {
                    synchronized (mDirectoryCache) {
                        mDirectoryCache.remove(oldPath);
                    }
                    final boolean wasDownload = isDownload(oldPath);
                    // first rename the row for the directory
                    count = qb.update(db, initialValues, userWhere, userWhereArgs);
                    if (count > 0) {
                        // update the paths of any files and folders contained in the directory
                        Object[] bindArgs = new Object[] {
                                newPath,
                                oldPath.length() + 1,
                                oldPath + "/",
                                oldPath + "0",
                                // update bucket_display_name and bucket_id based on new path
                                f.getName(),
                                f.toString().toLowerCase(Locale.ROOT).hashCode(),
                                isDownload
                                };
                        db.execSQL("UPDATE files SET _data=?1||SUBSTR(_data, ?2)" +
                                // also update bucket_display_name
                                ",bucket_display_name=?5" +
                                ",bucket_id=?6" +
                                ",is_download=?7" +
                                " WHERE _data >= ?3 AND _data < ?4;",
                                bindArgs);
                    }

                    if (count > 0) {
                        acceptWithExpansion(helper::notifyChange, uri);
                    }
                    if (f.getName().startsWith(".")) {
                        mMediaScanner.scanFile(new File(newPath));
                    }
                    return count;
                }
            } else if (newPath.toLowerCase(Locale.ROOT).endsWith("/.nomedia")) {
                mMediaScanner.scanFile(new File(newPath).getParentFile());
            }
        }

        final ContentValues values = new ContentValues(initialValues);
        switch (match) {
            case AUDIO_MEDIA_ID: {
                computeAudioLocalizedValues(values);
                computeAudioKeyValues(values);
                // fall-through
            }
            case AUDIO_PLAYLISTS_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
            case FILES_ID:
            case DOWNLOADS_ID: {
                computeDataValues(values);
                break;
            }
        }

        switch (match) {
            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS_ID:
                long playlistId = ContentUris.parseId(uri);
                count = qb.update(db, values, userWhere, userWhereArgs);
                if (count > 0) {
                    updatePlaylistDateModifiedToNow(db, playlistId);
                }
                break;
            case AUDIO_PLAYLISTS_ID_MEMBERS:
                long playlistIdMembers = Long.parseLong(uri.getPathSegments().get(3));
                count = qb.update(db, values, userWhere, userWhereArgs);
                if (count > 0) {
                    updatePlaylistDateModifiedToNow(db, playlistIdMembers);
                }
                break;
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                String moveit = uri.getQueryParameter("move");
                if (moveit != null) {
                    String key = MediaStore.Audio.Playlists.Members.PLAY_ORDER;
                    if (values.containsKey(key)) {
                        int newpos = values.getAsInteger(key);
                        List <String> segments = uri.getPathSegments();
                        long playlist = Long.parseLong(segments.get(3));
                        int oldpos = Integer.parseInt(segments.get(5));
                        int rowsChanged = movePlaylistEntry(volumeName, helper, db, playlist, oldpos, newpos);
                        if (rowsChanged > 0) {
                            updatePlaylistDateModifiedToNow(db, playlist);
                        }

                        return rowsChanged;
                    }
                    throw new IllegalArgumentException("Need to specify " + key +
                            " when using 'move' parameter");
                }
                // fall through
            default:
                count = qb.update(db, values, userWhere, userWhereArgs);
                break;
        }

        // If the caller tried (and failed) to update metadata, the file on disk
        // might have changed, to scan it to collect the latest metadata.
        if (triggerInvalidate || triggerScan) {
            Trace.beginSection("invalidate");
            final LocalCallingIdentity token = clearLocalCallingIdentity();
            try {
                for (int i = 0; i < updatedIds.size(); i++) {
                    final long updatedId = updatedIds.get(i);
                    final Uri updatedUri = Files.getContentUri(volumeName, updatedId);
                    BackgroundThread.getExecutor().execute(() -> {
                        invalidateThumbnails(updatedUri);
                    });

                    if (triggerScan) {
                        try (Cursor c = queryForSingleItem(updatedUri,
                                new String[] { FileColumns.DATA }, null, null, null)) {
                            mMediaScanner.scanFile(new File(c.getString(0)));
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to update metadata for " + updatedUri, e);
                        }
                    }
                }
            } finally {
                restoreLocalCallingIdentity(token);
                Trace.endSection();
            }
        }

        if (count > 0) {
            acceptWithExpansion(helper::notifyChange, uri);
        }
        return count;
    }

    private int movePlaylistEntry(String volumeName, DatabaseHelper helper, SQLiteDatabase db,
            long playlist, int from, int to) {
        if (from == to) {
            return 0;
        }
        db.beginTransaction();
        int numlines = 0;
        Cursor c = null;
        try {
            c = db.query("audio_playlists_map",
                    new String [] {"play_order" },
                    "playlist_id=?", new String[] {"" + playlist}, null, null, "play_order",
                    from + ",1");
            c.moveToFirst();
            int from_play_order = c.getInt(0);
            FileUtils.closeQuietly(c);
            c = db.query("audio_playlists_map",
                    new String [] {"play_order" },
                    "playlist_id=?", new String[] {"" + playlist}, null, null, "play_order",
                    to + ",1");
            c.moveToFirst();
            int to_play_order = c.getInt(0);
            db.execSQL("UPDATE audio_playlists_map SET play_order=-1" +
                    " WHERE play_order=" + from_play_order +
                    " AND playlist_id=" + playlist);
            // We could just run both of the next two statements, but only one of
            // of them will actually do anything, so might as well skip the compile
            // and execute steps.
            if (from  < to) {
                db.execSQL("UPDATE audio_playlists_map SET play_order=play_order-1" +
                        " WHERE play_order<=" + to_play_order +
                        " AND play_order>" + from_play_order +
                        " AND playlist_id=" + playlist);
                numlines = to - from + 1;
            } else {
                db.execSQL("UPDATE audio_playlists_map SET play_order=play_order+1" +
                        " WHERE play_order>=" + to_play_order +
                        " AND play_order<" + from_play_order +
                        " AND playlist_id=" + playlist);
                numlines = from - to + 1;
            }
            db.execSQL("UPDATE audio_playlists_map SET play_order=" + to_play_order +
                    " WHERE play_order=-1 AND playlist_id=" + playlist);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            FileUtils.closeQuietly(c);
        }

        Uri uri = ContentUris.withAppendedId(
                MediaStore.Audio.Playlists.getContentUri(volumeName), playlist);
        // notifyChange() must be called after the database transaction is ended
        // or the listeners will read the old data in the callback
        getContext().getContentResolver().notifyChange(uri, null);

        return numlines;
    }

    private void updatePlaylistDateModifiedToNow(SQLiteDatabase database, long playlistId) {
        ContentValues values = new ContentValues();
        values.put(
                FileColumns.DATE_MODIFIED,
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        );

        database.update(
                MediaStore.Files.TABLE,
                values,
                MediaStore.Files.FileColumns._ID + "=?",
                new String[]{String.valueOf(playlistId)}
        );
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFileCommon(uri, mode, null);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return openFileCommon(uri, mode, signal);
    }

    private ParcelFileDescriptor openFileCommon(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        uri = safeUncanonicalize(uri);

        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);
        final String volumeName = getVolumeName(uri);

        // Handle some legacy cases where we need to redirect thumbnails
        switch (match) {
            case AUDIO_ALBUMART_ID: {
                final long albumId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Audio.Albums.getContentUri(volumeName), albumId);
                return ParcelFileDescriptor.open(ensureThumbnail(targetUri, signal),
                        ParcelFileDescriptor.MODE_READ_ONLY);

            }
            case AUDIO_ALBUMART_FILE_ID: {
                final long audioId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Audio.Media.getContentUri(volumeName), audioId);
                return ParcelFileDescriptor.open(ensureThumbnail(targetUri, signal),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            }
            case VIDEO_MEDIA_ID_THUMBNAIL: {
                final long videoId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Video.Media.getContentUri(volumeName), videoId);
                return ParcelFileDescriptor.open(ensureThumbnail(targetUri, signal),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            }
            case IMAGES_MEDIA_ID_THUMBNAIL: {
                final long imageId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Images.Media.getContentUri(volumeName), imageId);
                return ParcelFileDescriptor.open(ensureThumbnail(targetUri, signal),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }

        return openFileAndEnforcePathPermissionsHelper(uri, match, mode, signal);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        return openTypedAssetFileCommon(uri, mimeTypeFilter, opts, null);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts,
            CancellationSignal signal) throws FileNotFoundException {
        return openTypedAssetFileCommon(uri, mimeTypeFilter, opts, signal);
    }

    private AssetFileDescriptor openTypedAssetFileCommon(Uri uri, String mimeTypeFilter,
            Bundle opts, CancellationSignal signal) throws FileNotFoundException {
        uri = safeUncanonicalize(uri);

        // TODO: enforce that caller has access to this uri

        // Offer thumbnail of media, when requested
        final boolean wantsThumb = (opts != null) && opts.containsKey(ContentResolver.EXTRA_SIZE)
                && (mimeTypeFilter != null) && mimeTypeFilter.startsWith("image/");
        if (wantsThumb) {
            final File thumbFile = ensureThumbnail(uri, signal);
            return new AssetFileDescriptor(
                    ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY),
                    0, AssetFileDescriptor.UNKNOWN_LENGTH);
        }

        // Worst case, return the underlying file
        return new AssetFileDescriptor(openFileCommon(uri, "r", signal), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private File ensureThumbnail(Uri uri, CancellationSignal signal) throws FileNotFoundException {
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        Trace.beginSection("ensureThumbnail");
        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try {
            final File thumbFile;
            switch (match) {
                case AUDIO_ALBUMS_ID: {
                    final String volumeName = MediaStore.getVolumeName(uri);
                    final Uri baseUri = MediaStore.Audio.Media.getContentUri(volumeName);
                    final long albumId = ContentUris.parseId(uri);
                    try (Cursor c = query(baseUri, new String[] { MediaStore.Audio.Media._ID },
                            MediaStore.Audio.Media.ALBUM_ID + "=" + albumId, null, null, signal)) {
                        if (c.moveToFirst()) {
                            final long audioId = c.getLong(0);
                            final Uri targetUri = ContentUris.withAppendedId(baseUri, audioId);
                            return mAudioThumbnailer.ensureThumbnail(targetUri, signal);
                        } else {
                            throw new FileNotFoundException("No media for album " + uri);
                        }
                    }
                }
                case AUDIO_MEDIA_ID:
                    return mAudioThumbnailer.ensureThumbnail(uri, signal);
                case VIDEO_MEDIA_ID:
                    return mVideoThumbnailer.ensureThumbnail(uri, signal);
                case IMAGES_MEDIA_ID:
                    return mImageThumbnailer.ensureThumbnail(uri, signal);
                default:
                    throw new FileNotFoundException();
            }
        } catch (IOException e) {
            Log.w(TAG, e);
            throw new FileNotFoundException(e.getMessage());
        } finally {
            restoreLocalCallingIdentity(token);
            Trace.endSection();
        }
    }

    /**
     * Update the metadata columns for the image residing at given {@link Uri}
     * by reading data from the underlying image.
     */
    private void updateImageMetadata(ContentValues values, File file) {
        final BitmapFactory.Options bitmapOpts = new BitmapFactory.Options();
        bitmapOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bitmapOpts);

        values.put(MediaColumns.WIDTH, bitmapOpts.outWidth);
        values.put(MediaColumns.HEIGHT, bitmapOpts.outHeight);
    }

    /**
     * Return the {@link MediaColumns#DATA} field for the given {@code Uri}.
     */
    File queryForDataFile(Uri uri, CancellationSignal signal)
            throws FileNotFoundException {
        return queryForDataFile(uri, null, null, signal);
    }

    /**
     * Return the {@link MediaColumns#DATA} field for the given {@code Uri}.
     */
    File queryForDataFile(Uri uri, String selection, String[] selectionArgs,
            CancellationSignal signal) throws FileNotFoundException {
        try (Cursor cursor = queryForSingleItem(uri, new String[] { MediaColumns.DATA },
                selection, selectionArgs, signal)) {
            final String data = cursor.getString(0);
            if (TextUtils.isEmpty(data)) {
                throw new FileNotFoundException("Missing path for " + uri);
            } else {
                return new File(data);
            }
        }
    }

    /**
     * Return the {@link Uri} for the given {@code File}.
     */
    Uri queryForMediaUri(File file, CancellationSignal signal) throws FileNotFoundException {
        final String volumeName = MediaStore.getVolumeName(file);
        final Uri uri = Files.getContentUri(volumeName);
        try (Cursor cursor = queryForSingleItem(uri, new String[] { MediaColumns._ID },
                MediaColumns.DATA + "=?", new String[] { file.getAbsolutePath() }, signal)) {
            return ContentUris.withAppendedId(uri, cursor.getLong(0));
        }
    }

    /**
     * Query the given {@link Uri}, expecting only a single item to be found.
     *
     * @throws FileNotFoundException if no items were found, or multiple items
     *             were found, or there was trouble reading the data.
     */
    Cursor queryForSingleItem(Uri uri, String[] projection, String selection,
            String[] selectionArgs, CancellationSignal signal) throws FileNotFoundException {
        final Cursor c = query(uri, projection,
                ContentResolver.createSqlQueryBundle(selection, selectionArgs, null), signal);
        if (c == null) {
            throw new FileNotFoundException("Missing cursor for " + uri);
        } else if (c.getCount() < 1) {
            FileUtils.closeQuietly(c);
            throw new FileNotFoundException("No item at " + uri);
        } else if (c.getCount() > 1) {
            FileUtils.closeQuietly(c);
            throw new FileNotFoundException("Multiple items at " + uri);
        }

        if (c.moveToFirst()) {
            return c;
        } else {
            FileUtils.closeQuietly(c);
            throw new FileNotFoundException("Failed to read row from " + uri);
        }
    }

    /**
     * Compares {@code itemOwner} with package name of {@link LocalCallingIdentity} and throws
     * {@link IllegalStateException} if it doesn't match.
     * Make sure to set calling identity properly before calling.
     */
    private void requireOwnershipForItem(@Nullable String itemOwner, Uri item) {
        final boolean hasOwner = (itemOwner != null);
        final boolean callerIsOwner = Objects.equals(getCallingPackageOrSelf(), itemOwner);
        if (hasOwner && !callerIsOwner) {
            throw new IllegalStateException(
                    "Only owner is able to interact with pending item " + item);
        }
    }

    /**
     * Replacement for {@link #openFileHelper(Uri, String)} which enforces any
     * permissions applicable to the path before returning.
     */
    private ParcelFileDescriptor openFileAndEnforcePathPermissionsHelper(Uri uri, int match,
            String mode, CancellationSignal signal) throws FileNotFoundException {
        final int modeBits = ParcelFileDescriptor.parseMode(mode);
        final boolean forWrite = (modeBits != ParcelFileDescriptor.MODE_READ_ONLY);

        final boolean hasOwnerPackageName = hasOwnerPackageName(uri);
        final String[] projection = new String[] {
                MediaColumns.DATA,
                hasOwnerPackageName ? MediaColumns.OWNER_PACKAGE_NAME : "NULL",
                hasOwnerPackageName ? MediaColumns.IS_PENDING : "0",
        };

        final File file;
        final String ownerPackageName;
        final boolean isPending;
        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try (Cursor c = queryForSingleItem(uri, projection, null, null, signal)) {
            final String data = c.getString(0);
            if (TextUtils.isEmpty(data)) {
                throw new FileNotFoundException("Missing path for " + uri);
            } else {
                file = new File(data).getCanonicalFile();
            }
            ownerPackageName = c.getString(1);
            isPending = c.getInt(2) != 0;
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        } finally {
            restoreLocalCallingIdentity(token);
        }

        checkAccess(uri, file, forWrite);

        if (isPending) {
            requireOwnershipForItem(ownerPackageName, uri);
        }

        final boolean callerIsOwner = Objects.equals(getCallingPackageOrSelf(), ownerPackageName);
        // Figure out if we need to redact contents
        final boolean redactionNeeded = callerIsOwner ? false : isRedactionNeeded(uri);
        final RedactionInfo redactionInfo;
        try {
            redactionInfo = redactionNeeded ? getRedactionRanges(file)
                    : new RedactionInfo(new long[0], new long[0]);
        } catch(IOException e) {
            throw new IllegalStateException(e);
        }

        // Yell if caller requires original, since we can't give it to them
        // unless they have access granted above
        if (redactionNeeded && MediaStore.getRequireOriginal(uri)) {
            throw new UnsupportedOperationException(
                    "Caller must hold ACCESS_MEDIA_LOCATION permission to access original");
        }

        // Kick off metadata update when writing is finished
        final OnCloseListener listener = (e) -> {
            // We always update metadata to reflect the state on disk, even when
            // the remote writer tried claiming an exception
            invalidateThumbnails(uri);

            try {
                switch (match) {
                    case IMAGES_THUMBNAILS_ID:
                    case VIDEO_THUMBNAILS_ID:
                        final ContentValues values = new ContentValues();
                        updateImageMetadata(values, file);
                        update(uri, values, null, null);
                        break;
                    default:
                        mMediaScanner.scanFile(file);
                        break;
                }
            } catch (Exception e2) {
                Log.w(TAG, "Failed to update metadata for " + uri, e2);
            }
        };

        try {
            // First, handle any redaction that is needed for caller
            final ParcelFileDescriptor pfd;
            if (redactionInfo.redactionRanges.length > 0) {
                pfd = RedactingFileDescriptor.open(
                        getContext(),
                        file,
                        modeBits,
                        redactionInfo.redactionRanges,
                        redactionInfo.freeOffsets);
            } else {
                pfd = ParcelFileDescriptor.open(file, modeBits);
            }

            // Second, wrap in any listener that we've requested
            if (!isPending && forWrite && listener != null) {
                return ParcelFileDescriptor.fromPfd(pfd, BackgroundThread.getHandler(), listener);
            } else {
                return pfd;
            }
        } catch (IOException e) {
            if (e instanceof FileNotFoundException) {
                throw (FileNotFoundException) e;
            } else {
                throw new IllegalStateException(e);
            }
        }
    }

    private void deleteIfAllowed(Uri uri, String path) {
        try {
            final File file = new File(path);
            checkAccess(uri, file, true);
            file.delete();
        } catch (Exception e) {
            Log.e(TAG, "Couldn't delete " + path, e);
        }
    }

    @Deprecated
    private boolean isPending(Uri uri) {
        final int match = matchUri(uri, true);
        switch (match) {
            case AUDIO_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
                try (Cursor c = queryForSingleItem(uri,
                        new String[] { MediaColumns.IS_PENDING }, null, null, null)) {
                    return (c.getInt(0) != 0);
                } catch (FileNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            default:
                return false;
        }
    }

    @Deprecated
    private boolean isRedactionNeeded(Uri uri) {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_REDACTION_NEEDED);
    }

    private boolean isRedactionNeeded() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_REDACTION_NEEDED);
    }

    /**
     * Set of Exif tags that should be considered for redaction.
     */
    private static final String[] REDACTED_EXIF_TAGS = new String[] {
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_VERSION_ID,
    };

    /**
     * Set of ISO boxes that should be considered for redaction.
     */
    private static final int[] REDACTED_ISO_BOXES = new int[] {
            IsoInterface.BOX_LOCI,
            IsoInterface.BOX_XYZ,
            IsoInterface.BOX_GPS,
            IsoInterface.BOX_GPS0,
    };

    private static final class RedactionInfo {
        public final long[] redactionRanges;
        public final long[] freeOffsets;
        public RedactionInfo(long[] redactionRanges, long[] freeOffsets) {
            this.redactionRanges = redactionRanges;
            this.freeOffsets = freeOffsets;
        }
    }

    /**
     * Calculates the ranges that need to be redacted for the given file and user that wants to
     * access the file.
     *
     * @param uid UID of the package wanting to access the file
     * @param path File path
     * @return Ranges that should be redacted.
     *
     * @throws IOException if an error occurs while calculating the redaction ranges
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    @NonNull
    public long[] getRedactionRanges(String path, int uid) throws IOException {
        LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));

        final File file = new File(path);
        long[] res = new long[0];
        try {
            if (isRedactionNeeded()) {
                res = getRedactionRanges(file).redactionRanges;
            }
        } finally {
            restoreLocalCallingIdentity(token);
        }
        return res;
    }

    /**
     * Calculates the ranges containing sensitive metadata that should be redacted if the caller
     * doesn't have the required permissions.
     *
     * @param file file to be redacted
     * @return the ranges to be redacted in a RedactionInfo object, could be empty redaction ranges
     * if there's sensitive metadata
     * @throws IOException if an IOException happens while calculating the redaction ranges
     */
    private RedactionInfo getRedactionRanges(File file) throws IOException {
        Trace.beginSection("getRedactionRanges");
        final LongArray res = new LongArray();
        final LongArray freeOffsets = new LongArray();
        try (FileInputStream is = new FileInputStream(file)) {
            final Set<String> redactedXmpTags = new ArraySet<>(Arrays.asList(REDACTED_EXIF_TAGS));
            final String mimeType = MediaFile.getMimeTypeForFile(file.getPath());
            if (ExifInterface.isSupportedMimeType(mimeType)) {
                final ExifInterface exif = new ExifInterface(is.getFD());
                for (String tag : REDACTED_EXIF_TAGS) {
                    final long[] range = exif.getAttributeRange(tag);
                    if (range != null) {
                        res.add(range[0]);
                        res.add(range[0] + range[1]);
                    }
                }
                // Redact xmp where present
                final XmpInterface exifXmp = XmpInterface.fromContainer(exif, redactedXmpTags);
                res.addAll(exifXmp.getRedactionRanges());
            }

            if (IsoInterface.isSupportedMimeType(mimeType)) {
                final IsoInterface iso = IsoInterface.fromFileDescriptor(is.getFD());
                for (int box : REDACTED_ISO_BOXES) {
                    final long[] ranges = iso.getBoxRanges(box);
                    for (int i = 0; i < ranges.length; i += 2) {
                        long boxTypeOffset = ranges[i] - 4;
                        freeOffsets.add(boxTypeOffset);
                        res.add(boxTypeOffset);
                        res.add(ranges[i + 1]);
                    }
                }
                // Redact xmp where present
                final XmpInterface isoXmp = XmpInterface.fromContainer(iso, redactedXmpTags);
                res.addAll(isoXmp.getRedactionRanges());
            }
        } catch (IOException e) {
            throw new IOException("Failed to redact " + file, e);
        }
        Trace.endSection();
        return new RedactionInfo(res.toArray(), freeOffsets.toArray());
    }

    /**
     * Checks if the app identified by the given UID is allowed to open the given file for the given
     * access mode.
     *
     * @param path the path of the file to be opened
     * @param uid UID of the app requesting to open the file
     * @param forWrite specifies if the file is to be opened for write
     * @return 0 upon success. If the operation is illegal or not permitted, returns
     * -{@link OsConstants#ENOENT} to prevent malicious apps from distinguishing whether a file
     * they have no access to exists or not.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int isOpenAllowed(String path, int uid, boolean forWrite) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            if (appSpecificDir != null) {
                return checkAppSpecificDirAccess(appSpecificDir);
            }
            final String mimeType = MediaFile.getMimeTypeForFile(path);
            final Uri contentUri = getContentUriForFile(path, mimeType);
            final String[] projection = new String[]{
                    MediaColumns._ID,
                    MediaColumns.OWNER_PACKAGE_NAME,
                    MediaColumns.IS_PENDING};
            final String selection = MediaColumns.DATA + "=?";
            final String[] selectionArgs = new String[] { path };
            final Uri fileUri;
            boolean isPending = false;
            String ownerPackageName = null;
            try (final Cursor c = queryForSingleItem(contentUri, projection, selection,
                    selectionArgs, null)) {
                fileUri = ContentUris.withAppendedId(contentUri, c.getInt(0));
                ownerPackageName = c.getString(1);
                isPending = c.getInt(2) != 0;
            }

            final File file = new File(path);
            checkAccess(fileUri, file, forWrite);

            if (isPending) {
                requireOwnershipForItem(ownerPackageName, fileUri);
            }
            return 0;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Couldn't find file: " + path);
            // It's an illegal state because FuseDaemon shouldn't forward the request if
            // the file doesn't exist.
            throw new IllegalStateException(e);
        } catch (IllegalStateException | SecurityException e) {
            Log.e(TAG, "Permission to access file: " + path + " is denied");
            return -OsConstants.ENOENT;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Returns 0 if access is allowed, -ENOENT otherwise.
     * <p> Assumes that {@code mCallingIdentity} has been properly set to reflect the calling
     * package.
     */
    private int checkAppSpecificDirAccess(String appSpecificDir) {
        for (String packageName : mCallingIdentity.get().getSharedPackageNames()) {
            if (appSpecificDir.toLowerCase(Locale.ROOT)
                    .equals(packageName.toLowerCase(Locale.ROOT))) {
                return 0;
            }
        }
        Log.e(TAG, "Cannot access file under another app's external directory!");
        // We treat this error as if the directory doesn't exist to make it harder for
        // apps to snoop around whether other apps exist or not.
        return -OsConstants.ENOENT;
    }

    /**
     * Returns the name of the top level directory, or null if the path doesn't go through the
     * external storage directory.
     */
    @Nullable
    private static String extractTopLevelDir(String path) {
        Matcher m = PATTERN_TOP_LEVEL_DIR.matcher(path);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * @throws IllegalStateException if path is invalid or doesn't match a volume.
     */
    @NonNull
    private static Uri getContentUriForFile(@NonNull String filePath, @NonNull String mimeType) {
        final String volName = MediaStore.getVolumeName(new File(filePath));
        final String topLevelDir = extractTopLevelDir(filePath);
        if (topLevelDir == null) {
            // If the file path doesn't match the external storage directory, we use the files URI
            // as default and let #insert enforce the restrictions
            return Files.getContentUri(volName);
        }

        switch (topLevelDir) {
            case DIRECTORY_MUSIC:
            case DIRECTORY_PODCASTS:
            case DIRECTORY_RINGTONES:
            case DIRECTORY_ALARMS:
            case DIRECTORY_NOTIFICATIONS:
            case DIRECTORY_AUDIOBOOKS:
                return Audio.Media.getContentUri(volName);
            //TODO(b/143864294)
            case DIRECTORY_PICTURES:
                return Images.Media.getContentUri(volName);
            case DIRECTORY_MOVIES:
                return Video.Media.getContentUri(volName);
            case DIRECTORY_DCIM:
                if (mimeType.toLowerCase(Locale.ROOT).startsWith("image")) {
                    return Images.Media.getContentUri(volName);
                } else {
                    return Video.Media.getContentUri(volName);
                }
            case DIRECTORY_DOWNLOADS:
            case DIRECTORY_DOCUMENTS:
                break;
            default:
                Log.w(TAG, "Forgot to handle a top level directory in getContentUriForFile?");
        }
        return Files.getContentUri(volName);
    }

    private boolean fileExists(@NonNull String absolutePath, @NonNull Uri contentUri) {
        // We don't care about specific columns in the match,
        // we just want to check IF there's a match
        final String[] projection = {};
        final String selection = FileColumns.DATA + " = ?";
        final String[] selectionArgs = {absolutePath};

        try (final Cursor c = query(contentUri, projection, selection, selectionArgs, null)) {
            // Shouldn't return null
            return c.getCount() > 0;
        }
    }

    /**
     * Enforces file creation restrictions (see return values) for the given file on behalf of the
     * app with the given {@code uid}. If the file is is added to the shared storage, creates a
     * database entry for it.
     * <p> Does NOT create file.
     *
     * @param path the path of the file
     * @param uid UID of the app requesting to create the file
     * @return In case of success, 0. If the operation is illegal or not permitted, returns the
     * appropriate negated {@code errno} value:
     * <ul>
     * <li>ENOENT if the app tries to create file in other app's external dir
     * <li>EEXIST if the file already exists
     * <li>EPERM if the file type doesn't match the relative path
     * <li>EIO in case of any other I/O exception
     * </ul>
     *
     * @throws IllegalStateException if given path is invalid.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int insertFileIfNecessary(@NonNull String path, int uid) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            // App dirs are not indexed, so we don't create an entry for the file.
            if (appSpecificDir != null) {
                return checkAppSpecificDirAccess(appSpecificDir);
            }

            final String mimeType = MediaFile.getMimeTypeForFile(path);
            final Uri contentUri = getContentUriForFile(path, mimeType);
            if (fileExists(path, contentUri)) {
                return -OsConstants.EEXIST;
            }

            final String displayName = extractDisplayName(path);
            final String callingPackageName = getCallingPackageOrSelf();
            final String relativePath = extractRelativePath(path);

            ContentValues values = new ContentValues();
            values.put(FileColumns.RELATIVE_PATH, relativePath);
            values.put(FileColumns.DISPLAY_NAME, displayName);
            values.put(FileColumns.OWNER_PACKAGE_NAME, callingPackageName);
            values.put(MediaColumns.MIME_TYPE, mimeType);

            final Uri item = insert(contentUri, values);
            if (item == null) {
                return -OsConstants.EPERM;
            }
            return 0;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "insertFileIfNecessary failed", e);
            return -OsConstants.EPERM;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    private static int deleteFileInAppSpecificDir(@NonNull String path) {
        final File toDelete = new File(path);
        if (toDelete.delete()) {
            return 0;
        } else {
            return -OsConstants.ENOENT;
        }
    }

    /**
     * Deletes file with the given {@code path} on behalf of the app with the given {@code uid}.
     * <p>Before deleting, checks if app has permissions to delete this file.
     *
     * @param path the path of the file
     * @param uid UID of the app requesting to delete the file
     * @return 0 upon success.
     * In case of error, return the appropriate negated {@code errno} value:
     * <ul>
     * <li>ENOENT if the file does not exist or if the app tries to delete file in another app's
     * external dir
     * <li>EPERM a security exception was thrown by {@link #delete}
     * </ul>
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int deleteFile(@NonNull String path, int uid) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Check if app is deleting a file under an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            // Trying to create file under some app's external storage dir
            if (appSpecificDir != null) {
                int negErrno = checkAppSpecificDirAccess(appSpecificDir);
                if (negErrno == 0) {
                    return deleteFileInAppSpecificDir(path);
                } else {
                    return negErrno;
                }
            }

            final String mimeType = MediaFile.getMimeTypeForFile(path);
            final Uri contentUri = getContentUriForFile(path, mimeType);
            final String where = FileColumns.DATA + " = ?";
            final String[] whereArgs = {path};

            if (delete(contentUri, where, whereArgs) == 0) {
                return -OsConstants.ENOENT;
            } else {
                // success - 1 file was deleted
                return 0;
            }

        } catch (SecurityException e) {
            Log.e(TAG, "File deletion not allowed", e);
            return -OsConstants.EPERM;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    private boolean checkCallingPermissionGlobal(Uri uri, boolean forWrite) {
        // System internals can work with all media
        if (isCallingPackageSystem()) {
            return true;
        }

        // Check if caller is known to be owner of this item, to speed up
        // performance of our permission checks
        final int table = matchUri(uri, true);
        switch (table) {
            case AUDIO_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
            case FILES_ID:
            case DOWNLOADS_ID:
                final long id = ContentUris.parseId(uri);
                if (mCallingIdentity.get().isOwned(id)) {
                    return true;
                }
        }

        // Outstanding grant means they get access
        if (getContext().checkUriPermission(uri, mCallingIdentity.get().pid,
                mCallingIdentity.get().uid, forWrite
                        ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        : Intent.FLAG_GRANT_READ_URI_PERMISSION) == PERMISSION_GRANTED) {
            return true;
        }

        return false;
    }

    private boolean checkCallingPermissionLegacy(Uri uri, boolean forWrite, String callingPackage) {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY);
    }

    @Deprecated
    private boolean checkCallingPermissionAudio(boolean forWrite, String callingPackage) {
        if (forWrite) {
            return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_AUDIO);
        } else {
            return mCallingIdentity.get().hasPermission(PERMISSION_READ_AUDIO);
        }
    }

    @Deprecated
    private boolean checkCallingPermissionVideo(boolean forWrite, String callingPackage) {
        if (forWrite) {
            return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_VIDEO);
        } else {
            return mCallingIdentity.get().hasPermission(PERMISSION_READ_VIDEO);
        }
    }

    @Deprecated
    private boolean checkCallingPermissionImages(boolean forWrite, String callingPackage) {
        if (forWrite) {
            return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_IMAGES);
        } else {
            return mCallingIdentity.get().hasPermission(PERMISSION_READ_IMAGES);
        }
    }

    /**
     * Enforce that caller has access to the given {@link Uri}.
     *
     * @throws SecurityException if access isn't allowed.
     */
    private void enforceCallingPermission(Uri uri, boolean forWrite) {
        Trace.beginSection("enforceCallingPermission");
        try {
            enforceCallingPermissionInternal(uri, forWrite);
        } finally {
            Trace.endSection();
        }
    }

    private void enforceCallingPermissionInternal(Uri uri, boolean forWrite) {
        // Try a simple global check first before falling back to performing a
        // simple query to probe for access.
        if (checkCallingPermissionGlobal(uri, forWrite)) {
            // Access allowed, yay!
            return;
        }

        final DatabaseHelper helper;
        final SQLiteDatabase db;
        try {
            helper = getDatabaseForUri(uri);
            db = helper.getReadableDatabase();
        } catch (VolumeNotFoundException e) {
            throw e.rethrowAsIllegalArgumentException();
        }

        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int table = matchUri(uri, allowHidden);

        // First, check to see if caller has direct write access
        if (forWrite) {
            final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE, uri, table, null);
            try (Cursor c = qb.query(db, new String[0], null, null, null, null, null)) {
                if (c.moveToFirst()) {
                    // Direct write access granted, yay!
                    return;
                }
            }
        }

        // We only allow the user to grant access to specific media items in
        // strongly typed collections; never to broad collections
        boolean allowUserGrant = false;
        final int matchUri = matchUri(uri, true);
        switch (matchUri) {
            case IMAGES_MEDIA_ID:
            case AUDIO_MEDIA_ID:
            case VIDEO_MEDIA_ID:
                allowUserGrant = true;
                break;
        }

        // Second, check to see if caller has direct read access
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_QUERY, uri, table, null);
        try (Cursor c = qb.query(db, new String[0], null, null, null, null, null)) {
            if (c.moveToFirst()) {
                if (!forWrite) {
                    // Direct read access granted, yay!
                    return;
                } else if (allowUserGrant) {
                    // Caller has read access, but they wanted to write, and
                    // they'll need to get the user to grant that access
                    final Context context = getContext();
                    final PendingIntent intent = PendingIntent.getActivity(context, 42,
                            new Intent(null, uri, context, PermissionActivity.class),
                            FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);

                    final Icon icon = getCollectionIcon(uri);
                    final RemoteAction action = new RemoteAction(icon,
                            context.getText(R.string.permission_required_action),
                            context.getText(R.string.permission_required_action),
                            intent);

                    throw new RecoverableSecurityException(new SecurityException(
                            getCallingPackageOrSelf() + " has no access to " + uri),
                            context.getText(R.string.permission_required), action);
                }
            }
        }

        throw new SecurityException(getCallingPackageOrSelf() + " has no access to " + uri);
    }

    private Icon getCollectionIcon(Uri uri) {
        final PackageManager pm = getContext().getPackageManager();
        final String type = uri.getPathSegments().get(1);
        final String groupName;
        switch (type) {
            default: groupName = android.Manifest.permission_group.STORAGE; break;
        }
        try {
            final PermissionGroupInfo perm = pm.getPermissionGroupInfo(groupName, 0);
            return Icon.createWithResource(perm.packageName, perm.icon);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkAccess(Uri uri, File file, boolean isWrite) throws FileNotFoundException {
        // First, does caller have the needed row-level access?
        enforceCallingPermission(uri, isWrite);

        // Second, does the path look sane?
        if (!FileUtils.contains(Environment.getStorageDirectory(), file)) {
            checkWorldReadAccess(file.getAbsolutePath());
        }
    }

    /**
     * Check whether the path is a world-readable file
     */
    private static void checkWorldReadAccess(String path) throws FileNotFoundException {
        // Path has already been canonicalized, and we relax the check to look
        // at groups to support runtime storage permissions.
        final int accessBits = path.startsWith("/storage/") ? OsConstants.S_IRGRP
                : OsConstants.S_IROTH;
        try {
            StructStat stat = Os.stat(path);
            if (OsConstants.S_ISREG(stat.st_mode) &&
                ((stat.st_mode & accessBits) == accessBits)) {
                checkLeadingPathComponentsWorldExecutable(path);
                return;
            }
        } catch (ErrnoException e) {
            // couldn't stat the file, either it doesn't exist or isn't
            // accessible to us
        }

        throw new FileNotFoundException("Can't access " + path);
    }

    private static void checkLeadingPathComponentsWorldExecutable(String filePath)
            throws FileNotFoundException {
        File parent = new File(filePath).getParentFile();

        // Path has already been canonicalized, and we relax the check to look
        // at groups to support runtime storage permissions.
        final int accessBits = filePath.startsWith("/storage/") ? OsConstants.S_IXGRP
                : OsConstants.S_IXOTH;

        while (parent != null) {
            if (! parent.exists()) {
                // parent dir doesn't exist, give up
                throw new FileNotFoundException("access denied");
            }
            try {
                StructStat stat = Os.stat(parent.getPath());
                if ((stat.st_mode & accessBits) != accessBits) {
                    // the parent dir doesn't have the appropriate access
                    throw new FileNotFoundException("Can't access " + filePath);
                }
            } catch (ErrnoException e1) {
                // couldn't stat() parent
                throw new FileNotFoundException("Can't access " + filePath);
            }
            parent = parent.getParentFile();
        }
    }

    private static class FallbackException extends Exception {
        private final int mThrowSdkVersion;

        public FallbackException(String message, int throwSdkVersion) {
            super(message);
            mThrowSdkVersion = throwSdkVersion;
        }

        public IllegalArgumentException rethrowAsIllegalArgumentException() {
            throw new IllegalArgumentException(getMessage());
        }

        public Cursor translateForQuery(int targetSdkVersion) {
            if (targetSdkVersion >= mThrowSdkVersion) {
                throw new IllegalArgumentException(getMessage());
            } else {
                Log.w(TAG, getMessage());
                return null;
            }
        }

        public Uri translateForInsert(int targetSdkVersion) {
            if (targetSdkVersion >= mThrowSdkVersion) {
                throw new IllegalArgumentException(getMessage());
            } else {
                Log.w(TAG, getMessage());
                return null;
            }
        }

        public int translateForUpdateDelete(int targetSdkVersion) {
            if (targetSdkVersion >= mThrowSdkVersion) {
                throw new IllegalArgumentException(getMessage());
            } else {
                Log.w(TAG, getMessage());
                return 0;
            }
        }
    }

    static class VolumeNotFoundException extends FallbackException {
        public VolumeNotFoundException(String volumeName) {
            super("Volume " + volumeName + " not found", Build.VERSION_CODES.Q);
        }
    }

    static class VolumeArgumentException extends FallbackException {
        public VolumeArgumentException(File actual, Collection<File> allowed) {
            super("Requested path " + actual + " doesn't appear under " + allowed,
                    Build.VERSION_CODES.Q);
        }
    }

    private @NonNull DatabaseHelper getDatabaseForUri(Uri uri) throws VolumeNotFoundException {
        final String volumeName = resolveVolumeName(uri);
        synchronized (mAttachedVolumeNames) {
            if (!mAttachedVolumeNames.contains(volumeName)) {
                throw new VolumeNotFoundException(volumeName);
            }
        }
        if (MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
            return mInternalDatabase;
        } else {
            return mExternalDatabase;
        }
    }

    static boolean isMediaDatabaseName(String name) {
        if (INTERNAL_DATABASE_NAME.equals(name)) {
            return true;
        }
        if (EXTERNAL_DATABASE_NAME.equals(name)) {
            return true;
        }
        if (name.startsWith("external-") && name.endsWith(".db")) {
            return true;
        }
        return false;
    }

    static boolean isInternalMediaDatabaseName(String name) {
        if (INTERNAL_DATABASE_NAME.equals(name)) {
            return true;
        }
        return false;
    }

    private void attachVolume(Uri uri) {
        attachVolume(MediaStore.getVolumeName(uri));
    }

    public Uri attachVolume(String volume) {
        if (mCallingIdentity.get().pid != android.os.Process.myPid()) {
            throw new SecurityException(
                    "Opening and closing databases not allowed.");
        }

        // Quick sanity check for shady volume names
        MediaStore.checkArgumentVolumeName(volume);

        // Quick sanity check that volume actually exists
        if (!MediaStore.VOLUME_INTERNAL.equals(volume)) {
            try {
                getVolumePath(volume);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Volume " + volume + " currently unavailable", e);
            }
        }

        synchronized (mAttachedVolumeNames) {
            mAttachedVolumeNames.add(volume);
        }

        final Uri uri = MediaStore.AUTHORITY_URI.buildUpon().appendPath(volume).build();
        getContext().getContentResolver().notifyChange(uri, null);
        if (LOCAL_LOGV) Log.v(TAG, "Attached volume: " + volume);
        if (!MediaStore.VOLUME_INTERNAL.equals(volume)) {
            final DatabaseHelper helper = mInternalDatabase;
            ensureDefaultFolders(volume, helper, helper.getWritableDatabase());
        }
        return uri;
    }

    private void detachVolume(Uri uri) {
        detachVolume(MediaStore.getVolumeName(uri));
    }

    public void detachVolume(String volume) {
        if (mCallingIdentity.get().pid != android.os.Process.myPid()) {
            throw new SecurityException(
                    "Opening and closing databases not allowed.");
        }

        // Quick sanity check for shady volume names
        MediaStore.checkArgumentVolumeName(volume);

        if (MediaStore.VOLUME_INTERNAL.equals(volume)) {
            throw new UnsupportedOperationException(
                    "Deleting the internal volume is not allowed");
        }

        // Signal any scanning to shut down
        mMediaScanner.onDetachVolume(volume);

        synchronized (mAttachedVolumeNames) {
            mAttachedVolumeNames.remove(volume);
        }

        final Uri uri = MediaStore.AUTHORITY_URI.buildUpon().appendPath(volume).build();
        getContext().getContentResolver().notifyChange(uri, null);
        if (LOCAL_LOGV) Log.v(TAG, "Detached volume: " + volume);
    }

    /*
     * Useful commands to enable debugging:
     * $ adb shell setprop log.tag.MediaProvider VERBOSE
     * $ adb shell setprop db.log.slow_query_threshold.`adb shell cat \
     *       /data/system/packages.list |grep "com.android.providers.media " |cut -b 29-33` 0
     * $ adb shell setprop db.log.bindargs 1
     */

    public static final String TAG = "MediaProvider";
    public static final boolean LOCAL_LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    static final String INTERNAL_DATABASE_NAME = "internal.db";
    static final String EXTERNAL_DATABASE_NAME = "external.db";

    @GuardedBy("mAttachedVolumeNames")
    private final ArraySet<String> mAttachedVolumeNames = new ArraySet<>();
    @GuardedBy("mCustomCollators")
    private final ArraySet<String> mCustomCollators = new ArraySet<>();

    private MediaScanner mMediaScanner;

    private DatabaseHelper mInternalDatabase;
    private DatabaseHelper mExternalDatabase;

    // name of the volume currently being scanned by the media scanner (or null)
    private String mMediaScannerVolume;

    // current FAT volume ID
    private int mVolumeId = -1;

    // WARNING: the values of IMAGES_MEDIA, AUDIO_MEDIA, and VIDEO_MEDIA and AUDIO_PLAYLISTS
    // are stored in the "files" table, so do not renumber them unless you also add
    // a corresponding database upgrade step for it.
    private static final int IMAGES_MEDIA = 1;
    private static final int IMAGES_MEDIA_ID = 2;
    private static final int IMAGES_MEDIA_ID_THUMBNAIL = 3;
    private static final int IMAGES_THUMBNAILS = 4;
    private static final int IMAGES_THUMBNAILS_ID = 5;

    private static final int AUDIO_MEDIA = 100;
    private static final int AUDIO_MEDIA_ID = 101;
    private static final int AUDIO_MEDIA_ID_GENRES = 102;
    private static final int AUDIO_MEDIA_ID_GENRES_ID = 103;
    private static final int AUDIO_MEDIA_ID_PLAYLISTS = 104;
    private static final int AUDIO_MEDIA_ID_PLAYLISTS_ID = 105;
    private static final int AUDIO_GENRES = 106;
    private static final int AUDIO_GENRES_ID = 107;
    private static final int AUDIO_GENRES_ID_MEMBERS = 108;
    private static final int AUDIO_GENRES_ALL_MEMBERS = 109;
    private static final int AUDIO_PLAYLISTS = 110;
    private static final int AUDIO_PLAYLISTS_ID = 111;
    private static final int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    private static final int AUDIO_PLAYLISTS_ID_MEMBERS_ID = 113;
    private static final int AUDIO_ARTISTS = 114;
    private static final int AUDIO_ARTISTS_ID = 115;
    private static final int AUDIO_ALBUMS = 116;
    private static final int AUDIO_ALBUMS_ID = 117;
    private static final int AUDIO_ARTISTS_ID_ALBUMS = 118;
    private static final int AUDIO_ALBUMART = 119;
    private static final int AUDIO_ALBUMART_ID = 120;
    private static final int AUDIO_ALBUMART_FILE_ID = 121;

    private static final int VIDEO_MEDIA = 200;
    private static final int VIDEO_MEDIA_ID = 201;
    private static final int VIDEO_MEDIA_ID_THUMBNAIL = 202;
    private static final int VIDEO_THUMBNAILS = 203;
    private static final int VIDEO_THUMBNAILS_ID = 204;

    private static final int VOLUMES = 300;
    private static final int VOLUMES_ID = 301;

    private static final int MEDIA_SCANNER = 500;

    private static final int FS_ID = 600;
    private static final int VERSION = 601;

    private static final int FILES = 700;
    private static final int FILES_ID = 701;

    // Used only by the MTP implementation
    private static final int MTP_OBJECTS = 702;
    private static final int MTP_OBJECTS_ID = 703;
    private static final int MTP_OBJECT_REFERENCES = 704;

    // Used only to invoke special logic for directories
    private static final int FILES_DIRECTORY = 706;

    private static final int DOWNLOADS = 800;
    private static final int DOWNLOADS_ID = 801;

    /** Flag if we're running as {@link MediaStore#AUTHORITY_LEGACY} */
    private boolean mLegacyProvider;
    private LocalUriMatcher mUriMatcher;

    private static final String[] PATH_PROJECTION = new String[] {
        MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
    };

    private static final String OBJECT_REFERENCES_QUERY =
        "SELECT " + Audio.Playlists.Members.AUDIO_ID + " FROM audio_playlists_map"
        + " WHERE " + Audio.Playlists.Members.PLAYLIST_ID + "=?"
        + " ORDER BY " + Audio.Playlists.Members.PLAY_ORDER;

    private int matchUri(Uri uri, boolean allowHidden) {
        return mUriMatcher.matchUri(uri, allowHidden);
    }

    static class LocalUriMatcher {
        private final UriMatcher mPublic = new UriMatcher(UriMatcher.NO_MATCH);
        private final UriMatcher mHidden = new UriMatcher(UriMatcher.NO_MATCH);

        public int matchUri(Uri uri, boolean allowHidden) {
            final int publicMatch = mPublic.match(uri);
            if (publicMatch != UriMatcher.NO_MATCH) {
                return publicMatch;
            }

            final int hiddenMatch = mHidden.match(uri);
            if (hiddenMatch != UriMatcher.NO_MATCH) {
                // Detect callers asking about hidden behavior by looking closer when
                // the matchers diverge; we only care about apps that are explicitly
                // targeting a specific public API level.
                if (!allowHidden) {
                    throw new IllegalStateException("Unknown URL: " + uri + " is hidden API");
                }
                return hiddenMatch;
            }

            return UriMatcher.NO_MATCH;
        }

        public LocalUriMatcher(String auth) {
            mPublic.addURI(auth, "*/images/media", IMAGES_MEDIA);
            mPublic.addURI(auth, "*/images/media/#", IMAGES_MEDIA_ID);
            mPublic.addURI(auth, "*/images/media/#/thumbnail", IMAGES_MEDIA_ID_THUMBNAIL);
            mPublic.addURI(auth, "*/images/thumbnails", IMAGES_THUMBNAILS);
            mPublic.addURI(auth, "*/images/thumbnails/#", IMAGES_THUMBNAILS_ID);

            mPublic.addURI(auth, "*/audio/media", AUDIO_MEDIA);
            mPublic.addURI(auth, "*/audio/media/#", AUDIO_MEDIA_ID);
            mPublic.addURI(auth, "*/audio/media/#/genres", AUDIO_MEDIA_ID_GENRES);
            mPublic.addURI(auth, "*/audio/media/#/genres/#", AUDIO_MEDIA_ID_GENRES_ID);
            mHidden.addURI(auth, "*/audio/media/#/playlists", AUDIO_MEDIA_ID_PLAYLISTS);
            mHidden.addURI(auth, "*/audio/media/#/playlists/#", AUDIO_MEDIA_ID_PLAYLISTS_ID);
            mPublic.addURI(auth, "*/audio/genres", AUDIO_GENRES);
            mPublic.addURI(auth, "*/audio/genres/#", AUDIO_GENRES_ID);
            mPublic.addURI(auth, "*/audio/genres/#/members", AUDIO_GENRES_ID_MEMBERS);
            // TODO: not actually defined in API, but CTS tested
            mPublic.addURI(auth, "*/audio/genres/all/members", AUDIO_GENRES_ALL_MEMBERS);
            mPublic.addURI(auth, "*/audio/playlists", AUDIO_PLAYLISTS);
            mPublic.addURI(auth, "*/audio/playlists/#", AUDIO_PLAYLISTS_ID);
            mPublic.addURI(auth, "*/audio/playlists/#/members", AUDIO_PLAYLISTS_ID_MEMBERS);
            mPublic.addURI(auth, "*/audio/playlists/#/members/#", AUDIO_PLAYLISTS_ID_MEMBERS_ID);
            mPublic.addURI(auth, "*/audio/artists", AUDIO_ARTISTS);
            mPublic.addURI(auth, "*/audio/artists/#", AUDIO_ARTISTS_ID);
            mPublic.addURI(auth, "*/audio/artists/#/albums", AUDIO_ARTISTS_ID_ALBUMS);
            mPublic.addURI(auth, "*/audio/albums", AUDIO_ALBUMS);
            mPublic.addURI(auth, "*/audio/albums/#", AUDIO_ALBUMS_ID);
            // TODO: not actually defined in API, but CTS tested
            mPublic.addURI(auth, "*/audio/albumart", AUDIO_ALBUMART);
            // TODO: not actually defined in API, but CTS tested
            mPublic.addURI(auth, "*/audio/albumart/#", AUDIO_ALBUMART_ID);
            // TODO: not actually defined in API, but CTS tested
            mPublic.addURI(auth, "*/audio/media/#/albumart", AUDIO_ALBUMART_FILE_ID);

            mPublic.addURI(auth, "*/video/media", VIDEO_MEDIA);
            mPublic.addURI(auth, "*/video/media/#", VIDEO_MEDIA_ID);
            mPublic.addURI(auth, "*/video/media/#/thumbnail", VIDEO_MEDIA_ID_THUMBNAIL);
            mPublic.addURI(auth, "*/video/thumbnails", VIDEO_THUMBNAILS);
            mPublic.addURI(auth, "*/video/thumbnails/#", VIDEO_THUMBNAILS_ID);

            mPublic.addURI(auth, "*/media_scanner", MEDIA_SCANNER);

            // NOTE: technically hidden, since Uri is never exposed
            mPublic.addURI(auth, "*/fs_id", FS_ID);
            // NOTE: technically hidden, since Uri is never exposed
            mPublic.addURI(auth, "*/version", VERSION);

            mHidden.addURI(auth, "*", VOLUMES_ID);
            mHidden.addURI(auth, null, VOLUMES);

            // Used by MTP implementation
            mPublic.addURI(auth, "*/file", FILES);
            mPublic.addURI(auth, "*/file/#", FILES_ID);
            mHidden.addURI(auth, "*/object", MTP_OBJECTS);
            mHidden.addURI(auth, "*/object/#", MTP_OBJECTS_ID);
            mHidden.addURI(auth, "*/object/#/references", MTP_OBJECT_REFERENCES);

            // Used only to trigger special logic for directories
            mHidden.addURI(auth, "*/dir", FILES_DIRECTORY);

            mPublic.addURI(auth, "*/downloads", DOWNLOADS);
            mPublic.addURI(auth, "*/downloads/#", DOWNLOADS_ID);
        }
    }

    /**
     * Set of columns that can be safely mutated by external callers; all other
     * columns are treated as read-only, since they reflect what the media
     * scanner found on disk, and any mutations would be overwritten the next
     * time the media was scanned.
     */
    private static final ArraySet<String> sMutableColumns = new ArraySet<>();

    {
        sMutableColumns.add(MediaStore.MediaColumns.DATA);
        sMutableColumns.add(MediaStore.MediaColumns.RELATIVE_PATH);
        sMutableColumns.add(MediaStore.MediaColumns.DISPLAY_NAME);
        sMutableColumns.add(MediaStore.MediaColumns.IS_PENDING);
        sMutableColumns.add(MediaStore.MediaColumns.IS_TRASHED);
        sMutableColumns.add(MediaStore.MediaColumns.DATE_EXPIRES);

        sMutableColumns.add(MediaStore.Audio.AudioColumns.BOOKMARK);

        sMutableColumns.add(MediaStore.Video.VideoColumns.TAGS);
        sMutableColumns.add(MediaStore.Video.VideoColumns.CATEGORY);
        sMutableColumns.add(MediaStore.Video.VideoColumns.BOOKMARK);

        sMutableColumns.add(MediaStore.Audio.Playlists.NAME);
        sMutableColumns.add(MediaStore.Audio.Playlists.Members.AUDIO_ID);
        sMutableColumns.add(MediaStore.Audio.Playlists.Members.PLAY_ORDER);

        sMutableColumns.add(MediaStore.Files.FileColumns.MIME_TYPE);
        sMutableColumns.add(MediaStore.Files.FileColumns.MEDIA_TYPE);
    }

    /**
     * Set of columns that affect placement of files on disk.
     */
    private static final ArraySet<String> sPlacementColumns = new ArraySet<>();

    {
        sPlacementColumns.add(MediaStore.MediaColumns.DATA);
        sPlacementColumns.add(MediaStore.MediaColumns.RELATIVE_PATH);
        sPlacementColumns.add(MediaStore.MediaColumns.DISPLAY_NAME);
        sPlacementColumns.add(MediaStore.MediaColumns.MIME_TYPE);
    }

    /**
     * List of abusive custom columns that we're willing to allow via
     * {@link SQLiteQueryBuilder#setProjectionGreylist(List)}.
     */
    static final ArrayList<Pattern> sGreylist = new ArrayList<>();

    private static void addGreylistPattern(String pattern) {
        sGreylist.add(Pattern.compile(" *" + pattern + " *"));
    }

    static {
        final String maybeAs = "( (as )?[_a-z0-9]+)?";
        addGreylistPattern("(?i)[_a-z0-9]+" + maybeAs);
        addGreylistPattern("audio\\._id AS _id");
        addGreylistPattern("(?i)(min|max|sum|avg|total|count|cast)\\(([_a-z0-9]+" + maybeAs + "|\\*)\\)" + maybeAs);
        addGreylistPattern("case when case when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added \\* \\d+ when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added / \\d+ else \\d+ end > case when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified \\* \\d+ when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified / \\d+ else \\d+ end then case when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added \\* \\d+ when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added / \\d+ else \\d+ end else case when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified \\* \\d+ when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified / \\d+ else \\d+ end end as corrected_added_modified");
        addGreylistPattern("MAX\\(case when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken \\* \\d+ when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken / \\d+ else \\d+ end\\)");
        addGreylistPattern("MAX\\(case when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added \\* \\d+ when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added / \\d+ else \\d+ end\\)");
        addGreylistPattern("MAX\\(case when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified \\* \\d+ when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified / \\d+ else \\d+ end\\)");
        addGreylistPattern("\"content://media/[a-z]+/audio/media\"");
        addGreylistPattern("substr\\(_data, length\\(_data\\)-length\\(_display_name\\), 1\\) as filename_prevchar");
        addGreylistPattern("\\*" + maybeAs);
        addGreylistPattern("case when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken \\* \\d+ when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken / \\d+ else \\d+ end");
    }

    @GuardedBy("sProjectionMapCache")
    private static final ArrayMap<Class<?>, ArrayMap<String, String>>
            sProjectionMapCache = new ArrayMap<>();

    /**
     * Return a projection map that represents the valid columns that can be
     * queried the given contract class. The mapping is built automatically
     * using the {@link Column} annotation, and is designed to ensure that we
     * always support public API commitments.
     */
    public static ArrayMap<String, String> getProjectionMap(Class<?> clazz) {
        synchronized (sProjectionMapCache) {
            ArrayMap<String, String> map = sProjectionMapCache.get(clazz);
            if (map == null) {
                map = new ArrayMap<>();
                sProjectionMapCache.put(clazz, map);
                try {
                    for (Field field : clazz.getFields()) {
                        if (field.isAnnotationPresent(Column.class)) {
                            final String column = (String) field.get(null);
                            map.put(column, column);
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            return map;
        }
    }

    /**
     * Simple attempt to balance the given SQL expression by adding parenthesis
     * when needed.
     * <p>
     * Since this is only used for recovering from abusive apps, we're not
     * interested in trying to build a fully valid SQL parser up in Java. It'll
     * give up when it encounters complex SQL, such as string literals.
     */
    @VisibleForTesting
    static @Nullable String maybeBalance(@Nullable String sql) {
        if (sql == null) return null;

        int count = 0;
        char literal = '\0';
        for (int i = 0; i < sql.length(); i++) {
            final char c = sql.charAt(i);

            if (c == '\'' || c == '"') {
                if (literal == '\0') {
                    // Start literal
                    literal = c;
                } else if (literal == c) {
                    // End literal
                    literal = '\0';
                }
            }

            if (literal == '\0') {
                if (c == '(') {
                    count++;
                } else if (c == ')') {
                    count--;
                }
            }
        }
        while (count > 0) {
            sql = sql + ")";
            count--;
        }
        while (count < 0) {
            sql = "(" + sql;
            count++;
        }
        return sql;
    }

    static <T> boolean containsAny(Set<T> a, Set<T> b) {
        for (T i : b) {
            if (a.contains(i)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    String createSqlSortClause(Bundle queryArgs) {
        String[] columns = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS);
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("Can't create sort clause without columns.");
        }

        String sortOrder = TextUtils.join(", ", columns);

        if (queryArgs.containsKey(ContentResolver.QUERY_ARG_SORT_LOCALE)) {
            final String collatorName = ensureCustomCollator(
                    queryArgs.getString(ContentResolver.QUERY_ARG_SORT_LOCALE));
            sortOrder += " COLLATE " + collatorName;
        } else {
            // Interpret PRIMARY and SECONDARY collation strength as no-case collation based
            // on their javadoc descriptions.
            int collation = queryArgs.getInt(
                    ContentResolver.QUERY_ARG_SORT_COLLATION, java.text.Collator.IDENTICAL);
            if (collation == java.text.Collator.PRIMARY
                    || collation == java.text.Collator.SECONDARY) {
                sortOrder += " COLLATE NOCASE";
            }
        }

        int sortDir = queryArgs.getInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, Integer.MIN_VALUE);
        if (sortDir != Integer.MIN_VALUE) {
            switch (sortDir) {
                case ContentResolver.QUERY_SORT_DIRECTION_ASCENDING:
                    sortOrder += " ASC";
                    break;
                case ContentResolver.QUERY_SORT_DIRECTION_DESCENDING:
                    sortOrder += " DESC";
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported sort direction value."
                            + " See ContentResolver documentation for details.");
            }
        }
        return sortOrder;
    }

    /**
     * Gracefully recover from abusive callers that are smashing invalid
     * {@code GROUP BY} clauses into {@code WHERE} clauses.
     */
    @VisibleForTesting
    static Pair<String, String> recoverAbusiveGroupBy(Pair<String, String> selectionAndGroupBy) {
        final String origSelection = selectionAndGroupBy.first;
        final String origGroupBy = selectionAndGroupBy.second;

        final int index = (origSelection != null)
                ? origSelection.toUpperCase().indexOf(" GROUP BY ") : -1;
        if (index != -1) {
            String selection = origSelection.substring(0, index);
            String groupBy = origSelection.substring(index + " GROUP BY ".length());

            // Try balancing things out
            selection = maybeBalance(selection);
            groupBy = maybeBalance(groupBy);

            // Yell if we already had a group by requested
            if (!TextUtils.isEmpty(origGroupBy)) {
                throw new IllegalArgumentException(
                        "Abusive '" + groupBy + "' conflicts with requested '" + origGroupBy + "'");
            }

            Log.w(TAG, "Recovered abusive '" + selection + "' and '" + groupBy + "' from '"
                    + origSelection + "'");
            return Pair.create(selection, groupBy);
        } else {
            return selectionAndGroupBy;
        }
    }

    @VisibleForTesting
    static @Nullable Uri computeCommonPrefix(@NonNull List<Uri> uris) {
        if (uris.isEmpty()) return null;

        final Uri base = uris.get(0);
        final List<String> basePath = new ArrayList<>(base.getPathSegments());
        for (int i = 1; i < uris.size(); i++) {
            final List<String> probePath = uris.get(i).getPathSegments();
            for (int j = 0; j < basePath.size() && j < probePath.size(); j++) {
                if (!Objects.equals(basePath.get(j), probePath.get(j))) {
                    // Trim away all remaining common elements
                    while (basePath.size() > j) {
                        basePath.remove(j);
                    }
                }
            }

            final int probeSize = probePath.size();
            while (basePath.size() > probeSize) {
                basePath.remove(probeSize);
            }
        }

        final Uri.Builder builder = base.buildUpon().path(null);
        for (int i = 0; i < basePath.size(); i++) {
            builder.appendPath(basePath.get(i));
        }
        return builder.build();
    }

    @Deprecated
    private String getCallingPackageOrSelf() {
        return mCallingIdentity.get().getPackageName();
    }

    @Deprecated
    private int getCallingPackageTargetSdkVersion() {
        return mCallingIdentity.get().getTargetSdkVersion();
    }

    @Deprecated
    private boolean isCallingPackageAllowedHidden() {
        return isCallingPackageSystem();
    }

    @Deprecated
    private boolean isCallingPackageSystem() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_SYSTEM);
    }

    @Deprecated
    private boolean isCallingPackageLegacy() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("mThumbSize=" + mThumbSize);
        writer.println("mAttachedVolumeNames=" + mAttachedVolumeNames);
        writer.println(dump(mInternalDatabase, true));
        writer.println(dump(mExternalDatabase, true));
    }

    private String dump(DatabaseHelper dbh, boolean dumpDbLog) {
        StringBuilder s = new StringBuilder();
        s.append(dbh.mName);
        s.append(": ");
        SQLiteDatabase db = dbh.getReadableDatabase();
        if (db == null) {
            s.append("null");
        } else {
            s.append("version " + db.getVersion() + ", ");
            Cursor c = db.query("files", new String[] {"count(*)"}, null, null, null, null, null);
            try {
                if (c != null && c.moveToFirst()) {
                    int num = c.getInt(0);
                    s.append(num + " rows, ");
                } else {
                    s.append("couldn't get row count, ");
                }
            } finally {
                FileUtils.closeQuietly(c);
            }
            if (dbh.mScanStartTime != 0) {
                s.append("scan started " + DateUtils.formatDateTime(getContext(),
                        dbh.mScanStartTime,
                        DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_ABBREV_ALL));
                long now = dbh.mScanStopTime;
                if (now < dbh.mScanStartTime) {
                    now = SystemClock.elapsedRealtime();
                }
                s.append(" (" + DateUtils.formatElapsedTime(
                        (now - dbh.mScanStartTime) / 1_000) + ")");
                if (dbh.mScanStopTime < dbh.mScanStartTime) {
                    if (mMediaScannerVolume != null &&
                            dbh.mName.startsWith(mMediaScannerVolume)) {
                        s.append(" (ongoing)");
                    } else {
                        s.append(" (scanning " + mMediaScannerVolume + ")");
                    }
                }
            }
            if (dumpDbLog) {
                c = db.query("log", new String[] {"time", "message"},
                        null, null, null, null, "rowid");
                try {
                    if (c != null) {
                        while (c.moveToNext()) {
                            String when = c.getString(0);
                            String msg = c.getString(1);
                            s.append("\n" + when + " : " + msg);
                        }
                    }
                } finally {
                    FileUtils.closeQuietly(c);
                }
            } else {
                s.append(": pid=" + android.os.Process.myPid());
                s.append(", fingerprint=" + Build.FINGERPRINT);
            }
        }
        return s.toString();
    }
}
