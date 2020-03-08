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
import static android.content.ContentResolver.QUERY_ARG_SQL_GROUP_BY;
import static android.content.ContentResolver.QUERY_ARG_SQL_HAVING;
import static android.content.ContentResolver.QUERY_ARG_SQL_LIMIT;
import static android.content.ContentResolver.QUERY_ARG_SQL_SELECTION;
import static android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS;
import static android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER;
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

import static com.android.providers.media.DatabaseHelper.EXTERNAL_DATABASE_NAME;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DATABASE_NAME;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_BACKUP;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY_GRANTED;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY_READ;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_LEGACY_WRITE;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_IS_SYSTEM;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_MANAGE_EXTERNAL_STORAGE;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_AUDIO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_IMAGES;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_READ_VIDEO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_AUDIO;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_IMAGES;
import static com.android.providers.media.LocalCallingIdentity.PERMISSION_WRITE_VIDEO;
import static com.android.providers.media.scan.MediaScanner.REASON_DEMAND;
import static com.android.providers.media.scan.MediaScanner.REASON_IDLE;
import static com.android.providers.media.util.DatabaseUtils.bindList;
import static com.android.providers.media.util.FileUtils.computeDataValues;
import static com.android.providers.media.util.FileUtils.extractDisplayName;
import static com.android.providers.media.util.FileUtils.extractFileName;
import static com.android.providers.media.util.FileUtils.extractPathOwnerPackageName;
import static com.android.providers.media.util.FileUtils.extractRelativePath;
import static com.android.providers.media.util.FileUtils.extractRelativePathForDirectory;
import static com.android.providers.media.util.FileUtils.extractTopLevelDir;
import static com.android.providers.media.util.FileUtils.extractVolumeName;
import static com.android.providers.media.util.FileUtils.isDownload;
import static com.android.providers.media.util.Logging.LOGV;
import static com.android.providers.media.util.Logging.TAG;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManageExternalStorage;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpActiveChangedListener;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.app.RemoteAction;
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

