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

package com.android.providers.media.transcode;

import static androidx.test.InstrumentationRegistry.getContext;

import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class TranscodeTestUtils {
    private static final String TAG = "TranscodeTestUtils";

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long POLLING_SLEEP_MILLIS = 100;

    public static void stageHEVCVideoFile(File videoFile) throws IOException {
        if (!videoFile.getParentFile().exists()) {
            assertTrue(videoFile.getParentFile().mkdirs());
        }
        try (InputStream in =
                     getContext().getResources().openRawResource(R.raw.testvideo_HEVC);
             FileOutputStream out = new FileOutputStream(videoFile)) {
            FileUtils.copy(in, out);
            // Sync file to disk to ensure file is fully written to the lower fs before scanning
            // Otherwise, media provider might try to read the file on the lower fs and not see
            // the fully written bytes
            out.getFD().sync();
        }
        // MediaProvider treats this app as app with MANAGE_EXTERNAL_STORAGE,
        // so we have to explicitly insert this file to database.
        MediaStore.scanFile(getContext().getContentResolver(), videoFile);
    }

    public static void enableSeamlessTranscoding() throws Exception {
        executeShellCommand("setprop persist.sys.fuse.transcode true");
    }

    public static void disableSeamlessTranscoding() throws Exception {
        executeShellCommand("setprop persist.sys.fuse.transcode false");
    }

    public static void skipTranscodingForUid(int uid) throws IOException {
        final String command = "setprop persist.sys.fuse.transcode_skip_uids "
                + String.valueOf(uid);
        executeShellCommand(command);
    }

    public static void unskipTranscodingForAll() throws IOException {
        final String command = "setprop persist.sys.fuse.transcode_skip_uids -1";
        executeShellCommand(command);
    }

    /**
     * Executes a shell command.
     */
    public static String executeShellCommand(String command) throws IOException {
        int attempt = 0;
        while (attempt++ < 5) {
            try {
                return executeShellCommandInternal(command);
            } catch (InterruptedIOException e) {
                // Hmm, we had trouble executing the shell command; the best we
                // can do is try again a few more times
                Log.v(TAG, "Trouble executing " + command + "; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    private static String executeShellCommandInternal(String cmd) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (FileInputStream output = new FileInputStream(
                uiAutomation.executeShellCommand(cmd).getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    /**
     * Polls for external storage to be mounted.
     */
    public static void pollForExternalStorageState() throws Exception {
        pollForCondition(
                () -> Environment.getExternalStorageState(Environment.getExternalStorageDirectory())
                        .equals(Environment.MEDIA_MOUNTED),
                "Timed out while waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

}
