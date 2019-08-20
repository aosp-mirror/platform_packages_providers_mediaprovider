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

import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.android.providers.media.scan.ModernMediaScanner.isDirectoryHidden;
import static com.android.providers.media.scan.ModernMediaScanner.maybeOverrideMimeType;
import static com.android.providers.media.scan.ModernMediaScanner.parseOptionalDateTaken;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.MediaProvider;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;
import com.android.providers.media.tests.R;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public class ModernMediaScannerTest {
    // TODO: scan directory-vs-files and confirm identical results

    private File mDir;

    private Context mIsolatedContext;
    private ContentResolver mIsolatedResolver;

    private ModernMediaScanner mModern;

    @Before
    public void setUp() {
        mDir = new File(Environment.getExternalStorageDirectory(), "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);

        final Context context = InstrumentationRegistry.getTargetContext();
        mIsolatedContext = new IsolatedContext(context, "modern");
        mIsolatedResolver = mIsolatedContext.getContentResolver();

        mModern = new ModernMediaScanner(mIsolatedContext);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContents(mDir);
    }

    @Test
    public void testOverrideMimeType() throws Exception {
        assertEquals("image/png",
                maybeOverrideMimeType("image/png", null));
        assertEquals("image/png",
                maybeOverrideMimeType("image/png", "image"));
        assertEquals("image/png",
                maybeOverrideMimeType("image/png", "im/im"));
        assertEquals("image/png",
                maybeOverrideMimeType("image/png", "audio/x-shiny"));
        assertEquals("image/x-shiny",
                maybeOverrideMimeType("image/png", "image/x-shiny"));
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
            assertDirectoryNotHidden(new File(prefix + "/Android"));
            assertDirectoryNotHidden(new File(prefix + "/Android/meow"));
            assertDirectoryNotHidden(new File(prefix + "/Android/sandbox"));
            assertDirectoryNotHidden(new File(prefix + "/Android/sandbox/meow"));

            assertDirectoryHidden(new File(prefix + "/.meow"));
            assertDirectoryHidden(new File(prefix + "/Android/data"));
            assertDirectoryHidden(new File(prefix + "/Android/obb"));
        }
    }

    @Test
    public void testPlaylistM3u() throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);
        doPlaylist(R.raw.test_m3u, "test.m3u");
    }

    @Test
    public void testPlaylistPls() throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);
        doPlaylist(R.raw.test_pls, "test.pls");
    }

    @Test
    public void testPlaylistWpl() throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);
        doPlaylist(R.raw.test_wpl, "test.wpl");
    }

    private void doPlaylist(int res, String name) throws Exception {
        final File music = new File(mDir, "Music");
        music.mkdirs();
        stage(R.raw.test_audio, new File(music, "001.mp3"));
        stage(R.raw.test_audio, new File(music, "002.mp3"));
        stage(R.raw.test_audio, new File(music, "003.mp3"));
        stage(res, new File(music, name));

        mModern.scanDirectory(mDir);

        // We should see a new playlist with all three items as members
        final long playlistId;
        try (Cursor cursor = mIsolatedContext.getContentResolver().query(
                MediaStore.Files.EXTERNAL_CONTENT_URI, new String[] { FileColumns._ID },
                FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_PLAYLIST, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
                MediaColumns.DISPLAY_NAME
        }, null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            assertEquals(3, cursor.getCount());
            cursor.moveToNext();
            assertEquals("001.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("002.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("003.mp3", cursor.getString(0));
        }

        // Delete one of the media files and rescan
        new File(music, "002.mp3").delete();
        new File(music, name).setLastModified(10L);
        mModern.scanDirectory(mDir);

        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
                MediaColumns.DISPLAY_NAME
        }, null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("001.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("003.mp3", cursor.getString(0));
        }
    }

    @Test
    public void testScan_Common() throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);

        final File file = new File(mDir, "red.jpg");
        stage(R.raw.test_image, file);

        mModern.scanDirectory(mDir);

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
        MediaStore.waitForIdle(mIsolatedContext);

        try (Cursor cursor = mIsolatedResolver
                .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(32, cursor.getLong(cursor.getColumnIndex(MediaColumns.WIDTH)));
            assertEquals(32, cursor.getLong(cursor.getColumnIndex(MediaColumns.HEIGHT)));
        }

        // Delete raw file and confirm it's cleaned up
        file.delete();
        mModern.scanDirectory(mDir);
        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    @Test
    public void testScan_Nomedia_Dir() throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);

        final File red = new File(mDir, "red");
        final File blue = new File(mDir, "blue");
        red.mkdirs();
        blue.mkdirs();
        stage(R.raw.test_image, new File(red, "red.jpg"));
        stage(R.raw.test_image, new File(blue, "blue.jpg"));

        mModern.scanDirectory(mDir);

        // We should have found both images
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Hide one directory, rescan, and confirm hidden
        final File redNomedia = new File(red, ".nomedia");
        redNomedia.createNewFile();
        mModern.scanDirectory(mDir);
        assertQueryCount(1, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Unhide, rescan, and confirm visible again
        redNomedia.delete();
        mModern.scanDirectory(mDir);
        assertQueryCount(2, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    @Test
    public void testScan_Nomedia_File() throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);

        final File image = new File(mDir, "image.jpg");
        final File nomedia = new File(mDir, ".nomedia");
        stage(R.raw.test_image, image);
        nomedia.createNewFile();

        // Direct scan with nomedia means no image
        assertNull(mModern.scanFile(image));
        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Direct scan without nomedia means image
        nomedia.delete();
        assertNotNull(mModern.scanFile(image));
        assertNotNull(mModern.scanFile(image));
        assertQueryCount(1, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        // Direct scan again hides it again
        nomedia.createNewFile();
        assertNull(mModern.scanFile(image));
        assertQueryCount(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }

    private void assertQueryCount(int expected, Uri actualUri) {
        try (Cursor cursor = mIsolatedResolver.query(actualUri, null, null, null, null)) {
            assertEquals(expected, cursor.getCount());
        }
    }
}
