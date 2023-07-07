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
import static android.app.AppOpsManager.OPSTR_ACCESS_MEDIA_LOCATION;
import static android.app.AppOpsManager.OPSTR_NO_ISOLATED_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VISUAL_USER_SELECTED;
import static android.app.AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO;

import static androidx.test.InstrumentationRegistry.getContext;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

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
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVisualUserSelected;
import static com.android.providers.media.util.PermissionUtils.checkPermissionSelf;
import static com.android.providers.media.util.PermissionUtils.checkPermissionShell;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteVideo;
import static com.android.providers.media.util.PermissionUtils.checkWriteImagesOrVideoAppOps;
import static com.android.providers.media.util.TestUtils.adoptShellPermission;
import static com.android.providers.media.util.TestUtils.dropShellPermission;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PermissionUtilsTest {
    private static final TestApp TEST_APP_WITH_STORAGE_PERMS = new TestApp(
            "TestAppWithStoragePerms",
            "com.android.providers.media.testapp.withstorageperms", 1, false,
            "MediaProviderTestAppWithStoragePerms.apk");
    private static final TestApp TEST_APP_WITH_MEDIA_PERMS =
            new TestApp(
                    "TestAppWithMediaPerms",
                    "com.android.providers.media.testapp.withmediaperms",
                    1,
                    false,
                    "MediaProviderTestAppWithMediaPerms.apk");
    private static final TestApp TEST_APP_WITH_USER_SELECTED_PERMS =
            new TestApp(
                    "TestAppWithUserSelectedPerms",
                    "com.android.providers.media.testapp.withuserselectedperms",
                    1,
                    false,
                    "MediaProviderTestAppWithUserSelectedPerms.apk");
    private static final TestApp TEST_APP_WITHOUT_PERMS = new TestApp("TestAppWithoutPerms",
            "com.android.providers.media.testapp.withoutperms", 1, false,
            "MediaProviderTestAppWithoutPerms.apk");
    private static final TestApp LEGACY_TEST_APP = new TestApp("LegacyTestApp",
            "com.android.providers.media.testapp.legacy", 1, false,
            "LegacyMediaProviderTestApp.apk");

    // Permission checks are based on uid, so we can pass -1 pid and avoid starting the test apps.
    private static final int TEST_APP_PID = -1;

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
        assertThat(checkPermissionShell(uid)).isFalse();
        assertThat(checkPermissionManager(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionDelegator(context, pid, uid)).isFalse();
        assertThat(checkPermissionManageMedia(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionAccessMediaLocation(context, pid, uid,
                packageName, null, true)).isFalse();

        assertThat(checkPermissionReadStorage(context, pid, uid, packageName, null)).isTrue();
        assertThat(checkPermissionWriteStorage(context, pid, uid, packageName, null)).isTrue();

        assertThat(checkPermissionReadAudio(context, pid, uid, packageName, null, false)).isTrue();
        assertThat(checkPermissionWriteAudio(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionReadVideo(context, pid, uid, packageName, null, false)).isTrue();
        assertThat(checkPermissionWriteVideo(context, pid, uid, packageName, null)).isFalse();
        assertThat(checkPermissionReadImages(context, pid, uid, packageName, null, false)).isTrue();
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
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU, codeName = "Tiramisu")
    public void testDefaultPermissionsOnTestAppWithStoragePerms() throws Exception {
        String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), TEST_APP_PID, testAppUid)).isFalse();
            assertThat(checkPermissionShell(testAppUid)).isFalse();
            assertThat(
                    checkIsLegacyStorageGranted(getContext(), testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionInstallPackages(getContext(), TEST_APP_PID, testAppUid,
                            packageName, null)).isFalse();
            assertThat(
                    checkPermissionAccessMtp(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionWriteStorage(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null)).isTrue();
            assertThat(
                    checkPermissionReadStorage(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isTrue();
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                false /* targetSdkIsAtLeastT */, true /* expected */);
            // APPs with W_E_S can also read media.
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                true /* targetSdkIsAtLeastT */, true /* expected */);

        } finally {
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU, codeName = "Tiramisu")
    public void testDefaultPermissionsOnTestAppWithMediaPerms() throws Exception {
        String packageName = TEST_APP_WITH_MEDIA_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), TEST_APP_PID, testAppUid)).isFalse();
            assertThat(checkPermissionShell(testAppUid)).isFalse();
            assertThat(checkIsLegacyStorageGranted(getContext(), testAppUid, packageName, null))
                    .isFalse();
            assertThat(checkPermissionInstallPackages(
                        getContext(), TEST_APP_PID, testAppUid, packageName, null)).isFalse();
            assertThat(checkPermissionAccessMtp(
                        getContext(), TEST_APP_PID, testAppUid, packageName, null)).isFalse();
            assertThat(checkPermissionWriteStorage(
                        getContext(), TEST_APP_PID, testAppUid, packageName, null)).isFalse();
            assertThat(checkPermissionReadStorage(
                        getContext(), TEST_APP_PID, testAppUid, packageName, null)).isFalse();
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                true /* targetSdkIsAtLeastT */, true /* expected */);
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                false /* targetSdkIsAtLeastT */, false /* expected */);

        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testDefaultPermissionsOnTestAppWithoutPerms() throws Exception {
        String packageName = TEST_APP_WITHOUT_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), TEST_APP_PID, testAppUid)).isFalse();
            assertThat(checkPermissionShell(testAppUid)).isFalse();
            assertThat(
                    checkPermissionManager(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();

            assertThat(
                    checkPermissionManageMedia(
                            getContext(), TEST_APP_PID, testAppUid, packageName, null))
                    .isFalse();

            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, false)).isFalse();

            assertThat(
                    checkIsLegacyStorageGranted(getContext(), testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionInstallPackages(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null)).isFalse();
            assertThat(
                    checkPermissionAccessMtp(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionWriteStorage(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionReadStorage( getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                false /* targetSdkIsAtLeastT */, false /* expected */);
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                true /* targetSdkIsAtLeastT */, false /* expected */);
            assertThat(checkPermissionReadVisualUserSelected(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, false)).isFalse();
            assertThat(checkPermissionReadVisualUserSelected(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, true)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testDefaultPermissionsOnLegacyTestApp() throws Exception {
        String packageName = LEGACY_TEST_APP.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS);

        try {
            assertThat(checkPermissionSelf(getContext(), TEST_APP_PID, testAppUid)).isFalse();
            assertThat(checkPermissionShell(testAppUid)).isFalse();
            assertThat(
                    checkPermissionManager(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();

            assertThat(
                    checkPermissionManageMedia(
                            getContext(), TEST_APP_PID, testAppUid, packageName, null))
                    .isFalse();

            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, false)).isTrue();

            assertThat(
                    checkIsLegacyStorageGranted(getContext(), testAppUid, packageName,
                            null)).isTrue();
            assertThat(
                    checkPermissionInstallPackages(getContext(), TEST_APP_PID, testAppUid,
                            packageName, null)).isFalse();
            assertThat(
                    checkPermissionAccessMtp(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();
            assertThat(
                    checkPermissionWriteStorage(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null)).isFalse();
            assertThat(checkPermissionReadStorage(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null)).isTrue();
            assertMediaReadPermissions(TEST_APP_PID, testAppUid, packageName,
                false /* targetSdkIsAtLeastT */, true /* expected */);
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testManageExternalStoragePermissionsOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        final int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        final String op = AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ERRORED);

            assertThat(checkPermissionManager(getContext(), TEST_APP_PID, testAppUid, packageName,
                    null)).isFalse();

            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ALLOWED);

            assertThat(checkPermissionManager(getContext(), TEST_APP_PID, testAppUid, packageName,
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
        final String op = AppOpsManager.permissionToOp(MANAGE_MEDIA);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ERRORED);

            assertThat(
                    checkPermissionManageMedia(
                            getContext(), TEST_APP_PID, testAppUid, packageName, null))
                    .isFalse();

            modifyAppOp(testAppUid, op, AppOpsManager.MODE_ALLOWED);

            assertThat(
                    checkPermissionManageMedia(
                            getContext(), TEST_APP_PID, testAppUid, packageName, null))
                    .isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testSystemGalleryPermissionsOnTestApp() throws Exception {
        String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            checkPermissionsForGallery(testAppUid, TEST_APP_PID, packageName, false);

            final String[] SYSTEM_GALERY_APPOPS =
                    {OPSTR_WRITE_MEDIA_IMAGES, OPSTR_WRITE_MEDIA_VIDEO};
            for (String op : SYSTEM_GALERY_APPOPS) {
                modifyAppOp(testAppUid, op, AppOpsManager.MODE_ALLOWED);
            }
            checkPermissionsForGallery(testAppUid, TEST_APP_PID, packageName, true);

            for (String op : SYSTEM_GALERY_APPOPS) {
                modifyAppOp(testAppUid, op, AppOpsManager.MODE_ERRORED);
            }
            checkPermissionsForGallery(testAppUid, TEST_APP_PID, packageName, false);
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
    public void testReadVideoOnTestAppWithStoragePerms() throws Exception {
        assertReadVideoOnTestApp(TEST_APP_WITH_STORAGE_PERMS);
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU, codeName = "Tiramisu")
    public void testReadVideoOnTestAppWithMediaPerms() throws Exception {
        assertReadVideoOnTestApp(TEST_APP_WITH_MEDIA_PERMS);
    }

    @Test
    @SdkSuppress(minSdkVersion = 34, codeName = "UpsideDownCake")
    public void testReadMediaVisualUserSelectedOnTestApp() throws Exception {
        boolean targetSdkIsAtLeastT = true;
        final String packageName = TEST_APP_WITH_USER_SELECTED_PERMS.getPackageName();
        int testAppUid = getApplicationContext().getPackageManager().getPackageUid(packageName,
                PackageManager.PackageInfoFlags.of(0));
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);
        try {
            assertThat(checkPermissionReadVisualUserSelected(getApplicationContext(), TEST_APP_PID,
                    testAppUid,
                    packageName, null, targetSdkIsAtLeastT)).isTrue();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VISUAL_USER_SELECTED,
                    AppOpsManager.MODE_ERRORED);
            assertThat(checkPermissionReadVisualUserSelected(getApplicationContext(), TEST_APP_PID,
                    testAppUid,
                    packageName, null, targetSdkIsAtLeastT)).isFalse();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VISUAL_USER_SELECTED,
                    AppOpsManager.MODE_ALLOWED);
            assertThat(checkPermissionReadVisualUserSelected(getApplicationContext(), TEST_APP_PID,
                    testAppUid,
                    packageName, null, targetSdkIsAtLeastT)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    private static void assertReadVideoOnTestApp(TestApp app) throws Exception {
        boolean isAtLeastT = (app == TEST_APP_WITH_MEDIA_PERMS) ? true : false;
        final String packageName = app.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);
        try {
            assertThat(checkPermissionReadVideo(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, isAtLeastT)).isTrue();
            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VIDEO, AppOpsManager.MODE_ERRORED);
            assertThat(checkPermissionReadVideo(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, isAtLeastT)).isFalse();
            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VIDEO, AppOpsManager.MODE_ALLOWED);
            assertThat(checkPermissionReadVideo(getContext(), TEST_APP_PID, testAppUid,
                packageName, null, isAtLeastT)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testWriteAudioOnTestApp() throws Exception {
        final String packageName = TEST_APP_WITH_STORAGE_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(
                packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            assertThat(
                    checkPermissionWriteAudio(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();

            modifyAppOp(testAppUid, OPSTR_WRITE_MEDIA_AUDIO, AppOpsManager.MODE_ALLOWED);
            assertThat(
                    checkPermissionWriteAudio(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isTrue();

            modifyAppOp(testAppUid, OPSTR_WRITE_MEDIA_AUDIO, AppOpsManager.MODE_ERRORED);
            assertThat(
                    checkPermissionWriteAudio(getContext(), TEST_APP_PID, testAppUid, packageName,
                            null)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testReadAudioOnTestAppWithStoragePerms() throws Exception {
        assertReadAudioOnTestApp(TEST_APP_WITH_STORAGE_PERMS);
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU, codeName = "Tiramisu")
    public void testReadAudioOnTestAppWithMediaPerms() throws Exception {
        assertReadAudioOnTestApp(TEST_APP_WITH_MEDIA_PERMS);
    }

    private static void assertReadAudioOnTestApp(TestApp app) throws Exception {
        boolean isAtLeastT = (app == TEST_APP_WITH_MEDIA_PERMS) ? true : false;
        final String packageName = app.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);
        try {
            assertThat(checkPermissionReadAudio(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null, isAtLeastT)).isTrue();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_AUDIO, AppOpsManager.MODE_ERRORED);
            assertThat(checkPermissionReadAudio(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null, isAtLeastT)).isFalse();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_AUDIO, AppOpsManager.MODE_ALLOWED);
            assertThat(checkPermissionReadAudio(getContext(), TEST_APP_PID, testAppUid,
                        packageName, null, isAtLeastT)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testReadImagesOnTestAppWithStoragePerms() throws Exception {
        assertReadImagesOnTestApp(TEST_APP_WITH_STORAGE_PERMS);
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU, codeName = "Tiramisu")
    public void testReadImagesOnTestAppWithMediaPerms() throws Exception {
        assertReadImagesOnTestApp(TEST_APP_WITH_MEDIA_PERMS);
    }

    private static void assertReadImagesOnTestApp(TestApp app) throws Exception {
        boolean isAtLeastT = (app == TEST_APP_WITH_MEDIA_PERMS) ? true : false;
        final String packageName = app.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);
        try {
            assertThat(checkPermissionReadImages(getContext(), TEST_APP_PID, testAppUid,
                            packageName, null, isAtLeastT)).isTrue();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_IMAGES, AppOpsManager.MODE_ERRORED);
            assertThat(checkPermissionReadImages(getContext(), TEST_APP_PID, testAppUid,
                            packageName, null, isAtLeastT)).isFalse();

            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_IMAGES, AppOpsManager.MODE_ALLOWED);
            assertThat(checkPermissionReadImages(getContext(), TEST_APP_PID, testAppUid,
                            packageName, null, isAtLeastT)).isTrue();
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
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codeName = "UpsideDownCake")
    public void requestsAmlCompatBehaviorOnTestApp() throws Exception {
        String packageName = TEST_APP_WITH_MEDIA_PERMS.getPackageName();
        int testAppUid = getContext().getPackageManager().getPackageUid(packageName, 0);
        String packageNameWithUser = TEST_APP_WITH_USER_SELECTED_PERMS.getPackageName();
        int testAppUidWithUser =
                getContext().getPackageManager().getPackageUid(packageNameWithUser, 0);
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);
        try {
            // With AML granted, access granted
            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, true)).isTrue();

            // With AML denied and requested, and RMVUS granted, access granted
            modifyAppOp(testAppUid, OPSTR_ACCESS_MEDIA_LOCATION, AppOpsManager.MODE_IGNORED);
            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, true)).isTrue();

            // With AML denied and requested, and RMVUS granted, but targetSDK < T,
            // access denied
            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, false)).isFalse();

            // With AML denied and requested, and RMVUS denied, access denied
            modifyAppOp(testAppUid, OPSTR_READ_MEDIA_VISUAL_USER_SELECTED,
                    AppOpsManager.MODE_IGNORED);
            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID, testAppUid,
                    packageName, null, false)).isFalse();

            // If AML is granted, and RMVUS granted explicitly, access granted
            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID,
                    testAppUidWithUser, packageNameWithUser, null, true)).isTrue();

            // If AML is denied, RMVUS granted, but requested explicitly, access denied
            modifyAppOp(testAppUidWithUser, OPSTR_ACCESS_MEDIA_LOCATION,
                    AppOpsManager.MODE_IGNORED);
            assertThat(checkPermissionAccessMediaLocation(getContext(), TEST_APP_PID,
                    testAppUidWithUser, packageNameWithUser, null, true)).isFalse();


        } finally {
            dropShellPermission();
        }
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
        assertThat(
                checkPermissionWriteAudio(getContext(), pid, uid, packageName, null))
                .isFalse();
    }

    private static void assertMediaReadPermissions(
            int pid, int uid, String packageName, boolean targetSdkIsAtLeastT, boolean expected) {
        assertEquals(
                expected,
                checkPermissionReadAudio(
                        getContext(), pid, uid, packageName, null, targetSdkIsAtLeastT));
        assertEquals(
                expected,
                checkPermissionReadImages(
                        getContext(), pid, uid, packageName, null, targetSdkIsAtLeastT));
        assertEquals(
                expected,
                checkPermissionReadVideo(
                        getContext(), pid, uid, packageName, null, targetSdkIsAtLeastT));
    }
}
