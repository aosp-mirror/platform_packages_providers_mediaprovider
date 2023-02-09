/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.providers.media.util;

import static android.os.ParcelFileDescriptor.MODE_APPEND;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;
import static android.system.OsConstants.F_OK;
import static android.system.OsConstants.O_APPEND;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_RDWR;
import static android.system.OsConstants.O_TRUNC;
import static android.system.OsConstants.O_WRONLY;
import static android.system.OsConstants.R_OK;
import static android.system.OsConstants.W_OK;
import static android.system.OsConstants.X_OK;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;

import static com.android.providers.media.util.FileUtils.buildUniqueFile;
import static com.android.providers.media.util.FileUtils.extractDisplayName;
import static com.android.providers.media.util.FileUtils.extractFileExtension;
import static com.android.providers.media.util.FileUtils.extractFileName;
import static com.android.providers.media.util.FileUtils.extractOwnerPackageNameFromRelativePath;
import static com.android.providers.media.util.FileUtils.extractPathOwnerPackageName;
import static com.android.providers.media.util.FileUtils.extractRelativePath;
import static com.android.providers.media.util.FileUtils.extractTopLevelDir;
import static com.android.providers.media.util.FileUtils.extractVolumeName;
import static com.android.providers.media.util.FileUtils.extractVolumePath;
import static com.android.providers.media.util.FileUtils.isDataOrObbPath;
import static com.android.providers.media.util.FileUtils.isDataOrObbRelativePath;
import static com.android.providers.media.util.FileUtils.isExternalMediaDirectory;
import static com.android.providers.media.util.FileUtils.isObbOrChildRelativePath;
import static com.android.providers.media.util.FileUtils.translateModeAccessToPosix;
import static com.android.providers.media.util.FileUtils.translateModePfdToPosix;
import static com.android.providers.media.util.FileUtils.translateModePosixToPfd;
import static com.android.providers.media.util.FileUtils.translateModePosixToString;
import static com.android.providers.media.util.FileUtils.translateModeStringToPosix;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.os.Environment;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.Range;
import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class FileUtilsTest {
    // Exposing here since it is also used by MediaProviderTest.java
    public static final int MAX_FILENAME_BYTES = FileUtils.MAX_FILENAME_BYTES;

    /**
     * To help avoid flaky tests, give ourselves a unique nonce to be used for
     * all filesystem paths, so that we don't risk conflicting with previous
     * test runs.
     */
    private static final String NONCE = String.valueOf(System.nanoTime());

    private static final String TEST_DIRECTORY_NAME = "FileUtilsTestDirectory" + NONCE;
    private static final String TEST_FILE_NAME = "FileUtilsTestFile" + NONCE;

    private File mTarget;
    private File mDcimTarget;
    private File mDeleteTarget;
    private File mDownloadTarget;
    private File mTestDownloadDir;

    @Before
    public void setUp() throws Exception {
        mTarget = InstrumentationRegistry.getTargetContext().getCacheDir();
        FileUtils.deleteContents(mTarget);

        mDcimTarget = new File(mTarget, "DCIM");
        mDcimTarget.mkdirs();

        mDeleteTarget = mDcimTarget;

        mDownloadTarget = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        mTestDownloadDir = new File(mDownloadTarget, TEST_DIRECTORY_NAME);
        mTestDownloadDir.mkdirs();
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mTarget);
        FileUtils.deleteContents(mTestDownloadDir);
    }

    private void touch(String name, long age) throws Exception {
        final File file = new File(mDeleteTarget, name);
        file.createNewFile();
        file.setLastModified(System.currentTimeMillis() - age);
    }

    @Test
    public void testString() throws Exception {
        final File file = new File(mTarget, String.valueOf(System.nanoTime()));

        // Verify initial empty state
        assertFalse(FileUtils.readString(file).isPresent());

        // Verify simple writing and reading
        FileUtils.writeString(file, Optional.of("meow"));
        assertTrue(FileUtils.readString(file).isPresent());
        assertEquals("meow", FileUtils.readString(file).get());

        // Verify empty writing deletes file
        FileUtils.writeString(file, Optional.empty());
        assertFalse(FileUtils.readString(file).isPresent());

        // Verify reading from a file with more than 4096 chars
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(4097);
        }
        assertEquals(Optional.empty(), FileUtils.readString(file));

        // Verify reading from non existing file.
        file.delete();
        assertEquals(Optional.empty(), FileUtils.readString(file));

    }

    @Test
    public void testDeleteOlderEmptyDir() throws Exception {
        FileUtils.deleteOlderFiles(mDeleteTarget, 10, WEEK_IN_MILLIS);
        assertDirContents();
    }

    @Test
    public void testDeleteOlderTypical() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDeleteTarget, 3, DAY_IN_MILLIS));
        assertDirContents("file1", "file2", "file3");
    }

    @Test
    public void testDeleteOlderInFuture() throws Exception {
        touch("file1", -HOUR_IN_MILLIS);
        touch("file2", HOUR_IN_MILLIS);
        touch("file3", WEEK_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDeleteTarget, 0, DAY_IN_MILLIS));
        assertDirContents("file1", "file2");

        touch("file1", -HOUR_IN_MILLIS);
        touch("file2", HOUR_IN_MILLIS);
        touch("file3", WEEK_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDeleteTarget, 0, DAY_IN_MILLIS));
        assertDirContents("file1", "file2");
    }

    @Test
    public void testDeleteOlderOnlyAge() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDeleteTarget, 0, DAY_IN_MILLIS));
        assertFalse(FileUtils.deleteOlderFiles(mDeleteTarget, 0, DAY_IN_MILLIS));
        assertDirContents("file1");
    }

    @Test
    public void testDeleteOlderOnlyCount() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDeleteTarget, 2, 0));
        assertFalse(FileUtils.deleteOlderFiles(mDeleteTarget, 2, 0));
        assertDirContents("file1", "file2");
    }

    @Test
    public void testTranslateMode() throws Exception {
        assertTranslate("r", O_RDONLY, MODE_READ_ONLY);

        assertTranslate("rw", O_RDWR | O_CREAT,
                MODE_READ_WRITE | MODE_CREATE);
        assertTranslate("rwt", O_RDWR | O_CREAT | O_TRUNC,
                MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);
        assertTranslate("rwa", O_RDWR | O_CREAT | O_APPEND,
                MODE_READ_WRITE | MODE_CREATE | MODE_APPEND);

        assertTranslate("w", O_WRONLY | O_CREAT,
                MODE_WRITE_ONLY | MODE_CREATE | MODE_CREATE);
        assertTranslate("wt", O_WRONLY | O_CREAT | O_TRUNC,
                MODE_WRITE_ONLY | MODE_CREATE | MODE_TRUNCATE);
        assertTranslate("wa", O_WRONLY | O_CREAT | O_APPEND,
                MODE_WRITE_ONLY | MODE_CREATE | MODE_APPEND);
    }

    @Test
    public void testMalformedTransate_int() throws Exception {
        try {
            // The non-standard Linux access mode 3 should throw
            // an IllegalArgumentException.
            translateModePosixToPfd(O_RDWR | O_WRONLY);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testMalformedTransate_string() throws Exception {
        try {
            // The non-standard Linux access mode 3 should throw
            // an IllegalArgumentException.
            translateModePosixToString(O_RDWR | O_WRONLY);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testTranslateMode_Invalid() throws Exception {
        try {
            translateModeStringToPosix("rwx");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            translateModeStringToPosix("");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testTranslateMode_Access() throws Exception {
        assertEquals(O_RDONLY, translateModeAccessToPosix(F_OK));
        assertEquals(O_RDONLY, translateModeAccessToPosix(R_OK));
        assertEquals(O_WRONLY, translateModeAccessToPosix(W_OK));
        assertEquals(O_RDWR, translateModeAccessToPosix(R_OK | W_OK));
        assertEquals(O_RDWR, translateModeAccessToPosix(R_OK | W_OK | X_OK));
    }

    private static void assertTranslate(String string, int posix, int pfd) {
        assertEquals(posix, translateModeStringToPosix(string));
        assertEquals(string, translateModePosixToString(posix));
        assertEquals(pfd, translateModePosixToPfd(posix));
        assertEquals(posix, translateModePfdToPosix(pfd));
    }

    @Test
    public void testContains() throws Exception {
        assertTrue(FileUtils.contains(new File("/"), new File("/moo.txt")));
        assertTrue(FileUtils.contains(new File("/"), new File("/")));

        assertTrue(FileUtils.contains(new File("/sdcard"), new File("/sdcard")));
        assertTrue(FileUtils.contains(new File("/sdcard/"), new File("/sdcard/")));

        assertTrue(FileUtils.contains(new File("/sdcard"), new File("/sdcard/moo.txt")));
        assertTrue(FileUtils.contains(new File("/sdcard/"), new File("/sdcard/moo.txt")));

        assertFalse(FileUtils.contains(new File("/sdcard"), new File("/moo.txt")));
        assertFalse(FileUtils.contains(new File("/sdcard/"), new File("/moo.txt")));

        assertFalse(FileUtils.contains(new File("/sdcard"), new File("/sdcard.txt")));
        assertFalse(FileUtils.contains(new File("/sdcard/"), new File("/sdcard.txt")));
    }

    @Test
    public void testValidFatFilename() throws Exception {
        assertTrue(FileUtils.isValidFatFilename("a"));
        assertTrue(FileUtils.isValidFatFilename("foo bar.baz"));
        assertTrue(FileUtils.isValidFatFilename("foo.bar.baz"));
        assertTrue(FileUtils.isValidFatFilename(".bar"));
        assertTrue(FileUtils.isValidFatFilename("foo.bar"));
        assertTrue(FileUtils.isValidFatFilename("foo bar"));
        assertTrue(FileUtils.isValidFatFilename("foo+bar"));
        assertTrue(FileUtils.isValidFatFilename("foo,bar"));

        assertFalse(FileUtils.isValidFatFilename("foo*bar"));
        assertFalse(FileUtils.isValidFatFilename("foo?bar"));
        assertFalse(FileUtils.isValidFatFilename("foo<bar"));
        assertFalse(FileUtils.isValidFatFilename(null));
        assertFalse(FileUtils.isValidFatFilename("."));
        assertFalse(FileUtils.isValidFatFilename("../foo"));
        assertFalse(FileUtils.isValidFatFilename("/foo"));

        assertEquals(".._foo", FileUtils.buildValidFatFilename("../foo"));
        assertEquals("_foo", FileUtils.buildValidFatFilename("/foo"));
        assertEquals(".foo", FileUtils.buildValidFatFilename(".foo"));
        assertEquals("foo.bar", FileUtils.buildValidFatFilename("foo.bar"));
        assertEquals("foo_bar__baz", FileUtils.buildValidFatFilename("foo?bar**baz"));
    }

    @Test
    public void testTrimFilename() throws Exception {
        assertEquals("short.txt", FileUtils.trimFilename("short.txt", 16));
        assertEquals("extrem...eme.txt", FileUtils.trimFilename("extremelylongfilename.txt", 16));

        final String unicode = "a\u03C0\u03C0\u03C0\u03C0z";
        assertEquals("a\u03C0\u03C0\u03C0\u03C0z", FileUtils.trimFilename(unicode, 10));
        assertEquals("a\u03C0...\u03C0z", FileUtils.trimFilename(unicode, 9));
        assertEquals("a...\u03C0z", FileUtils.trimFilename(unicode, 8));
        assertEquals("a...\u03C0z", FileUtils.trimFilename(unicode, 7));
        assertEquals("a...z", FileUtils.trimFilename(unicode, 6));
    }

    @Test
    public void testBuildUniqueFile_normal() throws Exception {
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test"));
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        assertNameEquals("test.jpeg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpeg"));
        assertNameEquals("TEst.JPeg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "TEst.JPeg"));
        assertNameEquals(".test.jpg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", ".test"));
        assertNameEquals("test.png.jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.png.jpg"));
        assertNameEquals("test.png.jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.png"));

        assertNameEquals("test.flac", FileUtils.buildUniqueFile(mTarget, "audio/flac", "test"));
        assertNameEquals("test.flac", FileUtils.buildUniqueFile(mTarget, "audio/flac", "test.flac"));
        assertNameEquals("test.flac",
                FileUtils.buildUniqueFile(mTarget, "application/x-flac", "test"));
        assertNameEquals("test.flac",
                FileUtils.buildUniqueFile(mTarget, "application/x-flac", "test.flac"));
    }

    @Test
    public void testBuildUniqueFile_unknown() throws Exception {
        assertNameEquals("test",
                FileUtils.buildUniqueFile(mTarget, "application/octet-stream", "test"));
        assertNameEquals("test.jpg",
                FileUtils.buildUniqueFile(mTarget, "application/octet-stream", "test.jpg"));
        assertNameEquals(".test",
                FileUtils.buildUniqueFile(mTarget, "application/octet-stream", ".test"));

        assertNameEquals("test", FileUtils.buildUniqueFile(mTarget, "lolz/lolz", "test"));
        assertNameEquals("test.lolz", FileUtils.buildUniqueFile(mTarget, "lolz/lolz", "test.lolz"));
    }

    @Test
    public void testBuildUniqueFile_increment() throws Exception {
        assertNameEquals("test.jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        new File(mTarget, "test.jpg").createNewFile();
        assertNameEquals("test (1).jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        new File(mTarget, "test (1).jpg").createNewFile();
        assertNameEquals("test (2).jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
    }

    @Test
    public void testBuildUniqueFile_increment_hidden() throws Exception {
        assertNameEquals(".hidden.jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", ".hidden.jpg"));
        new File(mTarget, ".hidden.jpg").createNewFile();
        assertNameEquals(".hidden (1).jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", ".hidden.jpg"));
    }

    @Test
    public void testBuildUniqueFile_mimeless() throws Exception {
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "test.jpg"));
        new File(mTarget, "test.jpg").createNewFile();
        assertNameEquals("test (1).jpg", FileUtils.buildUniqueFile(mTarget, "test.jpg"));

        assertNameEquals("test", FileUtils.buildUniqueFile(mTarget, "test"));
        new File(mTarget, "test").createNewFile();
        assertNameEquals("test (1)", FileUtils.buildUniqueFile(mTarget, "test"));

        assertNameEquals("test.foo.bar", FileUtils.buildUniqueFile(mTarget, "test.foo.bar"));
        new File(mTarget, "test.foo.bar").createNewFile();
        assertNameEquals("test.foo (1).bar", FileUtils.buildUniqueFile(mTarget, "test.foo.bar"));
    }

    /**
     * Verify that we generate unique filenames that meet the JEITA DCF
     * specification when writing into directories like {@code DCIM}.
     */
    @Test
    public void testBuildUniqueFile_DCF_strict() throws Exception {
        assertNameEquals("IMG_0100.JPG",
                buildUniqueFile(mDcimTarget, "IMG_0100.JPG"));

        touch(mDcimTarget, "IMG_0999.JPG");
        assertNameEquals("IMG_0998.JPG",
                buildUniqueFile(mDcimTarget, "IMG_0998.JPG"));
        assertNameEquals("IMG_1000.JPG",
                buildUniqueFile(mDcimTarget, "IMG_0999.JPG"));
        assertNameEquals("IMG_1000.JPG",
                buildUniqueFile(mDcimTarget, "IMG_1000.JPG"));

        touch(mDcimTarget, "IMG_1000.JPG");
        assertNameEquals("IMG_1001.JPG",
                buildUniqueFile(mDcimTarget, "IMG_0999.JPG"));

        // We can't step beyond standard numbering
        touch(mDcimTarget, "IMG_9999.JPG");
        try {
            buildUniqueFile(mDcimTarget, "IMG_9999.JPG");
            fail();
        } catch (FileNotFoundException expected) {
        }
    }

    /**
     * Verify that we generate unique filenames that meet the JEITA DCF
     * specification when writing into directories like {@code DCIM}.
     *
     * See b/174120008 for context.
     */
    @Test
    public void testBuildUniqueFile_DCF_strict_differentLocale() throws Exception {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ar", "SA"));
            testBuildUniqueFile_DCF_strict();
        }
        finally {
            Locale.setDefault(defaultLocale);
        }
    }

    /**
     * Verify that we generate unique filenames that look valid compared to other
     * {@code DCIM} filenames. These technically aren't part of the official
     * JEITA DCF specification.
     */
    @Test
    public void testBuildUniqueFile_DCF_relaxed() throws Exception {
        touch(mDcimTarget, "IMG_20190102_030405.jpg");
        assertNameEquals("IMG_20190102_030405~2.jpg",
                buildUniqueFile(mDcimTarget, "IMG_20190102_030405.jpg"));

        touch(mDcimTarget, "IMG_20190102_030405~2.jpg");
        assertNameEquals("IMG_20190102_030405~3.jpg",
                buildUniqueFile(mDcimTarget, "IMG_20190102_030405.jpg"));
        assertNameEquals("IMG_20190102_030405~3.jpg",
                buildUniqueFile(mDcimTarget, "IMG_20190102_030405~2.jpg"));
    }

    /**
     * Verify that we generate unique filenames that look valid compared to other
     * {@code DCIM} filenames. These technically aren't part of the official
     * JEITA DCF specification.
     *
     * See b/174120008 for context.
     */
    @Test
    public void testBuildUniqueFile_DCF_relaxed_differentLocale() throws Exception {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ar", "SA"));
            testBuildUniqueFile_DCF_relaxed();
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    public void testGetAbsoluteExtendedPath() throws Exception {
        assertEquals("/storage/emulated/0/DCIM/.trashed-1888888888-test.jpg",
                FileUtils.getAbsoluteExtendedPath(
                        "/storage/emulated/0/DCIM/.trashed-1621147340-test.jpg", 1888888888));
    }

    @Test
    public void testExtractVolumePath() throws Exception {
        assertEquals("/storage/emulated/0/",
                extractVolumePath("/storage/emulated/0/foo.jpg"));
        assertEquals("/storage/0000-0000/",
                extractVolumePath("/storage/0000-0000/foo.jpg"));
    }

    @Test
    public void testExtractVolumeName() throws Exception {
        assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY,
                extractVolumeName("/storage/emulated/0/foo.jpg"));
        assertEquals("0000-0000",
                extractVolumeName("/storage/0000-0000/foo.jpg"));
    }

    @Test
    public void testExtractRelativePath() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0/",
                "/storage/0000-0000/"
        }) {
            assertEquals("/",
                    extractRelativePath(prefix + "foo.jpg"));
            assertEquals("DCIM/",
                    extractRelativePath(prefix + "DCIM/foo.jpg"));
            assertEquals("DCIM/My Vacation/",
                    extractRelativePath(prefix + "DCIM/My Vacation/foo.jpg"));
            assertEquals("Pictures/",
                    extractRelativePath(prefix + "DCIM/../Pictures/.//foo.jpg"));
            assertEquals("/",
                    extractRelativePath(prefix + "DCIM/Pictures/./..//..////foo.jpg"));
            assertEquals("Android/data/",
                    extractRelativePath(prefix + "DCIM/foo.jpg/.//../../Android/data/poc"));
        }

        assertEquals(null, extractRelativePath("/sdcard/\\\u0000"));
    }

    @Test
    public void testExtractTopLevelDir() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0/",
                "/storage/0000-0000/"
        }) {
            assertEquals(null,
                    extractTopLevelDir(prefix + "foo.jpg"));
            assertEquals("DCIM",
                    extractTopLevelDir(prefix + "DCIM/foo.jpg"));
            assertEquals("DCIM",
                    extractTopLevelDir(prefix + "DCIM/My Vacation/foo.jpg"));
        }
    }

    @Test
    public void testExtractTopLevelDirWithRelativePathSegments() throws Exception {
        assertEquals(null,
                extractTopLevelDir(new String[] { null }));
        assertEquals("DCIM",
                extractTopLevelDir(new String[] { "DCIM" }));
        assertEquals("DCIM",
                extractTopLevelDir(new String[] { "DCIM", "My Vacation" }));

        assertEquals(null,
                extractTopLevelDir(new String[] { "AppClone" }, "AppClone"));
        assertEquals("DCIM",
                extractTopLevelDir(new String[] { "AppClone", "DCIM" }, "AppClone"));
        assertEquals("DCIM",
                extractTopLevelDir(new String[] { "AppClone", "DCIM", "My Vacation" }, "AppClone"));

        assertEquals("Test",
                extractTopLevelDir(new String[] { "Test" }, "AppClone"));
        assertEquals("Test",
                extractTopLevelDir(new String[] { "Test", "DCIM" }, "AppClone"));
        assertEquals("Test",
                extractTopLevelDir(new String[] { "Test", "DCIM", "My Vacation" }, "AppClone"));
    }

    @Test
    public void testExtractTopLevelDirForCrossUser() throws Exception {
        Assume.assumeTrue(FileUtils.isCrossUserEnabled());

        final String crossUserRoot = SystemProperties.get("external_storage.cross_user.root", null);
        Assume.assumeFalse(TextUtils.isEmpty(crossUserRoot));

        for (String prefix : new String[] {
                "/storage/emulated/0/",
                "/storage/0000-0000/"
        }) {
            assertEquals(null,
                    extractTopLevelDir(prefix + "foo.jpg"));
            assertEquals("DCIM",
                    extractTopLevelDir(prefix + "DCIM/foo.jpg"));
            assertEquals("DCIM",
                    extractTopLevelDir(prefix + "DCIM/My Vacation/foo.jpg"));

            assertEquals(null,
                    extractTopLevelDir(prefix + crossUserRoot + "/foo.jpg"));
            assertEquals("DCIM",
                    extractTopLevelDir(prefix + crossUserRoot + "/DCIM/foo.jpg"));
            assertEquals("DCIM",
                    extractTopLevelDir(prefix + crossUserRoot + "/DCIM/My Vacation/foo.jpg"));

            assertEquals("Test",
                    extractTopLevelDir(prefix + "Test/DCIM/foo.jpg"));
            assertEquals("Test",
                    extractTopLevelDir(prefix + "Test/DCIM/My Vacation/foo.jpg"));
        }
    }

    @Test
    public void testExtractDisplayName() throws Exception {
        for (String probe : new String[] {
                "foo.bar.baz",
                "/foo.bar.baz",
                "/foo.bar.baz/",
                "/sdcard/foo.bar.baz",
                "/sdcard/foo.bar.baz/",
        }) {
            assertEquals(probe, "foo.bar.baz", extractDisplayName(probe));
        }
    }

    @Test
    public void testExtractFileName() throws Exception {
        for (String probe : new String[] {
                "foo",
                "/foo",
                "/sdcard/foo",
                "foo.bar",
                "/foo.bar",
                "/sdcard/foo.bar",
        }) {
            assertEquals(probe, "foo", extractFileName(probe));
        }
    }

    @Test
    public void testExtractFileName_empty() throws Exception {
        for (String probe : new String[] {
                "",
                "/",
                ".bar",
                "/.bar",
                "/sdcard/.bar",
        }) {
            assertEquals(probe, "", extractFileName(probe));
        }
    }

    @Test
    public void testExtractFileExtension() throws Exception {
        for (String probe : new String[] {
                ".bar",
                "foo.bar",
                "/.bar",
                "/foo.bar",
                "/sdcard/.bar",
                "/sdcard/foo.bar",
                "/sdcard/foo.baz.bar",
                "/sdcard/foo..bar",
        }) {
            assertEquals(probe, "bar", extractFileExtension(probe));
        }
    }

    @Test
    public void testExtractFileExtension_none() throws Exception {
        for (String probe : new String[] {
                "",
                "/",
                "/sdcard/",
                "bar",
                "/bar",
                "/sdcard/bar",
        }) {
            assertEquals(probe, null, extractFileExtension(probe));
        }
    }

    @Test
    public void testExtractFileExtension_empty() throws Exception {
        for (String probe : new String[] {
                "foo.",
                "/foo.",
                "/sdcard/foo.",
        }) {
            assertEquals(probe, "", extractFileExtension(probe));
        }
    }

    @Test
    public void testSanitizeValues() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.RELATIVE_PATH, "path/in\0valid/data/");
        values.put(MediaColumns.DISPLAY_NAME, "inva\0lid");
        FileUtils.sanitizeValues(values, /*rewriteHiddenFileName*/ true);
        assertEquals("path/in_valid/data/", values.get(MediaColumns.RELATIVE_PATH));
        assertEquals("inva_lid", values.get(MediaColumns.DISPLAY_NAME));
    }

    @Test
    public void testSanitizeValues_Root() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.RELATIVE_PATH, "/");
        FileUtils.sanitizeValues(values, /*rewriteHiddenFileName*/ true);
        assertEquals("/", values.get(MediaColumns.RELATIVE_PATH));
    }

    @Test
    public void testSanitizeValues_HiddenFile() throws Exception {
        final String hiddenDirectoryPath = ".hiddenDirectory/";
        final String hiddenFileName = ".hiddenFile";
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.RELATIVE_PATH, hiddenDirectoryPath);
        values.put(MediaColumns.DISPLAY_NAME, hiddenFileName);

        FileUtils.sanitizeValues(values, /*rewriteHiddenFileName*/ false);
        assertEquals(hiddenDirectoryPath, values.get(MediaColumns.RELATIVE_PATH));
        assertEquals(hiddenFileName, values.get(MediaColumns.DISPLAY_NAME));

        FileUtils.sanitizeValues(values, /*rewriteHiddenFileName*/ true);
        assertEquals("_" + hiddenDirectoryPath, values.get(MediaColumns.RELATIVE_PATH));
        assertEquals("_" + hiddenFileName, values.get(MediaColumns.DISPLAY_NAME));
    }

    @Test
    public void testComputeDateExpires_None() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DATE_EXPIRES, 1577836800L);

        FileUtils.computeDateExpires(values);
        assertFalse(values.containsKey(MediaColumns.DATE_EXPIRES));
    }

    @Test
    public void testComputeDateExpires_Pending_Set() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_PENDING, 1);
        values.put(MediaColumns.DATE_EXPIRES, 1577836800L);

        FileUtils.computeDateExpires(values);
        final long target = (System.currentTimeMillis()
                + FileUtils.DEFAULT_DURATION_PENDING) / 1_000;
        Truth.assertThat(values.getAsLong(MediaColumns.DATE_EXPIRES))
                .isIn(Range.closed(target - 5, target + 5));
    }

    @Test
    public void testComputeDateExpires_Pending_Clear() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_PENDING, 0);
        values.put(MediaColumns.DATE_EXPIRES, 1577836800L);

        FileUtils.computeDateExpires(values);
        assertTrue(values.containsKey(MediaColumns.DATE_EXPIRES));
        assertNull(values.get(MediaColumns.DATE_EXPIRES));
    }

    @Test
    public void testComputeDateExpires_Trashed_Set() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 1);
        values.put(MediaColumns.DATE_EXPIRES, 1577836800L);

        FileUtils.computeDateExpires(values);
        final long target = (System.currentTimeMillis()
                + FileUtils.DEFAULT_DURATION_TRASHED) / 1_000;
        Truth.assertThat(values.getAsLong(MediaColumns.DATE_EXPIRES))
                .isIn(Range.closed(target - 5, target + 5));
    }

    @Test
    public void testComputeDateExpires_Trashed_Clear() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 0);
        values.put(MediaColumns.DATE_EXPIRES, 1577836800L);

        FileUtils.computeDateExpires(values);
        assertTrue(values.containsKey(MediaColumns.DATE_EXPIRES));
        assertNull(values.get(MediaColumns.DATE_EXPIRES));
    }

    @Test
    public void testComputeDataFromValues_Trashed_trimFileName() throws Exception {
        testComputeDataFromValues_withAction_trimFileName(MediaColumns.IS_TRASHED);
    }

    @Test
    public void testComputeDataFromValues_Pending_trimFileName() throws Exception {
        testComputeDataFromValues_withAction_trimFileName(MediaColumns.IS_PENDING);
    }

    @Test
    public void testGetTopLevelNoMedia_CurrentDir() throws Exception {
        File dirInDownload = getNewDirInDownload("testGetTopLevelNoMedia_CurrentDir");
        File nomedia = new File(dirInDownload, ".nomedia");
        assertTrue(nomedia.createNewFile());

        assertEquals(dirInDownload, FileUtils.getTopLevelNoMedia(new File(dirInDownload, "foo")));
    }

    @Test
    public void testGetTopLevelNoMedia_TopDir() throws Exception {
        File topDirInDownload = getNewDirInDownload("testGetTopLevelNoMedia_TopDir");
        File topNomedia = new File(topDirInDownload, ".nomedia");
        assertTrue(topNomedia.createNewFile());

        File dirInTopDirInDownload = new File(topDirInDownload, "foo");
        assertTrue(dirInTopDirInDownload.mkdirs());
        File nomedia = new File(dirInTopDirInDownload, ".nomedia");
        assertTrue(nomedia.createNewFile());

        assertEquals(topDirInDownload,
                FileUtils.getTopLevelNoMedia(new File(dirInTopDirInDownload, "foo")));
    }

    @Test
    public void testGetTopLevelNoMedia_NoDir() throws Exception {
        File topDirInDownload = getNewDirInDownload("testGetTopLevelNoMedia_NoDir");
        File dirInTopDirInDownload = new File(topDirInDownload, "foo");
        assertTrue(dirInTopDirInDownload.mkdirs());

        assertEquals(null,
                FileUtils.getTopLevelNoMedia(new File(dirInTopDirInDownload, "foo")));
    }

    @Test
    public void testDirectoryDirty() throws Exception {
        File dirInDownload = getNewDirInDownload("testDirectoryDirty");

        // All directories are considered dirty, unless hidden
        assertTrue(FileUtils.isDirectoryDirty(dirInDownload));

        // Marking a directory as clean has no effect without a .nomedia file
        FileUtils.setDirectoryDirty(dirInDownload, false);
        assertTrue(FileUtils.isDirectoryDirty(dirInDownload));

        // Creating an empty .nomedia file still keeps a directory dirty
        File nomedia = new File(dirInDownload, ".nomedia");
        assertTrue(nomedia.createNewFile());
        assertTrue(FileUtils.isDirectoryDirty(dirInDownload));

        // Marking as clean with a .nomedia file works
        FileUtils.setDirectoryDirty(dirInDownload, false);
        assertFalse(FileUtils.isDirectoryDirty(dirInDownload));

        // Marking as dirty with a .nomedia file works
        FileUtils.setDirectoryDirty(dirInDownload, true);
        assertTrue(FileUtils.isDirectoryDirty(dirInDownload));
    }

    @Test
    public void testExtractPathOwnerPackageName() {
        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Android/data/foo"))
                .isEqualTo("foo");
        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Android/obb/foo"))
                .isEqualTo("foo");
        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Android/media/foo"))
                .isEqualTo("foo");
        assertThat(extractPathOwnerPackageName("/storage/ABCD-1234/Android/data/foo"))
                .isEqualTo("foo");
        assertThat(extractPathOwnerPackageName("/storage/ABCD-1234/Android/obb/foo"))
                .isEqualTo("foo");
        assertThat(extractPathOwnerPackageName("/storage/ABCD-1234/Android/media/foo"))
                .isEqualTo("foo");

        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Android/data")).isNull();
        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Android/obb")).isNull();
        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Android/media")).isNull();
        assertThat(extractPathOwnerPackageName("/storage/ABCD-1234/Android/media")).isNull();
        assertThat(extractPathOwnerPackageName("/storage/emulated/0/Pictures/foo")).isNull();
        assertThat(extractPathOwnerPackageName("Android/data")).isNull();
        assertThat(extractPathOwnerPackageName("Android/obb")).isNull();
    }

    @Test
    public void testExtractOwnerPackageNameFromRelativePath() {
        assertThat(extractOwnerPackageNameFromRelativePath("Android/data/foo")).isEqualTo("foo");
        assertThat(extractOwnerPackageNameFromRelativePath("Android/obb/foo")).isEqualTo("foo");
        assertThat(extractOwnerPackageNameFromRelativePath("Android/media/foo")).isEqualTo("foo");
        assertThat(extractOwnerPackageNameFromRelativePath("Android/media/foo.com/files"))
                .isEqualTo("foo.com");

        assertThat(extractOwnerPackageNameFromRelativePath("/storage/emulated/0/Android/data/foo"))
                .isNull();
        assertThat(extractOwnerPackageNameFromRelativePath("Android/data")).isNull();
        assertThat(extractOwnerPackageNameFromRelativePath("Android/obb")).isNull();
        assertThat(extractOwnerPackageNameFromRelativePath("Android/media")).isNull();
        assertThat(extractOwnerPackageNameFromRelativePath("Pictures/foo")).isNull();
    }

    @Test
    public void testIsDataOrObbPath() {
        assertThat(isDataOrObbPath("/storage/emulated/0/Android/data")).isTrue();
        assertThat(isDataOrObbPath("/storage/emulated/0/Android/obb")).isTrue();
        assertThat(isDataOrObbPath("/storage/ABCD-1234/Android/data")).isTrue();
        assertThat(isDataOrObbPath("/storage/ABCD-1234/Android/obb")).isTrue();

        assertThat(isDataOrObbPath("/storage/emulated/0/Android/data/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0/Android/obb/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/ABCD-1234/Android/data/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/ABCD-1234/Android/obb/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/10/Android/obb/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated//Android/obb/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated//Android/obb")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0//Android/obb")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0//Android/obb/foo")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0/Android/")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0/Android/media/")).isFalse();
        assertThat(isDataOrObbPath("/storage/ABCD-1234/Android/media/")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0/Pictures/")).isFalse();
        assertThat(isDataOrObbPath("/storage/ABCD-1234/Android/obbfoo")).isFalse();
        assertThat(isDataOrObbPath("/storage/emulated/0/Android/datafoo")).isFalse();
        assertThat(isDataOrObbPath("Android/")).isFalse();
        assertThat(isDataOrObbPath("Android/media/")).isFalse();
    }

    @Test
    public void testIsDataOrObbRelativePath() {
        assertThat(isDataOrObbRelativePath("Android/data")).isTrue();
        assertThat(isDataOrObbRelativePath("Android/obb")).isTrue();
        assertThat(isDataOrObbRelativePath("Android/data/foo")).isTrue();
        assertThat(isDataOrObbRelativePath("Android/obb/foo")).isTrue();

        assertThat(isDataOrObbRelativePath("/storage/emulated/0/Android/data")).isFalse();
        assertThat(isDataOrObbRelativePath("Android/")).isFalse();
        assertThat(isDataOrObbRelativePath("Android/media/")).isFalse();
        assertThat(isDataOrObbRelativePath("Pictures/")).isFalse();
    }

    @Test
    public void testIsObbOrChildRelativePath() {
        assertThat(isObbOrChildRelativePath("Android/obb")).isTrue();
        assertThat(isObbOrChildRelativePath("Android/obb/")).isTrue();
        assertThat(isObbOrChildRelativePath("Android/obb/foo.com")).isTrue();

        assertThat(isObbOrChildRelativePath("/storage/emulated/0/Android/obb")).isFalse();
        assertThat(isObbOrChildRelativePath("/storage/emulated/0/Android/")).isFalse();
        assertThat(isObbOrChildRelativePath("Android/")).isFalse();
        assertThat(isObbOrChildRelativePath("Android/media/")).isFalse();
        assertThat(isObbOrChildRelativePath("Pictures/")).isFalse();
        assertThat(isObbOrChildRelativePath("Android/obbfoo")).isFalse();
        assertThat(isObbOrChildRelativePath("Android/data")).isFalse();
    }

    private File getNewDirInDownload(String name) {
        File file = new File(mTestDownloadDir, name);
        assertTrue(file.mkdir());
        return file;
    }

    private static File touch(File dir, String name) throws IOException {
        final File res = new File(dir, name);
        res.createNewFile();
        return res;
    }

    private static void assertNameEquals(String expected, File actual) {
        assertEquals(expected, actual.getName());
    }

    private void assertDirContents(String... expected) {
        final HashSet<String> expectedSet = new HashSet<>(Arrays.asList(expected));
        String[] actual = mDeleteTarget.list();
        if (actual == null) actual = new String[0];

        assertEquals(
                "Expected " + Arrays.toString(expected) + " but actual " + Arrays.toString(actual),
                expected.length, actual.length);
        for (String actualFile : actual) {
            assertTrue("Unexpected actual file " + actualFile, expectedSet.contains(actualFile));
        }
    }

    public static String createExtremeFileName(String prefix, String extension) {
        // create extreme long file name
        final int prefixLength = prefix.length();
        final int extensionLength = extension.length();
        StringBuilder str = new StringBuilder(prefix);
        for (int i = 0; i < (MAX_FILENAME_BYTES - prefixLength - extensionLength); i++) {
            str.append(i % 10);
        }
        return str.append(extension).toString();
    }

    private void testComputeDataFromValues_withAction_trimFileName(String columnKey) {
        final String originalName = createExtremeFileName("test", ".jpg");
        final String volumePath = "/storage/emulated/0/";
        final ContentValues values = new ContentValues();
        values.put(columnKey, 1);
        values.put(MediaColumns.RELATIVE_PATH, "DCIM/My Vacation/");
        values.put(MediaColumns.DATE_EXPIRES, 1577836800L);
        values.put(MediaColumns.DISPLAY_NAME, originalName);

        FileUtils.computeDataFromValues(values, new File(volumePath), false /* isForFuse */);

        final String data = values.getAsString(MediaColumns.DATA);
        final String result = FileUtils.extractDisplayName(data);
        // after adding the prefix .pending-timestamp or .trashed-timestamp,
        // the largest length of the file name is MAX_FILENAME_BYTES 255
        Truth.assertThat(result.length()).isAtMost(MAX_FILENAME_BYTES);
        Truth.assertThat(result).isNotEqualTo(originalName);
    }

    @Test
    public void testIsExternalMediaDirectory() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0/AppClone/",
                "/storage/0000-0000/AppClone/"
        }) {
            assertTrue(isExternalMediaDirectory(prefix + "Android/media/foo.jpg", "AppClone"));
            assertFalse(isExternalMediaDirectory(prefix + "Android/media/foo.jpg", "NotAppClone"));
        }
    }

    @Test
    public void testComputeDataFromValuesForValidPath_success() {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.RELATIVE_PATH, "Android/media/com.example");
        values.put(MediaColumns.DISPLAY_NAME, "./../../abc.txt");

        FileUtils.computeDataFromValues(values, new File("/storage/emulated/0"), false);

        assertThat(values.getAsString(MediaColumns.DATA)).isEqualTo(
                "/storage/emulated/0/Android/abc.txt");
    }

    @Test
    public void testComputeDataFromValuesForInvalidPath_throwsIllegalArgumentException() {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.RELATIVE_PATH, "\0");
        values.put(MediaColumns.DISPLAY_NAME, "./../../abc.txt");

        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.computeDataFromValues(values, new File("/storage/emulated/0"),
                        false));
    }
}
