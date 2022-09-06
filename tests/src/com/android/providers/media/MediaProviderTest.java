/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.providers.media;

import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.android.providers.media.util.FileUtils.extractDisplayName;
import static com.android.providers.media.util.FileUtils.extractRelativePath;
import static com.android.providers.media.util.FileUtils.extractRelativePathWithDisplayName;
import static com.android.providers.media.util.FileUtils.isDownload;
import static com.android.providers.media.util.FileUtils.isDownloadDir;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.util.ArrayMap;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.MediaProvider.FallbackException;
import com.android.providers.media.MediaProvider.VolumeArgumentException;
import com.android.providers.media.MediaProvider.VolumeNotFoundException;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.FileUtilsTest;
import com.android.providers.media.util.SQLiteQueryBuilder;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class MediaProviderTest {
    static final String TAG = "MediaProviderTest";

    // The test app without permissions
    static final String PERMISSIONLESS_APP = "com.android.providers.media.testapp.withoutperms";

    private static Context sIsolatedContext;
    private static ContentResolver sIsolatedResolver;

    @BeforeClass
    public static void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);

        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
    }

    @AfterClass
    public static void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    /**
     * To fully exercise all our tests, we require that the Cuttlefish emulator
     * have both emulated primary storage and an SD card be present.
     */
    @Test
    public void testCuttlefish() {
        Assume.assumeTrue(Build.MODEL.contains("Cuttlefish"));

        assertTrue("Cuttlefish must have both emulated storage and an SD card to exercise tests",
                MediaStore.getExternalVolumeNames(InstrumentationRegistry.getTargetContext())
                        .size() > 1);
    }

    @Test
    public void testSchema() {
        for (String path : new String[] {
                "images/media",
                "images/media/1",
                "images/thumbnails",
                "images/thumbnails/1",

                "audio/media",
                "audio/media/1",
                "audio/media/1/genres",
                "audio/media/1/genres/1",
                "audio/genres",
                "audio/genres/1",
                "audio/genres/1/members",
                "audio/playlists",
                "audio/playlists/1",
                "audio/playlists/1/members",
                "audio/playlists/1/members/1",
                "audio/artists",
                "audio/artists/1",
                "audio/artists/1/albums",
                "audio/albums",
                "audio/albums/1",
                "audio/albumart",
                "audio/albumart/1",

                "video/media",
                "video/media/1",
                "video/thumbnails",
                "video/thumbnails/1",

                "file",
                "file/1",

                "downloads",
                "downloads/1",
        }) {
            final Uri probe = MediaStore.AUTHORITY_URI.buildUpon()
                    .appendPath(MediaStore.VOLUME_EXTERNAL).appendEncodedPath(path).build();
            try (Cursor c = sIsolatedResolver.query(probe, null, null, null)) {
                assertNotNull("probe", c);
            }
            try {
                sIsolatedResolver.getType(probe);
            } catch (IllegalStateException tolerated) {
            }
        }
    }

    @Test
    public void testLocale() {
        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider())
                    .onLocaleChanged();
        }
    }

    @Test
    public void testDump() throws Exception {
        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            cpc.getLocalContentProvider().dump(null,
                    new PrintWriter(new ByteArrayOutputStream()), null);
        }
    }

    /**
     * Verify that our fallback exceptions throw on modern apps while degrading
     * gracefully for legacy apps.
     */
    @Test
    public void testFallbackException() throws Exception {
        for (FallbackException e : new FallbackException[] {
                new FallbackException("test", Build.VERSION_CODES.Q),
                new VolumeNotFoundException("test"),
                new VolumeArgumentException(new File("/"), Collections.emptyList())
        }) {
            // Modern apps should get thrown
            assertThrows(Exception.class, () -> {
                e.translateForInsert(Build.VERSION_CODES.CUR_DEVELOPMENT);
            });
            assertThrows(Exception.class, () -> {
                e.translateForUpdateDelete(Build.VERSION_CODES.CUR_DEVELOPMENT);
            });
            assertThrows(Exception.class, () -> {
                e.translateForQuery(Build.VERSION_CODES.CUR_DEVELOPMENT);
            });

            // Legacy apps gracefully log without throwing
            assertEquals(null, e.translateForInsert(Build.VERSION_CODES.BASE));
            assertEquals(0, e.translateForUpdateDelete(Build.VERSION_CODES.BASE));
            assertEquals(null, e.translateForQuery(Build.VERSION_CODES.BASE));
        }
    }

    /**
     * We already have solid coverage of this logic in {@link IdleServiceTest},
     * but the coverage system currently doesn't measure that, so we add the
     * bare minimum local testing here to convince the tooling that it's
     * covered.
     */
    @Test
    public void testIdle() throws Exception {
        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider())
                    .onIdleMaintenance(new CancellationSignal());
        }
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testCanonicalize() throws Exception {
        // We might have old files lurking, so force a clean slate
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();

        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        for (File file : new File[] {
                stage(R.raw.test_audio, new File(dir, "test" + System.nanoTime() + ".mp3")),
                stage(R.raw.test_video_xmp, new File(dir, "test" + System.nanoTime() + ".mp4")),
                stage(R.raw.lg_g4_iso_800_jpg, new File(dir, "test" + System.nanoTime() + ".jpg"))
        }) {
            final Uri uri = MediaStore.scanFile(sIsolatedResolver, file);
            Log.v(TAG, "Scanned " + file + " as " + uri);

            final Uri forward = sIsolatedResolver.canonicalize(uri);
            final Uri reverse = sIsolatedResolver.uncanonicalize(forward);

            assertEquals(ContentUris.parseId(uri), ContentUris.parseId(forward));
            assertEquals(ContentUris.parseId(uri), ContentUris.parseId(reverse));
        }
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testMetadata() {
        assertNotNull(MediaStore.getVersion(sIsolatedContext,
                MediaStore.VOLUME_EXTERNAL_PRIMARY));
        assertNotNull(MediaStore.getGeneration(sIsolatedResolver,
                MediaStore.VOLUME_EXTERNAL_PRIMARY));
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testCreateRequest() throws Exception {
        final Collection<Uri> uris = Arrays.asList(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, 42));
        assertNotNull(MediaStore.createWriteRequest(sIsolatedResolver, uris));
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testCheckUriPermission() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test.mp3");
        values.put(MediaColumns.MIME_TYPE, "audio/mpeg");
        final Uri uri = sIsolatedResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);

        assertEquals(PackageManager.PERMISSION_GRANTED, sIsolatedResolver.checkUriPermission(uri,
                android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    @Test
    public void testTrashLongFileNameItemHasTrimmedFileName() throws Exception {
        testActionLongFileNameItemHasTrimmedFileName(MediaColumns.IS_TRASHED);
    }

    @Test
    public void testPendingLongFileNameItemHasTrimmedFileName() throws Exception {
        testActionLongFileNameItemHasTrimmedFileName(MediaColumns.IS_PENDING);
    }

    private void testActionLongFileNameItemHasTrimmedFileName(String columnKey) throws Exception {
        // We might have old files lurking, so force a clean slate
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        final String[] projection = new String[]{MediaColumns.DATA};
        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        // create extreme long file name
        final String originalName = FileUtilsTest.createExtremeFileName("test" + System.nanoTime(),
                ".jpg");

        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(dir, originalName));
        final Uri uri = MediaStore.scanFile(sIsolatedResolver, file);
        Log.v(TAG, "Scanned " + file + " as " + uri);

        try (Cursor c = sIsolatedResolver.query(uri, projection, null, null)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            final String data = c.getString(0);
            final String result = FileUtils.extractDisplayName(data);
            assertEquals(originalName, result);
        }

        final Bundle extras = new Bundle();
        extras.putBoolean(MediaStore.QUERY_ARG_ALLOW_MOVEMENT, true);
        final ContentValues values = new ContentValues();
        values.put(columnKey, 1);
        sIsolatedResolver.update(uri, values, extras);

        try (Cursor c = sIsolatedResolver.query(uri, projection, null, null)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            final String data = c.getString(0);
            final String result = FileUtils.extractDisplayName(data);
            assertThat(result.length()).isAtMost(FileUtilsTest.MAX_FILENAME_BYTES);
            assertNotEquals(originalName, result);
        }
    }

    @Test
    public void testInsertionWithInvalidFilePath_throwsIllegalArgumentException() {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Android/media/com.example");
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                "./../../../../../../../../../../../data/media/test.txt");

        IllegalArgumentException illegalArgumentException = Assert.assertThrows(
                IllegalArgumentException.class, () -> sIsolatedResolver.insert(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values));

        assertThat(illegalArgumentException).hasMessageThat().contains(
                "Primary directory Android not allowed for content://media/external_primary/file;"
                        + " allowed directories are [Download, Documents]");
    }

    @Test
    public void testUpdationWithInvalidFilePath_throwsIllegalArgumentException() {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "test.txt");
        Uri uri = sIsolatedResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values);

        final ContentValues newValues = new ContentValues();
        newValues.put(MediaStore.MediaColumns.DATA, "/storage/emulated/0/../../../data/media/");
        IllegalArgumentException illegalArgumentException = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> sIsolatedResolver.update(uri, newValues, null));

        assertThat(illegalArgumentException).hasMessageThat().contains(
                "Requested path /data/media doesn't appear under [/storage/emulated/0]");
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testBulkInsert() throws Exception {
        final ContentValues values1 = new ContentValues();
        values1.put(MediaColumns.DISPLAY_NAME, "test1.mp3");
        values1.put(MediaColumns.MIME_TYPE, "audio/mpeg");

        final ContentValues values2 = new ContentValues();
        values2.put(MediaColumns.DISPLAY_NAME, "test2.mp3");
        values2.put(MediaColumns.MIME_TYPE, "audio/mpeg");

        final Uri targetUri = MediaStore.Audio.Media
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEquals(2, sIsolatedResolver.bulkInsert(targetUri,
                new ContentValues[] { values1, values2 }));
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testCustomCollator() throws Exception {
        final Bundle extras = new Bundle();
        extras.putString(ContentResolver.QUERY_ARG_SORT_LOCALE, "en");

        try (Cursor c = sIsolatedResolver.query(MediaStore.Files.EXTERNAL_CONTENT_URI,
                null, extras, null)) {
            assertNotNull(c);
        }
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testGetRedactionRanges_Image() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        stage(R.raw.test_image, file);
        assertNotNull(MediaProvider.getRedactionRanges(file));
    }

    /**
     * We already have solid coverage of this logic in
     * {@code CtsProviderTestCases}, but the coverage system currently doesn't
     * measure that, so we add the bare minimum local testing here to convince
     * the tooling that it's covered.
     */
    @Test
    public void testGetRedactionRanges_Video() throws Exception {
        final File file = File.createTempFile("test", ".mp4");
        stage(R.raw.test_video, file);
        assertNotNull(MediaProvider.getRedactionRanges(file));
    }

    @Test
    public void testComputeCommonPrefix_Single() {
        assertEquals(Uri.parse("content://authority/1/2/3"),
                MediaProvider.computeCommonPrefix(Arrays.asList(
                        Uri.parse("content://authority/1/2/3"))));
    }

    @Test
    public void testComputeCommonPrefix_Deeper() {
        assertEquals(Uri.parse("content://authority/1/2/3"),
                MediaProvider.computeCommonPrefix(Arrays.asList(
                        Uri.parse("content://authority/1/2/3/4"),
                        Uri.parse("content://authority/1/2/3/4/5"),
                        Uri.parse("content://authority/1/2/3"))));
    }

    @Test
    public void testComputeCommonPrefix_Siblings() {
        assertEquals(Uri.parse("content://authority/1/2"),
                MediaProvider.computeCommonPrefix(Arrays.asList(
                        Uri.parse("content://authority/1/2/3"),
                        Uri.parse("content://authority/1/2/99"))));
    }

    @Test
    public void testComputeCommonPrefix_Drastic() {
        assertEquals(Uri.parse("content://authority"),
                MediaProvider.computeCommonPrefix(Arrays.asList(
                        Uri.parse("content://authority/1/2/3"),
                        Uri.parse("content://authority/99/99/99"))));
    }

    private static String getPathOwnerPackageName(String path) {
        return FileUtils.extractPathOwnerPackageName(path);
    }

    @Test
    public void testPathOwnerPackageName_None() throws Exception {
        assertEquals(null, getPathOwnerPackageName(null));
        assertEquals(null, getPathOwnerPackageName("/data/path"));
    }

    @Test
    public void testPathOwnerPackageName_Emulated() throws Exception {
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/DCIM/foo.jpg"));
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/Android/"));
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/Android/data/"));

        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/data/com.example/"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/data/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/obb/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/media/com.example/foo.jpg"));
    }

    @Test
    public void testPathOwnerPackageName_Portable() throws Exception {
        assertEquals(null, getPathOwnerPackageName("/storage/0000-0000/DCIM/foo.jpg"));

        assertEquals("com.example",
                getPathOwnerPackageName("/storage/0000-0000/Android/data/com.example/foo.jpg"));
    }

    @Test
    public void testBuildData_Simple() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Pictures/file.png",
                buildFile(uri, null, "file", "image/png"));
        assertEndsWith("/Pictures/file.png",
                buildFile(uri, null, "file.png", "image/png"));
        assertEndsWith("/Pictures/file.jpg.png",
                buildFile(uri, null, "file.jpg", "image/png"));
    }

    @Test
    public void testBuildData_withUserId() throws Exception {
        final Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test_userid");
        values.put(MediaColumns.MIME_TYPE, "image/png");
        Uri result = sIsolatedResolver.insert(uri, values);
        try (Cursor c = sIsolatedResolver.query(result,
                new String[]{MediaColumns.DISPLAY_NAME, FileColumns._USER_ID},
                null, null)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("test_userid.png", c.getString(0));
            assertEquals(UserHandle.myUserId(), c.getInt(1));
        }
    }

    @Test
    public void testBuildData_Primary() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/DCIM/IMG_1024.JPG",
                buildFile(uri, Environment.DIRECTORY_DCIM, "IMG_1024.JPG", "image/jpeg"));
    }

    @Test
    @Ignore("Enable as part of b/142561358")
    public void testBuildData_Secondary() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Pictures/Screenshots/foo.png",
                buildFile(uri, "Pictures/Screenshots", "foo.png", "image/png"));
    }

    @Test
    public void testBuildData_InvalidNames() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Pictures/foo_bar.png",
            buildFile(uri, null, "foo/bar", "image/png"));
        assertEndsWith("/Pictures/_.hidden.png",
            buildFile(uri, null, ".hidden", "image/png"));
    }

    @Test
    public void testBuildData_InvalidTypes() throws Exception {
        for (String type : new String[] {
                "audio/foo", "video/foo", "image/foo", "application/foo", "foo/foo"
        }) {
            if (!type.startsWith("audio/")) {
                assertThrows(IllegalArgumentException.class, () -> {
                    buildFile(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            null, "foo", type);
                });
            }
            if (!type.startsWith("video/")) {
                assertThrows(IllegalArgumentException.class, () -> {
                    buildFile(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            null, "foo", type);
                });
            }
            if (!type.startsWith("image/")) {
                assertThrows(IllegalArgumentException.class, () -> {
                    buildFile(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            null, "foo", type);
                });
            }
        }
    }

    @Test
    public void testBuildData_InvalidSecondaryTypes() throws Exception {
        assertEndsWith("/Pictures/foo.png",
                buildFile(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        null, "foo.png", "image/*"));

        assertThrows(IllegalArgumentException.class, () -> {
            buildFile(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    null, "foo", "video/*");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            buildFile(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    null, "foo.mp4", "audio/*");
        });
    }

    @Test
    public void testBuildData_EmptyTypes() throws Exception {
        Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Pictures/foo.png",
                buildFile(uri, null, "foo.png", ""));

        uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith(".mp4",
                buildFile(uri, null, "", ""));
    }

    @Test
    public void testEnsureFileColumns_InvalidMimeType_targetSdkQ() throws Exception {
        final MediaProvider provider = new MediaProvider() {
            @Override
            public boolean isFuseThread() {
                return false;
            }

            @Override
            public int getCallingPackageTargetSdkVersion() {
                return Build.VERSION_CODES.Q;
            }
        };

        final ProviderInfo info = sIsolatedContext.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY, PackageManager.GET_META_DATA);
        // Attach providerInfo, to make sure mCallingIdentity can be populated
        provider.attachInfo(sIsolatedContext, info);
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues values = new ContentValues();

        values.put(MediaColumns.DISPLAY_NAME, "pngimage.png");
        provider.ensureFileColumns(uri, values);
        assertMimetype(values, "image/jpeg");
        assertDisplayName(values, "pngimage.png.jpg");

        values.clear();
        values.put(MediaColumns.DISPLAY_NAME, "pngimage.png");
        values.put(MediaColumns.MIME_TYPE, "");
        provider.ensureFileColumns(uri, values);
        assertMimetype(values, "image/jpeg");
        assertDisplayName(values, "pngimage.png.jpg");

        values.clear();
        values.put(MediaColumns.MIME_TYPE, "");
        provider.ensureFileColumns(uri, values);
        assertMimetype(values, "image/jpeg");

        values.clear();
        values.put(MediaColumns.DISPLAY_NAME, "foo.foo");
        provider.ensureFileColumns(uri, values);
        assertMimetype(values, "image/jpeg");
        assertDisplayName(values, "foo.foo.jpg");
    }

    @Ignore("Enable as part of b/142561358")
    public void testBuildData_Charset() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Pictures/foo__bar/bar__baz.png",
                buildFile(uri, "Pictures/foo\0\0bar", "bar::baz.png", "image/png"));
    }

    @Test
    public void testBuildData_Playlists() throws Exception {
        final Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Music/my_playlist.m3u",
                buildFile(uri, null, "my_playlist", "audio/mpegurl"));
        assertEndsWith("/Movies/my_playlist.pls",
                buildFile(uri, "Movies", "my_playlist", "audio/x-scpls"));
    }

    @Test
    public void testBuildData_Subtitles() throws Exception {
        final Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Movies/my_subtitle.srt",
                buildFile(uri, null, "my_subtitle", "application/x-subrip"));
        assertEndsWith("/Music/my_lyrics.lrc",
                buildFile(uri, "Music", "my_lyrics", "application/lrc"));
    }

    @Test
    public void testBuildData_Downloads() throws Exception {
        final Uri uri = MediaStore.Downloads
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Download/linux.iso",
                buildFile(uri, null, "linux.iso", "application/x-iso9660-image"));
    }

    @Test
    public void testBuildData_Pending_FromValues() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues forward = new ContentValues();
        forward.put(MediaColumns.RELATIVE_PATH, "DCIM/My Vacation/");
        forward.put(MediaColumns.DISPLAY_NAME, "IMG1024.JPG");
        forward.put(MediaColumns.MIME_TYPE, "image/jpeg");
        forward.put(MediaColumns.IS_PENDING, 1);
        forward.put(MediaColumns.IS_TRASHED, 0);
        forward.put(MediaColumns.DATE_EXPIRES, 1577836800L);
        ensureFileColumns(uri, forward);

        // Requested filename remains intact, but raw path on disk is mutated to
        // reflect that it's a pending item with a specific expiration time
        assertEquals("IMG1024.JPG",
                forward.getAsString(MediaColumns.DISPLAY_NAME));
        assertEndsWith(".pending-1577836800-IMG1024.JPG",
                forward.getAsString(MediaColumns.DATA));
    }

    @Test
    public void testBuildData_Pending_FromValues_differentLocale() throws Exception {
        // See b/174120008 for context.
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ar", "SA"));
            testBuildData_Pending_FromValues();
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    public void testBuildData_Pending_FromData() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues reverse = new ContentValues();
        reverse.put(MediaColumns.DATA,
                "/storage/emulated/0/DCIM/My Vacation/.pending-1577836800-IMG1024.JPG");
        ensureFileColumns(uri, reverse);

        assertEquals("DCIM/My Vacation/", reverse.getAsString(MediaColumns.RELATIVE_PATH));
        assertEquals("IMG1024.JPG", reverse.getAsString(MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", reverse.getAsString(MediaColumns.MIME_TYPE));
        assertEquals(1, (int) reverse.getAsInteger(MediaColumns.IS_PENDING));
        assertEquals(0, (int) reverse.getAsInteger(MediaColumns.IS_TRASHED));
        assertEquals(1577836800, (long) reverse.getAsLong(MediaColumns.DATE_EXPIRES));
    }

    @Test
    public void testBuildData_Trashed_FromValues() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues forward = new ContentValues();
        forward.put(MediaColumns.RELATIVE_PATH, "DCIM/My Vacation/");
        forward.put(MediaColumns.DISPLAY_NAME, "IMG1024.JPG");
        forward.put(MediaColumns.MIME_TYPE, "image/jpeg");
        forward.put(MediaColumns.IS_PENDING, 0);
        forward.put(MediaColumns.IS_TRASHED, 1);
        forward.put(MediaColumns.DATE_EXPIRES, 1577836800L);
        ensureFileColumns(uri, forward);

        // Requested filename remains intact, but raw path on disk is mutated to
        // reflect that it's a trashed item with a specific expiration time
        assertEquals("IMG1024.JPG",
                forward.getAsString(MediaColumns.DISPLAY_NAME));
        assertEndsWith(".trashed-1577836800-IMG1024.JPG",
                forward.getAsString(MediaColumns.DATA));
    }

    @Test
    public void testBuildData_Trashed_FromValues_differentLocale() throws Exception {
        // See b/174120008 for context.
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ar", "SA"));
            testBuildData_Trashed_FromValues();
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    public void testBuildData_Trashed_FromData() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues reverse = new ContentValues();
        reverse.put(MediaColumns.DATA,
                "/storage/emulated/0/DCIM/My Vacation/.trashed-1577836800-IMG1024.JPG");
        ensureFileColumns(uri, reverse);

        assertEquals("DCIM/My Vacation/", reverse.getAsString(MediaColumns.RELATIVE_PATH));
        assertEquals("IMG1024.JPG", reverse.getAsString(MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", reverse.getAsString(MediaColumns.MIME_TYPE));
        assertEquals(0, (int) reverse.getAsInteger(MediaColumns.IS_PENDING));
        assertEquals(1, (int) reverse.getAsInteger(MediaColumns.IS_TRASHED));
        assertEquals(1577836800, (long) reverse.getAsLong(MediaColumns.DATE_EXPIRES));
    }

    @Test
    public void testGreylist() throws Exception {
        assertFalse(isGreylistMatch(
                "SELECT secret FROM other_table"));

        assertTrue(isGreylistMatch(
                "case when case when (date_added >= 157680000 and date_added < 1892160000) then date_added * 1000 when (date_added >= 157680000000 and date_added < 1892160000000) then date_added when (date_added >= 157680000000000 and date_added < 1892160000000000) then date_added / 1000 else 0 end > case when (date_modified >= 157680000 and date_modified < 1892160000) then date_modified * 1000 when (date_modified >= 157680000000 and date_modified < 1892160000000) then date_modified when (date_modified >= 157680000000000 and date_modified < 1892160000000000) then date_modified / 1000 else 0 end then case when (date_added >= 157680000 and date_added < 1892160000) then date_added * 1000 when (date_added >= 157680000000 and date_added < 1892160000000) then date_added when (date_added >= 157680000000000 and date_added < 1892160000000000) then date_added / 1000 else 0 end else case when (date_modified >= 157680000 and date_modified < 1892160000) then date_modified * 1000 when (date_modified >= 157680000000 and date_modified < 1892160000000) then date_modified when (date_modified >= 157680000000000 and date_modified < 1892160000000000) then date_modified / 1000 else 0 end end as corrected_added_modified"));
        assertTrue(isGreylistMatch(
                "MAX(case when (datetaken >= 157680000 and datetaken < 1892160000) then datetaken * 1000 when (datetaken >= 157680000000 and datetaken < 1892160000000) then datetaken when (datetaken >= 157680000000000 and datetaken < 1892160000000000) then datetaken / 1000 else 0 end)"));
        assertTrue(isGreylistMatch(
                "0 as orientation"));
        assertTrue(isGreylistMatch(
                "\"content://media/internal/audio/media\""));
    }

    @Test
    public void testGreylist_115845887() {
        assertTrue(isGreylistMatch(
                "MAX(*)"));
        assertTrue(isGreylistMatch(
                "MAX(_id)"));

        assertTrue(isGreylistMatch(
                "sum(column_name)"));
        assertFalse(isGreylistMatch(
                "SUM(foo+bar)"));

        assertTrue(isGreylistMatch(
                "count(column_name)"));
        assertFalse(isGreylistMatch(
                "count(other_table.column_name)"));
    }

    @Test
    public void testGreylist_116489751_116135586_116117120_116084561_116074030_116062802() {
        assertTrue(isGreylistMatch(
                "MAX(case when (date_added >= 157680000 and date_added < 1892160000) then date_added * 1000 when (date_added >= 157680000000 and date_added < 1892160000000) then date_added when (date_added >= 157680000000000 and date_added < 1892160000000000) then date_added / 1000 else 0 end)"));
    }

    @Test
    public void testGreylist_116699470() {
        assertTrue(isGreylistMatch(
                "MAX(case when (date_modified >= 157680000 and date_modified < 1892160000) then date_modified * 1000 when (date_modified >= 157680000000 and date_modified < 1892160000000) then date_modified when (date_modified >= 157680000000000 and date_modified < 1892160000000000) then date_modified / 1000 else 0 end)"));
    }

    @Test
    public void testGreylist_116531759() {
        assertTrue(isGreylistMatch(
                "count(*)"));
        assertTrue(isGreylistMatch(
                "COUNT(*)"));
        assertFalse(isGreylistMatch(
                "xCOUNT(*)"));
        assertTrue(isGreylistMatch(
                "count(*) AS image_count"));
        assertTrue(isGreylistMatch(
                "count(_id)"));
        assertTrue(isGreylistMatch(
                "count(_id) AS image_count"));

        assertTrue(isGreylistMatch(
                "column_a AS column_b"));
        assertFalse(isGreylistMatch(
                "other_table.column_a AS column_b"));
    }

    @Test
    public void testGreylist_118475754() {
        assertTrue(isGreylistMatch(
                "count(*) pcount"));
        assertTrue(isGreylistMatch(
                "foo AS bar"));
        assertTrue(isGreylistMatch(
                "foo bar"));
        assertTrue(isGreylistMatch(
                "count(foo) AS bar"));
        assertTrue(isGreylistMatch(
                "count(foo) bar"));
    }

    @Test
    public void testGreylist_119522660() {
        assertTrue(isGreylistMatch(
                "CAST(_id AS TEXT) AS string_id"));
        assertTrue(isGreylistMatch(
                "cast(_id as text)"));
    }

    @Test
    public void testGreylist_126945991() {
        assertTrue(isGreylistMatch(
                "substr(_data, length(_data)-length(_display_name), 1) as filename_prevchar"));
    }

    @Test
    public void testGreylist_127900881() {
        assertTrue(isGreylistMatch(
                "*"));
    }

    @Test
    public void testGreylist_128389972() {
        assertTrue(isGreylistMatch(
                " count(bucket_id) images_count"));
    }

    @Test
    public void testGreylist_129746861() {
        assertTrue(isGreylistMatch(
                "case when (datetaken >= 157680000 and datetaken < 1892160000) then datetaken * 1000 when (datetaken >= 157680000000 and datetaken < 1892160000000) then datetaken when (datetaken >= 157680000000000 and datetaken < 1892160000000000) then datetaken / 1000 else 0 end"));
    }

    @Test
    public void testGreylist_114112523() {
        assertTrue(isGreylistMatch(
                "audio._id AS _id"));
    }

    @Test
    public void testComputeProjection_AggregationAllowed() throws Exception {
        final SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
        final ArrayMap<String, String> map = new ArrayMap<>();
        map.put("external", "internal");
        builder.setProjectionMap(map);
        builder.setStrict(true);
        builder.setStrictColumns(true);

        assertArrayEquals(
                new String[] { "internal" },
                builder.computeProjection(null));
        assertArrayEquals(
                new String[] { "internal" },
                builder.computeProjection(new String[] { "external" }));
        assertThrows(IllegalArgumentException.class, () -> {
            builder.computeProjection(new String[] { "internal" });
        });
        assertThrows(IllegalArgumentException.class, () -> {
            builder.computeProjection(new String[] { "MIN(internal)" });
        });
        assertArrayEquals(
                new String[] { "MIN(internal)" },
                builder.computeProjection(new String[] { "MIN(external)" }));
        assertThrows(IllegalArgumentException.class, () -> {
            builder.computeProjection(new String[] { "FOO(external)" });
        });
    }

    @Test
    public void testIsDownload() throws Exception {
        assertTrue(isDownload("/storage/emulated/0/Download/colors.png"));
        assertTrue(isDownload("/storage/emulated/0/Download/test.pdf"));
        assertTrue(isDownload("/storage/emulated/0/Download/dir/foo.mp4"));
        assertTrue(isDownload("/storage/0000-0000/Download/foo.txt"));

        assertFalse(isDownload("/storage/emulated/0/Pictures/colors.png"));
        assertFalse(isDownload("/storage/emulated/0/Pictures/Download/colors.png"));
        assertFalse(isDownload("/storage/emulated/0/Android/data/com.example/Download/foo.txt"));
        assertFalse(isDownload("/storage/emulated/0/Download"));
    }

    @Test
    public void testIsDownloadDir() throws Exception {
        assertTrue(isDownloadDir("/storage/emulated/0/Download"));

        assertFalse(isDownloadDir("/storage/emulated/0/Download/colors.png"));
        assertFalse(isDownloadDir("/storage/emulated/0/Download/dir/"));
    }

    @Test
    public void testComputeDataValues_Grouped() throws Exception {
        for (String data : new String[] {
                "/storage/0000-0000/DCIM/Camera/IMG1024.JPG",
                "/storage/0000-0000/DCIM/Camera/iMg1024.JpG",
                "/storage/0000-0000/DCIM/Camera/IMG1024.CR2",
                "/storage/0000-0000/DCIM/Camera/IMG1024.BURST001.JPG",
        }) {
            final ContentValues values = computeDataValues(data);
            assertVolume(values, "0000-0000");
            assertBucket(values, "/storage/0000-0000/DCIM/Camera", "Camera");
            assertRelativePath(values, "DCIM/Camera/");
        }
    }

    @Test
    public void testComputeDataValues_Extensions() throws Exception {
        ContentValues values;

        values = computeDataValues("/storage/0000-0000/DCIM/Camera/IMG1024");
        assertVolume(values, "0000-0000");
        assertBucket(values, "/storage/0000-0000/DCIM/Camera", "Camera");
        assertRelativePath(values, "DCIM/Camera/");

        values = computeDataValues("/storage/0000-0000/DCIM/Camera/.foo");
        assertVolume(values, "0000-0000");
        assertBucket(values, "/storage/0000-0000/DCIM/Camera", "Camera");
        assertRelativePath(values, "DCIM/Camera/");

        values = computeDataValues("/storage/476A-17F8/123456/test.png");
        assertVolume(values, "476a-17f8");
        assertBucket(values, "/storage/476A-17F8/123456", "123456");
        assertRelativePath(values, "123456/");

        values = computeDataValues("/storage/476A-17F8/123456/789/test.mp3");
        assertVolume(values, "476a-17f8");
        assertBucket(values, "/storage/476A-17F8/123456/789", "789");
        assertRelativePath(values, "123456/789/");
    }

    @Test
    public void testComputeDataValues_DirectoriesInvalid() throws Exception {
        for (String data : new String[] {
                "/storage/IMG1024.JPG",
                "/data/media/IMG1024.JPG",
                "IMG1024.JPG",
        }) {
            final ContentValues values = computeDataValues(data);
            assertRelativePath(values, null);
        }
    }

    @Test
    public void testComputeDataValues_Directories() throws Exception {
        ContentValues values;

        for (String top : new String[] {
                "/storage/emulated/0",
        }) {
            values = computeDataValues(top + "/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top, null);
            assertRelativePath(values, "/");

            values = computeDataValues(top + "/One/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top + "/One", "One");
            assertRelativePath(values, "One/");

            values = computeDataValues(top + "/One/Two/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top + "/One/Two", "Two");
            assertRelativePath(values, "One/Two/");

            values = computeDataValues(top + "/One/Two/Three/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top + "/One/Two/Three", "Three");
            assertRelativePath(values, "One/Two/Three/");
        }
    }

    @Test
    public void testEnsureFileColumns_resolvesMimeType() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "pngimage.png");

        final MediaProvider provider = new MediaProvider() {
            @Override
            public boolean isFuseThread() {
                return false;
            }

            @Override
            public int getCallingPackageTargetSdkVersion() {
                return Build.VERSION_CODES.CUR_DEVELOPMENT;
            }
        };
        final ProviderInfo info = sIsolatedContext.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY, PackageManager.GET_META_DATA);
        // Attach providerInfo, to make sure mCallingIdentity can be populated
        provider.attachInfo(sIsolatedContext, info);
        provider.ensureFileColumns(uri, values);

        assertMimetype(values, "image/png");
    }

    @Test
    public void testRelativePathForInvalidDirectories() throws Exception {
        for (String path : new String[] {
                "/storage/emulated",
                "/storage",
                "/data/media/Foo.jpg",
                "Foo.jpg",
                "storage/Foo"
        }) {
            assertEquals(null, FileUtils.extractRelativePathWithDisplayName(path));
        }
    }

    @Test
    public void testRelativePathForValidDirectories() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/emulated/10",
                "/storage/ABCD-1234"
        }) {
            assertRelativePathForDirectory(prefix, "/");
            assertRelativePathForDirectory(prefix + "/DCIM", "DCIM/");
            assertRelativePathForDirectory(prefix + "/DCIM/Camera", "DCIM/Camera/");
            assertRelativePathForDirectory(prefix + "/Z", "Z/");
            assertRelativePathForDirectory(prefix + "/Android/media/com.example/Foo",
                    "Android/media/com.example/Foo/");
        }
    }

    @Test
    public void testComputeAudioKeyValues_167339595_differentAlbumIds() throws Exception {
        // same album name, different album artists
        final ContentValues valuesOne = new ContentValues();
        valuesOne.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesOne.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesOne.put(FileColumns.DATA, "/storage/emulated/0/Clocks.mp3");
        valuesOne.put(AudioColumns.TITLE, "Clocks");
        valuesOne.put(AudioColumns.ALBUM, "A Rush of Blood");
        valuesOne.put(AudioColumns.ALBUM_ARTIST, "Coldplay");
        valuesOne.put(AudioColumns.GENRE, "Rock");
        valuesOne.put(AudioColumns.IS_MUSIC, true);

        final ContentValues valuesTwo = new ContentValues();
        valuesTwo.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesTwo.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesTwo.put(FileColumns.DATA, "/storage/emulated/0/Sounds.mp3");
        valuesTwo.put(AudioColumns.TITLE, "Sounds");
        valuesTwo.put(AudioColumns.ALBUM, "A Rush of Blood");
        valuesTwo.put(AudioColumns.ALBUM_ARTIST, "ColdplayTwo");
        valuesTwo.put(AudioColumns.GENRE, "Alternative rock");
        valuesTwo.put(AudioColumns.IS_MUSIC, true);

        MediaProvider.computeAudioKeyValues(valuesOne);
        final long albumIdOne = valuesOne.getAsLong(AudioColumns.ALBUM_ID);
        MediaProvider.computeAudioKeyValues(valuesTwo);
        final long albumIdTwo = valuesTwo.getAsLong(AudioColumns.ALBUM_ID);

        assertNotEquals(albumIdOne, albumIdTwo);

        // same album name, different paths, no album artists
        final ContentValues valuesThree = new ContentValues();
        valuesThree.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesThree.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesThree.put(FileColumns.DATA, "/storage/emulated/0/Silent.mp3");
        valuesThree.put(AudioColumns.TITLE, "Silent");
        valuesThree.put(AudioColumns.ALBUM, "Rainbow");
        valuesThree.put(AudioColumns.ARTIST, "Sample1");
        valuesThree.put(AudioColumns.GENRE, "Rock");
        valuesThree.put(AudioColumns.IS_MUSIC, true);

        final ContentValues valuesFour = new ContentValues();
        valuesFour.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesFour.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesFour.put(FileColumns.DATA, "/storage/emulated/0/123456/Rainbow.mp3");
        valuesFour.put(AudioColumns.TITLE, "Rainbow");
        valuesFour.put(AudioColumns.ALBUM, "Rainbow");
        valuesFour.put(AudioColumns.ARTIST, "Sample2");
        valuesFour.put(AudioColumns.GENRE, "Alternative rock");
        valuesFour.put(AudioColumns.IS_MUSIC, true);

        MediaProvider.computeAudioKeyValues(valuesThree);
        final long albumIdThree = valuesThree.getAsLong(AudioColumns.ALBUM_ID);
        MediaProvider.computeAudioKeyValues(valuesFour);
        final long albumIdFour = valuesFour.getAsLong(AudioColumns.ALBUM_ID);

        assertNotEquals(albumIdThree, albumIdFour);
    }

    @Test
    public void testComputeAudioKeyValues_167339595_sameAlbumId() throws Exception {
        // same album name, same path, no album artists
        final ContentValues valuesOne = new ContentValues();
        valuesOne.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesOne.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesOne.put(FileColumns.DATA, "/storage/emulated/0/Clocks.mp3");
        valuesOne.put(AudioColumns.TITLE, "Clocks");
        valuesOne.put(AudioColumns.ALBUM, "A Rush of Blood");
        valuesOne.put(AudioColumns.GENRE, "Rock");
        valuesOne.put(AudioColumns.IS_MUSIC, true);

        final ContentValues valuesTwo = new ContentValues();
        valuesTwo.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesTwo.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesTwo.put(FileColumns.DATA, "/storage/emulated/0/Sounds.mp3");
        valuesTwo.put(AudioColumns.TITLE, "Sounds");
        valuesTwo.put(AudioColumns.ALBUM, "A Rush of Blood");
        valuesTwo.put(AudioColumns.GENRE, "Alternative rock");
        valuesTwo.put(AudioColumns.IS_MUSIC, true);

        MediaProvider.computeAudioKeyValues(valuesOne);
        final long albumIdOne = valuesOne.getAsLong(AudioColumns.ALBUM_ID);
        MediaProvider.computeAudioKeyValues(valuesTwo);
        final long albumIdTwo = valuesTwo.getAsLong(AudioColumns.ALBUM_ID);

        assertEquals(albumIdOne, albumIdTwo);

        // same album name, same album artists, different artists
        final ContentValues valuesThree = new ContentValues();
        valuesThree.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesThree.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesThree.put(FileColumns.DATA, "/storage/emulated/0/Silent.mp3");
        valuesThree.put(AudioColumns.TITLE, "Silent");
        valuesThree.put(AudioColumns.ALBUM, "Rainbow");
        valuesThree.put(AudioColumns.ALBUM_ARTIST, "Various Artists");
        valuesThree.put(AudioColumns.ARTIST, "Sample1");
        valuesThree.put(AudioColumns.GENRE, "Rock");
        valuesThree.put(AudioColumns.IS_MUSIC, true);

        final ContentValues valuesFour = new ContentValues();
        valuesFour.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        valuesFour.put(FileColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        valuesFour.put(FileColumns.DATA, "/storage/emulated/0/Rainbow.mp3");
        valuesFour.put(AudioColumns.TITLE, "Rainbow");
        valuesFour.put(AudioColumns.ALBUM, "Rainbow");
        valuesFour.put(AudioColumns.ALBUM_ARTIST, "Various Artists");
        valuesFour.put(AudioColumns.ARTIST, "Sample2");
        valuesFour.put(AudioColumns.GENRE, "Alternative rock");
        valuesFour.put(AudioColumns.IS_MUSIC, true);

        MediaProvider.computeAudioKeyValues(valuesThree);
        final long albumIdThree = valuesThree.getAsLong(AudioColumns.ALBUM_ID);
        MediaProvider.computeAudioKeyValues(valuesFour);
        final long albumIdFour = valuesFour.getAsLong(AudioColumns.ALBUM_ID);

        assertEquals(albumIdThree, albumIdFour);
    }

    @Test
    public void testQueryAudioViewsNoTrashedItem() throws Exception {
        testQueryAudioViewsNoItemWithColumn(MediaStore.Audio.Media.IS_TRASHED);
    }

    @Test
    public void testQueryAudioViewsNoPendingItem() throws Exception {
        testQueryAudioViewsNoItemWithColumn(MediaStore.Audio.Media.IS_PENDING);
    }

    private void testQueryAudioViewsNoItemWithColumn(String columnKey) throws Exception {
        // We might have old files lurking, so force a clean slate
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);

        final File audio = new File(dir, "test" + System.nanoTime() + ".mp3");
        final Uri audioUri =
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final String album = "TestAlbum" + System.nanoTime();
        final String artist = "TestArtist" + System.nanoTime();
        final String genre = "TestGenre" + System.nanoTime();
        final String relativePath = extractRelativePath(audio.getAbsolutePath());
        final String displayName = extractDisplayName(audio.getAbsolutePath());
        ContentValues values = new ContentValues();

        values.put(MediaStore.Audio.Media.ALBUM, album);
        values.put(MediaStore.Audio.Media.ARTIST, artist);
        values.put(MediaStore.Audio.Media.GENRE, genre);
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        values.put(columnKey, 1);

        Uri result = sIsolatedResolver.insert(audioUri, values);

        // Check the audio file is inserted correctly
        try (Cursor c = sIsolatedResolver.query(result,
                new String[]{MediaColumns.DISPLAY_NAME, columnKey}, null, null)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals(displayName, c.getString(0));
            assertEquals(1, c.getInt(1));
        }

        final String volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        assertQueryResultNoItems(MediaStore.Audio.Albums.getContentUri(volume));
        assertQueryResultNoItems(MediaStore.Audio.Artists.getContentUri(volume));
        assertQueryResultNoItems(MediaStore.Audio.Genres.getContentUri(volume));
    }

    private static void assertQueryResultNoItems(Uri uri) throws Exception {
        try (Cursor c = sIsolatedResolver.query(uri, null, null, null, null)) {
            assertNotNull(c);
            assertEquals(0, c.getCount());
        }
    }

    private static void assertRelativePathForDirectory(String directoryPath, String relativePath) {
        assertWithMessage("extractRelativePathForDirectory(" + directoryPath + ") :")
                .that(extractRelativePathWithDisplayName(directoryPath))
                .isEqualTo(relativePath);
    }

    private static ContentValues computeDataValues(String path) {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, path);
        FileUtils.computeValuesFromData(values, /*forFuse*/ false);
        Log.v(TAG, "Computed values " + values);
        return values;
    }

    private static void assertBucket(ContentValues values, String bucketId, String bucketName) {
        if (bucketId != null) {
            assertEquals(bucketName,
                    values.getAsString(ImageColumns.BUCKET_DISPLAY_NAME));
            assertEquals(bucketId.toLowerCase(Locale.ROOT).hashCode(),
                    (long) values.getAsLong(ImageColumns.BUCKET_ID));
        } else {
            assertNull(values.get(ImageColumns.BUCKET_DISPLAY_NAME));
            assertNull(values.get(ImageColumns.BUCKET_ID));
        }
    }

    private static void assertVolume(ContentValues values, String volumeName) {
        assertEquals(volumeName, values.getAsString(ImageColumns.VOLUME_NAME));
    }

    private static void assertRelativePath(ContentValues values, String relativePath) {
        assertEquals(relativePath, values.get(ImageColumns.RELATIVE_PATH));
    }

    private static void assertMimetype(ContentValues values, String type) {
        assertEquals(type, values.get(MediaColumns.MIME_TYPE));
    }

    private static void assertDisplayName(ContentValues values, String type) {
        assertEquals(type, values.get(MediaColumns.DISPLAY_NAME));
    }

    private static boolean isGreylistMatch(String raw) {
        for (Pattern p : MediaProvider.sGreylist) {
            if (p.matcher(raw).matches()) {
                return true;
            }
        }
        return false;
    }

    private String buildFile(Uri uri, String relativePath, String displayName,
            String mimeType) {
        final ContentValues values = new ContentValues();
        if (relativePath != null) {
            values.put(MediaColumns.RELATIVE_PATH, relativePath);
        }
        values.put(MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaColumns.MIME_TYPE, mimeType);
        try {
            ensureFileColumns(uri, values);
        } catch (VolumeArgumentException | VolumeNotFoundException e) {
            throw e.rethrowAsIllegalArgumentException();
        }
        return values.getAsString(MediaColumns.DATA);
    }

    private void ensureFileColumns(Uri uri, ContentValues values)
            throws VolumeArgumentException, VolumeNotFoundException {
        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider())
                    .ensureFileColumns(uri, values);
        }
    }

    private static void assertEndsWith(String expected, String actual) {
        if (!actual.endsWith(expected)) {
            fail("Expected ends with " + expected + " but found " + actual);
        }
    }

    private static <T extends Exception> void assertThrows(Class<T> clazz, Runnable r) {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass())) {
                throw e;
            }
        }
    }

    @Test
    public void testNestedTransaction_applyBatch() throws Exception {
        final Uri[] uris = new Uri[]{
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL, 0),
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, 0),
        };
        final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newDelete(uris[0]).build());
        ops.add(ContentProviderOperation.newDelete(uris[1]).build());
        sIsolatedResolver.applyBatch(MediaStore.AUTHORITY, ops);
    }

    @Test
    public void testRedactionForInvalidUris() throws Exception {
        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            MediaProvider mp = (MediaProvider) cpc.getLocalContentProvider();
            final String volumeName = MediaStore.VOLUME_EXTERNAL;
            assertNull(mp.getRedactedUri(MediaStore.Images.Media.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Video.Media.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Audio.Media.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Audio.Albums.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Audio.Artists.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Audio.Genres.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Audio.Playlists.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Downloads.getContentUri(volumeName)));
            assertNull(mp.getRedactedUri(MediaStore.Files.getContentUri(volumeName)));

            // Check with a very large value - which shouldn't be present normally (at least for
            // tests).
            assertNull(mp.getRedactedUri(
                    MediaStore.Images.Media.getContentUri(volumeName, Long.MAX_VALUE)));
        }
    }

    @Test
    public void testRedactionForInvalidAndValidUris() throws Exception {
        final String volumeName = MediaStore.VOLUME_EXTERNAL;
        final List<Uri> uris = new ArrayList<>();
        uris.add(MediaStore.Images.Media.getContentUri(volumeName));
        uris.add(MediaStore.Video.Media.getContentUri(volumeName));

        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        final File[] files = new File[]{
                stage(R.raw.test_audio, new File(dir, "test" + System.nanoTime() + ".mp3")),
                stage(R.raw.test_video_xmp,
                        new File(dir, "test" + System.nanoTime() + ".mp4")),
                stage(R.raw.lg_g4_iso_800_jpg,
                        new File(dir, "test" + System.nanoTime() + ".jpg"))
        };

        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            MediaProvider mp = (MediaProvider) cpc.getLocalContentProvider();
            for (File file : files) {
                uris.add(MediaStore.scanFile(sIsolatedResolver, file));
            }

            List<Uri> redactedUris = mp.getRedactedUri(uris);
            assertEquals(uris.size(), redactedUris.size());
            assertNull(redactedUris.get(0));
            assertNull(redactedUris.get(1));
            assertNotNull(redactedUris.get(2));
            assertNotNull(redactedUris.get(3));
            assertNotNull(redactedUris.get(4));
        } finally {
            for (File file : files) {
                file.delete();
            }
        }
    }

    @Test
    public void testRedactionForFileExtension() throws Exception {
        testRedactionForFileExtension(R.raw.test_audio, ".mp3");
        testRedactionForFileExtension(R.raw.test_video_xmp, ".mp4");
        testRedactionForFileExtension(R.raw.lg_g4_iso_800_jpg, ".jpg");
    }

    private void testRedactionForFileExtension(int resId, String extension) throws Exception {
        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        final File file = new File(dir, "test" + System.nanoTime() + extension);

        stage(resId, file);

        final List<Uri> uris = new ArrayList<>();
        uris.add(MediaStore.scanFile(sIsolatedResolver, file));


        try (ContentProviderClient cpc = sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            final MediaProvider mp = (MediaProvider) cpc.getLocalContentProvider();

            final String[] projection = new String[]{MediaColumns.DISPLAY_NAME, MediaColumns.DATA};
            for (Uri uri : mp.getRedactedUri(uris)) {
                try (Cursor c = sIsolatedResolver.query(uri, projection, null, null)) {
                    assertNotNull(c);
                    assertEquals(1, c.getCount());
                    assertTrue(c.moveToFirst());
                    assertTrue(c.getString(0).endsWith(extension));
                    assertTrue(c.getString(1).endsWith(extension));
                }
            }
        } finally {
            file.delete();
        }
    }
}
