/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.provider.MediaStore.Files.FileColumns.TRANSCODE_COMPLETE;
import static android.provider.MediaStore.Files.FileColumns.TRANSCODE_EMPTY;
import static android.provider.MediaStore.MATCH_EXCLUDE;
import static android.provider.MediaStore.QUERY_ARG_MATCH_PENDING;
import static android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED;

import static com.android.providers.media.MediaProvider.VolumeNotFoundException;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_CLIENT_TIMEOUT;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_SERVICE_ERROR;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_SESSION_CANCELED;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__TRANSCODE_RESULT__FAIL;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__TRANSCODE_RESULT__SUCCESS;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED;
import static com.android.providers.media.util.SyntheticPathUtils.createSparseFile;

import android.annotation.IntRange;
import android.annotation.LongDef;
import android.app.ActivityManager;
import android.app.ActivityManager.OnUidImportanceListener;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.media.ApplicationMediaCapabilities;
import android.media.MediaCodec;
import android.media.MediaFeature;
import android.media.MediaFormat;
import android.media.MediaTranscodingManager;
import android.media.MediaTranscodingManager.TranscodingRequest.VideoFormatResolver;
import android.media.MediaTranscodingManager.TranscodingSession;
import android.media.MediaTranscodingManager.VideoTranscodingRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.SQLiteQueryBuilder;
import com.android.providers.media.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiresApi(Build.VERSION_CODES.S)
public class TranscodeHelperImpl implements TranscodeHelper {
    private static final String TAG = "TranscodeHelper";
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.fuse.log", false);
    private static final float MAX_APP_NAME_SIZE_PX = 500f;

    // Notice the pairing of the keys.When you change a DEVICE_CONFIG key, then please also change
    // the corresponding SYS_PROP key too; and vice-versa.
    // Keeping the whole strings separate for the ease of text search.
    private static final String TRANSCODE_ENABLED_SYS_PROP_KEY =
            "persist.sys.fuse.transcode_enabled";
    private static final String TRANSCODE_ENABLED_DEVICE_CONFIG_KEY = "transcode_enabled";
    private static final String TRANSCODE_DEFAULT_SYS_PROP_KEY =
            "persist.sys.fuse.transcode_default";
    private static final String TRANSCODE_DEFAULT_DEVICE_CONFIG_KEY = "transcode_default";
    private static final String TRANSCODE_USER_CONTROL_SYS_PROP_KEY =
            "persist.sys.fuse.transcode_user_control";
    private static final String TRANSCODE_COMPAT_MANIFEST_KEY = "transcode_compat_manifest";
    private static final String TRANSCODE_COMPAT_STALE_KEY = "transcode_compat_stale";
    private static final String TRANSCODE_MAX_DURATION_MS_KEY = "transcode_max_duration_ms";

    private static final int MY_UID = android.os.Process.myUid();
    private static final int MAX_TRANSCODE_DURATION_MS = (int) TimeUnit.MINUTES.toMillis(1);

    // Whether the device has HDR plugin for transcoding HDR to SDR video.
    private boolean mHasHdrPlugin = false;

    /**
     * Force enable an app to support the HEVC media capability
     *
     * Apps should declare their supported media capabilities in their manifest but this flag can be
     * used to force an app into supporting HEVC, hence avoiding transcoding while accessing media
     * encoded in HEVC.
     *
     * Setting this flag will override any OS level defaults for apps. It is disabled by default,
     * meaning that the OS defaults would take precedence.
     *
     * Setting this flag and {@code FORCE_DISABLE_HEVC_SUPPORT} is an undefined
     * state and will result in the OS ignoring both flags.
     */
    @ChangeId
    @Disabled
    private static final long FORCE_ENABLE_HEVC_SUPPORT = 174228127L;

    /**
     * Force disable an app from supporting the HEVC media capability
     *
     * Apps should declare their supported media capabilities in their manifest but this flag can be
     * used to force an app into not supporting HEVC, hence forcing transcoding while accessing
     * media encoded in HEVC.
     *
     * Setting this flag will override any OS level defaults for apps. It is disabled by default,
     * meaning that the OS defaults would take precedence.
     *
     * Setting this flag and {@code FORCE_ENABLE_HEVC_SUPPORT} is an undefined state
     * and will result in the OS ignoring both flags.
     */
    @ChangeId
    @Disabled
    private static final long FORCE_DISABLE_HEVC_SUPPORT = 174227820L;

    @VisibleForTesting
    static final int FLAG_HEVC = 1 << 0;
    @VisibleForTesting
    static final int FLAG_SLOW_MOTION = 1 << 1;
    private static final int FLAG_HDR_10 = 1 << 2;
    private static final int FLAG_HDR_10_PLUS = 1 << 3;
    private static final int FLAG_HDR_HLG = 1 << 4;
    private static final int FLAG_HDR_DOLBY_VISION = 1 << 5;
    private static final int MEDIA_FORMAT_FLAG_MASK = FLAG_HEVC | FLAG_SLOW_MOTION
            | FLAG_HDR_10 | FLAG_HDR_10_PLUS | FLAG_HDR_HLG | FLAG_HDR_DOLBY_VISION;

    @LongDef({
            FLAG_HEVC,
            FLAG_SLOW_MOTION,
            FLAG_HDR_10,
            FLAG_HDR_10_PLUS,
            FLAG_HDR_HLG,
            FLAG_HDR_DOLBY_VISION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationMediaCapabilitiesFlags {
    }

    /** Coefficient to 'guess' how long a transcoding session might take */
    private static final double TRANSCODING_TIMEOUT_COEFFICIENT = 10;
    /** Coefficient to 'guess' how large a transcoded file might be */
    private static final double TRANSCODING_SIZE_COEFFICIENT = 2;

    /**
     * Copied from MediaProvider.java
     * TODO(b/170465810): Remove this when  getQueryBuilder code is refactored.
     */
    private static final int TYPE_QUERY = 0;
    private static final int TYPE_UPDATE = 2;

    private static final int MAX_FINISHED_TRANSCODING_SESSION_STORE_COUNT = 16;
    private static final String DIRECTORY_CAMERA = "Camera";

    private static final boolean IS_TRANSCODING_SUPPORTED = SdkLevel.isAtLeastS();

    private final Object mLock = new Object();
    private final Context mContext;
    private final MediaProvider mMediaProvider;
    private final PackageManager mPackageManager;
    private final StorageManager mStorageManager;
    private final ActivityManager mActivityManager;
    private final File mTranscodeDirectory;
    private final List<String> mSupportedRelativePaths;
    @GuardedBy("mLock")
    private UUID mTranscodeVolumeUuid;

    @GuardedBy("mLock")
    private final Map<String, StorageTranscodingSession> mStorageTranscodingSessions =
            new ArrayMap<>();

    // These are for dumping purpose only.
    // We keep these separately because the probability of getting cancelled and error'ed sessions
    // is pretty low, and we are limiting the count of what we keep.  So, we don't wanna miss out
    // on dumping the cancelled and error'ed sessions.
    @GuardedBy("mLock")
    private final Map<StorageTranscodingSession, Boolean> mSuccessfulTranscodeSessions =
            createFinishedTranscodingSessionMap();
    @GuardedBy("mLock")
    private final Map<StorageTranscodingSession, Boolean> mCancelledTranscodeSessions =
            createFinishedTranscodingSessionMap();
    @GuardedBy("mLock")
    private final Map<StorageTranscodingSession, Boolean> mErroredTranscodeSessions =
            createFinishedTranscodingSessionMap();

    private final TranscodeUiNotifier mTranscodingUiNotifier;
    private final TranscodeDenialController mTranscodeDenialController;
    private final SessionTiming mSessionTiming;
    @GuardedBy("mLock")
    private final Map<String, Integer> mAppCompatMediaCapabilities = new ArrayMap<>();
    @GuardedBy("mLock")
    private boolean mIsTranscodeEnabled;

    private static final String[] TRANSCODE_CACHE_INFO_PROJECTION =
            {FileColumns._ID, FileColumns._TRANSCODE_STATUS};
    private static final String TRANSCODE_WHERE_CLAUSE =
            FileColumns.DATA + "=?" + " and mime_type not like 'null'";

    public TranscodeHelperImpl(Context context, MediaProvider mediaProvider) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mStorageManager = context.getSystemService(StorageManager.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
        mMediaProvider = mediaProvider;
        mTranscodeDirectory = new File("/storage/emulated/" + UserHandle.myUserId(),
                DIRECTORY_TRANSCODE);
        mTranscodeDirectory.mkdirs();
        mSessionTiming = new SessionTiming();
        mTranscodingUiNotifier = new TranscodeUiNotifier(context, mSessionTiming);
        mIsTranscodeEnabled = isTranscodeEnabled();
        int maxTranscodeDurationMs =
                mMediaProvider.getIntDeviceConfig(TRANSCODE_MAX_DURATION_MS_KEY,
                        MAX_TRANSCODE_DURATION_MS);
        mTranscodeDenialController = new TranscodeDenialController(mActivityManager,
                mTranscodingUiNotifier, maxTranscodeDurationMs);
        mSupportedRelativePaths = verifySupportedRelativePaths(StringUtils.getStringArrayConfig(
                        mContext, R.array.config_supported_transcoding_relative_paths));
        mHasHdrPlugin = hasHDRPlugin();

        parseTranscodeCompatManifest();
        // The storage namespace is a boot namespace so we actually don't expect this to be changed
        // after boot, but it is useful for tests
        mMediaProvider.addOnPropertiesChangedListener(properties -> parseTranscodeCompatManifest());
    }

    private boolean hasHDRPlugin() {
        MediaCodec decoder = null;
        boolean hasPlugin = false;
        try {
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            // We could query the HDR plugin with any resolution. But as normal HDR video is at
            // least 1080P(1920x1080), so we create a 1080P video format to query.
            MediaFormat decoderFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
            decoderFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            decoder.configure(decoderFormat, null, null, 0);
            MediaFormat inputFormat = decoder.getInputFormat();
            if (inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
                    == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
                hasPlugin = true;
            }
        } catch (Exception ioe) {
            hasPlugin = false;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Unable to stop decoder", e);
                }
            }
        }
        Log.i(TAG, "Device HDR Plugin is available: " + hasPlugin);
        return hasPlugin;
    }

