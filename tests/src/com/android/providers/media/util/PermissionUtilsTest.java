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

package com.android.providers.media.util;

import static android.Manifest.permission.MANAGE_APP_OPS_MODES;
import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.MANAGE_MEDIA;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;
import static android.app.AppOpsManager.OPSTR_NO_ISOLATED_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.providers.media.util.PermissionUtils.checkAppOpRequestInstallPackagesForSharedUid;
import static com.android.providers.media.util.PermissionUtils.checkIsLegacyStorageGranted;
import static com.android.providers.media.util.PermissionUtils.checkNoIsolatedStorageGranted;
import static com.android.providers.media.util.PermissionUtils.checkPermissionAccessMediaLocation;
import static com.android.providers.media.util.PermissionUtils.checkPermissionAccessMtp;
import static com.android.providers.media.util.PermissionUtils.checkPermissionDelegator;
import static com.android.providers.media.util.PermissionUtils.checkPermissionInstallPackages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManageMedia;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManager;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVideo;
import static com.android.providers.media.util.PermissionUtils.checkPermissionSelf;
import static com.android.providers.media.util.PermissionUtils.checkPermissionShell;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteVideo;
import static com.android.providers.media.util.PermissionUtils.checkWriteImagesOrVideoAppOps;
import static com.android.providers.media.util.TestUtils.QUERY_TYPE;
import static com.android.providers.media.util.TestUtils.RUN_INFINITE_ACTIVITY;
import static com.android.providers.media.util.TestUtils.adoptShellPermission;
import static com.android.providers.media.util.TestUtils.dropShellPermission;
import static com.android.providers.media.util.TestUtils.getPid;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;

