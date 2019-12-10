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

package com.android.tests.fused;

import static android.os.SystemProperties.getBoolean;
import static android.provider.MediaStore.MediaColumns;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ClipDescription;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.Manifest;
import android.webkit.MimeTypeMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.io.ByteStreams;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.Locale;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.Uninstall;
import com.android.cts.install.lib.TestApp;
import com.android.tests.fused.lib.ReaddirTestHelper;
import static com.android.tests.fused.lib.ReaddirTestHelper.QUERY_TYPE;
import static com.android.tests.fused.lib.ReaddirTestHelper.READDIR_QUERY;
import static com.android.tests.fused.lib.ReaddirTestHelper.CREATE_FILE_QUERY;
import static com.android.tests.fused.lib.ReaddirTestHelper.DELETE_FILE_QUERY;

@RunWith(AndroidJUnit4.class)
public class FilePathAccessTest {
    static final String TAG = "FilePathAccessTest";
    static final String THIS_PACKAGE_NAME = FilePathAccessTest.class.getPackage().getName();

    static final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();

    static final File DCIM_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_DCIM);
    static final File MUSIC_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_MUSIC);
    static final File MOVIES_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_MOVIES);
    static final File DOWNLOAD_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_DOWNLOADS);
    static final File ANDROID_DATA_DIR = new File(EXTERNAL_STORAGE_DIR, "Android/data");
    static final File ANDROID_MEDIA_DIR = new File(EXTERNAL_STORAGE_DIR, "Android/media");
    static final String TEST_DIRECTORY = "FilePathAccessTestDirectory";

    static final File EXTERNAL_FILES_DIR = getContext().getExternalFilesDir(null);
    static final File EXTERNAL_MEDIA_DIR = getContext().getExternalMediaDirs()[0];

    static final String MUSIC_FILE_NAME = "FilePathAccessTest_file.mp3";
    static final String VIDEO_FILE_NAME = "FilePathAccessTest_file.mp4";
    static final String IMAGE_FILE_NAME = "FilePathAccessTest_file.jpg";
    static final String NONMEDIA_FILE_NAME = "FilePathAccessTest_file.pdf";

    static final String STR_DATA1 = "Just some random text";
    static final String STR_DATA2 = "More arbitrary stuff";

    static final byte[] BYTES_DATA1 = STR_DATA1.getBytes();
    static final byte[] BYTES_DATA2 = STR_DATA2.getBytes();

    static final String FILE_CREATION_ERROR_MESSAGE = "No such file or directory";
    private static final UiAutomation sUiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation();

    private static final TestApp TEST_APP_A  = new TestApp("TestAppA",
            "com.android.tests.fused.testapp.A", 1, false, "TestAppA.apk");
    private static final TestApp TEST_APP_B  = new TestApp("TestAppB",
            "com.android.tests.fused.testapp.B", 1, false, "TestAppB.apk");

    // skips all test cases if FUSE is not active.
    @Before
    public void assumeFuseIsOn() {
        assumeTrue(getBoolean("sys.fuse_snapshot", false));
        EXTERNAL_FILES_DIR.mkdirs();
    }

    /**
     * Test that we enforce certain media types can only be created in certain directories.
     */
    @Test
    public void testTypePathConformity() throws Exception {
        // Only music files can be created in Music
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MUSIC_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MUSIC_DIR, VIDEO_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MUSIC_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        // Only video files can be created in Movies
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MOVIES_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MOVIES_DIR, MUSIC_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MOVIES_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        // Only image and video files can be created in DCIM
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(DCIM_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(DCIM_DIR, MUSIC_FILE_NAME).createNewFile();
        });

        assertCanCreateFile(new File(DCIM_DIR, IMAGE_FILE_NAME));
        assertCanCreateFile(new File(MUSIC_DIR, MUSIC_FILE_NAME));
        assertCanCreateFile(new File(MOVIES_DIR, VIDEO_FILE_NAME));
        assertCanCreateFile(new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME));

        // No file whatsoever can be created in the top level directory
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, MUSIC_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, VIDEO_FILE_NAME).createNewFile();
        });
    }

    /**
     * Test that we can create a file in app's external files directory,
     * and that we can write and read to/from the file.
     */
    @Test
    public void testCreateFileInAppExternalDir() throws Exception {
        final File file = new File(EXTERNAL_FILES_DIR, "text.txt");
        try {
            assertThat(file.createNewFile()).isTrue();
            assertThat(file.delete()).isTrue();
            // Ensure the file is properly deleted and can be created again
            assertThat(file.createNewFile()).isTrue();

            // Write to file
            try (final FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(BYTES_DATA1);
            }

            // Read the same data from file
            assertFileContent(file, BYTES_DATA1);
        } finally {
            file.delete();
        }
    }

    /**
     * Test that we can't create a file in another app's external files directory,
     * and that we'll get the same error regardless of whether the app exists or not.
     */
    @Test
    public void testCreateFileInOtherAppExternalDir() throws Exception {
        // Creating a file in a non existent package dir should return ENOENT, as expected
        final File nonexistentPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "no.such.package"));
        final File file1 = new File(nonexistentPackageFileDir, NONMEDIA_FILE_NAME);
        assertThrows(IOException.class, FILE_CREATION_ERROR_MESSAGE,
                () -> { file1.createNewFile(); });

        // Creating a file in an existent package dir should give the same error string to avoid
        // leaking installed app names, and we know the following directory exists because shell
        // mkdirs it in test setup
        final File shellPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "com.android.shell"));
        final File file2 = new File(shellPackageFileDir, NONMEDIA_FILE_NAME);
        assertThrows(IOException.class, FILE_CREATION_ERROR_MESSAGE,
                () -> { file1.createNewFile(); });
        try {
            // On the other hand, shell can create the file
            executeShellCommand("touch " + file2);
            assertThat(executeShellCommand("ls " + shellPackageFileDir))
                    .contains(NONMEDIA_FILE_NAME);
        } finally {
            executeShellCommand("rm " + file2);
        }
    }

    /**
     * Test that we can contribute media without any permissions.
     */
    @Test
    public void testContributeMediaFile() throws Exception {
        final File imageFile = new File(DCIM_DIR, IMAGE_FILE_NAME);

        ContentResolver cr = getContentResolver();
        final String selection = MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaColumns.DISPLAY_NAME + " = ?";
        final String[] selectionArgs = { Environment.DIRECTORY_DCIM + '/', IMAGE_FILE_NAME };

        try {
            assertThat(imageFile.createNewFile()).isTrue();

            // Ensure that the file was successfully added to the MediaProvider database
            try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    /* projection */new String[] {MediaColumns.OWNER_PACKAGE_NAME},
                    selection, selectionArgs, null)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertThat(c.getString(c.getColumnIndex(MediaColumns.OWNER_PACKAGE_NAME)))
                        .isEqualTo("com.android.tests.fused");
            }

            // Try to write random data to the file
            try (final FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(BYTES_DATA1);
                fos.write(BYTES_DATA2);
            }

            final byte[] expected = (STR_DATA1 + STR_DATA2).getBytes();
            assertFileContent(imageFile, expected);
        } finally {
            imageFile.delete();
        }
        // Ensure that delete makes a call to MediaProvider to remove the file from its database.
        try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                /* projection */new String[] {MediaColumns.OWNER_PACKAGE_NAME},
                selection, selectionArgs, null)) {
            assertThat(c.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCreateAndDeleteEmptyDir() throws Exception {
        // Remove directory in order to create it again
        EXTERNAL_FILES_DIR.delete();

        // Can create own external files dir
        assertThat(EXTERNAL_FILES_DIR.mkdir()).isTrue();

        final File dir1 = new File(EXTERNAL_FILES_DIR, "random_dir");
        // Can create dirs inside it
        assertThat(dir1.mkdir()).isTrue();

        final File dir2 = new File(dir1, "random_dir_inside_random_dir");
        // And create a dir inside the new dir
        assertThat(dir2.mkdir());

        // And can delete them all
        assertThat(dir2.delete()).isTrue();
        assertThat(dir1.delete()).isTrue();
        assertThat(EXTERNAL_FILES_DIR.delete()).isTrue();

        // Can't create external dir for other apps
        final File nonexistentPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "no.such.package"));
        final File shellPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "com.android.shell"));

        assertThat(nonexistentPackageFileDir.mkdir()).isFalse();
        assertThat(shellPackageFileDir.mkdir()).isFalse();
    }

    @Test
    public void testDeleteNonemptyDir() throws Exception {
        // TODO(b/142806973): use multi app infra which will be introduced in ag/9730872 to try and
        // delete directories that have files contributed by other apps
    }

    /**
     * This test relies on the fact that {@link File#list} uses opendir internally, and that it
     * returns {@code null} if opendir fails.
     */
    @Test
    public void testOpendirRestrictions() throws Exception {
        // Opening a non existent package directory should fail, as expected
        final File nonexistentPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "no.such.package"));
        assertThat(nonexistentPackageFileDir.list()).isNull();

        // Opening another package's external directory should fail as well, even if it exists
        final File shellPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "com.android.shell"));
        assertThat(shellPackageFileDir.list()).isNull();

        // We can open our own external files directory
        final String[] filesList = EXTERNAL_FILES_DIR.list();
        assertThat(filesList).isNotNull();
        assertThat(filesList).isEmpty();

        // We can open any public directory in external storage
        assertThat(DCIM_DIR.list()).isNotNull();
        assertThat(DOWNLOAD_DIR.list()).isNotNull();
        assertThat(MOVIES_DIR.list()).isNotNull();
        assertThat(MUSIC_DIR.list()).isNotNull();

        // We can open the root directory of external storage
        final String[] topLevelDirs = EXTERNAL_STORAGE_DIR.list();
        assertThat(topLevelDirs).isNotNull();
        // TODO(b/145287327): This check fails on a device with no visible files.
        // This can be fixed if we display default directories.
        // assertThat(topLevelDirs).isNotEmpty();
    }

    @Test
    public void testLowLevelFileIO() throws Exception {
        String filePath = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME).toString();
        try {
            int createFlags = OsConstants.O_CREAT | OsConstants.O_RDWR;
            int createExclFlags = createFlags | OsConstants.O_EXCL;

            FileDescriptor fd = Os.open(filePath, createExclFlags, OsConstants.S_IRWXU);
            Os.close(fd);
            assertThrows(ErrnoException.class, () -> {
                Os.open(filePath, createExclFlags, OsConstants.S_IRWXU);
            });

            fd = Os.open(filePath, createFlags, OsConstants.S_IRWXU);
            try {
                assertThat(Os.write(fd, ByteBuffer.wrap(BYTES_DATA1)))
                        .isEqualTo(BYTES_DATA1.length);
                assertFileContent(fd, BYTES_DATA1);
            } finally {
                Os.close(fd);
            }
            // should just append the data
            fd = Os.open(filePath, createFlags | OsConstants.O_APPEND, OsConstants.S_IRWXU);
            try {
                assertThat(Os.write(fd, ByteBuffer.wrap(BYTES_DATA2)))
                        .isEqualTo(BYTES_DATA2.length);
                final byte[] expected = (STR_DATA1 + STR_DATA2).getBytes();
                assertFileContent(fd, expected);
            } finally {
                Os.close(fd);
            }
            // should overwrite everything
            fd = Os.open(filePath, createFlags | OsConstants.O_TRUNC, OsConstants.S_IRWXU);
            try {
                final byte[] otherData = "this is different data".getBytes();
                assertThat(Os.write(fd, ByteBuffer.wrap(otherData))).isEqualTo(otherData.length);
                assertFileContent(fd, otherData);
            } finally {
                Os.close(fd);
            }
        } finally {
            new File(filePath).delete();
        }
    }

    /**
     * Test that media files from other packages are only visible to apps with storage permission.
     */
    @Test
    public void testListDirectoriesWithMediaFiles() throws Exception {
        final File dir = new File(DCIM_DIR, TEST_DIRECTORY);
        final File videoFile = new File(dir, VIDEO_FILE_NAME);
        final String videoFileName = videoFile.getName();
        try {
            if (!dir.exists()) {
                assertThat(dir.mkdir()).isTrue();
            }

            // Install TEST_APP_A and create media file in the new directory.
            installApp(TEST_APP_A, false);
            assertThat(createFileFromTestApp(TEST_APP_A, videoFile.getPath())).isTrue();
            // TEST_APP_A should see TEST_DIRECTORY in DCIM and new file in TEST_DIRECTORY.
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, DCIM_DIR.getPath()))
                    .contains(TEST_DIRECTORY);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, dir.getPath()))
                    .containsExactly(videoFileName);

            // Install TEST_APP_B with storage permission.
            installApp(TEST_APP_B, true);
            // TEST_APP_B with storage permission should see TEST_DIRECTORY in DCIM and new file
            // in TEST_DIRECTORY.
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_B, DCIM_DIR.getPath()))
                    .contains(TEST_DIRECTORY);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_B, dir.getPath()))
                    .containsExactly(videoFileName);

            // Revoke storage permission for TEST_APP_B
            revokeReadExternalStorage(TEST_APP_B.getPackageName());
            // TEST_APP_B without storage permission should not see TEST_DIRECTORY in DCIM and new
            // file in new TEST_DIRECTORY.
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_B, DCIM_DIR.getPath()))
                    .doesNotContain(TEST_DIRECTORY);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_B, dir.getPath()))
                    .doesNotContain(videoFileName);
        } finally {
            uninstallApp(TEST_APP_B);
            if(videoFile.exists()) {
                assertThat(deleteFileFromTestApp(TEST_APP_A, videoFile.getPath())).isTrue();
            }
            if (dir.exists()) {
                  // Try deleting the directory. Do we delete directory if app doesn't own all
                  // files in it?
                  dir.delete();
            }
            uninstallApp(TEST_APP_A);
        }
    }

    /**
     * Test that app can not see non-media files created by other packages
     */
    @Test
    public void testListDirectoriesWithNonMediaFiles() throws Exception {
        final File dir = new File(DOWNLOAD_DIR, TEST_DIRECTORY);
        final File pdfFile = new File(dir, NONMEDIA_FILE_NAME);
        final String pdfFileName = pdfFile.getName();
        try {
            if (!dir.exists()) {
                assertThat(dir.mkdir()).isTrue();
            }

            // Install TEST_APP_A and create non media file in the new directory.
            installApp(TEST_APP_A, false);
            assertThat(createFileFromTestApp(TEST_APP_A, pdfFile.getPath())).isTrue();

            // TEST_APP_A should see TEST_DIRECTORY in DOWNLOAD_DIR and new non media file in
            // TEST_DIRECTORY.
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, DOWNLOAD_DIR.getPath()))
                    .contains(TEST_DIRECTORY);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, dir.getPath()))
                    .containsExactly(pdfFileName);

            // Install TEST_APP_B with storage permission.
            installApp(TEST_APP_B, true);
            // TEST_APP_B with storage permission should not see TEST_DIRECTORY in DOWNLOAD_DIR
            // and should not see new non media file in TEST_DIRECTORY.
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_B, DOWNLOAD_DIR.getPath()))
                    .doesNotContain(TEST_DIRECTORY);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_B, dir.getPath()))
                    .doesNotContain(pdfFileName);
        } finally {
            uninstallApp(TEST_APP_B);
            if(pdfFile.exists()) {
                assertThat(deleteFileFromTestApp(TEST_APP_A, pdfFile.getPath())).isTrue();
            }
            if (dir.exists()) {
                  // Try deleting the directory. Do we delete directory if app doesn't own all
                  // files in it?
                  dir.delete();
            }
            uninstallApp(TEST_APP_A);
        }
    }

    /**
     * Test that app can only see its directory in Android/data.
     */
    @Test
    public void testListFilesFromExternalFilesDirectory() throws Exception {
        final String packageName = getContext().getPackageName();
        final File videoFile = new File(EXTERNAL_FILES_DIR, NONMEDIA_FILE_NAME);
        final String videoFileName = videoFile.getName();

        try {
            // Create a file in app's external files directory
            if (!videoFile.exists()) {
                assertThat(videoFile.createNewFile()).isTrue();
            }
            // App should see its directory and directories of shared packages. App should see all
            // files and directories in its external directory.
            assertThat(ReaddirTestHelper.readDirectory(ANDROID_DATA_DIR)).contains(packageName);
            assertThat(ReaddirTestHelper.readDirectory(videoFile.getParentFile()))
                    .containsExactly(videoFileName);

            // Install TEST_APP_A with READ_EXTERNAL_STORAGE permission.
            // TEST_APP_A should not see other app's external files directory.
            installApp(TEST_APP_A, true);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, ANDROID_DATA_DIR.getPath()))
                    .doesNotContain(packageName);
            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, EXTERNAL_FILES_DIR.getPath())).isEmpty();
        } finally {
            assertThat(videoFile.delete()).isTrue();
        }
    }

    /**
     * Test that app can see files and directories in Android/media.
     */
    @Test
    public void testListFilesFromExternalMediaDirectory() throws Exception {
        final String packageName = getContext().getPackageName();
        final File videoFile = new File(EXTERNAL_MEDIA_DIR, VIDEO_FILE_NAME);
        final String videoFileName = videoFile.getName();

        try {
            // Create a file in app's external media directory
            if (!videoFile.exists()) {
                assertThat(videoFile.createNewFile()).isTrue();
            }

            // App should see its directory and other app's external media directories with media
            // files.
            assertThat(ReaddirTestHelper.readDirectory(ANDROID_MEDIA_DIR)).contains(packageName);
            assertThat(ReaddirTestHelper.readDirectory(EXTERNAL_MEDIA_DIR))
                    .containsExactly(videoFileName);

            // Install TEST_APP_A with READ_EXTERNAL_STORAGE permission.
            // TEST_APP_A with storage permission should see other app's external media directory.

            // TODO(b/145757667): Uncomment this when we start indexing Android/media files.
            //  For context, this used to work when we used to scan files after closing them, but
            //  now, since we don't, videoFileName is not indexed by MediaProvider, which means
            //  that Android/media/<pkg-name> is empty and so MediaProvider can't see it.
            //  We also can't use ContentResolver#insert since MediaProvider doesn't allow videos
            //  under primary directory Android.
//            installApp(TEST_APP_A, true);
//            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, ANDROID_MEDIA_DIR.getPath()))
//                    .contains(packageName);
//            assertThat(listDirectoryEntriesFromTestApp(TEST_APP_A, EXTERNAL_MEDIA_DIR.getPath()))
//                    .containsExactly(videoFileName);
        } finally {
            videoFile.delete();
        }
    }

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    private static ContentResolver getContentResolver() {
        return getContext().getContentResolver();
    }

    private static void assertCanCreateFile(File file) throws IOException {
        // If the file somehow managed to survive a previous run, then the test app was uninstalled
        // and MediaProvider will remove our its ownership of the file, so it's not guaranteed that
        // we can create nor delete it.
        if (!file.exists()) {
            assertThat(file.createNewFile()).isTrue();
            assertThat(file.delete()).isTrue();
        } else {
            Log.w(TAG, "Couldn't assertCanCreateFile(" + file + ") because file existed prior to "
                    + "running the test!");
        }
    }

    /**
     * Asserts the entire content of the file equals exactly {@code expectedContent}.
     */
    private static void assertFileContent(File file, byte[] expectedContent) throws IOException {
        try (final FileInputStream fis = new FileInputStream(file)) {
            assertInputStreamContent(fis, expectedContent);
        }
    }

    /**
     * Asserts the entire content of the file equals exactly {@code expectedContent}.
     * <p>Sets {@code fd} to beginning of file first.
     */
    private static void assertFileContent(FileDescriptor fd, byte[] expectedContent)
            throws IOException, ErrnoException {
        Os.lseek(fd, 0, OsConstants.SEEK_SET);
        try (final FileInputStream fis = new FileInputStream(fd)) {
            assertInputStreamContent(fis, expectedContent);
        }
    }

    private static void assertInputStreamContent(InputStream in, byte[] expectedContent)
            throws IOException {
        assertThat(ByteStreams.toByteArray(in)).isEqualTo(expectedContent);
    }

    /**
     * A functional interface representing an operation that takes no arguments,
     * returns no arguments and might throw an {@link Exception} of any kind.
     */
    @FunctionalInterface
    private interface Operation<T extends Exception> {
        /**
         * This is the method that gets called for any object that implements this interface.
         */
        void run() throws T;
    }

    private static <T extends Exception> void assertThrows(Class<T> clazz, Operation<T> r)
            throws Exception {
        assertThrows(clazz, "", r);
    }

    private static <T extends Exception> void assertThrows(Class<T> clazz, String errMsg,
            Operation<T> r) throws Exception {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass()) || !e.getMessage().contains(errMsg)) {
                Log.e(TAG, "Expected " + clazz + " exception with error message: " + errMsg, e);
                throw e;
            }
        }
    }

    private static String executeShellCommand(String cmd) throws Exception {
        try (FileInputStream output = new FileInputStream (sUiAutomation.executeShellCommand(cmd)
                .getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    private void installApp(TestApp testApp, boolean grantStoragePermission)
            throws Exception {

        try {
            final String packageName = testApp.getPackageName();
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);
            if (InstallUtils.getInstalledVersion(packageName) != -1) {
                Uninstall.packages(packageName);
            }
            Install.single(testApp).commit();
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(1);
            if (grantStoragePermission) {
                grantReadExternalStorage(packageName);
            }
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void uninstallApp(TestApp testApp) throws Exception {
        try {
            final String packageName = testApp.getPackageName();
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.DELETE_PACKAGES);

            Uninstall.packages(packageName);
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(-1);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void grantReadExternalStorage(String packageName) throws Exception {
        sUiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
        try {
            sUiAutomation.grantRuntimePermission(packageName,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void revokeReadExternalStorage(String packageName) throws Exception {
        sUiAutomation.adoptShellPermissionIdentity("android.permission.REVOKE_RUNTIME_PERMISSIONS");
        try {
            sUiAutomation.revokeRuntimePermission(packageName,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void forceStopApp(String packageName) throws Exception {
        try {
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.FORCE_STOP_PACKAGES);

            getContext().getSystemService(ActivityManager.class).forceStopPackage(packageName);
            Thread.sleep(1000);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private ArrayList<String> listDirectoryEntriesFromTestApp(TestApp testApp, String dirPath)
            throws Exception {
        return getContentsFromTestApp(testApp, dirPath, READDIR_QUERY);
    }

    private boolean createFileFromTestApp(TestApp testApp, String dirPath) throws Exception {
        return createOrDeleteFileFromTestApp(testApp, dirPath, CREATE_FILE_QUERY);
    }

    private boolean deleteFileFromTestApp(TestApp testApp, String dirPath) throws Exception {
        return createOrDeleteFileFromTestApp(testApp, dirPath, DELETE_FILE_QUERY);
    }

    private void sendIntentToTestApp(TestApp testApp, String dirPath, String actionName,
            BroadcastReceiver broadcastReceiver, CountDownLatch latch) throws Exception {

        final ArrayList<String> appOutputList = new ArrayList<String>();
        final String packageName = testApp.getPackageName();
        forceStopApp(packageName);
        // Register broadcast receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(actionName);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        getContext().registerReceiver(broadcastReceiver, intentFilter);

        // Launch the helper app.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(QUERY_TYPE, actionName);
        intent.putExtra(actionName, dirPath);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        getContext().startActivity(intent);
        latch.await();
        getContext().unregisterReceiver(broadcastReceiver);
    }

    private ArrayList<String> getContentsFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<String> appOutputList = new ArrayList<String>();
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.hasExtra(actionName)) {
                    appOutputList.addAll(intent.getStringArrayListExtra(actionName));
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutputList;
    }

    private boolean createOrDeleteFileFromTestApp(TestApp testApp, String dirPath, String actionName)
            throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] appOutput = new boolean[1];
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.hasExtra(actionName)) {
                    appOutput[0] = intent.getBooleanExtra(actionName, false);
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutput[0];
    }
}
