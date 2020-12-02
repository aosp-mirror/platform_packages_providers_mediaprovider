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

import static com.android.providers.media.transcode.TranscodeTestConstants.INTENT_EXTRA_CALLING_PKG;
import static com.android.providers.media.transcode.TranscodeTestConstants.INTENT_EXTRA_PATH;
import static com.android.providers.media.transcode.TranscodeTestConstants.OPEN_FILE_QUERY;
import static com.android.providers.media.transcode.TranscodeTestConstants.INTENT_QUERY_TYPE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
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
        return ParcelFileDescriptor.open(file, forWrite ? ParcelFileDescriptor.MODE_READ_WRITE
                : ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public static ParcelFileDescriptor open(Uri uri, boolean forWrite, Bundle bundle)
            throws Exception {
        ContentResolver resolver = getContext().getContentResolver();
        if (bundle == null) {
            return resolver.openFileDescriptor(uri, forWrite ? "rw" : "r");
        } else {
            return resolver.openTypedAssetFileDescriptor(uri, "*/*", bundle)
                    .getParcelFileDescriptor();
        }
    }

    public static void enableSeamlessTranscoding() throws Exception {
        executeShellCommand("setprop persist.sys.fuse.transcode_user_control true");
        executeShellCommand("setprop persist.sys.fuse.transcode_enabled true");
        executeShellCommand("setprop persist.sys.fuse.transcode_default false");
    }

    public static void disableSeamlessTranscoding() throws Exception {
        executeShellCommand("setprop persist.sys.fuse.transcode_user_control true");
        executeShellCommand("setprop persist.sys.fuse.transcode_enabled false");
        executeShellCommand("setprop persist.sys.fuse.transcode_default false");
    }

    public static void enableTranscodingForPackage(String packageName) throws IOException {
        // TODO(b/169327180): Enable per package
        executeShellCommand("setprop persist.sys.fuse.transcode_default true");
    }

    public static void forceEnableAppCompatHevc(String packageName) throws IOException {
        final String command = "am compat enable 174228127 " + packageName;
        executeShellCommand(command);
    }

    public static void forceDisableAppCompatHevc(String packageName) throws IOException {
        final String command = "am compat enable 174227820 " + packageName;
        executeShellCommand(command);
    }

    public static void resetAppCompat(String packageName) throws IOException {
        final String command = "am compat reset-all " + packageName;
        executeShellCommand(command);
    }

    public static void disableTranscodingForAllPackages() throws IOException {
        executeShellCommand("setprop persist.sys.fuse.transcode_default false");
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

    public static void grantPermission(String packageName, String permission) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
        try {
            uiAutomation.grantRuntimePermission(packageName, permission);
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

    /**
     * Installs a {@link TestApp} and grants it storage permissions.
     */
    public static void installAppWithStoragePermissions(TestApp testApp)
            throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            final String packageName = testApp.getPackageName();
            uiAutomation.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES, Manifest.permission.DELETE_PACKAGES);
            if (InstallUtils.getInstalledVersion(packageName) != -1) {
                Uninstall.packages(packageName);
            }
            Install.single(testApp).commit();
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(1);

            grantPermission(packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            grantPermission(packageName, Manifest.permission.READ_EXTERNAL_STORAGE);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Uninstalls a {@link TestApp}.
     */
    public static void uninstallApp(TestApp testApp) throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            final String packageName = testApp.getPackageName();
            uiAutomation.adoptShellPermissionIdentity(Manifest.permission.DELETE_PACKAGES);

            Uninstall.packages(packageName);
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(-1);
        } catch (Exception e) {
            Log.e(TAG, "Exception occurred while uninstalling app: " + testApp, e);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Makes the given {@code testApp} open a file for read or write.
     *
     * <p>This method drops shell permission identity.
     */
    public static ParcelFileDescriptor openFileAs(TestApp testApp, File dirPath)
            throws Exception {
        String actionName = getContext().getPackageName() + ".open_file";
        Bundle bundle = getFromTestApp(testApp, dirPath.getPath(), actionName);
        return getContext().getContentResolver().openFileDescriptor(
                bundle.getParcelable(actionName), "rw");
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static Bundle getFromTestApp(TestApp testApp, String dirPath, String actionName)
            throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Bundle[] bundle = new Bundle[1];
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                bundle[0] = intent.getExtras();
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return bundle[0];
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static void sendIntentToTestApp(TestApp testApp, String dirPath, String actionName,
            BroadcastReceiver broadcastReceiver, CountDownLatch latch) throws Exception {
        final String packageName = testApp.getPackageName();
        forceStopApp(packageName);
        // Register broadcast receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(actionName);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        getContext().registerReceiver(broadcastReceiver, intentFilter);

        // Launch the test app.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(INTENT_QUERY_TYPE, actionName);
        intent.putExtra(INTENT_EXTRA_CALLING_PKG, getContext().getPackageName());
        intent.putExtra(INTENT_EXTRA_PATH, dirPath);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        getContext().startActivity(intent);
        if (!latch.await(POLLING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            final String errorMessage = "Timed out while waiting to receive " + actionName
                    + " intent from " + packageName;
            throw new TimeoutException(errorMessage);
        }
        getContext().unregisterReceiver(broadcastReceiver);
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static void forceStopApp(String packageName) throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(Manifest.permission.FORCE_STOP_PACKAGES);

            getContext().getSystemService(ActivityManager.class).forceStopPackage(packageName);
            Thread.sleep(1000);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    public static void assertFileContent(File file1, File file2, ParcelFileDescriptor pfd1,
            ParcelFileDescriptor pfd2, boolean assertSame) throws Exception {
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

        String message = String.format("Files: %s and %s. isSame=%b. assertSame=%s",
                file1, file2, isSame, assertSame);
        assertEquals(message, isSame, assertSame);
    }

    public static void assertTranscode(Uri uri, boolean transcode) throws Exception {
        long start = SystemClock.elapsedRealtimeNanos();
        assertTranscode(open(uri, true, null /* bundle */), transcode);
    }

    public static void assertTranscode(File file, boolean transcode) throws Exception {
        assertTranscode(open(file, false), transcode);
    }

    public static void assertTranscode(ParcelFileDescriptor pfd, boolean transcode)
            throws Exception {
        long start = SystemClock.elapsedRealtimeNanos();
        assertEquals(10, Os.pread(pfd.getFileDescriptor(), new byte[10], 0, 10, 0));
        long end = SystemClock.elapsedRealtimeNanos();
        long readDuration = end - start;

        // With transcoding read(2) > 100ms (usually > 1s)
        // Without transcoding read(2) < 10ms (usually < 1ms)
        String message = "readDuration=" + readDuration + "ns";
        if (transcode) {
            assertTrue(message, readDuration > TimeUnit.MILLISECONDS.toNanos(100));
        } else {
            assertTrue(message, readDuration < TimeUnit.MILLISECONDS.toNanos(10));
        }
    }
}
