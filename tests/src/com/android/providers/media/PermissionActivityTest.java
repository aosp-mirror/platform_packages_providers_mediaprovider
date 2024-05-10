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
import static android.Manifest.permission.READ_MEDIA_AUDIO;
import static android.Manifest.permission.READ_MEDIA_IMAGES;
import static android.Manifest.permission.READ_MEDIA_VIDEO;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.providers.media.PermissionActivity.VERB_FAVORITE;
import static com.android.providers.media.PermissionActivity.VERB_TRASH;
import static com.android.providers.media.PermissionActivity.VERB_UNFAVORITE;
import static com.android.providers.media.PermissionActivity.VERB_WRITE;
import static com.android.providers.media.PermissionActivity.shouldShowActionDialog;
import static com.android.providers.media.util.PermissionUtils.checkPermissionAccessMediaLocation;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManageMedia;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManager;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVideo;
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
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.concurrent.TimeoutException;

/**
 * We already have solid coverage of this logic in {@code CtsProviderTestCases},
 * but the coverage system currently doesn't measure that, so we add the bare
 * minimum local testing here to convince the tooling that it's covered.
 */
@RunWith(AndroidJUnit4.class)
public class PermissionActivityTest {
    private static final String TEST_APP_PACKAGE_NAME =
            "com.android.providers.media.testapp.permission";
    private static final String TEST_APP_33_PACKAGE_NAME =
            "com.android.providers.media.testapp.permissionmedia";

    private static final String OP_ACCESS_MEDIA_LOCATION =
            AppOpsManager.permissionToOp(ACCESS_MEDIA_LOCATION);
    private static final String OP_MANAGE_MEDIA =
            AppOpsManager.permissionToOp(MANAGE_MEDIA);
    private static final String OP_MANAGE_EXTERNAL_STORAGE =
            AppOpsManager.permissionToOp(MANAGE_EXTERNAL_STORAGE);
    private static final String OP_READ_EXTERNAL_STORAGE =
            AppOpsManager.permissionToOp(READ_EXTERNAL_STORAGE);
    private static final String OP_READ_MEDIA_IMAGES =
            AppOpsManager.permissionToOp(READ_MEDIA_IMAGES);
    private static final String OP_READ_MEDIA_AUDIO =
            AppOpsManager.permissionToOp(READ_MEDIA_AUDIO);
    private static final String OP_READ_MEDIA_VIDEO =
            AppOpsManager.permissionToOp(READ_MEDIA_VIDEO);

    // The list is used to restore the permissions after the test is finished.
    // The default value for these app ops is {@link AppOpsManager#MODE_DEFAULT}
    private static final String[] DEFAULT_OP_PERMISSION_LIST = new String[] {
            OP_MANAGE_EXTERNAL_STORAGE,
            OP_MANAGE_MEDIA
    };

    // The list is used to restore the permissions after the test is finished.
    // The default value for these app ops is {@link AppOpsManager#MODE_ALLOWED}
    private static final String[] ALLOWED_OP_PERMISSION_LIST = new String[] {
            OP_ACCESS_MEDIA_LOCATION,
            OP_READ_EXTERNAL_STORAGE
    };

    private static final long TIMEOUT_MILLIS = 3000;
    private static final long SLEEP_MILLIS = 30;

    private static final int TEST_APP_PID = -1;
    private int mTestAppUid = -1;
    private int mTestAppUid33 = -1;

