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

import static android.Manifest.permission.ACCESS_MEDIA_LOCATION;
import static android.app.AppOpsManager.permissionToOp;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.ContentResolver.QUERY_ARG_SQL_SELECTION;
import static android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.MediaStore.MATCH_DEFAULT;
import static android.provider.MediaStore.MATCH_EXCLUDE;
import static android.provider.MediaStore.MATCH_INCLUDE;
import static android.provider.MediaStore.MATCH_ONLY;
import static android.provider.MediaStore.QUERY_ARG_MATCH_FAVORITE;
import static android.provider.MediaStore.QUERY_ARG_MATCH_PENDING;
import static android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED;
import static android.provider.MediaStore.QUERY_ARG_RELATED_URI;
import static android.provider.MediaStore.getVolumeName;

import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static com.android.providers.media.DatabaseHelper.EXTERNAL_DATABASE_NAME;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DATABASE_NAME;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_DELEGATOR;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY_GRANTED;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY_READ;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY_WRITE;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_MANAGER;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_SELF;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_SHELL;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_SYSTEM_GALLERY;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_AUDIO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_IMAGES;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_VIDEO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_AUDIO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_IMAGES;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_VIDEO;
import static com.android.providers.media.scan.MediaScanner.REASON_DEMAND;
import static com.android.providers.media.scan.MediaScanner.REASON_IDLE;
import static com.android.providers.media.util.DatabaseUtils.bindList;
import static com.android.providers.media.util.FileUtils.DEFAULT_FOLDER_NAMES;
import static com.android.providers.media.util.FileUtils.PATTERN_PENDING_FILEPATH_FOR_SQL;
import static com.android.providers.media.util.FileUtils.extractDisplayName;
import static com.android.providers.media.util.FileUtils.extractFileName;
import static com.android.providers.media.util.FileUtils.extractPathOwnerPackageName;
import static com.android.providers.media.util.FileUtils.extractRelativePath;
import static com.android.providers.media.util.FileUtils.extractRelativePathForDirectory;
import static com.android.providers.media.util.FileUtils.extractTopLevelDir;
import static com.android.providers.media.util.FileUtils.extractVolumeName;
import static com.android.providers.media.util.FileUtils.extractVolumePath;
import static com.android.providers.media.util.FileUtils.getAbsoluteSanitizedPath;
import static com.android.providers.media.util.FileUtils.isDataOrObbPath;
import static com.android.providers.media.util.FileUtils.isDownload;
import static com.android.providers.media.util.FileUtils.sanitizePath;
import static com.android.providers.media.util.Logging.LOGV;
import static com.android.providers.media.util.Logging.TAG;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpActiveChangedListener;
import android.app.AppOpsManager.OnOpChangedListener;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.app.RemoteAction;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.icu.util.ULocale;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Binder;
import android.os.Binder.ProxyTransactListener;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageManager.StorageVolumeCallback;
import android.os.storage.StorageVolume;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.Column;
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
import android.util.Size;
import android.util.SparseArray;
import android.webkit.MimeTypeMap;

