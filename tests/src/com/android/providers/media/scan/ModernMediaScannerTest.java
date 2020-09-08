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

import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.android.providers.media.scan.ModernMediaScanner.shouldScanPathAndIsPathHidden;
import static com.android.providers.media.scan.ModernMediaScanner.isFileAlbumArt;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptional;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalDate;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalDateTaken;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalImageResolution;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalMimeType;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalNumerator;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalOrZero;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalOrientation;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalResolution;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalTrack;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalVideoResolution;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalYear;
import static com.android.providers.media.scan.ModernMediaScanner.shouldScanDirectory;
import static com.android.providers.media.util.FileUtils.isDirectoryHidden;
import static com.android.providers.media.util.FileUtils.isFileHidden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.R;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class ModernMediaScannerTest {
    // TODO: scan directory-vs-files and confirm identical results

    private File mDir;

    private Context mIsolatedContext;
    private ContentResolver mIsolatedResolver;

    private ModernMediaScanner mModern;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);

        mDir = new File(context.getExternalMediaDirs()[0], "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);

        mIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        mIsolatedResolver = mIsolatedContext.getContentResolver();

        mModern = new ModernMediaScanner(mIsolatedContext);
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
        assertFalse(parseOptionalMimeType("image/png", null).isPresent());
        assertFalse(parseOptionalMimeType("image/png", "image").isPresent());
        assertFalse(parseOptionalMimeType("image/png", "im/im").isPresent());
        assertFalse(parseOptionalMimeType("image/png", "audio/x-shiny").isPresent());

        assertTrue(parseOptionalMimeType("image/png", "image/x-shiny").isPresent());
        assertEquals("image/x-shiny",
                parseOptionalMimeType("image/png", "image/x-shiny").get());
    }

    @Test
    public void testOverrideMimeType_148316354() throws Exception {
        // Radical file type shifting isn't allowed
        assertEquals(Optional.empty(),
                parseOptionalMimeType("video/mp4", "audio/mpeg"));

        // One specific narrow type of shift (mp4 -> m4a) is allowed
        assertEquals(Optional.of("audio/mp4"),
                parseOptionalMimeType("video/mp4", "audio/mp4"));

        // The other direction isn't allowed
        assertEquals(Optional.empty(),
                parseOptionalMimeType("audio/mp4", "video/mp4"));
    }

    @Test
    public void testParseOptional() throws Exception {
        assertFalse(parseOptional(null).isPresent());
        assertFalse(parseOptional("").isPresent());
        assertFalse(parseOptional(" ").isPresent());
        assertFalse(parseOptional("-1").isPresent());

        assertFalse(parseOptional(-1).isPresent());
        assertTrue(parseOptional(0).isPresent());
        assertTrue(parseOptional(1).isPresent());

        assertEquals("meow", parseOptional("meow").get());
        assertEquals(42, (int) parseOptional(42).get());
    }

    @Test
    public void testParseOptionalOrZero() throws Exception {
        assertFalse(parseOptionalOrZero(-1).isPresent());
        assertFalse(parseOptionalOrZero(0).isPresent());
        assertTrue(parseOptionalOrZero(1).isPresent());
    }

    @Test
    public void testParseOptionalNumerator() throws Exception {
        assertEquals(12, (int) parseOptionalNumerator("12").get());
        assertEquals(12, (int) parseOptionalNumerator("12/24").get());

        assertFalse(parseOptionalNumerator("/24").isPresent());
    }

    @Test
    public void testParseOptionalOrientation() throws Exception {
        assertEquals(0,
                (int) parseOptionalOrientation(ExifInterface.ORIENTATION_NORMAL).get());
        assertEquals(90,
                (int) parseOptionalOrientation(ExifInterface.ORIENTATION_ROTATE_90).get());
        assertEquals(180,
                (int) parseOptionalOrientation(ExifInterface.ORIENTATION_ROTATE_180).get());
        assertEquals(270,
                (int) parseOptionalOrientation(ExifInterface.ORIENTATION_ROTATE_270).get());

        // We can't represent this as an orientation
        assertFalse(parseOptionalOrientation(ExifInterface.ORIENTATION_TRANSPOSE).isPresent());
    }

    @Test
    public void testParseOptionalImageResolution() throws Exception {
        final MediaMetadataRetriever mmr = mock(MediaMetadataRetriever.class);
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)))
                .thenReturn("640");
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)))
                .thenReturn("480");
        assertEquals("640\u00d7480", parseOptionalImageResolution(mmr).get());
    }

    @Test
    public void testParseOptionalVideoResolution() throws Exception {
        final MediaMetadataRetriever mmr = mock(MediaMetadataRetriever.class);
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)))
                .thenReturn("640");
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)))
                .thenReturn("480");
        assertEquals("640\u00d7480", parseOptionalVideoResolution(mmr).get());
    }

    @Test
    public void testParseOptionalResolution() throws Exception {
        final ExifInterface exif = mock(ExifInterface.class);
        when(exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)).thenReturn("640");
        when(exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)).thenReturn("480");
        assertEquals("640\u00d7480", parseOptionalResolution(exif).get());
    }

    @Test
    public void testParseOptionalDate() throws Exception {
        assertEquals(1577836800000L, (long) parseOptionalDate("20200101T000000").get());
    }

    @Test
    public void testParseOptionalTrack() throws Exception {
        final MediaMetadataRetriever mmr = mock(MediaMetadataRetriever.class);
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)))
                .thenReturn("1/2");
        when(mmr.extractMetadata(eq(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)))
                .thenReturn("4/12");
        assertEquals(1004, (int) parseOptionalTrack(mmr).get());
    }

    @Test
    public void testParseDateTaken_Complete() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // Offset is recorded, test both zeros
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-00:00");
        assertEquals(1453972654000L, (long) parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
        assertEquals(1453972654000L, (long) parseOptionalDateTaken(exif, 0L).get());

        // Offset is recorded, test both directions
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-07:00");
        assertEquals(1453972654000L + 25200000L, (long) parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+07:00");
        assertEquals(1453972654000L - 25200000L, (long) parseOptionalDateTaken(exif, 0L).get());
    }

    @Test
    public void testParseDateTaken_Gps() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // GPS tells us we're in UTC
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:14:00");
        assertEquals(1453972654000L, (long) parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:20:00");
        assertEquals(1453972654000L, (long) parseOptionalDateTaken(exif, 0L).get());

        // GPS tells us we're in -7
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "16:14:00");
        assertEquals(1453972654000L + 25200000L, (long) parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "16:20:00");
        assertEquals(1453972654000L + 25200000L, (long) parseOptionalDateTaken(exif, 0L).get());

        // GPS tells us we're in +7
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "02:14:00");
        assertEquals(1453972654000L - 25200000L, (long) parseOptionalDateTaken(exif, 0L).get());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "02:20:00");
        assertEquals(1453972654000L - 25200000L, (long) parseOptionalDateTaken(exif, 0L).get());

        // GPS beyond 24 hours isn't helpful
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:27");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:17:34");
        assertFalse(parseOptionalDateTaken(exif, 0L).isPresent());
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:29");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:17:34");
        assertFalse(parseOptionalDateTaken(exif, 0L).isPresent());
    }

    @Test
    public void testParseDateTaken_File() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // Modified tells us we're in UTC
        assertEquals(1453972654000L,
                (long) parseOptionalDateTaken(exif, 1453972654000L - 60000L).get());
        assertEquals(1453972654000L,
                (long) parseOptionalDateTaken(exif, 1453972654000L + 60000L).get());

        // Modified tells us we're in -7
        assertEquals(1453972654000L + 25200000L,
                (long) parseOptionalDateTaken(exif, 1453972654000L + 25200000L - 60000L).get());
        assertEquals(1453972654000L + 25200000L,
                (long) parseOptionalDateTaken(exif, 1453972654000L + 25200000L + 60000L).get());

        // Modified tells us we're in +7
        assertEquals(1453972654000L - 25200000L,
                (long) parseOptionalDateTaken(exif, 1453972654000L - 25200000L - 60000L).get());
        assertEquals(1453972654000L - 25200000L,
                (long) parseOptionalDateTaken(exif, 1453972654000L - 25200000L + 60000L).get());

        // Modified beyond 24 hours isn't helpful
        assertFalse(parseOptionalDateTaken(exif, 1453972654000L - 86400000L).isPresent());
        assertFalse(parseOptionalDateTaken(exif, 1453972654000L + 86400000L).isPresent());
    }

    @Test
    public void testParseDateTaken_Hopeless() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");

        // Offset is completely missing, and no useful GPS or modified time
        assertFalse(parseOptionalDateTaken(exif, 0L).isPresent());
    }

    @Test
    public void testParseYear_Invalid() throws Exception {
        assertEquals(Optional.empty(), parseOptionalYear(null));
        assertEquals(Optional.empty(), parseOptionalYear(""));
        assertEquals(Optional.empty(), parseOptionalYear(" "));
        assertEquals(Optional.empty(), parseOptionalYear("meow"));

        assertEquals(Optional.empty(), parseOptionalYear("0"));
        assertEquals(Optional.empty(), parseOptionalYear("00"));
        assertEquals(Optional.empty(), parseOptionalYear("000"));
        assertEquals(Optional.empty(), parseOptionalYear("0000"));

        assertEquals(Optional.empty(), parseOptionalYear("1"));
        assertEquals(Optional.empty(), parseOptionalYear("01"));
        assertEquals(Optional.empty(), parseOptionalYear("001"));
        assertEquals(Optional.empty(), parseOptionalYear("0001"));

        // No sane way to determine year from two-digit date formats
        assertEquals(Optional.empty(), parseOptionalYear("01-01-01"));

        // Specific example from partner
        assertEquals(Optional.empty(), parseOptionalYear("000 "));
    }

    @Test
    public void testParseYear_Valid() throws Exception {
        assertEquals(Optional.of(1900), parseOptionalYear("1900"));
        assertEquals(Optional.of(2020), parseOptionalYear("2020"));
        assertEquals(Optional.of(2020), parseOptionalYear(" 2020 "));
        assertEquals(Optional.of(2020), parseOptionalYear("01-01-2020"));

        // Specific examples from partner
        assertEquals(Optional.of(1984), parseOptionalYear("1984-06-26T07:00:00Z"));
        assertEquals(Optional.of(2016), parseOptionalYear("Thu, 01 Sep 2016 10:11:12.123456 -0500"));
    }

    private static void assertShouldScanPathAndIsPathHidden(boolean isScannable, boolean isHidden,
        File dir) {
        assertEquals(Pair.create(isScannable, isHidden), shouldScanPathAndIsPathHidden(dir));
    }

    @Test
    public void testShouldScanPathAndIsPathHidden() {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/emulated/0/Android/sandbox/com.example",
                "/storage/0000-0000",
                "/storage/0000-0000/Android/sandbox/com.example",
        }) {
            assertShouldScanPathAndIsPathHidden(true, false, new File(prefix));
            assertShouldScanPathAndIsPathHidden(true, false, new File(prefix + "/meow"));
            assertShouldScanPathAndIsPathHidden(true, false, new File(prefix + "/Android/meow"));
            assertShouldScanPathAndIsPathHidden(true, false,
                    new File(prefix + "/Android/sandbox/meow"));

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
        }
    }

    private static void assertShouldScanDirectory(File file) {
        assertTrue(file.getAbsolutePath(), shouldScanDirectory(file));
    }

    private static void assertShouldntScanDirectory(File file) {
        assertFalse(file.getAbsolutePath(), shouldScanDirectory(file));
    }

    @Test
    public void testShouldScanDirectory() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/emulated/0/Android/sandbox/com.example",
                "/storage/0000-0000",
                "/storage/0000-0000/Android/sandbox/com.example",
        }) {
            assertShouldScanDirectory(new File(prefix));
            assertShouldScanDirectory(new File(prefix + "/meow"));
            assertShouldScanDirectory(new File(prefix + "/Android"));
            assertShouldScanDirectory(new File(prefix + "/Android/meow"));
            assertShouldScanDirectory(new File(prefix + "/Android/sandbox"));
            assertShouldScanDirectory(new File(prefix + "/Android/sandbox/meow"));
            assertShouldScanDirectory(new File(prefix + "/.meow"));

            assertShouldntScanDirectory(new File(prefix + "/Android/data"));
            assertShouldntScanDirectory(new File(prefix + "/Android/obb"));

            assertShouldntScanDirectory(new File(prefix + "/Pictures/.thumbnails"));
            assertShouldntScanDirectory(new File(prefix + "/Movies/.thumbnails"));
            assertShouldntScanDirectory(new File(prefix + "/Music/.thumbnails"));

            assertShouldScanDirectory(new File(prefix + "/DCIM/.thumbnails"));
        }
    }

    private static void assertDirectoryHidden(File file) {
        assertTrue(file.getAbsolutePath(), isDirectoryHidden(file));
    }

    private static void assertDirectoryNotHidden(File file) {
        assertFalse(file.getAbsolutePath(), isDirectoryHidden(file));
    }

    @Test
    public void testIsDirectoryHidden() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/emulated/0/Android/sandbox/com.example",
                "/storage/0000-0000",
                "/storage/0000-0000/Android/sandbox/com.example",
        }) {
            assertDirectoryNotHidden(new File(prefix));
            assertDirectoryNotHidden(new File(prefix + "/meow"));

            assertDirectoryHidden(new File(prefix + "/.meow"));
        }


        final File nomediaFile = new File("storage/emulated/0/Download/meow", ".nomedia");
        try {
            assertTrue(nomediaFile.getParentFile().mkdirs());
            assertTrue(nomediaFile.createNewFile());

            assertDirectoryHidden(nomediaFile.getParentFile());

            assertTrue(nomediaFile.delete());

            assertDirectoryNotHidden(nomediaFile.getParentFile());
        } finally {
            nomediaFile.delete();
            nomediaFile.getParentFile().delete();
        }
    }

    @Test
    public void testIsFileHidden() throws Exception {
        assertFalse(isFileHidden(
                new File("/storage/emulated/0/DCIM/IMG1024.JPG")));
        assertFalse(isFileHidden(
                new File("/storage/emulated/0/DCIM/.pending-1577836800-IMG1024.JPG")));
        assertFalse(isFileHidden(
                new File("/storage/emulated/0/DCIM/.trashed-1577836800-IMG1024.JPG")));
        assertTrue(isFileHidden(
                new File("/storage/emulated/0/DCIM/.IMG1024.JPG")));
    }

    @Test
    public void testIsZero() throws Exception {
        assertFalse(ModernMediaScanner.isZero(""));
        assertFalse(ModernMediaScanner.isZero("meow"));
        assertFalse(ModernMediaScanner.isZero("1"));
        assertFalse(ModernMediaScanner.isZero("01"));
        assertFalse(ModernMediaScanner.isZero("010"));

        assertTrue(ModernMediaScanner.isZero("0"));
        assertTrue(ModernMediaScanner.isZero("00"));
        assertTrue(ModernMediaScanner.isZero("000"));
    }

    @Test
    public void testPlaylistM3u() throws Exception {
        doPlaylist(R.raw.test_m3u, "test.m3u");
    }

    @Test
    public void testPlaylistPls() throws Exception {
        doPlaylist(R.raw.test_pls, "test.pls");
    }

    @Test
    public void testPlaylistWpl() throws Exception {
        doPlaylist(R.raw.test_wpl, "test.wpl");
    }

    @Test
    public void testPlaylistXspf() throws Exception {
        doPlaylist(R.raw.test_xspf, "test.xspf");
    }

    private void doPlaylist(int res, String name) throws Exception {
        final File music = new File(mDir, "Music");
        music.mkdirs();
        stage(R.raw.test_audio, new File(music, "001.mp3"));
        stage(R.raw.test_audio, new File(music, "002.mp3"));
        stage(R.raw.test_audio, new File(music, "003.mp3"));
        stage(R.raw.test_audio, new File(music, "004.mp3"));
        stage(R.raw.test_audio, new File(music, "005.mp3"));
        stage(res, new File(music, name));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        // We should see a new playlist with all three items as members
        final long playlistId;
        try (Cursor cursor = mIsolatedContext.getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[] { FileColumns._ID },
                FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_PLAYLIST, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
                MediaColumns.DISPLAY_NAME
        }, null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            assertEquals(5, cursor.getCount());
            cursor.moveToNext();
            assertEquals("001.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("002.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("003.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("004.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("005.mp3", cursor.getString(0));
        }

        // Delete one of the media files and rescan
        new File(music, "002.mp3").delete();
        new File(music, name).setLastModified(10L);
        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
                MediaColumns.DISPLAY_NAME
        }, null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            assertEquals(4, cursor.getCount());
            cursor.moveToNext();
            assertEquals("001.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("003.mp3", cursor.getString(0));
        }

        // Replace media file in a completely different location, which normally
        // wouldn't match the exact playlist path, but we're willing to perform
        // a relaxed search
        final File soundtracks = new File(mDir, "Soundtracks");
        soundtracks.mkdirs();
        stage(R.raw.test_audio, new File(soundtracks, "002.mp3"));
        stage(res, new File(music, name));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
                MediaColumns.DISPLAY_NAME
        }, null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            assertEquals(5, cursor.getCount());
            cursor.moveToNext();
            assertEquals("001.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("002.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("003.mp3", cursor.getString(0));
        }
    }

    @Test
    public void testFilter() throws Exception {
        final File music = new File(mDir, "Music");
        music.mkdirs();
        stage(R.raw.test_audio, new File(music, "example.mp3"));
        mModern.scanDirectory(mDir, REASON_UNKNOWN);

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
     * All file formats are thoroughly tested by {@code CtsProviderTestCases},
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
    public void testScan_Nomedia_Dir() throws Exception {
        final File red = new File(mDir, "red");
        final File blue = new File(mDir, "blue");
        red.mkdirs();
        blue.mkdirs();
        stage(R.raw.test_image, new File(red, "red.jpg"));
        stage(R.raw.test_image, new File(blue, "blue.jpg"));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        // We should have found both images
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Hide one directory, rescan, and confirm hidden
        final File redNomedia = new File(red, ".nomedia");
        redNomedia.createNewFile();
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(1, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Unhide, rescan, and confirm visible again
        redNomedia.delete();
        mModern.scanDirectory(mDir, REASON_UNKNOWN);
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
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
    public void testScanFileAndUpdateOwnerPackageName() throws Exception {
        final File image = new File(mDir, "image.jpg");
        final String thisPackageName = InstrumentationRegistry.getContext().getPackageName();
        stage(R.raw.test_image, image);

        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        // scanning the image file inserts new database entry with OWNER_PACKAGE_NAME as
        // thisPackageName.
        assertNotNull(mModern.scanFile(image, REASON_UNKNOWN, thisPackageName));
        try (Cursor cursor = mIsolatedResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(thisPackageName, cursor.getString(0));
        }
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
        final File music = new File(mDir, "Music");
        final File audio = new File(music, "audio.mp3");

        music.mkdirs();
        stage(R.raw.test_audio_empty_title, audio);

        mModern.scanFile(audio, REASON_UNKNOWN);

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals("audio", cursor.getString(cursor.getColumnIndex(MediaColumns.TITLE)));
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
            assertEquals("audio/mp4",
                    cursor.getString(cursor.getColumnIndex(MediaColumns.MIME_TYPE)));
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
        try {
            mIsolatedResolver.openFileDescriptor(uri, "w", null);
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
            assertEquals(LegacyMediaScannerTest.isNonMediaFile(path), isFileAlbumArt(file));
        }

        for (String path : new String[] {
                "/storage/emulated/0/AlbumArtLarge.jpg",
                "/storage/emulated/0/albumartlarge.jpg",
        }) {
            final File file = new File(path);
            assertTrue(isFileAlbumArt(file));
        }
    }
}
