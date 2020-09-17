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

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.MediaStore;

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
                Manifest.permission.UPDATE_APP_OPS_STATS);

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
        Truth.assertThat(sMediaProvider.isOpenAllowedForFuse(
                file.getPath(), sTestUid, true)).isEqualTo(0);

        // We should have no redaction
        Truth.assertThat(sMediaProvider.getRedactionRangesForFuse(
                        file.getPath(), sTestUid, 0)).isEqualTo(new long[0]);

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
    public void test_scanFileForFuse() throws Exception {
        final File file = new File(sTestDir, "test" + System.nanoTime() + ".jpg");
        Truth.assertThat(file.createNewFile()).isTrue();
        sMediaProvider.scanFileForFuse(file.getPath());
    }

    @Test
    public void test_isOpendirAllowedForFuse() throws Exception {
        Truth.assertThat(sMediaProvider.isOpendirAllowedForFuse(
                sTestDir.getPath(), sTestUid, /* forWrite */ false)).isEqualTo(0);
    }

    @Test
    public void test_isDirectoryCreationOrDeletionAllowedForFuse() throws Exception {
        Truth.assertThat(sMediaProvider.isDirectoryCreationOrDeletionAllowedForFuse(
                sTestDir.getPath(), sTestUid, true)).isEqualTo(0);
    }
}
