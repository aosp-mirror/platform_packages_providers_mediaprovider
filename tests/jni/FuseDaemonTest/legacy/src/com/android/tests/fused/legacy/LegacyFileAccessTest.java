/*
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

package com.android.tests.fused.legacy;

import static com.android.tests.fused.lib.TestUtils.createFileAs;


import static com.android.tests.fused.lib.TestUtils.deleteFileAsNoThrow;
import static com.android.tests.fused.lib.TestUtils.getFileOwnerPackageFromDatabase;
import static com.android.tests.fused.lib.TestUtils.getFileRowIdFromDatabase;
import static com.android.tests.fused.lib.TestUtils.installApp;
import static com.android.tests.fused.lib.TestUtils.listAs;
import static com.android.tests.fused.lib.TestUtils.pollForExternalStorageState;
import static com.android.tests.fused.lib.TestUtils.uninstallApp;

import static com.android.tests.fused.lib.TestUtils.pollForPermission;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;
import com.android.tests.fused.lib.ReaddirTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;

/**
 * Test app targeting Q and requesting legacy storage - tests legacy file path access.
 * Designed to be run by LegacyAccessHostTest.
 *
 * <p> Test cases that assume we have WRITE_EXTERNAL_STORAGE only are appended with hasW,
 * those that assume we have READ_EXTERNAL_STORAGE only are appended with hasR, those who assume we
 * have both are appended with hasRW.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyFileAccessTest {

    private static final String TAG = "LegacyFileAccessTest";
    static final String THIS_PACKAGE_NAME = InstrumentationRegistry.getContext().getPackageName();

    static final String VIDEO_FILE_NAME = "LegacyAccessTest_file.mp4";
    static final String NONMEDIA_FILE_NAME = "LegacyAccessTest_file.pdf";

    private static final TestApp TEST_APP_A  = new TestApp("TestAppA",
            "com.android.tests.fused.testapp.A", 1, false, "TestAppA.apk");

    @Before
    public void setUp() throws Exception {
        pollForExternalStorageState();
    }

    /**
     * Tests that legacy apps bypass the type-path conformity restrictions imposed by MediaProvider.
     * <p> Assumes we have WRITE_EXTERNAL_STORAGE.
     */
    @Test
    public void testCreateFilesInRandomPlaces_hasW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ false);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);
        // Can create file under root dir
        File file = new File(Environment.getExternalStorageDirectory(), "LegacyFileAccessTest.txt");
        assertCanCreateFile(file);

        // Can create music file under DCIM
        file = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DCIM + "/LegacyFileAccessTest.mp3");
        assertCanCreateFile(file);

        // Can create random file under external files dir
        file = new File(InstrumentationRegistry.getContext().getExternalFilesDir(null),
                "LegacyFileAccessTest");
        assertCanCreateFile(file);

        // However, even legacy apps can't create files under other app's directories
        final File otherAppDir = new File(Environment.getExternalStorageDirectory(),
                "Android/data/com.android.shell");
        file = new File(otherAppDir, "LegacyFileAccessTest.txt");

        // otherAppDir was already created by the host test
        try {
            file.createNewFile();
            fail("File creation expected to fail: " + file);
        } catch (IOException expected) {
        }
    }

    /**
     * Tests that legacy apps bypass dir creation/deletion restrictions imposed by MediaProvider.
     * <p> Assumes we have WRITE_EXTERNAL_STORAGE.
     */
    @Test
    public void testMkdirInRandomPlaces_hasW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ false);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);
        // Can create a top-level direcotry
        final File topLevelDir = new File(Environment.getExternalStorageDirectory(),
                "LegacyFileAccessTest");
        assertCanCreateDir(topLevelDir);

        final File otherAppDir = new File(Environment.getExternalStorageDirectory(),
                "Android/data/com.android.shell");

        // However, even legacy apps can't create dirs under other app's directories
        final File subDir = new File(otherAppDir, "LegacyFileAccessTest");
        // otherAppDir was already created by the host test
        assertThat(subDir.mkdir()).isFalse();

        // Try to list a directory and fail because it requires READ permission
        assertThat(new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_MUSIC).list()).isNull();
    }

    /**
     * Tests that an app can't access external storage without permissions.
     */
    @Test
    public void testCantAccessExternalStorage() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ false);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ false);
        // Can't create file under root dir
        File file = new File(Environment.getExternalStorageDirectory(),
                "LegacyFileAccessTest.txt");
        try {
            file.createNewFile();
            fail("File creation expected to fail: " + file);
        } catch (IOException expected) {
        }

        // Can't create music file under /MUSIC
        file = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_MUSIC + "/LegacyFileAccessTest.mp3");
        try {
            file.createNewFile();
            fail("File creation expected to fail: " + file);
        } catch (IOException expected) {
        }

        // Can't create a top-level direcotry
        final File topLevelDir = new File(Environment.getExternalStorageDirectory(),
                "LegacyFileAccessTest");
        assertThat(topLevelDir.mkdir()).isFalse();

        // Can't read existing file
        file = new File(Environment.getExternalStorageDirectory(), "LegacyAccessHostTest_shell");
        try {
            Os.open(file.getPath(), OsConstants.O_RDONLY, /*mode*/ 0);
            fail("Opening file for read expected to fail: " + file);
        } catch (ErrnoException expected) {
        }

        // Can't delete file
        assertThat(new File(Environment.getExternalStorageDirectory(),
                "LegacyAccessHostTest_shell").delete()).isFalse();

        // try to list a directory and fail
        assertThat(new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_MUSIC).list()).isNull();
        assertThat(Environment.getExternalStorageDirectory().list()).isNull();

        // However, even without permissions, we can access our own external dir
        file = new File(InstrumentationRegistry.getContext().getExternalFilesDir(null),
                "LegacyFileAccessTest");
        try {
            assertThat(file.createNewFile()).isTrue();
            assertThat(Arrays.asList(file.getParentFile().list()))
                    .containsExactly("LegacyFileAccessTest");
        } finally {
            file.delete();
        }

        // we can access our own external media directory without permissions.
        file = new File(InstrumentationRegistry.getContext().getExternalMediaDirs()[0],
                "LegacyFileAccessTest");
        try {
            assertThat(file.createNewFile()).isTrue();
            assertThat(Arrays.asList(file.getParentFile().list()))
                    .containsExactly("LegacyFileAccessTest");
        } finally {
            file.delete();
        }
    }

    // test read storage permission
    @Test
    public void testReadOnlyExternalStorage_hasR() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ false);
        // can list directory content
        assertThat(new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_MUSIC).list()).isNotNull();

        // can open file for read
        FileDescriptor fd = null;
        try {
            fd = Os.open(new File(Environment.getExternalStorageDirectory(),
                    "LegacyAccessHostTest_shell").getPath(), OsConstants.O_RDONLY, /*mode*/ 0);
        } finally {
            if (fd != null) {
                Os.close(fd);
            }
        }

        // try to write a file and fail
        File file = new File(Environment.getExternalStorageDirectory(),
                "LegacyAccessHostTest_shell");
        try {
            Os.open(file.getPath(), OsConstants.O_WRONLY, /*mode*/ 0);
            fail("Opening file for write expected to fail: " + file);
        } catch (ErrnoException expected) {
        }

        // try to create file and fail, because it requires WRITE
        file = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_MUSIC + "/LegacyFileAccessTest.mp3");
        try {
            file.createNewFile();
            fail("Creating file expected to fail: " + file);
        } catch (IOException expected) {
        }

        // try to mkdir and fail, because it requires WRITE
        assertThat(new File(Environment.getExternalStorageDirectory(), "/LegacyFileAccessTest")
                .mkdir()).isFalse();
    }

    /**
     * Test that legacy app with storage permission can list all files
     */
    @Test
    public void testListFiles_hasR() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ false);

        // can list a non-media file created by other package.
        assertThat(Arrays.asList(Environment.getExternalStorageDirectory().list()))
                .contains("LegacyAccessHostTest_shell");
    }

    /**
     * Test that rename for legacy app with WRITE_EXTERNAL_STORAGE permission bypasses rename
     * restrictions imposed by MediaProvider
     */
    @Test
    public void testCanRename_hasRW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);

        final File musicFile1 = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DCIM + "/LegacyFileAccessTest.mp3");
        final File musicFile2 = new File(Environment.getExternalStorageDirectory(),
                "/LegacyFileAccessTest.mp3");
        final File musicFile3 = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_MOVIES + "/LegacyFileAccessTest.mp3");
        final File nonMediaDir1 = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DCIM + "/LegacyFileAccessTest");
        final File nonMediaDir2 = new File(Environment.getExternalStorageDirectory(),
                "LegacyFileAccessTest");
        final File pdfFile1 = new File(nonMediaDir1, "LegacyFileAccessTest.pdf");
        final File pdfFile2 = new File(nonMediaDir2, "LegacyFileAccessTest.pdf");
        try {
            // can rename a file to root directory.
            assertThat(musicFile1.createNewFile()).isTrue();
            assertCanRename(musicFile1, musicFile2);

            // can rename a music file to Movies directory.
            assertCanRename(musicFile2, musicFile3);

            assertThat(nonMediaDir1.mkdir()).isTrue();
            assertThat(pdfFile1.createNewFile()).isTrue();
            // can rename directory to root directory.
            assertCanRename(nonMediaDir1, nonMediaDir2);
            assertThat(pdfFile2.exists()).isTrue();
        } finally {
            musicFile1.delete();
            musicFile2.delete();
            musicFile3.delete();

            pdfFile1.delete();
            pdfFile2.delete();
            nonMediaDir1.delete();
            nonMediaDir2.delete();
        }
    }

    /**
     * Test that legacy app with only READ_EXTERNAL_STORAGE can only rename files in app external
     * directories.
     */
    @Test
    public void testCantRename_hasR() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ false);

        final File shellFile1 = new File(Environment.getExternalStorageDirectory(),
                "LegacyAccessHostTest_shell");
        final File shellFile2 = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS + "/LegacyFileAccessTest_shell");
        final File mediaFile1 = new File(InstrumentationRegistry.getContext().
                getExternalMediaDirs()[0], "LegacyFileAccessTest1");
        final File mediaFile2 = new File(InstrumentationRegistry.getContext().
                getExternalMediaDirs()[0], "LegacyFileAccessTest2");
        try {
            // app can't rename shell file.
            assertThat(shellFile1.renameTo(shellFile2)).isFalse();
            // app can't move shell file to its media directory.
            assertThat(mediaFile1.renameTo(shellFile1)).isFalse();
            // However, even without permissions, app can rename files in its own external media
            // directory.
            assertThat(mediaFile1.createNewFile()).isTrue();
            assertCanRename(mediaFile1, mediaFile2);
        } finally {
            mediaFile1.delete();
            mediaFile2.delete();
        }
    }

    /**
     * Test that legacy app with no storage permission can only rename files in app external
     * directories.
     */
    @Test
    public void testCantRename_noStoragePermission() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ false);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ false);

        final File shellFile1 = new File(Environment.getExternalStorageDirectory(),
                "LegacyAccessHostTest_shell");
        final File shellFile2 = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS + "/LegacyFileAccessTest_shell");
        final File mediaFile1 = new File(InstrumentationRegistry.getContext().
                getExternalMediaDirs()[0], "LegacyFileAccessTest1");
        final File mediaFile2 = new File(InstrumentationRegistry.getContext().
                getExternalMediaDirs()[0], "LegacyFileAccessTest2");
        try {
            // app can't rename shell file.
            assertThat(shellFile1.renameTo(shellFile2)).isFalse();
            // app can't move shell file to its media directory.
            assertThat(mediaFile1.renameTo(shellFile1)).isFalse();
            // However, even without permissions, app can rename files in its own external media
            // directory.
            assertThat(mediaFile1.createNewFile()).isTrue();
            assertCanRename(mediaFile1, mediaFile2);
        } finally {
            mediaFile1.delete();
            mediaFile2.delete();
        }
    }

    /**
     * Test that legacy app with WRITE_EXTERNAL_STORAGE can delete all files, and corresponding
     * database entry is deleted on deleting the file.
     */
    @Test
    public void testCanDeleteAllFiles_hasRW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);

        final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();
        final File videoFile = new File(EXTERNAL_STORAGE_DIR, VIDEO_FILE_NAME);
        final File otherAppPdfFile = new File(EXTERNAL_STORAGE_DIR,
                Environment.DIRECTORY_DOWNLOADS + "/" + NONMEDIA_FILE_NAME);

        try {
            assertThat(videoFile.createNewFile()).isTrue();
            assertThat(ReaddirTestHelper.readDirectory(EXTERNAL_STORAGE_DIR))
                    .contains(VIDEO_FILE_NAME);

            assertThat(getFileRowIdFromDatabase(videoFile)).isNotEqualTo(-1);
            // Legacy app can delete its own file.
            assertThat(videoFile.delete()).isTrue();
            // Deleting the file will remove videoFile entry from database.
            assertThat(getFileRowIdFromDatabase(videoFile)).isEqualTo(-1);

            installApp(TEST_APP_A, false);
            assertThat(createFileAs(TEST_APP_A, otherAppPdfFile.getAbsolutePath())).isTrue();
            assertThat(getFileRowIdFromDatabase(otherAppPdfFile)).isNotEqualTo(-1);
            // Legacy app with write permission can delete the pdfFile owned by TestApp.
            assertThat(otherAppPdfFile.delete()).isTrue();
            // Deleting the pdfFile also removes pdfFile from database.
            assertThat(getFileRowIdFromDatabase(otherAppPdfFile)).isEqualTo(-1);
        } finally {
            deleteFileAsNoThrow(TEST_APP_A, otherAppPdfFile.getAbsolutePath());
            uninstallApp(TEST_APP_A);
            videoFile.delete();
        }
    }

    /**
     * Test that file created by legacy app is inserted to MediaProvider database. And,
     * MediaColumns.OWNER_PACKAGE_NAME is updated with calling package's name.
     */
    @Test
    public void testLegacyAppCanOwnAFile_hasW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);

        final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();
        final File videoFile = new File(EXTERNAL_STORAGE_DIR, VIDEO_FILE_NAME);
        try {
            assertThat(videoFile.createNewFile()).isTrue();

            installApp(TEST_APP_A, true);
            // videoFile is inserted to database, non-legacy app can see this videoFile on 'ls'.
            assertThat(listAs(TEST_APP_A, EXTERNAL_STORAGE_DIR.getAbsolutePath()))
                    .contains(VIDEO_FILE_NAME);

            // videoFile is in database, row ID for videoFile can not be -1.
            assertNotEquals(-1, getFileRowIdFromDatabase(videoFile));
            assertEquals(THIS_PACKAGE_NAME, getFileOwnerPackageFromDatabase(videoFile));

            assertTrue(videoFile.delete());
            // videoFile is removed from database on delete, hence row ID is -1.
            assertEquals(-1, getFileRowIdFromDatabase(videoFile));
        } finally {
            videoFile.delete();
            uninstallApp(TEST_APP_A);
        }
    }

    private static void assertCanCreateFile(File file) throws IOException {
        if (file.exists()) {
            file.delete();
        }
        try {
            if (!file.createNewFile()) {
                fail("Could not create file: " + file);
            }
        } finally {
            file.delete();
        }
    }

    private static void assertCanCreateDir(File dir) throws IOException {
        if (dir.exists()) {
            if (!dir.delete()) {
                Log.w(TAG, "Can't create dir " + dir + " because it already exists and we can't "
                        + "delete it!");
                return;
            }
        }
        try {
            if (!dir.mkdir()) {
                fail("Could not mkdir: " + dir);
            }
        } finally {
            dir.delete();
        }
    }

    private static void assertCanRename(File oldPath, File newPath) {
        assertThat(oldPath.renameTo(newPath)).isTrue();
        assertThat(oldPath.exists()).isFalse();
        assertThat(newPath.exists()).isTrue();
    }
}
