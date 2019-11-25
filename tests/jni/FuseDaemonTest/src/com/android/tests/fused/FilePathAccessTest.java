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

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

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

    static final File EXTERNAL_FILES_DIR = getContext().getExternalFilesDir(null);

    static final String MUSIC_FILE_NAME = "FilePathAccessTest_file.mp3";
    static final String VIDEO_FILE_NAME = "FilePathAccessTest_file.mp4";
    static final String IMAGE_FILE_NAME = "FilePathAccessTest_file.jpg";
    static final String NONMEDIA_FILE_NAME = "FilePathAccessTest_file.pdf";

    static final String STR_DATA1 = "Just some random text";
    static final String STR_DATA2 = "More arbitrary stuff";

    static final byte[] BYTES_DATA1 = STR_DATA1.getBytes();
    static final byte[] BYTES_DATA2 = STR_DATA2.getBytes();

    static final String FILE_CREATION_ERROR_MESSAGE = "No such file or directory";

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
            if (file.exists()) {
                assertThat(file.delete()).isTrue();
            }
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
            executeShellCommand("rm " + file1);
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
            // Closing the file after writing will trigger a MediaScanner scan that updates the
            // file's entry in MediaProvider's database

            // When FileOutputStream is closed, a MediaScanner scan is triggered on the JNI thread.
            // To avoid race conditions with the query check, we add another JNI task that will be
            // synchronized on the same JNI thread.
            final byte[] expected = (STR_DATA1 + STR_DATA2).getBytes();
            assertFileContent(imageFile, expected);

            // Ensure that the scan was completed and the file's size was updated.
            try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    /* projection */new String[] {MediaColumns.SIZE},
                    selection, selectionArgs, null)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertThat(c.getInt(c.getColumnIndex(MediaColumns.SIZE)))
                        .isEqualTo(BYTES_DATA1.length + BYTES_DATA2.length);
            }
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
        assertThat(topLevelDirs).isNotEmpty();
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
        try (FileInputStream output = new FileInputStream (InstrumentationRegistry
                .getInstrumentation().getUiAutomation()
                .executeShellCommand(cmd).getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }
}