import androidx.annotation.GuardedBy;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.DatabaseHelper.OnFilesChangeListener;
import com.android.providers.media.DatabaseHelper.OnLegacyMigrationListener;
import com.android.providers.media.fuse.ExternalStorageServiceImpl;
import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.scan.NullMediaScanner;
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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Specify what default directories the caller gets full access to. By default, the caller
     * shouldn't get full access to any default dirs.
     * But for example, we do an exception for System Gallery apps and allow them full access to:
     * DCIM, Pictures, Movies.
     */
    private static final String INCLUDED_DEFAULT_DIRECTORIES =
            "android:included-default-directories";

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
    private static final Map<String, Collection<File>> sCachedVolumeScanPaths = new ArrayMap<>();

    private void updateVolumes() {
        synchronized (sCacheLock) {
            sCachedExternalVolumeNames.clear();
            sCachedExternalVolumeNames.addAll(MediaStore.getExternalVolumeNames(getContext()));

            sCachedVolumeScanPaths.clear();
            try {
                sCachedVolumeScanPaths.put(MediaStore.VOLUME_INTERNAL,
                        FileUtils.getVolumeScanPaths(getContext(), MediaStore.VOLUME_INTERNAL));
                for (String volumeName : sCachedExternalVolumeNames) {
                    sCachedVolumeScanPaths.put(volumeName,
                            FileUtils.getVolumeScanPaths(getContext(), volumeName));
                }
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }

        // Update filters to reflect mounted volumes so users don't get
        // confused by metadata from ejected volumes
        BackgroundThread.getExecutor().execute(() -> {
            mExternalDatabase.setFilterVolumeNames(getExternalVolumeNames());
        });
    }

    public File getVolumePath(String volumeName) throws FileNotFoundException {
        // TODO(b/144275217): A more performant invocation is
        // MediaStore#getVolumePath(sCachedVolumes, volumeName) since we avoid a binder
        // to StorageManagerService to getVolumeList. We need to delay the mount broadcasts
        // from StorageManagerService so that sCachedVolumes is up to date in
        // onVolumeStateChanged before we to call this method, otherwise we would crash
        // when we don't find volumeName yet

        // Ugly hack to keep unit tests passing, where we don't always have a
        // Context to discover volumes with
        if (getContext() == null) {
            return Environment.getExternalStorageDirectory();
        }

        return FileUtils.getVolumePath(getContext(), volumeName);
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

    /**
     * We simply propagate the UID that is being tracked by
     * {@link LocalCallingIdentity}, which means we accurately blame both
     * incoming Binder calls and FUSE calls.
     */
    private final ProxyTransactListener mTransactListener = new ProxyTransactListener() {
        @Override
        public Object onTransactStarted(IBinder binder, int transactionCode) {
            final int uid = mCallingIdentity.get().uid;
            return Binder.setCallingWorkSourceUid(uid);
        }

        @Override
        public void onTransactEnded(Object session) {
            final long token = (long) session;
            Binder.restoreCallingWorkSource(token);
        }
    };

    // In memory cache of path<->id mappings, to speed up inserts during media scan
    @GuardedBy("mDirectoryCache")
    private final ArrayMap<String, Long> mDirectoryCache = new ArrayMap<>();

    private static final String[] sDataOnlyColumn = new String[] {
        FileColumns.DATA
    };

    private static final String[] sPlaylistIdPlayOrder = new String[] {
        Playlists.Members.PLAYLIST_ID,
        Playlists.Members.PLAY_ORDER
    };

    private static final String ID_NOT_PARENT_CLAUSE =
            "_id NOT IN (SELECT parent FROM files WHERE parent IS NOT NULL)";

    private static final String CANONICAL = "canonical";

    private BroadcastReceiver mMediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final StorageVolume sv = intent.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
            try {
                final String volumeName;
                if (sv.isPrimary()) {
                    volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;
                } else {
                    try {
                        volumeName = MediaStore
                                .checkArgumentVolumeName(sv.getMediaStoreVolumeName());
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
            } catch (Exception e) {
                Log.w(TAG, "Failed to handle broadcast " + intent, e);
            }
        }
    };

    private final void updateQuotaTypeForUri(@NonNull Uri uri, int mediaType) {
        File file;
        try {
            file = queryForDataFile(uri, null);
        } catch (FileNotFoundException e) {
            // Ignore
            return;
        }
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

    private final OnFilesChangeListener mFilesListener = new OnFilesChangeListener() {
        @Override
        public void onInsert(@NonNull DatabaseHelper helper, @NonNull String volumeName, long id,
                int mediaType, boolean isDownload) {
            acceptWithExpansion(helper::notifyInsert, volumeName, id, mediaType, isDownload);

            if (helper.isExternal()) {
                // Update the quota type on the filesystem
                Uri fileUri = MediaStore.Files.getContentUri(volumeName, id);
                updateQuotaTypeForUri(fileUri, mediaType);
            }

            // Tell our SAF provider so it knows when views are no longer empty
            MediaDocumentsProvider.onMediaStoreInsert(getContext(), volumeName, mediaType, id);
        }

        @Override
        public void onUpdate(@NonNull DatabaseHelper helper, @NonNull String volumeName, long id,
                int oldMediaType, boolean oldIsDownload,
                int newMediaType, boolean newIsDownload) {
            final boolean isDownload = oldIsDownload || newIsDownload;
            acceptWithExpansion(helper::notifyUpdate, volumeName, id, oldMediaType, isDownload);

            // When media type changes, notify both old and new collections and
            // invalidate any thumbnails
            if (newMediaType != oldMediaType) {
                Uri fileUri = MediaStore.Files.getContentUri(volumeName, id);
                if (helper.isExternal()) {
                    updateQuotaTypeForUri(fileUri, newMediaType);
                }
                acceptWithExpansion(helper::notifyUpdate, volumeName, id, newMediaType, isDownload);
                invalidateThumbnails(fileUri);
            }
        }

        @Override
        public void onDelete(@NonNull DatabaseHelper helper, @NonNull String volumeName, long id,
                int mediaType, boolean isDownload) {
            // Both notify apps and revoke any outstanding permission grants
            final Context context = getContext();
            acceptWithExpansion((uri) -> {
                helper.notifyDelete(uri);
                context.revokeUriPermission(uri, ~0);
            }, volumeName, id, mediaType, isDownload);

            // Invalidate any thumbnails now that media is gone
            invalidateThumbnails(MediaStore.Files.getContentUri(volumeName, id));

            // Tell our SAF provider so it can revoke too
            MediaDocumentsProvider.onMediaStoreDelete(getContext(), volumeName, mediaType, id);
        }
    };

    private final OnLegacyMigrationListener mMigrationListener = new OnLegacyMigrationListener() {
        @Override
        public void onStarted(ContentProviderClient client, String volumeName) {
            MediaStore.startLegacyMigration(ContentResolver.wrap(client), volumeName);
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
            Environment.DIRECTORY_AUDIOBOOKS,
            Environment.DIRECTORY_DOCUMENTS,
    };

    private static boolean isDefaultDirectoryName(@Nullable String dirName) {
        for (String defaultDirName : sDefaultFolderNames) {
            if (defaultDirName.equals(dirName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensure that default folders are created on mounted primary storage
     * devices. We only do this once per volume so we don't annoy the user if
     * deleted manually.
     */
    private void ensureDefaultFolders(String volumeName, DatabaseHelper helper) {
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
                for (String folderName : sDefaultFolderNames) {
                    final File folder = new File(vol.getDirectory(), folderName);
                    if (!folder.exists()) {
                        folder.mkdirs();
                        insertDirectory(helper, folder.getAbsolutePath());
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
        Log.v(TAG, "Attached " + info.authority + " from " + info.applicationInfo.packageName);

        mLegacyProvider = Objects.equals(info.authority, MediaStore.AUTHORITY_LEGACY);
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

        // Reasonable thumbnail size is half of the smallest screen edge width
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int thumbSize = Math.min(metrics.widthPixels, metrics.heightPixels) / 2;
        mThumbSize = new Size(thumbSize, thumbSize);

        if (mLegacyProvider) {
            // When running in legacy mode, we're simply keeping the old
            // database intact, and so we should perform no scanning operations
            mMediaScanner = new NullMediaScanner(context);
        } else {
            mMediaScanner = new ModernMediaScanner(context);
        }

        mInternalDatabase = new DatabaseHelper(context, INTERNAL_DATABASE_NAME,
                true, false, mLegacyProvider, Column.class,
                Metrics::logSchemaChange, mFilesListener, mMigrationListener);
        mExternalDatabase = new DatabaseHelper(context, EXTERNAL_DATABASE_NAME,
                false, false, mLegacyProvider, Column.class,
                Metrics::logSchemaChange, mFilesListener, mMigrationListener);

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
        mStorageManager.registerStorageVolumeCallback(context.getMainExecutor(),
                new StorageVolumeCallback() {
                    @Override
                    public void onStateChanged(@NonNull StorageVolume volume) {
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

        final DatabaseHelper helper = mExternalDatabase;
        final SQLiteDatabase db = helper.getReadableDatabase();

        // Scan all volumes to resolve any staleness
        for (String volumeName : getExternalVolumeNames()) {
            // Possibly bail before digging into each volume
            signal.throwIfCanceled();

            try {
                MediaService.onScanVolume(getContext(), volumeName, REASON_IDLE);
            } catch (IOException e) {
                Log.w(TAG, e);
            }
        }

        // Delete any stale thumbnails
        final int staleThumbnails = pruneThumbnails(signal);
        Log.d(TAG, "Pruned " + staleThumbnails + " unknown thumbnails");

        // Finished orphaning any content whose package no longer exists
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
            onPackageOrphaned(packageName);
        }
        final int stalePackages = unknownPackages.size();
        Log.d(TAG, "Pruned " + stalePackages + " unknown packages");

        // Delete any expired content; we're paranoid about wildly changing
        // clocks, so only delete items within the last week
        final long from = ((System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS) / 1000);
        final long to = (System.currentTimeMillis() / 1000);
        final int expiredMedia;
        try (Cursor c = db.query(true, "files", new String[] { "volume_name", "_id" },
                FileColumns.DATE_EXPIRES + " BETWEEN " + from + " AND " + to, null,
                null, null, null, null, signal)) {
            while (c.moveToNext()) {
                final String volumeName = c.getString(0);
                final long id = c.getLong(1);
                delete(Files.getContentUri(volumeName, id), null, null);
            }
            expiredMedia = c.getCount();
            Log.d(TAG, "Deleted " + expiredMedia + " expired items on " + helper.mName);
        }

        // Forget any stale volumes
        final Set<String> recentVolumeNames = MediaStore.getRecentExternalVolumeNames(getContext());
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

        synchronized (mDirectoryCache) {
            mDirectoryCache.clear();
        }

        final long durationMillis = (SystemClock.elapsedRealtime() - startTime);
        Metrics.logIdleMaintenance(MediaStore.VOLUME_EXTERNAL, helper.getItemCount(),
                durationMillis, staleThumbnails, expiredMedia);
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

    public void scanDirectory(File file, int reason) {
        mMediaScanner.scanDirectory(file, reason);
    }

    public Uri scanFile(File file, int reason) {
        return mMediaScanner.scanFile(file, reason);
    }

    public Uri scanFile(File file, int reason, String ownerPackage) {
        return mMediaScanner.scanFile(file, reason, ownerPackage);
    }

    /**
     * Makes MediaScanner scan the given file.
     * @param file path of the file to be scanned
     * @param uid  UID of the app that owns the file on the given path. If the file is scanned
     *            on create, this UID will be used for updating owner package.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public void scanFileForFuse(String file, int uid) {
        final String callingPackage =
                LocalCallingIdentity.fromExternal(getContext(), uid).getPackageName();
        scanFile(new File(file), REASON_DEMAND, callingPackage);
    }

    /**
     * Returns true if the app denoted by the given {@code uid} and {@code packageName} is allowed
     * to clear other apps' cache directories.
     */
    static boolean hasPermissionToClearCaches(Context context, ApplicationInfo ai) {
        return checkPermissionManageExternalStorage(context, /*pid*/-1, ai.uid, ai.packageName);
    }

    /**
     * Clears all app's external cache directories, i.e. for each app we delete
     * /sdcard/Android/data/app/cache/* but we keep the directory itself.
     *
     * <p>This method doesn't perform any checks, so make sure that the calling package is allowed
     * to clear cache directories by calling {@link #hasPermissionToClearCaches} first.
     */
    static void clearAppCacheDirectories() {
        Log.i(TAG, "Clearing cache for all apps on");
        final File rootDataDir = FileUtils.buildPath(Environment.getExternalStorageDirectory(),
                DIRECTORY_ANDROID, "data");
        for (File appDataDir : rootDataDir.listFiles()) {
            try {
                final File appCacheDir = new File(appDataDir, "cache");
                if (appCacheDir.isDirectory()) {
                    FileUtils.deleteContents(appCacheDir);
                }
            } catch (Exception e) {
                // We want to avoid crashing MediaProvider at all costs, so we handle all "generic"
                // exceptions here.
                Log.e(TAG, "Couldn't delete all app cache dirs!", e);
            }
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
        final LocalCallingIdentity token = clearLocalCallingIdentity(
                LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            final String appSpecificDir = extractPathOwnerPackageName(path);
            // Apps are allowed to list files only in their own external directory.
            if (appSpecificDir != null) {
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return new String[] {"/"};
                } else {
                    return new String[] {""};
                }
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
            // Escape '(' & ')'to avoid regex conflicts
            relativePath = relativePath.replace("(","\\(").replace(")", "\\)");

            // Get database entries for files from MediaProvider database with
            // MediaColumns.RELATIVE_PATH as the given path.
            String[] projection = {MediaColumns.DISPLAY_NAME};
            Bundle queryArgs = new Bundle();
            queryArgs.putString(QUERY_ARG_SQL_SELECTION, MediaColumns.RELATIVE_PATH +
                    " =? and mime_type not like 'null'");
            queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, new String[] {relativePath});
            try (final Cursor cursor = query(Files.getContentUriForPath(path), projection,
                    queryArgs, null)) {
                while(cursor.moveToNext()) {
                    fileNamesList.add(cursor.getString(cursor.getColumnIndex(projection[0])));
                }
            }
            return fileNamesList.toArray(new String[fileNamesList.size()]);
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    /**
     * Checks if given {@code mimeType} is supported in {@code path}.
     */
    private boolean isMimeTypeSupportedInPath(String path, String mimeType) {
        final String supportedPrimaryMimeType;
        switch (matchUri(getContentUriForFile(path, mimeType), true)) {
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
        return (supportedPrimaryMimeType.equals(ClipDescription.MIMETYPE_UNKNOWN) ||
                mimeType.startsWith(supportedPrimaryMimeType));
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
        final Uri uriOldPath = Files.getContentUriForPath(oldPath);
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
            final Uri uriNewPath = Files.getContentUriForPath(oldPath);
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
    private ContentValues getContentValuesForFuseRename(String path, String oldMimeType,
            String newMimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaColumns.MIME_TYPE, newMimeType);
        values.put(MediaColumns.DATA, path);

        if (!oldMimeType.equals(newMimeType)) {
            int mediaType = MimeUtils.resolveMediaType(newMimeType);
            values.put(FileColumns.MEDIA_TYPE, mediaType);
        }
        final boolean allowHidden = isCallingPackageAllowedHidden();
        if (!newMimeType.equals("null") &&
                matchUri(getContentUriForFile(path, newMimeType), allowHidden) == AUDIO_MEDIA) {
            computeAudioLocalizedValues(values);
            computeAudioKeyValues(values);
        }
        computeDataValues(values);
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
        final String selection = MediaColumns.RELATIVE_PATH + " REGEXP '^" +
                extractRelativePathForDirectory(oldPath) + "/?.*' and mime_type not like 'null'";
        ArrayList<String> fileList = new ArrayList<>();

        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try (final Cursor c = query(Files.getContentUriForPath(oldPath),
                new String[] {MediaColumns.DATA}, selection, null, null)) {
            while (c.moveToNext()) {
                final String filePath = c.getString(0).replaceFirst("^" + oldPath + "/(.*)", "$1");
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
        final String selection = MediaColumns.RELATIVE_PATH + " REGEXP '^" +
                extractRelativePathForDirectory(oldPath) + "/?.*' and mime_type not like 'null'";
        final Uri uriOldPath = Files.getContentUriForPath(oldPath);

        final LocalCallingIdentity token = clearLocalCallingIdentity();
        try (final Cursor c = query(uriOldPath, new String[] {MediaColumns._ID}, selection, null,
                null)) {
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
        try (Cursor c = qb.query(helper, projection, selection, null,
                null, null, null, null, null)) {
            // Check if the calling package has write permission to all files in the given
            // directory. If calling package has write permission to all files in the directory, the
            // query with update uri should return same number of files as previous query.
            if (c.getCount() != countAllFilesInDirectory) {
                throw new IllegalArgumentException("Calling package doesn't have write permission "
                        + " to rename one or more files in " + oldPath);
            }
            while(c.moveToNext()) {
                final String filePath = c.getString(0).replaceFirst("^" + oldPath + "/(.*)", "$1");
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
            helper = getDatabaseForUri(Files.getContentUriForPath(oldPath));
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Volume not found while trying to update database for "
                    + oldPath, e);
        }

        helper.beginTransaction();
        try {
            final Bundle qbExtras = new Bundle();
            qbExtras.putStringArrayList(INCLUDED_DEFAULT_DIRECTORIES,
                    getIncludedDefaultDirectories());
            for (String filePath : fileList) {
                final String newFilePath = newPath + "/" + filePath;
                final String mimeType = MimeUtils.resolveMimeType(new File(newFilePath));
                if(!updateDatabaseForFuseRename(helper, oldPath + "/" + filePath, newFilePath,
                        getContentValuesForFuseRename(newFilePath, mimeType, mimeType), qbExtras)) {
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
        return renameFileUncheckedForFuse(oldPath, newPath);
    }

    private int renameFileUncheckedForFuse(String oldPath, String newPath) {
        final String newMimeType = MimeUtils.resolveMimeType(new File(newPath));
        final DatabaseHelper helper;
        try {
            helper = getDatabaseForUri(Files.getContentUriForPath(oldPath));
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Volume not found while trying to update database for"
                + oldPath + ". Rename failed due to database update error", e);
        }

        helper.beginTransaction();
        try {
            final String oldMimeType = MimeUtils.resolveMimeType(new File(oldPath));
            if (!updateDatabaseForFuseRename(helper, oldPath, newPath,
                    getContentValuesForFuseRename(newPath, oldMimeType, newMimeType))) {
                Log.e(TAG, "Calling package doesn't have write permission to rename file.");
                return OsConstants.EPERM;
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
        return 0;
    }

    /**
     * Rename file/directory without imposing any restrictions.
     *
     * We don't impose any rename restrictions for apps that bypass scoped storage restrictions.
     * However, we update database entries for renamed files to keep the database consistent.
     */
    private int renameUncheckedForFuse(String oldPath, String newPath) {

        return renameInLowerFs(oldPath, newPath);
        // TODO(b/145737191) Legacy apps don't expect FuseDaemon to update database.
        // Inserting/deleting the database entry might break app functionality.
        //if (new File(oldPath).isFile()) {
        //     return renameFileUncheckedForFuse(oldPath, newPath);
        // } else {
        //    return renameDirectoryUncheckedForFuse(oldPath, newPath,
        //            getAllFilesForRenameDirectory(oldPath));
        // }
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
        final LocalCallingIdentity token = clearLocalCallingIdentity(
                LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            final String oldPathPackageName = extractPathOwnerPackageName(oldPath);
            final String newPathPackageName = extractPathOwnerPackageName(newPath);

            if (oldPathPackageName != null && newPathPackageName != null) {
                if (isCallingIdentitySharedPackageName(oldPathPackageName) &&
                        isCallingIdentitySharedPackageName(newPathPackageName)) {
                    return renameInLowerFs(oldPath, newPath);
                } else {
                    return OsConstants.EACCES;
                }
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
            } else if (oldRelativePath.length == 1 && TextUtils.isEmpty(oldRelativePath[0])) {
                // Allow rename of files/folders other than default directories.
                final String displayName = extractDisplayName(oldPath);
                for (String defaultFolder : sDefaultFolderNames) {
                    if (displayName.equals(defaultFolder)) {
                        Log.e(TAG, errorMessage + oldPath + " is a default folder."
                                + " Renaming a default folder is not allowed.");
                        return OsConstants.EPERM;
                    }
                }
            } else if (newRelativePath.length == 1 && TextUtils.isEmpty(newRelativePath[0])) {
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
            if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                type = TYPE_UPDATE;
            } else {
                type = TYPE_QUERY;
            }

            final SQLiteQueryBuilder qb = getQueryBuilder(type, table, uri, Bundle.EMPTY, null);
            try (Cursor c = qb.query(helper,
                    new String[] { BaseColumns._ID }, null, null, null, null, null, null, null)) {
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
                DatabaseUtils.createSqlQueryBundle(selection, selectionArgs, sortOrder), null);
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
        queryArgs = (queryArgs != null) ? queryArgs : Bundle.EMPTY;

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

        final String selection = queryArgs.getString(QUERY_ARG_SQL_SELECTION);
        final String[] selectionArgs = queryArgs.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);
        final String groupBy = queryArgs.getString(QUERY_ARG_SQL_GROUP_BY);
        final String having = queryArgs.getString(QUERY_ARG_SQL_HAVING);
        final String sortOrder = queryArgs.getString(QUERY_ARG_SQL_SORT_ORDER);
        final String limit = queryArgs.getString(QUERY_ARG_SQL_LIMIT);

        final Cursor c = qb.query(helper, projection, selection, selectionArgs, groupBy, having,
                sortOrder, limit, signal);

        if (c != null) {
            // As a performance optimization, only configure notifications when
            // resulting cursor will leave our process
            if (mCallingIdentity.get().pid != android.os.Process.myPid()) {
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
    void ensureFileColumns(@NonNull Uri uri, @NonNull ContentValues values)
            throws VolumeArgumentException {
        final LocalUriMatcher matcher = new LocalUriMatcher(MediaStore.AUTHORITY);
        final int match = matcher.matchUri(uri, true);
        ensureNonUniqueFileColumns(match, uri, Bundle.EMPTY, values, null /* currentPath */);
    }

    private void ensureUniqueFileColumns(int match, @NonNull Uri uri, @NonNull Bundle extras,
            @NonNull ContentValues values) throws VolumeArgumentException {
        ensureFileColumns(match, uri, extras, values, true, null /* currentPath */);
    }

    private void ensureNonUniqueFileColumns(int match, @NonNull Uri uri,
            @NonNull Bundle extras, @NonNull ContentValues values, @Nullable String currentPath)
            throws VolumeArgumentException {
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
            throws VolumeArgumentException {
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
                defaultSecondary = ".thumbnails";
                break;
            case VIDEO_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
                defaultMimeType = "image/jpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_IMAGE;
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = ".thumbnails";
                break;
            case IMAGES_THUMBNAILS:
            case IMAGES_THUMBNAILS_ID:
                defaultMimeType = "image/jpeg";
                defaultMediaType = FileColumns.MEDIA_TYPE_IMAGE;
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                allowedPrimary = Arrays.asList(defaultPrimary);
                defaultSecondary = ".thumbnails";
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
            final String data = values.getAsString(MediaColumns.DATA);

            if (TextUtils.isEmpty(values.getAsString(MediaColumns.DISPLAY_NAME))) {
                values.put(MediaColumns.DISPLAY_NAME, extractDisplayName(data));
            }
            if (TextUtils.isEmpty(values.getAsString(MediaColumns.MIME_TYPE))) {
                values.put(MediaColumns.MIME_TYPE, MimeUtils.resolveMimeType(new File(data)));
            }
        }
        // Extract the MIME type from the display name if we couldn't resolve it from the raw path
        if (!TextUtils.isEmpty(values.getAsString(MediaColumns.DISPLAY_NAME))) {
            final String displayName = values.getAsString(MediaColumns.DISPLAY_NAME);

            if (TextUtils.isEmpty(values.getAsString(MediaColumns.MIME_TYPE))) {
                values.put(
                        MediaColumns.MIME_TYPE, MimeUtils.resolveMimeType(new File(displayName)));
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
                        allowedPrimary = Arrays.asList(
                                Environment.DIRECTORY_MUSIC,
                                Environment.DIRECTORY_MOVIES);
                        break;
                    case FileColumns.MEDIA_TYPE_SUBTITLE:
                        defaultMimeType = "application/x-subrip";
                        defaultMediaType = FileColumns.MEDIA_TYPE_SUBTITLE;
                        defaultPrimary = Environment.DIRECTORY_MOVIES;
                        allowedPrimary = Arrays.asList(
                                Environment.DIRECTORY_MUSIC,
                                Environment.DIRECTORY_MOVIES);
                        break;
                }
            } else if (defaultMediaType != actualMediaType) {
                final String[] split = defaultMimeType.split("/");
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

            // Require that content lives under well-defined directories to help
            // keep the user's content organized

            // Start by saying unchanged paths are valid
            boolean validPath = res.getAbsolutePath().equals(currentPath);

            // Next, consider allowing based on allowed primary directory
            final String primary = relativePath[0];
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

            // Nothing left to check; caller can't use this path
            if (!validPath) {
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

    private long insertDirectory(DatabaseHelper helper, String path) {
        if (LOGV) Log.v(TAG, "inserting directory " + path);
        ContentValues values = new ContentValues();
        values.put(FileColumns.FORMAT, MtpConstants.FORMAT_ASSOCIATION);
        values.put(FileColumns.DATA, path);
        values.put(FileColumns.PARENT, getParent(helper, path));
        values.put(FileColumns.OWNER_PACKAGE_NAME, extractPathOwnerPackageName(path));
        values.put(FileColumns.VOLUME_NAME, extractVolumeName(path));
        values.put(FileColumns.RELATIVE_PATH, extractRelativePath(path));
        values.put(FileColumns.DISPLAY_NAME, extractDisplayName(path));
        values.put(FileColumns.IS_DOWNLOAD, isDownload(path));
        File file = new File(path);
        if (file.exists()) {
            values.put(FileColumns.DATE_MODIFIED, file.lastModified() / 1000);
        }
        final SQLiteDatabase db = helper.getWritableDatabase();
        long rowId = db.insert("files", FileColumns.DATE_MODIFIED, values);
        return rowId;
    }

    private long getParent(DatabaseHelper helper, String path) {
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
            final SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query("files", new String[] { FileColumns._ID },
                    FileColumns.DATA + "=?", new String[] { parentPath }, null, null, null)) {
                if (c.moveToFirst()) {
                    id = c.getLong(0);
                } else {
                    id = insertDirectory(helper, parentPath);
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

    private long insertFile(@NonNull SQLiteQueryBuilder qb, @NonNull DatabaseHelper helper,
            int match, @NonNull Uri uri, @NonNull Bundle extras, @NonNull ContentValues values,
            int mediaType, boolean notify) {
        boolean wasPathEmpty = !values.containsKey(MediaStore.MediaColumns.DATA)
                || TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DATA));

        // Make sure all file-related columns are defined
        try {
            ensureUniqueFileColumns(match, uri, extras, values);
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
            format = MimeUtils.resolveFormatCode(mimeType);
        }
        if (path != null && path.endsWith("/")) {
            Log.e(TAG, "directory has trailing slash: " + path);
            return 0;
        }
        if (format != 0) {
            values.put(FileColumns.FORMAT, format);
        }

        if (mimeType == null && path != null && format != MtpConstants.FORMAT_ASSOCIATION) {
            mimeType = MimeUtils.resolveMimeType(new File(path));
        }

        if (mimeType != null) {
            values.put(FileColumns.MIME_TYPE, mimeType);
            values.put(FileColumns.MEDIA_TYPE, MimeUtils.resolveMediaType(mimeType));
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

            Long parent = values.getAsLong(FileColumns.PARENT);
            if (parent == null) {
                if (path != null) {
                    long parentId = getParent(helper, path);
                    values.put(FileColumns.PARENT, parentId);
                }
            }

            rowId = insertAllowingUpsert(qb, helper, values, path);
        }
        if (format == MtpConstants.FORMAT_ASSOCIATION) {
            synchronized (mDirectoryCache) {
                mDirectoryCache.put(path, rowId);
            }
        }

        return rowId;
    }

    /**
     * Inserts a new row in MediaProvider database with {@code values}. Treats insert as upsert for
     * double inserts from same package.
     */
    private long insertAllowingUpsert(@NonNull SQLiteQueryBuilder qb,
            @NonNull DatabaseHelper helper, @NonNull ContentValues values, String path)
            throws SQLiteConstraintException {
        return helper.runWithTransaction(() -> {
            try {
                return qb.insert(helper, values);
            } catch (SQLiteConstraintException e) {
                final long rowId = getIdIfPathExistsForPackage(qb, helper, path,
                        getCallingPackageOrSelf());
                // Apps sometimes create a file via direct path and then insert it into
                // MediaStore via ContentResolver. The former should create a database entry,
                // so we have to treat the latter as an upsert.
                // TODO(b/149917493) Perform all INSERT operations as UPSERT.
                if (rowId != -1 && qb.update(helper, values, "_id=?",
                        new String[]{Long.toString(rowId)}) == 1) {
                    return rowId;
                }
                // Rethrow SQLiteConstraintException on failed upsert.
                throw e;
            }
        });
    }

    /**
     * @return row id of the entry with path {@code path} and owner {@code packageName}, if it
     * exists.
     */
    private long getIdIfPathExistsForPackage(@NonNull SQLiteQueryBuilder qb,
            @NonNull DatabaseHelper helper, String path, String packageName) {
        final String[] projection = new String[] {FileColumns._ID};
        final String selection = FileColumns.DATA + " LIKE ? AND " +
                FileColumns.OWNER_PACKAGE_NAME + " LIKE ? ";

        // TODO(b:149842708) Handle sharedUid. Package name check will fail if FUSE didn't
        // use the right package name for sharedUid.
        try (Cursor c = qb.query(helper, projection, selection, new String[] {path, packageName},
                null, null, null, null, null)) {
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        }
        return -1;
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
        extras = (extras != null) ? extras : Bundle.EMPTY;

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

                if (isCallingPackageSystem() || isCallingPackageLegacyWrite()) {
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

            if (isCallingPackageSystem() || isCallingPackageBackup()) {
                // When media inserted by ourselves during a scan, or by a
                // backup app, the best we can do is guess ownership based on
                // path when it's not explicitly provided
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
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_INSERT, match, uri, extras, null);

        switch (match) {
            case IMAGES_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_IMAGE, true);
                if (rowId > 0) {
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
                        MediaStore.Images.Media.getContentUri(resolvedVolumeName), imageId),
                        extras, true);

                ensureUniqueFileColumns(match, uri, extras, initialValues);

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

                ensureUniqueFileColumns(match, uri, extras, initialValues);

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
                rowId = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_AUDIO, true);
                if (rowId > 0) {
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
                        MediaStore.Audio.Media.getContentUri(resolvedVolumeName), audioId),
                        Bundle.EMPTY, false);
                final long playlistId = initialValues
                        .getAsLong(MediaStore.Audio.Playlists.Members.PLAYLIST_ID);
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(resolvedVolumeName), playlistId),
                        Bundle.EMPTY, true);

                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Playlists.Members.AUDIO_ID, audioId);
                rowId = qb.insert(helper, values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                    updatePlaylistDateModifiedToNow(helper, playlistId);
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
                rowId = insertFile(qb, helper, match, uri, extras, values,
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
                        MediaStore.Audio.Media.getContentUri(resolvedVolumeName), audioId),
                        Bundle.EMPTY, false);
                final long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                enforceCallingPermission(ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.getContentUri(resolvedVolumeName), playlistId),
                        Bundle.EMPTY, true);

                ContentValues values = new ContentValues(initialValues);
                values.put(Audio.Playlists.Members.PLAYLIST_ID, playlistId);
                rowId = qb.insert(helper, values);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                    updatePlaylistDateModifiedToNow(helper, playlistId);
                }
                break;
            }

            case VIDEO_MEDIA: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                rowId = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_VIDEO, true);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(
                            Video.Media.getContentUri(originalVolumeName), rowId);
                }
                break;
            }

            case AUDIO_ALBUMART: {
                if (helper.mInternal) {
                    throw new UnsupportedOperationException("no internal album art allowed");
                }

                ensureUniqueFileColumns(match, uri, extras, initialValues);

                rowId = qb.insert(helper, initialValues);
                if (rowId > 0) {
                    newUri = ContentUris.withAppendedId(uri, rowId);
                }
                break;
            }

            case FILES: {
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                final boolean isDownload = maybeMarkAsDownload(initialValues);
                final boolean isDocumentType = MimeUtils.isDocumentMimeType(
                        initialValues.getAsString((MediaColumns.MIME_TYPE)));
                final int mediaType = isDocumentType ? FileColumns.MEDIA_TYPE_DOCUMENT
                        : FileColumns.MEDIA_TYPE_NONE;
                rowId = insertFile(qb, helper, match, uri, extras, initialValues,
                        mediaType, true);

                if (rowId > 0) {
                    newUri = Files.getContentUri(originalVolumeName, rowId);
                }
                break;
            }

            case DOWNLOADS:
                maybePut(initialValues, FileColumns.OWNER_PACKAGE_NAME, ownerPackageName);
                initialValues.put(FileColumns.IS_DOWNLOAD, true);
                rowId = insertFile(qb, helper, match, uri, extras, initialValues,
                        FileColumns.MEDIA_TYPE_NONE, false);
                if (rowId > 0) {
                    final int mediaType = initialValues.getAsInteger(FileColumns.MEDIA_TYPE);
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
            mMediaScanner.scanFile(new File(path).getParentFile(), REASON_DEMAND);
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

    private static void appendWhereStandaloneMatch(@NonNull SQLiteQueryBuilder qb,
            @NonNull String column, /* @Match */ int match) {
        switch (match) {
            case MATCH_INCLUDE:
                // No special filtering needed
                break;
            case MATCH_EXCLUDE:
                appendWhereStandalone(qb, column + "=?", 0);
                break;
            case MATCH_ONLY:
                appendWhereStandalone(qb, column + "=?", 1);
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
        if (parseBoolean(uri.getQueryParameter("distinct"))) {
            qb.setDistinct(true);
        }
        qb.setStrict(true);
        // TODO: re-enable as part of fixing b/146518586
        if (getCallingPackageTargetSdkVersion() >= Build.VERSION_CODES.R) {
            qb.setStrictColumns(true);
            qb.setStrictGrammar(true);
        }

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
        if (matchPending == MATCH_DEFAULT) matchPending = MATCH_EXCLUDE;
        if (matchTrashed == MATCH_DEFAULT) matchTrashed = MATCH_EXCLUDE;
        if (matchFavorite == MATCH_DEFAULT) matchFavorite = MATCH_INCLUDE;

        // Handle callers using legacy filtering
        final String filter = uri.getQueryParameter("filter");

        boolean includeAllVolumes = false;

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
                    appendWhereStandalone(qb, FileColumns.OWNER_PACKAGE_NAME + " IN "
                            + sharedPackages);
                }
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite);
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
                            "image_id IN (SELECT _id FROM images WHERE owner_package_name IN "
                                    + sharedPackages + ")");
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
                            DatabaseUtils.bindSelection(FileColumns.OWNER_PACKAGE_NAME
                                    + " IN " + sharedPackages
                                    + " OR is_ringtone=1 OR is_alarm=1 OR is_notification=1"));
                }
                appendWhereStandaloneFilter(qb, new String[] {
                        AudioColumns.ARTIST_KEY, AudioColumns.ALBUM_KEY, AudioColumns.TITLE_KEY
                }, filter);
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite);
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
            case AUDIO_MEDIA_ID_PLAYLISTS_ID:
                appendWhereStandalone(qb, "_id=?", uri.getPathSegments().get(5));
                // fall-through
            case AUDIO_MEDIA_ID_PLAYLISTS: {
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
                    appendWhereStandalone(qb, FileColumns.OWNER_PACKAGE_NAME + " IN "
                            + sharedPackages);
                }
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite);
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
                    qb.setProjectionMap(getProjectionMap(Audio.Artists.Albums.class));

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
                    appendWhereStandalone(qb, FileColumns.OWNER_PACKAGE_NAME + " IN "
                            + sharedPackages);
                }
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite);
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
                            "video_id IN (SELECT _id FROM video WHERE owner_package_name IN "
                                    + sharedPackages + ")");
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
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_SUBTITLE));
                        options.add("media_type=0 AND mime_type LIKE 'audio/%'");
                    }
                    if (checkCallingPermissionVideo(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_VIDEO));
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_SUBTITLE));
                        options.add("media_type=0 AND mime_type LIKE 'video/%'");
                    }
                    if (checkCallingPermissionImages(forWrite, callingPackage)) {
                        options.add(DatabaseUtils.bindSelection("media_type=?",
                                FileColumns.MEDIA_TYPE_IMAGE));
                        options.add("media_type=0 AND mime_type LIKE 'image/%'");
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
                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite);
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

                appendWhereStandaloneMatch(qb, FileColumns.IS_PENDING, matchPending);
                appendWhereStandaloneMatch(qb, FileColumns.IS_TRASHED, matchTrashed);
                appendWhereStandaloneMatch(qb, FileColumns.IS_FAVORITE, matchFavorite);
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

        // If we're the legacy provider, and the caller is the system, then
        // we're willing to let them access any columns they want
        if (mLegacyProvider && isCallingPackageSystem()) {
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
        Trace.beginSection("insert");
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
        extras = (extras != null) ? extras : Bundle.EMPTY;

        final String userWhere = extras.getString(QUERY_ARG_SQL_SELECTION);
        final String[] userWhereArgs = extras.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);

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

            mMediaScannerVolume = null;
            return 1;
        }

        if (match == VOLUMES_ID) {
            detachVolume(uri);
            count = 1;
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

                            deleteIfAllowed(uri, extras, data);

                            // Only need to inform DownloadProvider about the downloads deleted on
                            // external volume.
                            if (isDownload == 1) {
                                deletedDownloadIds.put(id, mimeType);
                            }
                            if (mediaType == FileColumns.MEDIA_TYPE_AUDIO) {
                                if (!helper.mInternal) {
                                    idvalue[0] = String.valueOf(id);
                                    // for each playlist that the item appears in, move
                                    // all the items behind it forward by one
                                    final SQLiteDatabase db = helper.getWritableDatabase();
                                    Cursor cc = db.query("audio_playlists_map",
                                                sPlaylistIdPlayOrder,
                                                "audio_id=?", idvalue, null, null, null);
                                    try {
                                        while (cc.moveToNext()) {
                                            long playlistId = cc.getLong(0);
                                            playlistvalues[0] = String.valueOf(playlistId);
                                            playlistvalues[1] = String.valueOf(cc.getInt(1));
                                            db.execSQL("UPDATE audio_playlists_map" +
                                                    " SET play_order=play_order-1" +
                                                    " WHERE playlist_id=? AND play_order>?",
                                                    playlistvalues);
                                            updatePlaylistDateModifiedToNow(helper, playlistId);
                                        }
                                        db.delete("audio_playlists_map", "audio_id=?", idvalue);
                                    } finally {
                                        FileUtils.closeQuietly(cc);
                                    }
                                }
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
                    count = deleteRecursive(qb, helper, userWhere, userWhereArgs);
                    break;

                case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
                    long playlistId = Long.parseLong(uri.getPathSegments().get(3));
                    count = deleteRecursive(qb, helper, userWhere, userWhereArgs);
                    if (count > 0) {
                        updatePlaylistDateModifiedToNow(helper, playlistId);
                    }
                    break;
                default:
                    count = deleteRecursive(qb, helper, userWhere, userWhereArgs);
                    break;
            }

            if (deletedDownloadIds.size() > 0) {
                final long token = Binder.clearCallingIdentity();
                try {
                    getContext().getSystemService(DownloadManager.class)
                            .onMediaStoreDownloadsDeleted(deletedDownloadIds);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            if (isFilesTable && !isCallingPackageSystem()) {
                Metrics.logDeletion(volumeName, System.currentTimeMillis(),
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
        synchronized (mDirectoryCache) {
            mDirectoryCache.clear();

            return (int) helper.runWithTransaction(() -> {
                int n = 0;
                int total = 0;
                do {
                    n = qb.delete(helper, userWhere, userWhereArgs);
                    total += n;
                } while (n > 0);
                return total;
            });
        }
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
            case MediaStore.GET_GENERATION_CALL: {
                final String volumeName = extras.getString(Intent.EXTRA_TEXT);

                final long generation;
                try {
                    generation = getDatabaseForUri(MediaStore.Files.getContentUri(volumeName))
                            .getGeneration();
                } catch (VolumeNotFoundException e) {
                    throw e.rethrowAsIllegalArgumentException();
                }

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

        final String volumeName = MediaStore.getVolumeName(uris.get(0));
        for (Uri uri : uris) {
            // Require that everything is on the same volume
            if (!Objects.equals(volumeName, MediaStore.getVolumeName(uri))) {
                throw new IllegalArgumentException("All requested items must be on same volume");
            }

            final int match = matchUri(uri, false);
            switch (match) {
                case IMAGES_MEDIA_ID:
                case AUDIO_MEDIA_ID:
                case VIDEO_MEDIA_ID:
                    // Caller is requesting a specific media item by its ID,
                    // which means it's valid for requests
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
                        MediaColumns.IS_TRASHED,
                        MediaColumns.DATE_EXPIRES);
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

    private int pruneThumbnails(@NonNull CancellationSignal signal) {
        int prunedCount = 0;

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
                    thumbFile.delete();
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
                    ".thumbnails", ContentUris.parseId(uri) + ".jpg");
        }

        public abstract Bitmap getThumbnailBitmap(Uri uri, CancellationSignal signal)
                throws IOException;

        public ParcelFileDescriptor ensureThumbnail(Uri uri, CancellationSignal signal)
                throws IOException {
            // First attempt to fast-path by opening the thumbnail; if it
            // doesn't exist we fall through to create it below
            final File thumbFile = getThumbnailFile(uri);
            try {
                return ParcelFileDescriptor.open(thumbFile,
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
                thumbWrite = ParcelFileDescriptor.open(thumbTempFile,
                        ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE);
                thumbRead = ParcelFileDescriptor.open(thumbTempFile,
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
                thumbTempFile.delete();
            }
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
                deleteIfAllowed(uri, Bundle.EMPTY, path);
            }
        }

        db.execSQL("delete from thumbnails where image_id=?", new String[] { idString });
        db.execSQL("delete from videothumbnails where video_id=?", new String[] { idString });
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
        extras = (extras != null) ? extras : Bundle.EMPTY;

        // Related items are only considered for new media creation, and they
        // can't be leveraged to move existing content into blocked locations
        extras.remove(QUERY_ARG_RELATED_URI);

        final String userWhere = extras.getString(QUERY_ARG_SQL_SELECTION);
        final String[] userWhereArgs = extras.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);

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

            // Ignore or augment incoming raw filesystem paths
            for (String column : sDataColumns.keySet()) {
                if (!initialValues.containsKey(column)) continue;

                if (isCallingPackageSystem() || isCallingPackageLegacyWrite()) {
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
                ensureUniqueFileColumns(match, uri, extras, initialValues);

                final String afterPath = initialValues.getAsString(MediaColumns.DATA);

                Log.d(TAG, "Moving " + beforePath + " to " + afterPath);
                try {
                    Os.rename(beforePath, afterPath);
                } catch (ErrnoException e) {
                    throw new IllegalStateException(e);
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

        // if the media type is being changed, check if it's being changed from image or video
        // to something else
        if (initialValues.containsKey(FileColumns.MEDIA_TYPE)) {
            final int newMediaType = initialValues.getAsInteger(FileColumns.MEDIA_TYPE);

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
                count = qb.update(helper, values, userWhere, userWhereArgs);
                if (count > 0) {
                    updatePlaylistDateModifiedToNow(helper, playlistId);
                }
                break;
            case AUDIO_PLAYLISTS_ID_MEMBERS:
                long playlistIdMembers = Long.parseLong(uri.getPathSegments().get(3));
                count = qb.update(helper, values, userWhere, userWhereArgs);
                if (count > 0) {
                    updatePlaylistDateModifiedToNow(helper, playlistIdMembers);
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
                        int rowsChanged = movePlaylistEntry(volumeName, helper,
                                playlist, oldpos, newpos);
                        if (rowsChanged > 0) {
                            updatePlaylistDateModifiedToNow(helper, playlist);
                        }

                        return rowsChanged;
                    }
                    throw new IllegalArgumentException("Need to specify " + key +
                            " when using 'move' parameter");
                }
                // fall through
            default:
                count = qb.update(helper, values, userWhere, userWhereArgs);
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
                            mMediaScanner.scanFile(new File(c.getString(0)), REASON_DEMAND);
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

    private int movePlaylistEntry(String volumeName, DatabaseHelper helper,
            long playlist, int from, int to) {
        if (from == to) {
            return 0;
        }
        final SQLiteDatabase db = helper.getWritableDatabase();
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

    private void updatePlaylistDateModifiedToNow(DatabaseHelper helper, long playlistId) {
        ContentValues values = new ContentValues();
        values.put(
                FileColumns.DATE_MODIFIED,
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        );

        final Uri uri = ContentUris.withAppendedId(
                MediaStore.Audio.Playlists.getContentUri(helper.mVolumeName), playlistId);
        final SQLiteQueryBuilder qb = getQueryBuilder(TYPE_UPDATE, AUDIO_PLAYLISTS_ID,
                uri, Bundle.EMPTY, null);
        qb.update(helper, values, null, null);
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
                && (mimeTypeFilter != null) && mimeTypeFilter.startsWith("image/");
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
                    switch (MimeUtils.resolveMediaType(getType(uri))) {
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
                DatabaseUtils.createSqlQueryBundle(selection, selectionArgs, null), signal);
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

    private FuseDaemon getFuseDaemonForFile(File file) {
        StorageVolume volume = mStorageManager.getStorageVolume(file);
        if (volume == null) {
            return null;
        }
        return ExternalStorageServiceImpl.getFuseDaemon(volume.getId());
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
        final int modeBits = ParcelFileDescriptor.parseMode(mode);
        final boolean forWrite = (modeBits & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0;

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
                    pfd = ParcelFileDescriptor.open(getFuseFile(file), modeBits);
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
                FuseDaemon daemon = getFuseDaemonForFile(file);
                ParcelFileDescriptor lowerFsFd = ParcelFileDescriptor.open(file, modeBits);
                boolean forRead = (modeBits & ParcelFileDescriptor.MODE_READ_ONLY) != 0;
                boolean shouldOpenWithFuse = daemon != null
                        && daemon.shouldOpenWithFuse(filePath, forRead, lowerFsFd.getFd());

                if (SystemProperties.getBoolean(PROP_FUSE, false) && shouldOpenWithFuse) {
                    // If the file is already opened on the FUSE mount with VFS caching enabled
                    // we return an upper filesystem fd (via FUSE) to avoid file corruption
                    // resulting from cache inconsistencies between the upper and lower
                    // filesystem caches
                    Log.w(TAG, "Using FUSE for " + filePath);
                    pfd = ParcelFileDescriptor.open(getFuseFile(file), modeBits);
                    try {
                        lowerFsFd.close();
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to close lower filesystem fd " + file.getPath(), e);
                    }
                } else {
                    Log.i(TAG, "Using lower FS for " + filePath);
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

    private void deleteIfAllowed(Uri uri, Bundle extras, String path) {
        try {
            final File file = new File(path);
            checkAccess(uri, extras, file, true);
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

    private boolean isCallingPackageRequestingLegacy() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY_GRANTED);
    }

    private static int getFileMediaType(String path) {
        final File file = new File(path);
        final String mimeType = MimeUtils.resolveMimeType(file);
        return MimeUtils.resolveMediaType(mimeType);
    }

    private boolean canAccessMediaFile(String filePath) {
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

        if (mCallingIdentity.get().hasPermission(PERMISSION_MANAGE_EXTERNAL_STORAGE)) {
            return true;
        }

        // Apps with write access to images and/or videos can bypass our restrictions if all of the
        // the files they're accessing are of the compatible media type.
        if (canAccessMediaFile(filePath)) {
            return true;
        }

        return false;
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

    @Nullable
    private String getAbsoluteSanitizedPath(String path) {
        final String[] pathSegments = sanitizePath(path);
        if (pathSegments.length == 0) {
            return null;
        }
        return path = "/" + String.join("/",
                Arrays.copyOfRange(pathSegments, 1, pathSegments.length));
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
    public long[] getRedactionRangesForFuse(String path, int uid) throws IOException {
        final File file = new File(path);

        // When we're calculating redaction ranges for MediaProvider, it means we're actually
        // calculating redaction ranges for another app that called to MediaProvider through Binder,
        // so we always need to redact because the redaction checks were done earlier
        if (uid == android.os.Process.myUid()) {
            return getRedactionRanges(file).redactionRanges;
        }

        LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));

        long[] res = new long[0];
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            if (appSpecificDir != null) {
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return res;
                }
            }

            if (!isRedactionNeeded()
                    || shouldBypassFuseRestrictions(/*forWrite*/ false, path)) {
                return res;
            }

            path = getAbsoluteSanitizedPath(path);
            if (path == null) {
                throw new IOException("Invalid path " + path);
            }

            final Uri contentUri = Files.getContentUri(MediaStore.getVolumeName(new File(path)));
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
    private RedactionInfo getRedactionRanges(File file) throws IOException {
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
     * Checks if the app identified by the given UID is allowed to open the given file for the given
     * access mode.
     *
     * @param path the path of the file to be opened
     * @param uid UID of the app requesting to open the file
     * @param forWrite specifies if the file is to be opened for write
     * @return 0 upon success. If the operation is illegal or not permitted, returns
     * {@link OsConstants#ENOENT} to prevent malicious apps from distinguishing whether a file
     * they have no access to exists or not, or {@link OsConstants#EACCES} or if the calling package
     * is a legacy app that doesn't have right storage permission.
     *
     * Called from JNI in jni/MediaProviderWrapper.cpp
     */
    @Keep
    public int isOpenAllowedForFuse(String path, int uid, boolean forWrite) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            if (appSpecificDir != null) {
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return 0;
                } else {
                    Log.e(TAG, "Can't open a file in another app's external directory!");
                    return OsConstants.ENOENT;
                }
            }

            if (shouldBypassFuseRestrictions(forWrite, path)) {
                return 0;
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EACCES;
            }

            path = getAbsoluteSanitizedPath(path);
            if (path == null) {
                Log.e(TAG, "Invalid path " + path);
                return OsConstants.EPERM;
            }

            final Uri contentUri = Files.getContentUri(MediaStore.getVolumeName(new File(path)));
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
            checkAccess(fileUri, Bundle.EMPTY, file, forWrite);

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
            return OsConstants.ENOENT;
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
        return getPossibleContentUrisForPath(filePath, mimeType)[0];
    }

    /**
     * Returns possible content URIs for given path.
     *
     * Return mime type specific URI for file paths where mime type is specified.
     * If the path is a directory and mime type is unknown, return all possible
     * URIs specific to top level directory of the given path.
     *
     * @throws IllegalStateException if path is invalid or doesn't match a volume.
     */
    @NonNull
    private Uri[] getPossibleContentUrisForPath(@NonNull String path,
            @NonNull String mimeType) {
        final String volName = FileUtils.getVolumeName(getContext(), new File(path));
        Uri[] uris = {Files.getContentUri(volName)};
        final String topLevelDir = extractTopLevelDir(path);
        if (topLevelDir == null) {
            // If the file path doesn't match the external storage directory, we use the files URI
            // as default and let #insert enforce the restrictions
            return uris;
        }
        switch (topLevelDir) {
            case DIRECTORY_MUSIC:
            case DIRECTORY_PODCASTS:
            case DIRECTORY_RINGTONES:
            case DIRECTORY_ALARMS:
            case DIRECTORY_NOTIFICATIONS:
            case DIRECTORY_AUDIOBOOKS:
                uris[0] = Audio.Media.getContentUri(volName);
                break;
            case DIRECTORY_MOVIES:
                uris[0] = Video.Media.getContentUri(volName);
                break;
            case DIRECTORY_DCIM:
            case DIRECTORY_PICTURES:
                if (mimeType.toLowerCase(Locale.ROOT).startsWith("image")) {
                    uris[0] = Images.Media.getContentUri(volName);
                } else if (mimeType.toLowerCase(Locale.ROOT).startsWith("video")) {
                    uris[0] = Video.Media.getContentUri(volName);
                } else if (new File(path).isDirectory()) {
                    // DCIM and subdirectories of DCIM support both pictures and videos. Return both
                    // URIs if the path is directory.
                    uris = new Uri[]{Images.Media.getContentUri(volName),
                            Video.Media.getContentUri(volName)};
                } else {
                    // Send images uri for unsupported file types.
                    uris[0] = Images.Media.getContentUri(volName);
                }
                break;
            case DIRECTORY_DOWNLOADS:
            case DIRECTORY_DOCUMENTS:
                break;
            default:
                Log.w(TAG, "Forgot to handle a top level directory in getContentUriForFile?");
        }
        return uris;
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
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            // App dirs are not indexed, so we don't create an entry for the file.
            if (appSpecificDir != null) {
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return 0;
                } else {
                    Log.e(TAG, "Can't create a file in another app's external directory");
                    return OsConstants.ENOENT;
                }
            }

            if (shouldBypassFuseRestrictions(/*forWrite*/ true, path)) {
                return 0;
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EPERM;
            }

            final String mimeType = MimeUtils.resolveMimeType(new File(path));
            final Uri contentUri = getContentUriForFile(path, mimeType);
            if (fileExists(path, contentUri)) {
                return OsConstants.EEXIST;
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
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Check if app is deleting a file under an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            // Trying to delete file under some app's external storage dir
            if (appSpecificDir != null) {
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return deleteFileUnchecked(path);
                } else {
                    Log.e(TAG, "Can't delete a file in another app's external directory!");
                    return OsConstants.ENOENT;
                }
            }

            if (shouldBypassFuseRestrictions(/*forWrite*/ true, path)) {
                // TODO(b/145737191) Legacy apps don't expect FuseDaemon to update database.
                // Inserting/deleting the database entry might break app functionality.
                return deleteFileUnchecked(path);
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EPERM;
            }

            final String sanitizedPath = getAbsoluteSanitizedPath(path);
            if (sanitizedPath == null) {
                throw new IOException("Invalid path " + path);
            }

            final Uri contentUri = Files.getContentUri(MediaStore.getVolumeName(new File(path)));
            final String where = FileColumns.DATA + " = ?";
            final String[] whereArgs = {sanitizedPath};

            if (delete(contentUri, where, whereArgs) == 0) {
                return OsConstants.ENOENT;
            } else if (!path.equals(sanitizedPath)) {
                // delete() doesn't delete the file in lower file system if sanitized path is
                // different path from actual path. Delete the file using actual path of the file.
                return deleteFileUnchecked(path);
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
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            // App dirs are not indexed, so we don't create an entry for the file.
            if (appSpecificDir != null) {
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return 0;
                } else {
                    Log.e(TAG, "Can't modify another app's external directory!");
                    return OsConstants.EACCES;
                }
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
                // We allow creating the default top level directories only, all other oprations on
                // top level directories are not allowed.
                if (forCreate && isDefaultDirectoryName(extractDisplayName(path))) {
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
    public int isOpendirAllowedForFuse(@NonNull String path, int uid) {
        final LocalCallingIdentity token =
                clearLocalCallingIdentity(LocalCallingIdentity.fromExternal(getContext(), uid));
        try {
            // Returns null if the path doesn't correspond to an app specific directory
            final String appSpecificDir = extractPathOwnerPackageName(path);

            if (appSpecificDir != null) {
                if (DIRECTORY_MEDIA.equals(sanitizePath(extractRelativePath(path))[1])) {
                    // Allow opening external media directories of other packages.
                    return 0;
                }
                if (isCallingIdentitySharedPackageName(appSpecificDir)) {
                    return 0;
                } else {
                    Log.e(TAG, "Can't access another app's external directory!");
                    return OsConstants.ENOENT;
                }
            }

            if (shouldBypassFuseRestrictions(/*forWrite*/ false, path)) {
                return 0;
            }
            // Legacy apps that made is this far don't have the right storage permission and hence
            // are not allowed to access anything other than their external app directory
            if (isCallingPackageRequestingLegacy()) {
                return OsConstants.EACCES;
            }

            return 0;
        } finally {
            restoreLocalCallingIdentity(token);
        }
    }

    private boolean checkCallingPermissionGlobal(Uri uri, boolean forWrite) {
        // System internals can work with all media
        if (isCallingPackageSystem()) {
            return true;
        }

        // Apps that have permission to manage external storage can work with all files
        if (mCallingIdentity.get().hasPermission(PERMISSION_MANAGE_EXTERNAL_STORAGE)) {
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
            try (Cursor c = qb.query(helper, new String[0],
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
        try (Cursor c = qb.query(helper, new String[0],
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
                // Maybe we are racing onVolumeStateChanged, update our cache and try again
                updateVolumes();
            }
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

        final ContentResolver resolver = getContext().getContentResolver();
        final Uri uri = getBaseContentUri(volume);
        resolver.notifyChange(getBaseContentUri(volume), null);

        if (LOGV) Log.v(TAG, "Attached volume: " + volume);
        if (!MediaStore.VOLUME_INTERNAL.equals(volume)) {
            // Also notify on synthetic view of all devices
            resolver.notifyChange(getBaseContentUri(MediaStore.VOLUME_EXTERNAL), null);

            BackgroundThread.getExecutor().execute(() -> {
                final DatabaseHelper helper = MediaStore.VOLUME_INTERNAL.equals(volume)
                        ? mInternalDatabase : mExternalDatabase;
                ensureDefaultFolders(volume, helper);
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
    static final int AUDIO_MEDIA_ID_PLAYLISTS = 104;
    static final int AUDIO_MEDIA_ID_PLAYLISTS_ID = 105;
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

    /** Flag if we're running as {@link MediaStore#AUTHORITY_LEGACY} */
    private boolean mLegacyProvider;
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
    private boolean isCallingPackageBackup() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_BACKUP);
    }

    @Deprecated
    private boolean isCallingPackageLegacyWrite() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY_WRITE);
    }

    @Deprecated
    private boolean isCallingPackageLegacyRead() {
        return mCallingIdentity.get().hasPermission(PERMISSION_IS_LEGACY_READ);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("mThumbSize=" + mThumbSize);
        writer.println("mAttachedVolumeNames=" + mAttachedVolumeNames);
        writer.println();

        Logging.dumpPersistent(writer);
    }
}
