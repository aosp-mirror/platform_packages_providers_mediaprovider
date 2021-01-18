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

import static android.provider.MediaStore.Files.FileColumns.TRANSCODE_COMPLETE;
import static android.provider.MediaStore.Files.FileColumns.TRANSCODE_EMPTY;
import static android.provider.MediaStore.MATCH_EXCLUDE;
import static android.provider.MediaStore.QUERY_ARG_MATCH_PENDING;
import static android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED;

import static com.android.providers.media.MediaProvider.VolumeNotFoundException;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__TRANSCODE_RESULT__FAIL;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__TRANSCODE_RESULT__SUCCESS;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED;

import android.annotation.IntRange;
import android.annotation.LongDef;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.media.ApplicationMediaCapabilities;
import android.media.MediaFeature;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.media.MediaTranscodeManager.TranscodingRequest.MediaFormatResolver;
import android.media.MediaTranscodeManager.TranscodingSession;
import android.media.MediaTranscodingException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.SQLiteQueryBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TranscodeHelper {
    private static final String TAG = "TranscodeHelper";
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.fuse.log", false);

    // TODO(b/169327180): Move to ApplicationMediaCapabilities
    private static final String MEDIA_CAPABILITIES_PROPERTY
            = "android.media.PROPERTY_MEDIA_CAPABILITIES";

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

    private static final long FLAG_HEVC = 1 << 0;
    private static final long FLAG_SLOW_MOTION = 1 << 1;
    private static final long FLAG_HDR_10 = 1 << 2;
    private static final long FLAG_HDR_10_PLUS = 1 << 3;
    private static final long FLAG_HDR_HLG = 1 << 4;
    private static final long FLAG_HDR_DOLBY_VISION = 1 << 5;

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
    private static final double TRANSCODING_TIMEOUT_COEFFICIENT = 2;
    /** Coefficient to 'guess' how large a transcoded file might be */
    private static final double TRANSCODING_SIZE_COEFFICIENT = 2;

    /**
     * Copied from MediaProvider.java
     * TODO(b/170465810): Remove this when  getQueryBuilder code is refactored.
     */
    private static final int TYPE_QUERY = 0;
    private static final int TYPE_UPDATE = 2;
    private static final String DIRECTORY_CAMERA = "Camera";

    private final Object mLock = new Object();
    private final Context mContext;
    private final MediaProvider mMediaProvider;
    private final PackageManager mPackageManager;
    private final MediaTranscodeManager mMediaTranscodeManager;
    private final File mTranscodeDirectory;
    @GuardedBy("mLock")
    private final Map<String, TranscodingSession> mTranscodingSessions = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<CountDownLatch> mTranscodingLatches = new SparseArray<>();
    private final TranscodeUiNotifier mTranscodingUiNotifier;
    private final TranscodeMetrics mTranscodingMetrics;
    @GuardedBy("mLock")
    private final Map<String, Long> mAppCompatMediaCapabilities = new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mIsTranscodeEnabled;

    private static final String[] TRANSCODE_CACHE_INFO_PROJECTION =
            {FileColumns._ID, FileColumns._TRANSCODE_STATUS};
    private static final String TRANSCODE_WHERE_CLAUSE =
            FileColumns.DATA + "=?" + " and mime_type not like 'null'";

    public TranscodeHelper(Context context, MediaProvider mediaProvider) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mMediaTranscodeManager = context.getSystemService(MediaTranscodeManager.class);
        mMediaProvider = mediaProvider;
        mTranscodeDirectory = new File("/storage/emulated/" + UserHandle.myUserId(),
                DIRECTORY_TRANSCODE);
        mTranscodeDirectory.mkdirs();
        mTranscodingMetrics = new TranscodeMetrics();
        mTranscodingUiNotifier = new TranscodeUiNotifier(context, mTranscodingMetrics);
        mIsTranscodeEnabled = isTranscodeEnabled();

        parseTranscodeCompatManifest();
    }

    /**
     * Regex that matches path of transcode file. The regex only
     * matches emulated volume, for files in other volumes we don't
     * seamlessly transcode.
     */
    private static final Pattern PATTERN_TRANSCODE_PATH = Pattern.compile(
            "(?i)^/storage/emulated/(?:[0-9]+)/\\.transcode/(?:\\d+)$");
    private static final String DIRECTORY_TRANSCODE = ".transcode";

    /**
     * @return true if the file path matches transcode file path.
     */
    public static boolean isTranscodeFile(@NonNull String path) {
        final Matcher matcher = PATTERN_TRANSCODE_PATH.matcher(path);
        return matcher.matches();
    }

    @NonNull
    public File getTranscodeDirectory() {
        return mTranscodeDirectory;
    }

    /**
     * @return transcode file's path for given {@code rowId}
     */
    @NonNull
    public String getTranscodePath(long rowId) {
        return new File(getTranscodeDirectory(), String.valueOf(rowId)).getAbsolutePath();
    }

    /* TODO: this should probably use a cache so we don't
     * need to ask the package manager every time
     */
    private String getNameForUid(int uid) {
        String name = mPackageManager.getNameForUid(uid);
        if (name == null) {
            Log.w(TAG, "got null name for uid " + uid + ", using empty string instead");
            return "";
        }
        return name;
    }

    private void reportTranscodingResult(int uid, boolean success, long durationMillis) {
        if (!isTranscodeEnabled()) {
            return;
        }

        MediaProviderStatsLog.write(
                TRANSCODING_DATA,
                getNameForUid(uid),
                MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__READ_TRANSCODE,
                -1, // file size
                success ? TRANSCODING_DATA__TRANSCODE_RESULT__SUCCESS :
                        TRANSCODING_DATA__TRANSCODE_RESULT__FAIL,
                durationMillis
                );
    }

    public boolean transcode(String src, String dst, int uid) {
        TranscodingSession session = null;
        CountDownLatch latch = null;
        long startTime = SystemClock.elapsedRealtime();
        boolean result = false;

        try {
            synchronized (mLock) {
                session = mTranscodingSessions.get(src);
                if (session == null) {
                    latch = new CountDownLatch(1);
                    try {
                        session = enqueueTranscodingSession(src, dst, uid, latch);
                    } catch (MediaTranscodingException | FileNotFoundException |
                            UnsupportedOperationException e) {
                        throw new IllegalStateException(e);
                    }

                    mTranscodingLatches.put(session.getSessionId(), latch);
                    mTranscodingSessions.put(src, session);
                } else {
                    latch = mTranscodingLatches.get(session.getSessionId());
                    if (latch == null) {
                        throw new IllegalStateException("Expected latch for" + session);
                    }
                }
            }

            result = waitTranscodingResult(uid, src, session, latch);
            if (result) {
                updateTranscodeStatus(src, TRANSCODE_COMPLETE);
            } else {
                logEvent("Transcoding failed for " + src + ". session: ", session);
                // Attempt to workaround media transcoding deadlock, b/165374867
                // Cancelling a deadlocked session seems to unblock the transcoder
                finishTranscodingResult(uid, src, session, latch);
            }
        } finally {
            reportTranscodingResult(uid, result, SystemClock.elapsedRealtime() - startTime);
        }
        return result;
    }

    public String getIoPath(String path, int uid) {
        if (!shouldTranscode(path, uid, null /* bundle */)) {
            return path;
        }

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

        final File file = new File(path);
        long maxFileSize = (long) (file.length() * 2);
        getTranscodeDirectory().mkdirs();
        try (RandomAccessFile raf = new RandomAccessFile(transcodeFile, "rw")) {
            raf.setLength(maxFileSize);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialise transcoding for file " + path, e);
            return path;
        }

        return transcodePath;
    }

    private void reportTranscodingDirectAccess(int uid) {
        if (!isTranscodeEnabled()) {
            return;
        }

        MediaProviderStatsLog.write(
                TRANSCODING_DATA,
                getNameForUid(uid),
                MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__READ_DIRECT,
                -1, // file size
                TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                -1  // duration
                );
    }

    // TODO(b/173491972): Generalize to consider other file/app media capabilities beyond hevc
    public boolean shouldTranscode(String path, int uid, Bundle bundle) {
        boolean isTranscodeEnabled = isTranscodeEnabled();
        updateConfigs(isTranscodeEnabled);

        if (!isTranscodeEnabled) {
            logVerbose("Transcode not enabled");
            return false;
        }
        logVerbose("Checking shouldTranscode for: " + path + ". Uid: " + uid);

        if (!supportsTranscode(path) || uid < Process.FIRST_APPLICATION_UID
                || uid == Process.myUid()) {
            logVerbose("Transcode not supported");
            // Never transcode in any of these conditions
            // 1. Path doesn't support transcode
            // 2. Uid is from native process on device
            // 3. Uid is ourselves, which can happen when we are opening a file via FUSE for
            // redaction on behalf of another app via ContentResolver
            return false;
        }

        // Transcode only if file needs transcoding
        try (Cursor cursor = queryFileForTranscode(path,
                new String[]{FileColumns._VIDEO_CODEC_TYPE})) {
            if (cursor == null || !cursor.moveToNext()) {
                logVerbose("Couldn't find database row");
                return false;
            }
            if (!MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(cursor.getString(0))) {
                logVerbose("File is not HEVC");
                return false;
            }
        }
        boolean transcodeNeeded = doesAppNeedTranscoding(uid, bundle);
        if (!transcodeNeeded) {
            reportTranscodingDirectAccess(uid);
        }
        return transcodeNeeded;
    }

    private boolean doesAppNeedTranscoding(int uid, Bundle bundle) {
        if (bundle != null) {
            if (bundle.getBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, false)) {
                logVerbose("Original format requested");
                return false;
            }

            ApplicationMediaCapabilities capabilities =
                    bundle.getParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES);
            if (capabilities != null && capabilities.getSupportedVideoMimeTypes().contains(
                    MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                logVerbose("Media capability requested matches original format");
                return false;
            }
        }

        // Check app-compat flags
        boolean hevcSupportEnabled = CompatChanges.isChangeEnabled(FORCE_ENABLE_HEVC_SUPPORT, uid);
        boolean hevcSupportDisabled = CompatChanges.isChangeEnabled(FORCE_DISABLE_HEVC_SUPPORT,
                uid);
        if (hevcSupportEnabled && hevcSupportDisabled) {
            Log.w(TAG, "Ignoring app compat flags: Set to simultaneously enable and disable "
                    + "HEVC support for uid: " + uid);
        } else if (hevcSupportEnabled) {
            logVerbose("App compat hevc support enabled");
            return false;
        } else if (hevcSupportDisabled) {
            logVerbose("App compat hevc support disabled");
            return true;
        }

        // TODO(b/169327180): We should also check app's targetSDK version to verify if app still
        // qualifies to be on these lists.
        LocalCallingIdentity identity = mMediaProvider.getCachedCallingIdentityForTranscoding(uid);
        final String[] callingPackages = identity.getSharedPackageNames();

        // Check manifest supported packages and mAppCompatMediaCapabilities
        // If we are here then the file supports HEVC, so we only check if the package is in the
        // mAppCompatCapabilities.  If it's there, we will respect that value.
        for (String callingPackage : callingPackages) {
            if (checkManifestSupport(callingPackage, identity)) {
                logVerbose("Manifest supports original format");
                return false;
            }

            synchronized (mLock) {
                if (mAppCompatMediaCapabilities.containsKey(callingPackage)) {
                    boolean shouldTranscode = mAppCompatMediaCapabilities.get(callingPackage) == 0;
                    if (shouldTranscode) {
                        logVerbose("Compat manifest does not support original format");
                    } else {
                        logVerbose("Compat manifest supports original format");
                    }
                    return shouldTranscode;
                }
            }
        }

        boolean shouldTranscode = getBooleanProperty(TRANSCODE_DEFAULT_SYS_PROP_KEY,
                TRANSCODE_DEFAULT_DEVICE_CONFIG_KEY, true /* defaultValue */);
        if (shouldTranscode) {
            logVerbose("Default behavior should transcode");
        } else {
            logVerbose("Default behavior should not transcode");
        }
        return shouldTranscode;
    }

    public boolean supportsTranscode(String path) {
        File file = new File(path);
        String name = file.getName();
        final String cameraRelativePath =
                String.format("%s/%s/", Environment.DIRECTORY_DCIM, DIRECTORY_CAMERA);

        return !isTranscodeFile(path) && name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                && path.startsWith("/storage/emulated/")
                && cameraRelativePath.equalsIgnoreCase(FileUtils.extractRelativePath(path));
    }

    /**
     * @return {@code true} if HEVC is explicitly supported by the manifest of {@code packageName},
     * {@code false} otherwise.
     */
    private boolean checkManifestSupport(String packageName, LocalCallingIdentity identity) {
        // TODO(b/169327180):
        // 1. Support beyond HEVC
        // 2. Shared package names policy:
        // If appA and appB share the same uid. And appA supports HEVC but appB doesn't.
        // Should we assume entire uid supports or doesn't?
        // For now, we assume uid supports, but this might change in future
        int flags = identity.getApplicationMediaCapabilitiesFlags();
        if (flags != -1) {
            return (flags & FLAG_HEVC) != 0;
        }

        try {
            Property mediaCapProperty = mPackageManager.getProperty(MEDIA_CAPABILITIES_PROPERTY,
                    packageName);
            XmlResourceParser parser = mPackageManager.getResourcesForApplication(packageName)
                    .getXml(mediaCapProperty.getResourceId());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);

            identity.setApplicationMediaCapabilitiesFlags(capabilitiesToFlags(capability));
            return capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC);
        } catch (PackageManager.NameNotFoundException
                | ApplicationMediaCapabilities.FormatNotFoundException
                | UnsupportedOperationException e) {
            return false;
        }
    }

    @ApplicationMediaCapabilitiesFlags
    private int capabilitiesToFlags(ApplicationMediaCapabilities capability)
            throws ApplicationMediaCapabilities.FormatNotFoundException {
        int flags = 0;
        if (capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            flags |= FLAG_HEVC;
        }
        if (capability.isSlowMotionSupported()) {
            flags |= FLAG_SLOW_MOTION;
        }
        if (capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10)) {
            flags |= FLAG_HDR_10;
        }
        if (capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10_PLUS)) {
            flags |= FLAG_HDR_10_PLUS;
        }
        if (capability.isHdrTypeSupported(MediaFeature.HdrType.HLG)) {
            flags |= FLAG_HDR_HLG;
        }
        if (capability.isHdrTypeSupported(MediaFeature.HdrType.DOLBY_VISION)) {
            flags |= FLAG_HDR_DOLBY_VISION;
        }
        return flags;
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
    void reportIfHEVCAdded(Uri uri) {
        if (!isTranscodeEnabled()) {
            return;
        }

        try (Cursor c = mMediaProvider.queryForSingleItem(uri,
                new String[] {
                    FileColumns._VIDEO_CODEC_TYPE,
                    FileColumns.SIZE,
                    FileColumns.OWNER_PACKAGE_NAME,
                    FileColumns.DATA},
                null, null, null)) {
            if (supportsTranscode(c.getString(3)) &&
                    MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(c.getString(0))) {
                MediaProviderStatsLog.write(
                        TRANSCODING_DATA,
                        c.getString(2),
                        MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__HEVC_WRITE,
                        c.getLong(1),
                        TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                        -1 // duration
                        );
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't get cursor for scanned file", e);
        }
    }

    private void reportTranscodingCachedAccess(int uid) {
        if (!isTranscodeEnabled()) {
            return;
        }

        MediaProviderStatsLog.write(
                TRANSCODING_DATA,
                getNameForUid(uid),
                MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_TYPE__READ_CACHE,
                -1, // file size
                TRANSCODING_DATA__TRANSCODE_RESULT__UNDEFINED,
                -1 // duration
                );
    }

    public boolean isTranscodeFileCached(int uid, String path, String transcodePath) {
        if (SystemProperties.getBoolean("sys.fuse.disable_transcode_cache", false)) {
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
                reportTranscodingCachedAccess(uid);
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
                sourceFormat.setFloat(MediaFormat.KEY_FRAME_RATE, framerate);
                MediaFormatResolver resolver = new MediaFormatResolver()
                        .setSourceVideoFormatHint(sourceFormat)
                        .setClientCapabilities(capability);
                MediaFormat resolvedFormat = resolver.resolveVideoFormat();
                resolvedFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);

                return resolvedFormat;
            }
        }
        throw new IllegalStateException("Couldn't get video format info from database for " + path);
    }

    private TranscodingSession enqueueTranscodingSession(String src, String dst, int uid,
            final CountDownLatch latch)
            throws FileNotFoundException, MediaTranscodingException, UnsupportedOperationException {

        File file = new File(src);
        File transcodeFile = new File(dst);

        Uri uri = Uri.fromFile(file);
        Uri transcodeUri = Uri.fromFile(transcodeFile);

        MediaFormat format = getVideoTrackFormat(src);

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setClientUid(uid)
                        .setSourceUri(uri)
                        .setDestinationUri(transcodeUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(format)
                        .build();
        TranscodingSession session = mMediaTranscodeManager.enqueueRequest(request,
                ForegroundThread.getExecutor(),
                s -> {
                    mTranscodingUiNotifier.stop(s, src);
                    finishTranscodingResult(uid, src, s, latch);
                    mTranscodingMetrics.logSessionEnd(s);
                });
        session.setOnProgressUpdateListener(ForegroundThread.getExecutor(),
                (s, progress) -> mTranscodingUiNotifier.setProgress(s, src, progress));

        mTranscodingMetrics.logSessionStart(session);
        mTranscodingUiNotifier.start(session, src);
        logEvent("Transcoding start: " + src + ". Uid: " + uid, session);
        return session;
    }

    private boolean waitTranscodingResult(int uid, String src, TranscodingSession session,
            CountDownLatch latch) {
        try {
            int timeout = getTranscodeTimeoutSeconds(src);

            String waitStartLog = "Transcoding wait start: " + src + ". Uid: " + uid + ". Timeout: "
                    + timeout + "s";
            logEvent(waitStartLog, session);

            boolean latchResult = latch.await(timeout, TimeUnit.SECONDS);
            boolean transcodeResult = session.getResult() == TranscodingSession.RESULT_SUCCESS;

            String waitEndLog = "Transcoding wait end: " + src + ". Uid: " + uid + ". Timeout: "
                    + !latchResult + ". Success: " + transcodeResult;
            logEvent(waitEndLog, session);

            return transcodeResult;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Transcoding latch interrupted." + session);
            return false;
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
        synchronized (mLock) {
            latch.countDown();
            session.cancel();
            mTranscodingSessions.remove(src);
            mTranscodingLatches.remove(session.getSessionId());
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
        return new File(getTranscodeDirectory(), String.valueOf(rowId)).delete();
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
        return getBooleanProperty(TRANSCODE_ENABLED_SYS_PROP_KEY,
                TRANSCODE_ENABLED_DEVICE_CONFIG_KEY, true /* defaultValue */);
    }

    private void updateConfigs(boolean transcodeEnabled) {
        synchronized (mLock) {
            boolean isTranscodeEnabledChanged = transcodeEnabled != mIsTranscodeEnabled;
            boolean isDebug = SystemProperties.getBoolean("sys.fuse.transcode_debug", false);

            if (isTranscodeEnabledChanged || isDebug) {
                Log.i(TAG, "Reloading transcode configs. transcodeEnabled: " + transcodeEnabled
                        + ". lastTranscodeEnabled: " + mIsTranscodeEnabled + ". isDebug: "
                        + isDebug);

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

            if (!parseTranscodeCompatManifestFromDeviceConfigLocked()) {
                Log.i(TAG, "Failed parsing transcode compat manifest from device config "
                        + "attempting resource...");
                parseTranscodeCompatManifestFromResourceLocked();
            }
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
        Long packageCompatValue;
        int i = 0;
        while (i < manifest.length - 1) {
            try {
                packageName = manifest[i++];
                packageCompatValue = Long.valueOf(manifest[i++]);
                synchronized (mLock) {
                    // Lock is already held, explicitly hold again to make error prone happy
                    mAppCompatMediaCapabilities.put(packageName, packageCompatValue);
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse media capability from device config for package: "
                        + packageName, e);
            }
        }

        synchronized (mLock) {
            // Lock is already held, explicitly hold again to make error prone happy
            int size = mAppCompatMediaCapabilities.size();
            Log.i(TAG, "Parsed " + size + " packages from device config");
            return size != 0;
        }
    }

    /** @return {@code true} if the manifest was parsed successfully, {@code false} otherwise */
    private boolean parseTranscodeCompatManifestFromResourceLocked() {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.transcode_compat_manifest);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        try {
            while (reader.ready()) {
                String line = reader.readLine();
                String packageName = "";
                Long packageCompatValue;

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
                    packageCompatValue = Long.valueOf(lineValues[1]);
                    synchronized (mLock) {
                        // Lock is already held, explicitly hold again to make error prone happy
                        mAppCompatMediaCapabilities.put(packageName, packageCompatValue);
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse media capability from resource for package: "
                            + packageName, e);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read transcode compat manifest", e);
        }

        synchronized (mLock) {
            // Lock is already held, explicitly hold again to make error prone happy
            int size = mAppCompatMediaCapabilities.size();
            Log.i(TAG, "Parsed " + size + " packages from resource");
            return size != 0;
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

    private static class TranscodeUiNotifier {
        private static final int PROGRESS_MAX = 100;
        private static final int ALERT_DISMISS_DELAY_MS = 1000;
        private static final int SHOW_PROGRESS_THRESHOLD_TIME_MS = 1000;
        private static final String TRANSCODE_ALERT_CHANNEL_ID = "native_transcode_alert_channel";
        private static final String TRANSCODE_ALERT_CHANNEL_NAME = "Native Transcode Alerts";
        private static final String TRANSCODE_PROGRESS_CHANNEL_ID =
                "native_transcode_progress_channel";
        private static final String TRANSCODE_PROGRESS_CHANNEL_NAME = "Native Transcode Progress";

        private final NotificationManagerCompat mNotificationManager;
        // Builder for creating alert notifications.
        private final NotificationCompat.Builder mAlertBuilder;
        // Builder for creating progress notifications.
        private final NotificationCompat.Builder mProgressBuilder;
        private final TranscodeMetrics mTranscodingMetrics;

        TranscodeUiNotifier(Context context, TranscodeMetrics metrics) {
            mNotificationManager = NotificationManagerCompat.from(context);
            createAlertNotificationChannel(context);
            createProgressNotificationChannel(context);
            mAlertBuilder = createAlertNotificationBuilder(context);
            mProgressBuilder = createProgressNotificationBuilder(context);
            mTranscodingMetrics = metrics;
        }

        void start(TranscodingSession session, String filePath) {
            ForegroundThread.getHandler().post(() -> {
                mAlertBuilder.setContentTitle("Transcoding started");
                mAlertBuilder.setContentText(FileUtils.extractDisplayName(filePath));
                final int notificationId = session.getSessionId();
                mNotificationManager.notify(notificationId, mAlertBuilder.build());
            });
        }

        void stop(TranscodingSession session, String filePath) {
            endSessionWithMessage(session, filePath, getResultMessageForSession(session));
        }

        void setProgress(TranscodingSession session, String filePath,
                @IntRange(from = 0, to = PROGRESS_MAX) int progress) {
            if (shouldShowProgress(session)) {
                mProgressBuilder.setContentText(FileUtils.extractDisplayName(filePath));
                mProgressBuilder.setProgress(PROGRESS_MAX, progress, /* indeterminate= */ false);
                final int notificationId = session.getSessionId();
                mNotificationManager.notify(notificationId, mProgressBuilder.build());
            }
        }

        private boolean shouldShowProgress(TranscodingSession session) {
            return (System.currentTimeMillis() - mTranscodingMetrics.getSessionStartTime(session))
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

        private void createAlertNotificationChannel(Context context) {
            NotificationChannel channel = new NotificationChannel(TRANSCODE_ALERT_CHANNEL_ID,
                    TRANSCODE_ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            NotificationManager notificationManager = context.getSystemService(
                    NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        private void createProgressNotificationChannel(Context context) {
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
                    .setContentTitle("Transcoding media")
                    .setSmallIcon(R.drawable.thumb_clip);
            return builder;
        }

        private static String getResultMessageForSession(TranscodingSession session) {
            switch (session.getResult()) {
                case TranscodingSession.RESULT_CANCELED:
                    return "Transcoding cancelled";
                case TranscodingSession.RESULT_ERROR:
                    return "Transcoding error";
                case TranscodingSession.RESULT_SUCCESS:
                    return "Transcoding success";
                default:
                    return "Transcoding result unknown";
            }
        }
    }

    /**
     * Stores metrics for transcode sessions.
     */
    private static final class TranscodeMetrics {

        // This should be accessed only in foreground thread.
        private final SparseArray<Long> mSessionStartTimes = new SparseArray<>();

        // Call this only in foreground thread.
        long getSessionStartTime(TranscodingSession session) {
            return mSessionStartTimes.get(session.getSessionId());
        }

        void logSessionStart(TranscodingSession session) {
            ForegroundThread.getHandler().post(
                    () -> mSessionStartTimes.append(session.getSessionId(),
                            System.currentTimeMillis()));
        }

        void logSessionEnd(TranscodingSession session) {
            ForegroundThread.getHandler().post(
                    () -> mSessionStartTimes.remove(session.getSessionId()));
        }
    }
}
