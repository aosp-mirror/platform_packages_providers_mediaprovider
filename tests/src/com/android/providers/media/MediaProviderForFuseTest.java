/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.providers.media.MediaProvider.DIRECTORY_ACCESS_FOR_READ;
import static com.android.providers.media.MediaProvider.DIRECTORY_ACCESS_FOR_WRITE;
import static com.android.providers.media.MediaProvider.DIRECTORY_ACCESS_FOR_CREATE;
import static com.android.providers.media.MediaProvider.DIRECTORY_ACCESS_FOR_DELETE;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.OsConstants;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import com.google.common.truth.Truth;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;

/**
 * This class is purely here to convince internal code coverage tools that
 * {@code FuseDaemonHostTest} is actually covering all of these methods; the
 * current coverage infrastructure doesn't support host tests yet.
 */
@RunWith(AndroidJUnit4.class)
public class MediaProviderForFuseTest {

    private static Context sIsolatedContext;
    private static ContentResolver sIsolatedResolver;
    private static MediaProvider sMediaProvider;

    private static int sTestUid;
    private static File sTestDir;

    @BeforeClass
    public static void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.UPDATE_APP_OPS_STATS,
                Manifest.permission.INTERACT_ACROSS_USERS);

        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ true);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        sMediaProvider = (MediaProvider) sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY).getLocalContentProvider();

        // Use a random app without any permissions
        sTestUid = context.getPackageManager().getPackageUid(MediaProviderTest.PERMISSIONLESS_APP,
                PackageManager.MATCH_ALL);
        sTestDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        // Some tests delete top-level directories. Try to create DIRECTORY_PICTURES to ensure
        // sTestDir always exists.
        sTestDir.mkdir();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testTypical() throws Exception {
        final File file = new File(sTestDir, "test" + System.nanoTime() + ".jpg");

        // We can create our file
        Truth.assertThat(sMediaProvider.insertFileIfNecessaryForFuse(
                file.getPath(), sTestUid)).isEqualTo(0);
        Truth.assertThat(Arrays.asList(sMediaProvider.getFilesInDirectoryForFuse(
                sTestDir.getPath(), sTestUid))).contains(file.getName());

        // Touch on disk so we can rename below
        file.createNewFile();

        // We can write our file
        FileOpenResult result = sMediaProvider.onFileOpenForFuse(
                file.getPath(),
                file.getPath(),
                sTestUid,
                0 /* tid */, 0 /* transforms_reason */,
                true /* forWrite */, false /* redact */, false /* transcode_metrics */);
        Truth.assertThat(result.status).isEqualTo(0);
        Truth.assertThat(result.redactionRanges).isEqualTo(new long[0]);

        // We can rename our file
        final File renamed = new File(sTestDir, "renamed" + System.nanoTime() + ".jpg");
        Truth.assertThat(sMediaProvider.renameForFuse(
                file.getPath(), renamed.getPath(), sTestUid)).isEqualTo(0);
        Truth.assertThat(Arrays.asList(sMediaProvider.getFilesInDirectoryForFuse(
                sTestDir.getPath(), sTestUid))).doesNotContain(file.getName());
        Truth.assertThat(Arrays.asList(sMediaProvider.getFilesInDirectoryForFuse(
                sTestDir.getPath(), sTestUid))).contains(renamed.getName());

        // And we can delete it
        Truth.assertThat(sMediaProvider.deleteFileForFuse(
                renamed.getPath(), sTestUid)).isEqualTo(0);
        Truth.assertThat(Arrays.asList(sMediaProvider.getFilesInDirectoryForFuse(
                sTestDir.getPath(), sTestUid))).doesNotContain(renamed.getName());
    }

    @Test
    public void testRenameDirectory() throws Exception {
        sTestDir = new File(sTestDir, "subdir" + System.nanoTime());
        sTestDir.mkdirs();

        // Create test directory and file
        final File file = new File(sTestDir, "test" + System.nanoTime() + ".jpg");
        Truth.assertThat(sMediaProvider.insertFileIfNecessaryForFuse(
                file.getPath(), sTestUid)).isEqualTo(0);
        Truth.assertThat(file.createNewFile()).isTrue();

        // Rename directory should bring along files
        final File renamed = new File(sTestDir.getParentFile(), "renamed" + System.nanoTime());
        Truth.assertThat(sMediaProvider.renameForFuse(
                sTestDir.getPath(), renamed.getPath(), sTestUid)).isEqualTo(0);
        Truth.assertThat(Arrays.asList(sMediaProvider.getFilesInDirectoryForFuse(
                renamed.getPath(), sTestUid))).contains(file.getName());
    }

    @Test
    public void test_isDirAccessAllowedForFuse() throws Exception {
        //verify can create and write but not delete top-level default folder
        final File topLevelDefaultDir = Environment.buildExternalStoragePublicDirs(
                Environment.DIRECTORY_PICTURES)[0];
        final String topLevelDefaultDirPath = topLevelDefaultDir.getPath();
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_READ)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_CREATE)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_WRITE)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_DELETE)).isEqualTo(
                OsConstants.EACCES);

        //verify cannot create or write top-level non-default folder, but can read it
        final File topLevelNonDefaultDir = Environment.buildExternalStoragePublicDirs(
                "non-default-dir")[0];
        final String topLevelNonDefaultDirPath = topLevelNonDefaultDir.getPath();
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_READ)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_CREATE)).isEqualTo(
                OsConstants.EACCES);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_WRITE)).isEqualTo(OsConstants.EACCES);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                topLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_DELETE)).isEqualTo(OsConstants.EACCES);

        //verify can read, create, write and delete random non-top-level folder
        final File lowerLevelNonDefaultDir = new File(topLevelDefaultDir,
                "subdir" + System.nanoTime());
        lowerLevelNonDefaultDir.mkdirs();
        final String lowerLevelNonDefaultDirPath = lowerLevelNonDefaultDir.getPath();
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                lowerLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_READ)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                lowerLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_CREATE)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                lowerLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_WRITE)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                lowerLevelNonDefaultDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_DELETE)).isEqualTo(0);

        //verify cannot update outside /storage folder
        final File rootDir = new File("/myfolder");
        final String rootDirPath = rootDir.getPath();
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                rootDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_READ)).isEqualTo(0);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                rootDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_CREATE)).isEqualTo(OsConstants.EPERM);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                rootDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_WRITE)).isEqualTo(OsConstants.EPERM);
        Truth.assertThat(sMediaProvider.isDirAccessAllowedForFuse(
                rootDirPath, sTestUid,
                DIRECTORY_ACCESS_FOR_DELETE)).isEqualTo(OsConstants.EPERM);

    }
}