import androidx.annotation.GuardedBy;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.DatabaseHelper.OnFilesChangeListener;
import com.android.providers.media.DatabaseHelper.OnLegacyMigrationListener;
import com.android.providers.media.fuse.ExternalStorageServiceImpl;
import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.playlist.Playlist;
import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.BackgroundThread;
import com.android.providers.media.util.CachedSupplier;
import com.android.providers.media.util.DatabaseUtils;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.IsoInterface;
import com.android.providers.media.util.Logging;
import com.android.providers.media.util.LongArray;
import com.android.providers.media.util.Metrics;
import com.android.providers.media.util.MimeUtils;
import com.android.providers.media.util.PermissionUtils;
import com.android.providers.media.util.RedactingFileDescriptor;
import com.android.providers.media.util.SQLiteQueryBuilder;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
     * Regex of a selection string that matches a specific ID.
     */
    static final Pattern PATTERN_SELECTION_ID = Pattern.compile(
            "(?:image_id|video_id)\\s*=\\s*(\\d+)");

    /**
     * Property that indicates whether fuse is enabled.
     */
    private static final String PROP_FUSE = "persist.sys.fuse";

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
    private static final String DIRECTORY_ANDROID = "Android";

    private static final String DIRECTORY_MEDIA = "media";
    private static final String DIRECTORY_THUMBNAILS = ".thumbnails";

    /**
     * Hard-coded filename where the current value of
     * {@link DatabaseHelper#getOrCreateUuid} is persisted on a physical SD card
     * to help identify stale thumbnail collections.
     */
    private static final String FILE_DATABASE_UUID = ".database_uuid";

    /**
     * Specify what default directories the caller gets full access to. By default, the caller
     * shouldn't get full access to any default dirs.
     * But for example, we do an exception for System Gallery apps and allow them full access to:
     * DCIM, Pictures, Movies.
     */
    private static final String INCLUDED_DEFAULT_DIRECTORIES =
            "android:included-default-directories";

    /**
     * Value indicating that operations should include database rows matching the criteria defined
     * by this key only when calling package has write permission to the database row or column is
     * {@column MediaColumns#IS_PENDING} and is set by FUSE.
     * <p>
     * Note that items <em>not</em> matching the criteria will also be included, and as part of this
     * match no additional write permission checks are carried out for those items.
     */
    private static final int MATCH_VISIBLE_FOR_FILEPATH = 32;

    private static final int NON_HIDDEN_CACHE_SIZE = 50;

    /**
     * Where clause to match pending files from FUSE. Pending files from FUSE will not have
     * PATTERN_PENDING_FILEPATH_FOR_SQL pattern.
     */
    private static final String MATCH_PENDING_FROM_FUSE = String.format("lower(%s) NOT REGEXP '%s'",
            MediaColumns.DATA, PATTERN_PENDING_FILEPATH_FOR_SQL);

    // Stolen from: UserHandle#getUserId
    private static final int PER_USER_RANGE = 100000;
    private static final boolean PROP_CROSS_USER_ALLOWED =
            SystemProperties.getBoolean("external_storage.cross_user.enabled", false);

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
    private static final Set<String> sCachedExternalVolumeNames = new ArraySet<>();
    @GuardedBy("sCacheLock")
    private static final Map<String, File> sCachedVolumePaths = new ArrayMap<>();
    @GuardedBy("sCacheLock")
    private static final Map<String, Collection<File>> sCachedVolumeScanPaths = new ArrayMap<>();
    @GuardedBy("sCacheLock")
    private static final ArrayMap<File, String> sCachedVolumePathToId = new ArrayMap<>();

    private static final int sUserId = UserHandle.myUserId();

    // WARNING/TODO: This will be replaced by signature APIs in S
    private static final String DOWNLOADS_PROVIDER_AUTHORITY = "downloads";

    @GuardedBy("mShouldRedactThreadIds")
    private final LongArray mShouldRedactThreadIds = new LongArray();

    @GuardedBy("mNonHiddenPaths")
    private final LRUCache<String, Integer> mNonHiddenPaths = new LRUCache<>(NON_HIDDEN_CACHE_SIZE);

    public void updateVolumes() {
        synchronized (sCacheLock) {
            sCachedExternalVolumeNames.clear();
            sCachedExternalVolumeNames.addAll(MediaStore.getExternalVolumeNames(getContext()));
            Log.v(TAG, "Updated external volumes to: " + sCachedExternalVolumeNames.toString());

            sCachedVolumePaths.clear();
            sCachedVolumeScanPaths.clear();
            sCachedVolumePathToId.clear();
            try {
                sCachedVolumeScanPaths.put(MediaStore.VOLUME_INTERNAL,
                        FileUtils.getVolumeScanPaths(getContext(), MediaStore.VOLUME_INTERNAL));
            } catch (FileNotFoundException e) {
                Log.wtf(TAG, "Failed to update volume " + MediaStore.VOLUME_INTERNAL, e);
            }

            for (String volumeName : sCachedExternalVolumeNames) {
                try {
                    final Uri uri = MediaStore.Files.getContentUri(volumeName);
                    final StorageVolume volume = mStorageManager.getStorageVolume(uri);
                    sCachedVolumePaths.put(volumeName, volume.getDirectory());
                    sCachedVolumeScanPaths.put(volumeName,
                            FileUtils.getVolumeScanPaths(getContext(), volumeName));
                    sCachedVolumePathToId.put(volume.getDirectory(), volume.getId());
                } catch (IllegalStateException | FileNotFoundException e) {
                    Log.wtf(TAG, "Failed to update volume " + volumeName, e);
                }
            }
        }

        // Update filters to reflect mounted volumes so users don't get
        // confused by metadata from ejected volumes
        ForegroundThread.getExecutor().execute(() -> {
            mExternalDatabase.setFilterVolumeNames(getExternalVolumeNames());
        });
    }

    public @NonNull File getVolumePath(@NonNull String volumeName) throws FileNotFoundException {
        // Ugly hack to keep unit tests passing, where we don't always have a
        // Context to discover volumes with
        if (getContext() == null) {
            return Environment.getExternalStorageDirectory();
        }

        synchronized (sCacheLock) {
            if (sCachedVolumePaths.containsKey(volumeName)) {
                return sCachedVolumePaths.get(volumeName);
            }

            // Nothing found above; let's ask directly and cache the answer
            final File res = FileUtils.getVolumePath(getContext(), volumeName);
            sCachedVolumePaths.put(volumeName, res);
            return res;
        }
    }

    public @NonNull String getVolumeId(@NonNull File file) throws FileNotFoundException {
        synchronized (sCacheLock) {
            for (int i = 0; i < sCachedVolumePathToId.size(); i++) {
                if (FileUtils.contains(sCachedVolumePathToId.keyAt(i), file)) {
                    return sCachedVolumePathToId.valueAt(i);
                }
            }

            // Nothing found above; let's ask directly and cache the answer
            final StorageVolume volume = mStorageManager.getStorageVolume(file);
            if (volume == null) {
                throw new FileNotFoundException("Missing volume for " + file);
            }
            sCachedVolumePathToId.put(volume.getDirectory(), volume.getId());
            return volume.getId();
        }
    }

    public @NonNull Set<String> getExternalVolumeNames() {
        synchronized (sCacheLock) {
            return new ArraySet<>(sCachedExternalVolumeNames);
        }
    }

    public @NonNull Collection<File> getVolumeScanPaths(String volumeName)
            throws FileNotFoundException {
        synchronized (sCacheLock) {
            if (sCachedVolumeScanPaths.containsKey(volumeName)) {
                return new ArrayList<>(sCachedVolumeScanPaths.get(volumeName));
            }

            // Nothing found above; let's ask directly and cache the answer
            final Collection<File> res = FileUtils.getVolumeScanPaths(getContext(), volumeName);
            sCachedVolumeScanPaths.put(volumeName, res);
            return res;
        }
    }

    private StorageManager mStorageManager;
    private AppOpsManager mAppOpsManager;
    private PackageManager mPackageManager;
    private DevicePolicyManager mDevicePolicyManager;

    private int mExternalStorageAuthorityAppId;
    private int mDownloadsAuthorityAppId;

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
     * Map from UID to cached {@link LocalCallingIdentity}. Values are only
     * maintained in this map until there's any change in the appops needed or packages
     * used in the {@link LocalCallingIdentity}.
     */
    @GuardedBy("mCachedCallingIdentityForFuse")
    private final SparseArray<LocalCallingIdentity> mCachedCallingIdentityForFuse =
            new SparseArray<>();

    private OnOpChangedListener mModeListener =
            (op, packageName) -> invalidateLocalCallingIdentityCache(packageName, "op " + op);

    @GuardedBy("mNonWorkProfileUsers")
    private final List<Integer> mNonWorkProfileUsers = new ArrayList<>();

    /**
     * Retrieves a cached calling identity or creates a new one. Also, always sets the app-op
     * description for the calling identity.
     */
    private LocalCallingIdentity getCachedCallingIdentityForFuse(int uid) {
        synchronized (mCachedCallingIdentityForFuse) {
            PermissionUtils.setOpDescription("via FUSE");
            LocalCallingIdentity ident = mCachedCallingIdentityForFuse.get(uid);
            if (ident == null) {
               ident = LocalCallingIdentity.fromExternal(getContext(), uid);
               if (uid / PER_USER_RANGE == sUserId) {
                   mCachedCallingIdentityForFuse.put(uid, ident);
               } else {
                   // In some app cloning designs, MediaProvider user 0 may
                   // serve requests for apps running as a "clone" user; in
                   // those cases, don't keep a cache for the clone user, since
                   // we don't get any invalidation events for these users.
               }
            }
            return ident;
        }
    }

    /**
     * Calling identity state about on the current thread. Populated on demand,
     * and invalidated by {@link #onCallingPackageChanged()} when each remote
     * call is finished.
     */
    private final ThreadLocal<LocalCallingIdentity> mCallingIdentity = ThreadLocal
            .withInitial(() -> {
                PermissionUtils.setOpDescription("via MediaProvider");
                synchronized (mCachedCallingIdentity) {
                    final LocalCallingIdentity cached = mCachedCallingIdentity
                            .get(Binder.getCallingUid());
                    return (cached != null) ? cached
                            : LocalCallingIdentity.fromBinder(getContext(), this);
                }
            });

    /**
     * We simply propagate the UID that is being tracked by
     * {@link LocalCallingIdentity}, which means we accurately blame both
     * incoming Binder calls and FUSE calls.
     */
    private final ProxyTransactListener mTransactListener = new ProxyTransactListener() {
        @Override
        public Object onTransactStarted(IBinder binder, int transactionCode) {
            if (LOGV) Trace.beginSection(Thread.currentThread().getStackTrace()[5].getMethodName());
            return Binder.setCallingWorkSourceUid(mCallingIdentity.get().uid);
        }

        @Override
        public void onTransactEnded(Object session) {
            final long token = (long) session;
            Binder.restoreCallingWorkSource(token);
            if (LOGV) Trace.endSection();
        }
    };

    // In memory cache of path<->id mappings, to speed up inserts during media scan
    @GuardedBy("mDirectoryCache")
    private final ArrayMap<String, Long> mDirectoryCache = new ArrayMap<>();

    private static final String[] sDataOnlyColumn = new String[] {
        FileColumns.DATA
    };

    private static final String ID_NOT_PARENT_CLAUSE =
            "_id NOT IN (SELECT parent FROM files WHERE parent IS NOT NULL)";

    private static final String CANONICAL = "canonical";

    private static final String ALL_VOLUMES = "all_volumes";

    private BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_PACKAGE_REMOVED:
                case Intent.ACTION_PACKAGE_ADDED:
                    Uri uri = intent.getData();
                    String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
                    if (pkg != null) {
                        invalidateLocalCallingIdentityCache(pkg, "package " + intent.getAction());
                    } else {
                        Log.w(TAG, "Failed to retrieve package from intent: " + intent.getAction());
                    }
                    break;
            }
        }
    };

    private void invalidateLocalCallingIdentityCache(String packageName, String reason) {
        synchronized (mCachedCallingIdentityForFuse) {
            try {
                Log.i(TAG, "Invalidating LocalCallingIdentity cache for package " + packageName
                        + ". Reason: " + reason);
                mCachedCallingIdentityForFuse.remove(
                        getContext().getPackageManager().getPackageUid(packageName, 0));
            } catch (NameNotFoundException ignored) {
            }
        }
    }

    private final void updateQuotaTypeForUri(@NonNull Uri uri, int mediaType) {
        Trace.beginSection("updateQuotaTypeForUri");
        File file;
        try {
            file = queryForDataFile(uri, null);
            if (!file.exists()) {
                // This can happen if an item is inserted in MediaStore before it is created
                return;
            }

            if (mediaType == FileColumns.MEDIA_TYPE_NONE) {
                // This might be because the file is hidden; but we still want to
                // attribute its quota to the correct type, so get the type from
                // the extension instead.
                mediaType = MimeUtils.resolveMediaType(MimeUtils.resolveMimeType(file));
            }

            updateQuotaTypeForFileInternal(file, mediaType);
        } catch (FileNotFoundException | IllegalArgumentException e) {
            // Ignore
            Log.w(TAG, "Failed to update quota for uri: " + uri, e);
            return;
        } finally {
            Trace.endSection();
        }
    }

    private final void updateQuotaTypeForFileInternal(File file, int mediaType) {
        try {
            switch (mediaType) {
                case FileColumns.MEDIA_TYPE_AUDIO:
                    mStorageManager.updateExternalStorageFileQuotaType(file,
                            StorageManager.QUOTA_TYPE_MEDIA_AUDIO);
                    break;
                case FileColumns.MEDIA_TYPE_VIDEO:
                    mStorageManager.updateExternalStorageFileQuotaType(file,
                            StorageManager.QUOTA_TYPE_MEDIA_VIDEO);
                    break;
                case FileColumns.MEDIA_TYPE_IMAGE:
                    mStorageManager.updateExternalStorageFileQuotaType(file,
                            StorageManager.QUOTA_TYPE_MEDIA_IMAGE);
                    break;
                default:
                    mStorageManager.updateExternalStorageFileQuotaType(file,
                            StorageManager.QUOTA_TYPE_MEDIA_NONE);
                    break;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to update quota type for " + file.getPath(), e);
        }
    }

    /**
     * Since these operations are in the critical path of apps working with
     * media, we only collect the {@link Uri} that need to be notified, and all
     * other side-effect operations are delegated to {@link BackgroundThread} so
     * that we return as quickly as possible.
     */
    private final OnFilesChangeListener mFilesListener = new OnFilesChangeListener() {
        @Override
        public void onInsert(@NonNull DatabaseHelper helper, @NonNull String volumeName, long id,
                int mediaType, boolean isDownload) {
            handleInsertedRowForFuse(id);
            acceptWithExpansion(helper::notifyInsert, volumeName, id, mediaType, isDownload);

            helper.postBackground(() -> {
                if (helper.isExternal()) {
                    // Update the quota type on the filesystem
                    Uri fileUri = MediaStore.Files.getContentUri(volumeName, id);
                    updateQuotaTypeForUri(fileUri, mediaType);
                }

                // Tell our SAF provider so it knows when views are no longer empty
                MediaDocumentsProvider.onMediaStoreInsert(getContext(), volumeName, mediaType, id);
            });
        }

        @Override
        public void onUpdate(@NonNull DatabaseHelper helper, @NonNull String volumeName,
                long oldId, int oldMediaType, boolean oldIsDownload,
                long newId, int newMediaType, boolean newIsDownload,
                String oldOwnerPackage, String newOwnerPackage, String oldPath) {
            final boolean isDownload = oldIsDownload || newIsDownload;
            final Uri fileUri = MediaStore.Files.getContentUri(volumeName, oldId);
            handleUpdatedRowForFuse(oldPath, oldOwnerPackage, oldId, newId);
            handleOwnerPackageNameChange(oldPath, oldOwnerPackage, newOwnerPackage);
            acceptWithExpansion(helper::notifyUpdate, volumeName, oldId, oldMediaType, isDownload);

            helper.postBackground(() -> {
                if (helper.isExternal()) {
                    // Update the quota type on the filesystem
                    updateQuotaTypeForUri(fileUri, newMediaType);
                }
            });

            if (newMediaType != oldMediaType) {
                acceptWithExpansion(helper::notifyUpdate, volumeName, oldId, newMediaType,
                        isDownload);

                helper.postBackground(() -> {
                    // Invalidate any thumbnails when the media type changes
                    invalidateThumbnails(fileUri);
                });
            }
        }

        @Override
        public void onDelete(@NonNull DatabaseHelper helper, @NonNull String volumeName, long id,
                int mediaType, boolean isDownload, String ownerPackageName, String path) {
            handleDeletedRowForFuse(path, ownerPackageName, id);
            acceptWithExpansion(helper::notifyDelete, volumeName, id, mediaType, isDownload);

            helper.postBackground(() -> {
                // Item no longer exists, so revoke all access to it
                Trace.beginSection("revokeUriPermission");
                try {
                    acceptWithExpansion((uri) -> {
                        getContext().revokeUriPermission(uri, ~0);
                    }, volumeName, id, mediaType, isDownload);
                } finally {
                    Trace.endSection();
                }

                switch (mediaType) {
                    case FileColumns.MEDIA_TYPE_PLAYLIST:
                    case FileColumns.MEDIA_TYPE_AUDIO:
                        if (helper.isExternal()) {
                            removePlaylistMembers(mediaType, id);
                        }
                }

                // Invalidate any thumbnails now that media is gone
                invalidateThumbnails(MediaStore.Files.getContentUri(volumeName, id));

                // Tell our SAF provider so it can revoke too
                MediaDocumentsProvider.onMediaStoreDelete(getContext(), volumeName, mediaType, id);
            });
        }
    };

    private final UnaryOperator<String> mIdGenerator = path -> {
        final long rowId = mCallingIdentity.get().getDeletedRowId(path);
        if (rowId != -1 && isFuseThread()) {
            return String.valueOf(rowId);
        }
        return null;
    };

    /** {@hide} */
    public static final OnLegacyMigrationListener MIGRATION_LISTENER =
            new OnLegacyMigrationListener() {
        @Override
        public void onStarted(ContentProviderClient client, String volumeName) {
            MediaStore.startLegacyMigration(ContentResolver.wrap(client), volumeName);
        }

        @Override
        public void onProgress(ContentProviderClient client, String volumeName,
                long progress, long total) {
            // TODO: notify blocked threads of progress once we can change APIs
        }

        @Override
        public void onFinished(ContentProviderClient client, String volumeName) {
            MediaStore.finishLegacyMigration(ContentResolver.wrap(client), volumeName);
        }
    };

    /**
     * Apply {@link Consumer#accept} to the given item.
     * <p>
     * Since media items can be exposed through multiple collections or views,
     * this method expands the single item being accepted to also accept all
     * relevant views.
     */
    private void acceptWithExpansion(@NonNull Consumer<Uri> consumer, @NonNull String volumeName,
            long id, int mediaType, boolean isDownload) {
        switch (mediaType) {
            case FileColumns.MEDIA_TYPE_AUDIO:
                consumer.accept(MediaStore.Audio.Media.getContentUri(volumeName, id));

                // Any changing audio items mean we probably need to invalidate all
                // indexed views built from that media
                consumer.accept(Audio.Genres.getContentUri(volumeName));
                consumer.accept(Audio.Playlists.getContentUri(volumeName));
                consumer.accept(Audio.Artists.getContentUri(volumeName));
                consumer.accept(Audio.Albums.getContentUri(volumeName));
                break;

            case FileColumns.MEDIA_TYPE_VIDEO:
                consumer.accept(MediaStore.Video.Media.getContentUri(volumeName, id));
                break;

            case FileColumns.MEDIA_TYPE_IMAGE:
                consumer.accept(MediaStore.Images.Media.getContentUri(volumeName, id));
                break;

            case FileColumns.MEDIA_TYPE_PLAYLIST:
                consumer.accept(Audio.Playlists.Members.getContentUri(volumeName, id));
                break;
        }

        // Also notify through any generic views
        consumer.accept(MediaStore.Files.getContentUri(volumeName, id));
        if (isDownload) {
            consumer.accept(MediaStore.Downloads.getContentUri(volumeName, id));
        }

        // Rinse and repeat through any synthetic views
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
            case MediaStore.VOLUME_EXTERNAL:
                // Already a top-level view, no need to expand
                break;
            default:
                acceptWithExpansion(consumer, MediaStore.VOLUME_EXTERNAL,
                        id, mediaType, isDownload);
                break;
        }
    }

    private static boolean isCrossUserEnabled() {
        return PROP_CROSS_USER_ALLOWED && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R;
    }

    /**
     * Ensure that default folders are created on mounted primary storage
     * devices. We only do this once per volume so we don't annoy the user if
     * deleted manually.
     */
    private void ensureDefaultFolders(@NonNull String volumeName, @NonNull SQLiteDatabase db) {
        try {
            final File path = getVolumePath(volumeName);
            final StorageVolume vol = mStorageManager.getStorageVolume(path);
            final String key;
            if (vol == null) {
                Log.w(TAG, "Failed to ensure default folders for " + volumeName);
                return;
            }

            if (vol.isPrimary()) {
                key = "created_default_folders";
            } else {
                key = "created_default_folders_" + vol.getMediaStoreVolumeName();
            }

            final SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getContext());
            if (prefs.getInt(key, 0) == 0) {
                for (String folderName : DEFAULT_FOLDER_NAMES) {
                    final File folder = new File(vol.getDirectory(), folderName);
                    if (!folder.exists()) {
                        folder.mkdirs();
                        insertDirectory(db, folder.getAbsolutePath());
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

    /**
     * Ensure that any thumbnail collections on the given storage volume can be
     * used with the given {@link DatabaseHelper}. If the
     * {@link DatabaseHelper#getOrCreateUuid} doesn't match the UUID found on
     * disk, then all thumbnails will be considered stable and will be deleted.
     */
    private void ensureThumbnailsValid(@NonNull String volumeName, @NonNull SQLiteDatabase db) {
        final String uuidFromDatabase = DatabaseHelper.getOrCreateUuid(db);
        try {
            for (File dir : getThumbnailDirectories(volumeName)) {
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                final File file = new File(dir, FILE_DATABASE_UUID);
                final Optional<String> uuidFromDisk = FileUtils.readString(file);

                final boolean updateUuid;
                if (!uuidFromDisk.isPresent()) {
                    // For newly inserted volumes or upgrading of existing volumes,
                    // assume that our current UUID is valid
                    updateUuid = true;
                } else if (!Objects.equals(uuidFromDatabase, uuidFromDisk.get())) {
                    // The UUID of database disagrees with the one on disk,
                    // which means we can't trust any thumbnails
                    Log.d(TAG, "Invalidating all thumbnails under " + dir);
                    FileUtils.walkFileTreeContents(dir.toPath(), this::deleteAndInvalidate);
                    updateUuid = true;
                } else {
                    updateUuid = false;
                }

                if (updateUuid) {
                    FileUtils.writeString(file, Optional.of(uuidFromDatabase));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to ensure thumbnails valid for " + volumeName, e);
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        Log.v(TAG, "Attached " + info.authority + " from " + info.applicationInfo.packageName);

        mUriMatcher = new LocalUriMatcher(info.authority);

        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        // Shift call statistics back to the original caller
        Binder.setProxyTransactListener(mTransactListener);

        mStorageManager = context.getSystemService(StorageManager.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);

        // Reasonable thumbnail size is half of the smallest screen edge width
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int thumbSize = Math.min(metrics.widthPixels, metrics.heightPixels) / 2;
        mThumbSize = new Size(thumbSize, thumbSize);

        mMediaScanner = new ModernMediaScanner(context);

        mInternalDatabase = new DatabaseHelper(context, INTERNAL_DATABASE_NAME,
                true, false, false, Column.class,
                Metrics::logSchemaChange, mFilesListener, MIGRATION_LISTENER, mIdGenerator);
        mExternalDatabase = new DatabaseHelper(context, EXTERNAL_DATABASE_NAME,
                false, false, false, Column.class,
                Metrics::logSchemaChange, mFilesListener, MIGRATION_LISTENER, mIdGenerator);

        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.setPriority(10);
        packageFilter.addDataScheme("package");
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        context.registerReceiver(mPackageReceiver, packageFilter);

        // Watch for invalidation of cached volumes
        mStorageManager.registerStorageVolumeCallback(context.getMainExecutor(),
                new StorageVolumeCallback() {
                    @Override
                    public void onStateChanged(@NonNull StorageVolume volume) {
                        updateVolumes();
                   }
                });

        updateVolumes();
        attachVolume(MediaStore.VOLUME_INTERNAL, /* validate */ false);
        for (String volumeName : getExternalVolumeNames()) {
            attachVolume(volumeName, /* validate */ false);
        }

        // Watch for performance-sensitive activity
        mAppOpsManager.startWatchingActive(new String[] {
                AppOpsManager.OPSTR_CAMERA
        }, context.getMainExecutor(), mActiveListener);

        mAppOpsManager.startWatchingMode(AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE,
                null /* all packages */, mModeListener);
        mAppOpsManager.startWatchingMode(AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE,
                null /* all packages */, mModeListener);
        mAppOpsManager.startWatchingMode(permissionToOp(ACCESS_MEDIA_LOCATION),
                null /* all packages */, mModeListener);
        // Legacy apps
        mAppOpsManager.startWatchingMode(AppOpsManager.OPSTR_LEGACY_STORAGE,
                null /* all packages */, mModeListener);
        // File managers
        mAppOpsManager.startWatchingMode(AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE,
                null /* all packages */, mModeListener);
        // Default gallery changes
        mAppOpsManager.startWatchingMode(AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES,
                null /* all packages */, mModeListener);
        mAppOpsManager.startWatchingMode(AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO,
                null /* all packages */, mModeListener);
        try {
            // Here we are forced to depend on the non-public API of AppOpsManager. If
            // OPSTR_NO_ISOLATED_STORAGE app op is not defined in AppOpsManager, then this call will
            // throw an IllegalArgumentException during MediaProvider startup. In combination with
            // MediaProvider's CTS tests it should give us guarantees that OPSTR_NO_ISOLATED_STORAGE
            // is defined.
            mAppOpsManager.startWatchingMode(PermissionUtils.OPSTR_NO_ISOLATED_STORAGE,
                    null /* all packages */, mModeListener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to start watching " + PermissionUtils.OPSTR_NO_ISOLATED_STORAGE, e);
        }

        ProviderInfo provider = mPackageManager.resolveContentProvider(
            DOWNLOADS_PROVIDER_AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if (provider != null) {
            mDownloadsAuthorityAppId = UserHandle.getAppId(provider.applicationInfo.uid);
        }

        provider = mPackageManager.resolveContentProvider(
            MediaStore.EXTERNAL_STORAGE_PROVIDER_AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if (provider != null) {
            mExternalStorageAuthorityAppId = UserHandle.getAppId(provider.applicationInfo.uid);
        }
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

    private boolean isPackageKnown(@NonNull String packageName) {
        final PackageManager pm = getContext().getPackageManager();

        // First, is the app actually installed?
        try {
            pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (NameNotFoundException ignored) {
        }

        // Second, is the app pending, probably from a backup/restore operation?
        for (SessionInfo si : pm.getPackageInstaller().getAllSessions()) {
            if (Objects.equals(packageName, si.getAppPackageName())) {
                return true;
            }
        }

        // I've never met this package in my life
        return false;
    }

    public void onIdleMaintenance(@NonNull CancellationSignal signal) {
        final long startTime = SystemClock.elapsedRealtime();

        // Trim any stale log files before we emit new events below
        Logging.trimPersistent();

        // Scan all volumes to resolve any staleness
        for (String volumeName : getExternalVolumeNames()) {
            // Possibly bail before digging into each volume
            signal.throwIfCanceled();

            try {
                MediaService.onScanVolume(getContext(), volumeName, REASON_IDLE);
            } catch (IOException e) {
                Log.w(TAG, e);
            }

            // Ensure that our thumbnails are valid
            mExternalDatabase.runWithTransaction((db) -> {
                ensureThumbnailsValid(volumeName, db);
                return null;
            });
        }

        // Delete any stale thumbnails
        final int staleThumbnails = mExternalDatabase.runWithTransaction((db) -> {
            return pruneThumbnails(db, signal);
        });
        Log.d(TAG, "Pruned " + staleThumbnails + " unknown thumbnails");

        // Finished orphaning any content whose package no longer exists
        final int stalePackages = mExternalDatabase.runWithTransaction((db) -> {
            final ArraySet<String> unknownPackages = new ArraySet<>();
            try (Cursor c = db.query(true, "files", new String[] { "owner_package_name" },
                    null, null, null, null, null, null, signal)) {
                while (c.moveToNext()) {
                    final String packageName = c.getString(0);
                    if (TextUtils.isEmpty(packageName)) continue;

                    if (!isPackageKnown(packageName)) {
                        unknownPackages.add(packageName);
                    }
                }
            }
            for (String packageName : unknownPackages) {
                onPackageOrphaned(db, packageName);
            }
            return unknownPackages.size();
        });
        Log.d(TAG, "Pruned " + stalePackages + " unknown packages");

        // Delete any expired content; we're paranoid about wildly changing
        // clocks, so only delete items within the last week
        final long from = ((System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS) / 1000);
        final long to = (System.currentTimeMillis() / 1000);
        final int expiredMedia = mExternalDatabase.runWithTransaction((db) -> {
            try (Cursor c = db.query(true, "files", new String[] { "volume_name", "_id" },
                    FileColumns.DATE_EXPIRES + " BETWEEN " + from + " AND " + to, null,
                    null, null, null, null, signal)) {
                while (c.moveToNext()) {
                    final String volumeName = c.getString(0);
                    final long id = c.getLong(1);
                    delete(Files.getContentUri(volumeName, id), null, null);
                }
                return c.getCount();
            }
        });
        Log.d(TAG, "Deleted " + expiredMedia + " expired items");

        // Forget any stale volumes
        mExternalDatabase.runWithTransaction((db) -> {
            final Set<String> recentVolumeNames = MediaStore
                    .getRecentExternalVolumeNames(getContext());
            final Set<String> knownVolumeNames = new ArraySet<>();
            try (Cursor c = db.query(true, "files", new String[] { MediaColumns.VOLUME_NAME },
                    null, null, null, null, null, null, signal)) {
                while (c.moveToNext()) {
                    knownVolumeNames.add(c.getString(0));
                }
            }
            final Set<String> staleVolumeNames = new ArraySet<>();
            staleVolumeNames.addAll(knownVolumeNames);
            staleVolumeNames.removeAll(recentVolumeNames);
            for (String staleVolumeName : staleVolumeNames) {
                final int num = db.delete("files", FileColumns.VOLUME_NAME + "=?",
                        new String[] { staleVolumeName });
                Log.d(TAG, "Forgot " + num + " stale items from " + staleVolumeName);
            }
            return null;
        });

        synchronized (mDirectoryCache) {
            mDirectoryCache.clear();
        }

        final long itemCount = mExternalDatabase.runWithTransaction((db) -> {
            return DatabaseHelper.getItemCount(db);
        });

        final long durationMillis = (SystemClock.elapsedRealtime() - startTime);
        Metrics.logIdleMaintenance(MediaStore.VOLUME_EXTERNAL, itemCount,
                durationMillis, staleThumbnails, expiredMedia);
    }

    public void onIdleMaintenanceStopped() {
        mMediaScanner.onIdleScanStopped();
    }

    /**
     * Orphan any content of the given package. This will delete Android/media orphaned files from
     * the database.
     */
    public void onPackageOrphaned(String packageName) {
        mExternalDatabase.runWithTransaction((db) -> {
            onPackageOrphaned(db, packageName);
            return null;
        });
    }

    /**
     * Orphan any content of the given package from the given database. This will delete
     * Android/media orphaned files from the database.
     */
    public void onPackageOrphaned(@NonNull SQLiteDatabase db, @NonNull String packageName) {
        // Delete files from Android/media.
        String relativePath = "Android/media/" + DatabaseUtils.escapeForLike(packageName) + "/%";
        final int countDeleted = db.delete(
                "files",
                "relative_path LIKE ? ESCAPE '\\' AND owner_package_name=?",
                new String[] {relativePath, packageName});
        Log.d(TAG, "Deleted " + countDeleted + " Android/media items belonging to "
                + packageName + " on " + db.getPath());

        // Orphan rest of files.
        final ContentValues values = new ContentValues();
        values.putNull(FileColumns.OWNER_PACKAGE_NAME);

        final int countOrphaned = db.update("files", values,
                "owner_package_name=?", new String[] { packageName });
        if (countOrphaned > 0) {
            Log.d(TAG, "Orphaned " + countOrphaned + " items belonging to "
                    + packageName + " on " + db.getPath());
        }
    }

    public void scanDirectory(File file, int reason) {
        mMediaScanner.scanDirectory(file, reason);
    }

    public Uri scanFile(File file, int reason) {
        return mMediaScanner.scanFile(file, reason);
    }

    public Uri scanFile(File file, int reason, String ownerPackage) {
        return mMediaScanner.scanFile(file, reason, ownerPackage);
    }

    private Uri scanFileAsMediaProvider(File file, int reason) {
        final LocalCallingIdentity tokenInner = clearLocalCallingIdentity();
        try {
            return scanFile(file, REASON_DEMAND);
        } finally {
            restoreLocalCallingIdentity(tokenInner);
        }
    }

    /**
     * Makes MediaScanner scan the given file.
     * @param file path of the file to be scanned
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public void scanFileForFuse(String file) {
        scanFile(new File(file), REASON_DEMAND);
    }

    /**
     * Called when a new file is created through FUSE
     *
     * @param file path of the file that was created
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public void onFileCreatedForFuse(String path) {
        // Make sure we update the quota type of the file
        BackgroundThread.getExecutor().execute(() -> {
            File file = new File(path);
            int mediaType = MimeUtils.resolveMediaType(MimeUtils.resolveMimeType(file));
            updateQuotaTypeForFileInternal(file, mediaType);
        });
    }

    private boolean isAppCloneUserPair(int userId1, int userId2) {
        try {
            Method isAppCloneUserPair = StorageManager.class.getMethod("isAppCloneUserPair",
                    int.class, int.class);
            return (Boolean) isAppCloneUserPair.invoke(mStorageManager, userId1, userId2);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Log.w(TAG, "isAppCloneUserPair failed. Users: " + userId1 + " and " + userId2);
            return false;
        }
    }

    /**
     * Determines whether the passed in userId forms an app clone user pair with user 0.
     *
     * @param userId user ID to check
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public boolean isAppCloneUserForFuse(int userId) {
        if (!isCrossUserEnabled()) {
            Log.d(TAG, "CrossUser not enabled.");
            return false;
        }
        boolean result = isAppCloneUserPair(0, userId);
        Log.w(TAG, "isAppCloneUserPair for user " + userId + ": " + result);

        return result;
    }

    /**
     * Determines if to allow FUSE_LOOKUP for uid. Might allow uids that don't belong to the
     * MediaProvider user, depending on OEM configuration.
     *
     * @param uid linux uid to check
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public boolean shouldAllowLookupForFuse(int uid, int pathUserId) {
        int callingUserId = uid / PER_USER_RANGE;
        if (!isCrossUserEnabled()) {
            Log.d(TAG, "CrossUser not enabled. Users: " + callingUserId + " and " + pathUserId);
            return false;
        }

        if (callingUserId != 0 && pathUserId != 0) {
            Log.w(TAG, "CrossUser at least one user is 0 check failed. Users: " + callingUserId
                    + " and " + pathUserId);
            return false;
        }

        if (isWorkProfile(callingUserId) || isWorkProfile(pathUserId)) {
            // Cross-user lookup not allowed if one user in the pair has a profile owner app
            Log.w(TAG, "CrossUser work profile check failed. Users: " + callingUserId + " and "
                    + pathUserId);
            return false;
        }

        boolean result = isAppCloneUserPair(pathUserId, callingUserId);
        if (result) {
            Log.i(TAG, "CrossUser allowed. Users: " + callingUserId + " and " + pathUserId);
        } else {
            Log.w(TAG, "CrossUser isAppCloneUserPair check failed. Users: " + callingUserId
                    + " and " + pathUserId);
        }

        return result;
    }

    private boolean isWorkProfile(int userId) {
        synchronized (mNonWorkProfileUsers) {
            if (mNonWorkProfileUsers.contains(userId)) {
                return false;
            }
            if (userId == 0) {
                mNonWorkProfileUsers.add(userId);
                // user 0 cannot have a profile owner
                return false;
            }
        }

        List<Integer> uids = new ArrayList<>();
        for (ApplicationInfo ai : mPackageManager.getInstalledApplications(MATCH_DIRECT_BOOT_AWARE
                        | MATCH_DIRECT_BOOT_UNAWARE | MATCH_ANY_USER)) {
            if (((ai.uid / PER_USER_RANGE) == userId)
                    && mDevicePolicyManager.isProfileOwnerApp(ai.packageName)) {
                return true;
            }
        }

        synchronized (mNonWorkProfileUsers) {
            mNonWorkProfileUsers.add(userId);
            return false;
        }
    }

    /**
     * Returns true if the app denoted by the given {@code uid} and {@code packageName} is allowed
     * to clear other apps' cache directories.
     */
    static boolean hasPermissionToClearCaches(Context context, ApplicationInfo ai) {
        PermissionUtils.setOpDescription("clear app cache");
        try {
            return PermissionUtils.checkPermissionManager(context, /* pid */ -1, ai.uid,
                    ai.packageName, /* attributionTag */ null);
        } finally {
            PermissionUtils.clearOpDescription();
        }
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
        computeAudioKeyValue(values, AudioColumns.TITLE, AudioColumns.TITLE_KEY, /* focusId */
                null, /* hashValue */ 0);
        computeAudioKeyValue(values, AudioColumns.ARTIST, AudioColumns.ARTIST_KEY,
                AudioColumns.ARTIST_ID, /* hashValue */ 0);
        computeAudioKeyValue(values, AudioColumns.GENRE, AudioColumns.GENRE_KEY,
                AudioColumns.GENRE_ID, /* hashValue */ 0);
        computeAudioAlbumKeyValue(values);
    }

    /**
     * To distinguish same-named albums, we append a hash. The hash is
     * based on the "album artist" tag if present, otherwise on the path of
     * the parent directory of the audio file.
     */
    private static void computeAudioAlbumKeyValue(ContentValues values) {
        int hashCode = 0;

        final String albumArtist = values.getAsString(MediaColumns.ALBUM_ARTIST);
        if (!TextUtils.isEmpty(albumArtist)) {
            hashCode = albumArtist.hashCode();
        } else {
            final String path = values.getAsString(MediaColumns.DATA);
            if (!TextUtils.isEmpty(path)) {
                hashCode = path.substring(0, path.lastIndexOf('/')).hashCode();
            }
        }

        computeAudioKeyValue(values, AudioColumns.ALBUM, AudioColumns.ALBUM_KEY,
                AudioColumns.ALBUM_ID, hashCode);
    }

    private static void computeAudioKeyValue(@NonNull ContentValues values, @NonNull String focus,
            @Nullable String focusKey, @Nullable String focusId, int hashValue) {
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
            final long id = Hashing.farmHashFingerprint64().hashString(key + hashValue,
                    StandardCharsets.UTF_8).asLong() & ~(1L << 63);
            values.put(focusId, id);
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
                    break;
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
                    break;
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

    /**
     * @return where clause to exclude database rows where
     * <ul>
     * <li> {@code column} is set or
     * <li> {@code column} is {@link MediaColumns#IS_PENDING} and is set by FUSE and not owned by
     * calling package.
     * </ul>
     */
    private String getWhereClauseForMatchExclude(@NonNull String column) {
        if (column.equalsIgnoreCase(MediaColumns.IS_PENDING)) {
            final String callingPackage = getCallingPackageOrSelf();
            final String matchSharedPackagesClause = FileColumns.OWNER_PACKAGE_NAME + " IN "
                    + getSharedPackages();
            // Include owned pending files from Fuse
            return String.format("%s=0 OR (%s=1 AND %s AND %s)", column, column,
                    MATCH_PENDING_FROM_FUSE, matchSharedPackagesClause);
        }
        return column + "=0";
    }

    /**
     * @return where clause to include database rows where
     * <ul>
     * <li> {@code column} is not set or
     * <li> {@code column} is set and calling package has write permission to corresponding db row
     *      or {@code column} is {@link MediaColumns#IS_PENDING} and is set by FUSE.
     * </ul>
     * The method is used to match db rows corresponding to writable pending and trashed files.
     */
    @Nullable
    private String getWhereClauseForMatchableVisibleFromFilePath(@NonNull Uri uri,
            @NonNull String column) {
        if (isCallingPackageLegacyWrite() || checkCallingPermissionGlobal(uri, /*forWrite*/ true)) {
            // No special filtering needed
            return null;
        }

        final String callingPackage = getCallingPackageOrSelf();

        final ArrayList<String> options = new ArrayList<>();
        switch(matchUri(uri, isCallingPackageAllowedHidden())) {
            case IMAGES_MEDIA_ID:
            case IMAGES_MEDIA:
            case IMAGES_THUMBNAILS_ID:
            case IMAGES_THUMBNAILS:
                if (checkCallingPermissionImages(/*forWrite*/ true, callingPackage)) {
                    // No special filtering needed
                    return null;
                }
                break;
            case AUDIO_MEDIA_ID:
            case AUDIO_MEDIA:
            case AUDIO_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS:
                if (checkCallingPermissionAudio(/*forWrite*/ true, callingPackage)) {
                    // No special filtering needed
                    return null;
                }
                break;
            case VIDEO_MEDIA_ID:
            case VIDEO_MEDIA:
            case VIDEO_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS:
                if (checkCallingPermissionVideo(/*firWrite*/ true, callingPackage)) {
                    // No special filtering needed
                    return null;
                }
                break;
            case DOWNLOADS_ID:
            case DOWNLOADS:
                // No app has special permissions for downloads.
                break;
            case FILES_ID:
            case FILES:
                if (checkCallingPermissionAudio(/*forWrite*/ true, callingPackage)) {
                    // Allow apps with audio permission to include audio* media types.
                    options.add(DatabaseUtils.bindSelection("media_type=?",
                            FileColumns.MEDIA_TYPE_AUDIO));
                    options.add(DatabaseUtils.bindSelection("media_type=?",
                            FileColumns.MEDIA_TYPE_PLAYLIST));
                    options.add(DatabaseUtils.bindSelection("media_type=?",
                            FileColumns.MEDIA_TYPE_SUBTITLE));
                }
                if (checkCallingPermissionVideo(/*forWrite*/ true, callingPackage)) {
                    // Allow apps with video permission to include video* media types.
                    options.add(DatabaseUtils.bindSelection("media_type=?",
                            FileColumns.MEDIA_TYPE_VIDEO));
                    options.add(DatabaseUtils.bindSelection("media_type=?",
                            FileColumns.MEDIA_TYPE_SUBTITLE));
                }
                if (checkCallingPermissionImages(/*forWrite*/ true, callingPackage)) {
                    // Allow apps with images permission to include images* media types.
                    options.add(DatabaseUtils.bindSelection("media_type=?",
                            FileColumns.MEDIA_TYPE_IMAGE));
                }
                break;
            default:
                // is_pending, is_trashed are not applicable for rest of the media tables.
                return null;
        }

        final String matchSharedPackagesClause = FileColumns.OWNER_PACKAGE_NAME + " IN "
                + getSharedPackages();
        options.add(DatabaseUtils.bindSelection(matchSharedPackagesClause));

        if (column.equalsIgnoreCase(MediaColumns.IS_PENDING)) {
            // Include all pending files from Fuse
            options.add(MATCH_PENDING_FROM_FUSE);
        }

        final String matchWritableRowsClause = String.format("%s=0 OR (%s=1 AND %s)", column,
                column, TextUtils.join(" OR ", options));
        return matchWritableRowsClause;
    }

    /**
     * Gets list of files in {@code path} from media provider database.
     *
     * @param path path of the directory.
     * @param uid UID of the calling process.
     * @return a list of file names in the given directory path.
     * An empty list is returned if no files are visible to the calling app or the given directory
     * does not have any files.
     * A list with ["/"] is returned if the path is not indexed by MediaProvider database or
     * calling package is a legacy app and has appropriate storage permissions for the given path.
     * In both scenarios file names should be obtained from lower file system.
     * A list with empty string[""] is returned if the calling package doesn't have access to the
     * given path.
     *
     * <p>Directory names are always obtained from lower file system.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public String[] getFilesInDirectoryForFuse(String path, int uid) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));

        try {
            if (isPrivatePackagePathNotOwnedByCaller(path)) {
                return new String[] {""};
            }

            // Do not allow apps to list Android/data or Android/obb dirs. Installer and
            // MOUNT_EXTERNAL_ANDROID_WRITABLE apps won't be blocked by this, as their OBB dirs
            // are mounted to lowerfs directly.
            if (isDataOrObbPath(path)) {
                return new String[] {""};
            }

            if (shouldBypassFuseRestrictions(/*forWrite*/ false, path)) {
                return new String[] {"/"};
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return new String[] {""};
            }

            // Get relative path for the contents of given directory.
            String relativePath = extractRelativePathForDirectory(path);

            if (relativePath == null) {
                // Path is /storage/emulated/, if relativePath is null, MediaProvider doesn't
                // have any details about the given directory. Use lower file system to obtain
                // files and directories in the given directory.
                return new String[] {"/"};
            }

            // For all other paths, get file names from media provider database.
            // Return media and non-media files visible to the calling package.
            ArrayList<String> fileNamesList = new ArrayList<>();

            // Only FileColumns.DATA contains actual name of the file.
            String[] projection = {MediaColumns.DATA};

            Bundle queryArgs = new Bundle();
            queryArgs.putString(QUERY_ARG_SQL_SELECTION, MediaColumns.RELATIVE_PATH +
                    " =? and mime_type not like 'null'");
            queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, new String[] {relativePath});
            // Get database entries for files from MediaProvider database with
            // MediaColumns.RELATIVE_PATH as the given path.
            try (final Cursor cursor = query(FileUtils.getContentUriForPath(path), projection,
                    queryArgs, null)) {
                while(cursor.moveToNext()) {
                    fileNamesList.add(extractDisplayName(cursor.getString(0)));
                }
            }
            return fileNamesList.toArray(new String[fileNamesList.size()]);
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Scan files during directory renames for the following reasons:
     * <ul>
     * <li>Because we don't update db rows for directories, we scan the oldPath to discard stale
     * directory db rows. This prevents conflicts during subsequent db operations with oldPath.
     * <li>We need to scan newPath as well, because the new directory may have become hidden
     * or unhidden, in which case we need to update the media types of the contained files
     * </ul>
     */
    private void scanRenamedDirectoryForFuse(@NonNull String oldPath, @NonNull String newPath) {
        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try {
            scanFile(new File(oldPath), REASON_DEMAND);
            scanFile(new File(newPath), REASON_DEMAND);
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Checks if given {@code mimeType} is supported in {@code path}.
     */
    private boolean isMimeTypeSupportedInPath(String path, String mimeType) {
        final String supportedPrimaryMimeType;
        final int match = matchUri(getContentUriForFile(path, mimeType), true);
        switch (match) {
            case AUDIO_MEDIA:
                supportedPrimaryMimeType = "audio";
                break;
            case VIDEO_MEDIA:
                supportedPrimaryMimeType = "video";
                break;
            case IMAGES_MEDIA:
                supportedPrimaryMimeType = "image";
                break;
            default:
                supportedPrimaryMimeType = ClipDescription.MIMETYPE_UNKNOWN;
        }
        return (supportedPrimaryMimeType.equalsIgnoreCase(ClipDescription.MIMETYPE_UNKNOWN) ||
                MimeUtils.startsWithIgnoreCase(mimeType, supportedPrimaryMimeType));
    }

    /**
     * Removes owner package for the renamed path if the calling package doesn't own the db row
     *
     * When oldPath is renamed to newPath, if newPath exists in the database, and caller is not the
     * owner of the file, owner package is set to 'null'. This prevents previous owner of newPath
     * from accessing renamed file.
     * @return {@code true} if
     * <ul>
     * <li> there is no corresponding database row for given {@code path}
     * <li> shared calling package is the owner of the database row
     * <li> owner package name is already set to 'null'
     * <li> updating owner package name to 'null' was successful.
     * </ul>
     * Returns {@code false} otherwise.
     */
    private boolean maybeRemoveOwnerPackageForFuseRename(@NonNull DatabaseHelper helper,
            @NonNull String path) {

        final Uri uri = FileUtils.getContentUriForPath(path);
        final int match = matchUri(uri, isCallingPackageAllowedHidden());
        final String ownerPackageName;
        final String selection = MediaColumns.DATA + " =? AND "
                + MediaColumns.OWNER_PACKAGE_NAME + " != 'null'";
        final String[] selectionArgs = new String[] {path};

        final SQLiteQueryBuilder qbForQuery =
                getQueryBuilder(TYPE_QUERY, match, uri, Bundle.EMPTY, null);
        try (Cursor c = qbForQuery.query(helper, new String[] {FileColumns.OWNER_PACKAGE_NAME},
                selection, selectionArgs, null, null, null, null, null)) {
            if (!c.moveToFirst()) {
                // We don't need to remove owner_package from db row if path doesn't exist in
                // database or owner_package is already set to 'null'
                return true;
            }
            ownerPackageName = c.getString(0);
            if (isCallingIdentitySharedPackageName(ownerPackageName)) {
                // We don't need to remove owner_package from db row if calling package is the owner
                // of the database row
                return true;
            }
        }

        final SQLiteQueryBuilder qbForUpdate =
                getQueryBuilder(TYPE_UPDATE, match, uri, Bundle.EMPTY, null);
        ContentValues values = new ContentValues();
        values.put(FileColumns.OWNER_PACKAGE_NAME, "null");
        return qbForUpdate.update(helper, values, selection, selectionArgs) == 1;
    }

    private boolean updateDatabaseForFuseRename(@NonNull DatabaseHelper helper,
            @NonNull String oldPath, @NonNull String newPath, @NonNull ContentValues values) {
        return updateDatabaseForFuseRename(helper, oldPath, newPath, values, Bundle.EMPTY);
    }

    /**
     * Updates database entry for given {@code path} with {@code values}
     */
    private boolean updateDatabaseForFuseRename(@NonNull DatabaseHelper helper,
            @NonNull String oldPath, @NonNull String newPath, @NonNull ContentValues values,
            @NonNull Bundle qbExtras) {
        final Uri uriOldPath = FileUtils.getContentUriForPath(oldPath);
        boolean allowHidden = isCallingPackageAllowedHidden();
        final SQLiteQueryBuilder qbForUpdate = getQueryBuilder(TYPE_UPDATE,
                matchUri(uriOldPath, allowHidden), uriOldPath, qbExtras, null);
        final String selection = MediaColumns.DATA + " =? ";
        int count = 0;
        boolean retryUpdateWithReplace = false;

        try {
            // TODO(b/146777893): System gallery apps can rename a media directory containing
            // non-media files. This update doesn't support updating non-media files that are not
            // owned by system gallery app.
            count = qbForUpdate.update(helper, values, selection, new String[]{oldPath});
        } catch (SQLiteConstraintException e) {
            Log.w(TAG, "Database update failed while renaming " + oldPath, e);
            retryUpdateWithReplace = true;
        }

        if (retryUpdateWithReplace) {
            // We are replacing file in newPath with file in oldPath. If calling package has
            // write permission for newPath, delete existing database entry and retry update.
            final Uri uriNewPath = FileUtils.getContentUriForPath(oldPath);
            final SQLiteQueryBuilder qbForDelete = getQueryBuilder(TYPE_DELETE,
                    matchUri(uriNewPath, allowHidden), uriNewPath, qbExtras, null);
            if (qbForDelete.delete(helper, selection, new String[] {newPath}) == 1) {
                Log.i(TAG, "Retrying database update after deleting conflicting entry");
                count = qbForUpdate.update(helper, values, selection, new String[]{oldPath});
            } else {
                return false;
            }
        }
        return count == 1;
    }

    /**
     * Gets {@link ContentValues} for updating database entry to {@code path}.
     */
    private ContentValues getContentValuesForFuseRename(String path, String newMimeType,
            boolean wasHidden, boolean isHidden) {
        ContentValues values = new ContentValues();
        values.put(MediaColumns.MIME_TYPE, newMimeType);
        values.put(MediaColumns.DATA, path);

        if (isHidden) {
            values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_NONE);
        } else {
            int mediaType = MimeUtils.resolveMediaType(newMimeType);
            values.put(FileColumns.MEDIA_TYPE, mediaType);
            if (wasHidden) {
                // Set this as pending so that apps can scan the file to update the metadata.
                // Otherwise, scan will skip scanning this file because rename() doesn't change
                // lastModifiedTime and scan assumes there is no change in the file.
                // This should be safe because, in Q, apps had to insert new db row or scan the file
                // to insert this file to database.
                values.put(FileColumns.IS_PENDING, 1);
            }
        }
        final boolean allowHidden = isCallingPackageAllowedHidden();
        if (!newMimeType.equalsIgnoreCase("null") &&
                matchUri(getContentUriForFile(path, newMimeType), allowHidden) == AUDIO_MEDIA) {
            computeAudioLocalizedValues(values);
            computeAudioKeyValues(values);
        }
        FileUtils.computeValuesFromData(values, isFuseThread());
        return values;
    }

    private ArrayList<String> getIncludedDefaultDirectories() {
        final ArrayList<String> includedDefaultDirs = new ArrayList<>();
        if (checkCallingPermissionVideo(/*forWrite*/ true, null)) {
            includedDefaultDirs.add(DIRECTORY_DCIM);
            includedDefaultDirs.add(DIRECTORY_PICTURES);
            includedDefaultDirs.add(DIRECTORY_MOVIES);
        } else if (checkCallingPermissionImages(/*forWrite*/ true, null)) {
            includedDefaultDirs.add(DIRECTORY_DCIM);
            includedDefaultDirs.add(DIRECTORY_PICTURES);
        }
        return includedDefaultDirs;
    }

    /**
     * Gets all files in the given {@code path} and subdirectories of the given {@code path}.
     */
    private ArrayList<String> getAllFilesForRenameDirectory(String oldPath) {
        final String selection = FileColumns.DATA + " LIKE ? ESCAPE '\\'"
                + " and mime_type not like 'null'";
        final String[] selectionArgs = new String[] {DatabaseUtils.escapeForLike(oldPath) + "/%"};
        ArrayList<String> fileList = new ArrayList<>();

        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try (final Cursor c = query(FileUtils.getContentUriForPath(oldPath),
                new String[] {MediaColumns.DATA}, selection, selectionArgs, null)) {
            while (c.moveToNext()) {
                String filePath = c.getString(0);
                filePath = filePath.replaceFirst(Pattern.quote(oldPath + "/"), "");
                fileList.add(filePath);
            }
        } finally {
            restoreLocalCallingIdentity(token);
        }
        return fileList;
    }

    /**
     * Gets files in the given {@code path} and subdirectories of the given {@code path} for which
     * calling package has write permissions.
     *
     * This method throws {@code IllegalArgumentException} if the directory has one or more
     * files for which calling package doesn't have write permission or if file type is not
     * supported in {@code newPath}
     */
    private ArrayList<String> getWritableFilesForRenameDirectory(String oldPath, String newPath)
            throws IllegalArgumentException {
        // Try a simple check to see if the caller has full access to the given collections first
        // before falling back to performing a query to probe for access.
        final String oldRelativePath = extractRelativePathForDirectory(oldPath);
        final String newRelativePath = extractRelativePathForDirectory(newPath);
        boolean hasFullAccessToOldPath = false;
        boolean hasFullAccessToNewPath = false;
        for (String defaultDir : getIncludedDefaultDirectories()) {
            if (oldRelativePath.startsWith(defaultDir)) hasFullAccessToOldPath = true;
            if (newRelativePath.startsWith(defaultDir)) hasFullAccessToNewPath = true;
        }
        if (hasFullAccessToNewPath && hasFullAccessToOldPath) {
            return getAllFilesForRenameDirectory(oldPath);
        }

        final int countAllFilesInDirectory;
        final String selection = FileColumns.DATA + " LIKE ? ESCAPE '\\'"
                + " and mime_type not like 'null'";
        final String[] selectionArgs = new String[] {DatabaseUtils.escapeForLike(oldPath) + "/%"};

        final Uri uriOldPath = FileUtils.getContentUriForPath(oldPath);

        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try (final Cursor c = query(uriOldPath, new String[] {MediaColumns._ID}, selection,
                selectionArgs, null)) {
            // get actual number of files in the given directory.
            countAllFilesInDirectory = c.getCount();
        } finally {
            restoreLocalCallingIdentity(token);
        }

        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE,
                matchUri(uriOldPath, isCallingPackageAllowedHidden()), uriOldPath, Bundle.EMPTY,
                null);
        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(uriOldPath);
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Volume not found while querying files for renaming "
                    + oldPath);
        }

        ArrayList<String> fileList = new ArrayList<>();
        final String[] projection = {MediaColumns.DATA, MediaColumns.MIME_TYPE};
        try (Cursor c = qb.query(helper, projection, selection, selectionArgs, null, null, null,
                null, null)) {
            // Check if the calling package has write permission to all files in the given
            // directory. If calling package has write permission to all files in the directory, the
            // query with update uri should return same number of files as previous query.
            if (c.getCount() != countAllFilesInDirectory) {
                throw new IllegalArgumentException("Calling package doesn't have write permission "
                        + " to rename one or more files in " + oldPath);
            }
            while(c.moveToNext()) {
                String filePath = c.getString(0);
                filePath = filePath.replaceFirst(Pattern.quote(oldPath + "/"), "");

                final String mimeType = c.getString(1);
                if (!isMimeTypeSupportedInPath(newPath + "/" + filePath, mimeType)) {
                    throw new IllegalArgumentException("Can't rename " + oldPath + "/" + filePath
                            + ". Mime type " + mimeType + " not supported in " + newPath);
                }
                fileList.add(filePath);
            }
        }
        return fileList;
    }

    private int renameInLowerFs(String oldPath, String newPath) {
        try {
            Os.rename(oldPath, newPath);
            return 0;
        } catch (ErrnoException e) {
            final String errorMessage = "Rename " + oldPath + " to " + newPath + " failed.";
            Log.e(TAG, errorMessage, e);
            return e.errno;
        }
    }

    /**
     * Rename directory from {@code oldPath} to {@code newPath}.
     *
     * Renaming a directory is only allowed if calling package has write permission to all files in
     * the given directory tree and all file types in the given directory tree are supported by the
     * top level directory of new path. Renaming a directory is split into three steps:
     * 1. Check calling package's permissions for all files in the given directory tree. Also check
     *    file type support for all files in the {@code newPath}.
     * 2. Try updating database for all files in the directory.
     * 3. Rename the directory in lower file system. If rename in the lower file system is
     *    successful, commit database update.
     *
     * @param oldPath path of the directory to be renamed.
     * @param newPath new path of directory to be renamed.
     * @return 0 on successful rename, appropriate negated errno value if the rename is not allowed.
     * <ul>
     * <li>{@link OsConstants#EPERM} Renaming a directory with file types not supported by
     * {@code newPath} or renaming a directory with files for which calling package doesn't have
     * write permission.
     * This method can also return errno returned from {@code Os.rename} function.
     */
    private int renameDirectoryCheckedForFuse(String oldPath, String newPath) {
        final ArrayList<String> fileList;
        try {
            fileList = getWritableFilesForRenameDirectory(oldPath, newPath);
        } catch (IllegalArgumentException e) {
            final String errorMessage = "Rename " + oldPath + " to " + newPath + " failed. ";
            Log.e(TAG, errorMessage, e);
            return OsConstants.EPERM;
        }

        return renameDirectoryUncheckedForFuse(oldPath, newPath, fileList);
    }

    private int renameDirectoryUncheckedForFuse(String oldPath, String newPath,
            ArrayList<String> fileList) {
        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(FileUtils.getContentUriForPath(oldPath));
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Volume not found while trying to update database for "
                    + oldPath, e);
        }

        helper.beginTransaction();
        try {
            final Bundle qbExtras = new Bundle();
            qbExtras.putStringArrayList(INCLUDED_DEFAULT_DIRECTORIES,
                    getIncludedDefaultDirectories());
            final boolean wasHidden = FileUtils.isDirectoryHidden(new File(oldPath));
            final boolean isHidden = FileUtils.isDirectoryHidden(new File(newPath));
            for (String filePath : fileList) {
                final String newFilePath = newPath + "/" + filePath;
                final String mimeType = MimeUtils.resolveMimeType(new File(newFilePath));
                if(!updateDatabaseForFuseRename(helper, oldPath + "/" + filePath, newFilePath,
                        getContentValuesForFuseRename(newFilePath, mimeType, wasHidden, isHidden),
                        qbExtras)) {
                    Log.e(TAG, "Calling package doesn't have write permission to rename file.");
                    return OsConstants.EPERM;
                }
            }

            // Rename the directory in lower file system.
            int errno = renameInLowerFs(oldPath, newPath);
            if (errno == 0) {
                helper.setTransactionSuccessful();
            } else {
                return errno;
            }
        } finally {
            helper.endTransaction();
        }
        // Directory movement might have made new/old path hidden.
        scanRenamedDirectoryForFuse(oldPath, newPath);
        return 0;
    }

    /**
     * Rename a file from {@code oldPath} to {@code newPath}.
     *
     * Renaming a file is split into three parts:
     * 1. Check if {@code newPath} supports new file type.
     * 2. Try updating database entry from {@code oldPath} to {@code newPath}. This update may fail
     *    if calling package doesn't have write permission for {@code oldPath} and {@code newPath}.
     * 3. Rename the file in lower file system. If Rename in lower file system succeeds, commit
     *    database update.
     * @param oldPath path of the file to be renamed.
     * @param newPath new path of the file to be renamed.
     * @return 0 on successful rename, appropriate negated errno value if the rename is not allowed.
     * <ul>
     * <li>{@link OsConstants#EPERM} Calling package doesn't have write permission for
     * {@code oldPath} or {@code newPath}, or file type is not supported by {@code newPath}.
     * This method can also return errno returned from {@code Os.rename} function.
     */
    private int renameFileCheckedForFuse(String oldPath, String newPath) {
        // Check if new mime type is supported in new path.
        final String newMimeType = MimeUtils.resolveMimeType(new File(newPath));
        if (!isMimeTypeSupportedInPath(newPath, newMimeType)) {
            return OsConstants.EPERM;
        }
        return renameFileForFuse(oldPath, newPath, /* bypassRestrictions */ false) ;
    }

    private int renameFileUncheckedForFuse(String oldPath, String newPath) {
        return renameFileForFuse(oldPath, newPath, /* bypassRestrictions */ true) ;
    }

    private static boolean shouldFileBeHidden(@NonNull File file) {
        if (FileUtils.isFileHidden(file)) {
            return true;
        }
        File parent = file.getParentFile();
        while (parent != null) {
            if (FileUtils.isDirectoryHidden(parent)) {
                return true;
            }
            parent = parent.getParentFile();
        }

        return false;
    }

    private int renameFileForFuse(String oldPath, String newPath, boolean bypassRestrictions) {
        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(FileUtils.getContentUriForPath(oldPath));
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Failed to update database row with " + oldPath, e);
        }

        final boolean wasHidden = shouldFileBeHidden(new File(oldPath));
        final boolean isHidden = shouldFileBeHidden(new File(newPath));
        helper.beginTransaction();
        try {
            final String newMimeType = MimeUtils.resolveMimeType(new File(newPath));
            if (!updateDatabaseForFuseRename(helper, oldPath, newPath,
                    getContentValuesForFuseRename(newPath, newMimeType, wasHidden, isHidden))) {
                if (!bypassRestrictions) {
                    Log.e(TAG, "Calling package doesn't have write permission to rename file.");
                    return OsConstants.EPERM;
                } else if (!maybeRemoveOwnerPackageForFuseRename(helper, newPath)) {
                    Log.wtf(TAG, "Couldn't clear owner package name for " + newPath);
                    return OsConstants.EPERM;
                }
            }

            // Try renaming oldPath to newPath in lower file system.
            int errno = renameInLowerFs(oldPath, newPath);
            if (errno == 0) {
                helper.setTransactionSuccessful();
            } else {
                return errno;
            }
        } finally {
            helper.endTransaction();
        }
        // The above code should have taken are of the mime/media type of the new file,
        // even if it was moved to/from a hidden directory.
        // This leaves cases where the source/dest of the move is a .nomedia file itself. Eg:
        // 1) /sdcard/foo/.nomedia => /sdcard/foo/bar.mp3
        //    in this case, the code above has given bar.mp3 the correct mime type, but we should
        //    still can /sdcard/foo, because it's now no longer hidden
        // 2) /sdcard/foo/.nomedia => /sdcard/bar/.nomedia
        //    in this case, we need to scan both /sdcard/foo and /sdcard/bar/
        // 3) /sdcard/foo/bar.mp3 => /sdcard/foo/.nomedia
        //    in this case, we need to scan all of /sdcard/foo
        if (extractDisplayName(oldPath).equals(".nomedia")) {
            scanFile(new File(oldPath).getParentFile(), REASON_DEMAND);
        }
        if (extractDisplayName(newPath).equals(".nomedia")) {
            scanFile(new File(newPath).getParentFile(), REASON_DEMAND);
        }

        return 0;
    }

    /**
     * Rename file/directory without imposing any restrictions.
     *
     * We don't impose any rename restrictions for apps that bypass scoped storage restrictions.
     * However, we update database entries for renamed files to keep the database consistent.
     */
    private int renameUncheckedForFuse(String oldPath, String newPath) {
        if (new File(oldPath).isFile()) {
            return renameFileUncheckedForFuse(oldPath, newPath);
        } else {
            return renameDirectoryUncheckedForFuse(oldPath, newPath,
                    getAllFilesForRenameDirectory(oldPath));
        }
    }

    /**
     * Rename file or directory from {@code oldPath} to {@code newPath}.
     *
     * @param oldPath path of the file or directory to be renamed.
     * @param newPath new path of the file or directory to be renamed.
     * @param uid UID of the calling package.
     * @return 0 on successful rename, appropriate errno value if the rename is not allowed.
     * <ul>
     * <li>{@link OsConstants#ENOENT} Renaming a non-existing file or renaming a file from path that
     * is not indexed by MediaProvider database.
     * <li>{@link OsConstants#EPERM} Renaming a default directory or renaming a file to a file type
     * not supported by new path.
     * This method can also return errno returned from {@code Os.rename} function.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int renameForFuse(String oldPath, String newPath, int uid) {
        final String errorMessage = "Rename " + oldPath + " to " + newPath + " failed. ";
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));

        try {
            if (isPrivatePackagePathNotOwnedByCaller(oldPath)
                    || isPrivatePackagePathNotOwnedByCaller(newPath)) {
                return OsConstants.EACCES;
            }

            if (!newPath.equals(getAbsoluteSanitizedPath(newPath))) {
                Log.e(TAG, "New path name contains invalid characters.");
                return OsConstants.EPERM;
            }

            if (shouldBypassDatabaseAndSetDirtyForFuse(uid, newPath)) {
                return renameInLowerFs(oldPath, newPath);
            }

            if (shouldBypassFuseRestrictions(/*forWrite*/ true, oldPath)
                    && shouldBypassFuseRestrictions(/*forWrite*/ true, newPath)) {
                return renameUncheckedForFuse(oldPath, newPath);
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EACCES;
            }

            final String[] oldRelativePath = sanitizePath(extractRelativePath(oldPath));
            final String[] newRelativePath = sanitizePath(extractRelativePath(newPath));
            if (oldRelativePath.length == 0 || newRelativePath.length == 0) {
                // Rename not allowed on paths that can't be translated to RELATIVE_PATH.
                Log.e(TAG, errorMessage +  "Invalid path.");
                return OsConstants.EPERM;
            }
            if (oldRelativePath.length == 1 && TextUtils.isEmpty(oldRelativePath[0])) {
                // Allow rename of files/folders other than default directories.
                final String displayName = extractDisplayName(oldPath);
                for (String defaultFolder : DEFAULT_FOLDER_NAMES) {
                    if (displayName.equals(defaultFolder)) {
                        Log.e(TAG, errorMessage + oldPath + " is a default folder."
                                + " Renaming a default folder is not allowed.");
                        return OsConstants.EPERM;
                    }
                }
            }
            if (newRelativePath.length == 1 && TextUtils.isEmpty(newRelativePath[0])) {
                Log.e(TAG, errorMessage +  newPath + " is in root folder."
                        + " Renaming a file/directory to root folder is not allowed");
                return OsConstants.EPERM;
            }

            final File directoryAndroid = new File(Environment.getExternalStorageDirectory(),
                    DIRECTORY_ANDROID);
            final File directoryAndroidMedia = new File(directoryAndroid, DIRECTORY_MEDIA);
            if (directoryAndroidMedia.getAbsolutePath().equals(oldPath)) {
                // Don't allow renaming 'Android/media' directory.
                // Android/[data|obb] are bind mounted and these paths don't go through FUSE.
                Log.e(TAG, errorMessage +  oldPath + " is a default folder in app external "
                        + "directory. Renaming a default folder is not allowed.");
                return OsConstants.EPERM;
            } else if (FileUtils.contains(directoryAndroid, new File(newPath))) {
                if (newRelativePath.length == 1) {
                    // New path is Android/*. Path is directly under Android. Don't allow moving
                    // files and directories to Android/.
                    Log.e(TAG, errorMessage +  newPath + " is in app external directory. "
                            + "Renaming a file/directory to app external directory is not "
                            + "allowed.");
                    return OsConstants.EPERM;
                } else if(!FileUtils.contains(directoryAndroidMedia, new File(newPath))) {
                    // New path is  Android/*/*. Don't allow moving of files or directories
                    // to app external directory other than media directory.
                    Log.e(TAG, errorMessage +  newPath + " is not in external media directory."
                            + "File/directory can only be renamed to a path in external media "
                            + "directory. Renaming file/directory to path in other external "
                            + "directories is not allowed");
                    return OsConstants.EPERM;
                }
            }

            // Continue renaming files/directories if rename of oldPath to newPath is allowed.
            if (new File(oldPath).isFile()) {
                return renameFileCheckedForFuse(oldPath, newPath);
            } else {
                return renameDirectoryCheckedForFuse(oldPath, newPath);
            }
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    @Override
    public int checkUriPermission(@NonNull Uri uri, int uid,
            /* @Intent.AccessUriMode */ int modeFlags) {
        final LocalCallingIdentity token = clearLocalCallingIdentity(
                LocalCallingIdentity.fromExternal(getContext(), uid));

        try {
            final boolean allowHidden = isCallingPackageAllowedHidden();
            final int table = matchUri(uri, allowHidden);

            final DatabaseHelper helper;
            try {
                helper = getDatabaseForUri(uri);
            } catch (VolumeNotFoundException e) {
                return PackageManager.PERMISSION_DENIED;
            }

            final int type;
            final boolean forWrite;
            if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                type = TYPE_UPDATE;
                forWrite = true;
            } else {
                type = TYPE_QUERY;
                forWrite = false;
            }

            final SQLiteQueryBuilder qb = getQueryBuilder(type, table, uri, Bundle.EMPTY, null);
            try (Cursor c = qb.query(helper,
                    new String[] { BaseColumns._ID }, null, null, null, null, null, null, null)) {
                if (c.getCount() == 1) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }

            try {
                if (ContentUris.parseId(uri) != -1) {
                    return PackageManager.PERMISSION_DENIED;
                }
            } catch (NumberFormatException ignored) { }

            // If the uri is a valid content uri and doesn't have a valid ID at the end of the uri,
            // (i.e., uri is uri of the table not of the item/row), and app doesn't request prefix
            // grant, we are willing to grant this uri permission since this doesn't grant them any
            // extra access. This grant will only grant permissions on given uri, it will not grant
            // access to db rows of the corresponding table.
            if ((modeFlags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0) {
                return PackageManager.PERMISSION_GRANTED;
            }

            // For prefix grant on the uri with content uri without id, we don't allow apps to
            // grant access as they might end up granting access to all files.
        } finally {
            restoreLocalCallingIdentity(token);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return query(uri, projection,
                DatabaseUtils.createSqlQueryBundle(selection, selectionArgs, sortOrder), null);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
        return query(uri, projection, queryArgs, signal, /* forSelf */ false);
    }

    private Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal signal, boolean forSelf) {
        try {
            return queryInternal(uri, projection, queryArgs, signal, forSelf);
        } catch (FallbackException e) {
            return e.translateForQuery(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private Cursor queryInternal(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal signal, boolean forSelf) throws FallbackException {
        queryArgs = (queryArgs != null) ? queryArgs : new Bundle();

        // INCLUDED_DEFAULT_DIRECTORIES extra should only be set inside MediaProvider.
        queryArgs.remove(INCLUDED_DEFAULT_DIRECTORIES);

        final ArraySet<String> honoredArgs = new ArraySet<>();
        DatabaseUtils.resolveQueryArgs(queryArgs, honoredArgs::add, this::ensureCustomCollator);

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
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_QUERY, table, uri, queryArgs,
                honoredArgs::add);

        if (targetSdkVersion < Build.VERSION_CODES.R) {
            // Some apps are abusing "ORDER BY" clauses to inject "LIMIT"
            // clauses; gracefully lift them out.
            DatabaseUtils.recoverAbusiveSortOrder(queryArgs);

            // Some apps are abusing the Uri query parameters to inject LIMIT
            // clauses; gracefully lift them out.
            DatabaseUtils.recoverAbusiveLimit(uri, queryArgs);
        }

        if (targetSdkVersion < Build.VERSION_CODES.Q) {
            // Some apps are abusing the "WHERE" clause by injecting "GROUP BY"
            // clauses; gracefully lift them out.
            DatabaseUtils.recoverAbusiveSelection(queryArgs);

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
            final String selection = queryArgs.getString(QUERY_ARG_SQL_SELECTION);
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
                    final File file = ContentResolver.encodeToFile(
                            fullUri.buildUpon().appendPath("thumbnail").build());
                    final String data = file.getAbsolutePath();
                    cursor.newRow().add(MediaColumns._ID, null)
                            .add(Images.Thumbnails.IMAGE_ID, id)
                            .add(Video.Thumbnails.VIDEO_ID, id)
                            .add(MediaColumns.DATA, data);
                    return cursor;
                }
            }
        }

        final Cursor c = qb.query(helper, projection, queryArgs, signal);
        if (c != null && !forSelf) {
            // As a performance optimization, only configure notifications when
            // resulting cursor will leave our process
            final boolean callerIsRemote = mCallingIdentity.get().pid != android.os.Process.myPid();
            if (callerIsRemote && !isFuseThread()) {
                c.setNotificationUri(getContext().getContentResolver(), uri);
            }

            final Bundle extras = new Bundle();
            extras.putStringArray(ContentResolver.EXTRA_HONORED_ARGS,
                    honoredArgs.toArray(new String[honoredArgs.size()]));
            c.setExtras(extras);
        }
        return c;
    }

    @Override
    public String getType(Uri url) {
        final int match = matchUri(url, true);
        switch (match) {
            case IMAGES_MEDIA_ID:
            case AUDIO_MEDIA_ID:
            case AUDIO_PLAYLISTS_ID:
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
                return Audio.Playlists.CONTENT_TYPE;

            case VIDEO_MEDIA:
                return Video.Media.CONTENT_TYPE;
            case DOWNLOADS:
                return Downloads.CONTENT_TYPE;
        }
        throw new IllegalStateException("Unknown URL : " + url);
    }

    @VisibleForTesting
    void ensureFileColumns(@NonNull Uri uri, @NonNull ContentValues values)
            throws VolumeArgumentException, VolumeNotFoundException {
        final LocalUriMatcher matcher = new LocalUriMatcher(MediaStore.AUTHORITY);
        final int match = matcher.matchUri(uri, true);
        ensureNonUniqueFileColumns(match, uri, Bundle.EMPTY, values, null /* currentPath */);
    }

    private void ensureUniqueFileColumns(int match, @NonNull Uri uri, @NonNull Bundle extras,
            @NonNull ContentValues values, @Nullable String currentPath)
            throws VolumeArgumentException, VolumeNotFoundException {
        ensureFileColumns(match, uri, extras, values, true, currentPath);
    }

    private void ensureNonUniqueFileColumns(int match, @NonNull Uri uri,
            @NonNull Bundle extras, @NonNull ContentValues values, @Nullable String currentPath)
            throws VolumeArgumentException, VolumeNotFoundException {
        ensureFileColumns(match, uri, extras, values, false, currentPath);
    }

    /**
     * Get the various file-related {@link MediaColumns} in the given
     * {@link ContentValues} into sane condition. Also validates that defined
     * columns are valid for the given {@link Uri}, such as ensuring that only
     * {@code image/*} can be inserted into
     * {@link android.provider.MediaStore.Images}.
     */
    private void ensureFileColumns(int match, @NonNull Uri uri, @NonNull Bundle extras,
            @NonNull ContentValues values, boolean makeUnique, @Nullable String currentPath)
            throws VolumeArgumentException, VolumeNotFoundException {
        Trace.beginSection("ensureFileColumns");

        Objects.requireNonNull(uri);
        Objects.requireNonNull(extras);
        Objects.requireNonNull(values);

        // Figure out defaults based on Uri being modified
        String defaultMimeType = ClipDescription.MIMETYPE_UNKNOWN;
        int defaultMediaType = FileColumns.MEDIA_TYPE_NONE;
        String defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
        String defaultSecondary = null;
        List<String> allowedPrimary = Arrays.asList(
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_DOCUMENTS);
        switch (match) {
            case AUDIO_MEDIA:
            case AUDIO_MEDIA_ID:
                defaultMimeType = "audio/mpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_AUDIO;
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_ALARMS,
                        Environment.DIRECTORY_AUDIOBOOKS,
                        Environment.DIRECTORY_MUSIC,
                        Environment.DIRECTORY_NOTIFICATIONS,
                        Environment.DIRECTORY_PODCASTS,
                        Environment.DIRECTORY_RINGTONES);
                break;
            case VIDEO_MEDIA:
            case VIDEO_MEDIA_ID:
                defaultMimeType = "video/mp4";
                defaultMediaType = FileColumns.MEDIA_TYPE_VIDEO;
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_DCIM,
                        Environment.DIRECTORY_MOVIES,
                        Environment.DIRECTORY_PICTURES);
                break;
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
                defaultMimeType = "image/jpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_IMAGE;
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_DCIM,
                        Environment.DIRECTORY_PICTURES);
                break;
            case AUDIO_ALBUMART:
            case AUDIO_ALBUMART_ID:
                defaultMimeType = "image/jpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_IMAGE;
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case VIDEO_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
                defaultMimeType = "image/jpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_IMAGE;
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case IMAGES_THUMBNAILS:
            case IMAGES_THUMBNAILS_ID:
                defaultMimeType = "image/jpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_IMAGE;
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case AUDIO_PLAYLISTS:
            case AUDIO_PLAYLISTS_ID:
                defaultMimeType = "audio/mpegurl";
                defaultMediaType = FileColumns.MEDIA_TYPE_PLAYLIST;
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                allowedPrimary = Arrays.asList(
                        Environment.DIRECTORY_MUSIC,
                        Environment.DIRECTORY_MOVIES);
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
            FileUtils.computeValuesFromData(values, isFuseThread());
        }

        final boolean isTargetSdkROrHigher =
                getCallingPackageTargetSdkVersion() >= Build.VERSION_CODES.R;
        final String displayName = values.getAsString(MediaColumns.DISPLAY_NAME);
        final String mimeTypeFromExt = TextUtils.isEmpty(displayName) ? null :
                MimeUtils.resolveMimeType(new File(displayName));

        if (TextUtils.isEmpty(values.getAsString(MediaColumns.MIME_TYPE))) {
            if (isTargetSdkROrHigher) {
                // Extract the MIME type from the display name if we couldn't resolve it from the
                // raw path
                if (mimeTypeFromExt != null) {
                    values.put(MediaColumns.MIME_TYPE, mimeTypeFromExt);
                } else {
                    // We couldn't resolve mimeType, it means that both display name and MIME type
                    // were missing in values, so we use defaultMimeType.
                    values.put(MediaColumns.MIME_TYPE, defaultMimeType);
                }
            } else if (defaultMediaType == FileColumns.MEDIA_TYPE_NONE) {
                values.put(MediaColumns.MIME_TYPE, mimeTypeFromExt);
            } else {
                // We don't use mimeTypeFromExt to preserve legacy behavior.
                values.put(MediaColumns.MIME_TYPE, defaultMimeType);
            }
        }

        String mimeType = values.getAsString(MediaColumns.MIME_TYPE);
        if (defaultMediaType == FileColumns.MEDIA_TYPE_NONE) {
            // We allow any mimeType for generic uri with default media type as MEDIA_TYPE_NONE.
        } else if (mimeType != null &&
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) == null) {
            if (mimeTypeFromExt != null &&
                    defaultMediaType == MimeUtils.resolveMediaType(mimeTypeFromExt)) {
                // If mimeType from extension matches the defaultMediaType of uri, we use mimeType
                // from file extension as mimeType. This is an effort to guess the mimeType when we
                // get unsupported mimeType.
                // Note: We can't force defaultMimeType because when we force defaultMimeType, we
                // will force the file extension as well. For example, if DISPLAY_NAME=Foo.png and
                // mimeType="image/*". If we force mimeType to be "image/jpeg", we append the file
                // name with the new file extension i.e., "Foo.png.jpg" where as the expected file
                // name was "Foo.png"
                values.put(MediaColumns.MIME_TYPE, mimeTypeFromExt);
            } else if (isTargetSdkROrHigher) {
                // We are here because given mimeType is unsupported also we couldn't guess valid
                // mimeType from file extension.
                throw new IllegalArgumentException("Unsupported MIME type " + mimeType);
            } else {
                // We can't throw error for legacy apps, so we try to use defaultMimeType.
                values.put(MediaColumns.MIME_TYPE, defaultMimeType);
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
        }

        mimeType = values.getAsString(MediaColumns.MIME_TYPE);
        // Sanity check MIME type against table
        if (mimeType != null) {
            final int actualMediaType = MimeUtils.resolveMediaType(mimeType);
            if (defaultMediaType == FileColumns.MEDIA_TYPE_NONE) {
                // Give callers an opportunity to work with playlists and
                // subtitles using the generic files table
                switch (actualMediaType) {
                    case FileColumns.MEDIA_TYPE_PLAYLIST:
                        defaultMimeType = "audio/mpegurl";
                        defaultMediaType = FileColumns.MEDIA_TYPE_PLAYLIST;
                        defaultPrimary = Environment.DIRECTORY_MUSIC;
                        allowedPrimary = new ArrayList<>(allowedPrimary);
                        allowedPrimary.add(Environment.DIRECTORY_MUSIC);
                        allowedPrimary.add(Environment.DIRECTORY_MOVIES);
                        break;
                    case FileColumns.MEDIA_TYPE_SUBTITLE:
                        defaultMimeType = "application/x-subrip";
                        defaultMediaType = FileColumns.MEDIA_TYPE_SUBTITLE;
                        defaultPrimary = Environment.DIRECTORY_MOVIES;
                        allowedPrimary = new ArrayList<>(allowedPrimary);
                        allowedPrimary.add(Environment.DIRECTORY_MUSIC);
                        allowedPrimary.add(Environment.DIRECTORY_MOVIES);
                        break;
                }
            } else if (defaultMediaType != actualMediaType) {
                final String[] split = defaultMimeType.split("/");
                throw new IllegalArgumentException(
                        "MIME type " + mimeType + " cannot be inserted into " + uri
                                + "; expected MIME type under " + split[0] + "/*");
            }
        }

        // Use default directories when missing
        if (TextUtils.isEmpty(values.getAsString(MediaColumns.RELATIVE_PATH))) {
            if (defaultSecondary != null) {
                values.put(MediaColumns.RELATIVE_PATH,
                        defaultPrimary + '/' + defaultSecondary + '/');
            } else {
                values.put(MediaColumns.RELATIVE_PATH,
                        defaultPrimary + '/');
            }
        }

        // Generate path when undefined
        if (TextUtils.isEmpty(values.getAsString(MediaColumns.DATA))) {
            File volumePath;
            try {
                volumePath = getVolumePath(resolvedVolumeName);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(e);
            }

            FileUtils.sanitizeValues(values, /*rewriteHiddenFileName*/ !isFuseThread());
            FileUtils.computeDataFromValues(values, volumePath, isFuseThread());

            // Create result file
            File res = new File(values.getAsString(MediaColumns.DATA));
            try {
                if (makeUnique) {
                    res = FileUtils.buildUniqueFile(res.getParentFile(),
                            mimeType, res.getName());
                } else {
                    res = FileUtils.buildNonUniqueFile(res.getParentFile(),
                            mimeType, res.getName());
                }
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(
                        "Failed to build unique file: " + res + " " + values);
            }

            // Require that content lives under well-defined directories to help
            // keep the user's content organized

            // Start by saying unchanged directories are valid
            final String currentDir = (currentPath != null)
                    ? new File(currentPath).getParent() : null;
            boolean validPath = res.getParent().equals(currentDir);

            // Next, consider allowing based on allowed primary directory
            final String[] relativePath = values.getAsString(MediaColumns.RELATIVE_PATH).split("/");
            final String primary = (relativePath.length > 0) ? relativePath[0] : null;
            if (!validPath) {
                validPath = allowedPrimary.contains(primary);
            }

            // Next, consider allowing paths when referencing a related item
            final Uri relatedUri = extras.getParcelable(QUERY_ARG_RELATED_URI);
            if (!validPath && relatedUri != null) {
                try (Cursor c = queryForSingleItem(relatedUri, new String[] {
                        MediaColumns.MIME_TYPE,
                        MediaColumns.RELATIVE_PATH,
                }, null, null, null)) {
                    // If top-level MIME type matches, and relative path
                    // matches, then allow caller to place things here

                    final String expectedType = MimeUtils.extractPrimaryType(
                            c.getString(0));
                    final String actualType = MimeUtils.extractPrimaryType(
                            values.getAsString(MediaColumns.MIME_TYPE));
                    if (!Objects.equals(expectedType, actualType)) {
                        throw new IllegalArgumentException("Placement of " + actualType
                                + " item not allowed in relation to " + expectedType + " item");
                    }

                    final String expectedPath = c.getString(1);
                    final String actualPath = values.getAsString(MediaColumns.RELATIVE_PATH);
                    if (!Objects.equals(expectedPath, actualPath)) {
                        throw new IllegalArgumentException("Placement of " + actualPath
                                + " item not allowed in relation to " + expectedPath + " item");
                    }

                    // If we didn't see any trouble above, then we'll allow it
                    validPath = true;
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to find related item " + relatedUri + ": " + e);
                }
            }

            // Consider allowing external media directory of calling package
            if (!validPath) {
                final String pathOwnerPackage = extractPathOwnerPackageName(res.getAbsolutePath());
                if (pathOwnerPackage != null) {
                    validPath = isExternalMediaDirectory(res.getAbsolutePath()) &&
                            isCallingIdentitySharedPackageName(pathOwnerPackage);
                }
            }

            // Allow apps with MANAGE_EXTERNAL_STORAGE to create files anywhere
            if (!validPath) {
                validPath = isCallingPackageManager();
            }

            // Allow system gallery to create image/video files.
            if (!validPath) {
                // System gallery can create image/video files in any existing directory, it can
                // also create subdirectories in any existing top-level directory. However, system
                // gallery is not allowed to create non-default top level directory.
                final boolean createNonDefaultTopLevelDir = primary != null &&
                        !FileUtils.buildPath(volumePath, primary).exists();
                validPath = !createNonDefaultTopLevelDir &&
                        canAccessMediaFile(res.getAbsolutePath(), /*allowLegacy*/ false);
            }

            // Nothing left to check; caller can't use this path
            if (!validPath) {
                throw new IllegalArgumentException(
                        "Primary directory " + primary + " not allowed for " + uri
                                + "; allowed directories are " + allowedPrimary);
            }

            boolean isFuseThread = isFuseThread();
            // Check if the following are true:
            // 1. Not a FUSE thread
            // 2. |res| is a child of a default dir and the default dir is missing
            // If true, we want to update the mTime of the volume root, after creating the dir
            // on the lower filesystem. This fixes some FileManagers relying on the mTime change
            // for UI updates
            File defaultDirVolumePath =
                    isFuseThread ? null : checkDefaultDirMissing(resolvedVolumeName, res);
            // Ensure all parent folders of result file exist
            res.getParentFile().mkdirs();
            if (!res.getParentFile().exists()) {
                throw new IllegalStateException("Failed to create directory: " + res);
            }
            touchFusePath(defaultDirVolumePath);

            values.put(MediaColumns.DATA, res.getAbsolutePath());
            // buildFile may have changed the file name, compute values to extract new DISPLAY_NAME.
            // Note: We can't extract displayName from res.getPath() because for pending & trashed
            // files DISPLAY_NAME will not be same as file name.
            FileUtils.computeValuesFromData(values, isFuseThread);
        } else {
            assertFileColumnsSane(match, uri, values);
        }

        // Drop columns that aren't relevant for special tables
        switch (match) {
            case AUDIO_ALBUMART:
            case VIDEO_THUMBNAILS:
            case IMAGES_THUMBNAILS:
                final Set<String> valid = getProjectionMap(MediaStore.Images.Thumbnails.class)
                        .keySet();
                for (String key : new ArraySet<>(values.keySet())) {
                    if (!valid.contains(key)) {
                        values.remove(key);
                    }
                }
                break;
        }

        Trace.endSection();
    }

    /**
     * @return the default dir if {@code file} is a child of default dir and it's missing,
     * {@code null} otherwise.
     */
    private File checkDefaultDirMissing(String volumeName, File file) {
        String topLevelDir = FileUtils.extractTopLevelDir(file.getPath());
        if (topLevelDir != null && FileUtils.isDefaultDirectoryName(topLevelDir)) {
            try {
                File volumePath = getVolumePath(volumeName);
                if (!new File(volumePath, topLevelDir).exists()) {
                    return volumePath;
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to checkDefaultDirMissing for " + file, e);
            }
        }
        return null;
    }

    /** Updates mTime of {@code path} on the FUSE filesystem */
    private void touchFusePath(@Nullable File path) {
        if (path != null) {
            // Touch root of volume to update mTime on FUSE filesystem
            // This allows FileManagers that may be relying on mTime changes to update their UI
            File fusePath = getFuseFile(path);
            if (fusePath != null) {
                Log.i(TAG, "Touching FUSE path " + fusePath);
                fusePath.setLastModified(System.currentTimeMillis());
            }
        }
    }

    /**
     * Check that any requested {@link MediaColumns#DATA} paths actually
     * live on the storage volume being targeted.
     */
    private void assertFileColumnsSane(int match, Uri uri, ContentValues values)
            throws VolumeArgumentException, VolumeNotFoundException {
        if (!values.containsKey(MediaColumns.DATA)) return;

        final String volumeName = resolveVolumeName(uri);
        try {
            // Sanity check that the requested path actually lives on volume
            final Collection<File> allowed = getVolumeScanPaths(volumeName);
            final File actual = new File(values.getAsString(MediaColumns.DATA))
                    .getCanonicalFile();
            if (!FileUtils.contains(allowed, actual)) {
                throw new VolumeArgumentException(actual, allowed);
            }
        } catch (IOException e) {
            throw new VolumeNotFoundException(volumeName);
        }
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        if (match == VOLUMES) {
            return super.bulkInsert(uri, values);
        }

        if (match == AUDIO_PLAYLISTS_ID || match == AUDIO_PLAYLISTS_ID_MEMBERS) {
            final String resolvedVolumeName = resolveVolumeName(uri);

            final long playlistId = Long.parseLong(uri.getPathSegments().get(3));
            final Uri playlistUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Playlists.getContentUri(resolvedVolumeName), playlistId);

            final String audioVolumeName =
                    MediaStore.VOLUME_INTERNAL.equals(resolvedVolumeName)
                            ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL;

            // Require that caller has write access to underlying media
            enforceCallingPermission(playlistUri, Bundle.EMPTY, true);
            for (ContentValues each : values) {
                final long audioId = each.getAsLong(Audio.Playlists.Members.AUDIO_ID);
                final Uri audioUri = Audio.Media.getContentUri(audioVolumeName, audioId);
                enforceCallingPermission(audioUri, Bundle.EMPTY, false);
            }

            return bulkInsertPlaylist(playlistUri, values);
        }

        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(uri);
        } catch (VolumeNotFoundException e) {
            return e.translateForUpdateDelete(targetSdkVersion);
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

    private int bulkInsertPlaylist(@NonNull Uri uri, @NonNull ContentValues[] values) {
        Trace.beginSection("bulkInsertPlaylist");
        try {
            try {
                return addPlaylistMembers(uri, values);
            } catch (SQLiteConstraintException e) {
                if (getCallingPackageTargetSdkVersion() >= Build.VERSION_CODES.R) {
                    throw e;
                } else {
                    return 0;
                }
            }
        } catch (FallbackException e) {
            return e.translateForBulkInsert(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private long insertDirectory(@NonNull SQLiteDatabase db, @NonNull String path) {
        if (LOGV) Log.v(TAG, "inserting directory " + path);
        ContentValues values = new ContentValues();
        values.put(FileColumns.FORMAT, MtpConstants.FORMAT_ASSOCIATION);
        values.put(FileColumns.DATA, path);
        values.put(FileColumns.PARENT, getParent(db, path));
        values.put(FileColumns.OWNER_PACKAGE_NAME, extractPathOwnerPackageName(path));
        values.put(FileColumns.VOLUME_NAME, extractVolumeName(path));
        values.put(FileColumns.RELATIVE_PATH, extractRelativePath(path));
        values.put(FileColumns.DISPLAY_NAME, extractDisplayName(path));
        values.put(FileColumns.IS_DOWNLOAD, isDownload(path) ? 1 : 0);
        File file = new File(path);
        if (file.exists()) {
            values.put(FileColumns.DATE_MODIFIED, file.lastModified() / 1000);
        }
        return db.insert("files", FileColumns.DATE_MODIFIED, values);
    }

    private long getParent(@NonNull SQLiteDatabase db, @NonNull String path) {
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
                    id = insertDirectory(db, parentPath);
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
        mInternalDatabase.runWithTransaction((db) -> {
            localizeTitles(db);
            return null;
        });
    }

    private void localizeTitles(@NonNull SQLiteDatabase db) {
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

    private Uri insertFile(@NonNull SQLiteQueryBuilder qb, @NonNull DatabaseHelper helper,
            int match, @NonNull Uri uri, @NonNull Bundle extras, @NonNull ContentValues values,
            int mediaType) throws VolumeArgumentException, VolumeNotFoundException {
        boolean wasPathEmpty = !values.containsKey(MediaStore.MediaColumns.DATA)
                || TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DATA));

        // Make sure all file-related columns are defined
        ensureUniqueFileColumns(match, uri, extras, values, null);

        switch (mediaType) {
            case FileColumns.MEDIA_TYPE_AUDIO: {
                computeAudioLocalizedValues(values);
                computeAudioKeyValues(values);
                break;
            }
        }

        // compute bucket_id and bucket_display_name for all files
        String path = values.getAsString(MediaStore.MediaColumns.DATA);
        FileUtils.computeValuesFromData(values, isFuseThread());
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
            format = MimeUtils.resolveFormatCode(mimeType);
        }
        if (path != null && path.endsWith("/")) {
            // TODO: convert to using FallbackException once VERSION_CODES.S is defined
            Log.e(TAG, "directory has trailing slash: " + path);
            return null;
        }
        if (format != 0) {
            values.put(FileColumns.FORMAT, format);
        }

        if (mimeType == null && path != null && format != MtpConstants.FORMAT_ASSOCIATION) {
            mimeType = MimeUtils.resolveMimeType(new File(path));
        }

        if (mimeType != null) {
            values.put(FileColumns.MIME_TYPE, mimeType);
            if (isCallingPackageSelf() && values.containsKey(FileColumns.MEDIA_TYPE)) {
                // Leave FileColumns.MEDIA_TYPE untouched if the caller is ModernMediaScanner and
                // FileColumns.MEDIA_TYPE is already populated.
            } else if (isFuseThread() && path != null && shouldFileBeHidden(new File(path))) {
                // We should only mark MEDIA_TYPE as MEDIA_TYPE_NONE for Fuse Thread.
                // MediaProvider#insert() returns the uri by appending the "rowId" to the given
                // uri, hence to ensure the correct working of the returned uri, we shouldn't
                // change the MEDIA_TYPE in insert operation and let scan change it for us.
                values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_NONE);
            } else {
                values.put(FileColumns.MEDIA_TYPE, MimeUtils.resolveMediaType(mimeType));
            }
        } else {
            values.put(FileColumns.MEDIA_TYPE, mediaType);
        }

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

            rowId = insertAllowingUpsert(qb, helper, values, path);
        }
        if (format == MtpConstants.FORMAT_ASSOCIATION) {
            synchronized (mDirectoryCache) {
                mDirectoryCache.put(path, rowId);
            }
        }

        return ContentUris.withAppendedId(uri, rowId);
    }

    /**
     * Inserts a new row in MediaProvider database with {@code values}. Treats insert as upsert for
     * double inserts from same package.
     */
    private long insertAllowingUpsert(@NonNull SQLiteQueryBuilder qb,
            @NonNull DatabaseHelper helper, @NonNull ContentValues values, String path)
            throws SQLiteConstraintException {
        return helper.runWithTransaction((db) -> {
            Long parent = values.getAsLong(FileColumns.PARENT);
            if (parent == null) {
                if (path != null) {
                    final long parentId = getParent(db, path);
                    values.put(FileColumns.PARENT, parentId);
                }
            }

            try {
                return qb.insert(helper, values);
            } catch (SQLiteConstraintException e) {
                final String packages = getAllowedPackagesForUpsert(
                        values.getAsString(MediaColumns.OWNER_PACKAGE_NAME));
                SQLiteQueryBuilder qbForUpsert = getQueryBuilderForUpsert(path);
                final long rowId = getIdIfPathOwnedByPackages(qbForUpsert, helper, path, packages);
                // Apps sometimes create a file via direct path and then insert it into
                // MediaStore via ContentResolver. The former should create a database entry,
                // so we have to treat the latter as an upsert.
                // TODO(b/149917493) Perform all INSERT operations as UPSERT.
                if (rowId != -1 && qbForUpsert.update(helper, values, "_id=?",
                        new String[]{Long.toString(rowId)}) == 1) {
                    return rowId;
                }
                // Rethrow SQLiteConstraintException on failed upsert.
                throw e;
            }
        });
    }

    /**
     * @return row id of the entry with path {@code path} if the owner is one of {@code packages}.
     */
    private long getIdIfPathOwnedByPackages(@NonNull SQLiteQueryBuilder qb,
            @NonNull DatabaseHelper helper, String path, String packages) {
        final String[] projection = new String[] {FileColumns._ID};
        final  String ownerPackageMatchClause = DatabaseUtils.bindSelection(
                MediaColumns.OWNER_PACKAGE_NAME + " IN " + packages);
        final String selection = FileColumns.DATA + " =? AND " + ownerPackageMatchClause;

        try (Cursor c = qb.query(helper, projection, selection, new String[] {path}, null, null,
                null, null, null)) {
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        }
        return -1;
    }

    /**
     * Gets packages that should match to upsert a db row.
     *
     * A database row can be upserted if
     * <ul>
     * <li> Calling package or one of the shared packages owns the db row.
     * <li> {@code givenOwnerPackage} owns the db row. This is useful when DownloadProvider
     * requests upsert on behalf of another app
     * </ul>
     */
    private String getAllowedPackagesForUpsert(@Nullable String givenOwnerPackage) {
        ArrayList<String> packages = new ArrayList<>();
        packages.addAll(Arrays.asList(mCallingIdentity.get().getSharedPackageNames()));

        // If givenOwnerPackage is CallingIdentity, packages list would already have shared package
        // names of givenOwnerPackage. If givenOwnerPackage is not CallingIdentity, since
        // DownloadProvider can upsert a row on behalf of app, we should include all shared packages
        // of givenOwnerPackage.
        if (givenOwnerPackage != null && isCallingPackageDelegator() &&
                !isCallingIdentitySharedPackageName(givenOwnerPackage)) {
            // Allow DownloadProvider to Upsert if givenOwnerPackage is owner of the db row.
            packages.addAll(Arrays.asList(getSharedPackagesForPackage(givenOwnerPackage)));
        }
        return bindList((Object[]) packages.toArray());
    }

    /**
     * @return {@link SQLiteQueryBuilder} for upsert with Files uri. This disables strict columns
     * check to allow upsert to update any column with Files uri.
     */
    private SQLiteQueryBuilder getQueryBuilderForUpsert(@NonNull String path) {
        final boolean allowHidden = isCallingPackageAllowedHidden();
        Bundle extras = new Bundle();
        extras.putInt(QUERY_ARG_MATCH_PENDING, MATCH_INCLUDE);
        extras.putInt(QUERY_ARG_MATCH_TRASHED, MATCH_INCLUDE);

        // When Fuse inserts a file to database it doesn't set is_download column. When app tries
        // insert with Downloads uri, upsert fails because getIdIfPathExistsForCallingPackage can't
        // find a row ID with is_download=1. Use Files uri to get queryBuilder & update any existing
        // row irrespective of is_download=1.
        final Uri uri = FileUtils.getContentUriForPath(path);
        SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE, matchUri(uri, allowHidden), uri,
                extras, null);

        // We won't be able to update columns that are not part of projection map of Files table. We
        // have already checked strict columns in previous insert operation which failed with
        // exception. Any malicious column usage would have got caught in insert operation, hence we
        // can safely disable strict column check for upsert.
        qb.setStrictColumns(false);
        return qb;
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
            values.put(FileColumns.IS_DOWNLOAD, 1);
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

    /**
     * @deprecated all operations should be routed through the overload that
     *             accepts a {@link Bundle} of extras.
     */
    @Override
    @Deprecated
    public Uri insert(Uri uri, ContentValues values) {
        return insert(uri, values, null);
    }

    @Override
    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable Bundle extras) {
        Trace.beginSection("insert");
        try {
            try {
                return insertInternal(uri, values, extras);
            } catch (SQLiteConstraintException e) {
                if (getCallingPackageTargetSdkVersion() >= Build.VERSION_CODES.R) {
                    throw e;
                } else {
                    return null;
                }
            }
        } catch (FallbackException e) {
            return e.translateForInsert(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private @Nullable Uri insertInternal(@NonNull Uri uri, @Nullable ContentValues initialValues,
            @Nullable Bundle extras) throws FallbackException {
        extras = (extras != null) ? extras : new Bundle();

        // INCLUDED_DEFAULT_DIRECTORIES extra should only be set inside MediaProvider.
        extras.remove(INCLUDED_DEFAULT_DIRECTORIES);

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
            Uri attachedVolume = attachVolume(name, /* validate */ true);
            if (mMediaScannerVolume != null && mMediaScannerVolume.equals(name)) {
                final DatabaseHelper helper = getDatabaseForUri(
                        MediaStore.Files.getContentUri(mMediaScannerVolume));
                helper.mScanStartTime = SystemClock.elapsedRealtime();
            }
            return attachedVolume;
        }

        switch (match) {
            case AUDIO_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                final long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri playlistUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(resolvedVolumeName), playlistId);

                final long audioId = initialValues
                        .getAsLong(MediaStore.Audio.Playlists.Members.AUDIO_ID);
                final String audioVolumeName =
                        MediaStore.VOLUME_INTERNAL.equals(resolvedVolumeName)
                                ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL;
                final Uri audioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(audioVolumeName), audioId);

                // Require that caller has write access to underlying media
                enforceCallingPermission(playlistUri, Bundle.EMPTY, true);
                enforceCallingPermission(audioUri, Bundle.EMPTY, false);

                // Playlist contents are always persisted directly into playlist
                // files on disk to ensure that we can reliably migrate between
                // devices and recover from database corruption
                final long id = addPlaylistMembers(playlistUri, initialValues);
                final ContentResolver resolver = getContext().getContentResolver();
                resolver.notifyChange(playlistUri, null, ContentResolver.NOTIFY_INSERT);
                return ContentUris.withAppendedId(MediaStore.Audio.Playlists.Members
                        .getContentUri(originalVolumeName, playlistId), id);
            }
        }

        String path = null;
        String ownerPackageName = null;
        if (initialValues != null) {
            // IDs are forever; nobody should be editing them
            initialValues.remove(MediaColumns._ID);

            // Expiration times are hard-coded; let's derive them
            FileUtils.computeDateExpires(initialValues);

            // Ignore or augment incoming raw filesystem paths
            for (String column : sDataColumns.keySet()) {
                if (!initialValues.containsKey(column)) continue;

                if (isCallingPackageSelf() || isCallingPackageLegacyWrite()) {
                    // Mutation allowed
                } else if (isCallingPackageManager()) {
                    // Apps with MANAGE_EXTERNAL_STORAGE have all files access, hence they are
                    // allowed to insert files anywhere.
                } else {
                    Log.w(TAG, "Ignoring mutation of  " + column + " from "
                            + getCallingPackageOrSelf());
                    initialValues.remove(column);
                }
            }

            path = initialValues.getAsString(MediaStore.MediaColumns.DATA);

            if (!isCallingPackageSelf()) {
                initialValues.remove(FileColumns.IS_DOWNLOAD);
            }

            // We no longer track location metadata
            if (initialValues.containsKey(ImageColumns.LATITUDE)) {
                initialValues.putNull(ImageColumns.LATITUDE);
            }
            if (initialValues.containsKey(ImageColumns.LONGITUDE)) {
                initialValues.putNull(ImageColumns.LONGITUDE);
            }
            if (getCallingPackageTargetSdkVersion() <= Build.VERSION_CODES.Q) {
                // These columns are removed in R.
                if (initialValues.containsKey("primary_directory")) {
                    initialValues.remove("primary_directory");
                }
                if (initialValues.containsKey("secondary_directory")) {
                    initialValues.remove("secondary_directory");
                }
            }

            if (isCallingPackageSelf() || isCallingPackageShell()) {
                // When media inserted by ourselves during a scan, or by the
                // shell, the best we can do is guess ownership based on path
                // when it's not explicitly provided
                ownerPackageName = initialValues.getAsString(FileColumns.OWNER_PACKAGE_NAME);
                if (TextUtils.isEmpty(ownerPackageName)) {
                    ownerPackageName = extractPathOwnerPackageName(path);
                }
            } else if (isCallingPackageDelegator()) {
                // When caller is a delegator, we handle ownership as a hybrid
                // of the two other cases: we're willing to accept any ownership
                // transfer attempted during insert, but we fall back to using
                // the Binder identity if they don't request a specific owner
                ownerPackageName = initialValues.getAsString(FileColumns.OWNER_PACKAGE_NAME);
                if (TextUtils.isEmpty(ownerPackageName)) {
                    ownerPackageName = getCallingPackageOrSelf();
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
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_INSERT, match, uri, extras, null);

        switch (match) {
            case IMAGES_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                newUri = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_IMAGE);
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
                        MediaStore.Images.Media.getContentUri(resolvedVolumeName), imageId),
                        extras, true);

                ensureUniqueFileColumns(match, uri, extras, initialValues, null);

                rowId = qb.insert(helper, initialValues);
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
                        MediaStore.Video.Media.getContentUri(resolvedVolumeName), videoId),
                        Bundle.EMPTY, true);

                ensureUniqueFileColumns(match, uri, extras, initialValues, null);

                rowId = qb.insert(helper, initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(Video.Thumbnails.
                            getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case AUDIO_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                newUri = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_AUDIO);
                break;
            }

            case AUDIO_MEDIA_ID_GENRES: {
                throw new FallbackException("Genres are read-only", Build.VERSION_CODES.R);
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
                // Playlist names are stored as display names, but leave
                // values untouched if the caller is ModernMediaScanner
                if (!isCallingPackageSelf()) {
                    if (values.containsKey(Playlists.NAME)) {
                        values.put(MediaColumns.DISPLAY_NAME, values.getAsString(Playlists.NAME));
                    }
                    if (!values.containsKey(MediaColumns.MIME_TYPE)) {
                        values.put(MediaColumns.MIME_TYPE, "audio/mpegurl");
                    }
                }
                newUri = insertFile(qb, helper, match, uri, extras, values,
                        FileColumns.MEDIA_TYPE_PLAYLIST);
                if (newUri != null) {
                    // Touch empty playlist file on disk so its ready for renames
                    if (Binder.getCallingUid() != android.os.Process.myUid()) {
                        try (OutputStream out = ContentResolver.wrap(this)
                                .openOutputStream(newUri)) {
                        } catch (IOException ignored) {
                        }
                    }
                }
                break;
            }

            case VIDEO_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                newUri = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_VIDEO);
                break;
            }

            case AUDIO_ALBUMART: {
                if (helper.mInternal) {
                    throw new UnsupportedOperationException("no internal album art allowed");
                }

                ensureUniqueFileColumns(match, uri, extras, initialValues, null);

                rowId = qb.insert(helper, initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case FILES: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                final String mimeType = initialValues.getAsString(MediaColumns.MIME_TYPE);
                final int mediaType = MimeUtils.resolveMediaType(mimeType);
                newUri = insertFile(qb, helper, match, uri, extras, initialValues,
                        mediaType);
                break;
            }

            case DOWNLOADS:
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                initialValues.put(FileColumns.IS_DOWNLOAD, 1);
                newUri = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_NONE);
                break;

            default:
                throw new UnsupportedOperationException("Invalid URI " + uri);
        }

        // Remember that caller is owner of this item, to speed up future
        // permission checks for this caller
        mCallingIdentity.get().setOwned(rowId, true);

        if (path != null && path.toLowerCase(Locale.ROOT).endsWith("/.nomedia")) {
            mMediaScanner.scanFile(new File(path).getParentFile(), REASON_DEMAND);
        }

        return newUri;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
                throws OperationApplicationException {
        // Open transactions on databases for requested volumes
        final Set<DatabaseHelper> transactions = new ArraySet<>();
        try {
            for (ContentProviderOperation op : operations) {
                final DatabaseHelper helper = getDatabaseForUri(op.getUri());
                if (transactions.contains(helper)) continue;

                if (!helper.isTransactionActive()) {
                    helper.beginTransaction();
                    transactions.add(helper);
                } else {
                    // We normally don't allow nested transactions (since we
                    // don't have a good way to selectively roll them back) but
                    // if the incoming operation is ignoring exceptions, then we
                    // don't need to worry about partial rollback and can
                    // piggyback on the larger active transaction
                    if (!op.isExceptionAllowed()) {
                        throw new IllegalStateException("Nested transactions not supported");
                    }
                }
            }

            final ContentProviderResult[] result = super.applyBatch(operations);
            for (DatabaseHelper helper : transactions) {
                helper.setTransactionSuccessful();
            }
            return result;
        } catch (VolumeNotFoundException e) {
            throw e.rethrowAsIllegalArgumentException();
        } finally {
            for (DatabaseHelper helper : transactions) {
                helper.endTransaction();
            }
        }
    }

    private void appendWhereStandaloneMatch(@NonNull SQLiteQueryBuilder qb,
            @NonNull String column, /* @Match */ int match, Uri uri) {
        switch (match) {
            case MATCH_INCLUDE:
                // No special filtering needed
                break;
            case MATCH_EXCLUDE:
                appendWhereStandalone(qb, getWhereClauseForMatchExclude(column));
                break;
            case MATCH_ONLY:
                appendWhereStandalone(qb, column + "=?", 1);
                break;
            case MATCH_VISIBLE_FOR_FILEPATH:
                final String whereClause =
                        getWhereClauseForMatchableVisibleFromFilePath(uri, column);
                if (whereClause != null) {
                    appendWhereStandalone(qb, whereClause);
                }
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    private static void appendWhereStandalone(@NonNull SQLiteQueryBuilder qb,
            @Nullable String selection, @Nullable Object... selectionArgs) {
        qb.appendWhereStandalone(DatabaseUtils.bindSelection(selection, selectionArgs));
    }

    private static void appendWhereStandaloneFilter(@NonNull SQLiteQueryBuilder qb,
            @NonNull String[] columns, @Nullable String filter) {
        if (TextUtils.isEmpty(filter)) return;
        for (String filterWord : filter.split("\\s+")) {
            appendWhereStandalone(qb, String.join("||", columns) + " LIKE ? ESCAPE '\\'",
                    "%" + DatabaseUtils.escapeForLike(Audio.keyFor(filterWord)) + "%");
        }
    }

    @Deprecated
    private String getSharedPackages() {
        final String[] sharedPackageNames = mCallingIdentity.get().getSharedPackageNames();
        return bindList((Object[]) sharedPackageNames);
    }

    /**
     * Gets shared packages names for given {@code packageName}
     */
    private String[] getSharedPackagesForPackage(String packageName) {
        try {
            final int packageUid = getContext().getPackageManager()
                    .getPackageUid(packageName, 0);
            return getContext().getPackageManager().getPackagesForUid(packageUid);
        } catch (NameNotFoundException ignored) {
            return new String[] {packageName};
        }
    }

    private static final int TYPE_QUERY = 0;
    private static final int TYPE_INSERT = 1;
    private static final int TYPE_UPDATE = 2;
    private static final int TYPE_DELETE = 3;

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
    private @NonNull SQLiteQueryBuilder getQueryBuilder(int type, int match,
            @NonNull Uri uri, @NonNull Bundle extras, @Nullable Consumer<String> honored) {
        Trace.beginSection("getQueryBuilder");
        try {
            return getQueryBuilderInternal(type, match, uri, extras, honored);
        } finally {
            Trace.endSection();
        }
    }

    private @NonNull SQLiteQueryBuilder getQueryBuilderInternal(int type, int match,
            @NonNull Uri uri, @NonNull Bundle extras, @Nullable Consumer<String> honored) {
        final boolean forWrite;
        switch (type) {
            case TYPE_QUERY: forWrite = false; break;
            case TYPE_INSERT: forWrite = true; break;
            case TYPE_UPDATE: forWrite = true; break;
            case TYPE_DELETE: forWrite = true; break;
            default: throw new IllegalStateException();
        }

        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        if (uri.getBooleanQueryParameter("distinct", false)) {
            qb.setDistinct(true);
        }
        qb.setStrict(true);
        if (isCallingPackageSelf()) {
            // When caller is system, such as the media scanner, we're willing
            // to let them access any columns they want
        } else {
            qb.setTargetSdkVersion(getCallingPackageTargetSdkVersion());
            qb.setStrictColumns(true);
            qb.setStrictGrammar(true);
        }

        // TODO: throw when requesting a currently unmounted volume
        final String volumeName = MediaStore.getVolumeName(uri);
        final String includeVolumes;
        if (MediaStore.VOLUME_EXTERNAL.equals(volumeName)) {
            includeVolumes = bindList(getExternalVolumeNames().toArray());
        } else {
            includeVolumes = bindList(volumeName);
        }
        final String sharedPackages = getSharedPackages();
        final String matchSharedPackagesClause = FileColumns.OWNER_PACKAGE_NAME + " IN "
                + sharedPackages;

        final boolean allowGlobal = checkCallingPermissionGlobal(uri, forWrite);
        final boolean allowLegacy =
                forWrite ? isCallingPackageLegacyWrite() : isCallingPackageLegacyRead();
        final boolean allowLegacyRead = allowLegacy && !forWrite;

        int matchPending = extras.getInt(QUERY_ARG_MATCH_PENDING, MATCH_DEFAULT);
        int matchTrashed = extras.getInt(QUERY_ARG_MATCH_TRASHED, MATCH_DEFAULT);
        int matchFavorite = extras.getInt(QUERY_ARG_MATCH_FAVORITE, MATCH_DEFAULT);

        final ArrayList<String> includedDefaultDirs = extras.getStringArrayList(
                INCLUDED_DEFAULT_DIRECTORIES);

        // Handle callers using legacy arguments
        if (MediaStore.getIncludePending(uri)) matchPending = MATCH_INCLUDE;

        // Resolve any remaining default options
        final int defaultMatchForPendingAndTrashed;
        if (isFuseThread()) {
            // Write operations always check for file ownership, we don't need additional write
            // permission check for is_pending and is_trashed.
            defaultMatchForPendingAndTrashed =
                    forWrite ? MATCH_INCLUDE : MATCH_VISIBLE_FOR_FILEPATH;
        } else {
            defaultMatchForPendingAndTrashed = MATCH_EXCLUDE;
        }
        if (matchPending == MATCH_DEFAULT) matchPending = defaultMatchForPendingAndTrashed;
        if (matchTrashed == MATCH_DEFAULT) matchTrashed = defaultMatchForPendingAndTrashed;
        if (matchFavorite == MATCH_DEFAULT) matchFavorite = MATCH_INCLUDE;

        // Handle callers using legacy filtering
        final String filter = uri.getQueryParameter("filter");

        // Only accept ALL_VOLUMES parameter up until R, because we're not convinced we want
        // to commit to this as an API.
        final boolean includeAllVolumes = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) ?
                "1".equals(uri.getQueryParameter(ALL_VOLUMES)) : false;
        final String callingPackage = getCallingPackageOrSelf();

        switch (match) {
            case IMAGES_MEDIA_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                matchPending = MATCH_INCLUDE;
                matchTrashed = MATCH_INCLUDE;
                // fall-through
            case IMAGES_MEDIA: {
                if (type == TYPE_QUERY) {
                    qb.setTables("images");
                    qb.setProjectionMap(
                            getProjectionMap(Images.Media.class));
                } else {
                    qb.setTables("files");
                    qb.setProjectionMap(
                            getProjectionMap(Images.Media.class, Files.FileColumns.class));
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_IMAGE);
                }
                if (!allowGlobal && !checkCallingPermissionImages(forWrite, callingPackage)) {
                    appendWhereStandalone(qb, matchSharedPackagesClause);
                }
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite, uri);
                if (honored != null) {
                    honored.accept(QUERY_ARG_MATCH_PENDING);
                    honored.accept(QUERY_ARG_MATCH_TRASHED);
                    honored.accept(QUERY_ARG_MATCH_FAVORITE);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
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
                            "image_id IN (SELECT _id FROM images WHERE "
                                    + matchSharedPackagesClause + ")");
                }
                break;
            }
            case AUDIO_MEDIA_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                matchPending = MATCH_INCLUDE;
                matchTrashed = MATCH_INCLUDE;
                // fall-through
            case AUDIO_MEDIA: {
                if (type == TYPE_QUERY) {
                    qb.setTables("audio");
                    qb.setProjectionMap(
                            getProjectionMap(Audio.Media.class));
                } else {
                    qb.setTables("files");
                    qb.setProjectionMap(
                            getProjectionMap(Audio.Media.class, Files.FileColumns.class));
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_AUDIO);
                }
                if (!allowGlobal && !checkCallingPermissionAudio(forWrite, callingPackage)) {
                    // Apps without Audio permission can only see their own
                    // media, but we also let them see ringtone-style media to
                    // support legacy use-cases.
                    appendWhereStandalone(qb,
                            DatabaseUtils.bindSelection(matchSharedPackagesClause
                                    + " OR is_ringtone=1 OR is_alarm=1 OR is_notification=1"));
                }
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY, AudioColumns.ALBUM_KEY, AudioColumns.TITLE_KEY
                }, filter);
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite, uri);
                if (honored != null) {
                    honored.accept(QUERY_ARG_MATCH_PENDING);
                    honored.accept(QUERY_ARG_MATCH_TRASHED);
                    honored.accept(QUERY_ARG_MATCH_FAVORITE);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
            case AUDIO_MEDIA_ID_GENRES_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_MEDIA_ID_GENRES: {
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
            }
            case AUDIO_GENRES_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_GENRES: {
                qb.setTables("audio_genres");
                qb.setProjectionMap(getProjectionMap(Audio.Genres.class));
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }
            case AUDIO_GENRES_ID_MEMBERS:
                appendWhereStandalone(qb, "genre_id=?", uri.getPathSegments().get(3));
                // fall-through
            case AUDIO_GENRES_ALL_MEMBERS: {
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
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY, AudioColumns.ALBUM_KEY, AudioColumns.TITLE_KEY
                }, filter);
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }
            case AUDIO_PLAYLISTS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                matchPending = MATCH_INCLUDE;
                matchTrashed = MATCH_INCLUDE;
                // fall-through
            case AUDIO_PLAYLISTS: {
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_playlists");
                    qb.setProjectionMap(
                            getProjectionMap(Audio.Playlists.class));
                } else {
                    qb.setTables("files");
                    qb.setProjectionMap(
                            getProjectionMap(Audio.Playlists.class, Files.FileColumns.class));
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_PLAYLIST);
                }
                if (!allowGlobal && !checkCallingPermissionAudio(forWrite, callingPackage)) {
                    appendWhereStandalone(qb, matchSharedPackagesClause);
                }
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite, uri);
                if (honored != null) {
                    honored.accept(QUERY_ARG_MATCH_PENDING);
                    honored.accept(QUERY_ARG_MATCH_TRASHED);
                    honored.accept(QUERY_ARG_MATCH_FAVORITE);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
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
                    qb.setProjectionMap(getProjectionMap(Audio.Playlists.Members.class));
                }
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY, AudioColumns.ALBUM_KEY, AudioColumns.TITLE_KEY
                }, filter);
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
                    qb.setProjectionMap(getProjectionMap(Audio.Albums.class));

                    final String artistId = uri.getPathSegments().get(3);
                    appendWhereStandalone(qb, "artist_id=?", artistId);
                } else {
                    throw new UnsupportedOperationException("Albums cannot be directly modified");
                }
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ALBUM_KEY
                }, filter);
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
            case AUDIO_ARTISTS: {
                if (type == TYPE_QUERY) {
                    qb.setTables("audio_artists");
                    qb.setProjectionMap(getProjectionMap(Audio.Artists.class));
                } else {
                    throw new UnsupportedOperationException("Artists cannot be directly modified");
                }
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY
                }, filter);
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }
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
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY, AudioColumns.ALBUM_KEY
                }, filter);
                if (!allowGlobal && !checkCallingPermissionAudio(false, callingPackage)) {
                    // We don't have a great way to filter parsed metadata by
                    // owner, so callers need to hold READ_MEDIA_AUDIO
                    appendWhereStandalone(qb, "0");
                }
                break;
            }
            case VIDEO_MEDIA_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                matchPending = MATCH_INCLUDE;
                matchTrashed = MATCH_INCLUDE;
                // fall-through
            case VIDEO_MEDIA: {
                if (type == TYPE_QUERY) {
                    qb.setTables("video");
                    qb.setProjectionMap(
                            getProjectionMap(Video.Media.class));
                } else {
                    qb.setTables("files");
                    qb.setProjectionMap(
                            getProjectionMap(Video.Media.class, Files.FileColumns.class));
                    appendWhereStandalone(qb, FileColumns.MEDIA_TYPE + "=?",
                            FileColumns.MEDIA_TYPE_VIDEO);
                }
                if (!allowGlobal && !checkCallingPermissionVideo(forWrite, callingPackage)) {
                    appendWhereStandalone(qb, matchSharedPackagesClause);
                }
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite, uri);
                if (honored != null) {
                    honored.accept(QUERY_ARG_MATCH_PENDING);
                    honored.accept(QUERY_ARG_MATCH_TRASHED);
                    honored.accept(QUERY_ARG_MATCH_FAVORITE);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
            case VIDEO_THUMBNAILS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(3));
                // fall-through
            case VIDEO_THUMBNAILS: {
                qb.setTables("videothumbnails");
                qb.setProjectionMap(getProjectionMap(Video.Thumbnails.class));
                if (!allowGlobal && !checkCallingPermissionVideo(forWrite, callingPackage)) {
                    appendWhereStandalone(qb,
                            "video_id IN (SELECT _id FROM video WHERE " +
                                    matchSharedPackagesClause + ")");
                }
                break;
            }
            case FILES_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(2));
                matchPending = MATCH_INCLUDE;
                matchTrashed = MATCH_INCLUDE;
                // fall-through
            case FILES: {
                qb.setTables("files");
                qb.setProjectionMap(getProjectionMap(Files.FileColumns.class));

                final ArrayList<String> options = new ArrayList<>();
                if (!allowGlobal && !allowLegacyRead) {
                    options.add(DatabaseUtils.bindSelection(matchSharedPackagesClause));
                    if (allowLegacy) {
                        options.add(DatabaseUtils.bindSelection("volume_name=?",
                                MediaStore.VOLUME_EXTERNAL_PRIMARY));
                    }
                    if (checkCallingPermissionAudio(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_AUDIO));
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_PLAYLIST));
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_SUBTITLE));
                        options.add(matchSharedPackagesClause
                                + " AND media_type=0 AND mime_type LIKE 'audio/%'");
                    }
                    if (checkCallingPermissionVideo(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_VIDEO));
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_SUBTITLE));
                        options.add(matchSharedPackagesClause
                                + " AND media_type=0 AND mime_type LIKE 'video/%'");
                    }
                    if (checkCallingPermissionImages(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_IMAGE));
                        options.add(matchSharedPackagesClause
                                + " AND media_type=0 AND mime_type LIKE 'image/%'");
                    }
                    if (includedDefaultDirs != null) {
                        for (String defaultDir : includedDefaultDirs) {
                            options.add(FileColumns.RELATIVE_PATH + " LIKE '" + defaultDir + "/%'");
                        }
                    }
                }
                if (options.size() > 0) {
                    appendWhereStandalone(qb, TextUtils.join(" OR ", options));
                }

                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY, AudioColumns.ALBUM_KEY, AudioColumns.TITLE_KEY
                }, filter);
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite, uri);
                if (honored != null) {
                    honored.accept(QUERY_ARG_MATCH_PENDING);
                    honored.accept(QUERY_ARG_MATCH_TRASHED);
                    honored.accept(QUERY_ARG_MATCH_FAVORITE);
                }
                if (!includeAllVolumes) {
                    appendWhereStandalone(qb, FileColumns.VOLUME_NAME + " IN " + includeVolumes);
                }
                break;
            }
            case DOWNLOADS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(2));
                matchPending = MATCH_INCLUDE;
                matchTrashed = MATCH_INCLUDE;
                // fall-through
            case DOWNLOADS: {
                if (type == TYPE_QUERY) {
                    qb.setTables("downloads");
                    qb.setProjectionMap(
                            getProjectionMap(Downloads.class));
                } else {
                    qb.setTables("files");
                    qb.setProjectionMap(
                            getProjectionMap(Downloads.class, Files.FileColumns.class));
                    appendWhereStandalone(qb, FileColumns.IS_DOWNLOAD + "=1");
                }

                final ArrayList<String> options = new ArrayList<>();
                if (!allowGlobal && !allowLegacyRead) {
                    options.add(DatabaseUtils.bindSelection(matchSharedPackagesClause));
                    if (allowLegacy) {
                        options.add(DatabaseUtils.bindSelection("volume_name=?",
                                MediaStore.VOLUME_EXTERNAL_PRIMARY));
                    }
                }
                if (options.size() > 0) {
                    appendWhereStandalone(qb, TextUtils.join(" OR ", options));
                }

                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed, uri);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite, uri);
                if (honored != null) {
                    honored.accept(QUERY_ARG_MATCH_PENDING);
                    honored.accept(QUERY_ARG_MATCH_TRASHED);
                    honored.accept(QUERY_ARG_MATCH_FAVORITE);
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

        // To ensure we're enforcing our security model, all operations must
        // have a projection map configured
        if (qb.getProjectionMap() == null) {
            throw new IllegalStateException("All queries must have a projection map");
        }

        // If caller is an older app, we're willing to let through a
        // greylist of technically invalid columns
        if (getCallingPackageTargetSdkVersion() < Build.VERSION_CODES.Q) {
            qb.setProjectionGreylist(sGreylist);
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

    /**
     * @deprecated all operations should be routed through the overload that
     *             accepts a {@link Bundle} of extras.
     */
    @Override
    @Deprecated
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return delete(uri,
                DatabaseUtils.createSqlQueryBundle(selection, selectionArgs, null));
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable Bundle extras) {
        Trace.beginSection("delete");
        try {
            return deleteInternal(uri, extras);
        } catch (FallbackException e) {
            return e.translateForUpdateDelete(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private int deleteInternal(@NonNull Uri uri, @Nullable Bundle extras)
            throws FallbackException {
        extras = (extras != null) ? extras : new Bundle();

        // INCLUDED_DEFAULT_DIRECTORIES extra should only be set inside MediaProvider.
        extras.remove(INCLUDED_DEFAULT_DIRECTORIES);

        uri = safeUncanonicalize(uri);
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        switch(match) {
            case AUDIO_MEDIA_ID:
            case AUDIO_PLAYLISTS_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
            case DOWNLOADS_ID:
            case FILES_ID: {
                if (!isFuseThread() && getCachedCallingIdentityForFuse(Binder.getCallingUid()).
                        removeDeletedRowId(Long.parseLong(uri.getLastPathSegment()))) {
                    // Apps sometimes delete the file via filePath and then try to delete the db row
                    // using MediaProvider#delete. Since we would have already deleted the db row
                    // during the filePath operation, the latter will result in a security
                    // exception. Apps which don't expect an exception will break here. Since we
                    // have already deleted the db row, silently return zero as deleted count.
                    return 0;
                }
            }
            break;
            default:
                // For other match types, given uri will not correspond to a valid file.
                break;
        }

        final String userWhere = extras.getString(QUERY_ARG_SQL_SELECTION);
        final String[] userWhereArgs = extras.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);

        int count = 0;

        final String volumeName = getVolumeName(uri);
        final int targetSdkVersion = getCallingPackageTargetSdkVersion();

        // handle MEDIA_SCANNER before calling getDatabaseForUri()
        if (match == MEDIA_SCANNER) {
            if (mMediaScannerVolume == null) {
                return 0;
            }

            final DatabaseHelper helper = getDatabaseForUri(
                    MediaStore.Files.getContentUri(mMediaScannerVolume));

            helper.mScanStopTime = SystemClock.elapsedRealtime();

            mMediaScannerVolume = null;
            return 1;
        }

        if (match == VOLUMES_ID) {
            detachVolume(uri);
            count = 1;
        }

        switch (match) {
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                extras.putString(QUERY_ARG_SQL_SELECTION,
                        BaseColumns._ID + "=" + uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                final long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri playlistUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(volumeName), playlistId);

                // Playlist contents are always persisted directly into playlist
                // files on disk to ensure that we can reliably migrate between
                // devices and recover from database corruption
                int numOfRemovedPlaylistMembers = removePlaylistMembers(playlistUri, extras);
                if (numOfRemovedPlaylistMembers > 0) {
                    final ContentResolver resolver = getContext().getContentResolver();
                    resolver.notifyChange(playlistUri, null, ContentResolver.NOTIFY_DELETE);
                }
                return numOfRemovedPlaylistMembers;
            }
        }

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_DELETE, match, uri, extras, null);

        {
            // Give callers interacting with a specific media item a chance to
            // escalate access if they don't already have it
            switch (match) {
                case AUDIO_MEDIA_ID:
                case VIDEO_MEDIA_ID:
                case IMAGES_MEDIA_ID:
                    enforceCallingPermission(uri, extras, true);
            }

            final String[] projection = new String[] {
                    FileColumns.MEDIA_TYPE,
                    FileColumns.DATA,
                    FileColumns._ID,
                    FileColumns.IS_DOWNLOAD,
                    FileColumns.MIME_TYPE,
            };
            final boolean isFilesTable = qb.getTables().equals("files");
            final LongSparseArray<String> deletedDownloadIds = new LongSparseArray<>();
            if (isFilesTable) {
                String deleteparam = uri.getQueryParameter(MediaStore.PARAM_DELETE_DATA);
                if (deleteparam == null || ! deleteparam.equals("false")) {
                    Cursor c = qb.query(helper, projection, userWhere, userWhereArgs,
                            null, null, null, null, null);
                    try {
                        while (c.moveToNext()) {
                            final int mediaType = c.getInt(0);
                            final String data = c.getString(1);
                            final long id = c.getLong(2);
                            final int isDownload = c.getInt(3);
                            final String mimeType = c.getString(4);

                            // Forget that caller is owner of this item
                            mCallingIdentity.get().setOwned(id, false);

                            deleteIfAllowed(uri, extras, data);
                            count += qb.delete(helper, BaseColumns._ID + "=" + id, null);

                            // Only need to inform DownloadProvider about the downloads deleted on
                            // external volume.
                            if (isDownload == 1) {
                                deletedDownloadIds.put(id, mimeType);
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
                case AUDIO_GENRES_ID_MEMBERS:
                    throw new FallbackException("Genres are read-only", Build.VERSION_CODES.R);

                case IMAGES_THUMBNAILS_ID:
                case IMAGES_THUMBNAILS:
                case VIDEO_THUMBNAILS_ID:
                case VIDEO_THUMBNAILS:
                    // Delete the referenced files first.
                    Cursor c = qb.query(helper, sDataOnlyColumn, userWhere, userWhereArgs, null,
                            null, null, null, null);
                    if (c != null) {
                        try {
                            while (c.moveToNext()) {
                                deleteIfAllowed(uri, extras, c.getString(0));
                            }
                        } finally {
                            FileUtils.closeQuietly(c);
                        }
                    }
                    count += deleteRecursive(qb, helper, userWhere, userWhereArgs);
                    break;

                default:
                    count += deleteRecursive(qb, helper, userWhere, userWhereArgs);
                    break;
            }

            if (deletedDownloadIds.size() > 0) {
                // Do this on a background thread, since we don't want to make binder
                // calls as part of a FUSE call.
                helper.postBackground(() -> {
                    getContext().getSystemService(DownloadManager.class)
                            .onMediaStoreDownloadsDeleted(deletedDownloadIds);
                });
            }

            if (isFilesTable && !isCallingPackageSelf()) {
                Metrics.logDeletion(volumeName, mCallingIdentity.get().uid,
                        getCallingPackageOrSelf(), count);
            }
        }

        return count;
    }

    /**
     * Executes identical delete repeatedly within a single transaction until
     * stability is reached. Combined with {@link #ID_NOT_PARENT_CLAUSE}, this
     * can be used to recursively delete all matching entries, since it only
     * deletes parents when no references remaining.
     */
    private int deleteRecursive(SQLiteQueryBuilder qb, DatabaseHelper helper, String userWhere,
            String[] userWhereArgs) {
        return (int) helper.runWithTransaction((db) -> {
            synchronized (mDirectoryCache) {
                mDirectoryCache.clear();
            }

            int n = 0;
            int total = 0;
            do {
                n = qb.delete(helper, userWhere, userWhereArgs);
                total += n;
            } while (n > 0);
            return total;
        });
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Trace.beginSection("call");
        try {
            return callInternal(method, arg, extras);
        } finally {
            Trace.endSection();
        }
    }

    private Bundle callInternal(String method, String arg, Bundle extras) {
        switch (method) {
            case MediaStore.RESOLVE_PLAYLIST_MEMBERS_CALL: {
                final LocalCallingIdentity token = clearLocalCallingIdentity();
                final CallingIdentity providerToken = clearCallingIdentity();
                try {
                    final Uri playlistUri = extras.getParcelable(MediaStore.EXTRA_URI);
                    resolvePlaylistMembers(playlistUri);
                } finally {
                    restoreCallingIdentity(providerToken);
                    restoreLocalCallingIdentity(token);
                }
                return null;
            }
            case MediaStore.RUN_IDLE_MAINTENANCE_CALL: {
                // Protect ourselves from random apps by requiring a generic
                // permission held by common debugging components, such as shell
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DUMP, TAG);
                final LocalCallingIdentity token = clearLocalCallingIdentity();
                final CallingIdentity providerToken = clearCallingIdentity();
                try {
                    onIdleMaintenance(new CancellationSignal());
                } finally {
                    restoreCallingIdentity(providerToken);
                    restoreLocalCallingIdentity(token);
                }
                return null;
            }
            case MediaStore.WAIT_FOR_IDLE_CALL: {
                ForegroundThread.waitForIdle();
                BackgroundThread.waitForIdle();
                return null;
            }
            case MediaStore.SCAN_FILE_CALL:
            case MediaStore.SCAN_VOLUME_CALL: {
                final LocalCallingIdentity token = clearLocalCallingIdentity();
                final CallingIdentity providerToken = clearCallingIdentity();
                try {
                    final Bundle res = new Bundle();
                    switch (method) {
                        case MediaStore.SCAN_FILE_CALL: {
                            final File file = new File(arg);
                            res.putParcelable(Intent.EXTRA_STREAM, scanFile(file, REASON_DEMAND));
                            break;
                        }
                        case MediaStore.SCAN_VOLUME_CALL: {
                            final String volumeName = arg;
                            MediaService.onScanVolume(getContext(), volumeName, REASON_DEMAND);
                            break;
                        }
                    }
                    return res;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    restoreCallingIdentity(providerToken);
                    restoreLocalCallingIdentity(token);
                }
            }
            case MediaStore.GET_VERSION_CALL: {
                final String volumeName = extras.getString(Intent.EXTRA_TEXT);

                final DatabaseHelper helper;
                try {
                    helper = getDatabaseForUri(MediaStore.Files.getContentUri(volumeName));
                } catch (VolumeNotFoundException e) {
                    throw e.rethrowAsIllegalArgumentException();
                }

                final String version = helper.runWithoutTransaction((db) -> {
                    return db.getVersion() + ":" + DatabaseHelper.getOrCreateUuid(db);
                });

                final Bundle res = new Bundle();
                res.putString(Intent.EXTRA_TEXT, version);
                return res;
            }
            case MediaStore.GET_GENERATION_CALL: {
                final String volumeName = extras.getString(Intent.EXTRA_TEXT);

                final DatabaseHelper helper;
                try {
                    helper = getDatabaseForUri(MediaStore.Files.getContentUri(volumeName));
                } catch (VolumeNotFoundException e) {
                    throw e.rethrowAsIllegalArgumentException();
                }

                final long generation = helper.runWithoutTransaction((db) -> {
                    return DatabaseHelper.getGeneration(db);
                });

                final Bundle res = new Bundle();
                res.putLong(Intent.EXTRA_INDEX, generation);
                return res;
            }
            case MediaStore.GET_DOCUMENT_URI_CALL: {
                final Uri mediaUri = extras.getParcelable(MediaStore.EXTRA_URI);
                enforceCallingPermission(mediaUri, extras, false);

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
                                MediaStore.EXTERNAL_STORAGE_PROVIDER_AUTHORITY)) {
                    extras.putParcelable(MediaStore.EXTRA_URI, fileUri);
                    return client.call(method, null, extras);
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
            }
            case MediaStore.GET_MEDIA_URI_CALL: {
                final Uri documentUri = extras.getParcelable(MediaStore.EXTRA_URI);
                getContext().enforceCallingUriPermission(documentUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION, TAG);

                final Uri fileUri;
                try (ContentProviderClient client = getContext().getContentResolver()
                        .acquireUnstableContentProviderClient(
                                MediaStore.EXTERNAL_STORAGE_PROVIDER_AUTHORITY)) {
                    final Bundle res = client.call(method, null, extras);
                    fileUri = res.getParcelable(MediaStore.EXTRA_URI);
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }

                final LocalCallingIdentity token = clearLocalCallingIdentity();
                try {
                    final Bundle res = new Bundle();
                    res.putParcelable(MediaStore.EXTRA_URI,
                            queryForMediaUri(new File(fileUri.getPath()), null));
                    return res;
                } catch (FileNotFoundException e) {
                    throw new IllegalArgumentException(e);
                } finally {
                    restoreLocalCallingIdentity(token);
                }
            }
            case MediaStore.CREATE_WRITE_REQUEST_CALL:
            case MediaStore.CREATE_FAVORITE_REQUEST_CALL:
            case MediaStore.CREATE_TRASH_REQUEST_CALL:
            case MediaStore.CREATE_DELETE_REQUEST_CALL: {
                final PendingIntent pi = createRequest(method, extras);
                final Bundle res = new Bundle();
                res.putParcelable(MediaStore.EXTRA_RESULT, pi);
                return res;
            }
            default:
                throw new UnsupportedOperationException("Unsupported call: " + method);
        }
    }

    static List<Uri> collectUris(ClipData clipData) {
        final ArrayList<Uri> res = new ArrayList<>();
        for (int i = 0; i < clipData.getItemCount(); i++) {
            res.add(clipData.getItemAt(i).getUri());
        }
        return res;
    }

    /**
     * Generate the {@link PendingIntent} for the given grant request. This
     * method also sanity checks the incoming arguments for security purposes
     * before creating the privileged {@link PendingIntent}.
     */
    private @NonNull PendingIntent createRequest(@NonNull String method, @NonNull Bundle extras) {
        final ClipData clipData = extras.getParcelable(MediaStore.EXTRA_CLIP_DATA);
        final List<Uri> uris = collectUris(clipData);

        for (Uri uri : uris) {
            final int match = matchUri(uri, false);
            switch (match) {
                case IMAGES_MEDIA_ID:
                case AUDIO_MEDIA_ID:
                case VIDEO_MEDIA_ID:
                case AUDIO_PLAYLISTS_ID:
                    // Caller is requesting a specific media item by its ID,
                    // which means it's valid for requests
                    break;
                case FILES_ID:
                    // Allow only subtitle files
                    if (!isSubtitleFile(uri)) {
                        throw new IllegalArgumentException(
                                "All requested items must be Media items");
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "All requested items must be referenced by specific ID");
            }
        }

        // Enforce that limited set of columns can be mutated
        final ContentValues values = extras.getParcelable(MediaStore.EXTRA_CONTENT_VALUES);
        final List<String> allowedColumns;
        switch (method) {
            case MediaStore.CREATE_FAVORITE_REQUEST_CALL:
                allowedColumns = Arrays.asList(
                        MediaColumns.IS_FAVORITE);
                break;
            case MediaStore.CREATE_TRASH_REQUEST_CALL:
                allowedColumns = Arrays.asList(
                        MediaColumns.IS_TRASHED);
                break;
            default:
                allowedColumns = Arrays.asList();
                break;
        }
        if (values != null) {
            for (String key : values.keySet()) {
                if (!allowedColumns.contains(key)) {
                    throw new IllegalArgumentException("Invalid column " + key);
                }
            }
        }

        final Context context = getContext();
        final Intent intent = new Intent(method, null, context, PermissionActivity.class);
        intent.putExtras(extras);
        return PendingIntent.getActivity(context, PermissionActivity.REQUEST_CODE, intent,
                FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
    }

    /**
     * @return true if the given Files uri has media_type=MEDIA_TYPE_SUBTITLE
     */
    private boolean isSubtitleFile(Uri uri) {
        try (Cursor cursor = queryForSingleItem(uri, new String[]{FileColumns.MEDIA_TYPE}, null,
                null, null)) {
            return cursor.getInt(0) == FileColumns.MEDIA_TYPE_SUBTITLE;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Couldn't find database row for requested uri " + uri, e);
        }
        return false;
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
                    helper.runWithoutTransaction((db) -> {
                        db.execPerConnectionSQL("SELECT icu_load_collation(?, ?);",
                                new String[] { locale, collationName });
                        return null;
                    });
                }
                mCustomCollators.add(collationName);
            }
        }
        return collationName;
    }

    private int pruneThumbnails(@NonNull SQLiteDatabase db, @NonNull CancellationSignal signal) {
        int prunedCount = 0;

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
            final List<File> thumbDirs;
            try {
                thumbDirs = getThumbnailDirectories(volumeName);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to resolve volume " + volumeName, e);
                continue;
            }

            // Reconcile all thumbnails, deleting stale items
            for (File thumbDir : thumbDirs) {
                // Possibly bail before digging into each directory
                signal.throwIfCanceled();

                final File[] files = thumbDir.listFiles();
                for (File thumbFile : (files != null) ? files : new File[0]) {
                    if (Objects.equals(thumbFile.getName(), FILE_DATABASE_UUID)) continue;
                    final String name = FileUtils.extractFileName(thumbFile.getName());
                    try {
                        final long id = Long.parseLong(name);
                        if (Arrays.binarySearch(knownIdsRaw, id) >= 0) {
                            // Thumbnail belongs to known media, keep it
                            continue;
                        }
                    } catch (NumberFormatException e) {
                    }

                    Log.v(TAG, "Deleting stale thumbnail " + thumbFile);
                    deleteAndInvalidate(thumbFile);
                    prunedCount++;
                }
            }
        }

        // Also delete stale items from legacy tables
        db.execSQL("delete from thumbnails "
                + "where image_id not in (select _id from images)");
        db.execSQL("delete from videothumbnails "
                + "where video_id not in (select _id from video)");

        return prunedCount;
    }

    abstract class Thumbnailer {
        final String directoryName;

        public Thumbnailer(String directoryName) {
            this.directoryName = directoryName;
        }

        private File getThumbnailFile(Uri uri) throws IOException {
            final String volumeName = resolveVolumeName(uri);
            final File volumePath = getVolumePath(volumeName);
            return FileUtils.buildPath(volumePath, directoryName,
                    DIRECTORY_THUMBNAILS, ContentUris.parseId(uri) + ".jpg");
        }

        public abstract Bitmap getThumbnailBitmap(Uri uri, CancellationSignal signal)
                throws IOException;

        public ParcelFileDescriptor ensureThumbnail(Uri uri, CancellationSignal signal)
                throws IOException {
            // First attempt to fast-path by opening the thumbnail; if it
            // doesn't exist we fall through to create it below
            final File thumbFile = getThumbnailFile(uri);
            try {
                return FileUtils.openSafely(thumbFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (FileNotFoundException ignored) {
            }

            final File thumbDir = thumbFile.getParentFile();
            thumbDir.mkdirs();

            // When multiple threads race for the same thumbnail, the second
            // thread could return a file with a thumbnail still in
            // progress. We could add heavy per-ID locking to mitigate this
            // rare race condition, but it's simpler to have both threads
            // generate the same thumbnail using temporary files and rename
            // them into place once finished.
            final File thumbTempFile = File.createTempFile("thumb", null, thumbDir);

            ParcelFileDescriptor thumbWrite = null;
            ParcelFileDescriptor thumbRead = null;
            try {
                // Open our temporary file twice: once for local writing, and
                // once for remote reading. Both FDs point at the same
                // underlying inode on disk, so they're stable across renames
                // to avoid race conditions between threads.
                thumbWrite = FileUtils.openSafely(thumbTempFile,
                        ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE);
                thumbRead = FileUtils.openSafely(thumbTempFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);

                final Bitmap thumbnail = getThumbnailBitmap(uri, signal);
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 90,
                        new FileOutputStream(thumbWrite.getFileDescriptor()));

                try {
                    // Use direct syscall for better failure logs
                    Os.rename(thumbTempFile.getAbsolutePath(), thumbFile.getAbsolutePath());
                } catch (ErrnoException e) {
                    e.rethrowAsIOException();
                }

                // Everything above went peachy, so return a duplicate of our
                // already-opened read FD to keep our finally logic below simple
                return thumbRead.dup();

            } finally {
                // Regardless of success or failure, try cleaning up any
                // remaining temporary file and close all our local FDs
                FileUtils.closeQuietly(thumbWrite);
                FileUtils.closeQuietly(thumbRead);
                deleteAndInvalidate(thumbTempFile);
            }
        }

        public void invalidateThumbnail(Uri uri) throws IOException {
            deleteAndInvalidate(getThumbnailFile(uri));
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

    private List<File> getThumbnailDirectories(String volumeName) throws FileNotFoundException {
        final File volumePath = getVolumePath(volumeName);
        return Arrays.asList(
                FileUtils.buildPath(volumePath, DIRECTORY_MUSIC, DIRECTORY_THUMBNAILS),
                FileUtils.buildPath(volumePath, DIRECTORY_MOVIES, DIRECTORY_THUMBNAILS),
                FileUtils.buildPath(volumePath, DIRECTORY_PICTURES, DIRECTORY_THUMBNAILS));
    }

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
        try {
            helper = getDatabaseForUri(uri);
        } catch (VolumeNotFoundException e) {
            Log.w(TAG, e);
            return;
        }

        helper.runWithTransaction((db) -> {
            final String idString = Long.toString(id);
            try (Cursor c = db.rawQuery("select _data from thumbnails where image_id=?"
                    + " union all select _data from videothumbnails where video_id=?",
                    new String[] { idString, idString })) {
                while (c.moveToNext()) {
                    String path = c.getString(0);
                    deleteIfAllowed(uri, Bundle.EMPTY, path);
                }
            }

            db.execSQL("delete from thumbnails where image_id=?", new String[] { idString });
            db.execSQL("delete from videothumbnails where video_id=?", new String[] { idString });
            return null;
        });
    }

    /**
     * @deprecated all operations should be routed through the overload that
     *             accepts a {@link Bundle} of extras.
     */
    @Override
    @Deprecated
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return update(uri, values,
                DatabaseUtils.createSqlQueryBundle(selection, selectionArgs, null));
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable Bundle extras) {
        Trace.beginSection("update");
        try {
            return updateInternal(uri, values, extras);
        } catch (FallbackException e) {
            return e.translateForUpdateDelete(getCallingPackageTargetSdkVersion());
        } finally {
            Trace.endSection();
        }
    }

    private int updateInternal(@NonNull Uri uri, @Nullable ContentValues initialValues,
            @Nullable Bundle extras) throws FallbackException {
        extras = (extras != null) ? extras : new Bundle();

        // Related items are only considered for new media creation, and they
        // can't be leveraged to move existing content into blocked locations
        extras.remove(QUERY_ARG_RELATED_URI);
        // INCLUDED_DEFAULT_DIRECTORIES extra should only be set inside MediaProvider.
        extras.remove(INCLUDED_DEFAULT_DIRECTORIES);

        final String userWhere = extras.getString(QUERY_ARG_SQL_SELECTION);
        final String[] userWhereArgs = extras.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);

        // Limit the hacky workaround to camera targeting Q and below, to allow newer versions
        // of camera that does the right thing to work correctly.
        if ("com.google.android.GoogleCamera".equals(getCallingPackageOrSelf())
                && getCallingPackageTargetSdkVersion() <= Build.VERSION_CODES.Q) {
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

        switch (match) {
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                extras.putString(QUERY_ARG_SQL_SELECTION,
                        BaseColumns._ID + "=" + uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                final long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri playlistUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(volumeName), playlistId);
                if (uri.getBooleanQueryParameter("move", false)) {
                    // Convert explicit request into query; sigh, moveItem()
                    // uses zero-based indexing instead of one-based indexing
                    final int from = Integer.parseInt(uri.getPathSegments().get(5)) + 1;
                    final int to = initialValues.getAsInteger(Playlists.Members.PLAY_ORDER) + 1;
                    extras.putString(QUERY_ARG_SQL_SELECTION,
                            Playlists.Members.PLAY_ORDER + "=" + from);
                    initialValues.put(Playlists.Members.PLAY_ORDER, to);
                }

                // Playlist contents are always persisted directly into playlist
                // files on disk to ensure that we can reliably migrate between
                // devices and recover from database corruption
                final int index;
                if (initialValues.containsKey(Playlists.Members.PLAY_ORDER)) {
                    index = movePlaylistMembers(playlistUri, initialValues, extras);
                } else {
                    index = resolvePlaylistIndex(playlistUri, extras);
                }
                if (initialValues.containsKey(Playlists.Members.AUDIO_ID)) {
                    final Bundle queryArgs = new Bundle();
                    queryArgs.putString(QUERY_ARG_SQL_SELECTION,
                            Playlists.Members.PLAY_ORDER + "=" + (index + 1));
                    removePlaylistMembers(playlistUri, queryArgs);

                    final ContentValues values = new ContentValues();
                    values.put(Playlists.Members.AUDIO_ID,
                            initialValues.getAsString(Playlists.Members.AUDIO_ID));
                    values.put(Playlists.Members.PLAY_ORDER, (index + 1));
                    addPlaylistMembers(playlistUri, values);
                }

                final ContentResolver resolver = getContext().getContentResolver();
                resolver.notifyChange(playlistUri, null, ContentResolver.NOTIFY_UPDATE);
                return 1;
            }
        }

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE, match, uri, extras, null);

        // Give callers interacting with a specific media item a chance to
        // escalate access if they don't already have it
        switch (match) {
            case AUDIO_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
                enforceCallingPermission(uri, extras, true);
        }

        boolean triggerInvalidate = false;
        boolean triggerScan = false;
        if (initialValues != null) {
            // IDs are forever; nobody should be editing them
            initialValues.remove(MediaColumns._ID);

            // Expiration times are hard-coded; let's derive them
            FileUtils.computeDateExpires(initialValues);

            // Ignore or augment incoming raw filesystem paths
            for (String column : sDataColumns.keySet()) {
                if (!initialValues.containsKey(column)) continue;

                if (isCallingPackageSelf() || isCallingPackageLegacyWrite()) {
                    // Mutation allowed
                } else {
                    Log.w(TAG, "Ignoring mutation of  " + column + " from "
                            + getCallingPackageOrSelf());
                    initialValues.remove(column);
                }
            }

            // Enforce allowed ownership transfers
            if (initialValues.containsKey(MediaColumns.OWNER_PACKAGE_NAME)) {
                if (isCallingPackageSelf() || isCallingPackageShell()) {
                    // When the caller is the media scanner or the shell, we let
                    // them change ownership however they see fit; nothing to do
                } else if (isCallingPackageDelegator()) {
                    // When the caller is a delegator, allow them to shift
                    // ownership only when current owner, or when ownerless
                    final String currentOwner;
                    final String proposedOwner = initialValues
                            .getAsString(MediaColumns.OWNER_PACKAGE_NAME);
                    final Uri genericUri = MediaStore.Files.getContentUri(volumeName,
                            ContentUris.parseId(uri));
                    try (Cursor c = queryForSingleItem(genericUri,
                            new String[] { MediaColumns.OWNER_PACKAGE_NAME }, null, null, null)) {
                        currentOwner = c.getString(0);
                    } catch (FileNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                    final boolean transferAllowed = (currentOwner == null)
                            || Arrays.asList(getSharedPackagesForPackage(getCallingPackageOrSelf()))
                                    .contains(currentOwner);
                    if (transferAllowed) {
                        Log.v(TAG, "Ownership transfer from " + currentOwner + " to "
                                + proposedOwner + " allowed");
                    } else {
                        Log.w(TAG, "Ownership transfer from " + currentOwner + " to "
                                + proposedOwner + " blocked");
                        initialValues.remove(MediaColumns.OWNER_PACKAGE_NAME);
                    }
                } else {
                    // Otherwise no ownership changes are allowed
                    initialValues.remove(MediaColumns.OWNER_PACKAGE_NAME);
                }
            }

            if (!isCallingPackageSelf()) {
                Trace.beginSection("filter");

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
                        triggerScan = true;
                    }

                    // If we're publishing this item, perform a blocking scan to
                    // make sure metadata is updated
                    if (MediaColumns.IS_PENDING.equals(column)) {
                        triggerScan = true;

                        // Explicitly clear columns used to ignore no-op scans,
                        // since we need to force a scan on publish
                        initialValues.putNull(MediaColumns.DATE_MODIFIED);
                        initialValues.putNull(MediaColumns.SIZE);
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
            if (getCallingPackageTargetSdkVersion() <= Build.VERSION_CODES.Q) {
                // These columns are removed in R.
                if (initialValues.containsKey("primary_directory")) {
                    initialValues.remove("primary_directory");
                }
                if (initialValues.containsKey("secondary_directory")) {
                    initialValues.remove("secondary_directory");
                }
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

        switch (match) {
            case AUDIO_PLAYLISTS:
            case AUDIO_PLAYLISTS_ID:
                // Playlist names are stored as display names, but leave
                // values untouched if the caller is ModernMediaScanner
                if (!isCallingPackageSelf()) {
                    if (initialValues.containsKey(Playlists.NAME)) {
                        initialValues.put(MediaColumns.DISPLAY_NAME,
                                initialValues.getAsString(Playlists.NAME));
                    }
                    if (!initialValues.containsKey(MediaColumns.MIME_TYPE)) {
                        initialValues.put(MediaColumns.MIME_TYPE, "audio/mpegurl");
                    }
                }
                break;
        }

        // If we're touching columns that would change placement of a file,
        // blend in current values and recalculate path
        final boolean allowMovement = extras.getBoolean(MediaStore.QUERY_ARG_ALLOW_MOVEMENT,
                !isCallingPackageSelf());
        if (containsAny(initialValues.keySet(), sPlacementColumns)
                && !initialValues.containsKey(MediaColumns.DATA)
                && !isThumbnail
                && allowMovement) {
            Trace.beginSection("movement");

            // We only support movement under well-defined collections
            switch (match) {
                case AUDIO_MEDIA_ID:
                case AUDIO_PLAYLISTS_ID:
                case VIDEO_MEDIA_ID:
                case IMAGES_MEDIA_ID:
                case DOWNLOADS_ID:
                case FILES_ID:
                    break;
                default:
                    throw new IllegalArgumentException("Movement of " + uri
                            + " which isn't part of well-defined collection not allowed");
            }

            final LocalCallingIdentity token = clearLocalCallingIdentity();
            final Uri genericUri = MediaStore.Files.getContentUri(volumeName,
                    ContentUris.parseId(uri));
            try (Cursor c = queryForSingleItem(genericUri,
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
            ensureNonUniqueFileColumns(match, uri, extras, initialValues, beforePath);

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
                ensureUniqueFileColumns(match, uri, extras, initialValues, beforePath);

                String afterPath = initialValues.getAsString(MediaColumns.DATA);

                if (isCrossUserEnabled()) {
                    String afterVolume = extractVolumeName(afterPath);
                    String afterVolumePath =  extractVolumePath(afterPath);
                    String beforeVolumePath = extractVolumePath(beforePath);

                    if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(beforeVolume)
                            && beforeVolume.equals(afterVolume)
                            && !beforeVolumePath.equals(afterVolumePath)) {
                        // On cross-user enabled devices, it can happen that a rename intended as
                        // /storage/emulated/999/foo -> /storage/emulated/999/foo can end up as
                        // /storage/emulated/999/foo -> /storage/emulated/0/foo. We now fix-up
                        afterPath = afterPath.replaceFirst(afterVolumePath, beforeVolumePath);
                    }
                }

                Log.d(TAG, "Moving " + beforePath + " to " + afterPath);
                try {
                    Os.rename(beforePath, afterPath);
                    invalidateFuseDentry(beforePath);
                    invalidateFuseDentry(afterPath);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ENOENT) {
                        Log.d(TAG, "Missing file at " + beforePath + "; continuing anyway");
                    } else {
                        throw new IllegalStateException(e);
                    }
                }
                initialValues.put(MediaColumns.DATA, afterPath);

                // Some indexed metadata may have been derived from the path on
                // disk, so scan this item again to update it
                triggerScan = true;
            }

            Trace.endSection();
        }

        // Make sure any updated paths look sane
        assertFileColumnsSane(match, uri, initialValues);

        if (initialValues.containsKey(FileColumns.DATA)) {
            // If we're changing paths, invalidate any thumbnails
            triggerInvalidate = true;

            // If the new file exists, trigger a scan to adjust any metadata
            // that might be derived from the path
            final String data = initialValues.getAsString(FileColumns.DATA);
            if (!TextUtils.isEmpty(data) && new File(data).exists()) {
                triggerScan = true;
            }
        }

        // If we're already doing this update from an internal scan, no need to
        // kick off another no-op scan
        if (isCallingPackageSelf()) {
            triggerScan = false;
        }

        // Since the update mutation may prevent us from matching items after
        // it's applied, we need to snapshot affected IDs here
        final LongArray updatedIds = new LongArray();
        if (triggerInvalidate || triggerScan) {
            Trace.beginSection("snapshot");
            final LocalCallingIdentity token = clearLocalCallingIdentity();
            try (Cursor c = qb.query(helper, new String[] { FileColumns._ID },
                    userWhere, userWhereArgs, null, null, null, null, null)) {
                while (c.moveToNext()) {
                    updatedIds.add(c.getLong(0));
                }
            } finally {
                restoreLocalCallingIdentity(token);
                Trace.endSection();
            }
        }

        final ContentValues values = new ContentValues(initialValues);
        switch (match) {
            case AUDIO_MEDIA_ID:
            case AUDIO_PLAYLISTS_ID:
            case VIDEO_MEDIA_ID:
            case IMAGES_MEDIA_ID:
            case FILES_ID:
            case DOWNLOADS_ID: {
                FileUtils.computeValuesFromData(values, isFuseThread());
                break;
            }
        }

        if (initialValues.containsKey(FileColumns.MEDIA_TYPE)) {
            final int mediaType = initialValues.getAsInteger(FileColumns.MEDIA_TYPE);
            switch (mediaType) {
                case FileColumns.MEDIA_TYPE_AUDIO: {
                    computeAudioLocalizedValues(values);
                    computeAudioKeyValues(values);
                    break;
                }
            }
        }

        count = updateAllowingReplace(qb, helper, values, userWhere, userWhereArgs);

        // If the caller tried (and failed) to update metadata, the file on disk
        // might have changed, to scan it to collect the latest metadata.
        if (triggerInvalidate || triggerScan) {
            Trace.beginSection("invalidate");
            final LocalCallingIdentity token = clearLocalCallingIdentity();
            try {
                for (int i = 0; i < updatedIds.size(); i++) {
                    final long updatedId = updatedIds.get(i);
                    final Uri updatedUri = Files.getContentUri(volumeName, updatedId);
                    helper.postBackground(() -> {
                        invalidateThumbnails(updatedUri);
                    });

                    if (triggerScan) {
                        try (Cursor c = queryForSingleItem(updatedUri,
                                new String[] { FileColumns.DATA }, null, null, null)) {
                            final File file = new File(c.getString(0));
                            boolean runScanFileInBackground =
                                    extras.getBoolean(MediaStore.QUERY_ARG_DO_ASYNC_SCAN, false);
                            if (runScanFileInBackground) {
                                helper.postBackground(() -> {
                                    scanFileAsMediaProvider(file, REASON_DEMAND);
                                });
                            } else {
                                helper.postBlocking(() -> {
                                    scanFileAsMediaProvider(file, REASON_DEMAND);
                                });
                            }
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

        return count;
    }

    /**
     * Update row(s) that match {@code userWhere} in MediaProvider database with {@code values}.
     * Treats update as replace for updates with conflicts.
     */
    private int updateAllowingReplace(@NonNull SQLiteQueryBuilder qb,
            @NonNull DatabaseHelper helper, @NonNull ContentValues values, String userWhere,
            String[] userWhereArgs) throws SQLiteConstraintException {
        return helper.runWithTransaction((db) -> {
            try {
                return qb.update(helper, values, userWhere, userWhereArgs);
            } catch (SQLiteConstraintException e) {
                // b/155320967 Apps sometimes create a file via file path and then update another
                // explicitly inserted db row to this file. We have to resolve this update with a
                // replace.

                if (getCallingPackageTargetSdkVersion() >= Build.VERSION_CODES.R) {
                    // We don't support replace for non-legacy apps. Non legacy apps should have
                    // clearer interactions with MediaProvider.
                    throw e;
                }

                final String path = values.getAsString(FileColumns.DATA);

                // We will only handle UNIQUE constraint error for FileColumns.DATA. We will not try
                // update and replace if no file exists for conflicting db row.
                if (path == null || !new File(path).exists()) {
                    throw e;
                }

                final Uri uri = FileUtils.getContentUriForPath(path);
                final boolean allowHidden = isCallingPackageAllowedHidden();
                // The db row which caused UNIQUE constraint error may not match all column values
                // of the given queryBuilder, hence using a generic queryBuilder with Files uri.
                Bundle extras = new Bundle();
                extras.putInt(QUERY_ARG_MATCH_PENDING, MATCH_INCLUDE);
                extras.putInt(QUERY_ARG_MATCH_TRASHED, MATCH_INCLUDE);
                final SQLiteQueryBuilder qbForReplace = getQueryBuilder(TYPE_DELETE,
                        matchUri(uri, allowHidden), uri, extras, null);
                final long rowId = getIdIfPathOwnedByPackages(qbForReplace, helper, path,
                        getSharedPackages());

                if (rowId != -1 && qbForReplace.delete(helper, "_id=?",
                        new String[] {Long.toString(rowId)}) == 1) {
                    Log.i(TAG, "Retrying database update after deleting conflicting entry");
                    return qb.update(helper, values, userWhere, userWhereArgs);
                }
                // Rethrow SQLiteConstraintException if app doesn't own the conflicting db row.
                throw e;
            }
        });
    }

    /**
     * Update the internal table of {@link MediaStore.Audio.Playlists.Members}
     * by parsing the playlist file on disk and resolving it against scanned
     * audio items.
     * <p>
     * When a playlist references a missing audio item, the associated
     * {@link Playlists.Members#PLAY_ORDER} is skipped, leaving a gap to ensure
     * that the playlist entry is retained to avoid user data loss.
     */
    private void resolvePlaylistMembers(@NonNull Uri playlistUri) {
        Trace.beginSection("resolvePlaylistMembers");
        try {
            final DatabaseHelper helper;
            try {
                helper = getDatabaseForUri(playlistUri);
            } catch (VolumeNotFoundException e) {
                throw e.rethrowAsIllegalArgumentException();
            }

            helper.runWithTransaction((db) -> {
                resolvePlaylistMembersInternal(playlistUri, db);
                return null;
            });
        } finally {
            Trace.endSection();
        }
    }

    private void resolvePlaylistMembersInternal(@NonNull Uri playlistUri,
            @NonNull SQLiteDatabase db) {
        try {
            // Refresh playlist members based on what we parse from disk
            final long playlistId = ContentUris.parseId(playlistUri);
            final Map<String, Long> membersMap = getAllPlaylistMembers(playlistId);
            db.delete("audio_playlists_map", "playlist_id=" + playlistId, null);

            final Path playlistPath = queryForDataFile(playlistUri, null).toPath();
            final Playlist playlist = new Playlist();
            playlist.read(playlistPath.toFile());

            final List<Path> members = playlist.asList();
            for (int i = 0; i < members.size(); i++) {
                try {
                    final Path audioPath = playlistPath.getParent().resolve(members.get(i));
                    final long audioId = queryForPlaylistMember(audioPath, membersMap);

                    final ContentValues values = new ContentValues();
                    values.put(Playlists.Members.PLAY_ORDER, i + 1);
                    values.put(Playlists.Members.PLAYLIST_ID, playlistId);
                    values.put(Playlists.Members.AUDIO_ID, audioId);
                    db.insert("audio_playlists_map", null, values);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to resolve playlist member", e);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to refresh playlist", e);
        }
    }

    private Map<String, Long> getAllPlaylistMembers(long playlistId) {
        final Map<String, Long> membersMap = new ArrayMap<>();

        final Uri uri = Playlists.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        final String[] projection = new String[] {
                Playlists.Members.DATA,
                Playlists.Members.AUDIO_ID
        };
        try (Cursor c = query(uri, projection, null, null)) {
            if (c == null) {
                Log.e(TAG, "Cursor is null, failed to create cached playlist member info.");
                return membersMap;
            }
            while (c.moveToNext()) {
                membersMap.put(c.getString(0), c.getLong(1));
            }
        }
        return membersMap;
    }

    /**
     * Make two attempts to query this playlist member: first based on the exact
     * path, and if that fails, fall back to picking a single item matching the
     * display name. When there are multiple items with the same display name,
     * we can't resolve between them, and leave this member unresolved.
     */
    private long queryForPlaylistMember(@NonNull Path path, @NonNull Map<String, Long> membersMap)
            throws IOException {
        final String data = path.toFile().getCanonicalPath();
        if (membersMap.containsKey(data)) {
            return membersMap.get(data);
        }
        final Uri audioUri = Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor c = queryForSingleItem(audioUri,
                new String[] { BaseColumns._ID }, MediaColumns.DATA + "=?",
                new String[] { data }, null)) {
            return c.getLong(0);
        } catch (FileNotFoundException ignored) {
        }
        try (Cursor c = queryForSingleItem(audioUri,
                new String[] { BaseColumns._ID }, MediaColumns.DISPLAY_NAME + "=?",
                new String[] { path.toFile().getName() }, null)) {
            return c.getLong(0);
        } catch (FileNotFoundException ignored) {
        }
        throw new FileNotFoundException();
    }

    /**
     * Add the given audio item to the given playlist. Defaults to adding at the
     * end of the playlist when no {@link Playlists.Members#PLAY_ORDER} is
     * defined.
     */
    private long addPlaylistMembers(@NonNull Uri playlistUri, @NonNull ContentValues values)
            throws FallbackException {
        final long audioId = values.getAsLong(Audio.Playlists.Members.AUDIO_ID);
        final String audioVolumeName = MediaStore.VOLUME_INTERNAL.equals(getVolumeName(playlistUri))
                ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL;
        final Uri audioUri = Audio.Media.getContentUri(audioVolumeName, audioId);

        Integer playOrder = values.getAsInteger(Playlists.Members.PLAY_ORDER);
        playOrder = (playOrder != null) ? (playOrder - 1) : Integer.MAX_VALUE;

        try {
            final File playlistFile = queryForDataFile(playlistUri, null);
            final File audioFile = queryForDataFile(audioUri, null);

            final Playlist playlist = new Playlist();
            playlist.read(playlistFile);
            playOrder = playlist.add(playOrder,
                    playlistFile.toPath().getParent().relativize(audioFile.toPath()));
            playlist.write(playlistFile);
            invalidateFuseDentry(playlistFile);

            resolvePlaylistMembers(playlistUri);

            // Callers are interested in the actual ID we generated
            final Uri membersUri = Playlists.Members.getContentUri(
                    getVolumeName(playlistUri), ContentUris.parseId(playlistUri));
            try (Cursor c = query(membersUri, new String[] { BaseColumns._ID },
                    Playlists.Members.PLAY_ORDER + "=" + (playOrder + 1), null, null)) {
                c.moveToFirst();
                return c.getLong(0);
            }
        } catch (IOException e) {
            throw new FallbackException("Failed to update playlist", e,
                    android.os.Build.VERSION_CODES.R);
        }
    }

    private int addPlaylistMembers(@NonNull Uri playlistUri, @NonNull ContentValues[] initialValues)
            throws FallbackException {
        final String volumeName = getVolumeName(playlistUri);
        final String audioVolumeName =
                MediaStore.VOLUME_INTERNAL.equals(volumeName)
                        ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL;

        try {
            final File playlistFile = queryForDataFile(playlistUri, null);
            final Playlist playlist = new Playlist();
            playlist.read(playlistFile);

            for (ContentValues values : initialValues) {
                final long audioId = values.getAsLong(Audio.Playlists.Members.AUDIO_ID);
                final Uri audioUri = Audio.Media.getContentUri(audioVolumeName, audioId);
                final File audioFile = queryForDataFile(audioUri, null);

                Integer playOrder = values.getAsInteger(Playlists.Members.PLAY_ORDER);
                playOrder = (playOrder != null) ? (playOrder - 1) : Integer.MAX_VALUE;
                playlist.add(playOrder,
                        playlistFile.toPath().getParent().relativize(audioFile.toPath()));
            }
            playlist.write(playlistFile);

            resolvePlaylistMembers(playlistUri);
        } catch (IOException e) {
            throw new FallbackException("Failed to update playlist", e,
                    android.os.Build.VERSION_CODES.R);
        }

        return initialValues.length;
    }

    /**
     * Move an audio item within the given playlist.
     */
    private int movePlaylistMembers(@NonNull Uri playlistUri, @NonNull ContentValues values,
            @NonNull Bundle queryArgs) throws FallbackException {
        final int fromIndex = resolvePlaylistIndex(playlistUri, queryArgs);
        final int toIndex = values.getAsInteger(Playlists.Members.PLAY_ORDER) - 1;
        if (fromIndex == -1) {
            throw new FallbackException("Failed to resolve playlist member " + queryArgs,
                    android.os.Build.VERSION_CODES.R);
        }
        try {
            final File playlistFile = queryForDataFile(playlistUri, null);

            final Playlist playlist = new Playlist();
            playlist.read(playlistFile);
            final int finalIndex = playlist.move(fromIndex, toIndex);
            playlist.write(playlistFile);
            invalidateFuseDentry(playlistFile);

            resolvePlaylistMembers(playlistUri);
            return finalIndex;
        } catch (IOException e) {
            throw new FallbackException("Failed to update playlist", e,
                    android.os.Build.VERSION_CODES.R);
        }
    }

    /**
     * Removes an audio item or multiple audio items(if targetSDK<R) from the given playlist.
     */
    private int removePlaylistMembers(@NonNull Uri playlistUri, @NonNull Bundle queryArgs)
            throws FallbackException {
        final int[] indexes = resolvePlaylistIndexes(playlistUri, queryArgs);
        try {
            final File playlistFile = queryForDataFile(playlistUri, null);

            final Playlist playlist = new Playlist();
            playlist.read(playlistFile);
            final int count;
            if (indexes.length == 0) {
                // This means either no playlist members match the query or VolumeNotFoundException
                // was thrown. So we don't have anything to delete.
                count = 0;
            } else {
                count = playlist.removeMultiple(indexes);
            }
            playlist.write(playlistFile);
            invalidateFuseDentry(playlistFile);

            resolvePlaylistMembers(playlistUri);
            return count;
        } catch (IOException e) {
            throw new FallbackException("Failed to update playlist", e,
                    android.os.Build.VERSION_CODES.R);
        }
    }

    /**
     * Remove an audio item from the given playlist since the playlist file or the audio file is
     * already removed.
     */
    private void removePlaylistMembers(int mediaType, long id) {
        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(Audio.Media.EXTERNAL_CONTENT_URI);
        } catch (VolumeNotFoundException e) {
            Log.w(TAG, e);
            return;
        }

        helper.runWithTransaction((db) -> {
            final String where;
            if (mediaType == FileColumns.MEDIA_TYPE_PLAYLIST) {
                where = "playlist_id=?";
            } else {
                where = "audio_id=?";
            }
            db.delete("audio_playlists_map", where, new String[] { "" + id });
            return null;
        });
    }

    /**
     * Resolve query arguments that are designed to select specific playlist
     * items using the playlist's {@link Playlists.Members#PLAY_ORDER}.
     *
     * @return an array of the indexes that match the query.
     */
    private int[] resolvePlaylistIndexes(@NonNull Uri playlistUri, @NonNull Bundle queryArgs) {
        final Uri membersUri = Playlists.Members.getContentUri(
                getVolumeName(playlistUri), ContentUris.parseId(playlistUri));

        final DatabaseHelper helper;
        final SQLiteQueryBuilder qb;
        try {
            helper = getDatabaseForUri(membersUri);
            qb = getQueryBuilder(TYPE_DELETE, AUDIO_PLAYLISTS_ID_MEMBERS,
                    membersUri, queryArgs, null);
        } catch (VolumeNotFoundException ignored) {
            return new int[0];
        }

        try (Cursor c = qb.query(helper,
                new String[] { Playlists.Members.PLAY_ORDER }, queryArgs, null)) {
            if ((c.getCount() >= 1) && c.moveToFirst()) {
                int size = c.getCount();
                int[] res = new int[size];
                for (int i = 0; i < size; ++i) {
                    res[i] = c.getInt(0) - 1;
                    c.moveToNext();
                }
                return res;
            } else {
                // Cursor size is 0
                return new int[0];
            }
        }
    }

    /**
     * Resolve query arguments that are designed to select a specific playlist
     * item using its {@link Playlists.Members#PLAY_ORDER}.
     *
     * @return if there's only 1 item that matches the query, returns its index. Returns -1
     * otherwise.
     */
    private int resolvePlaylistIndex(@NonNull Uri playlistUri, @NonNull Bundle queryArgs) {
        int[] indexes = resolvePlaylistIndexes(playlistUri, queryArgs);
        if (indexes.length == 1) {
            return indexes[0];
        }
        return -1;
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
                return ensureThumbnail(targetUri, signal);
            }
            case AUDIO_ALBUMART_FILE_ID: {
                final long audioId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Audio.Media.getContentUri(volumeName), audioId);
                return ensureThumbnail(targetUri, signal);
            }
            case VIDEO_MEDIA_ID_THUMBNAIL: {
                final long videoId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Video.Media.getContentUri(volumeName), videoId);
                return ensureThumbnail(targetUri, signal);
            }
            case IMAGES_MEDIA_ID_THUMBNAIL: {
                final long imageId = Long.parseLong(uri.getPathSegments().get(3));
                final Uri targetUri = ContentUris
                        .withAppendedId(Images.Media.getContentUri(volumeName), imageId);
                return ensureThumbnail(targetUri, signal);
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
                && MimeUtils.startsWithIgnoreCase(mimeTypeFilter, "image/");
        if (wantsThumb) {
            final ParcelFileDescriptor pfd = ensureThumbnail(uri, signal);
            return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        }

        // Worst case, return the underlying file
        return new AssetFileDescriptor(openFileCommon(uri, "r", signal), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private ParcelFileDescriptor ensureThumbnail(Uri uri, CancellationSignal signal)
            throws FileNotFoundException {
        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int match = matchUri(uri, allowHidden);

        Trace.beginSection("ensureThumbnail");
        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try {
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
                case FILES_ID:
                case DOWNLOADS_ID: {
                    // When item is referenced in a generic way, resolve to actual type
                    final int mediaType = MimeUtils.resolveMediaType(getType(uri));
                    switch (mediaType) {
                        case FileColumns.MEDIA_TYPE_AUDIO:
                            return mAudioThumbnailer.ensureThumbnail(uri, signal);
                        case FileColumns.MEDIA_TYPE_VIDEO:
                            return mVideoThumbnailer.ensureThumbnail(uri, signal);
                        case FileColumns.MEDIA_TYPE_IMAGE:
                            return mImageThumbnailer.ensureThumbnail(uri, signal);
                        default:
                            throw new FileNotFoundException();
                    }
                }
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

    private void handleInsertedRowForFuse(long rowId) {
        if (isFuseThread()) {
            // Removes restored row ID saved list.
            mCallingIdentity.get().removeDeletedRowId(rowId);
        }
    }

    private void handleUpdatedRowForFuse(@NonNull String oldPath, @NonNull String ownerPackage,
            long oldRowId, long newRowId) {
        if (oldRowId == newRowId) {
            // Update didn't delete or add row ID. We don't need to save row ID or remove saved
            // deleted ID.
            return;
        }

        handleDeletedRowForFuse(oldPath, ownerPackage, oldRowId);
        handleInsertedRowForFuse(newRowId);
    }

    private void handleDeletedRowForFuse(@NonNull String path, @NonNull String ownerPackage,
            long rowId) {
        if (!isFuseThread()) {
            return;
        }

        // Invalidate saved owned ID's of the previous owner of the deleted path, this prevents old
        // owner from gaining access to newly created file with restored row ID.
        if (!ownerPackage.equals("null") && !ownerPackage.equals(getCallingPackageOrSelf())) {
            invalidateLocalCallingIdentityCache(ownerPackage, "owned_database_row_deleted:"
                    + path);
        }
        // Saves row ID corresponding to deleted path. Saved row ID will be restored on subsequent
        // create or rename.
        mCallingIdentity.get().addDeletedRowId(path, rowId);
    }

    private void handleOwnerPackageNameChange(@NonNull String oldPath,
            @NonNull String oldOwnerPackage, @NonNull String newOwnerPackage) {
        if (Objects.equals(oldOwnerPackage, newOwnerPackage)) {
            return;
        }
        // Invalidate saved owned ID's of the previous owner of the renamed path, this prevents old
        // owner from gaining access to replaced file.
        invalidateLocalCallingIdentityCache(oldOwnerPackage, "owner_package_changed:" + oldPath);
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
        final String volumeName = FileUtils.getVolumeName(getContext(), file);
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
                DatabaseUtils.createSqlQueryBundle(selection, selectionArgs, null), signal, true);
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

    private File getFuseFile(File file) {
        String filePath = file.getPath().replaceFirst(
                "/storage/", "/mnt/user/" + UserHandle.myUserId() + "/");
        return new File(filePath);
    }

    private @NonNull FuseDaemon getFuseDaemonForFile(@NonNull File file)
            throws FileNotFoundException {
        final FuseDaemon daemon = ExternalStorageServiceImpl.getFuseDaemon(getVolumeId(file));
        if (daemon == null) {
            throw new FileNotFoundException("Missing FUSE daemon for " + file);
        } else {
            return daemon;
        }
    }

    private void invalidateFuseDentry(@NonNull File file) {
        invalidateFuseDentry(file.getAbsolutePath());
    }

    private void invalidateFuseDentry(@NonNull String path) {
        try {
            final FuseDaemon daemon = getFuseDaemonForFile(new File(path));
            if (isFuseThread()) {
                // If we are on a FUSE thread, we don't need to invalidate,
                // (and *must* not, otherwise we'd crash) because the invalidation
                // is already reflected in the lower filesystem
                return;
            } else {
                daemon.invalidateFuseDentryCache(path);
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed to invalidate FUSE dentry", e);
        }
    }

    /**
     * Replacement for {@link #openFileHelper(Uri, String)} which enforces any
     * permissions applicable to the path before returning.
     *
     * <p>This function should never be called from the fuse thread since it tries to open
     * a "/mnt/user" path.
     */
    private ParcelFileDescriptor openFileAndEnforcePathPermissionsHelper(Uri uri, int match,
            String mode, CancellationSignal signal) throws FileNotFoundException {
        int modeBits = ParcelFileDescriptor.parseMode(mode);
        boolean forWrite = (modeBits & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0;
        if (forWrite) {
            // Upgrade 'w' only to 'rw'. This allows us acquire a WR_LOCK when calling
            // #shouldOpenWithFuse
            modeBits |= ParcelFileDescriptor.MODE_READ_WRITE;
        }

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

        checkAccess(uri, Bundle.EMPTY, file, forWrite);

        // We don't check ownership for files with IS_PENDING set by FUSE
        if (isPending && !isPendingFromFuse(file)) {
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

            // Invalidate so subsequent stat(2) on the upper fs is eventually consistent
            invalidateFuseDentry(file);
            try {
                switch (match) {
                    case IMAGES_THUMBNAILS_ID:
                    case VIDEO_THUMBNAILS_ID:
                        final ContentValues values = new ContentValues();
                        updateImageMetadata(values, file);
                        update(uri, values, null, null);
                        break;
                    default:
                        mMediaScanner.scanFile(file, REASON_DEMAND);
                        break;
                }
            } catch (Exception e2) {
                Log.w(TAG, "Failed to update metadata for " + uri, e2);
            }
        };

        try {
            // First, handle any redaction that is needed for caller
            final ParcelFileDescriptor pfd;
            final String filePath = file.getPath();
            if (redactionInfo.redactionRanges.length > 0) {
                if (SystemProperties.getBoolean(PROP_FUSE, false)) {
                    // If fuse is enabled, we can provide an fd that points to the fuse
                    // file system and handle redaction in the fuse handler when the caller reads.
                    Log.i(TAG, "Redacting with new FUSE for " + filePath);
                    long tid = android.os.Process.myTid();
                    synchronized (mShouldRedactThreadIds) {
                        mShouldRedactThreadIds.add(tid);
                    }
                    try {
                        pfd = FileUtils.openSafely(getFuseFile(file), modeBits);
                    } finally {
                        synchronized (mShouldRedactThreadIds) {
                            mShouldRedactThreadIds.remove(mShouldRedactThreadIds.indexOf(tid));
                        }
                    }
                } else {
                    // TODO(b/135341978): Remove this and associated code
                    // when fuse is on by default.
                    Log.i(TAG, "Redacting with old FUSE for " + filePath);
                    pfd = RedactingFileDescriptor.open(
                            getContext(),
                            file,
                            modeBits,
                            redactionInfo.redactionRanges,
                            redactionInfo.freeOffsets);
                }
            } else {
                FuseDaemon daemon = null;
                try {
                    daemon = getFuseDaemonForFile(file);
                } catch (FileNotFoundException ignored) {
                }
                ParcelFileDescriptor lowerFsFd = FileUtils.openSafely(file, modeBits);
                // Always acquire a readLock. This allows us make multiple opens via lower
                // filesystem
                boolean shouldOpenWithFuse = daemon != null
                        && daemon.shouldOpenWithFuse(filePath, true /* forRead */, lowerFsFd.getFd());

                if (SystemProperties.getBoolean(PROP_FUSE, false) && shouldOpenWithFuse) {
                    // If the file is already opened on the FUSE mount with VFS caching enabled
                    // we return an upper filesystem fd (via FUSE) to avoid file corruption
                    // resulting from cache inconsistencies between the upper and lower
                    // filesystem caches
                    Log.w(TAG, "Using FUSE for " + filePath);
                    pfd = FileUtils.openSafely(getFuseFile(file), modeBits);
                    try {
                        lowerFsFd.close();
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to close lower filesystem fd " + file.getPath(), e);
                    }
                } else {
                    Log.i(TAG, "Using lower FS for " + filePath);
                    if (forWrite) {
                        // When opening for write on the lower filesystem, invalidate the VFS dentry
                        // so subsequent open/getattr calls will return correctly.
                        //
                        // A 'dirty' dentry with write back cache enabled can cause the kernel to
                        // ignore file attributes or even see stale page cache data when the lower
                        // filesystem has been modified outside of the FUSE driver
                        invalidateFuseDentry(file);
                    }

                    pfd = lowerFsFd;
                }
            }

            // Second, wrap in any listener that we've requested
            if (!isPending && forWrite && listener != null) {
                return ParcelFileDescriptor.wrap(pfd, BackgroundThread.getHandler(), listener);
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

    private void deleteAndInvalidate(@NonNull Path path) {
        deleteAndInvalidate(path.toFile());
    }

    private void deleteAndInvalidate(@NonNull File file) {
        file.delete();
        invalidateFuseDentry(file);
    }

    private void deleteIfAllowed(Uri uri, Bundle extras, String path) {
        try {
            final File file = new File(path);
            checkAccess(uri, extras, file, true);
            deleteAndInvalidate(file);
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

    private boolean isCallingPackageRequestingLegacy() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY_GRANTED);
    }

    private static int getFileMediaType(String path) {
        final File file = new File(path);
        final String mimeType = MimeUtils.resolveMimeType(file);
        return MimeUtils.resolveMediaType(mimeType);
    }

    private boolean canAccessMediaFile(String filePath, boolean allowLegacy) {
        if (!allowLegacy && isCallingPackageRequestingLegacy()) {
            return false;
        }
        switch (getFileMediaType(filePath)) {
            case FileColumns.MEDIA_TYPE_IMAGE:
                return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_IMAGES);
            case FileColumns.MEDIA_TYPE_VIDEO:
                return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_VIDEO);
            default:
                return false;
        }
    }

    /**
     * Returns true if:
     * <ul>
     * <li>the calling identity is an app targeting Q or older versions AND is requesting legacy
     * storage
     * <li>the calling identity holds {@code MANAGE_EXTERNAL_STORAGE}
     * <li>the calling identity owns filePath (eg /Android/data/com.foo)
     * <li>the calling identity has permission to write images and the given file is an image file
     * <li>the calling identity has permission to write video and the given file is an video file
     * </ul>
     */
    private boolean shouldBypassFuseRestrictions(boolean forWrite, String filePath) {
        boolean isRequestingLegacyStorage = forWrite ? isCallingPackageLegacyWrite()
                : isCallingPackageLegacyRead();
        if (isRequestingLegacyStorage) {
            return true;
        }

        if (isCallingPackageManager()) {
            return true;
        }

        // Files under the apps own private directory
        final String appSpecificDir = extractPathOwnerPackageName(filePath);
        if (appSpecificDir != null && isCallingIdentitySharedPackageName(appSpecificDir)) {
            return true;
        }

        // Apps with write access to images and/or videos can bypass our restrictions if all of the
        // the files they're accessing are of the compatible media type.
        if (canAccessMediaFile(filePath, /*allowLegacy*/ true)) {
            return true;
        }

        return false;
    }

    /**
     * Returns true if the passed in path is an application-private data directory
     * (such as Android/data/com.foo or Android/obb/com.foo) that does not belong to the caller.
     */
    private boolean isPrivatePackagePathNotOwnedByCaller(String path) {
        // Files under the apps own private directory
        final String appSpecificDir = extractPathOwnerPackageName(path);

        if (appSpecificDir == null) {
            return false;
        }

        final String relativePath = extractRelativePath(path);
        // Android/media is not considered private, because it contains media that is explicitly
        // scanned and shared by other apps
        if (relativePath.startsWith("Android/media")) {
            return false;
        }

        // This is a private-package path; return true if not owned by the caller
        return !isCallingIdentitySharedPackageName(appSpecificDir);
    }

    private boolean shouldBypassDatabaseAndSetDirtyForFuse(int uid, String path) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));
        try {
            boolean shouldBypass = false;
            if (uid != android.os.Process.SHELL_UID && isCallingPackageManager()) {
                shouldBypass = true;
            }  else if (isCallingPackageLegacyWrite() && isCallingPackageSystemGallery()) {
                // We bypass db operations for legacy system galleries with W_E_S (see b/167307393).
                // Tracking a longer term solution in b/168784136.
                shouldBypass = true;
            }

            if (shouldBypass) {
                synchronized (mNonHiddenPaths) {
                    File file = new File(path);
                    String key = file.getParent();
                    boolean maybeHidden = !mNonHiddenPaths.containsKey(key);

                    if (maybeHidden) {
                        File topNoMedia = FileUtils.getTopLevelNoMedia(new File(path));
                        if (topNoMedia == null) {
                            mNonHiddenPaths.put(key, 0);
                        } else {
                            mMediaScanner.onDirectoryDirty(topNoMedia);
                        }
                    }
                }
            }
            return shouldBypass;
        } finally {
            restoreLocalCallingIdentity(token);
        }
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

    public static final Set<String> sRedactedExifTags = new ArraySet<>(
            Arrays.asList(REDACTED_EXIF_TAGS));

    private static final class RedactionInfo {
        public final long[] redactionRanges;
        public final long[] freeOffsets;
        public RedactionInfo(long[] redactionRanges, long[] freeOffsets) {
            this.redactionRanges = redactionRanges;
            this.freeOffsets = freeOffsets;
        }
    }

    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int mMaxSize;

        public LRUCache(int maxSize) {
            this.mMaxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > mMaxSize;
        }
    }

    /**
     * Calculates the ranges that need to be redacted for the given file and user that wants to
     * access the file.
     *
     * @param uid UID of the package wanting to access the file
     * @param path File path
     * @param tid thread id making IO on the FUSE filesystem
     * @return Ranges that should be redacted.
     *
     * @throws IOException if an error occurs while calculating the redaction ranges
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    @NonNull
    public long[] getRedactionRangesForFuse(String path, int uid, int tid) throws IOException {
        final File file = new File(path);

        // When we're calculating redaction ranges for MediaProvider, it means we're actually
        // calculating redaction ranges for another app that called to MediaProvider through Binder.
        // If the tid is in mShouldRedactThreadIds, we should redact, otherwise, we don't redact
        if (uid == android.os.Process.myUid()) {
            boolean shouldRedact = false;
            synchronized (mShouldRedactThreadIds) {
                shouldRedact = mShouldRedactThreadIds.indexOf(tid) != -1;
            }
            if (shouldRedact) {
                return getRedactionRanges(file).redactionRanges;
            } else {
                return new long[0];
            }
        }

        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));

        long[] res = new long[0];
        try {
            if (!isRedactionNeeded()
                    || shouldBypassFuseRestrictions(/*forWrite*/ false, path)) {
                return res;
            }

            final Uri contentUri = FileUtils.getContentUriForPath(path);
            final String[] projection = new String[]{
                    MediaColumns.OWNER_PACKAGE_NAME, MediaColumns._ID };
            final String selection = MediaColumns.DATA + "=?";
            final String[] selectionArgs = new String[] { path };
            final String ownerPackageName;
            final Uri item;
            try (final Cursor c = queryForSingleItem(contentUri, projection, selection,
                    selectionArgs, null)) {
                c.moveToFirst();
                ownerPackageName = c.getString(0);
                item = ContentUris.withAppendedId(contentUri, /*item id*/ c.getInt(1));
            } catch (FileNotFoundException e) {
                // Ideally, this shouldn't happen unless the file was deleted after we checked its
                // existence and before we get to the redaction logic here. In this case we throw
                // and fail the operation and FuseDaemon should handle this and fail the whole open
                // operation gracefully.
                throw new FileNotFoundException(
                        path + " not found while calculating redaction ranges: " + e.getMessage());
            }

            final boolean callerIsOwner = Objects.equals(getCallingPackageOrSelf(),
                    ownerPackageName);
            final boolean callerHasUriPermission = getContext().checkUriPermission(
                    item, mCallingIdentity.get().pid, mCallingIdentity.get().uid,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PERMISSION_GRANTED;

            if (!callerIsOwner && !callerHasUriPermission) {
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
    @VisibleForTesting
    public static RedactionInfo getRedactionRanges(File file) throws IOException {
        Trace.beginSection("getRedactionRanges");
        final LongArray res = new LongArray();
        final LongArray freeOffsets = new LongArray();
        try (FileInputStream is = new FileInputStream(file)) {
            final String mimeType = MimeUtils.resolveMimeType(file);
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
                final XmpInterface exifXmp = XmpInterface.fromContainer(exif);
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
                final XmpInterface isoXmp = XmpInterface.fromContainer(iso);
                res.addAll(isoXmp.getRedactionRanges());
            }
        } catch (FileNotFoundException ignored) {
            // If file not found, then there's nothing to redact
        } catch (IOException e) {
            throw new IOException("Failed to redact " + file, e);
        }
        Trace.endSection();
        return new RedactionInfo(res.toArray(), freeOffsets.toArray());
    }

    /**
     * @return {@code true} if {@code file} is pending from FUSE, {@code false} otherwise.
     * Files pending from FUSE will not have pending file pattern.
     */
    private static boolean isPendingFromFuse(@NonNull File file) {
        final Matcher matcher =
                FileUtils.PATTERN_EXPIRES_FILE.matcher(extractDisplayName(file.getName()));
        return !matcher.matches();
    }

    /**
     * Checks if the app identified by the given UID is allowed to open the given file for the given
     * access mode.
     *
     * @param path the path of the file to be opened
     * @param uid UID of the app requesting to open the file
     * @param forWrite specifies if the file is to be opened for write
     * @return 0 upon success. {@link OsConstants#EACCES} if the operation is illegal or not
     * permitted for the given {@code uid} or if the calling package is a legacy app that doesn't
     * have right storage permission.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int isOpenAllowedForFuse(String path, int uid, boolean forWrite) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));

        try {
            if (isPrivatePackagePathNotOwnedByCaller(path)) {
                Log.e(TAG, "Can't open a file in another app's external directory!");
                return OsConstants.ENOENT;
            }

            if (shouldBypassFuseRestrictions(forWrite, path)) {
                return 0;
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EACCES;
            }

            final Uri contentUri = FileUtils.getContentUriForPath(path);
            final String[] projection = new String[]{
                    MediaColumns._ID,
                    MediaColumns.OWNER_PACKAGE_NAME,
                    MediaColumns.IS_PENDING};
            final String selection = MediaColumns.DATA + "=?";
            final String[] selectionArgs = new String[] { path };
            final Uri fileUri;
            final boolean isPending;
            String ownerPackageName = null;
            try (final Cursor c = queryForSingleItem(contentUri, projection, selection,
                    selectionArgs, null)) {
                fileUri = ContentUris.withAppendedId(contentUri, c.getInt(0));
                ownerPackageName = c.getString(1);
                isPending = c.getInt(2) != 0;
            }

            final File file = new File(path);
            checkAccess(fileUri, Bundle.EMPTY, file, forWrite);

            // We don't check ownership for files with IS_PENDING set by FUSE
            if (isPending && !isPendingFromFuse(new File(path))) {
                requireOwnershipForItem(ownerPackageName, fileUri);
            }
            return 0;
        } catch (FileNotFoundException e) {
            // We are here because
            // * App doesn't have read permission to the requested path, hence queryForSingleItem
            //   couldn't return a valid db row, or,
            // * There is no db row corresponding to the requested path, which is more unlikely.
            // In both of these cases, it means that app doesn't have access permission to the file.
            Log.e(TAG, "Couldn't find file: " + path);
            return OsConstants.EACCES;
        } catch (IllegalStateException | SecurityException e) {
            Log.e(TAG, "Permission to access file: " + path + " is denied");
            return OsConstants.EACCES;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Returns {@code true} if {@link #mCallingIdentity#getSharedPackages(String)} contains the
     * given package name, {@code false} otherwise.
     * <p> Assumes that {@code mCallingIdentity} has been properly set to reflect the calling
     * package.
     */
    private boolean isCallingIdentitySharedPackageName(@NonNull String packageName) {
        for (String sharedPkgName : mCallingIdentity.get().getSharedPackageNames()) {
            if (packageName.toLowerCase(Locale.ROOT)
                    .equals(sharedPkgName.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @throws IllegalStateException if path is invalid or doesn't match a volume.
     */
    @NonNull
    private Uri getContentUriForFile(@NonNull String filePath, @NonNull String mimeType) {
        final String volName;
        try {
            volName = FileUtils.getVolumeName(getContext(), new File(filePath));
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Couldn't get volume name for " + filePath);
        }
        Uri uri = Files.getContentUri(volName);
        final String topLevelDir = extractTopLevelDir(filePath);
        if (topLevelDir == null) {
            // If the file path doesn't match the external storage directory, we use the files URI
            // as default and let #insert enforce the restrictions
            return uri;
        }
        switch (topLevelDir) {
            case DIRECTORY_PODCASTS:
            case DIRECTORY_RINGTONES:
            case DIRECTORY_ALARMS:
            case DIRECTORY_NOTIFICATIONS:
            case DIRECTORY_AUDIOBOOKS:
                uri = Audio.Media.getContentUri(volName);
                break;
            case DIRECTORY_MUSIC:
                if (MimeUtils.isPlaylistMimeType(mimeType)) {
                    uri = Audio.Playlists.getContentUri(volName);
                } else if (!MimeUtils.isSubtitleMimeType(mimeType)) {
                    // Send Files uri for media type subtitle
                    uri = Audio.Media.getContentUri(volName);
                }
                break;
            case DIRECTORY_MOVIES:
                if (MimeUtils.isPlaylistMimeType(mimeType)) {
                    uri = Audio.Playlists.getContentUri(volName);
                } else if (!MimeUtils.isSubtitleMimeType(mimeType)) {
                    // Send Files uri for media type subtitle
                    uri = Video.Media.getContentUri(volName);
                }
                break;
            case DIRECTORY_DCIM:
            case DIRECTORY_PICTURES:
                if (MimeUtils.isImageMimeType(mimeType)) {
                    uri = Images.Media.getContentUri(volName);
                } else {
                    uri = Video.Media.getContentUri(volName);
                }
                break;
            case DIRECTORY_DOWNLOADS:
            case DIRECTORY_DOCUMENTS:
                break;
            default:
                Log.w(TAG, "Forgot to handle a top level directory in getContentUriForFile?");
        }
        return uri;
    }

    private boolean fileExists(@NonNull String absolutePath) {
        // We don't care about specific columns in the match,
        // we just want to check IF there's a match
        final String[] projection = {};
        final String selection = FileColumns.DATA + " = ?";
        final String[] selectionArgs = {absolutePath};
        final Uri uri = FileUtils.getContentUriForPath(absolutePath);

        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try {
            try (final Cursor c = query(uri, projection, selection, selectionArgs, null)) {
                // Shouldn't return null
                return c.getCount() > 0;
            }
        } finally {
            clearLocalCallingIdentity(token);
        }
    }

    private boolean isExternalMediaDirectory(@NonNull String path) {
        final String relativePath = extractRelativePath(path);
        if (relativePath != null) {
            return relativePath.startsWith("Android/media");
        }
        return false;
    }

    private Uri insertFileForFuse(@NonNull String path, @NonNull Uri uri, @NonNull String mimeType,
            boolean useData) {
        ContentValues values = new ContentValues();
        values.put(FileColumns.OWNER_PACKAGE_NAME, getCallingPackageOrSelf());
        values.put(MediaColumns.MIME_TYPE, mimeType);
        values.put(FileColumns.IS_PENDING, 1);

        if (useData) {
            values.put(FileColumns.DATA, path);
        } else {
            values.put(FileColumns.VOLUME_NAME, extractVolumeName(path));
            values.put(FileColumns.RELATIVE_PATH, extractRelativePath(path));
            values.put(FileColumns.DISPLAY_NAME, extractDisplayName(path));
        }
        return insert(uri, values, Bundle.EMPTY);
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
     * appropriate {@code errno} value:
     * <ul>
     * <li>{@link OsConstants#ENOENT} if the app tries to create file in other app's external dir
     * <li>{@link OsConstants#EEXIST} if the file already exists
     * <li>{@link OsConstants#EPERM} if the file type doesn't match the relative path, or if the
     * calling package is a legacy app that doesn't have WRITE_EXTERNAL_STORAGE permission.
     * <li>{@link OsConstants#EIO} in case of any other I/O exception
     * </ul>
     *
     * @throws IllegalStateException if given path is invalid.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int insertFileIfNecessaryForFuse(@NonNull String path, int uid) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));

        try {
            if (isPrivatePackagePathNotOwnedByCaller(path)) {
                Log.e(TAG, "Can't create a file in another app's external directory");
                return OsConstants.ENOENT;
            }

            if (!path.equals(getAbsoluteSanitizedPath(path))) {
                Log.e(TAG, "File name contains invalid characters");
                return OsConstants.EPERM;
            }

            if (shouldBypassDatabaseAndSetDirtyForFuse(uid, path)) {
                if (path.endsWith("/.nomedia")) {
                    File parent = new File(path).getParentFile();
                    synchronized (mNonHiddenPaths) {
                        mNonHiddenPaths.keySet().removeIf(
                                k -> FileUtils.contains(parent, new File(k)));
                    }
                }
                return 0;
            }

            final String mimeType = MimeUtils.resolveMimeType(new File(path));

            if (shouldBypassFuseRestrictions(/*forWrite*/ true, path)) {
                final boolean callerRequestingLegacy = isCallingPackageRequestingLegacy();
                if (!fileExists(path)) {
                    // If app has already inserted the db row, inserting the row again might set
                    // IS_PENDING=1. We shouldn't overwrite existing entry as part of FUSE
                    // operation, hence, insert the db row only when it doesn't exist.
                    try {
                        insertFileForFuse(path, FileUtils.getContentUriForPath(path),
                                mimeType, /*useData*/ callerRequestingLegacy);
                    } catch (Exception ignored) {
                    }
                } else {
                    // Upon creating a file via FUSE, if a row matching the path already exists
                    // but a file doesn't exist on the filesystem, we transfer ownership to the
                    // app attempting to create the file. If we don't update ownership, then the
                    // app that inserted the original row may be able to observe the contents of
                    // written file even though they don't hold the right permissions to do so.
                    if (callerRequestingLegacy) {
                        final String owner = getCallingPackageOrSelf();
                        if (owner != null && !updateOwnerForPath(path, owner)) {
                            return OsConstants.EPERM;
                        }
                    }
                }

                return 0;
            }

            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EPERM;
            }

            if (fileExists(path)) {
                // If the file already exists in the db, we shouldn't allow the file creation.
                return OsConstants.EEXIST;
            }

            final Uri contentUri = getContentUriForFile(path, mimeType);
            final Uri item = insertFileForFuse(path, contentUri, mimeType, /*useData*/ false);
            if (item == null) {
                return OsConstants.EPERM;
            }
            return 0;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "insertFileIfNecessary failed", e);
            return OsConstants.EPERM;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    private boolean updateOwnerForPath(@NonNull String path, @NonNull String newOwner) {
        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(FileUtils.getContentUriForPath(path));
        } catch (VolumeNotFoundException e) {
            // Cannot happen, as this is a path that we already resolved.
            throw new AssertionError("Path must already be resolved", e);
        }

        ContentValues values = new ContentValues(1);
        values.put(FileColumns.OWNER_PACKAGE_NAME, newOwner);

        return helper.runWithoutTransaction((db) -> {
            return db.update("files", values, "_data=?", new String[] { path });
        }) == 1;
    }

    private static int deleteFileUnchecked(@NonNull String path) {
        final File toDelete = new File(path);
        if (toDelete.delete()) {
            return 0;
        } else {
            return OsConstants.ENOENT;
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
     * <li>{@link OsConstants#ENOENT} if the file does not exist or if the app tries to delete file
     * in another app's external dir
     * <li>{@link OsConstants#EPERM} a security exception was thrown by {@link #delete}, or if the
     * calling package is a legacy app that doesn't have WRITE_EXTERNAL_STORAGE permission.
     * </ul>
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int deleteFileForFuse(@NonNull String path, int uid) throws IOException {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));
        try {
            if (isPrivatePackagePathNotOwnedByCaller(path)) {
                Log.e(TAG, "Can't delete a file in another app's external directory!");
                return OsConstants.ENOENT;
            }

            if (shouldBypassDatabaseAndSetDirtyForFuse(uid, path)) {
                return deleteFileUnchecked(path);
            }

            final boolean shouldBypass = shouldBypassFuseRestrictions(/*forWrite*/ true, path);

            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (!shouldBypass && isCallingPackageRequestingLegacy()) {
                return OsConstants.EPERM;
            }

            final Uri contentUri = FileUtils.getContentUriForPath(path);
            final String where = FileColumns.DATA + " = ?";
            final String[] whereArgs = {path};

            if (delete(contentUri, where, whereArgs) == 0) {
                if (shouldBypass) {
                    return deleteFileUnchecked(path);
                }
                return OsConstants.ENOENT;
            } else {
                // success - 1 file was deleted
                return 0;
            }

        } catch (SecurityException e) {
            Log.e(TAG, "File deletion not allowed", e);
            return OsConstants.EPERM;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Checks if the app with the given UID is allowed to create or delete the directory with the
     * given path.
     *
     * @param path File path of the directory that the app wants to create/delete
     * @param uid UID of the app that wants to create/delete the directory
     * @param forCreate denotes whether the operation is directory creation or deletion
     * @return 0 if the operation is allowed, or the following {@code errno} values:
     * <ul>
     * <li>{@link OsConstants#EACCES} if the app tries to create/delete a dir in another app's
     * external directory, or if the calling package is a legacy app that doesn't have
     * WRITE_EXTERNAL_STORAGE permission.
     * <li>{@link OsConstants#EPERM} if the app tries to create/delete a top-level directory.
     * </ul>
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int isDirectoryCreationOrDeletionAllowedForFuse(
            @NonNull String path, int uid, boolean forCreate) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));

        try {
            // App dirs are not indexed, so we don't create an entry for the file.
            if (isPrivatePackagePathNotOwnedByCaller(path)) {
                Log.e(TAG, "Can't modify another app's external directory!");
                return OsConstants.EACCES;
            }

            if (shouldBypassFuseRestrictions(/*forWrite*/ true, path)) {
                return 0;
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EACCES;
            }

            final String[] relativePath = sanitizePath(extractRelativePath(path));
            final boolean isTopLevelDir =
                    relativePath.length == 1 && TextUtils.isEmpty(relativePath[0]);
            if (isTopLevelDir) {
                // We allow creating the default top level directories only, all other operations on
                // top level directories are not allowed.
                if (forCreate && FileUtils.isDefaultDirectoryName(extractDisplayName(path))) {
                    return 0;
                }
                Log.e(TAG,
                        "Creating a non-default top level directory or deleting an existing"
                                + " one is not allowed!");
                return OsConstants.EPERM;
            }
            return 0;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Checks whether the app with the given UID is allowed to open the directory denoted by the
     * given path.
     *
     * @param path directory's path
     * @param uid UID of the requesting app
     * @return 0 if it's allowed to open the diretory, {@link OsConstants#EACCES} if the calling
     * package is a legacy app that doesn't have READ_EXTERNAL_STORAGE permission,
     * {@link OsConstants#ENOENT}  otherwise.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int isOpendirAllowedForFuse(@NonNull String path, int uid, boolean forWrite) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));
        try {
            if ("/storage/emulated".equals(path)) {
                return OsConstants.EPERM;
            }
            if (isPrivatePackagePathNotOwnedByCaller(path)) {
                Log.e(TAG, "Can't access another app's external directory!");
                return OsConstants.ENOENT;
            }

            // Do not allow apps to open Android/data or Android/obb dirs. Installer and
            // MOUNT_EXTERNAL_ANDROID_WRITABLE apps won't be blocked by this, as their OBB dirs
            // are mounted to lowerfs directly.
            if (isDataOrObbPath(path)) {
                return OsConstants.EACCES;
            }

            if (shouldBypassFuseRestrictions(forWrite, path)) {
                return 0;
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EACCES;
            }
            // This is a non-legacy app. Rest of the directories are generally writable
            // except for non-default top-level directories.
            if (forWrite) {
                final String[] relativePath = sanitizePath(extractRelativePath(path));
                if (relativePath.length == 0) {
                    Log.e(TAG, "Directoy write not allowed on invalid relative path for " + path);
                    return OsConstants.EPERM;
                }
                final boolean isTopLevelDir =
                        relativePath.length == 1 && TextUtils.isEmpty(relativePath[0]);
                if (isTopLevelDir) {
                    if (FileUtils.isDefaultDirectoryName(extractDisplayName(path))) {
                        return 0;
                    } else {
                        Log.e(TAG,
                                "Writing to a non-default top level directory is not allowed!");
                        return OsConstants.EACCES;
                    }
                }
            }

            return 0;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    @Keep
    public boolean isUidForPackageForFuse(@NonNull String packageName, int uid) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(getCachedCallingIdentityForFuse(uid));
        try {
            return isCallingIdentitySharedPackageName(packageName) ||
                    isCallingIdentityAllowedPrivAppAccess(uid);
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * External Storage Provider and Download Provider can access priv app directories.
     *
     * @param uid UID of the calling package
     */
    private boolean isCallingIdentityAllowedPrivAppAccess(int uid) {
        return (uid == mExternalStorageAuthorityAppId) || (uid == mDownloadsAuthorityAppId);
    }

    private boolean checkCallingPermissionGlobal(Uri uri, boolean forWrite) {
        // System internals can work with all media
        if (isCallingPackageSelf() || isCallingPackageShell()) {
            return true;
        }

        // Apps that have permission to manage external storage can work with all files
        if (isCallingPackageManager()) {
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

    @VisibleForTesting
    public boolean isFuseThread() {
        return FuseDaemon.native_is_fuse_thread();
    }

    @Deprecated
    private boolean checkCallingPermissionAudio(boolean forWrite, String callingPackage) {
        if (forWrite) {
            return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_AUDIO);
        } else {
            // write permission should be enough for reading as well
            return mCallingIdentity.get().hasPermission(PERMISSION_READ_AUDIO)
                    || mCallingIdentity.get().hasPermission(PERMISSION_WRITE_AUDIO);
        }
    }

    @Deprecated
    private boolean checkCallingPermissionVideo(boolean forWrite, String callingPackage) {
        if (forWrite) {
            return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_VIDEO);
        } else {
            // write permission should be enough for reading as well
            return mCallingIdentity.get().hasPermission(PERMISSION_READ_VIDEO)
                    || mCallingIdentity.get().hasPermission(PERMISSION_WRITE_VIDEO);
        }
    }

    @Deprecated
    private boolean checkCallingPermissionImages(boolean forWrite, String callingPackage) {
        if (forWrite) {
            return mCallingIdentity.get().hasPermission(PERMISSION_WRITE_IMAGES);
        } else {
            // write permission should be enough for reading as well
            return mCallingIdentity.get().hasPermission(PERMISSION_READ_IMAGES)
                    || mCallingIdentity.get().hasPermission(PERMISSION_WRITE_IMAGES);
        }
    }

    /**
     * Enforce that caller has access to the given {@link Uri}.
     *
     * @throws SecurityException if access isn't allowed.
     */
    private void enforceCallingPermission(@NonNull Uri uri, @NonNull Bundle extras,
            boolean forWrite) {
        Trace.beginSection("enforceCallingPermission");
        try {
            enforceCallingPermissionInternal(uri, extras, forWrite);
        } finally {
            Trace.endSection();
        }
    }

    private void enforceCallingPermissionInternal(@NonNull Uri uri, @NonNull Bundle extras,
            boolean forWrite) {
        Objects.requireNonNull(uri);
        Objects.requireNonNull(extras);

        // Try a simple global check first before falling back to performing a
        // simple query to probe for access.
        if (checkCallingPermissionGlobal(uri, forWrite)) {
            // Access allowed, yay!
            return;
        }

        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(uri);
        } catch (VolumeNotFoundException e) {
            throw e.rethrowAsIllegalArgumentException();
        }

        final boolean allowHidden = isCallingPackageAllowedHidden();
        final int table = matchUri(uri, allowHidden);

        // First, check to see if caller has direct write access
        if (forWrite) {
            final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE, table, uri, extras, null);
            qb.allowRowidColumn();
            try (Cursor c = qb.query(helper, new String[] { SQLiteQueryBuilder.ROWID_COLUMN },
                    null, null, null, null, null, null, null)) {
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
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_QUERY, table, uri, extras, null);
        qb.allowRowidColumn();
        try (Cursor c = qb.query(helper, new String[] { SQLiteQueryBuilder.ROWID_COLUMN },
                null, null, null, null, null, null, null)) {
            if (c.moveToFirst()) {
                if (!forWrite) {
                    // Direct read access granted, yay!
                    return;
                } else if (allowUserGrant) {
                    // Caller has read access, but they wanted to write, and
                    // they'll need to get the user to grant that access
                    final Context context = getContext();
                    final Collection<Uri> uris = Arrays.asList(uri);
                    final PendingIntent intent = MediaStore
                            .createWriteRequest(ContentResolver.wrap(this), uris);

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

    private void checkAccess(@NonNull Uri uri, @NonNull Bundle extras, @NonNull File file,
            boolean isWrite) throws FileNotFoundException {
        // First, does caller have the needed row-level access?
        enforceCallingPermission(uri, extras, isWrite);

        // Second, does the path look sane?
        if (!FileUtils.contains(Environment.getStorageDirectory(), file)) {
            checkWorldReadAccess(file.getAbsolutePath());
        }
    }

    /**
     * Check whether the path is a world-readable file
     */
    @VisibleForTesting
    public static void checkWorldReadAccess(String path) throws FileNotFoundException {
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

    @VisibleForTesting
    static class FallbackException extends Exception {
        private final int mThrowSdkVersion;

        public FallbackException(String message, int throwSdkVersion) {
            super(message);
            mThrowSdkVersion = throwSdkVersion;
        }

        public FallbackException(String message, Throwable cause, int throwSdkVersion) {
            super(message, cause);
            mThrowSdkVersion = throwSdkVersion;
        }

        @Override
        public String getMessage() {
            if (getCause() != null) {
                return super.getMessage() + ": " + getCause().getMessage();
            } else {
                return super.getMessage();
            }
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

        public int translateForBulkInsert(int targetSdkVersion) {
            if (targetSdkVersion >= mThrowSdkVersion) {
                throw new IllegalArgumentException(getMessage());
            } else {
                Log.w(TAG, getMessage());
                return 0;
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

    @VisibleForTesting
    static class VolumeNotFoundException extends FallbackException {
        public VolumeNotFoundException(String volumeName) {
            super("Volume " + volumeName + " not found", Build.VERSION_CODES.Q);
        }
    }

    @VisibleForTesting
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

    private @NonNull Uri getBaseContentUri(@NonNull String volumeName) {
        return MediaStore.AUTHORITY_URI.buildUpon().appendPath(volumeName).build();
    }

    public Uri attachVolume(String volume, boolean validate) {
        if (mCallingIdentity.get().pid != android.os.Process.myPid()) {
            throw new SecurityException(
                    "Opening and closing databases not allowed.");
        }

        // Quick sanity check for shady volume names
        MediaStore.checkArgumentVolumeName(volume);

        // Quick sanity check that volume actually exists
        if (!MediaStore.VOLUME_INTERNAL.equals(volume) && validate) {
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

        final ContentResolver resolver = getContext().getContentResolver();
        final Uri uri = getBaseContentUri(volume);
        resolver.notifyChange(getBaseContentUri(volume), null);

        if (LOGV) Log.v(TAG, "Attached volume: " + volume);
        if (!MediaStore.VOLUME_INTERNAL.equals(volume)) {
            // Also notify on synthetic view of all devices
            resolver.notifyChange(getBaseContentUri(MediaStore.VOLUME_EXTERNAL), null);

            ForegroundThread.getExecutor().execute(() -> {
                final DatabaseHelper helper = MediaStore.VOLUME_INTERNAL.equals(volume)
                        ? mInternalDatabase : mExternalDatabase;
                helper.runWithTransaction((db) -> {
                    ensureDefaultFolders(volume, db);
                    ensureThumbnailsValid(volume, db);
                    return null;
                });

                // We just finished the database operation above, we know that
                // it's ready to answer queries, so notify our DocumentProvider
                // so it can answer queries without risking ANR
                MediaDocumentsProvider.onMediaStoreReady(getContext(), volume);
            });
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

        final ContentResolver resolver = getContext().getContentResolver();
        final Uri uri = getBaseContentUri(volume);
        resolver.notifyChange(getBaseContentUri(volume), null);

        if (!MediaStore.VOLUME_INTERNAL.equals(volume)) {
            // Also notify on synthetic view of all devices
            resolver.notifyChange(getBaseContentUri(MediaStore.VOLUME_EXTERNAL), null);
        }

        if (LOGV) Log.v(TAG, "Detached volume: " + volume);
    }

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
    static final int IMAGES_MEDIA = 1;
    static final int IMAGES_MEDIA_ID = 2;
    static final int IMAGES_MEDIA_ID_THUMBNAIL = 3;
    static final int IMAGES_THUMBNAILS = 4;
    static final int IMAGES_THUMBNAILS_ID = 5;

    static final int AUDIO_MEDIA = 100;
    static final int AUDIO_MEDIA_ID = 101;
    static final int AUDIO_MEDIA_ID_GENRES = 102;
    static final int AUDIO_MEDIA_ID_GENRES_ID = 103;
    static final int AUDIO_GENRES = 106;
    static final int AUDIO_GENRES_ID = 107;
    static final int AUDIO_GENRES_ID_MEMBERS = 108;
    static final int AUDIO_GENRES_ALL_MEMBERS = 109;
    static final int AUDIO_PLAYLISTS = 110;
    static final int AUDIO_PLAYLISTS_ID = 111;
    static final int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    static final int AUDIO_PLAYLISTS_ID_MEMBERS_ID = 113;
    static final int AUDIO_ARTISTS = 114;
    static final int AUDIO_ARTISTS_ID = 115;
    static final int AUDIO_ALBUMS = 116;
    static final int AUDIO_ALBUMS_ID = 117;
    static final int AUDIO_ARTISTS_ID_ALBUMS = 118;
    static final int AUDIO_ALBUMART = 119;
    static final int AUDIO_ALBUMART_ID = 120;
    static final int AUDIO_ALBUMART_FILE_ID = 121;

    static final int VIDEO_MEDIA = 200;
    static final int VIDEO_MEDIA_ID = 201;
    static final int VIDEO_MEDIA_ID_THUMBNAIL = 202;
    static final int VIDEO_THUMBNAILS = 203;
    static final int VIDEO_THUMBNAILS_ID = 204;

    static final int VOLUMES = 300;
    static final int VOLUMES_ID = 301;

    static final int MEDIA_SCANNER = 500;

    static final int FS_ID = 600;
    static final int VERSION = 601;

    static final int FILES = 700;
    static final int FILES_ID = 701;

    static final int DOWNLOADS = 800;
    static final int DOWNLOADS_ID = 801;

    private LocalUriMatcher mUriMatcher;

    private static final String[] PATH_PROJECTION = new String[] {
        MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
    };

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

            mPublic.addURI(auth, "*/file", FILES);
            mPublic.addURI(auth, "*/file/#", FILES_ID);

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
        sMutableColumns.add(MediaStore.MediaColumns.IS_FAVORITE);
        sMutableColumns.add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);

        sMutableColumns.add(MediaStore.Audio.AudioColumns.BOOKMARK);

        sMutableColumns.add(MediaStore.Video.VideoColumns.TAGS);
        sMutableColumns.add(MediaStore.Video.VideoColumns.CATEGORY);
        sMutableColumns.add(MediaStore.Video.VideoColumns.BOOKMARK);

        sMutableColumns.add(MediaStore.Audio.Playlists.NAME);
        sMutableColumns.add(MediaStore.Audio.Playlists.Members.AUDIO_ID);
        sMutableColumns.add(MediaStore.Audio.Playlists.Members.PLAY_ORDER);

        sMutableColumns.add(MediaStore.DownloadColumns.DOWNLOAD_URI);
        sMutableColumns.add(MediaStore.DownloadColumns.REFERER_URI);

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
        sPlacementColumns.add(MediaStore.MediaColumns.IS_PENDING);
        sPlacementColumns.add(MediaStore.MediaColumns.IS_TRASHED);
        sPlacementColumns.add(MediaStore.MediaColumns.DATE_EXPIRES);
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

    public ArrayMap<String, String> getProjectionMap(Class<?>... clazzes) {
        return mExternalDatabase.getProjectionMap(clazzes);
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

    private boolean isCallingPackageSystemGallery() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_SYSTEM_GALLERY);
    }

    @Deprecated
    private String getCallingPackageOrSelf() {
        return mCallingIdentity.get().getPackageName();
    }

    @Deprecated
    @VisibleForTesting
    public int getCallingPackageTargetSdkVersion() {
        return mCallingIdentity.get().getTargetSdkVersion();
    }

    @Deprecated
    private boolean isCallingPackageAllowedHidden() {
        return isCallingPackageSelf();
    }

    @Deprecated
    private boolean isCallingPackageSelf() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_SELF);
    }

    @Deprecated
    private boolean isCallingPackageShell() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_SHELL);
    }

    @Deprecated
    private boolean isCallingPackageManager() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_MANAGER);
    }

    @Deprecated
    private boolean isCallingPackageDelegator() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_DELEGATOR);
    }

    @Deprecated
    private boolean isCallingPackageLegacyRead() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY_READ);
    }

    @Deprecated
    private boolean isCallingPackageLegacyWrite() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY_WRITE);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("mThumbSize=" + mThumbSize);
        synchronized (mAttachedVolumeNames) {
            writer.println("mAttachedVolumeNames=" + mAttachedVolumeNames);
        }
        writer.println();

        Logging.dumpPersistent(writer);
    }
}