    @Before
    public void setUp() throws Exception {
        mTestAppUid = getContext().getPackageManager().getPackageUid(TEST_APP_PACKAGE_NAME, 0);
        mTestAppUid33 = getContext().getPackageManager().getPackageUid(TEST_APP_33_PACKAGE_NAME, 0);
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
    public void testLaunchWithNoCallerInfoNoCrash() throws Exception {
        ActivityTestRule<PermissionActivity> activityTestRule = new ActivityTestRule<>(
                PermissionActivity.class, /* initialTouchMode */ true, /* launchActivity */ false);
        activityTestRule.launchActivity(new Intent());
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
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testShouldShowActionDialog_noRESAndMES_true() throws Exception {
        final String[] enableAppOpsList = {OP_MANAGE_MEDIA};
        final String[] disableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_READ_EXTERNAL_STORAGE};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid, enableAppOpsList, disableAppOpsList, TEST_APP_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialog_noRMAAndMES_true_33() throws Exception {
        final String[] enableAppOpsList =
                {OP_MANAGE_MEDIA, OP_READ_MEDIA_IMAGES, OP_READ_MEDIA_VIDEO};
        final String[] disableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_READ_MEDIA_AUDIO};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ true,
                    /* shouldCheckReadImages */ false, /* shouldCheckReadVideo */ false,
                    /* mShouldCheckReadAudioOrReadVideo */ false,
                    /* isTargetSdkAtLeastT */ true)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialog_noRMIAndMES_true_33() throws Exception {
        final String[] enableAppOpsList =
                {OP_MANAGE_MEDIA, OP_READ_MEDIA_AUDIO, OP_READ_MEDIA_VIDEO};
        final String[] disableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_READ_MEDIA_IMAGES};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ false,
                    /* shouldCheckReadImages */ true, /* shouldCheckReadVideo */ false,
                    /* mShouldCheckReadAudioOrReadVideo */ false,
                    /* isTargetSdkAtLeastT */ true)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialog_noRMVAndMES_true_33() throws Exception {
        final String[] enableAppOpsList =
                {OP_MANAGE_MEDIA, OP_READ_MEDIA_AUDIO, OP_READ_MEDIA_IMAGES};
        final String[] disableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_READ_MEDIA_VIDEO};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ false,
                    /* shouldCheckReadImages */ false, /* shouldCheckReadVideo */ true,
                    /* mShouldCheckReadAudioOrReadVideo */ false,
                    /* isTargetSdkAtLeastT */ true)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialogForSubtitle_noRMARMVAndMES_true_33() throws Exception {
        final String[] enableAppOpsList =
                {OP_MANAGE_MEDIA, OP_READ_MEDIA_IMAGES};
        final String[] disableAppOpsList =
                {OP_MANAGE_EXTERNAL_STORAGE, OP_READ_MEDIA_AUDIO, OP_READ_MEDIA_VIDEO};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ false,
                    /* shouldCheckReadImages */ false, /* shouldCheckReadVideo */ false,
                    /* mShouldCheckReadAudioOrReadVideo */ true,
                    /* isTargetSdkAtLeastT */ true)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testShouldShowActionDialog_noMANAGE_MEDIA_true() throws Exception {
        final String[] enableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_READ_EXTERNAL_STORAGE};
        final String[] disableAppOpsList = {OP_MANAGE_MEDIA};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid, enableAppOpsList, disableAppOpsList, TEST_APP_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialog_noMANAGE_MEDIA_true_33() throws Exception {
        final String[] enableAppOpsList = {
            OP_MANAGE_EXTERNAL_STORAGE,
            OP_READ_MEDIA_AUDIO,
            OP_READ_MEDIA_VIDEO,
            OP_READ_MEDIA_IMAGES
        };
        final String[] disableAppOpsList = {OP_MANAGE_MEDIA};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ true,
                    /* shouldCheckReadImages */ true, /* shouldCheckReadVideo */ true,
                    /* mShouldCheckReadAudioOrReadVideo */ true,
                    /* isTargetSdkAtLeastT */ true)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testShouldShowActionDialog_hasMMWithRES_false() throws Exception {
        final String[] enableAppOpsList = {OP_MANAGE_MEDIA, OP_READ_EXTERNAL_STORAGE};
        final String[] disableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid, enableAppOpsList, disableAppOpsList, TEST_APP_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isFalse();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialog_hasMMWithRM_false_33() throws Exception {
        final String[] enableAppOpsList = {
            OP_MANAGE_MEDIA, OP_READ_MEDIA_AUDIO, OP_READ_MEDIA_VIDEO, OP_READ_MEDIA_IMAGES
        };
        final String[] disableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ true,
                    /* shouldCheckReadImages */ true, /* shouldCheckReadVideo */ true,
                    /* mShouldCheckReadAudioOrReadVideo */ true,
                    /* isTargetSdkAtLeastT */ true)).isFalse();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testShouldShowActionDialog_hasMMWithMES_false() throws Exception {
        final String[] enableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_MANAGE_MEDIA};
        final String[] disableAppOpsList = {OP_READ_EXTERNAL_STORAGE};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid, enableAppOpsList, disableAppOpsList, TEST_APP_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_TRASH)).isFalse();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void testShouldShowActionDialog_hasMMWithMES_false_33() throws Exception {
        final String[] enableAppOpsList = {OP_MANAGE_EXTERNAL_STORAGE, OP_MANAGE_MEDIA};
        final String[] disableAppOpsList = {
            OP_READ_MEDIA_AUDIO, OP_READ_MEDIA_VIDEO, OP_READ_MEDIA_IMAGES
        };

        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid33, enableAppOpsList, disableAppOpsList, TEST_APP_33_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid33,
                    TEST_APP_33_PACKAGE_NAME, null, VERB_TRASH,
                    /* shouldCheckMediaPermissions */ true, /* shouldCheckReadAudio */ true,
                    /* shouldCheckReadImages */ true, /* shouldCheckReadVideo */ true,
                    /* mShouldCheckReadAudioOrReadVideo */ true,
                    /* isTargetSdkAtLeastT */ true)).isFalse();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid33);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testShouldShowActionDialog_writeNoACCESS_MEDIA_LOCATION_true() throws Exception {
        final String[] enableAppOpsList =
                {OP_MANAGE_EXTERNAL_STORAGE, OP_MANAGE_MEDIA, OP_READ_EXTERNAL_STORAGE};
        final String[] disableAppOpsList = {OP_ACCESS_MEDIA_LOCATION};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid, enableAppOpsList, disableAppOpsList, TEST_APP_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_WRITE)).isTrue();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid);
            dropShellPermission();
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testShouldShowActionDialog_writeHasACCESS_MEDIA_LOCATION_false() throws Exception {
        final String[] enableAppOpsList = {
                OP_ACCESS_MEDIA_LOCATION,
                OP_MANAGE_EXTERNAL_STORAGE,
                OP_MANAGE_MEDIA,
                OP_READ_EXTERNAL_STORAGE};
        final String[] disableAppOpsList = new String[]{};
        adoptShellPermission(UPDATE_APP_OPS_STATS, MANAGE_APP_OPS_MODES);

        try {
            setupPermissions(
                    mTestAppUid, enableAppOpsList, disableAppOpsList, TEST_APP_PACKAGE_NAME);

            assertThat(shouldShowActionDialog(getContext(), TEST_APP_PID, mTestAppUid,
                    TEST_APP_PACKAGE_NAME, null, VERB_WRITE)).isFalse();
        } finally {
            restoreDefaultAppOpPermissions(mTestAppUid);
            dropShellPermission();
        }
    }

    private static void setupPermissions(int uid, @NonNull String[] enableAppOpsList,
            @NonNull String[] disableAppOpsList, @NonNull String packageName) throws Exception {
        for (String op : enableAppOpsList) {
            modifyAppOp(uid, op, AppOpsManager.MODE_ALLOWED);
        }

        for (String op : disableAppOpsList) {
            modifyAppOp(uid, op, AppOpsManager.MODE_ERRORED);
        }

        pollForAppOpPermissions(
                TEST_APP_PID, packageName, uid, enableAppOpsList, /* hasPermission= */ true);
        pollForAppOpPermissions(
                TEST_APP_PID, packageName, uid, disableAppOpsList, /* hasPermission= */ false);
    }

    private static void restoreDefaultAppOpPermissions(int uid) {
        for (String op : DEFAULT_OP_PERMISSION_LIST) {
            modifyAppOp(uid, op, AppOpsManager.MODE_DEFAULT);
        }

        for (String op : ALLOWED_OP_PERMISSION_LIST) {
            modifyAppOp(uid, op, AppOpsManager.MODE_ALLOWED);
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

    private static void modifyAppOp(int uid, @NonNull String op, int mode) {
        getContext().getSystemService(AppOpsManager.class).setUidMode(op, uid, mode);
    }

    private static void pollForAppOpPermissions(int pid, @NonNull String packageName, int uid,
            String[] opList, boolean hasPermission) throws Exception {
        long current = System.currentTimeMillis();
        final long timeout = current + TIMEOUT_MILLIS;
        final HashSet<String> checkedOpSet = new HashSet<>();

        while (current < timeout && checkedOpSet.size() < opList.length) {
            for (String op : opList) {
                if (!checkedOpSet.contains(op)
                        && checkPermission(op, pid, uid, packageName, hasPermission)) {
                    checkedOpSet.add(op);
                    continue;
                }
            }
            Thread.sleep(SLEEP_MILLIS);
            current = System.currentTimeMillis();
        }

        if (checkedOpSet.size() != opList.length) {
            throw new TimeoutException("Check AppOp permissions with " + uid + " timeout");
        }
    }

    private static boolean checkPermission(@NonNull String op, int pid, int uid,
            @NonNull String packageName, boolean expected) throws Exception {
        final Context context = getContext();

        if (TextUtils.equals(op, OP_READ_EXTERNAL_STORAGE)) {
            return expected == checkPermissionReadStorage(context, pid, uid, packageName,
                    /* attributionTag= */ null);
        } else if (TextUtils.equals(op, OP_READ_MEDIA_IMAGES)) {
            return expected == checkPermissionReadImages(
                context, pid, uid, packageName, /* attributionTag= */ null, /* isAtleastT */ true);
        } else if (TextUtils.equals(op, OP_READ_MEDIA_AUDIO)) {
            return expected == checkPermissionReadAudio(
                context, pid, uid, packageName, /* attributionTag= */ null, /* isAtleastT */ true);
        } else if (TextUtils.equals(op, OP_READ_MEDIA_VIDEO)) {
            return expected == checkPermissionReadVideo(
                context, pid, uid, packageName, /* attributionTag= */ null, /* isAtleastT */ true);
        } else if (TextUtils.equals(op, OP_MANAGE_EXTERNAL_STORAGE)) {
            return expected == checkPermissionManager(context, pid, uid, packageName,
                    /* attributionTag= */ null);
        } else if (TextUtils.equals(op, OP_MANAGE_MEDIA)) {
            return expected == checkPermissionManageMedia(context, pid, uid, packageName,
                    /* attributionTag= */ null);
        } else if (TextUtils.equals(op, OP_ACCESS_MEDIA_LOCATION)) {
            final int targetSdk = context.getPackageManager()
                    .getApplicationInfo(packageName, 0).targetSdkVersion;

            return expected == checkPermissionAccessMediaLocation(context, pid, uid,
                    packageName, /* attributionTag= */ null,
                    targetSdk >= Build.VERSION_CODES.TIRAMISU);
        } else {
            throw new IllegalArgumentException("checkPermission is not supported for op: " + op);
        }
    }
}
