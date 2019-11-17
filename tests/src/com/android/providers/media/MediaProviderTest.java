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

import static android.provider.MediaStore.Downloads.isDownload;
import static android.provider.MediaStore.Downloads.isDownloadDir;

import static com.android.providers.media.MediaProvider.ensureFileColumns;
import static com.android.providers.media.MediaProvider.extractPathOwnerPackageName;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.MediaProvider.VolumeArgumentException;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class MediaProviderTest {
    static final String TAG = "MediaProviderTest";

    @Test
    public void testSchema() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern");
        final ContentResolver isolatedResolver = isolatedContext.getContentResolver();

        for (String path : new String[] {
                "images/media",
                "images/media/1",
                "images/thumbnails",
                "images/thumbnails/1",

                "audio/media",
                "audio/media/1",
                "audio/media/1/genres",
                "audio/media/1/genres/1",
                "audio/media/1/playlists",
                "audio/media/1/playlists/1",
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
            try (Cursor c = isolatedResolver.query(probe, null, null, null)) {
                assertNotNull("probe", c);
            }
        }
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
        return extractPathOwnerPackageName(path);
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
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/sandbox/com.example/foo.jpg"));
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
    public void testBuildData_Primary() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/DCIM/IMG_1024.JPG",
                buildFile(uri, Environment.DIRECTORY_DCIM, "IMG_1024.JPG", "image/jpeg"));
    }

    @Test
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
    public void testBuildData_Charset() throws Exception {
        final Uri uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        assertEndsWith("/Pictures/foo__bar/bar__baz.png",
                buildFile(uri, "Pictures/foo\0\0bar", "bar::baz.png", "image/png"));
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
    public void testBindList() {
        assertEquals("()", MediaProvider.bindList());
        assertEquals("( 'foo' )", MediaProvider.bindList("foo"));
        assertEquals("( 'foo' , 'bar' )", MediaProvider.bindList("foo", "bar"));
        assertEquals("( 'foo' , 'bar' , 'baz' )", MediaProvider.bindList("foo", "bar", "baz"));
        assertEquals("( 'foo' , NULL , 42 )", MediaProvider.bindList("foo", null, 42));
    }

    @Test
    public void testIsDownload() throws Exception {
        assertTrue(isDownload("/storage/emulated/0/Download/colors.png"));
        assertTrue(isDownload("/storage/emulated/0/Download/test.pdf"));
        assertTrue(isDownload("/storage/emulated/0/Download/dir/foo.mp4"));
        assertTrue(isDownload("/storage/0000-0000/Download/foo.txt"));
        assertTrue(isDownload(
                "/storage/emulated/0/Android/sandbox/com.example/Download/colors.png"));
        assertTrue(isDownload(
                "/storage/emulated/0/Android/sandbox/shared-com.uid.shared/Download/colors.png"));
        assertTrue(isDownload(
                "/storage/0000-0000/Android/sandbox/com.example/Download/colors.png"));
        assertTrue(isDownload(
                "/storage/0000-0000/Android/sandbox/shared-com.uid.shared/Download/colors.png"));


        assertFalse(isDownload("/storage/emulated/0/Pictures/colors.png"));
        assertFalse(isDownload("/storage/emulated/0/Pictures/Download/colors.png"));
        assertFalse(isDownload("/storage/emulated/0/Android/data/com.example/Download/foo.txt"));
        assertFalse(isDownload(
                "/storage/emulated/0/Android/sandbox/com.example/dir/Download/foo.txt"));
        assertFalse(isDownload("/storage/emulated/0/Download"));
        assertFalse(isDownload("/storage/emulated/0/Android/sandbox/com.example/Download"));
        assertFalse(isDownload(
                "/storage/0000-0000/Android/sandbox/shared-com.uid.shared/Download"));
    }

    @Test
    public void testIsDownloadDir() throws Exception {
        assertTrue(isDownloadDir("/storage/emulated/0/Download"));
        assertTrue(isDownloadDir("/storage/emulated/0/Android/sandbox/com.example/Download"));

        assertFalse(isDownloadDir("/storage/emulated/0/Download/colors.png"));
        assertFalse(isDownloadDir("/storage/emulated/0/Download/dir/"));
        assertFalse(isDownloadDir(
                "/storage/emulated/0/Android/sandbox/com.example/Download/dir/foo.txt"));
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
            assertGroup(values, "IMG1024");
            assertRelativePath(values, "DCIM/Camera/");
        }
    }

    @Test
    public void testComputeDataValues_Extensions() throws Exception {
        ContentValues values;

        values = computeDataValues("/storage/0000-0000/DCIM/Camera/IMG1024");
        assertVolume(values, "0000-0000");
        assertBucket(values, "/storage/0000-0000/DCIM/Camera", "Camera");
        assertGroup(values, null);
        assertRelativePath(values, "DCIM/Camera/");

        values = computeDataValues("/storage/0000-0000/DCIM/Camera/.foo");
        assertVolume(values, "0000-0000");
        assertBucket(values, "/storage/0000-0000/DCIM/Camera", "Camera");
        assertGroup(values, null);
        assertRelativePath(values, "DCIM/Camera/");
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
                "/storage/emulated/0/Android/sandbox/com.example",
        }) {
            values = computeDataValues(top + "/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top, null);
            assertGroup(values, "IMG1024");
            assertRelativePath(values, "/");

            values = computeDataValues(top + "/One/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top + "/One", "One");
            assertGroup(values, "IMG1024");
            assertRelativePath(values, "One/");

            values = computeDataValues(top + "/One/Two/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top + "/One/Two", "Two");
            assertGroup(values, "IMG1024");
            assertRelativePath(values, "One/Two/");

            values = computeDataValues(top + "/One/Two/Three/IMG1024.JPG");
            assertVolume(values, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            assertBucket(values, top + "/One/Two/Three", "Three");
            assertGroup(values, "IMG1024");
            assertRelativePath(values, "One/Two/Three/");
        }
    }

    private static ContentValues computeDataValues(String path) {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, path);
        MediaProvider.computeDataValues(values);
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

    private static void assertGroup(ContentValues values, String groupId) {
        if (groupId != null) {
            assertEquals(groupId.toLowerCase(Locale.ROOT).hashCode(),
                    (long) values.getAsLong(ImageColumns.GROUP_ID));
        } else {
            assertNull(values.get(ImageColumns.GROUP_ID));
        }
    }

    private static void assertVolume(ContentValues values, String volumeName) {
        assertEquals(volumeName, values.getAsString(ImageColumns.VOLUME_NAME));
    }

    private static void assertRelativePath(ContentValues values, String relativePath) {
        assertEquals(relativePath, values.get(ImageColumns.RELATIVE_PATH));
    }

    private static boolean isGreylistMatch(String raw) {
        for (Pattern p : MediaProvider.sGreylist) {
            if (p.matcher(raw).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String buildFile(Uri uri, String relativePath, String displayName,
            String mimeType) {
        final ContentValues values = new ContentValues();
        if (relativePath != null) {
            values.put(MediaColumns.RELATIVE_PATH, relativePath);
        }
        values.put(MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaColumns.MIME_TYPE, mimeType);
        try {
            ensureFileColumns(uri, values);
        } catch (VolumeArgumentException e) {
            throw e.rethrowAsIllegalArgumentException();
        }
        return values.getAsString(MediaColumns.DATA);
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
}
