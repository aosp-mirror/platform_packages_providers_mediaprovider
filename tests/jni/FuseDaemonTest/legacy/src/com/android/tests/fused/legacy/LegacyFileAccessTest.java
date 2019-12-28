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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

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

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long POLLING_SLEEP_MILLIS = 100;

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

    /*
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

    private boolean isPermissionGranted(String perm) {
        return InstrumentationRegistry.getContext().checkCallingOrSelfPermission(perm)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void pollForPermission(String perm, boolean granted) throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (granted == isPermissionGranted(perm)) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for permission " + perm + " to be "
                + (granted ? "granted" : "revoked"));
    }
}
