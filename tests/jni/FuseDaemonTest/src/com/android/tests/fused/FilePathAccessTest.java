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

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.tests.fused.lib.RedactionTestHelper.assertExifMetadataMatch;
import static com.android.tests.fused.lib.RedactionTestHelper.assertExifMetadataMismatch;
import static com.android.tests.fused.lib.RedactionTestHelper.getExifMetadata;
import static com.android.tests.fused.lib.RedactionTestHelper.getExifMetadataFromRawResource;
import static com.android.tests.fused.lib.TestUtils.assertThrows;
import static com.android.tests.fused.lib.TestUtils.createFileAs;
import static com.android.tests.fused.lib.TestUtils.deleteFileAs;
import static com.android.tests.fused.lib.TestUtils.executeShellCommand;
import static com.android.tests.fused.lib.TestUtils.installApp;
import static com.android.tests.fused.lib.TestUtils.listAs;
import static com.android.tests.fused.lib.TestUtils.readExifMetadataFromTestApp;
import static com.android.tests.fused.lib.TestUtils.revokeReadExternalStorage;
import static com.android.tests.fused.lib.TestUtils.uninstallApp;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.TestApp;
import com.android.tests.fused.lib.ReaddirTestHelper;
import com.android.tests.fused.lib.ReaddirTestHelper;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FilePathAccessTest {
    static final String TAG = "FilePathAccessTest";
    static final String THIS_PACKAGE_NAME = getContext().getPackageName();

    static final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();

    static final File DCIM_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_DCIM);
    static final File PICTURES_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_PICTURES);
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

    private static final TestApp TEST_APP_A  = new TestApp("TestAppA",
            "com.android.tests.fused.testapp.A", 1, false, "TestAppA.apk");
    private static final TestApp TEST_APP_B  = new TestApp("TestAppB",
            "com.android.tests.fused.testapp.B", 1, false, "TestAppB.apk");

    // skips all test cases if FUSE is not active.
    @Before
    public void assumeFuseIsOn() {
        assumeTrue(getBoolean("persist.sys.fuse", false));
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
        assertThat(dir2.mkdir()).isTrue();

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
            assertThat(createFileAs(TEST_APP_A, videoFile.getPath())).isTrue();
            // TEST_APP_A should see TEST_DIRECTORY in DCIM and new file in TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_A, DCIM_DIR.getPath())).contains(TEST_DIRECTORY);
            assertThat(listAs(TEST_APP_A, dir.getPath())).containsExactly(videoFileName);

            // Install TEST_APP_B with storage permission.
            installApp(TEST_APP_B, true);
            // TEST_APP_B with storage permission should see TEST_DIRECTORY in DCIM and new file
            // in TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_B, DCIM_DIR.getPath())).contains(TEST_DIRECTORY);
            assertThat(listAs(TEST_APP_B, dir.getPath())).containsExactly(videoFileName);

            // Revoke storage permission for TEST_APP_B
            revokeReadExternalStorage(TEST_APP_B.getPackageName());
            // TEST_APP_B without storage permission should see TEST_DIRECTORY in DCIM and should
            // not see new file in new TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_B, DCIM_DIR.getPath())).contains(TEST_DIRECTORY);
            assertThat(listAs(TEST_APP_B, dir.getPath())).doesNotContain(videoFileName);
        } finally {
            uninstallApp(TEST_APP_B);
            if(videoFile.exists()) {
                deleteFileAs(TEST_APP_A, videoFile.getPath());
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
            assertThat(createFileAs(TEST_APP_A, pdfFile.getPath())).isTrue();

            // TEST_APP_A should see TEST_DIRECTORY in DOWNLOAD_DIR and new non media file in
            // TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_A, DOWNLOAD_DIR.getPath())).contains(TEST_DIRECTORY);
            assertThat(listAs(TEST_APP_A, dir.getPath())).containsExactly(pdfFileName);

            // Install TEST_APP_B with storage permission.
            installApp(TEST_APP_B, true);
            // TEST_APP_B with storage permission should see TEST_DIRECTORY in DOWNLOAD_DIR
            // and should not see new non media file in TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_B, DOWNLOAD_DIR.getPath())).contains(TEST_DIRECTORY);
            assertThat(listAs(TEST_APP_B, dir.getPath())).doesNotContain(pdfFileName);
        } finally {
            uninstallApp(TEST_APP_B);
            if(pdfFile.exists()) {
                deleteFileAs(TEST_APP_A, pdfFile.getPath());
            }
            if (dir.exists()) {
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
        final String packageName = THIS_PACKAGE_NAME;
        final File videoFile = new File(EXTERNAL_FILES_DIR, NONMEDIA_FILE_NAME);
        final String videoFileName = videoFile.getName();

        try {
            // Create a file in app's external files directory
            if (!videoFile.exists()) {
                assertThat(videoFile.createNewFile()).isTrue();
            }
            // App should see its directory and directories of shared packages. App should see all
            // files and directories in its external directory.
            assertThat(ReaddirTestHelper.readDirectory(videoFile.getParentFile()))
                    .containsExactly(videoFileName);

            // Install TEST_APP_A with READ_EXTERNAL_STORAGE permission.
            // TEST_APP_A should not see other app's external files directory.
            installApp(TEST_APP_A, true);
            // TODO(b/146497700): This is passing because ReaddirTestHelper ignores IOException and
            //  returns empty list.
            assertThat(listAs(TEST_APP_A, ANDROID_DATA_DIR.getPath())).doesNotContain(packageName);
            assertThat(listAs(TEST_APP_A, EXTERNAL_FILES_DIR.getPath())).isEmpty();
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Test that app can see files and directories in Android/media.
     */
    @Test
    public void testListFilesFromExternalMediaDirectory() throws Exception {
        final String packageName = THIS_PACKAGE_NAME;
        final File videoFile = new File(EXTERNAL_MEDIA_DIR, VIDEO_FILE_NAME);
        final String videoFileName = videoFile.getName();

        try {
            // Create a file in app's external media directory
            if (!videoFile.exists()) {
                assertThat(videoFile.createNewFile()).isTrue();
            }

            // App should see its directory and other app's external media directories with media
            // files.
            // TODO(b/145757667): Uncomment this when we start indexing Android/media files.
            // assertThat(ReaddirTestHelper.readDirectory(ANDROID_MEDIA_DIR)).contains(packageName);
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

    /**
     * Test that readdir lists unsupported file types in default directories.
     */
    @Test
    public void testListUnsupportedFileType() throws Exception {
        final File pdfFile = new File(DCIM_DIR, NONMEDIA_FILE_NAME);
        final File videoFile = new File(MUSIC_DIR, VIDEO_FILE_NAME);
        try {
            // TEST_APP_A with storage permission should not see pdf file in DCIM
            executeShellCommand("touch " + pdfFile.getAbsolutePath());
            assertThat(pdfFile.exists()).isTrue();
            assertThat(MediaStore.scanFile(getContentResolver(), pdfFile)).isNotNull();

            installApp(TEST_APP_A, true);
            assertThat(listAs(TEST_APP_A, DCIM_DIR.getPath())).doesNotContain(NONMEDIA_FILE_NAME);

            // TEST_APP_A with storage permission should see video file in Music directory.
            executeShellCommand("touch " + videoFile.getAbsolutePath());
            assertThat(MediaStore.scanFile(getContentResolver(), videoFile)).isNotNull();
            assertThat(listAs(TEST_APP_A, MUSIC_DIR.getPath())).contains(VIDEO_FILE_NAME);
        } finally {
            executeShellCommand("rm " + pdfFile.getAbsolutePath());
            executeShellCommand("rm " + videoFile.getAbsolutePath());
        }
    }

    @Test
    public void testMetaDataRedaction() throws Exception {
        File jpgFile = new File(PICTURES_DIR, "img_metadata.jpg");
        try {
            if (jpgFile.exists()) {
                assertThat(jpgFile.delete()).isTrue();
            }

            HashMap<String, String> originalExif = getExifMetadataFromRawResource(
                    R.raw.img_with_metadata);

            try (InputStream in = getContext().getResources().openRawResource(
                    R.raw.img_with_metadata);
                 OutputStream out = new FileOutputStream(jpgFile)) {
                // Dump the image we have to external storage
                FileUtils.copy(in, out);
            }

            HashMap<String, String> exif = getExifMetadata(jpgFile);
            assertExifMetadataMatch(exif, originalExif);

            installApp(TEST_APP_A, /*grantStoragePermissions*/ true);
            HashMap<String, String> exifFromTestApp = readExifMetadataFromTestApp(TEST_APP_A,
                    jpgFile.getPath());
            // Other apps shouldn't have access to the same metadata without explicit permission
            assertExifMetadataMismatch(exifFromTestApp, originalExif);

            // TODO(b/146346138): Test that if we give TEST_APP_A write URI permission,
            //  it would be able to access the metadata.
        } finally {
            jpgFile.delete();
            uninstallApp(TEST_APP_A);
        }
    }

    @Test
    public void testOpenFilePathFirstWriteContentResolver() throws Exception {
        String displayName = "open_file_path_write_content_resolver.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor readPfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);
            ParcelFileDescriptor writePfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "rw");

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverFirstWriteContentResolver() throws Exception {
        String displayName = "open_content_resolver_write_content_resolver.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor writePfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "rw");
            ParcelFileDescriptor readPfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenFilePathFirstWriteFilePath() throws Exception {
        String displayName = "open_file_path_write_file_path.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor writePfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);
            ParcelFileDescriptor readPfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "rw");

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverFirstWriteFilePath() throws Exception {
        String displayName = "open_content_resolver_write_file_path.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor readPfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "rw");
            ParcelFileDescriptor writePfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverWriteOnly() throws Exception {
        String displayName = "open_content_resolver_write_only.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            // Since we can only place one F_WRLCK, the second open for readPfd will go
            // throuh FUSE
            ParcelFileDescriptor writePfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "w");
            ParcelFileDescriptor readPfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "rw");

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverDup() throws Exception {
        String displayName = "open_content_resolver_dup.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            // Even if we close the original fd, since we have a dup open
            // the FUSE IO should still bypass the cache
            ParcelFileDescriptor writePfd = openWithMediaProvider(Environment.DIRECTORY_DCIM,
                    displayName, "rw");
            ParcelFileDescriptor writePfdDup = writePfd.dup();
            writePfd.close();
            ParcelFileDescriptor readPfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            assertRWR(readPfd.getFileDescriptor(), writePfdDup.getFileDescriptor());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testContentResolverDelete() throws Exception {
        String displayName = "content_resolver_delete.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            deleteWithMediaProvider(Environment.DIRECTORY_DCIM, displayName);

            assertThat(file.exists()).isFalse();
            assertThat(file.createNewFile()).isTrue();
        } finally {
            file.delete();
        }
    }

    @Test
    public void testContentResolverUpdate() throws Exception {
        String oldDisplayName = "content_resolver_update_old.jpg";
        String newDisplayName = "content_resolver_update_new.jpg";
        File oldFile = new File(DCIM_DIR, oldDisplayName);
        File newFile = new File(DCIM_DIR, newDisplayName);

        try {
            assertThat(oldFile.createNewFile()).isTrue();

            updateWithMediaProvider(Environment.DIRECTORY_DCIM, oldDisplayName, newDisplayName);

            assertThat(oldFile.exists()).isFalse();
            assertThat(oldFile.createNewFile()).isTrue();
            assertThat(newFile.exists()).isTrue();
            assertThat(newFile.createNewFile()).isFalse();
        } finally {
            oldFile.delete();
            newFile.delete();
        }
    }

    /**
     * Test that basic file path restrictions are enforced on file rename.
     */
    @Test
    public void testRenameFile() throws Exception {
        final File nonMediaDir = new File(DOWNLOAD_DIR, TEST_DIRECTORY);
        final File pdfFile1 = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME);
        final File pdfFile2 = new File(nonMediaDir, NONMEDIA_FILE_NAME);
        final File videoFile1 = new File(DCIM_DIR, VIDEO_FILE_NAME);
        final File videoFile2 = new File(MOVIES_DIR, VIDEO_FILE_NAME);
        final File videoFile3 = new File(DOWNLOAD_DIR, VIDEO_FILE_NAME);

        try {
            // Rename Non-media file
            assertThat(pdfFile1.createNewFile()).isTrue();
            if (!nonMediaDir.exists()) {
                assertThat(nonMediaDir.mkdirs()).isTrue();
            }
            assertThat(pdfFile1.renameTo(pdfFile2)).isTrue();
            assertThat(pdfFile1.exists()).isFalse();
            assertThat(pdfFile2.exists()).isTrue();

            assertThat(pdfFile2.renameTo(pdfFile1)).isTrue();
            assertThat(pdfFile2.exists()).isFalse();
            assertThat(pdfFile1.exists()).isTrue();

            // Rename media file
            assertThat(videoFile1.createNewFile()).isTrue();
            assertThat(videoFile1.renameTo(videoFile2)).isTrue();
            assertThat(videoFile1.exists()).isFalse();
            assertThat(videoFile2.exists()).isTrue();

            assertThat(videoFile2.renameTo(videoFile3)).isTrue();
            assertThat(videoFile2.exists()).isFalse();
            assertThat(videoFile3.exists()).isTrue();

            // Move video file back to DCIM to ensure database entry is deleted on delete().
            assertThat(videoFile3.renameTo(videoFile1)).isTrue();
        } finally {
            pdfFile1.delete();
            pdfFile2.delete();
            videoFile1.delete();
            videoFile2.delete();
            videoFile3.delete();
            nonMediaDir.delete();
        }
    }

    /**
     * Test that renaming directories is allowed and aligns to default directory restrictions.
     */
    @Test
    public void testRenameDirectory() throws Exception {

        final String mediaDirectoryName = TEST_DIRECTORY + "Media";
        final File mediaDirectory1 = new File(DCIM_DIR, mediaDirectoryName);
        final File videoFile1 = new File(mediaDirectory1, VIDEO_FILE_NAME);
        final File mediaDirectory2 =  new File(DOWNLOAD_DIR, mediaDirectoryName);
        final File videoFile2 = new File(mediaDirectory2, VIDEO_FILE_NAME);
        final File mediaDirectory3 =  new File(MOVIES_DIR, TEST_DIRECTORY);
        final File videoFile3 = new File(mediaDirectory3, VIDEO_FILE_NAME);

        try {
            if (!mediaDirectory1.exists()) {
                assertThat(mediaDirectory1.mkdirs()).isTrue();
            }
            assertThat(videoFile1.createNewFile()).isTrue();

            // Renaming to and from default directories is not allowed.
            assertThat(mediaDirectory1.renameTo(DCIM_DIR)).isFalse();
            // Move top level default directories
            assertThat(DOWNLOAD_DIR.renameTo(new File(DCIM_DIR, TEST_DIRECTORY))).isFalse();

            // Move media directory to Download directory.
            assertThat(mediaDirectory1.renameTo(mediaDirectory2)).isTrue();
            assertThat(mediaDirectory1.exists()).isFalse();
            assertThat(mediaDirectory2.exists()).isTrue();
            assertThat(videoFile1.exists()).isFalse();
            assertThat(videoFile2.exists()).isTrue();

            // Move media directory to Movies directory and rename directory in new path.
            assertThat(mediaDirectory2.renameTo(mediaDirectory3)).isTrue();
            assertThat(mediaDirectory2.exists()).isFalse();
            assertThat(mediaDirectory3.exists()).isTrue();
            assertThat(videoFile2.exists()).isFalse();
            assertThat(videoFile3.exists()).isTrue();

            // Move videoFile back to original directory to ensure database entry for video file
            // is deleted on delete().
            assertThat(mediaDirectory3.renameTo(mediaDirectory1)).isTrue();
        } finally {
            videoFile1.delete();
            videoFile2.delete();
            videoFile3.delete();
            mediaDirectory1.delete();
            mediaDirectory2.delete();
            mediaDirectory3.delete();
        }
    }

    /**
     * Test renaming empty directory is allowed
     */
    @Test
    public void testRenameEmptyDirectory() throws Exception {
        final String emptyDirectoryName = TEST_DIRECTORY + "Media";
        File emptyDirectoryOldPath = new File(DCIM_DIR, emptyDirectoryName);
        File emptyDirectoryNewPath = new File(MOVIES_DIR, TEST_DIRECTORY);
        try {
            if (!emptyDirectoryOldPath.exists()) {
                assertThat(emptyDirectoryOldPath.mkdirs()).isTrue();
                assertThat(emptyDirectoryOldPath.renameTo(emptyDirectoryNewPath)).isTrue();
                assertThat(emptyDirectoryNewPath.exists()).isTrue();
            }
        } finally {
            emptyDirectoryOldPath.delete();
            emptyDirectoryNewPath.delete();
        }
    }

    private void deleteWithMediaProvider(String relativePath, String displayName) throws Exception {
        String selection = MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = { relativePath + '/', displayName };

        assertThat(getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        selection, selectionArgs)).isEqualTo(1);
    }

    private void updateWithMediaProvider(String relativePath, String oldDisplayName,
            String newDisplayName) throws Exception {
        String selection = MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = { relativePath + '/', oldDisplayName };
        String[] projection = {MediaColumns._ID, MediaColumns.DATA};

        ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, newDisplayName);

        try (final Cursor cursor = getContentResolver().query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs,
                null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            int id = cursor.getInt(cursor.getColumnIndex(MediaColumns._ID));
            String data = cursor.getString(cursor.getColumnIndex(MediaColumns.DATA));
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            Log.i(TAG, "Uri: " + uri + ". Data: " + data);
            assertThat(getContentResolver().update(uri, values, selection, selectionArgs))
                    .isEqualTo(1);
        }
    }


    /**
     * Assert that the last read in: read - write - read using {@code readFd} and {@code writeFd}
     * see the last write. {@code readFd} and {@code writeFd} are fds pointing to the same
     * underlying file on disk but may be derived from different mount points and in that case
     * have separate VFS caches.
     */
    private void assertRWR(FileDescriptor readFd, FileDescriptor writeFd) throws Exception {
        byte[] readBuffer = new byte[10];
        byte[] writeBuffer = new byte[10];
        Arrays.fill(writeBuffer, (byte) 1);

        // Write so readFd has content to read from next
        Os.pwrite(readFd, readBuffer, 0, 10, 0);
        // Read so readBuffer is in readFd's mount VFS cache
        Os.pread(readFd, readBuffer, 0, 10, 0);

        // Assert that readBuffer is zeroes
        assertThat(readBuffer).isEqualTo(new byte[10]);

        // Write so writeFd and readFd should now see writeBuffer
        Os.pwrite(writeFd, writeBuffer, 0, 10, 0);

        // Read so the last write can be verified on readFd
        Os.pread(readFd, readBuffer, 0, 10, 0);

        // Assert that the last write is indeed visible via readFd
        assertThat(readBuffer).isEqualTo(writeBuffer);
    }

    private ParcelFileDescriptor openWithMediaProvider(String relativePath, String displayName,
            String mode) throws Exception {
        String selection = MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = { relativePath + '/', displayName };
        String[] projection = {MediaColumns._ID, MediaColumns.DATA};

        try (final Cursor cursor = getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                        selectionArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            int id = cursor.getInt(cursor.getColumnIndex(MediaColumns._ID));
            String data = cursor.getString(cursor.getColumnIndex(MediaColumns.DATA));
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            Log.i(TAG, "Uri: " + uri + ". Data: " + data);
            return getContentResolver().openFileDescriptor(uri, mode);
        }
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
}
