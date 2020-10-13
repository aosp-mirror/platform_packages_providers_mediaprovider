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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingJob;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.media.MediaTranscodingException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemProperties;
import android.provider.MediaStore.Files.FileColumns;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.SQLiteQueryBuilder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TranscodeHelper {
    private static final String TAG = "TranscodeHelper";
    private static final String TRANSCODE_FILE_PREFIX = ".transcode_";

    /** Coefficient to 'guess' how long a transcoding job might take */
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

    private final Context mContext;
    private final MediaProvider mMediaProvider;
    private final MediaTranscodeManager mMediaTranscodeManager;
    @GuardedBy("mTranscodingJobs")
    private final Map<String, TranscodingJob> mTranscodingJobs = new ArrayMap<>();
    @GuardedBy("mTranscodingJobs")
    private final SparseArray<CountDownLatch> mTranscodingLatches = new SparseArray<>();

    private static final String[] TRANSCODE_CACHE_INFO_PROJECTION =
            {FileColumns._ID, FileColumns._TRANSCODE_STATUS};
    private static final String TRANSCODE_WHERE_CLAUSE =
            FileColumns.DATA + "=?" + " and mime_type not like 'null'";

    public TranscodeHelper(Context context, MediaProvider mediaProvider) {
        mContext = context;
        mMediaTranscodeManager = context.getSystemService(MediaTranscodeManager.class);
        mMediaProvider = mediaProvider;
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
    public static File getTranscodeDirectory() {
        final File transcodeDirectory =
                FileUtils.buildPath(Environment.getExternalStorageDirectory(), DIRECTORY_TRANSCODE);
        transcodeDirectory.mkdirs();
        return transcodeDirectory;
    }

    /**
     * @return transcode file's path for given {@code rowId}
     */
    @NonNull
    public static String getTranscodePath(long rowId) {
        return new File(getTranscodeDirectory(), String.valueOf(rowId)).getAbsolutePath();
    }

    public boolean transcode(String src, String dst, int uid) {
        TranscodingJob job = null;
        CountDownLatch latch = null;

        synchronized (mTranscodingJobs) {
            job = mTranscodingJobs.get(src);
            if (job == null) {
                latch = new CountDownLatch(1);
                try {
                    job = enqueueTranscodingJob(src, dst, uid, latch);
                } catch (MediaTranscodingException | FileNotFoundException e) {
                    throw new IllegalStateException(e);
                }

                mTranscodingLatches.put(job.getJobId(), latch);
                mTranscodingJobs.put(src, job);
            } else {
                latch = mTranscodingLatches.get(job.getJobId());
                if (latch == null) {
                    throw new IllegalStateException("Expected latch for" + job);
                }
            }
        }

        boolean result = waitTranscodingResult(src, job, latch);
        if (result) {
            updateTranscodeStatus(src, TRANSCODE_COMPLETE);
        } else {
            Log.w(TAG, "Transcoding timed out for " + src + ". Job: " + job);
            // Attempt to workaround media transcoding deadlock, b/165374867
            // Cancelling a deadlocked job seems to unblock the transcoder
            finishTranscodingResult(src, job, latch);
        }
        return result;
    }

    public String getIoPath(String path, int uid) {
        if (!shouldTranscode(path, uid)) {
            return path;
        }

        Pair<Long, Integer> cacheInfo = getTranscodeCacheInfoFromDB(path);
        final long rowId =cacheInfo.first;
        if (rowId == -1 ) {
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
        try (RandomAccessFile raf = new RandomAccessFile(transcodeFile, "rw")) {
            raf.setLength(maxFileSize);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialise transcoding for file " + path, e);
            return path;
        }

        return transcodePath;
    }

    public boolean shouldTranscode(String path, int uid) {
        if (!supportsTranscode(path) || uid < android.os.Process.FIRST_APPLICATION_UID) {
            return false;
        }

        int supportedUid = SystemProperties.getInt("fuse.sys.transcode_uid", -2);
        if ((supportedUid == uid) || (supportedUid == -1)) {
            return true;
        }

        String[] supportedPackages = SystemProperties.get("fuse.sys.transcode_package").split(",");
        for (String packageName : supportedPackages) {
            if (packageName.isEmpty()) {
                continue;
            }

            try {
                if (uid == mContext.getPackageManager().getPackageUid(packageName, 0)) {
                    return true;
                }
            } catch (NameNotFoundException e) {
                Log.i(TAG, "Ignoring package not found", e);
            }
        }

        return false;
    }

    public boolean supportsTranscode(String path) {
        File file = new File(path);
        String name = file.getName();
        final String cameraRelativePath =
                String.format("%s/%s/", Environment.DIRECTORY_DCIM, DIRECTORY_CAMERA);

        // TODO(b/170597936): Don't enable transforms until bug is fixed.
        // return !isTranscodeFile(path) && name.endsWith(".mp4") &&
        //        cameraRelativePath.equalsIgnoreCase(FileUtils.extractRelativePath(path));
        return false;
    }

    private DatabaseHelper getDatabaseHelperForUri(Uri uri) {
        final DatabaseHelper helper;
        try {
            return mMediaProvider.getDatabaseForUriForTranscoding(uri);
        } catch (VolumeNotFoundException e) {
            throw new IllegalStateException("Volume not found while querying transcode path", e);
        }
    }

    private Pair<Long, Integer> getTranscodeCacheInfoFromDB(String path) {
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
        try (Cursor c = qb.query(getDatabaseHelperForUri(uri), TRANSCODE_CACHE_INFO_PROJECTION,
                extras, null)) {
            if (c.moveToNext()) {
                return Pair.create(c.getLong(0), c.getInt(1));
            }
        }
        return Pair.create((long)-1, TRANSCODE_EMPTY);
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
            return transcodePath.equalsIgnoreCase(getTranscodePath(rowId)) &&
                    transcodeStatus == TRANSCODE_COMPLETE &&
                    new File(transcodePath).exists();
        }
        return false;
    }

    private TranscodingJob enqueueTranscodingJob(String src, String dst, int uid,
            final CountDownLatch latch) throws FileNotFoundException, MediaTranscodingException {
        int bitRate = 20000000; // 20Mbps
        int width = 1920;
        int height = 1080;

        File file = new File(src);
        File transcodeFile = new File(dst);

        Uri uri = Uri.fromFile(file);
        Uri transcodeUri = Uri.fromFile(transcodeFile);

        // TODO: Get MediaFormat from database
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                .setClientUid(uid)
                .setSourceUri(uri)
                .setDestinationUri(transcodeUri)
                .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                .setVideoTrackFormat(format)
                .build();

        return mMediaTranscodeManager.enqueueRequest(request, ForegroundThread.getExecutor(),
                job -> finishTranscodingResult(src, job, latch));
    }

    private boolean waitTranscodingResult(String src, TranscodingJob job, CountDownLatch latch) {
        try {
            int timeout = getTranscodeTimeoutSeconds(src);
            Log.d(TAG, "Transcoding latch start, timeout: " + timeout + "s" + job);
            boolean result = latch.await(timeout, TimeUnit.SECONDS);
            Log.d(TAG, "Transcoding latch end, result: " + result + job);
            return job.getResult() == TranscodingJob.RESULT_SUCCESS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.d(TAG, "Transcoding latch interrupted." + job);
            return false;
        }
    }

    private int getTranscodeTimeoutSeconds(String file) {
        double sizeMb = new File(file).length() / (1024 * 1024);
        return (int) (sizeMb * TRANSCODING_TIMEOUT_COEFFICIENT);
    }

    private void finishTranscodingResult(String src, TranscodingJob job, CountDownLatch latch) {
        synchronized (mTranscodingJobs) {
            latch.countDown();
            job.cancel();
            mTranscodingJobs.remove(src);
            mTranscodingLatches.remove(job.getJobId());
        }
        Log.d(TAG, "Transcoding finished" + job);
    }

    private boolean updateTranscodeStatus(String path, int transcodeStatus) {
        final Uri uri = FileUtils.getContentUriForPath(path);
        // TODO(b/170465810): Replace this with matchUri when the code is refactored.
        final int match = MediaProvider.FILES;
        final SQLiteQueryBuilder qb = mMediaProvider.getQueryBuilderForTranscoding(TYPE_UPDATE,
                match, uri, Bundle.EMPTY, null);
        final String[] selectionArgs = new String[] {path};

        ContentValues values = new ContentValues();
        values.put(FileColumns._TRANSCODE_STATUS, transcodeStatus);
        return qb.update(getDatabaseHelperForUri(uri), values, TRANSCODE_WHERE_CLAUSE,
                selectionArgs) == 1;
    }

    public boolean deleteCachedTranscodeFile(long rowId) {
        return new File(getTranscodeDirectory(), String.valueOf(rowId)).delete();
    }
}
