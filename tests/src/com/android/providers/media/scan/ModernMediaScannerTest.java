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

package com.android.providers.media.scan;

import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static com.android.providers.media.scan.MediaScanner.REASON_IDLE;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.android.providers.media.scan.ModernMediaScanner.MAX_EXCLUDE_DIRS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.tests.utils.Timer;
import com.android.providers.media.util.FileUtils;

import com.google.common.io.ByteStreams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class ModernMediaScannerTest {
    // TODO: scan directory-vs-files and confirm identical results

    private static final String TAG = "ModernMediaScannerTest";
    /**
     * Number of times we should repeat an operation to get an average/max.
     */
    private static final int COUNT_REPEAT = 5;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private File mDir;

    private Context mIsolatedContext;
    private ContentResolver mIsolatedResolver;

    private ModernMediaScanner mModern;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);

        mDir = new File(context.getExternalMediaDirs()[0], "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);

        mIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        mIsolatedResolver = mIsolatedContext.getContentResolver();

        mModern = new ModernMediaScanner(mIsolatedContext, new TestConfigStore());
    }

    @After
    public void tearDown() {
        FileUtils.deleteContents(mDir);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testSimple() throws Exception {
        assertNotNull(mModern.getContext());
    }

    @Test
    public void testOverrideMimeType() throws Exception {
        assertFalse(mModern.parseOptionalMimeType("image/png", null).isPresent());
        assertFalse(mModern.parseOptionalMimeType("image/png", "image").isPresent());
        assertFalse(mModern.parseOptionalMimeType("image/png", "im/im").isPresent());
        assertFalse(mModern.parseOptionalMimeType("image/png", "audio/x-shiny").isPresent());

        assertTrue(mModern.parseOptionalMimeType("image/png", "image/x-shiny").isPresent());
        assertEquals("image/x-shiny",
                mModern.parseOptionalMimeType("image/png", "image/x-shiny").get());

        // Radical file type shifting isn't allowed
        assertEquals(Optional.empty(),
                mModern.parseOptionalMimeType("video/mp4", "audio/mpeg"));
    }

    @Test
    public void testParseOptional() throws Exception {
        assertFalse(mModern.parseOptional(null).isPresent());
        assertFalse(mModern.parseOptional("").isPresent());
        assertFalse(mModern.parseOptional(" ").isPresent());
        assertFalse(mModern.parseOptional("-1").isPresent());

        assertFalse(mModern.parseOptional(-1).isPresent());
        assertTrue(mModern.parseOptional(0).isPresent());
        assertTrue(mModern.parseOptional(1).isPresent());

        assertEquals("meow", mModern.parseOptional("meow").get());
        assertEquals(42, (int) mModern.parseOptional(42).get());
    }

    @Test
    public void testParseOptionalOrZero() throws Exception {
        assertFalse(mModern.parseOptionalOrZero(-1).isPresent());
        assertFalse(mModern.parseOptionalOrZero(0).isPresent());
        assertTrue(mModern.parseOptionalOrZero(1).isPresent());
    }

    @Test
    public void testParseOptionalNumerator() throws Exception {
        assertEquals(12, (int) mModern.parseOptionalNumerator("12").get());
        assertEquals(12, (int) mModern.parseOptionalNumerator("12/24").get());

        assertFalse(mModern.parseOptionalNumerator("/24").isPresent());
    }

    @Test
    public void testParseOptionalOrientation() throws Exception {
        assertEquals(0,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_NORMAL).get());
        assertEquals(90,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_ROTATE_90).get());
        assertEquals(180,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_ROTATE_180).get());
        assertEquals(270,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_ROTATE_270).get());

        assertEquals(0,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
                        .get());
        assertEquals(90,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_TRANSPOSE).get());
        assertEquals(180,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_FLIP_VERTICAL)
                        .get());
        assertEquals(270,
                (int) mModern.parseOptionalOrientation(ExifInterface.ORIENTATION_TRANSVERSE).get());
    }

    @Test
    public void testParseOptionalImageResolution() throws Exception {
        final MediaMetadataRetriever mmr = mock(MediaMetadataRetriever.class);
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)))
                .thenReturn("640");
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)))
                .thenReturn("480");
        assertEquals("640\u00d7480", mModern.parseOptionalImageResolution(mmr).get());
    }

    @Test
    public void testParseOptionalVideoResolution() throws Exception {
        final MediaMetadataRetriever mmr = mock(MediaMetadataRetriever.class);
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)))
                .thenReturn("640");
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)))
                .thenReturn("480");
        assertEquals("640\u00d7480", mModern.parseOptionalVideoResolution(mmr).get());
    }

    @Test
    public void testParseOptionalResolution() throws Exception {
        final ExifInterface exif = mock(ExifInterface.class);
        when(exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)).thenReturn("640");
        when(exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)).thenReturn("480");
        assertEquals("640\u00d7480", mModern.parseOptionalResolution(exif).get());
    }

    @Test
    public void testParseOptionalDate() throws Exception {
        assertThat(mModern.parseOptionalDate("20200101T000000"))
                .isEqualTo(Optional.of(1577836800000L));
        assertThat(mModern.parseOptionalDate("20200101T211205"))
                .isEqualTo(Optional.of(1577913125000L));
        assertThat(mModern.parseOptionalDate("20200101T211205.000Z"))
                .isEqualTo(Optional.of(1577913125000L));
        assertThat(mModern.parseOptionalDate("20200101T211205.123Z"))
                .isEqualTo(Optional.of(1577913125123L));
    }

    @Test
    public void testParseOptionalTrack() throws Exception {
        final MediaMetadataRetriever mmr = mock(MediaMetadataRetriever.class);
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)))
                .thenReturn("1/2");
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)))
                .thenReturn("4/12");
        assertEquals(1004, (int) mModern.parseOptionalTrack(mmr).get());
    }

    @Test
    public void testParseDateTaken_Complete() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // Offset is recorded, test both zeros
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-00:00");
        assertEquals(1453972654000L, (long) mModern.parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
        assertEquals(1453972654000L, (long) mModern.parseOptionalDateTaken(exif, 0L).get());

        // Offset is recorded, test both directions
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-07:00");
        assertEquals(1453972654000L + 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+07:00");
        assertEquals(1453972654000L - 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 0L).get());
    }

    @Test
    public void testParseDateTaken_Gps() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // GPS tells us we're in UTC
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:14:00");
        assertEquals(1453972654000L, (long) mModern.parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:20:00");
        assertEquals(1453972654000L, (long) mModern.parseOptionalDateTaken(exif, 0L).get());

        // GPS tells us we're in -7
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "16:14:00");
        assertEquals(1453972654000L + 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "16:20:00");
        assertEquals(1453972654000L + 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 0L).get());

        // GPS tells us we're in +7
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "02:14:00");
        assertEquals(1453972654000L - 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "02:20:00");
        assertEquals(1453972654000L - 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 0L).get());

        // GPS beyond 24 hours isn't helpful
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:27");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:17:34");
        assertFalse(mModern.parseOptionalDateTaken(exif, 0L).isPresent());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:29");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:17:34");
        assertFalse(mModern.parseOptionalDateTaken(exif, 0L).isPresent());
    }

    @Test
    public void testParseDateTaken_File() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // Modified tells us we're in UTC
        assertEquals(1453972654000L,
                (long) mModern.parseOptionalDateTaken(exif, 1453972654000L - 60000L).get());
        assertEquals(1453972654000L,
                (long) mModern.parseOptionalDateTaken(exif, 1453972654000L + 60000L).get());

        // Modified tells us we're in -7
        assertEquals(1453972654000L + 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 1453972654000L + 25200000L - 60000L)
                        .get());
        assertEquals(1453972654000L + 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 1453972654000L + 25200000L + 60000L)
                        .get());

        // Modified tells us we're in +7
        assertEquals(1453972654000L - 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 1453972654000L - 25200000L - 60000L)
                        .get());
        assertEquals(1453972654000L - 25200000L,
                (long) mModern.parseOptionalDateTaken(exif, 1453972654000L - 25200000L + 60000L)
                        .get());

        // Modified beyond 24 hours isn't helpful
        assertFalse(mModern.parseOptionalDateTaken(exif, 1453972654000L - 86400000L).isPresent());
        assertFalse(mModern.parseOptionalDateTaken(exif, 1453972654000L + 86400000L).isPresent());
    }

    @Test
    public void testParseDateTaken_Hopeless() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // Offset is completely missing, and no useful GPS or modified time
        assertFalse(mModern.parseOptionalDateTaken(exif, 0L).isPresent());
    }

    @Test
    public void testParseYear_Invalid() throws Exception {
        assertEquals(Optional.empty(), mModern.parseOptionalYear(null));
        assertEquals(Optional.empty(), mModern.parseOptionalYear(""));
        assertEquals(Optional.empty(), mModern.parseOptionalYear(" "));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("meow"));

        assertEquals(Optional.empty(), mModern.parseOptionalYear("0"));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("00"));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("000"));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("0000"));

        assertEquals(Optional.empty(), mModern.parseOptionalYear("1"));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("01"));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("001"));
        assertEquals(Optional.empty(), mModern.parseOptionalYear("0001"));

        // No sane way to determine year from two-digit date formats
        assertEquals(Optional.empty(), mModern.parseOptionalYear("01-01-01"));

        // Specific example from partner
        assertEquals(Optional.empty(), mModern.parseOptionalYear("000 "));
    }

    @Test
    public void testParseYear_Valid() throws Exception {
        assertEquals(Optional.of(1900), mModern.parseOptionalYear("1900"));
        assertEquals(Optional.of(2020), mModern.parseOptionalYear("2020"));
        assertEquals(Optional.of(2020), mModern.parseOptionalYear(" 2020 "));
        assertEquals(Optional.of(2020), mModern.parseOptionalYear("01-01-2020"));

        // Specific examples from partner
        assertEquals(Optional.of(1984),
                mModern.parseOptionalYear("1984-06-26T07:00:00Z"));
        assertEquals(Optional.of(2016),
                mModern.parseOptionalYear("Thu, 01 Sep 2016 10:11:12.123456 -0500"));
    }

    private void assertShouldScanPathAndIsPathHidden(boolean isScannable, boolean isHidden,
        File dir) {
        Pair<Boolean, Boolean> actual = mModern.shouldScanPathAndIsPathHidden(dir);
        assertWithMessage("assert should scan for dir: " + dir.getAbsolutePath())
            .that(actual.first)
            .isEqualTo(isScannable);
        assertWithMessage("assert is hidden for dir: " + dir.getAbsolutePath())
            .that(actual.second)
            .isEqualTo(isHidden);
    }

    @Test
    public void testShouldScanPathAndIsPathHidden() {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/0000-0000",
        }) {
            assertShouldScanPathAndIsPathHidden(true, false, new File(prefix));
            assertShouldScanPathAndIsPathHidden(true, false, new File(prefix + "/meow"));
            assertShouldScanPathAndIsPathHidden(true, false, new File(prefix + "/Android/meow"));

            assertShouldScanPathAndIsPathHidden(true, true, new File(prefix + "/.meow/dir"));

            assertShouldScanPathAndIsPathHidden(false, false,
                    new File(prefix + "/Android/data/meow"));
            assertShouldScanPathAndIsPathHidden(false, false,
                    new File(prefix + "/Android/obb/meow"));

            // When the path is not scannable, we don't care if it's hidden or not.
            assertShouldScanPathAndIsPathHidden(false, false,
                    new File(prefix + "/Pictures/.thumbnails/meow"));
            assertShouldScanPathAndIsPathHidden(false, false,
                    new File(prefix + "/Movies/.thumbnails/meow"));
            assertShouldScanPathAndIsPathHidden(false, false,
                    new File(prefix + "/Music/.thumbnails/meow"));

            assertShouldScanPathAndIsPathHidden(false, false,
                    new File(prefix + "/.transforms/transcode"));
        }
    }

    private void assertVisibleFolder(File dir) throws Exception {
        final File nomediaFile = new File(dir, ".nomedia");

        if (!nomediaFile.getParentFile().exists()) {
            assertWithMessage("cannot create dir: " + nomediaFile.getParentFile().getAbsolutePath())
                .that(nomediaFile.getParentFile().mkdirs())
                .isTrue();
        }
        try {
            if (!nomediaFile.exists()) {
                executeShellCommand("touch " + nomediaFile.getAbsolutePath());
                assertWithMessage("cannot create nomedia file: " + nomediaFile.getAbsolutePath())
                    .that(nomediaFile.exists())
                    .isTrue();
            }
            assertShouldScanPathAndIsPathHidden(true, false, dir);
        } finally {
            executeShellCommand("rm " + nomediaFile.getAbsolutePath());
        }
    }

    /**
     * b/168830497: Test that root folder, default folders and Camera folder are always visible
     */
    @Test
    public void testVisibleDefaultFolders() throws Exception {
        final File root = new File("storage/emulated/0");

        assertVisibleFolder(root);

        // Top level directories should always be visible
        for (String dirName : FileUtils.DEFAULT_FOLDER_NAMES) {
            final File defaultFolder = new File(root, dirName);
            assertVisibleFolder(defaultFolder);
        }

        // DCIM/Camera should always be visible
        final File cameraDir = new File(root, Environment.DIRECTORY_DCIM + "/" + "Camera");
        assertVisibleFolder(cameraDir);

        // Screenshots should always be visible
        for (String dirName : FileUtils.DEFAULT_FOLDER_NAMES) {
            File screenshotsDir = new File(root, dirName + "/" + Environment.DIRECTORY_SCREENSHOTS);
            assertVisibleFolder(screenshotsDir);
        }
    }

    /**
     *  b/192799231: Test that root folder which has .nomedia directory is always visible
     */
    @Test
    public void testVisibleRootWithNoMediaDirectory() throws Exception {
        final File root = new File("storage/emulated/0");
        final File nomediaDir = new File(root, ".nomedia");
        final File file = new File(nomediaDir, "test.jpg");

        try {
            if (!nomediaDir.exists()) {
                executeShellCommand("mkdir -p " + nomediaDir.getAbsolutePath());
            }
            if (!file.exists()) {
                executeShellCommand("touch " + file.getAbsolutePath());
                assertTrue(file.exists());
            }
            assertShouldScanPathAndIsPathHidden(true, false, root);
        } finally {
            executeShellCommand("rm -rf " + nomediaDir.getAbsolutePath());
        }
    }

    private void assertShouldScanDirectory(File file) {
        assertTrue(file.getAbsolutePath(), mModern.shouldScanDirectory(file));
    }

    private void assertShouldntScanDirectory(File file) {
        assertFalse(file.getAbsolutePath(), mModern.shouldScanDirectory(file));
    }

    @Test
    public void testShouldScanDirectory() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/0000-0000",
        }) {
            assertShouldScanDirectory(new File(prefix));
            assertShouldScanDirectory(new File(prefix + "/meow"));
            assertShouldScanDirectory(new File(prefix + "/Android"));
            assertShouldScanDirectory(new File(prefix + "/Android/meow"));
            assertShouldScanDirectory(new File(prefix + "/.meow"));

            assertShouldntScanDirectory(new File(prefix + "/Android/data"));
            assertShouldntScanDirectory(new File(prefix + "/Android/obb"));
            assertShouldntScanDirectory(new File(prefix + "/Android/sandbox"));

            assertShouldntScanDirectory(new File(prefix + "/Pictures/.thumbnails"));
            assertShouldntScanDirectory(new File(prefix + "/Movies/.thumbnails"));
            assertShouldntScanDirectory(new File(prefix + "/Music/.thumbnails"));

            assertShouldScanDirectory(new File(prefix + "/DCIM/.thumbnails"));
            assertShouldntScanDirectory(new File(prefix + "/.transforms"));
        }
    }

    @Test
    public void testIsZero() throws Exception {
        assertFalse(mModern.isZero(""));
        assertFalse(mModern.isZero("meow"));
        assertFalse(mModern.isZero("1"));
        assertFalse(mModern.isZero("01"));
        assertFalse(mModern.isZero("010"));

        assertTrue(mModern.isZero("0"));
        assertTrue(mModern.isZero("00"));
        assertTrue(mModern.isZero("000"));
    }

    @Test
    public void testFilter() throws Exception {
        stageMusicFile(R.raw.test_audio, "example.mp3");

        // Exact matches
        assertQueryCount(1, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "artist").build());
        assertQueryCount(1, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "album").build());
        assertQueryCount(1, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "title").build());

        // Partial matches mid-string
        assertQueryCount(1, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "ArT").build());

        // Filter should only apply to narrow collection type
        assertQueryCount(0, MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "title").build());

        // Other unrelated search terms
        assertQueryCount(0, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "example").build());
        assertQueryCount(0, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendQueryParameter("filter", "チ").build());
    }

    @Test
    public void testScan_Common() throws Exception {
        final File file = new File(mDir, "red.jpg");
        stage(R.raw.test_image, file);

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        // Confirm that we found new image and scanned it
        final Uri uri;
        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndex(MediaColumns._ID)));
            assertEquals(1280, cursor.getLong(cursor.getColumnIndex(MediaColumns.WIDTH)));
            assertEquals(720, cursor.getLong(cursor.getColumnIndex(MediaColumns.HEIGHT)));
        }

        // Write a totally different image and confirm that we automatically
        // rescanned it
        try (ParcelFileDescriptor pfd = mIsolatedResolver.openFile(uri, "wt", null)) {
            final Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90,
                    new FileOutputStream(pfd.getFileDescriptor()));
        }

        // Make sure out pending scan has finished
        MediaStore.waitForIdle(mIsolatedResolver);

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(32, cursor.getLong(cursor.getColumnIndex(MediaColumns.WIDTH)));
            assertEquals(32, cursor.getLong(cursor.getColumnIndex(MediaColumns.HEIGHT)));
        }

        // Delete raw file and confirm it's cleaned up
        file.delete();
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    /**
     * All file formats are thoroughly tested by {@code CtsMediaProviderTestCases},
     * but to prove code coverage we also need to exercise manually here with a
     * bare-bones scan operation.
     */
    @Test
    public void testScan_Coverage() throws Exception {
        stage(R.raw.test_audio, new File(mDir, "audio.mp3"));
        stage(R.raw.test_video, new File(mDir, "video.mp4"));
        stage(R.raw.test_image, new File(mDir, "image.jpg"));
        stage(R.raw.test_m3u, new File(mDir, "playlist.m3u"));
        stage(R.raw.test_srt, new File(mDir, "subtitle.srt"));
        stage(R.raw.test_txt, new File(mDir, "document.txt"));
        stage(R.raw.test_bin, new File(mDir, "random.bin"));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);
    }

    @Test
    public void testScan_missingDir() throws Exception {
        File newDir = new File(mDir, "new-dir");
        // Below shouldn't crash
        mModern.scanDirectory(newDir, REASON_UNKNOWN);

        newDir = new File(Environment.getStorageDirectory(), "new-dir");
        // Below shouldn't crash
        mModern.scanDirectory(newDir, REASON_UNKNOWN);
    }

    @Test
    public void testScan_Nomedia_Dir() throws Exception {
        final File redDir = new File(mDir, "red");
        final File blueDir = new File(mDir, "blue");
        redDir.mkdirs();
        blueDir.mkdirs();
        stage(R.raw.test_image, new File(redDir, "red.jpg"));
        stage(R.raw.test_image, new File(blueDir, "blue.jpg"));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        // We should have found both images
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Hide one directory, rescan, and confirm hidden
        final File redNomedia = new File(redDir, ".nomedia");
        redNomedia.createNewFile();
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(1, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        assertThat(FileUtils.readString(redNomedia)).isEqualTo(Optional.of(redDir.getPath()));

        // Unhide, rescan, and confirm visible again
        redNomedia.delete();
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    @Test
    public void testScan_MaxExcludeNomediaDirs_DoesNotThrowException() throws Exception {
        // Create MAX_EXCLUDE_DIRS + 50 nomedia dirs in mDir
        // (Need to add 50 as MAX_EXCLUDE_DIRS is a safe limit;
        // 499 would have been too close to the exception limit)
        // Mark them as non-dirty so that they are excluded from scans
        for (int i = 0 ; i < (MAX_EXCLUDE_DIRS + 50) ; i++) {
            createCleanNomediaDir(mDir);
        }

        final File redDir = new File(mDir, "red");
        redDir.mkdirs();
        stage(R.raw.test_image, new File(redDir, "red.jpg"));

        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(1, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    private void createCleanNomediaDir(File dir) throws Exception {
        final File nomediaDir = new File(dir, "test_" + System.nanoTime());
        nomediaDir.mkdirs();
        final File nomedia = new File(nomediaDir, ".nomedia");
        nomedia.createNewFile();

        FileUtils.setDirectoryDirty(nomediaDir, false);
        assertThat(FileUtils.isDirectoryDirty(nomediaDir)).isFalse();
    }

    @Test
    public void testScan_Nomedia_File() throws Exception {
        final File image = new File(mDir, "image.jpg");
        final File nomedia = new File(mDir, ".nomedia");
        stage(R.raw.test_image, image);
        nomedia.createNewFile();

        // Direct scan with nomedia will change media type to MEDIA_TYPE_NONE
        assertNotNull(mModern.scanFile(image, REASON_UNKNOWN));
        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Direct scan without nomedia means image
        nomedia.delete();
        assertNotNull(mModern.scanFile(image, REASON_UNKNOWN));
        assertQueryCount(1, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Direct scan again changes the media type to MEDIA_TYPE_NONE
        nomedia.createNewFile();
        assertNotNull(mModern.scanFile(image, REASON_UNKNOWN));
        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    @Test
    public void testScan_missingFile() throws Exception {
        File image = new File(mDir, "image.jpg");
        assertThat(mModern.scanFile(image, REASON_UNKNOWN)).isNull();

        image = new File(Environment.getStorageDirectory(), "image.jpg");
        assertThat(mModern.scanFile(image, REASON_UNKNOWN)).isNull();
    }

    /**
     * Verify fix for obscure bug which would cause us to delete files outside a
     * directory that share a common prefix.
     */
    @Test
    public void testScan_Prefix() throws Exception {
        final File dir = new File(mDir, "test");
        final File inside = new File(dir, "testfile.jpg");
        final File outside = new File(mDir, "testfile.jpg");

        dir.mkdirs();
        inside.createNewFile();
        outside.createNewFile();

        // Scanning from top means we get both items
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Scanning from middle means we still have both items
        mModern.scanDirectory(dir, REASON_UNKNOWN);
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    private void assertQueryCount(int expected, Uri actualUri) {
        try (Cursor cursor = mIsolatedResolver.query(actualUri, null, null, null, null)) {
            assertEquals(expected, cursor.getCount());
        }
    }

    @Test
    public void testScan_audio_empty_title() throws Exception {
        stageMusicFile(R.raw.test_audio_empty_title, "audio.mp3");

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals("audio", cursor.getString(cursor.getColumnIndex(MediaColumns.TITLE)));
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testScan_audio_recording() throws Exception {
        final File music = new File(mDir, "Recordings");
        final File audio = new File(music, "audio.mp3");

        music.mkdirs();
        stage(R.raw.test_audio, audio);

        mModern.scanFile(audio, REASON_UNKNOWN);

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(1, cursor.getInt(cursor.getColumnIndex(AudioColumns.IS_RECORDING)));
            assertEquals(0, cursor.getInt(cursor.getColumnIndex(AudioColumns.IS_MUSIC)));
        }
    }

    /**
     * Verify a narrow exception where we allow an {@code mp4} video file on
     * disk to be indexed as an {@code m4a} audio file.
     */
    @Test
    public void testScan_148316354() throws Exception {
        final File file = new File(mDir, "148316354.mp4");
        stage(R.raw.test_m4a, file);

        final Uri uri = mModern.scanFile(file, REASON_UNKNOWN);
        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.MIME_TYPE)))
                    .isEqualTo("audio/mp4");
            assertThat(cursor.getString(cursor.getColumnIndex(AudioColumns.IS_MUSIC)))
                    .isEqualTo("1");

        }
    }

    @Test
    public void testScan_audioMp4_notRescanIfUnchanged() throws Exception {
        final File file = new File(mDir, "176522651.m4a");
        stage(R.raw.test_m4a, file);

        // We trigger a scan twice, but we expect the second scan to be skipped since there were
        // no changes.
        mModern.scanFile(file, REASON_UNKNOWN);
        mModern.scanFile(file, REASON_UNKNOWN);

        try (Cursor cursor =
                     mIsolatedResolver.query(
                             MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                             null, null, null, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            String added = cursor.getString(cursor.getColumnIndex(MediaColumns.GENERATION_ADDED));
            String modified =
                    cursor.getString(cursor.getColumnIndex(MediaColumns.GENERATION_MODIFIED));
            assertThat(modified).isEqualTo(added);
        }
    }

    /**
     * If there is a scan action between invoking {@link ContentResolver#insert} and
     * {@link ContentResolver#openFileDescriptor}, it should not raise
     * (@link FileNotFoundException}.
     */
    @Test
    public void testScan_166063754() throws Exception {
        Uri collection = MediaStore.Images.Media
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, mDir.getName() + "_166063754.jpg");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = mIsolatedResolver.insert(collection, values);

        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mModern.scanFile(dir, REASON_UNKNOWN);
        try (ParcelFileDescriptor fd = mIsolatedResolver.openFileDescriptor(uri, "w", null)) {
            assertThat(fd).isNotNull();
        } catch (FileNotFoundException e) {
            throw new AssertionError("Can't open uri " + uri, e);
        }
    }

    @Test
    public void testAlbumArtPattern() throws Exception {
        for (String path : new String[] {
                "/storage/emulated/0/._abc",
                "/storage/emulated/0/a._abc",

                "/storage/emulated/0/AlbumArtSmall.jpg",
                "/storage/emulated/0/albumartsmall.jpg",

                "/storage/emulated/0/AlbumArt_{}_Small.jpg",
                "/storage/emulated/0/albumart_{a}_small.jpg",
                "/storage/emulated/0/AlbumArt_{}_Large.jpg",
                "/storage/emulated/0/albumart_{a}_large.jpg",

                "/storage/emulated/0/Folder.jpg",
                "/storage/emulated/0/folder.jpg",

                "/storage/emulated/0/AlbumArt.jpg",
                "/storage/emulated/0/albumart.jpg",
                "/storage/emulated/0/albumart1.jpg",
        }) {
            final File file = new File(path);
            assertEquals(LegacyMediaScannerTest.isNonMediaFile(path), mModern.isFileAlbumArt(file));
        }

        for (String path : new String[] {
                "/storage/emulated/0/AlbumArtLarge.jpg",
                "/storage/emulated/0/albumartlarge.jpg",
        }) {
            final File file = new File(path);
            assertTrue(mModern.isFileAlbumArt(file));
        }
    }

    @Test
    public void testScan_BitmapFile() throws Exception {
        final File bmp = new File(mDir, "image.bmp");
        stage(R.raw.test_bmp, bmp);

        final Uri uri = mModern.scanFile(bmp, REASON_UNKNOWN);
        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(1280,
                    cursor.getInt(cursor.getColumnIndex(MediaColumns.WIDTH)));
            assertEquals(720,
                    cursor.getInt(cursor.getColumnIndex(MediaColumns.HEIGHT)));
        }
    }

    @Test
    public void testScan_deleteStaleRowWithExpiredPendingFile() throws Exception {
        final String displayName = "audio.mp3";
        final long dateExpires = (System.currentTimeMillis() - 5 * DateUtils.DAY_IN_MILLIS) / 1000;
        final String expiredName = String.format(
                Locale.US, ".%s-%d-%s", FileUtils.PREFIX_PENDING, dateExpires, displayName);
        final File audio = new File(mDir, expiredName);
        stage(R.raw.test_audio, audio);

        // files should exist
        assertThat(audio.exists()).isTrue();

        // scan file, row is added
        mModern.scanFile(audio, REASON_UNKNOWN);
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        // Delete the pending file to make the row is stale.
        audio.delete();
        assertThat(audio.exists()).isFalse();

        // the row still exists
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        mModern.scanFile(audio, REASON_UNKNOWN);

        // ScanFile above deleted stale expired pending row, hence we shouldn't see
        // the pending row in query result
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testScan_keepStaleRowWithNonExpiredPendingFile() throws Exception {
        final String displayName = "audio.mp3";
        final long dateExpires = (System.currentTimeMillis() + 2 * DateUtils.DAY_IN_MILLIS) / 1000;
        final String expiredName = String.format(
                Locale.US, ".%s-%d-%s", FileUtils.PREFIX_PENDING, dateExpires, displayName);
        final File audio = new File(mDir, expiredName);
        stage(R.raw.test_audio, audio);

        // file should exist
        assertThat(audio.exists()).isTrue();

        // scan file, row is added
        mModern.scanFile(audio, REASON_UNKNOWN);
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        // Delete the pending file to make the row is stale
        audio.delete();
        assertThat(audio.exists()).isFalse();

        // the row still exists
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        mModern.scanFile(audio, REASON_UNKNOWN);

        // ScanFile above didn't delete stale pending row which is not expired, hence
        // we still see the pending row in query result
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }
    }

    @Test
    public void testScan_deleteStaleRowWithExpiredTrashedFile() throws Exception {
        final String displayName = "audio.mp3";
        final long dateExpires = (System.currentTimeMillis() - 5 * DateUtils.DAY_IN_MILLIS) / 1000;
        final String expiredName = String.format(
                Locale.US, ".%s-%d-%s", FileUtils.PREFIX_TRASHED, dateExpires, displayName);
        final File audio = new File(mDir, expiredName);
        stage(R.raw.test_audio, audio);

        // file should exist
        assertThat(audio.exists()).isTrue();

        // scan file, row is added
        mModern.scanFile(audio, REASON_UNKNOWN);
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        // Delete the trashed file to make the row is stale
        audio.delete();
        assertThat(audio.exists()).isFalse();

        // the row still exists
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        mModern.scanFile(audio, REASON_UNKNOWN);

        // ScanFile above deleted stale expired trashed row, hence we shouldn't see
        // the trashed row in query result
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testScan_deleteStaleRowWithNonExpiredTrashedFile() throws Exception {
        final String displayName = "audio.mp3";
        final long dateExpires = (System.currentTimeMillis() + 2 * DateUtils.DAY_IN_MILLIS) / 1000;
        final String expiredName = String.format(
                Locale.US, ".%s-%d-%s", FileUtils.PREFIX_TRASHED, dateExpires, displayName);
        final File audio = new File(mDir, expiredName);
        stage(R.raw.test_audio, audio);

        // file should exist
        assertThat(audio.exists()).isTrue();

        // scan file, row is added
        mModern.scanFile(audio, REASON_UNKNOWN);
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        // Delete the trashed file to make the row is stale
        audio.delete();
        assertThat(audio.exists()).isFalse();

        // the row still exists
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        mModern.scanFile(audio, REASON_UNKNOWN);

        // ScanFile above deleted stale trashed row that is not expired, hence we
        // shouldn't see the trashed row in query result
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testScan_deleteStaleRow() throws Exception {
        final String displayName = "audio.mp3";
        final File audio = new File(mDir, displayName);
        stage(R.raw.test_audio, audio);

        // file should exist
        assertThat(audio.exists()).isTrue();

        // scan file, row is added
        mModern.scanFile(audio, REASON_UNKNOWN);

        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, null, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        // Delete the file to make the row is stale
        audio.delete();
        assertThat(audio.exists()).isFalse();

        // the row still exists
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, null, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        mModern.scanFile(audio, REASON_UNKNOWN);

        // ScanFile above deleted stale row, hence we shouldn't see the row in query result
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, null, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    /**
     * Executes a shell command.
     */
    public static String executeShellCommand(String command) throws IOException {
        int attempt = 0;
        while (attempt++ < 5) {
            try {
                return executeShellCommandInternal(command);
            } catch (InterruptedIOException e) {
                // Hmm, we had trouble executing the shell command; the best we
                // can do is try again a few more times
                Log.v(TAG, "Trouble executing " + command + "; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    private static String executeShellCommandInternal(String cmd) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (FileInputStream output = new FileInputStream(
                uiAutomation.executeShellCommand(cmd).getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    @Test
    public void testScan_largeXmpData() throws Exception {
        final File image = new File(mDir, "large_xmp.mp4");
        stage(R.raw.large_xmp, image);
        assertTrue(image.exists());

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] { MediaColumns.XMP }, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(0, cursor.getBlob(0).length);
        }
    }

    @Test
    public void testNoOpScan_NoMediaDirs() throws Exception {
        File nomedia = new File(mDir, ".nomedia");
        assertThat(nomedia.createNewFile()).isTrue();
        for (int i = 0; i < 100; i++) {
            File file = new File(mDir, "file_" + System.nanoTime());
            assertThat(file.createNewFile()).isTrue();
        }
        Timer firstDirScan = new Timer("firstDirScan");
        firstDirScan.start();
        // Time taken : preVisitDirectory + 100 visitFiles
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        firstDirScan.stop();
        firstDirScan.dumpResults();

        // Time taken : preVisitDirectory
        Timer noOpDirScan = new Timer("noOpDirScan1");
        for (int i = 0; i < COUNT_REPEAT; i++) {
            noOpDirScan.start();
            mModern.scanDirectory(mDir, REASON_UNKNOWN);
            noOpDirScan.stop();
        }
        noOpDirScan.dumpResults();
        assertThat(noOpDirScan.getMaxDurationMillis()).isLessThan(
                firstDirScan.getMaxDurationMillis());

        // Creating new file in the nomedia dir by a non-M_E_S app should not set nomedia dir dirty.
        File file = new File(mDir, "file_" + System.nanoTime());
        assertThat(file.createNewFile()).isTrue();

        // The dir should not be dirty and subsequest scans should not scan the entire directory.
        // Time taken : preVisitDirectory
        noOpDirScan = new Timer("noOpDirScan2");
        for (int i = 0; i < COUNT_REPEAT; i++) {
            noOpDirScan.start();
            mModern.scanDirectory(mDir, REASON_UNKNOWN);
            noOpDirScan.stop();
        }
        noOpDirScan.dumpResults();
        assertThat(noOpDirScan.getMaxDurationMillis()).isLessThan(
                firstDirScan.getMaxDurationMillis());
    }

    @Test
    public void testScan_TrackNumber_isStored() throws Exception {
        stageMusicFile(R.raw.test_audio, "audio.mp3");

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(2, cursor.getInt(cursor.getColumnIndex(AudioColumns.TRACK)));
        }
    }

    @Test
    public void testScan_EmptyTrackNumber_isNull() throws Exception {
        stageMusicFile(R.raw.test_audio_empty_track_number, "audio.mp3");

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertThat(cursor.getString(cursor.getColumnIndex(AudioColumns.TRACK))).isNull();
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU, codeName = "Tiramisu")
    @Test
    @EnableFlags({Flags.FLAG_AUDIO_SAMPLE_COLUMNS})
    public void testScan_SampleMetadata_isStored() throws Exception {
        final File musicFolder = new File(mDir, "Music");
        final File audioFile = new File(musicFolder, "audio.wav");

        final ContentValues values = new ContentValues();
        values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        values.put(FileColumns.DATA, audioFile.getAbsolutePath());
        // Rows that have to be rescanned after the schema update
        // should not change "generation_modified"
        values.put(FileColumns._MODIFIER, FileColumns._MODIFIER_SCHEMA_UPDATE);
        Uri audioUri = mIsolatedResolver.insert(
                MediaStore.Audio.Media.getContentUri(VOLUME_EXTERNAL_PRIMARY), values);

        int generationModifiedBeforeScan = getGenerationModified(audioUri);

        musicFolder.mkdirs();
        stage(R.raw.testwav_16bit_44100hz, audioFile);
        mModern.scanFile(audioFile, REASON_IDLE);

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertThat(cursor.getInt(cursor.getColumnIndex(AudioColumns.SAMPLERATE))).isEqualTo(
                    44100);
            assertThat(
                    cursor.getInt(cursor.getColumnIndex(AudioColumns.BITS_PER_SAMPLE))).isEqualTo(
                    16);
            assertThat(cursor.getInt(cursor.getColumnIndex(FileColumns.GENERATION_MODIFIED)))
                    .isEqualTo(generationModifiedBeforeScan);
        }
    }

    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.S_V2)
    @Test
    public void testScan_SampleMetadata_isIgnored() throws Exception {
        stageMusicFile(R.raw.testwav_16bit_44100hz, "audio.wav");
        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertThat(cursor.getString(cursor.getColumnIndex(AudioColumns.SAMPLERATE))).isNull();
            assertThat(
                    cursor.getString(cursor.getColumnIndex(AudioColumns.BITS_PER_SAMPLE))).isNull();
        }
    }

    private void stageMusicFile(int resource, String filename) throws IOException {
        final File musicFolder = new File(mDir, "Music");
        final File audioFile = new File(musicFolder, filename);

        musicFolder.mkdirs();
        stage(resource, audioFile);
        mModern.scanFile(audioFile, REASON_UNKNOWN);
    }

    private int getGenerationModified(Uri uri) {
        Cursor c = mIsolatedResolver.query(uri, new String[]{MediaColumns.GENERATION_MODIFIED},
                null, null);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        return c.getInt(0);
    }
}
