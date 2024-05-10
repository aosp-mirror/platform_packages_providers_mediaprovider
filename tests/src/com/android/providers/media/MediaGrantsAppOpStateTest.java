/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.Manifest.permission.MANAGE_APP_OPS_MODES;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VISUAL_USER_SELECTED;
import static android.provider.MediaStore.grantMediaReadForPackage;

import static com.android.providers.media.util.FileCreationUtils.insertFileInResolver;
import static com.android.providers.media.util.TestUtils.dropShellPermission;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;
import com.android.providers.media.util.FileCreationUtils;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SdkSuppress(minSdkVersion = 34, codeName = "UpsideDownCake")
@RunWith(AndroidJUnit4.class)
public class MediaGrantsAppOpStateTest {
    private static final TestApp TEST_APP_WITH_USER_SELECTED_PERMS =
            new TestApp(
                    "TestAppWithUserSelectedPerms",
                    "com.android.providers.media.testapp.withuserselectedperms",
                    1,
                    false,
                    "MediaProviderTestAppWithUserSelectedPerms.apk");
    private static final String TEST_APP_PACKAGE_NAME =
            TEST_APP_WITH_USER_SELECTED_PERMS.getPackageName();

    private static Context sIsolatedContext;
    private static DatabaseHelper sExternalDatabase;
    private static int sTestAppUid;
    private static List<Uri> sUriList;
    private static AppOpsManager sAppOpsManager;
    private static final Object sLock = new Object();
    private static AppOpsManager.OnOpChangedListener sOnOpChangedListener =
            (op, packageName) -> sLock.notify();

    @BeforeClass
    public static void setUp() throws Exception {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        android.Manifest.permission.READ_DEVICE_CONFIG,
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        android.Manifest.permission.WRITE_MEDIA_STORAGE,
                        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                        // only needed for this test
                        UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);
        Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sExternalDatabase = ((IsolatedContext) sIsolatedContext).getExternalDatabase();
        sAppOpsManager = context.getSystemService(AppOpsManager.class);
        Long fileId1 = insertFileInResolver(sIsolatedContext.getContentResolver(), "test_file1");
        Long fileId2 = insertFileInResolver(sIsolatedContext.getContentResolver(), "test_file2");
        sUriList = List.of(FileCreationUtils.buildValidPickerUri(fileId1),
                FileCreationUtils.buildValidPickerUri(fileId2));
        sTestAppUid = context.getPackageManager()
                .getPackageUid(TEST_APP_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0));
    }

    @AfterClass
    public static void tearDown() {
        try {
            for (Uri uri: sUriList) {
                sIsolatedContext.getContentResolver().delete(uri, Bundle.EMPTY);
            }
        } catch (Exception ignored) {
        }
        dropShellPermission();
    }

    @Test
    public void testAppOpStateChangeToAllowAll() throws Exception {
        // Set the initial state to User Select mode
        denyAppOp(OPSTR_READ_MEDIA_IMAGES);
        denyAppOp(OPSTR_READ_MEDIA_VIDEO);
        allowAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);

        grantMediaReadForPackage(sIsolatedContext, sTestAppUid, sUriList);
        // verify we can see the grant
        assertThat(getRowCountForTestPackage()).isEqualTo(sUriList.size());

        // Change the state to Allow All
        allowAppOp(OPSTR_READ_MEDIA_IMAGES);
        allowAppOp(OPSTR_READ_MEDIA_VIDEO);
        // Verify that grants are removed
        assertThat(getRowCountForTestPackage()).isEqualTo(0);

        // Change the state back to "Select flow"
        denyAppOp(OPSTR_READ_MEDIA_IMAGES);
        denyAppOp(OPSTR_READ_MEDIA_VIDEO);
        assertThat(getRowCountForTestPackage()).isEqualTo(0);
    }

    @Test
    public void testAppOpStateChangeToDenyAll() throws Exception {
        // Set the initial state to User Select mode
        denyAppOp(OPSTR_READ_MEDIA_IMAGES);
        denyAppOp(OPSTR_READ_MEDIA_VIDEO);
        allowAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);

        grantMediaReadForPackage(sIsolatedContext, sTestAppUid, sUriList);
        // verify we can see the grant
        assertThat(getRowCountForTestPackage()).isEqualTo(sUriList.size());

        // Change the state to deny all
        denyAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);
        assertThat(getRowCountForTestPackage()).isEqualTo(0);

        // Change the state back to "Select Flow"
        allowAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);
        assertThat(getRowCountForTestPackage()).isEqualTo(0);
    }

    @Test
    public void testGrantSelectFlowDoesntClearGrants() throws Exception {
        // Set the initial state to deny all
        denyAppOp(OPSTR_READ_MEDIA_IMAGES);
        denyAppOp(OPSTR_READ_MEDIA_VIDEO);
        denyAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);

        grantMediaReadForPackage(sIsolatedContext, sTestAppUid, sUriList);
        allowAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);
        // verify we can see the grant
        assertThat(getRowCountForTestPackage()).isEqualTo(sUriList.size());
    }

    @Test
    public void testAllowVideosOnlyClearsGrants() throws Exception {
        // Set the initial state to User Select mode
        denyAppOp(OPSTR_READ_MEDIA_IMAGES);
        denyAppOp(OPSTR_READ_MEDIA_VIDEO);
        allowAppOp(OPSTR_READ_MEDIA_VISUAL_USER_SELECTED);

        grantMediaReadForPackage(sIsolatedContext, sTestAppUid, sUriList);
        // verify we can see the grant
        assertThat(getRowCountForTestPackage()).isEqualTo(sUriList.size());

        // Change the state to Allow All for Videos
        allowAppOp(OPSTR_READ_MEDIA_VIDEO);
        assertThat(getRowCountForTestPackage()).isEqualTo(0);
    }

    private void allowAppOp(String op) throws InterruptedException {
        modifyAppOpAndPoll(op, AppOpsManager.MODE_ALLOWED);
    }

    private void denyAppOp(String op) throws InterruptedException {
        modifyAppOpAndPoll(op, AppOpsManager.MODE_ERRORED);
    }

    private void modifyAppOpAndPoll(String op, int mode)
            throws InterruptedException {
        sAppOpsManager.startWatchingMode(op, TEST_APP_PACKAGE_NAME, sOnOpChangedListener);
        synchronized (sLock) {
            sAppOpsManager.setUidMode(op, sTestAppUid, mode);
            // Make our best effort to exit early on op change, otherwise wait for 100ms if this was
            // a no-op change.
            sLock.wait(100);
        }
        sAppOpsManager.stopWatchingMode(sOnOpChangedListener);
    }

    private int getRowCountForTestPackage() {
        try (Cursor c = sExternalDatabase.runWithTransaction(
                (db) -> db.query(MediaGrants.MEDIA_GRANTS_TABLE,
                        new String[]{MediaGrants.FILE_ID_COLUMN,
                                MediaGrants.OWNER_PACKAGE_NAME_COLUMN},
                        String.format("%s = '%s'",
                                MediaGrants.OWNER_PACKAGE_NAME_COLUMN, TEST_APP_PACKAGE_NAME),
                        null, null, null, null))) {
            assertWithMessage("Expected cursor to be not null").that(c).isNotNull();
            return c.getCount();
        }
    }
}