    /**
     * Regex that matches path of transcode file. The regex only
     * matches emulated volume, for files in other volumes we don't
     * seamlessly transcode.
     */
    private static final Pattern PATTERN_TRANSCODE_PATH = Pattern.compile(
            "(?i)^/storage/emulated/(?:[0-9]+)/\\.transforms/transcode/(?:\\d+)$");
    private static final String DIRECTORY_TRANSCODE = ".transforms/transcode";
    /**
     * @return true if the file path matches transcode file path.
     */
    private static boolean isTranscodeFile(@NonNull String path) {
        final Matcher matcher = PATTERN_TRANSCODE_PATH.matcher(path);
        return matcher.matches();
    }

    public void freeCache(long bytes) {
        File[] files = mTranscodeDirectory.listFiles();
        for (File file : files) {
            if (bytes <= 0) {
                return;
            }
            if (file.exists() && file.isFile()) {
                long size = file.length();
                boolean deleted = file.delete();
                if (deleted) {
                    bytes -= size;
                }
            }
        }
    }

    private UUID getTranscodeVolumeUuid() {
        synchronized (mLock) {
            if (mTranscodeVolumeUuid != null) {
                return mTranscodeVolumeUuid;
            }
        }

        StorageVolume vol = mStorageManager.getStorageVolume(mTranscodeDirectory);
        if (vol != null) {
            synchronized (mLock) {
                mTranscodeVolumeUuid = vol.getStorageUuid();
                return mTranscodeVolumeUuid;
            }
        } else {
            Log.w(TAG, "Failed to get storage volume UUID for: " + mTranscodeDirectory);
            return null;
        }
    }

    /**
     * @return transcode file's path for given {@code rowId}
     */
    @NonNull
    private String getTranscodePath(long rowId) {
        return new File(mTranscodeDirectory, String.valueOf(rowId)).getAbsolutePath();
    }

    public void onAnrDelayStarted(String packageName, int uid, int tid, int reason) {
        if (!isTranscodeEnabled()) {
            return;
        }

        if (uid == MY_UID) {
            Log.w(TAG, "Skipping ANR delay handling for MediaProvider");
            return;
        }

        logVerbose("Checking transcode status during ANR of " + packageName);

        Set<StorageTranscodingSession> sessions = new ArraySet<>();
        synchronized (mLock) {
            sessions.addAll(mStorageTranscodingSessions.values());
        }

        for (StorageTranscodingSession session: sessions) {
            if (session.isUidBlocked(uid)) {
                session.setAnr();
                Log.i(TAG, "Package: " + packageName + " with uid: " + uid
                        + " and tid: " + tid + " is blocked on transcoding: " + session);
                // TODO(b/170973510): Show UI
            }
        }
    }

