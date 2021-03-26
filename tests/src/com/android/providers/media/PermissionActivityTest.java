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

import static android.Manifest.permission.ACCESS_MEDIA_LOCATION;
import static android.Manifest.permission.MANAGE_APP_OPS_MODES;
import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.MANAGE_MEDIA;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.providers.media.PermissionActivity.VERB_FAVORITE;
import static com.android.providers.media.PermissionActivity.VERB_TRASH;
import static com.android.providers.media.PermissionActivity.VERB_UNFAVORITE;
import static com.android.providers.media.PermissionActivity.VERB_WRITE;
import static com.android.providers.media.PermissionActivity.shouldShowActionDialog;
import static com.android.providers.media.util.TestUtils.adoptShellPermission;
import static com.android.providers.media.util.TestUtils.dropShellPermission;

import static com.google.common.truth.Truth.assertThat;

import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * We already have solid coverage of this logic in {@code CtsProviderTestCases},
 * but the coverage system currently doesn't measure that, so we add the bare
 * minimum local testing here to convince the tooling that it's covered.
 */
@RunWith(AndroidJUnit4.class)
public class PermissionActivityTest {
    private static final String TEST_APP_PACKAGE_NAME =
            "com.android.providers.media.testapp.withstorageperms";

    private static final int TEST_APP_PID = -1;
    private int mTestAppUid = -1;

    @Before
    public void setUp() throws Exception {
        mTestAppUid = getContext().getPackageManager().getPackageUid(TEST_APP_PACKAGE_NAME, 0);
    }

    @Test
    public void testSimple() throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(inst.getContext(), GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final GetResultActivity activity = (GetResultActivity) inst.startActivitySync(intent);
        activity.startActivityForResult(createIntent(), 42);
    }

    @Test
    public void testShouldShowActionDialog_favorite_false() throws Exception {
        assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                TEST_APP_PACKAGE_NAME, null, VERB_FAVORITE)).isFalse();
    }

    @Test
    public void testShouldShowActionDialog_unfavorite_false() throws Exception {
        assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                TEST_APP_PACKAGE_NAME, null, VERB_UNFAVORITE)).isFalse();
    }

    @Test
    public void testShouldShowActionDialog_noRESAndMES_true() throws Exception {
        final String[] enableAppOpsList = {AppOpsManager.permissionToOp(MANAGE_MEDIA)};
        final String[] disableAppOpsList = {
                AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE),
                AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE)};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            for (String op : enableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ALLOWED);
            }

            for (String op : disableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ERRORED);
            }

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testShouldShowActionDialog_noMANAGE_MEDIA_true() throws Exception {
        final String[] enableAppOpsList = {
                AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE),
                AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE)};
        final String[] disableAppOpsList =  {AppOpsManager.permissionToOp(MANAGE_MEDIA)};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            for (String op : enableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ALLOWED);
            }

            for (String op : disableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ERRORED);
            }

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testShouldShowActionDialog_hasPermissionWithRES_false() throws Exception {
        final String[] enableAppOpsList = {
                AppOpsManager.permissionToOp(MANAGE_MEDIA),
                AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE)};
        final String[] disableAppOpsList = {AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE)};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            for (String op : enableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ALLOWED);
            }

            for (String op : disableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ERRORED);
            }

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testShouldShowActionDialog_hasPermissionWithMES_false() throws Exception {
        final String[] enableAppOpsList = {
                AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE),
                AppOpsManager.permissionToOp(MANAGE_MEDIA)};
        final String[] disableAppOpsList = {AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE)};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            for (String op : enableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ALLOWED);
            }

            for (String op : disableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ERRORED);
            }

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testShouldShowActionDialog_writeNoACCESS_MEDIA_LOCATION_true() throws Exception {
        final String[] enableAppOpsList = {
                AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE),
                AppOpsManager.permissionToOp(MANAGE_MEDIA),
                AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE)};
        final String[] disableAppOpsList = {AppOpsManager.permissionToOp(ACCESS_MEDIA_LOCATION)};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            for (String op : enableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ALLOWED);
            }

            for (String op : disableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ERRORED);
            }

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_WRITE)).isTrue();
        } finally {
            dropShellPermission();
        }
    }

    @Test
    public void testShouldShowActionDialog_writeHasACCESS_MEDIA_LOCATION_false() throws Exception {
        final String[] enableAppOpsList = {
                AppOpsManager.permissionToOp(ACCESS_MEDIA_LOCATION),
                AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE),
                AppOpsManager.permissionToOp(MANAGE_MEDIA),
                AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE)};
        final String[] disableAppOpsList = new String[]{};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            for (String op : enableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ALLOWED);
            }

            for (String op : disableAppOpsList) {
                modifyAppOp(mTestAppUid, op, AppOpsManager.MODE_ERRORED);
            }

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_WRITE)).isFalse();
        } finally {
            dropShellPermission();
        }
    }

    private static Intent createIntent() throws Exception {
        final Context context = InstrumentationRegistry.getContext();

        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        final File file = MediaScannerTest.stage(R.raw.test_image,
                new File(dir, "test" + System.nanoTime() + ".jpg"));
        final Uri uri = MediaStore.scanFile(context.getContentResolver(), file);

        final Intent intent = new Intent(MediaStore.CREATE_WRITE_REQUEST_CALL, null,
                context, PermissionActivity.class);
        intent.putExtra(MediaStore.EXTRA_CLIP_DATA, ClipData.newRawUri("", uri));
        intent.putExtra(MediaStore.EXTRA_CONTENT_VALUES, new ContentValues());
        return intent;
    }

    private static void modifyAppOp(int uid, String op, int mode) {
        getContext().getSystemService(AppOpsManager.class).setUidMode(op, uid, mode);
    }
}
