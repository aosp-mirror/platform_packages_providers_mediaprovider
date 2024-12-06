/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.provider.MediaStore.MEDIA_IGNORE_FILENAME;

import static androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC;
import static androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL;

import static com.android.providers.media.PickerUriResolver.getUserId;
import static com.android.providers.media.PickerUriResolver.unwrapProviderUri;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.android.providers.media.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility class to handle transcoding requests from PhotoPicker.
 */
public class PhotoPickerTranscodeHelper {

    private static final String TAG = "PickerTranscodeHelper";

    private static final String STORAGE_PREFIX = "/storage/emulated/";
    private static final String DIRECTORY_TRANSCODE = ".picker_transcoded";
    private static final int TRANSCODING_TIMEOUT_SECOND = 90;

    @NonNull
    private final File mTranscodeDirectory;
    @NonNull
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    @NonNull
    private Media3Transcoder mTranscoder = new Media3Transcoder();

    public PhotoPickerTranscodeHelper() {
        this(new File(STORAGE_PREFIX + UserHandle.myUserId(), DIRECTORY_TRANSCODE));
    }

    @VisibleForTesting
    PhotoPickerTranscodeHelper(@NonNull File transcodeDirectory) {
        mTranscodeDirectory = transcodeDirectory;
        if (!mTranscodeDirectory.exists()) {
            mTranscodeDirectory.mkdir();
        }
    }

