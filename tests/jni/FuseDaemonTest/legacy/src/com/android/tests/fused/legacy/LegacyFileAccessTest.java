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

import static com.android.tests.fused.lib.TestUtils.BYTES_DATA1;
import static com.android.tests.fused.lib.TestUtils.BYTES_DATA2;
import static com.android.tests.fused.lib.TestUtils.STR_DATA1;
import static com.android.tests.fused.lib.TestUtils.STR_DATA2;
import static com.android.tests.fused.lib.TestUtils.assertCanRenameFile;
import static com.android.tests.fused.lib.TestUtils.assertCanRenameDirectory;
import static com.android.tests.fused.lib.TestUtils.assertCantRenameFile;
import static com.android.tests.fused.lib.TestUtils.assertFileContent;
import static com.android.tests.fused.lib.TestUtils.createFileAs;
import static com.android.tests.fused.lib.TestUtils.deleteFileAsNoThrow;
import static com.android.tests.fused.lib.TestUtils.getContentResolver;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;
import com.android.tests.fused.lib.ReaddirTestHelper;

import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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

    static final String IMAGE_FILE_NAME = "FilePathAccessTest_file.jpg";
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
    @Ignore("Re-enable as part of b/145737191")
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
            assertCanRenameFile(musicFile1, musicFile2);

            // can rename a music file to Movies directory.
            assertCanRenameFile(musicFile2, musicFile3);

            assertThat(nonMediaDir1.mkdir()).isTrue();
            assertThat(pdfFile1.createNewFile()).isTrue();
            // can rename directory to root directory.
            assertCanRenameDirectory(nonMediaDir1, nonMediaDir2, new File[]{pdfFile1},
                    new File[]{pdfFile2});
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
            assertCantRenameFile(shellFile1, shellFile2);
            // app can't move shell file to its media directory.
            assertCantRenameFile(shellFile1, mediaFile1);
            // However, even without permissions, app can rename files in its own external media
            // directory.
            assertThat(mediaFile1.createNewFile()).isTrue();
            assertThat(mediaFile1.renameTo(mediaFile2)).isTrue();
            assertThat(mediaFile2.exists()).isTrue();
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
            assertCantRenameFile(shellFile1, shellFile2);
            // app can't move shell file to its media directory.
            assertCantRenameFile(shellFile1, mediaFile1);
            // However, even without permissions, app can rename files in its own external media
            // directory.
            assertThat(mediaFile1.createNewFile()).isTrue();
            assertThat(mediaFile1.renameTo(mediaFile2)).isTrue();
            assertThat(mediaFile2.exists()).isTrue();
        } finally {
            mediaFile1.delete();
            mediaFile2.delete();
        }
    }

    /**
     * Test that legacy app with WRITE_EXTERNAL_STORAGE can delete all files, and corresponding
     * database entry is deleted on deleting the file.
     */
    @Ignore("Re-enable as part of b/145737191")
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
    @Ignore("Re-enable as part of b/145737191")
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

    /**
     * b/14966134: Test that FuseDaemon doesn't leave stale database entries after create() and
     * rename().
     */
    @Test
    public void testCreateAndRenameDoesntLeaveStaleDBRow_hasRW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);

        final File directoryDCIM = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DCIM);
        final File videoFile = new File(directoryDCIM, VIDEO_FILE_NAME);
        final File renamedVideoFile = new File(directoryDCIM, "Renamed_" + VIDEO_FILE_NAME);
        final ContentResolver cr = getContentResolver();

        try {
            assertThat(videoFile.createNewFile()).isTrue();
            assertThat(videoFile.renameTo(renamedVideoFile)).isTrue();

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DATA, renamedVideoFile.getAbsolutePath());
            // Insert new renamedVideoFile to database
            final Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values, null);
            assertNotNull(uri);

            // Query for all images/videos in the device.
            // This shouldn't list videoFile which was renamed to renamedVideoFile.
            final ArrayList<String> imageAndVideoFiles = getImageAndVideoFilesFromDatabase();
            assertThat(imageAndVideoFiles).contains(renamedVideoFile.getName());
            assertThat(imageAndVideoFiles).doesNotContain(videoFile.getName());
        } finally {
            videoFile.delete();
            renamedVideoFile.delete();
            MediaStore.scanFile(cr, renamedVideoFile);
        }
    }

    /**
     * b/150147690,b/150193381: Test that file rename doesn't delete any existing Uri.
     */
    @Test
    public void testRenameDoesntInvalidateUri_hasRW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);

        final File directoryDCIM = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DCIM);
        final File imageFile = new File(directoryDCIM, IMAGE_FILE_NAME);
        final File temporaryImageFile = new File(directoryDCIM, IMAGE_FILE_NAME + "_.tmp");
        final ContentResolver cr = getContentResolver();

        try {
            assertThat(imageFile.createNewFile()).isTrue();
            try (final FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(BYTES_DATA1);
            }
            // Insert this file to database.
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DATA, imageFile.getAbsolutePath());
            final Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values, null);
            assertNotNull(uri);

            Files.copy(imageFile, temporaryImageFile);
            // Write more bytes to temporaryImageFile
            try (final FileOutputStream fos = new FileOutputStream(temporaryImageFile, true)) {
                fos.write(BYTES_DATA2);
            }
            assertThat(imageFile.delete()).isTrue();
            temporaryImageFile.renameTo(imageFile);

            // Previous uri of imageFile is unaltered after delete & rename.
            final Uri scannedUri = MediaStore.scanFile(cr, imageFile);
            assertThat(scannedUri.getLastPathSegment()).isEqualTo(uri.getLastPathSegment());

            final byte[] expected = (STR_DATA1 + STR_DATA2).getBytes();
            assertFileContent(imageFile, expected);
        } finally {
            imageFile.delete();
            temporaryImageFile.delete();
            MediaStore.scanFile(cr, imageFile);
        }
    }

    /**
     * b/150498564,b/150274099: Test that apps can rename files that are not in database.
     */
    @Test
    public void testCanRenameAFileWithNoDBRow_hasRW() throws Exception {
        pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, /*granted*/ true);
        pollForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, /*granted*/ true);

        final File directoryDCIM = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DCIM);
        final File directoryNoMedia = new File(directoryDCIM, ".directoryNoMedia");
        final File imageInNoMediaDir = new File(directoryNoMedia, IMAGE_FILE_NAME);
        final File renamedImageInDCIM = new File(directoryDCIM, IMAGE_FILE_NAME);
        final File noMediaFile = new File(directoryNoMedia, ".nomedia");
        final ContentResolver cr = getContentResolver();

        try {
            if (!directoryNoMedia.exists()) {
                assertThat(directoryNoMedia.mkdirs()).isTrue();
            }
            assertThat(noMediaFile.createNewFile()).isTrue();
            assertThat(imageInNoMediaDir.createNewFile()).isTrue();
            // Remove imageInNoMediaDir from database.
            MediaStore.scanFile(cr, directoryNoMedia);

            // Query for all images/videos in the device. This shouldn't list imageInNoMediaDir
            assertThat(getImageAndVideoFilesFromDatabase())
                    .doesNotContain(imageInNoMediaDir.getName());

            // Rename shouldn't throw error even if imageInNoMediaDir is not in database.
            assertThat(imageInNoMediaDir.renameTo(renamedImageInDCIM)).isTrue();
            // We can insert renamedImageInDCIM to database
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DATA, renamedImageInDCIM.getAbsolutePath());
            final Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values, null);
            assertNotNull(uri);
        } finally {
            imageInNoMediaDir.delete();
            renamedImageInDCIM.delete();
            MediaStore.scanFile(cr, renamedImageInDCIM);
            noMediaFile.delete();
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

    /**
     * Queries {@link ContentResolver} for all image and video files, returns display name of
     * corresponding files.
     */
    private static ArrayList<String> getImageAndVideoFilesFromDatabase() {
        ArrayList<String> mediaFiles = new ArrayList<>();
        final String selection = "is_pending = 0 AND is_trashed = 0 AND "
                + "(media_type = ? OR media_type = ?)";
        final String[] selectionArgs = new String[] {
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)};

        try (Cursor c = getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                /* projection */ new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                selection, selectionArgs, null)) {
            while (c.moveToNext()) {
                mediaFiles.add(c.getString(0));
            }
        }
        return mediaFiles;
    }
}
