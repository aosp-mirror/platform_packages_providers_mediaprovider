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
import static org.junit.Assert.assertEquals;

import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.Os;
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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class TranscodeTestUtils {
    private static final String TAG = "TranscodeTestUtils";

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long POLLING_SLEEP_MILLIS = 100;

    public static Uri stageHEVCVideoFile(File videoFile) throws IOException {
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
        return MediaStore.scanFile(getContext().getContentResolver(), videoFile);
    }

    public static ParcelFileDescriptor open(File file, boolean forWrite) throws Exception {
        // TODO(b/171953356): Switch to read_only fds if forWrite==false
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE);
    }

    public static ParcelFileDescriptor open(Uri uri, boolean forWrite) throws Exception {
        // TODO(b/171953356): Switch to read_only fds if forWrite==false
        return getContext().getContentResolver().openFileDescriptor(uri, "rw");
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

    public static void grantPermission(String permission) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
        try {
            uiAutomation.grantRuntimePermission(getContext().getPackageName(), permission);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Polls until we're granted or denied a given permission.
     */
    public static void pollForPermission(String perm, boolean granted) throws Exception {
        pollForCondition(() -> granted == checkPermissionAndAppOp(perm),
                "Timed out while waiting for permission " + perm + " to be "
                        + (granted ? "granted" : "revoked"));
    }


    /**
     * Checks if the given {@code permission} is granted and corresponding AppOp is MODE_ALLOWED.
     */
    private static boolean checkPermissionAndAppOp(String permission) {
        final int pid = Os.getpid();
        final int uid = Os.getuid();
        final Context context = getContext();
        final String packageName = context.getPackageName();
        if (context.checkPermission(permission, pid, uid) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final String op = AppOpsManager.permissionToOp(permission);
        // No AppOp associated with the given permission, skip AppOp check.
        if (op == null) {
            return true;
        }

        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        try {
            appOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            return false;
        }

        return appOps.unsafeCheckOpNoThrow(op, uid, packageName) == AppOpsManager.MODE_ALLOWED;
    }

    public static void assertFileContent(ParcelFileDescriptor pfd1, ParcelFileDescriptor pfd2,
            boolean assertSame) throws Exception {
        final int len = 1024;
        byte[] bytes1;
        byte[] bytes2;
        int size1 = 0;
        int size2 = 0;

        boolean isSame = true;
        do {
            bytes1 = new byte[len];
            bytes2 = new byte[len];

            size1 = Os.read(pfd1.getFileDescriptor(), bytes1, 0, len);
            size2 = Os.read(pfd2.getFileDescriptor(), bytes2, 0, len);

            assertTrue(size1 >= 0);
            assertTrue(size2 >= 0);

            isSame = (size1 == size2) && Arrays.equals(bytes1, bytes2);
            if (!isSame) {
                break;
            }
        } while (size1 > 0 && size2 > 0);

        assertEquals(isSame, assertSame);
    }

    public static void assertTranscode(File file, boolean transcode) throws Exception {
        long start = SystemClock.elapsedRealtimeNanos();
        ParcelFileDescriptor pfd = open(file, false);
        long end = SystemClock.elapsedRealtimeNanos();
        long openDuration = end - start;

        start = SystemClock.elapsedRealtimeNanos();
        assertEquals(10, Os.pread(pfd.getFileDescriptor(), new byte[10], 0, 10, 0));
        end = SystemClock.elapsedRealtimeNanos();
        long readDuration = end - start;

        // With transcoding read(2) dominates open(2)
        // Without transcoding open(2) dominates IO
        String message = "readDuration=" + readDuration + "ns. openDuration=" + openDuration + "ns";
        if (transcode) {
            assertTrue(message, readDuration > openDuration);
        } else {
            assertTrue(message, openDuration > readDuration);
        }
    }
}