    /**
     * Opens the transcoded file for the given host and media ID.
     *
     * @param host The host of the transcoded file.
     * @param mediaId The media ID of the transcoded file.
     * @return The ParcelFileDescriptor of the transcoded file.
     * @throws FileNotFoundException If the transcoded file does not exist.
     */
    public ParcelFileDescriptor openTranscodedFile(@NonNull String host, @NonNull String mediaId)
            throws FileNotFoundException {
        final String transcodedFilePath = toTranscodedFilePath(host, mediaId);
        final File transcodedFile = new File(transcodedFilePath);

        return FileUtils.openSafely(transcodedFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Gets the size of the transcoded file for the given host and media ID.
     *
     * @param host The host of the transcoded file.
     * @param mediaId The media ID of the transcoded file.
     * @return The size of the transcoded file in bytes, or -1 if the file does not exist.
     */
    public long getTranscodedFileSize(@NonNull String host, @NonNull String mediaId) {
        final String transcodedFilePath = toTranscodedFilePath(host, mediaId);
        final File transcodedFile = new File(transcodedFilePath);

        if (transcodedFile.exists()) {
            return transcodedFile.length();
        }

        return -1L;
    }

    /**
     * Transcodes the given media URI and caches the result.
     *
     * @param context The current context.
     * @param source The URI of the media to transcode.
     * @return True if the transcoding was successful, false otherwise.
     */
    @SuppressLint("NewApi")
    public boolean transcode(@NonNull Context context, @NonNull Uri source) {
        // Check the user id.
        if (getUserId(source) != context.getUser().getIdentifier()) {
            throw new IllegalArgumentException(
                    "User id is not matched, not able to handle the uri : " + source);
        }

        final String destination = toTranscodedFilePath(source);

        // No need to transcode if transcoded video is already cached.
        final File transcodedFile = new File(destination);
        if (transcodedFile.exists()) {
            return true;
        }

        try {
            return mExecutor.submit(() -> {
                int transcodingStatus = mTranscoder.transcode(context, source, destination,
                        TRANSCODING_TIMEOUT_SECOND, true);
                if (transcodingStatus == Media3Transcoder.TRANSCODING_EXPORT_EXCEPTION) {
                    // Retry transcoding without using OpenGL.
                    transcodingStatus = mTranscoder.transcode(context, source, destination,
                            TRANSCODING_TIMEOUT_SECOND, false);
                }
                return transcodingStatus == Media3Transcoder.TRANSCODING_SUCCESS;
            }).get();
        } catch (InterruptedException e) {
            Log.w(TAG, "Transcoding interrupted.", e);
            mTranscoder.cancelRunningTask();
            return false;
        } catch (ExecutionException e) {
            // Should not go here.
            Log.e(TAG, "Unexpected error from Transcoder.", e);
            return false;
        }
    }

    /**
     * Frees up cache space for the given number of bytes.
     *
     * @param bytes The number of bytes to free.
     * @return The number of bytes freed.
     */
    public long freeCache(long bytes) {
        final File[] files = mTranscodeDirectory.listFiles();

        if (files == null) {
            return 0;
        }

        long bytesFreed = 0;
        for (File file : files) {
            if (bytes - bytesFreed <= 0) {
                break;
            }

            if (Objects.equals(file.getName(), MEDIA_IGNORE_FILENAME)) continue;

            if (file.exists() && file.isFile()) {
                final long size = file.length();
                if (file.delete()) {
                    bytesFreed += size;
                }
            }
        }

        return bytesFreed;
    }

    /**
     * Cleans all transcoded files.
     *
     * @param signal the cancellation signal.
     */
    public void cleanAllTranscodedFiles(@Nullable CancellationSignal signal) {
        final File[] files = mTranscodeDirectory.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {
            if (signal != null && signal.isCanceled()) {
                Log.i(TAG, "Received a cancellation signal during cleaning cache.");
                break;
            }

            if (Objects.equals(file.getName(), MEDIA_IGNORE_FILENAME)) continue;

            if (file.exists() && file.isFile()) {
                file.delete();
            }
        }
    }

    /**
     * Deletes the cached transcoded file for the given host and media ID.
     *
     * @param host The host of the transcoded file to delete.
     * @param mediaId The media ID of the transcoded file to delete.
     * @return True if the file was deleted, false otherwise.
     */
    public boolean deleteCachedTranscodedFile(@NonNull String host, long mediaId) {
        final String transcodedFilePath = toTranscodedFilePath(host, String.valueOf(mediaId));
        return new File(transcodedFilePath).delete();
    }

    @NonNull
    private String toTranscodedFilePath(@NonNull Uri pickerUri) {
        // Unwrap picker uri to get host and id.
        final Uri unwrappedSourceUri = unwrapProviderUri(pickerUri);
        final String host = unwrappedSourceUri.getHost();
        final String mediaId = unwrappedSourceUri.getLastPathSegment();

        return toTranscodedFilePath(host, mediaId);
    }

    @NonNull
    private String toTranscodedFilePath(@NonNull String host, @NonNull String mediaId) {
        String transcodedId = host + "_" + mediaId;
        return new File(mTranscodeDirectory, transcodedId).getAbsolutePath();
    }

    @VisibleForTesting
    void setTranscoder(@NonNull Media3Transcoder transcoder) {
        mTranscoder = transcoder;
    }

    @VisibleForTesting
    static class Media3Transcoder {

        static final int TRANSCODING_SUCCESS = 0;
        static final int TRANSCODING_EXPORT_EXCEPTION = 1;
        static final int TRANSCODING_TIMEOUT_EXCEPTION = 2;
        static final int TRANSCODING_OTHER_EXCEPTION = 3;

        @Nullable
        private Transformer mTransformer = null;

        int transcode(@NonNull Context context, @NonNull Uri sourceUri,
                @NonNull String destinationPath, int timeoutSec, boolean useOpenGl) {
            // Cancel previous unfinished task (if existed).
            cancelRunningTask();

            final CompletableFuture<Void> future = new CompletableFuture<>();

            // Start transcoding.
            final Transformer.Listener listener = createListener(destinationPath, future);
            mTransformer = createTransformer(context, listener);
            final Composition composition = createComposition(sourceUri, useOpenGl);
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> mTransformer.start(composition, destinationPath));
            Log.d(TAG, "Transformer started, useOpenGl: " + useOpenGl);

            // Wait for transcoding.
            try {
                future.get(timeoutSec, TimeUnit.SECONDS);
                return TRANSCODING_SUCCESS;
            } catch (ExecutionException e) {
                Log.w(TAG, "Transformer execution failed.", e);
                return TRANSCODING_EXPORT_EXCEPTION;
            } catch (TimeoutException e) {
                Log.w(TAG, "Transformer execution timeout.", e);
                cancelRunningTask();
                return TRANSCODING_TIMEOUT_EXCEPTION;
            } catch (InterruptedException e) {
                // Should not go here.
                Log.e(TAG, "Unexpected error. Transformer execution interrupted.", e);
                return TRANSCODING_OTHER_EXCEPTION;
            } finally {
                mTransformer = null;
            }
        }

        void cancelRunningTask() {
            if (mTransformer != null) {
                final Transformer transformer = mTransformer;
                mTransformer = null;

                // Cancel transformer;
                final Handler handler = new Handler(Looper.getMainLooper());
                handler.post(transformer::cancel);
                Log.d(TAG, "Transformer canceled.");
            }
        }

        private Transformer.Listener createListener(@NonNull String destinationPath,
                @NonNull CompletableFuture<Void> future) {
            return new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                        @NonNull ExportResult exportResult) {
                    future.complete(null);

                    Log.d(TAG, "Transformer exported file successfully.");
                }

                @Override
                public void onError(@NonNull Composition composition,
                        @NonNull ExportResult exportResult,
                        @NonNull ExportException exportException) {
                    future.completeExceptionally(exportException);

                    Log.w(TAG, "Transformer failed to export file: ", exportException);

                    // Cleanup.
                    boolean isCacheDeleted = new File(destinationPath).delete();
                    if (isCacheDeleted) {
                        Log.d(TAG, "The destination file is deleted.");
                    } else {
                        Log.e(TAG, "Failed to delete the destination file.");
                    }
                }
            };
        }

        private Transformer createTransformer(@NonNull Context context,
                @NonNull Transformer.Listener transformerListener) {
            return new Transformer.Builder(context)
                    // Note that H.264/AVC is used here to make the logic simple and provide broad
                    // compatibility, but at the cost of reduced quality or increased size (compared
                    // to H.265/HEVC). If the purposes are no longer considerations, the video mime
                    // type should not be fixed as AVC.
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(transformerListener)
                    .build();
        }

        private Composition createComposition(@NonNull Uri sourceUri, boolean useOpenGl) {
            final int hdrMode = useOpenGl ? HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                    : HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC;
            final EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(
                    MediaItem.fromUri(sourceUri)).build();

            return new Composition
                    .Builder(new EditedMediaItemSequence(editedMediaItem))
                    .setHdrMode(hdrMode)
                    .build();
        }
    }
}