import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class PermissionUtilsTest {
    private static final TestApp TEST_APP_WITH_STORAGE_PERMS = new TestApp(
            "TestAppWithStoragePerms",
            "com.android.providers.media.testapp.withstorageperms", 1, false,
            "MediaProviderTestAppWithStoragePerms.apk");
    private static final TestApp TEST_APP_WITHOUT_PERMS = new TestApp("TestAppWithoutPerms",
            "com.android.providers.media.testapp.withoutperms", 1, false,
            "MediaProviderTestAppWithoutPerms.apk");
    private static final TestApp LEGACY_TEST_APP = new TestApp("LegacyTestApp",
            "com.android.providers.media.testapp.legacy", 1, false,
            "LegacyMediaProviderTestApp.apk");
    private static final Map<String, Integer> pidMap = new HashMap<>();

    @BeforeClass
    public static void startTestApps() throws Exception {
        startTestApp(TEST_APP_WITH_STORAGE_PERMS);
        startTestApp(TEST_APP_WITHOUT_PERMS);
        startTestApp(LEGACY_TEST_APP);
    }

    @Test
    public void testConstructor() {
        new PermissionUtils();
    }

    /**
     * The best we can do here is assert that we're granted the permissions that
     * we expect to be holding.
     */
    @Test
    public void testSelfPermissions() {
        final Context context = getContext();
        final int pid = android.os.Process.myPid();
        final int uid = android.os.Process.myUid();
        final String packageName = context.getPackageName();

        assertThat(checkPermissionSelf(context, pid, uid)).isTrue();
        assertThat(checkPermissionShell(context, pid, uid)).isFalse();
        assertThat(checkPermissionManager(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionDelegator(context, pid, uid)).isFalse();
        assertThat(checkPermissionManageMedia(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionAccessMediaLocation(context, pid, uid,
                packageName, null)).isFalse();

        assertThat(checkPermissionReadStorage(context, pid, uid, packageName, null)).isTrue();
        assertThat(checkPermissionWriteStorage(context, pid, uid, packageName, null)).isTrue();

        assertThat(checkPermissionReadAudio(context, pid, uid, packageName, null)).isTrue();
        assertThat(checkPermissionWriteAudio(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionReadVideo(context, pid, uid, packageName, null)).isTrue();
        assertThat(checkPermissionWriteVideo(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionReadImages(context, pid, uid, packageName, null)).isTrue();
        assertThat(checkPermissionWriteImages(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionInstallPackages(context, pid, uid, packageName, null)).isFalse();
    }

    /**
     * Test that {@code android:no_isolated_storage} app op is by default denied.
     */
    @Test
    public void testNoIsolatedStorageIsByDefaultDenied() throws Exception {
        final Context context = getContext();
        final int uid = android.os.Process.myUid();
        final String packageName = context.getPackageName();
        assertThat(checkNoIsolatedStorageGranted(context, uid, packageName, null)).isFalse();
    }

    @Test
    public void testDefaultPermissionsOnTestAppWithStoragePerms() throws Exception {
        String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), testAppPid, testAppUid)).isFalse();
            assertThat(checkPermissionShell(getContext(), testAppPid, testAppUid)).isFalse();
            assertThat(
                    checkIsLegacyStorageGranted(getContext(), testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionInstallPackages(getContext(), testAppPid, testAppUid,
                            packageName, null)).isFalse();
            assertThat(
                    checkPermissionAccessMtp(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionWriteStorage(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();
            checkReadPermissions(testAppPid, testAppUid, packageName, true);
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testDefaultPermissionsOnTestAppWithoutPerms() throws Exception {
        String packageName = TEST_APP_WITHOUT_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), testAppPid, testAppUid)).isFalse();
            assertThat(checkPermissionShell(getContext(), testAppPid, testAppUid)).isFalse();
            assertThat(
                    checkPermissionManager(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();

            assertThat(checkPermissionManageMedia(getContext(), testAppPid, testAppUid, packageName,
                    null)).isFalse();

            assertThat(checkPermissionAccessMediaLocation(getContext(), testAppPid, testAppUid,
                    packageName, null)).isFalse();

            assertThat(
                    checkIsLegacyStorageGranted(getContext(), testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionInstallPackages(getContext(), testAppPid, testAppUid,
                            packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionAccessMtp(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionWriteStorage(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();
            checkReadPermissions(testAppPid, testAppUid, packageName, false);
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testDefaultPermissionsOnLegacyTestApp() throws Exception {
        String packageName = LEGACY_TEST_APP.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), testAppPid, testAppUid)).isFalse();
            assertThat(checkPermissionShell(getContext(), testAppPid, testAppUid)).isFalse();
            assertThat(
                    checkPermissionManager(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();

            assertThat(checkPermissionManageMedia(getContext(), testAppPid, testAppUid, packageName,
                    null)).isFalse();

            assertThat(checkPermissionAccessMediaLocation(getContext(), testAppPid, testAppUid,
                    packageName, null)).isTrue();

            assertThat(
                    checkIsLegacyStorageGranted(getContext(), testAppUid, packageName,
                            null)).isTrue();
            assertThat(
                    checkPermissionInstallPackages(getContext(), testAppPid, testAppUid,
                            packageName, null)).isFalse();
            assertThat(
                    checkPermissionAccessMtp(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionWriteStorage(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();
            checkReadPermissions(testAppPid, testAppUid, packageName, true);
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testManageExternalStoragePermissionsOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        final int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        final int testAppPid = pidMap.get(packageName);
        final String op = AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ERRORED);

            assertThat(checkPermissionManager(getContext(), testAppPid, testAppUid, packageName,
                    null)).isFalse();

            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ALLOWED);

            assertThat(checkPermissionManager(getContext(), testAppPid, testAppUid, packageName,
                    null)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testManageMediaPermissionsOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        final int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        final int testAppPid = pidMap.get(packageName);
        final String op = AppOpsManager.permissionToOp(MANAGE_MEDIA);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ERRORED);

            assertThat(checkPermissionManageMedia(getContext(), testAppPid, testAppUid, packageName,
                    null)).isFalse();

            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ALLOWED);

            assertThat(checkPermissionManageMedia(getContext(), testAppPid, testAppUid, packageName,
                    null)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testSystemGalleryPermissionsOnTestApp() throws Exception {
        String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            checkPermissionsForGallery(testAppUid, testAppPid, packageName, false);

            final String[] SYSTEM_GALERY_APPOPS =
                    {OPSTR_WRITE_MEDIA_IMAGES, OPSTR_WRITE_MEDIA_VIDEO};
            for (String op : SYSTEM_GALERY_APPOPS) {
                modifyAppOp(testAppUid, op, AppOpsManager.MODE_ALLOWED);
            }
            checkPermissionsForGallery(testAppUid, testAppPid, packageName, true);

            for (String op : SYSTEM_GALERY_APPOPS) {
                modifyAppOp(testAppUid, op, AppOpsManager.MODE_ERRORED);
            }
            checkPermissionsForGallery(testAppUid, testAppPid, packageName, false);
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testIsolatedStoragePermissionsOnTestApp() throws Exception {
        String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkNoIsolatedStorageGranted(getContext(), testAppUid, packageName,
                            null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_NO_ISOLATED_STORAGE, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkNoIsolatedStorageGranted(getContext(), testAppUid, packageName,
                            null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_NO_ISOLATED_STORAGE, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkNoIsolatedStorageGranted(getContext(), testAppUid, packageName,
                            null)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testReadVideoOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(
                packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkPermissionReadVideo(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VIDEO, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkPermissionReadVideo(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VIDEO, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkPermissionReadVideo(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testWriteAudioOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(
                packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkPermissionWriteAudio(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_WRITE_MEDIA_AUDIO, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkPermissionWriteAudio(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_WRITE_MEDIA_AUDIO, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkPermissionWriteAudio(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testReadAudioOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(
                packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkPermissionReadAudio(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_AUDIO, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkPermissionReadAudio(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_AUDIO, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkPermissionReadAudio(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testReadImagesOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        int testAppPid = pidMap.get(packageName);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkPermissionReadImages(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_IMAGES, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkPermissionReadImages(getContext(), testAppPid, testAppUid, packageName,
                            null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_IMAGES, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkPermissionReadImages(getContext(), testAppPid, testAppUid, packageName,
                            null)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testOpstrInstallPermissionsOnTestApp() throws Exception {
        int testAppUid = getContext().getPackageManager().getPackageUid(
                TEST_APP_WITH_STORAGE_PERMS.getPackageName(), 0);
        String[] packageName = {TEST_APP_WITH_STORAGE_PERMS.getPackageName()};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkAppOpRequestInstallPackagesForSharedUid(getContext(), testAppUid,
                            packageName, null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_REQUEST_INSTALL_PACKAGES, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkAppOpRequestInstallPackagesForSharedUid(getContext(), testAppUid,
                            packageName, null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_REQUEST_INSTALL_PACKAGES, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkAppOpRequestInstallPackagesForSharedUid(getContext(), testAppUid,
                            packageName, null)).isFalse();
        } finally {
            // App gets killed when we try to give it OPSTR_REQUEST_INSTALL_PACKAGES. Restart the
            // app so it doesn't affect other tests.
            startTestApp(TEST_APP_WITH_STORAGE_PERMS);
            dropShellPermission();
        }
    }

    static private void startTestApp(TestApp testApp) throws Exception {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(testApp.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.putExtra(QUERY_TYPE, RUN_INFINITE_ACTIVITY);
        getContext().startActivity(intent);
        pidMap.put(testApp.getPackageName(), getPid(testApp.getPackageName()));
    }

    static private void modifyAppOp(int uid, String op, int mode) {
        getContext().getSystemService(AppOpsManager.class).setUidMode(op, uid, mode);
    }

    static private void checkPermissionsForGallery(int uid, int pid, String packageName,
            boolean expected) {
        assertEquals(expected,
                checkWriteImagesOrVideoAppOps(getContext(), uid, packageName, null));
        assertEquals(expected,
                checkPermissionWriteImages(getContext(), pid, uid, packageName, null));
        assertEquals(expected,
                checkPermissionWriteVideo(getContext(), pid, uid, packageName, null));
        assertThat(checkPermissionWriteAudio(getContext(), pid, uid, packageName, null)).isFalse();
    }

    static private void checkReadPermissions(int pid, int uid, String packageName,
            boolean expected) {
        assertEquals(expected,
                checkPermissionReadStorage(getContext(), pid, uid, packageName, null));
        assertEquals(expected,
                checkPermissionReadAudio(getContext(), pid, uid, packageName, null));
        assertEquals(expected,
                checkPermissionReadImages(getContext(), pid, uid, packageName, null));
        assertEquals(expected,
                checkPermissionReadVideo(getContext(), pid, uid, packageName, null));
    }
}
