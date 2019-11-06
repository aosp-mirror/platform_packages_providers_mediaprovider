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
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class FilePathAccessTest {
    static final String TAG = "FilePathAccessTest";

    static final File EXTERNALE_STORAGE_DIR = Environment.getExternalStorageDirectory();

    static final File DCIM_DIR = new File(EXTERNALE_STORAGE_DIR, Environment.DIRECTORY_DCIM);
    static final File MUSIC_DIR = new File(EXTERNALE_STORAGE_DIR, Environment.DIRECTORY_MUSIC);
    static final File MOVIES_DIR = new File(EXTERNALE_STORAGE_DIR, Environment.DIRECTORY_MOVIES);
    static final File DOWNLOAD_DIR = new File(EXTERNALE_STORAGE_DIR,
            Environment.DIRECTORY_DOWNLOADS);

    static final File EXTERNAL_FILES_DIR = getContext().getExternalFilesDir(null);

    static final String MUSIC_FILE_NAME = "FilePathAccessTest_file.mp3";
    static final String VIDEO_FILE_NAME = "FilePathAccessTest_file.mp4";
    static final String IMAGE_FILE_NAME = "FilePathAccessTest_file.jpg";
    static final String NONMEDIA_FILE_NAME = "FilePathAccessTest_file.pdf";

    // skips all test cases if FUSE is not active.
    @Before
    public void assumeFuseIsOn() {
        assumeTrue(getBoolean("sys.fuse_snapshot", false));
    }

    /**
     * Test that we enforce certain media types can only be created in certain directories.
     */
    @Test
    public void testTypePathConformity() throws Exception {
        // Only music files can be created in Music
        assertThrows(IOException.class, () -> {
            new File(MUSIC_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, () -> {
            new File(MUSIC_DIR, VIDEO_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, () -> {
            new File(MUSIC_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        // Only video files can be created in Movies
        assertThrows(IOException.class, () -> {
            new File(MOVIES_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, () -> {
            new File(MOVIES_DIR, MUSIC_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, () -> {
            new File(MOVIES_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        // Only image and video files can be created in DCIM
        assertThrows(IOException.class, () -> {
            new File(DCIM_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, () -> {
            new File(DCIM_DIR, MUSIC_FILE_NAME).createNewFile();
        });

        assertCanCreateFile(new File(DCIM_DIR, IMAGE_FILE_NAME));
        assertCanCreateFile(new File(MUSIC_DIR, MUSIC_FILE_NAME));
        assertCanCreateFile(new File(MOVIES_DIR, VIDEO_FILE_NAME));
        assertCanCreateFile(new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME));
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

            final byte data1[] = "Just some random text".getBytes();
            final byte data2[] = "More arbitrary stuff".getBytes();

            // Write to file
            try (final FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data1);
                fos.write(data2);
            }

            // Read the same data from file
            try (final FileInputStream fis = new FileInputStream(file)) {
                final byte readData[] = new byte[data1.length + data2.length];
                assertThat(fis.read(readData)).isEqualTo(readData.length);
                for (int i = 0; i < data1.length; ++i) {
                    assertThat(readData[i]).isEqualTo(data1[i]);
                }
                for (int i = 0; i < data2.length; ++i) {
                    assertThat(readData[data1.length + i]).isEqualTo(data2[i]);
                }
            }
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
        // We're cheating here and using /sdcard/Android/data because there's no legal way to
        // access other app's external files dir.
        final String externalDir = EXTERNALE_STORAGE_DIR.toString() + "/Android/data/";
        // This package may not readily exist in an AOSP build - it doesn't matter since we should
        // get the same error ;)
        final String otherPackageName = "com.google.android.apps.maps";
        final File file1 = new File(externalDir + otherPackageName + '/' + NONMEDIA_FILE_NAME);
        try {
            file1.createNewFile();
            fail("Creating file expected to fail!");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("No such file or directory");
        }

        // Ensure that the app would get the same error regardless of whether the other
        // app's external directory exists or not
        final String nonexistentOtherPackageName = "no.such.package";
        final File file2 = new File(externalDir + nonexistentOtherPackageName + '/'
                + NONMEDIA_FILE_NAME);
        try {
            file2.createNewFile();
            fail("Creating file expected to fail!");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("No such file or directory");
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

            final byte data1[] = "Just some random text".getBytes();
            final byte data2[] = "More arbitrary stuff".getBytes();

            // Try to write random data to the file
            try (final FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(data1);
                fos.write(data2);
            }
            // Closing the file after writing will trigger a MediaScanner scan that updates the
            // file's entry in MediaProvider's database

            // When FileOutputStream is closed, a MediaScanner scan is triggered on the JNI thread.
            // To avoid race conditions with the query check, we add another JNI task that will be
            // synchronized on the same JNI thread.
            try (final FileInputStream fis = new FileInputStream(imageFile)) {
                final byte readData[] = new byte[data1.length + data2.length];
                assertThat(fis.read(readData)).isEqualTo(readData.length);
                for (int i = 0; i < data1.length; ++i)
                    assertThat(readData[i]).isEqualTo(data1[i]);
                for (int i = 0; i < data2.length; ++i)
                    assertThat(readData[data1.length + i]).isEqualTo(data2[i]);
            }

            // Ensure that the scan was completed and the file's size was updated.
            try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    /* projection */new String[] {MediaColumns.SIZE},
                    selection, selectionArgs, null)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertThat(c.getInt(c.getColumnIndex(MediaColumns.SIZE)))
                        .isEqualTo(data1.length + data2.length);
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
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass())) {
                throw e;
            }
        }
    }

}
