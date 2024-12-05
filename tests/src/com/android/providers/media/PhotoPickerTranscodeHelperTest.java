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

import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static android.os.Environment.getExternalStorageDirectory;

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static com.android.providers.media.PhotoPickerTranscodeHelper.Media3Transcoder;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PhotoPickerTranscodeHelperTest {

    private final int mTestUserId = UserHandle.myUserId();
    private final String mTestHost = "test";
    private final String mTestMediaId = "123";
    private final Uri mTestUri = Uri.parse("content://media/picker/" + mTestUserId + "/"
            + mTestHost + "/media/" + mTestMediaId);
    private PhotoPickerTranscodeHelper mHelper;
    private File mTestDirectory;

    @Before
    public void setUp() {
        mTestDirectory = new File(getExternalStorageDirectory(),
                DIRECTORY_DOWNLOADS + "/.picker_transcoded");
        mHelper = new PhotoPickerTranscodeHelper(mTestDirectory);
    }

    @After
    public void tearDown() {
        mTestDirectory.delete();
    }

    @Test
    public void transcode() throws IOException {
        // Act & Assert.
        PhotoPickerTranscodeHelper helper = getTranscodeHelper(new Media3Transcoder() {
            @Override
            int transcode(@NonNull Context context, @NonNull Uri sourceUri,
                    @NonNull String destinationPath, int timeoutSec, boolean useOpenGl) {
                return Media3Transcoder.TRANSCODING_SUCCESS;
            }
        });
        assertThat(helper.transcode(getTargetContext(), mTestUri)).isTrue();
    }

    @Test
    public void transcode_doNotUseTranscoder_whenCacheFileExists() throws IOException {
        // Pre-create corresponded cached transcoded file.
        final File cachedFile = new File(mTestDirectory, mTestHost + "_" + mTestMediaId);
        if (cachedFile.createNewFile()) {
            assertThat(cachedFile.exists()).isTrue();
        }

        // Act & Assert.
        PhotoPickerTranscodeHelper helper = getTranscodeHelper(new Media3Transcoder() {
            @Override
            int transcode(@NonNull Context context, @NonNull Uri sourceUri,
                    @NonNull String destinationPath, int timeoutSec, boolean useOpenGl) {
                return PhotoPickerTranscodeHelper.Media3Transcoder.TRANSCODING_OTHER_EXCEPTION;
            }
        });
        assertThat(helper.transcode(getTargetContext(), mTestUri)).isTrue();

        // Cleanup.
        cachedFile.delete();
    }

    @Test
    public void transcode_canRetry_whenOpenGlExportFailed() throws IOException {
        // Act & Assert.
        PhotoPickerTranscodeHelper helper = getTranscodeHelper(new Media3Transcoder() {

            private boolean mHasTriedWithOpenGl = false;

            @Override
            int transcode(@NonNull Context context, @NonNull Uri sourceUri,
                    @NonNull String destinationPath, int timeoutSec, boolean useOpenGl) {
                if (useOpenGl) {
                    mHasTriedWithOpenGl = true;
                    return Media3Transcoder.TRANSCODING_EXPORT_EXCEPTION;
                } else if (mHasTriedWithOpenGl) {
                    return Media3Transcoder.TRANSCODING_SUCCESS;
                } else {
                    return Media3Transcoder.TRANSCODING_OTHER_EXCEPTION;
                }
            }
        });
        assertThat(helper.transcode(getTargetContext(), mTestUri)).isTrue();
    }

    @Test
    public void transcode_returnFalse_whenTranscoderFailed() throws IOException {
        // Act & Assert.
        PhotoPickerTranscodeHelper helper = getTranscodeHelper(new Media3Transcoder() {
            @Override
            int transcode(@NonNull Context context, @NonNull Uri sourceUri,
                    @NonNull String destinationPath, int timeoutSec, boolean useOpenGl) {
                return Media3Transcoder.TRANSCODING_EXPORT_EXCEPTION;
            }
        });
        assertThat(helper.transcode(getTargetContext(), mTestUri)).isFalse();
    }

    @Test
    public void transcode_returnFalse_whenTimeout() throws IOException {
        // Act & Assert.
        PhotoPickerTranscodeHelper helper = getTranscodeHelper(new Media3Transcoder() {
            @Override
            int transcode(@NonNull Context context, @NonNull Uri sourceUri,
                    @NonNull String destinationPath, int timeoutSec, boolean useOpenGl) {
                return Media3Transcoder.TRANSCODING_TIMEOUT_EXCEPTION;
            }
        });
        assertThat(helper.transcode(getTargetContext(), mTestUri)).isFalse();
    }

    @Test
    public void openTranscodedFile() throws IOException {
        // Pre-create corresponded cached transcoded file.
        final File cachedFile = new File(mTestDirectory, mTestHost + "_" + mTestMediaId);
        if (cachedFile.createNewFile()) {
            assertThat(cachedFile.exists()).isTrue();
        }

        // Act & Assert.
        ParcelFileDescriptor pfd = mHelper.openTranscodedFile(mTestHost, mTestMediaId);
        assertThat(pfd).isNotNull();

        // Cleanup.
        cachedFile.delete();
    }

    @Test
    public void getTranscodedFileSize() throws IOException {
        // Pre-create corresponded cached transcoded file.
        final File cachedFile = new File(mTestDirectory, mTestHost + "_" + mTestMediaId);
        if (cachedFile.createNewFile()) {
            assertThat(cachedFile.exists()).isTrue();
        }
        final RandomAccessFile raf = new RandomAccessFile(cachedFile, "rw");
        raf.setLength(3);

        // Act & Assert.
        long fileSize = mHelper.getTranscodedFileSize(mTestHost, mTestMediaId);
        assertThat(fileSize).isEqualTo(3);

        // Cleanup.
        cachedFile.delete();
    }

    @Test
    public void freeCache() throws IOException {
        final List<String> fileNamesToBeFreed = Arrays.asList("100", "101", "102");
        createTestFiles(fileNamesToBeFreed);

        // Act.
        mHelper.freeCache(1024);

        // Assert.
        for (String fileName : fileNamesToBeFreed) {
            final File file = new File(mTestDirectory, fileName);
            assertThat(file.exists()).isFalse();
        }
    }

    @Test
    public void freeCache_returnCorrectBytesFreed() throws IOException {
        // Pre-create corresponded cached transcoded file.
        final File cachedFile = new File(mTestDirectory, mTestHost + "_" + mTestMediaId);
        if (cachedFile.createNewFile()) {
            assertThat(cachedFile.exists()).isTrue();
        }
        final RandomAccessFile raf = new RandomAccessFile(cachedFile, "rw");
        raf.setLength(17);

        // Act.
        final long bytesFreed = mHelper.freeCache(1024);

        // Assert.
        assertThat(cachedFile.exists()).isFalse();
        assertThat(bytesFreed).isEqualTo(17);
    }

    @Test
    public void cleanAllTranscodedFiles() throws IOException {
        final List<String> fileNamesToBeFreed = Arrays.asList("100", "101", "102");
        createTestFiles(fileNamesToBeFreed);

        // Act.
        mHelper.cleanAllTranscodedFiles(null);

        // Assert.
        for (String fileName : fileNamesToBeFreed) {
            final File file = new File(mTestDirectory, fileName);
            assertThat(file.exists()).isFalse();
        }
    }

    @Test
    public void cleanAllTranscodedFiles_notPerform_whenCancellationsSignalSet() throws IOException {
        final List<String> fileNamesToBeFreed = Arrays.asList("100", "101", "102");
        createTestFiles(fileNamesToBeFreed);

        // Act.
        CancellationSignal signal = new CancellationSignal();
        signal.cancel();
        mHelper.cleanAllTranscodedFiles(signal);

        // Assert.
        for (String fileName : fileNamesToBeFreed) {
            final File file = new File(mTestDirectory, fileName);
            assertThat(file.exists()).isTrue();

            // Cleanup.
            file.delete();
        }
    }

    @Test
    public void deleteCachedTranscodedFile() throws IOException {
        final long localId = 123;
        final File fileToBeDeleted = new File(mTestDirectory, mTestHost + "_" + "123");
        if (fileToBeDeleted.createNewFile()) {
            assertThat(fileToBeDeleted.exists()).isTrue();
        }

        // Act.
        mHelper.deleteCachedTranscodedFile(mTestHost, localId);

        // Assert.
        assertThat(fileToBeDeleted.exists()).isFalse();
    }

    @NonNull
    private PhotoPickerTranscodeHelper getTranscodeHelper(@NonNull Media3Transcoder transcoder) {
        PhotoPickerTranscodeHelper helper = new PhotoPickerTranscodeHelper(mTestDirectory);
        helper.setTranscoder(transcoder);
        return helper;
    }

    private void createTestFiles(@NonNull List<String> fileNames) throws IOException {
        for (String fileName : fileNames) {
            File file = new File(mTestDirectory, fileName);
            if (file.createNewFile()) {
                assertThat(file.exists()).isTrue();
            }
        }
    }
}
