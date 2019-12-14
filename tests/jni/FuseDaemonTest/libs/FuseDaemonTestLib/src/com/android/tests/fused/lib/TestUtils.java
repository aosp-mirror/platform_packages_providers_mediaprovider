/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tests.fused.lib;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.tests.fused.lib.ReaddirTestHelper.CREATE_FILE_QUERY;
import static com.android.tests.fused.lib.ReaddirTestHelper.DELETE_FILE_QUERY;
import static com.android.tests.fused.lib.ReaddirTestHelper.READDIR_QUERY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;

import com.google.common.io.ByteStreams;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * General helper functions for FuseDaemon tests.
 */
public class TestUtils {
    static final String TAG = "FuseDaemonTest";

    public static final String QUERY_TYPE = "com.android.tests.fused.queryType";

    private static final UiAutomation sUiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation();

    /**
     * Grants {@link Manifest.permission#GRANT_RUNTIME_PERMISSIONS} to the given package.
     */
    public static void grantReadExternalStorage(String packageName) throws Exception {
        sUiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
        try {
            sUiAutomation.grantRuntimePermission(packageName,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Revokes {@link Manifest.permission#GRANT_RUNTIME_PERMISSIONS} from the given package.
     */
    public static void revokeReadExternalStorage(String packageName) {
        sUiAutomation.adoptShellPermissionIdentity("android.permission.REVOKE_RUNTIME_PERMISSIONS");
        try {
            sUiAutomation.revokeRuntimePermission(packageName,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    public static String executeShellCommand(String cmd) throws Exception {
        try (FileInputStream output = new FileInputStream (sUiAutomation.executeShellCommand(cmd)
                .getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    /**
     * Makes the given {@code testApp} list the content of the given directory and returns the
     * result as an {@link ArrayList}
     */
    public static ArrayList<String> listAs(TestApp testApp, String dirPath)
            throws Exception {
        return getContentsFromTestApp(testApp, dirPath, READDIR_QUERY);
    }

    /**
     * Makes the given {@code testApp} create a file.
     */
    public static boolean createFileAs(TestApp testApp, String path) throws Exception {
        return createOrDeleteFileFromTestApp(testApp, path, CREATE_FILE_QUERY);
    }

    /**
     * Makes the given {@code testApp} delete a file.
     */
    public static boolean deleteFileAs(TestApp testApp, String path) throws Exception {
        return createOrDeleteFileFromTestApp(testApp, path, DELETE_FILE_QUERY);
    }

    /**
     * Installs a {@link TestApp} and may grant it storage permissions.
     */
    public static void installApp(TestApp testApp, boolean grantStoragePermission)
            throws Exception {

        try {
            final String packageName = testApp.getPackageName();
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);
            if (InstallUtils.getInstalledVersion(packageName) != -1) {
                Uninstall.packages(packageName);
            }
            Install.single(testApp).commit();
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(1);
            if (grantStoragePermission) {
                grantReadExternalStorage(packageName);
            }
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Uninstalls a {@link TestApp}.
     */
    public static void uninstallApp(TestApp testApp) throws Exception {
        try {
            final String packageName = testApp.getPackageName();
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.DELETE_PACKAGES);

            Uninstall.packages(packageName);
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(-1);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    public static <T extends Exception> void assertThrows(Class<T> clazz, Operation<T> r)
            throws Exception {
        assertThrows(clazz, "", r);
    }

    public static <T extends Exception> void assertThrows(Class<T> clazz, String errMsg,
            Operation<T> r) throws Exception {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass()) || !e.getMessage().contains(errMsg)) {
                Log.e(TAG, "Expected " + clazz + " exception with error message: " + errMsg, e);
                throw e;
            }
        }
    }

    /**
     * A functional interface representing an operation that takes no arguments,
     * returns no arguments and might throw an {@link Exception} of any kind.
     */
    @FunctionalInterface
    public interface Operation<T extends Exception> {
        /**
         * This is the method that gets called for any object that implements this interface.
         */
        void run() throws T;
    }

    private static void forceStopApp(String packageName) throws Exception {
        try {
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.FORCE_STOP_PACKAGES);

            getContext().getSystemService(ActivityManager.class).forceStopPackage(packageName);
            Thread.sleep(1000);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private static void sendIntentToTestApp(TestApp testApp, String dirPath, String actionName,
            BroadcastReceiver broadcastReceiver, CountDownLatch latch) throws Exception {

        final ArrayList<String> appOutputList = new ArrayList<String>();
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
        intent.putExtra(QUERY_TYPE, actionName);
        intent.putExtra(actionName, dirPath);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        getContext().startActivity(intent);
        latch.await();
        getContext().unregisterReceiver(broadcastReceiver);
    }

    private static ArrayList<String> getContentsFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<String> appOutputList = new ArrayList<String>();
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.hasExtra(actionName)) {
                    appOutputList.addAll(intent.getStringArrayListExtra(actionName));
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutputList;
    }

    private static boolean createOrDeleteFileFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] appOutput = new boolean[1];
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.hasExtra(actionName)) {
                    appOutput[0] = intent.getBooleanExtra(actionName, false);
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutput[0];
    }
}