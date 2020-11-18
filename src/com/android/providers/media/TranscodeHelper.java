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

import android.annotation.IntRange;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.ApplicationMediaCapabilities;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingSession;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.media.MediaTranscodeManager.TranscodingRequest.MediaFormatResolver;
import android.media.MediaTranscodingException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemProperties;
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TranscodeHelper {
    private static final String TAG = "TranscodeHelper";
    private static final String TRANSCODE_FILE_PREFIX = ".transcode_";

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
    static final String DIRECTORY_CAMERA = "Camera";

    private final MediaProvider mMediaProvider;
    private final MediaTranscodeManager mMediaTranscodeManager;
    private final File mTranscodeDirectory;
    @GuardedBy("mTranscodingSessions")
    private final Map<String, TranscodingSession> mTranscodingSessions = new ArrayMap<>();
    @GuardedBy("mTranscodingSessions")
    private final SparseArray<CountDownLatch> mTranscodingLatches = new SparseArray<>();
    private final TranscodeUiNotifier mTranscodingUiNotifier;
    private final TranscodeMetrics mTranscodingMetrics;

    private static final String[] TRANSCODE_CACHE_INFO_PROJECTION =
            {FileColumns._ID, FileColumns._TRANSCODE_STATUS};
    private static final String TRANSCODE_WHERE_CLAUSE =
            FileColumns.DATA + "=?" + " and mime_type not like 'null'";

    /**
     * Never transcode for these packages.
     * TODO(b/169327180): Replace this with allow list from server.
     */
    private static final String[] ALLOW_LIST = new String[]{
            // TODO: Remove "com.google.android.apps.photos", after investigating issue.
            "com.google.android.apps.photos"
    };

    public TranscodeHelper(Context context, MediaProvider mediaProvider) {
        mMediaTranscodeManager = context.getSystemService(MediaTranscodeManager.class);
        mMediaProvider = mediaProvider;
        mTranscodeDirectory =
                FileUtils.buildPath(Environment.getExternalStorageDirectory(), DIRECTORY_TRANSCODE);
        mTranscodeDirectory.mkdirs();
        mTranscodingMetrics = new TranscodeMetrics();
        mTranscodingUiNotifier = new TranscodeUiNotifier(context, mTranscodingMetrics);
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

    public boolean transcode(String src, String dst, int uid) {
        TranscodingSession session = null;
        CountDownLatch latch = null;

        synchronized (mTranscodingSessions) {
            session = mTranscodingSessions.get(src);
            if (session == null) {
                latch = new CountDownLatch(1);
                try {
                    session = enqueueTranscodingSession(src, dst, uid, latch);
                } catch (MediaTranscodingException | FileNotFoundException e) {
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

        boolean result = waitTranscodingResult(uid, src, session, latch);
        if (result) {
            updateTranscodeStatus(src, TRANSCODE_COMPLETE);
        } else {
            logEvent("Transcoding failed for " + src + ". session: ", session);
            // Attempt to workaround media transcoding deadlock, b/165374867
            // Cancelling a deadlocked session seems to unblock the transcoder
            finishTranscodingResult(uid, src, session, latch);
        }
        return result;
    }

    public String getIoPath(String path, int uid) {
        if (!shouldTranscode(path, uid)) {
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

    public boolean shouldTranscode(String path, int uid) {
        final boolean transcodeEnabled
                = SystemProperties.getBoolean("persist.sys.fuse.transcode", false);
        if (!transcodeEnabled) {
            return false;
        }

        if (!supportsTranscode(path) || uid < android.os.Process.FIRST_APPLICATION_UID) {
            return false;
        }

        // Transcode only if file needs transcoding
        try (Cursor cursor = queryFileForTranscode(path,
                new String[]{FileColumns._VIDEO_CODEC_TYPE})) {
            if (cursor == null || !cursor.moveToNext()) {
                Log.d(TAG, "Couldn't find database row for path " + path +
                        ", Assuming no seamless transcoding needed.");
                return false;
            }
            if (!MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(cursor.getString(0))) {
                return false;
            }
        }

        // TODO(b/169327180): We should also check app's targetSDK version to verify if app still
        //  qualifies to be on the allow list.
        List<String> allowList = Arrays.asList(ALLOW_LIST);
        final String[] callingPackages = mMediaProvider.getSharedPackagesForUidForTranscoding(uid);
        for (String callingPackage : callingPackages) {
            if (allowList.contains(callingPackage)) {
                return false;
            }
        }

        List<String> transcodeUids = Arrays.asList(
                SystemProperties.get("persist.sys.fuse.transcode_uids").split(","));
        if (transcodeUids.contains(String.valueOf(uid))) {
            return true;
        }

        List<String> transcodePackages = Arrays.asList(
                SystemProperties.get("persist.sys.fuse.transcode_packages").split(","));
        for (String callingPackage : callingPackages) {
            if (transcodePackages.contains(callingPackage)) {
                return true;
            }
        }
        return false;
    }

    public boolean supportsTranscode(String path) {
        File file = new File(path);
        String name = file.getName();
        final String cameraRelativePath =
                String.format("%s/%s/", Environment.DIRECTORY_DCIM, DIRECTORY_CAMERA);

        return !isTranscodeFile(path) && name.endsWith(".mp4") &&
                cameraRelativePath.equalsIgnoreCase(FileUtils.extractRelativePath(path));
    }

    private Pair<Long, Integer> getTranscodeCacheInfoFromDB(String path) {
        try (Cursor cursor = queryFileForTranscode(path, TRANSCODE_CACHE_INFO_PROJECTION)) {
            if (cursor != null && cursor.moveToNext()) {
                return Pair.create(cursor.getLong(0), cursor.getInt(1));
            }
        }
        return Pair.create((long) -1, TRANSCODE_EMPTY);
    }

    public boolean isTranscodeFileCached(String path, String transcodePath) {
        if (SystemProperties.getBoolean("fuse.sys.disable_transcode_cache", false)) {
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
        String[] resolverInfoProjection = new String[] {
                FileColumns._VIDEO_CODEC_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.BITRATE
        };
        try (Cursor c = queryFileForTranscode(path, resolverInfoProjection)) {
            if (c != null && c.moveToNext()) {
                String codecType = c.getString(0);
                int width = c.getInt(1);
                int height = c.getInt(2);
                int bitRate = c.getInt(3);

                // TODO(b/169849854): Get this info from Manifest, for now if app got here it
                // definitely doesn't support hevc
                ApplicationMediaCapabilities capability =
                        new ApplicationMediaCapabilities.Builder().build();
                MediaFormatResolver resolver = new MediaFormatResolver()
                        .setSourceVideoFormatHint(MediaFormat.createVideoFormat(
                                codecType, width, height))
                        .setClientCapabilities(capability);
                MediaFormat format = resolver.resolveVideoFormat();
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);

                return format;
            }
        }
        throw new IllegalStateException("Couldn't get video format info from database for " + path);
    }

    private TranscodingSession enqueueTranscodingSession(String src, String dst, int uid,
            final CountDownLatch latch) throws FileNotFoundException, MediaTranscodingException {

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
        double sizeMb = new File(file).length() / (1024 * 1024);
        return (int) (sizeMb * TRANSCODING_TIMEOUT_COEFFICIENT);
    }

    private void finishTranscodingResult(int uid, String src, TranscodingSession session,
            CountDownLatch latch) {
        synchronized (mTranscodingSessions) {
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
        return qb.update(getDatabaseHelperForUri(uri), values, TRANSCODE_WHERE_CLAUSE,
                selectionArgs) == 1;
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

    private void logEvent(String event, @Nullable TranscodingSession session) {
        Log.d(TAG, event + (session == null ? "" : session));
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