    // TODO(b/170974147): This should probably use a cache so we don't need to ask the
    // package manager every time for the package name or installer name
    private String getMetricsSafeNameForUid(int uid) {
        String name = mPackageManager.getNameForUid(uid);
        if (name == null) {
            Log.w(TAG, "null package name received from getNameForUid for uid " + uid
                    + ", logging uid instead.");
            return Integer.toString(uid);
        } else if (name.isEmpty()) {
            Log.w(TAG, "empty package name received from getNameForUid for uid " + uid
                    + ", logging uid instead");
            return ":empty_package_name:" + uid;
        } else {
            try {
                InstallSourceInfo installInfo = mPackageManager.getInstallSourceInfo(name);
                ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(name, 0);
                if (installInfo.getInstallingPackageName() == null
                        && ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)) {
                    // For privacy reasons, we don't log metrics for side-loaded packages that
                    // are not system packages
                    return ":installer_adb:" + uid;
                }
                return name;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Unable to check installer for uid: " + uid, e);
                return ":name_not_found:" + uid;
            }
        }
    }

    private void reportTranscodingResult(int uid, boolean success, int errorCode, int failureReason,
            long transcodingDurationMs,
            int transcodingReason, String src, String dst, boolean hasAnr) {
        BackgroundThread.getExecutor().execute(() -> {
            try (Cursor c = queryFileForTranscode(src,
                    new String[]{MediaColumns.DURATION, MediaColumns.CAPTURE_FRAMERATE,
                            MediaColumns.WIDTH, MediaColumns.HEIGHT})) {
                if (c != null && c.moveToNext()) {
                    MediaProviderStatsLog.write(
                            TRANSCODING_DATA,
                            getMetricsSafeNameForUid(uid),
                            MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__READ_TRANSCODE,
                            success ? new File(dst).length() : -1,
                            success ? TRANSCODING_DATA__TRANSCODE_RESULT__SUCCESS :
                                    TRANSCODING_DATA__TRANSCODE_RESULT__FAIL,
                            transcodingDurationMs,
                            c.getLong(0) /* video_duration */,
                            c.getLong(1) /* capture_framerate */,
                            transcodingReason,
                            c.getLong(2) /* width */,
                            c.getLong(3) /* height */,
                            hasAnr,
                            failureReason,
                            errorCode);
                }
            }
        });
    }

    public boolean transcode(String src, String dst, int uid, int reason) {
        // This can only happen when we are in a version that supports transcoding.
        // So, no need to check for the SDK version here.

        StorageTranscodingSession storageSession = null;
        TranscodingSession transcodingSession = null;
        CountDownLatch latch = null;
        long startTime = SystemClock.elapsedRealtime();
        boolean result = false;
        int errorCode = TranscodingSession.ERROR_SERVICE_DIED;
        int failureReason = TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_SERVICE_ERROR;

        try {
            synchronized (mLock) {
                storageSession = mStorageTranscodingSessions.get(src);
                if (storageSession == null) {
                    latch = new CountDownLatch(1);
                    try {
                        transcodingSession = enqueueTranscodingSession(src, dst, uid, latch);
                        if (transcodingSession == null) {
                            Log.e(TAG, "Failed to enqueue request due to Service unavailable");
                            throw new IllegalStateException("Failed to enqueue request");
                        }
                    } catch (UnsupportedOperationException | IOException e) {
                        throw new IllegalStateException(e);
                    }
                    storageSession = new StorageTranscodingSession(transcodingSession, latch,
                            src, dst);
                    mStorageTranscodingSessions.put(src, storageSession);
                } else {
                    latch = storageSession.latch;
                    transcodingSession = storageSession.session;
                    if (latch == null || transcodingSession == null) {
                        throw new IllegalStateException("Uninitialised TranscodingSession for uid: "
                                + uid + ". Path: " + src);
                    }
                }
                storageSession.addBlockedUid(uid);
            }

            failureReason = waitTranscodingResult(uid, src, transcodingSession, latch);
            errorCode = transcodingSession.getErrorCode();
            result = failureReason == TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN;

            if (result) {
                updateTranscodeStatus(src, TRANSCODE_COMPLETE);
            } else {
                logEvent("Transcoding failed for " + src + ". session: ", transcodingSession);
                // Attempt to workaround potential media transcoding deadlock
                // Cancelling a deadlocked session seems to unblock the transcoder
                transcodingSession.cancel();
            }
        } finally {
            if (storageSession == null) {
                Log.w(TAG, "Failed to create a StorageTranscodingSession");
                // We were unable to even queue the request. Which means the media service is
                // in a very bad state
                reportTranscodingResult(uid, result, errorCode, failureReason,
                        SystemClock.elapsedRealtime() - startTime, reason,
                        src, dst, false /* hasAnr */);
                return false;
            }

            storageSession.notifyFinished(failureReason, errorCode);
            if (errorCode == TranscodingSession.ERROR_DROPPED_BY_SERVICE) {
                // If the transcoding service drops a request for a uid the uid will be denied
                // transcoding access until the next boot, notify the denial controller which may
                // also show a denial UI
                mTranscodeDenialController.onTranscodingDropped(uid);
            }
            reportTranscodingResult(uid, result, errorCode, failureReason,
                    SystemClock.elapsedRealtime() - startTime, reason,
                    src, dst, storageSession.hasAnr());
        }
        return result;
    }

    /**
     * Returns IO path for a {@code path} and {@code uid}
     *
     * IO path is the actual path to be used on the lower fs for IO via FUSE. For some file
     * transforms, this path might be different from the path the app is requesting IO on.
     *
     * @param path file path to get an IO path for
     * @param uid app requesting IO
     *
     */
    public String prepareIoPath(String path, int uid) {
        // This can only happen when we are in a version that supports transcoding.
        // So, no need to check for the SDK version here.

        Pair<Long, Integer> cacheInfo = getTranscodeCacheInfoFromDB(path);
        final long rowId = cacheInfo.first;
        if (rowId == -1) {
            // No database row found, The file is pending/trashed or not added to database yet.
            // Assuming that no transcoding needed.
            return path;
        }

        int transcodeStatus = cacheInfo.second;
        final String transcodePath = getTranscodePath(rowId);
        final File transcodeFile = new File(transcodePath);

        if (transcodeFile.exists()) {
            return transcodePath;
        }

        if (transcodeStatus == TRANSCODE_COMPLETE) {
            // The transcode file doesn't exist but db row is marked as TRANSCODE_COMPLETE,
            // update db row to TRANSCODE_EMPTY so that cache state remains valid.
            updateTranscodeStatus(path, TRANSCODE_EMPTY);
        }

        final long maxFileSize = (long) (new File(path).length() * 2);
        if (createSparseFile(transcodeFile, maxFileSize)) {
            return transcodePath;
        }

        return "";
    }

    private static int getMediaCapabilitiesUid(int uid, Bundle bundle) {
        if (bundle == null || !bundle.containsKey(MediaStore.EXTRA_MEDIA_CAPABILITIES_UID)) {
            return uid;
        }

        int mediaCapabilitiesUid = bundle.getInt(MediaStore.EXTRA_MEDIA_CAPABILITIES_UID);
        if (mediaCapabilitiesUid >= Process.FIRST_APPLICATION_UID) {
            logVerbose(
                    "Media capabilities uid " + mediaCapabilitiesUid + ", passed for uid " + uid);
            return mediaCapabilitiesUid;
        }
        Log.w(TAG, "Ignoring invalid media capabilities uid " + mediaCapabilitiesUid
                + " for uid: " + uid);
        return uid;
    }

    // TODO(b/173491972): Generalize to consider other file/app media capabilities beyond hevc
    /**
     * @return 0 or >0 representing whether we should transcode or not.
     * 0 means we should not transcode, otherwise we should transcode and the value is the
     * reason that will be logged to statsd as a transcode reason. Possible values are:
     * <ul>
     * <li>MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__SYSTEM_DEFAULT=1
     * <li>MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__SYSTEM_CONFIG=2
     * <li>MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_MANIFEST=3
     * <li>MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_COMPAT=4
     * <li>MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_EXTRA=5
     * </ul>
     *
     */
    public int shouldTranscode(String path, int uid, Bundle bundle) {
        boolean isTranscodeEnabled = isTranscodeEnabled();
        updateConfigs(isTranscodeEnabled);

        if (!isTranscodeEnabled) {
            logVerbose("Transcode not enabled");
            return 0;
        }

        uid = getMediaCapabilitiesUid(uid, bundle);
        logVerbose("Checking shouldTranscode for: " + path + ". Uid: " + uid);

        if (!supportsTranscode(path) || uid < Process.FIRST_APPLICATION_UID || uid == MY_UID) {
            logVerbose("Transcode not supported");
            // Never transcode in any of these conditions
            // 1. Path doesn't support transcode
            // 2. Uid is from native process on device
            // 3. Uid is ourselves, which can happen when we are opening a file via FUSE for
            // redaction on behalf of another app via ContentResolver
            return 0;
        }

        // Transcode only if file needs transcoding
        Pair<Integer, Long> result = getFileFlagsAndDurationMs(path);
        int fileFlags = result.first;
        long durationMs = result.second;

        if (fileFlags == 0) {
            // Nothing to transcode
            logVerbose("File is not HEVC");
            return 0;
        }

        int accessReason = doesAppNeedTranscoding(uid, bundle, fileFlags, durationMs);
        if (accessReason != 0 && mTranscodeDenialController.checkFileAccess(uid, durationMs)) {
            logVerbose("Transcoding denied");
            return 0;
        }
        return accessReason;
    }

    @VisibleForTesting
    int doesAppNeedTranscoding(int uid, Bundle bundle, int fileFlags, long durationMs) {
        // Check explicit Bundle provided
        if (bundle != null) {
            if (bundle.getBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, false)) {
                logVerbose("Original format requested");
                return 0;
            }

            ApplicationMediaCapabilities capabilities =
                    bundle.getParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES);
            if (capabilities != null) {
                Pair<Integer, Integer> flags = capabilitiesToMediaFormatFlags(capabilities);
                Optional<Boolean> appExtraResult = checkAppMediaSupport(flags.first, flags.second,
                        fileFlags, "app_extra");
                if (appExtraResult.isPresent()) {
                    if (appExtraResult.get()) {
                        return MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_EXTRA;
                    }
                    return 0;
                }
                // Bundle didn't have enough information to make decision, continue
            }
        }

        // Check app compat support
        Optional<Boolean> appCompatResult = checkAppCompatSupport(uid, fileFlags);
        if (appCompatResult.isPresent()) {
            if (appCompatResult.get()) {
                return MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_COMPAT;
            }
            return 0;
        }
        // App compat didn't have enough information to make decision, continue

        // If we are here then the file supports HEVC, so we only check if the package is in the
        // mAppCompatCapabilities.  If it's there, we will respect that value.
        LocalCallingIdentity identity = mMediaProvider.getCachedCallingIdentityForTranscoding(uid);
        final String[] callingPackages = identity.getSharedPackageNames();

        // Check app manifest support
        for (String callingPackage : callingPackages) {
            Optional<Boolean> appManifestResult = checkManifestSupport(callingPackage, identity,
                    fileFlags);
            if (appManifestResult.isPresent()) {
                if (appManifestResult.get()) {
                    return MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_MANIFEST;
                }
                return 0;
            }
            // App manifest didn't have enough information to make decision, continue

            // TODO(b/169327180): We should also check app's targetSDK version to verify if app
            // still qualifies to be on these lists.
            // Check config compat manifest
            synchronized (mLock) {
                if (mAppCompatMediaCapabilities.containsKey(callingPackage)) {
                    int configCompatFlags = mAppCompatMediaCapabilities.get(callingPackage);
                    int supportedFlags = configCompatFlags;
                    int unsupportedFlags = ~configCompatFlags & MEDIA_FORMAT_FLAG_MASK;

                    Optional<Boolean> systemConfigResult = checkAppMediaSupport(supportedFlags,
                            unsupportedFlags, fileFlags, "system_config");
                    if (systemConfigResult.isPresent()) {
                        if (systemConfigResult.get()) {
                            return MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__SYSTEM_CONFIG;
                        }
                        return 0;
                    }
                    // Should never get here because the supported & unsupported flags should span
                    // the entire universe of file flags
                }
            }
        }

        // TODO: Need to add transcode_default as flags
        if (shouldTranscodeDefault()) {
            logVerbose("Default behavior should transcode");
            return MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__SYSTEM_DEFAULT;
        } else {
            logVerbose("Default behavior should not transcode");
            return 0;
        }
    }

    /**
     * Checks if transcode is required for the given app media capabilities and file media formats
     *
     * @param appSupportedMediaFormatFlags bit mask of media capabilites explicitly supported by an
     * app, e.g 001 indicating HEVC support
     * @param appUnsupportedMediaFormatFlags bit mask of media capabilites explicitly not supported
     * by an app, e.g 10 indicating HDR_10 is not supportted
     * @param fileMediaFormatFlags bit mask of media capabilites contained in a file e.g 101
     * indicating HEVC and HDR_10 media file
     *
     * @return {@code Optional} containing {@code boolean}. {@code true} means transcode is
     * required, {@code false} means transcode is not required and {@code empty} means a decision
     * could not be made.
     */
    private Optional<Boolean> checkAppMediaSupport(int appSupportedMediaFormatFlags,
            int appUnsupportedMediaFormatFlags, int fileMediaFormatFlags, String type) {
        if ((appSupportedMediaFormatFlags & appUnsupportedMediaFormatFlags) != 0) {
            Log.w(TAG, "Ignoring app media capabilities for type: [" + type
                    + "]. Supported and unsupported capapbilities are not mutually exclusive");
            return Optional.empty();
        }

        // As an example:
        // 1. appSupportedMediaFormatFlags=001   # App supports HEVC
        // 2. appUnsupportedMediaFormatFlags=100 # App does not support HDR_10
        // 3. fileSupportedMediaFormatFlags=101  # File contains HEVC and HDR_10

        // File contains HDR_10 but app explicitly doesn't support it
        int fileMediaFormatsUnsupportedByApp =
                fileMediaFormatFlags & appUnsupportedMediaFormatFlags;
        if (fileMediaFormatsUnsupportedByApp != 0) {
            // If *any* file media formats are unsupported by the app we need to transcode
            logVerbose("App media capability check for type: [" + type + "]" + ". transcode=true");
            return Optional.of(true);
        }

        // fileMediaFormatsSupportedByApp=001 # File contains HEVC but app explicitly supports HEVC
        int fileMediaFormatsSupportedByApp = appSupportedMediaFormatFlags & fileMediaFormatFlags;
        // fileMediaFormatsNotSupportedByApp=100 # File contains HDR_10 but app doesn't support it
        int fileMediaFormatsNotSupportedByApp =
                fileMediaFormatsSupportedByApp ^ fileMediaFormatFlags;
        if (fileMediaFormatsNotSupportedByApp == 0) {
            logVerbose("App media capability check for type: [" + type + "]" + ". transcode=false");
            // If *all* file media formats are supported by the app, we don't need to transcode
            return Optional.of(false);
        }

        // If there are some file media formats that are neither supported nor unsupported by the
        // app we can't make a decision yet
        return Optional.empty();
    }

    private Pair<Integer, Long> getFileFlagsAndDurationMs(String path) {
        final String[] projection = new String[] {
            FileColumns._VIDEO_CODEC_TYPE,
            VideoColumns.COLOR_STANDARD,
            VideoColumns.COLOR_TRANSFER,
            MediaColumns.DURATION
        };

        try (Cursor cursor = queryFileForTranscode(path, projection)) {
            if (cursor == null || !cursor.moveToNext()) {
                logVerbose("Couldn't find database row");
                return Pair.create(0, 0L);
            }

            int result = 0;
            boolean isHdr10Plus = isHdr10Plus(cursor.getInt(1), cursor.getInt(2));
            // If the video is a HDR video and the device does not have HDR plugin, we will return
            // the original file regardless whether the app supports HEVC due to not all the devices
            // support transcoding 10bit HEVC to 8bit AVC. This check needs to be removed when
            // devices add support for it.
            boolean isTranscodeUnsupported = isHdr10Plus && !mHasHdrPlugin;
            if (isTranscodeUnsupported) {
                return Pair.create(0, 0L);
            }

            if (isHevc(cursor.getString(0))) {
                result |= FLAG_HEVC;
            }
            // Set the HDR flag if the device has HDR plugin. If HDR plugin is not available,
            // we will make the transcode decision based on whether the app supports HEVC or not.
            if (isHdr10Plus) {
                result |= FLAG_HDR_10_PLUS;
            }
            return Pair.create(result, cursor.getLong(3));
        }
    }

    private static boolean isHevc(String mimeType) {
        return MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(mimeType);
    }

    private static boolean isHdr10Plus(int colorStandard, int colorTransfer) {
        return (colorStandard == MediaFormat.COLOR_STANDARD_BT2020) &&
                (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
                        || colorTransfer == MediaFormat.COLOR_TRANSFER_HLG);
    }

    private static boolean isModernFormat(String mimeType, int colorStandard, int colorTransfer) {
        return isHevc(mimeType) || isHdr10Plus(colorStandard, colorTransfer);
    }

    public boolean supportsTranscode(String path) {
        final File file = new File(path);
        final String name = file.getName();
        final String relativePath = FileUtils.extractRelativePath(path);

        if (isTranscodeFile(path) || !name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || !path.startsWith("/storage/emulated/")) {
            return false;
        }

        for (String supportedRelativePath : mSupportedRelativePaths) {
            if (supportedRelativePath.equalsIgnoreCase(relativePath)) {
                return true;
            }
        }

        return false;
    }

    private static List<String> verifySupportedRelativePaths(List<String> relativePaths) {
        final List<String> verifiedPaths = new ArrayList<>();
        final String lowerCaseDcimDir = Environment.DIRECTORY_DCIM.toLowerCase(Locale.ROOT) + "/";

        for (String path : relativePaths) {
            if (path.toLowerCase(Locale.ROOT).startsWith(lowerCaseDcimDir) && path.endsWith("/")) {
                verifiedPaths.add(path);
            } else {
                Log.w(TAG, "Transcoding relative path must be a descendant of DCIM/ and end with"
                        + " '/'. Ignoring: " + path);
            }
        }

        return verifiedPaths;
    }

    private Optional<Boolean> checkAppCompatSupport(int uid, int fileFlags) {
        int supportedFlags = 0;
        int unsupportedFlags = 0;
        boolean hevcSupportEnabled = CompatChanges.isChangeEnabled(FORCE_ENABLE_HEVC_SUPPORT, uid);
        boolean hevcSupportDisabled = CompatChanges.isChangeEnabled(FORCE_DISABLE_HEVC_SUPPORT,
                uid);
        if (hevcSupportEnabled) {
            supportedFlags = FLAG_HEVC;
            logVerbose("App compat hevc support enabled");
        }

        if (hevcSupportDisabled) {
            unsupportedFlags = FLAG_HEVC;
            logVerbose("App compat hevc support disabled");
        }
        return checkAppMediaSupport(supportedFlags, unsupportedFlags, fileFlags, "app_compat");
    }

    /**
     * @return {@code true} if HEVC is explicitly supported by the manifest of {@code packageName},
     * {@code false} otherwise.
     */
    private Optional<Boolean> checkManifestSupport(String packageName,
            LocalCallingIdentity identity, int fileFlags) {
        // TODO(b/169327180):
        // 1. Support beyond HEVC
        // 2. Shared package names policy:
        // If appA and appB share the same uid. And appA supports HEVC but appB doesn't.
        // Should we assume entire uid supports or doesn't?
        // For now, we assume uid supports, but this might change in future
        int supportedFlags = identity.getApplicationMediaCapabilitiesSupportedFlags();
        int unsupportedFlags = identity.getApplicationMediaCapabilitiesUnsupportedFlags();
        if (supportedFlags != -1 && unsupportedFlags != -1) {
            return checkAppMediaSupport(supportedFlags, unsupportedFlags, fileFlags,
                    "cached_app_manifest");
        }

        try {
            Property mediaCapProperty = mPackageManager.getProperty(
                    PackageManager.PROPERTY_MEDIA_CAPABILITIES, packageName);
            XmlResourceParser parser = mPackageManager.getResourcesForApplication(packageName)
                    .getXml(mediaCapProperty.getResourceId());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
            Pair<Integer, Integer> flags = capabilitiesToMediaFormatFlags(capability);
            supportedFlags = flags.first;
            unsupportedFlags = flags.second;
            identity.setApplicationMediaCapabilitiesFlags(supportedFlags, unsupportedFlags);

            return checkAppMediaSupport(supportedFlags, unsupportedFlags, fileFlags,
                    "app_manifest");
        } catch (PackageManager.NameNotFoundException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    @ApplicationMediaCapabilitiesFlags
    private Pair<Integer, Integer> capabilitiesToMediaFormatFlags(
            ApplicationMediaCapabilities capability) {
        int supportedFlags = 0;
        int unsupportedFlags = 0;

        // MimeType
        if (capability.isFormatSpecified(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            if (capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                supportedFlags |= FLAG_HEVC;
            } else {
                unsupportedFlags |= FLAG_HEVC;
            }
        }

        // HdrType
        if (capability.isFormatSpecified(MediaFeature.HdrType.HDR10)) {
            if (capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10)) {
                supportedFlags |= FLAG_HDR_10;
            } else {
                unsupportedFlags |= FLAG_HDR_10;
            }
        }

        if (capability.isFormatSpecified(MediaFeature.HdrType.HDR10_PLUS)) {
            if (capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10_PLUS)) {
                supportedFlags |= FLAG_HDR_10_PLUS;
            } else {
                unsupportedFlags |= FLAG_HDR_10_PLUS;
            }
        }

        if (capability.isFormatSpecified(MediaFeature.HdrType.HLG)) {
            if (capability.isHdrTypeSupported(MediaFeature.HdrType.HLG)) {
                supportedFlags |= FLAG_HDR_HLG;
            } else {
                unsupportedFlags |= FLAG_HDR_HLG;
            }
        }

        if (capability.isFormatSpecified(MediaFeature.HdrType.DOLBY_VISION)) {
            if (capability.isHdrTypeSupported(MediaFeature.HdrType.DOLBY_VISION)) {
                supportedFlags |= FLAG_HDR_DOLBY_VISION;
            } else {
                unsupportedFlags |= FLAG_HDR_DOLBY_VISION;
            }
        }

        return Pair.create(supportedFlags, unsupportedFlags);
    }

    private boolean getBooleanProperty(String sysPropKey, String deviceConfigKey,
            boolean defaultValue) {
        // If the user wants to override the default, respect that; otherwise use the DeviceConfig
        // which is filled with the values sent from server.
        if (SystemProperties.getBoolean(TRANSCODE_USER_CONTROL_SYS_PROP_KEY, false)) {
            return SystemProperties.getBoolean(sysPropKey, defaultValue);
        }

        return mMediaProvider.getBooleanDeviceConfig(deviceConfigKey, defaultValue);
    }

    private Pair<Long, Integer> getTranscodeCacheInfoFromDB(String path) {
        try (Cursor cursor = queryFileForTranscode(path, TRANSCODE_CACHE_INFO_PROJECTION)) {
            if (cursor != null && cursor.moveToNext()) {
                return Pair.create(cursor.getLong(0), cursor.getInt(1));
            }
        }
        return Pair.create((long) -1, TRANSCODE_EMPTY);
    }

    // called from MediaProvider
    public void onUriPublished(Uri uri) {
        if (!isTranscodeEnabled()) {
            return;
        }

        try (Cursor c = mMediaProvider.queryForSingleItem(uri,
                new String[]{
                        FileColumns._VIDEO_CODEC_TYPE,
                        FileColumns.SIZE,
                        FileColumns.OWNER_PACKAGE_NAME,
                        FileColumns.DATA,
                        MediaColumns.DURATION,
                        MediaColumns.CAPTURE_FRAMERATE,
                        MediaColumns.WIDTH,
                        MediaColumns.HEIGHT
                },
                null, null, null)) {
            if (supportsTranscode(c.getString(3))) {
                if (isHevc(c.getString(0))) {
                    MediaProviderStatsLog.write(
                            TRANSCODING_DATA,
                            c.getString(2) /* owner_package_name */,
                            MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__HEVC_WRITE,
                            c.getLong(1) /* file size */,
                            TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                            -1 /* transcoding_duration */,
                            c.getLong(4) /* video_duration */,
                            c.getLong(5) /* capture_framerate */,
                            -1 /* transcode_reason */,
                            c.getLong(6) /* width */,
                            c.getLong(7) /* height */,
                            false /* hit_anr */,
                            TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN,
                            TranscodingSession.ERROR_NONE);

                } else {
                    MediaProviderStatsLog.write(
                            TRANSCODING_DATA,
                            c.getString(2) /* owner_package_name */,
                            MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__AVC_WRITE,
                            c.getLong(1) /* file size */,
                            TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                            -1 /* transcoding_duration */,
                            c.getLong(4) /* video_duration */,
                            c.getLong(5) /* capture_framerate */,
                            -1 /* transcode_reason */,
                            c.getLong(6) /* width */,
                            c.getLong(7) /* height */,
                            false /* hit_anr */,
                            TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN,
                            TranscodingSession.ERROR_NONE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't get cursor for scanned file", e);
        }
    }

    public void onFileOpen(String path, String ioPath, int uid, int transformsReason) {
        if (!isTranscodeEnabled() || !supportsTranscode(path)) {
            return;
        }

        String[] resolverInfoProjection = new String[] {
                    FileColumns._VIDEO_CODEC_TYPE,
                    FileColumns.SIZE,
                    MediaColumns.DURATION,
                    MediaColumns.CAPTURE_FRAMERATE,
                    MediaColumns.WIDTH,
                    MediaColumns.HEIGHT,
                    VideoColumns.COLOR_STANDARD,
                    VideoColumns.COLOR_TRANSFER
        };

        try (Cursor c = queryFileForTranscode(path, resolverInfoProjection)) {
            if (c != null && c.moveToNext()
                    && isModernFormat(c.getString(0), c.getInt(6), c.getInt(7))) {
                if (transformsReason == 0) {
                    MediaProviderStatsLog.write(
                            TRANSCODING_DATA,
                            getMetricsSafeNameForUid(uid) /* owner_package_name */,
                            MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__READ_DIRECT,
                            c.getLong(1) /* file size */,
                            TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                            -1 /* transcoding_duration */,
                            c.getLong(2) /* video_duration */,
                            c.getLong(3) /* capture_framerate */,
                            -1 /* transcode_reason */,
                            c.getLong(4) /* width */,
                            c.getLong(5) /* height */,
                            false /*hit_anr*/,
                            TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN,
                            TranscodingSession.ERROR_NONE);
                } else if (isTranscodeFileCached(path, ioPath)) {
                    MediaProviderStatsLog.write(
                            TRANSCODING_DATA,
                            getMetricsSafeNameForUid(uid) /* owner_package_name */,
                            MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__READ_CACHE,
                            c.getLong(1) /* file size */,
                            TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                            -1 /* transcoding_duration */,
                            c.getLong(2) /* video_duration */,
                            c.getLong(3) /* capture_framerate */,
                            transformsReason /* transcode_reason */,
                            c.getLong(4) /* width */,
                            c.getLong(5) /* height */,
                            false /*hit_anr*/,
                            TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN,
                            TranscodingSession.ERROR_NONE);
                } // else if file is not in cache, we'll log at read(2) when we transcode
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Unable to log metrics on file open", e);
        }
    }

    public boolean isTranscodeFileCached(String path, String transcodePath) {
        // This can only happen when we are in a version that supports transcoding.
        // So, no need to check for the SDK version here.

        if (SystemProperties.getBoolean("persist.sys.fuse.disable_transcode_cache", false)) {
            // Caching is disabled. Hence, delete the cached transcode file.
            return false;
        }

        Pair<Long, Integer> cacheInfo = getTranscodeCacheInfoFromDB(path);
        final long rowId = cacheInfo.first;
        if (rowId != -1) {
            final int transcodeStatus = cacheInfo.second;
            boolean result = transcodePath.equalsIgnoreCase(getTranscodePath(rowId)) &&
                    transcodeStatus == TRANSCODE_COMPLETE &&
                    new File(transcodePath).exists();
            if (result) {
                logEvent("Transcode cache hit: " + path, null /* session */);
            }
            return result;
        }
        return false;
    }

    @Nullable
    private MediaFormat getVideoTrackFormat(String path) {
        String[] resolverInfoProjection = new String[]{
                FileColumns._VIDEO_CODEC_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.BITRATE,
                MediaStore.MediaColumns.CAPTURE_FRAMERATE
        };
        try (Cursor c = queryFileForTranscode(path, resolverInfoProjection)) {
            if (c != null && c.moveToNext()) {
                String codecType = c.getString(0);
                int width = c.getInt(1);
                int height = c.getInt(2);
                int bitRate = c.getInt(3);
                float framerate = c.getFloat(4);

                // TODO(b/169849854): Get this info from Manifest, for now if app got here it
                // definitely doesn't support hevc
                ApplicationMediaCapabilities capability =
                        new ApplicationMediaCapabilities.Builder().build();
                MediaFormat sourceFormat = MediaFormat.createVideoFormat(
                        codecType, width, height);
                if (framerate > 0) {
                    sourceFormat.setFloat(MediaFormat.KEY_FRAME_RATE, framerate);
                }
                VideoFormatResolver resolver = new VideoFormatResolver(capability, sourceFormat);
                MediaFormat resolvedFormat = resolver.resolveVideoFormat();
                resolvedFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);

                return resolvedFormat;
            }
        }
        throw new IllegalStateException("Couldn't get video format info from database for " + path);
    }

    private TranscodingSession enqueueTranscodingSession(String src, String dst, int uid,
            final CountDownLatch latch) throws UnsupportedOperationException, IOException {
        // Fetch the service lazily to improve memory usage
        final MediaTranscodingManager mediaTranscodeManager =
                mContext.getSystemService(MediaTranscodingManager.class);
        File file = new File(src);
        File transcodeFile = new File(dst);

        // These are file URIs (effectively file paths) and even if the |transcodeFile| is
        // inaccesible via FUSE, it works because the transcoding service calls into the
        // MediaProvider to open them and within the MediaProvider, it is opened directly on
        // the lower fs.
        Uri uri = Uri.fromFile(file);
        Uri transcodeUri = Uri.fromFile(transcodeFile);

        ParcelFileDescriptor srcPfd = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor dstPfd = ParcelFileDescriptor.open(transcodeFile,
                ParcelFileDescriptor.MODE_READ_WRITE);

        MediaFormat format = getVideoTrackFormat(src);

        VideoTranscodingRequest request =
                new VideoTranscodingRequest.Builder(uri, transcodeUri, format)
                        .setClientUid(uid)
                        .setSourceFileDescriptor(srcPfd)
                        .setDestinationFileDescriptor(dstPfd)
                        .build();

        TranscodingSession session = mediaTranscodeManager.enqueueRequest(request,
                ForegroundThread.getExecutor(),
                s -> {
                    mTranscodingUiNotifier.stop(s, src);
                    finishTranscodingResult(uid, src, s, latch);
                    mSessionTiming.logSessionEnd(s);
                });
        session.setOnProgressUpdateListener(ForegroundThread.getExecutor(),
                (s, progress) -> mTranscodingUiNotifier.setProgress(s, src, progress));

        mSessionTiming.logSessionStart(session);
        mTranscodingUiNotifier.start(session, src);
        logEvent("Transcoding start: " + src + ". Uid: " + uid, session);
        return session;
    }

    /**
     * Returns an {@link Integer} indicating whether the transcoding {@code session} was successful
     * or not.
     *
     * @return {@link TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN} on success,
     * otherwise indicates failure.
     */
    private int waitTranscodingResult(int uid, String src, TranscodingSession session,
            CountDownLatch latch) {
        UUID uuid = getTranscodeVolumeUuid();
        try {
            if (uuid != null) {
                // tid is 0 since we can't really get the apps tid over binder
                mStorageManager.notifyAppIoBlocked(uuid, uid, 0 /* tid */,
                        StorageManager.APP_IO_BLOCKED_REASON_TRANSCODING);
            }

            int timeout = getTranscodeTimeoutSeconds(src);

            String waitStartLog = "Transcoding wait start: " + src + ". Uid: " + uid + ". Timeout: "
                    + timeout + "s";
            logEvent(waitStartLog, session);

            boolean latchResult = latch.await(timeout, TimeUnit.SECONDS);
            int sessionResult = session.getResult();
            boolean transcodeResult = sessionResult == TranscodingSession.RESULT_SUCCESS;

            String waitEndLog = "Transcoding wait end: " + src + ". Uid: " + uid + ". Timeout: "
                    + !latchResult + ". Success: " + transcodeResult;
            logEvent(waitEndLog, session);

            if (sessionResult == TranscodingSession.RESULT_SUCCESS) {
                return TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN;
            } else if (sessionResult == TranscodingSession.RESULT_CANCELED) {
                return TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_SESSION_CANCELED;
            } else if (!latchResult) {
                return TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_CLIENT_TIMEOUT;
            } else {
                return TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_SERVICE_ERROR;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Transcoding latch interrupted." + session);
            return TRANSCODING_DATA__FAILURE_CAUSE__TRANSCODING_CLIENT_TIMEOUT;
        } finally {
            if (uuid != null) {
                // tid is 0 since we can't really get the apps tid over binder
                mStorageManager.notifyAppIoResumed(uuid, uid, 0 /* tid */,
                        StorageManager.APP_IO_BLOCKED_REASON_TRANSCODING);
            }
        }
    }

    private int getTranscodeTimeoutSeconds(String file) {
        double sizeMb = (new File(file).length() / (1024 * 1024));
        // Ensure size is at least 1MB so transcoding timeout is at least the timeout coefficient
        sizeMb = Math.max(sizeMb, 1);
        return (int) (sizeMb * TRANSCODING_TIMEOUT_COEFFICIENT);
    }

    private void finishTranscodingResult(int uid, String src, TranscodingSession session,
            CountDownLatch latch) {
        final StorageTranscodingSession finishedSession;

        synchronized (mLock) {
            latch.countDown();
            session.cancel();

            finishedSession = mStorageTranscodingSessions.remove(src);

            switch (session.getResult()) {
                case TranscodingSession.RESULT_SUCCESS:
                    mSuccessfulTranscodeSessions.put(finishedSession, false /* placeholder */);
                    break;
                case TranscodingSession.RESULT_CANCELED:
                    mCancelledTranscodeSessions.put(finishedSession, false /* placeholder */);
                    break;
                case TranscodingSession.RESULT_ERROR:
                    mErroredTranscodeSessions.put(finishedSession, false /* placeholder */);
                    break;
                default:
                    Log.w(TAG, "TranscodingSession.RESULT_NONE received for a finished session");
            }
        }

        logEvent("Transcoding end: " + src + ". Uid: " + uid, session);
    }

    private boolean updateTranscodeStatus(String path, int transcodeStatus) {
        final Uri uri = FileUtils.getContentUriForPath(path);
        // TODO(b/170465810): Replace this with matchUri when the code is refactored.
        final int match = MediaProvider.FILES;
        final SQLiteQueryBuilder qb = mMediaProvider.getQueryBuilderForTranscoding(TYPE_UPDATE,
                match, uri, Bundle.EMPTY, null);
        final String[] selectionArgs = new String[]{path};

        ContentValues values = new ContentValues();
        values.put(FileColumns._TRANSCODE_STATUS, transcodeStatus);
        final boolean success = qb.update(getDatabaseHelperForUri(uri), values,
                TRANSCODE_WHERE_CLAUSE, selectionArgs) == 1;
        if (!success) {
            Log.w(TAG, "Transcoding status update to: " + transcodeStatus + " failed for " + path);
        }
        return success;
    }

    public boolean deleteCachedTranscodeFile(long rowId) {
        return new File(mTranscodeDirectory, String.valueOf(rowId)).delete();
    }

    private DatabaseHelper getDatabaseHelperForUri(Uri uri) {
        final DatabaseHelper helper;
        try {
            return mMediaProvider.getDatabaseForUriForTranscoding(uri);
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Volume not found while querying transcode path", e);
        }
    }

    /**
     * @return given {@code projection} columns from database for given {@code path}.
     * Note that cursor might be empty if there is no database row or file is pending or trashed.
     * TODO(b/170465810): Optimize these queries by bypassing getQueryBuilder(). These queries are
     * always on Files table and doesn't have any dependency on calling package. i.e., query is
     * always called with callingPackage=self.
     */
    @Nullable
    private Cursor queryFileForTranscode(String path, String[] projection) {
        final Uri uri = FileUtils.getContentUriForPath(path);
        // TODO(b/170465810): Replace this with matchUri when the code is refactored.
        final int match = MediaProvider.FILES;
        final SQLiteQueryBuilder qb = mMediaProvider.getQueryBuilderForTranscoding(TYPE_QUERY,
                match, uri, Bundle.EMPTY, null);
        final String[] selectionArgs = new String[]{path};

        Bundle extras = new Bundle();
        extras.putInt(QUERY_ARG_MATCH_PENDING, MATCH_EXCLUDE);
        extras.putInt(QUERY_ARG_MATCH_TRASHED, MATCH_EXCLUDE);
        extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, TRANSCODE_WHERE_CLAUSE);
        extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        return qb.query(getDatabaseHelperForUri(uri), projection, extras, null);
    }

    private boolean isTranscodeEnabled() {
        return IS_TRANSCODING_SUPPORTED && getBooleanProperty(TRANSCODE_ENABLED_SYS_PROP_KEY,
                TRANSCODE_ENABLED_DEVICE_CONFIG_KEY, true /* defaultValue */);
    }

    private boolean shouldTranscodeDefault() {
        return getBooleanProperty(TRANSCODE_DEFAULT_SYS_PROP_KEY,
                TRANSCODE_DEFAULT_DEVICE_CONFIG_KEY, false /* defaultValue */);
    }

    private void updateConfigs(boolean transcodeEnabled) {
        synchronized (mLock) {
            boolean isTranscodeEnabledChanged = transcodeEnabled != mIsTranscodeEnabled;

            if (isTranscodeEnabledChanged) {
                Log.i(TAG, "Reloading transcode configs. transcodeEnabled: " + transcodeEnabled
                        + ". lastTranscodeEnabled: " + mIsTranscodeEnabled);

                mIsTranscodeEnabled = transcodeEnabled;
                parseTranscodeCompatManifest();
            }
        }
    }

    private void parseTranscodeCompatManifest() {
        synchronized (mLock) {
            // Clear the transcode_compat manifest before parsing. If transcode is disabled,
            // nothing will be parsed, effectively leaving the compat manifest empty.
            mAppCompatMediaCapabilities.clear();
            if (!mIsTranscodeEnabled) {
                return;
            }

            Set<String> stalePackages = getTranscodeCompatStale();
            parseTranscodeCompatManifestFromResourceLocked(stalePackages);
            parseTranscodeCompatManifestFromDeviceConfigLocked();
        }
    }

    /** @return {@code true} if the manifest was parsed successfully, {@code false} otherwise */
    private boolean parseTranscodeCompatManifestFromDeviceConfigLocked() {
        final String[] manifest = mMediaProvider.getStringDeviceConfig(
                TRANSCODE_COMPAT_MANIFEST_KEY, "").split(",");

        if (manifest.length == 0 || manifest[0].isEmpty()) {
            Log.i(TAG, "Empty device config transcode compat manifest");
            return false;
        }
        if ((manifest.length % 2) != 0) {
            Log.w(TAG, "Uneven number of items in device config transcode compat manifest");
            return false;
        }

        String packageName = "";
        int packageCompatValue;
        int i = 0;
        int count = 0;
        while (i < manifest.length - 1) {
            try {
                packageName = manifest[i++];
                packageCompatValue = Integer.parseInt(manifest[i++]);
                synchronized (mLock) {
                    // Lock is already held, explicitly hold again to make error prone happy
                    mAppCompatMediaCapabilities.put(packageName, packageCompatValue);
                    count++;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse media capability from device config for package: "
                        + packageName, e);
            }
        }

        Log.i(TAG, "Parsed " + count + " packages from device config");
        return count != 0;
    }

    /** @return {@code true} if the manifest was parsed successfully, {@code false} otherwise */
    private boolean parseTranscodeCompatManifestFromResourceLocked(Set<String> stalePackages) {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.transcode_compat_manifest);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        int count = 0;
        try {
            while (reader.ready()) {
                String line = reader.readLine();
                String packageName = "";
                int packageCompatValue;

                if (line == null) {
                    Log.w(TAG, "Unexpected null line while parsing transcode compat manifest");
                    continue;
                }

                String[] lineValues = line.split(",");
                if (lineValues.length != 2) {
                    Log.w(TAG, "Failed to read line while parsing transcode compat manifest");
                    continue;
                }
                try {
                    packageName = lineValues[0];
                    packageCompatValue = Integer.parseInt(lineValues[1]);

                    if (stalePackages.contains(packageName)) {
                        Log.i(TAG, "Skipping stale package in transcode compat manifest: "
                                + packageName);
                        continue;
                    }

                    synchronized (mLock) {
                        // Lock is already held, explicitly hold again to make error prone happy
                        mAppCompatMediaCapabilities.put(packageName, packageCompatValue);
                        count++;
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse media capability from resource for package: "
                            + packageName, e);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read transcode compat manifest", e);
        }

        Log.i(TAG, "Parsed " + count + " packages from resource");
        return count != 0;
    }

    private Set<String> getTranscodeCompatStale() {
        Set<String> stalePackages = new ArraySet<>();
        final String[] staleConfig = mMediaProvider.getStringDeviceConfig(
                TRANSCODE_COMPAT_STALE_KEY, "").split(",");

        if (staleConfig.length == 0 || staleConfig[0].isEmpty()) {
            Log.i(TAG, "Empty transcode compat stale");
            return stalePackages;
        }

        for (String stalePackage : staleConfig) {
            stalePackages.add(stalePackage);
        }

        int size = stalePackages.size();
        Log.i(TAG, "Parsed " + size + " stale packages from device config");
        return stalePackages;
    }

    public void dump(PrintWriter writer) {
        writer.println("isTranscodeEnabled=" + isTranscodeEnabled());
        writer.println("shouldTranscodeDefault=" + shouldTranscodeDefault());

        synchronized (mLock) {
            writer.println("mAppCompatMediaCapabilities=" + mAppCompatMediaCapabilities);
            writer.println("mStorageTranscodingSessions=" + mStorageTranscodingSessions);
            writer.println("mSupportedTranscodingRelativePaths=" + mSupportedRelativePaths);
            writer.println("mHasHdrPlugin=" + mHasHdrPlugin);
            dumpFinishedSessions(writer);
        }
    }

    public List<String> getSupportedRelativePaths() {
        return mSupportedRelativePaths;
    }

    private void dumpFinishedSessions(PrintWriter writer) {
        synchronized (mLock) {
            writer.println("mSuccessfulTranscodeSessions=" + mSuccessfulTranscodeSessions.keySet());

            writer.println("mCancelledTranscodeSessions=" + mCancelledTranscodeSessions.keySet());

            writer.println("mErroredTranscodeSessions=" + mErroredTranscodeSessions.keySet());
        }
    }

    private static void logEvent(String event, @Nullable TranscodingSession session) {
        Log.d(TAG, event + (session == null ? "" : session));
    }

    private static void logVerbose(String message) {
        if (DEBUG) {
            Log.v(TAG, message);
        }
    }

    // We want to keep track of only the most recent [MAX_FINISHED_TRANSCODING_SESSION_STORE_COUNT]
    // finished transcoding sessions.
    private static LinkedHashMap createFinishedTranscodingSessionMap() {
        return new LinkedHashMap<StorageTranscodingSession, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Entry eldest) {
                return size() > MAX_FINISHED_TRANSCODING_SESSION_STORE_COUNT;
            }
        };
    }

    @VisibleForTesting
    static int getMyUid() {
        return MY_UID;
    }

    private static class StorageTranscodingSession {
        private static final DateTimeFormatter DATE_FORMAT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        public final TranscodingSession session;
        public final CountDownLatch latch;
        private final String mSrcPath;
        private final String mDstPath;
        @GuardedBy("latch")
        private final Set<Integer> mBlockedUids = new ArraySet<>();
        private final LocalDateTime mStartTime;
        @GuardedBy("latch")
        private LocalDateTime mFinishTime;
        @GuardedBy("latch")
        private boolean mHasAnr;
        @GuardedBy("latch")
        private int mFailureReason;
        @GuardedBy("latch")
        private int mErrorCode;

        public StorageTranscodingSession(TranscodingSession session, CountDownLatch latch,
                String srcPath, String dstPath) {
            this.session = session;
            this.latch = latch;
            this.mSrcPath = srcPath;
            this.mDstPath = dstPath;
            this.mStartTime = LocalDateTime.now();
            mErrorCode = TranscodingSession.ERROR_NONE;
            mFailureReason = TRANSCODING_DATA__FAILURE_CAUSE__CAUSE_UNKNOWN;
        }

        public void addBlockedUid(int uid) {
            session.addClientUid(uid);
        }

        public boolean isUidBlocked(int uid) {
            return session.getClientUids().contains(uid);
        }

        public void setAnr() {
            synchronized (latch) {
                mHasAnr = true;
            }
        }

        public boolean hasAnr() {
            synchronized (latch) {
                return mHasAnr;
            }
        }

        public void notifyFinished(int failureReason, int errorCode) {
            synchronized (latch) {
                mFinishTime = LocalDateTime.now();
                mFailureReason = failureReason;
                mErrorCode = errorCode;
            }
        }

        @Override
        public String toString() {
            String startTime = mStartTime.format(DATE_FORMAT);
            String finishTime = "NONE";
            String durationMs = "NONE";
            boolean hasAnr;
            int failureReason;
            int errorCode;

            synchronized (latch) {
                if (mFinishTime != null) {
                    finishTime = mFinishTime.format(DATE_FORMAT);
                    durationMs = String.valueOf(mStartTime.until(mFinishTime, ChronoUnit.MILLIS));
                }
                hasAnr = mHasAnr;
                failureReason = mFailureReason;
                errorCode = mErrorCode;
            }

            return String.format("<%s. Src: %s. Dst: %s. BlockedUids: %s. DurationMs: %sms"
                    + ". Start: %s. Finish: %sms. HasAnr: %b. FailureReason: %d. ErrorCode: %d>",
                    session.toString(), mSrcPath, mDstPath, session.getClientUids(), durationMs,
                    startTime, finishTime, hasAnr, failureReason, errorCode);
        }
    }

    private static class TranscodeUiNotifier {
        private static final int PROGRESS_MAX = 100;
        private static final int ALERT_DISMISS_DELAY_MS = 1000;
        private static final int SHOW_PROGRESS_THRESHOLD_TIME_MS = 1000;
        private static final String TRANSCODE_ALERT_CHANNEL_ID = "native_transcode_alert_channel";
        private static final String TRANSCODE_ALERT_CHANNEL_NAME = "Native Transcode Alerts";
        private static final String TRANSCODE_PROGRESS_CHANNEL_ID =
                "native_transcode_progress_channel";
        private static final String TRANSCODE_PROGRESS_CHANNEL_NAME = "Native Transcode Progress";

        // Related to notification settings
        private static final String TRANSCODE_NOTIFICATION_SYS_PROP_KEY =
                "persist.sys.fuse.transcode_notification";
        private static final boolean NOTIFICATION_ALLOWED_DEFAULT_VALUE = false;

        private final Context mContext;
        private final NotificationManagerCompat mNotificationManager;
        private final PackageManager mPackageManager;
        // Builder for creating alert notifications.
        private final NotificationCompat.Builder mAlertBuilder;
        // Builder for creating progress notifications.
        private final NotificationCompat.Builder mProgressBuilder;
        private final SessionTiming mSessionTiming;

        TranscodeUiNotifier(Context context, SessionTiming sessionTiming) {
            mContext = context;
            mNotificationManager = NotificationManagerCompat.from(context);
            mPackageManager = context.getPackageManager();
            createAlertNotificationChannel(context);
            createProgressNotificationChannel(context);
            mAlertBuilder = createAlertNotificationBuilder(context);
            mProgressBuilder = createProgressNotificationBuilder(context);
            mSessionTiming = sessionTiming;
        }

        void start(TranscodingSession session, String filePath) {
            if (!notificationEnabled()) {
                return;
            }
            ForegroundThread.getHandler().post(() -> {
                mAlertBuilder.setContentTitle(getString(mContext,
                                R.string.transcode_processing_started));
                mAlertBuilder.setContentText(FileUtils.extractDisplayName(filePath));
                final int notificationId = session.getSessionId();
                mNotificationManager.notify(notificationId, mAlertBuilder.build());
            });
        }

        void stop(TranscodingSession session, String filePath) {
            if (!notificationEnabled()) {
                return;
            }
            endSessionWithMessage(session, filePath, getResultMessageForSession(mContext, session));
        }

        void denied(int uid) {
            String appName = getAppName(uid);
            if (appName == null) {
                Log.w(TAG, "Not showing denial, no app name ");
                return;
            }

            final Handler handler = ForegroundThread.getHandler();
            handler.post(() -> {
                Toast.makeText(mContext,
                        mContext.getResources().getString(R.string.transcode_denied, appName),
                        Toast.LENGTH_LONG).show();
            });
        }

        void setProgress(TranscodingSession session, String filePath,
                @IntRange(from = 0, to = PROGRESS_MAX) int progress) {
            if (!notificationEnabled()) {
                return;
            }
            if (shouldShowProgress(session)) {
                mProgressBuilder.setContentText(FileUtils.extractDisplayName(filePath));
                mProgressBuilder.setProgress(PROGRESS_MAX, progress, /* indeterminate= */ false);
                final int notificationId = session.getSessionId();
                mNotificationManager.notify(notificationId, mProgressBuilder.build());
            }
        }

        private boolean shouldShowProgress(TranscodingSession session) {
            return (System.currentTimeMillis() - mSessionTiming.getSessionStartTime(session))
                    > SHOW_PROGRESS_THRESHOLD_TIME_MS;
        }

        private void endSessionWithMessage(TranscodingSession session, String filePath,
                String message) {
            final Handler handler = ForegroundThread.getHandler();
            handler.post(() -> {
                mAlertBuilder.setContentTitle(message);
                mAlertBuilder.setContentText(FileUtils.extractDisplayName(filePath));
                final int notificationId = session.getSessionId();
                mNotificationManager.notify(notificationId, mAlertBuilder.build());
                // Auto-dismiss after a delay.
                handler.postDelayed(() -> mNotificationManager.cancel(notificationId),
                        ALERT_DISMISS_DELAY_MS);
            });
        }

        private String getAppName(int uid) {
            String name = mPackageManager.getNameForUid(uid);
            if (name == null) {
                Log.w(TAG, "Couldn't find name");
                return null;
            }

            final ApplicationInfo aInfo;
            try {
                aInfo = mPackageManager.getApplicationInfo(name, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "unable to look up package name", e);
                return null;
            }

            // If the label contains new line characters it may push the security
            // message below the fold of the dialog. Labels shouldn't have new line
            // characters anyways, so we just delete all of the newlines (if there are any).
            return aInfo.loadSafeLabel(mPackageManager, MAX_APP_NAME_SIZE_PX,
                    TextUtils.SAFE_STRING_FLAG_SINGLE_LINE).toString();
        }

        private static String getString(Context context, int resourceId) {
            return context.getResources().getString(resourceId);
        }

        private static void createAlertNotificationChannel(Context context) {
            NotificationChannel channel = new NotificationChannel(TRANSCODE_ALERT_CHANNEL_ID,
                    TRANSCODE_ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            NotificationManager notificationManager = context.getSystemService(
                    NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        private static void createProgressNotificationChannel(Context context) {
            NotificationChannel channel = new NotificationChannel(TRANSCODE_PROGRESS_CHANNEL_ID,
                    TRANSCODE_PROGRESS_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = context.getSystemService(
                    NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        private static NotificationCompat.Builder createAlertNotificationBuilder(Context context) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                    TRANSCODE_ALERT_CHANNEL_ID);
            builder.setAutoCancel(false)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.thumb_clip);
            return builder;
        }

        private static NotificationCompat.Builder createProgressNotificationBuilder(
                Context context) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                    TRANSCODE_PROGRESS_CHANNEL_ID);
            builder.setAutoCancel(false)
                    .setOngoing(true)
                    .setContentTitle(getString(context, R.string.transcode_processing))
                    .setSmallIcon(R.drawable.thumb_clip);
            return builder;
        }

        private static String getResultMessageForSession(Context context,
                TranscodingSession session) {
            switch (session.getResult()) {
                case TranscodingSession.RESULT_CANCELED:
                    return getString(context, R.string.transcode_processing_cancelled);
                case TranscodingSession.RESULT_ERROR:
                    return getString(context, R.string.transcode_processing_error);
                case TranscodingSession.RESULT_SUCCESS:
                    return getString(context, R.string.transcode_processing_success);
                default:
                    return getString(context, R.string.transcode_processing_error);
            }
        }

        private static boolean notificationEnabled() {
            return SystemProperties.getBoolean(TRANSCODE_NOTIFICATION_SYS_PROP_KEY,
                    NOTIFICATION_ALLOWED_DEFAULT_VALUE);
        }
    }

    private static class TranscodeDenialController implements OnUidImportanceListener {
        private final int mMaxDurationMs;
        private final ActivityManager mActivityManager;
        private final TranscodeUiNotifier mUiNotifier;
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final Set<Integer> mActiveDeniedUids = new ArraySet<>();
        @GuardedBy("mLock")
        private final Set<Integer> mDroppedUids = new ArraySet<>();

        TranscodeDenialController(ActivityManager activityManager, TranscodeUiNotifier uiNotifier,
                int maxDurationMs) {
            mActivityManager = activityManager;
            mUiNotifier = uiNotifier;
            mMaxDurationMs = maxDurationMs;
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (importance != IMPORTANCE_FOREGROUND) {
                synchronized (mLock) {
                    if (mActiveDeniedUids.remove(uid) && mActiveDeniedUids.isEmpty()) {
                        // Stop the uid listener if this is the last uid triggering a denial UI
                        mActivityManager.removeOnUidImportanceListener(this);
                    }
                }
            }
        }

        /** @return {@code true} if file access should be denied, {@code false} otherwise */
        boolean checkFileAccess(int uid, long durationMs) {
            boolean shouldDeny = false;
            synchronized (mLock) {
                shouldDeny = durationMs > mMaxDurationMs || mDroppedUids.contains(uid);
            }

            if (!shouldDeny) {
                // Nothing to do
                return false;
            }

            synchronized (mLock) {
                if (!mActiveDeniedUids.contains(uid)
                        && mActivityManager.getUidImportance(uid) == IMPORTANCE_FOREGROUND) {
                    // Show UI for the first denial while foreground
                    mUiNotifier.denied(uid);

                    if (mActiveDeniedUids.isEmpty()) {
                        // Start a uid listener if this is the first uid triggering a denial UI
                        mActivityManager.addOnUidImportanceListener(this, IMPORTANCE_FOREGROUND);
                    }
                    mActiveDeniedUids.add(uid);
                }
            }
            return true;
        }

        void onTranscodingDropped(int uid) {
            synchronized (mLock) {
                mDroppedUids.add(uid);
            }
            // Notify about file access, so we might show a denial UI
            checkFileAccess(uid, 0 /* duration */);
        }
    }

    private static final class SessionTiming {
        // This should be accessed only in foreground thread.
        private final SparseArray<Long> mSessionStartTimes = new SparseArray<>();

        // Call this only in foreground thread.
        private long getSessionStartTime(MediaTranscodingManager.TranscodingSession session) {
            return mSessionStartTimes.get(session.getSessionId());
        }

        private void logSessionStart(MediaTranscodingManager.TranscodingSession session) {
            ForegroundThread.getHandler().post(
                    () -> mSessionStartTimes.append(session.getSessionId(),
                            System.currentTimeMillis()));
        }

        private void logSessionEnd(MediaTranscodingManager.TranscodingSession session) {
            ForegroundThread.getHandler().post(
                    () -> mSessionStartTimes.remove(session.getSessionId()));
        }
    }
}
