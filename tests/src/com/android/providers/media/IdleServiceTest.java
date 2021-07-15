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

import static android.os.Environment.DIRECTORY_MOVIES;
import static android.os.Environment.DIRECTORY_PICTURES;
import static android.os.Environment.buildPath;
import static android.provider.MediaStore.MediaColumns.DATE_EXPIRES;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class IdleServiceTest {
    private static final String TAG = MediaProviderTest.TAG;

    private File mDir;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);

        mDir = new File(context.getExternalMediaDirs()[0], "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContents(mDir);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testPruneThumbnails() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();

        // Previous tests (like DatabaseHelperTest) may have left stale
        // .database_uuid files, do an idle run first to clean them up.
        runIdleMaintenance(resolver);
        MediaStore.waitForIdle(resolver);

        final File dir = Environment.getExternalStorageDirectory();
        final File mediaDir = context.getExternalMediaDirs()[0];

        // Insert valid item into database
        final File file = MediaScannerTest.stage(R.raw.test_image,
                new File(mediaDir, System.nanoTime() + ".jpg"));
        final Uri uri = MediaStore.scanFile(resolver, file);
        final long id = ContentUris.parseId(uri);

        // Let things settle so our thumbnails don't get invalidated
        MediaStore.waitForIdle(resolver);

        // Touch some thumbnail files
        final File a = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", "1234567.jpg"));
        final File b = touch(buildPath(dir, DIRECTORY_MOVIES, ".thumbnails", "7654321.jpg"));
        final File c = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", id + ".jpg"));
        final File d = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", "random.bin"));

        // Idle maintenance pass should clean up unknown files
        runIdleMaintenance(resolver);
        assertFalse(exists(a));
        assertFalse(exists(b));
        assertTrue(exists(c));
        assertFalse(exists(d));

        // And change the UUID, which emulates ejecting and mounting a different
        // storage device; all thumbnails should then be invalidated
        final File uuidFile = buildPath(dir, Environment.DIRECTORY_PICTURES,
                ".thumbnails", ".database_uuid");
        delete(uuidFile);
        touch(uuidFile);

        // Idle maintenance pass should clean up all files
        runIdleMaintenance(resolver);
        assertFalse(exists(a));
        assertFalse(exists(b));
        assertFalse(exists(c));
        assertFalse(exists(d));
    }

    @Test
    public void testExtendTrashedItemExpiresOverOneWeek() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();

        // Create the expired item and scan the file to add it into database
        final long dateExpires = (System.currentTimeMillis() - 10 * DateUtils.DAY_IN_MILLIS) / 1000;
        final String expiredFileName = String.format(Locale.US, ".%s-%d-%s",
                FileUtils.PREFIX_TRASHED, dateExpires, System.nanoTime() + ".jpg");
        final File file = MediaScannerTest.stage(R.raw.test_image,
                new File(mDir, expiredFileName));
        final Uri uri = MediaStore.scanFile(resolver, file);

        MediaStore.waitForIdle(resolver);

        final String[] projection = new String[]{DATE_EXPIRES};
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        final long expectedExtendedTimestamp =
                (System.currentTimeMillis() + FileUtils.DEFAULT_DURATION_EXTENDED) / 1000 - 1;
        runIdleMaintenance(resolver);

        final long dateExpiresAfter;
        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            dateExpiresAfter = cursor.getLong(0);
            assertThat(dateExpiresAfter).isGreaterThan(expectedExtendedTimestamp);
        }
    }

    @Test
    public void testDeleteExpiredTrashedItem() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();

        // Create the expired item and scan the file to add it into database
        final long dateExpires = (System.currentTimeMillis() - 3 * DateUtils.DAY_IN_MILLIS) / 1000;
        final String expiredFileName = String.format(Locale.US, ".%s-%d-%s",
                FileUtils.PREFIX_TRASHED, dateExpires, System.nanoTime() + ".jpg");
        final File file = MediaScannerTest.stage(R.raw.test_image,
                new File(mDir, expiredFileName));
        final Uri uri = MediaStore.scanFile(resolver, file);

        MediaStore.waitForIdle(resolver);

        final String[] projection = new String[]{DATE_EXPIRES};
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }

        runIdleMaintenance(resolver);

        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void runIdleMaintenance(ContentResolver resolver) {
        final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ui.adoptShellPermissionIdentity(android.Manifest.permission.DUMP);
        try {
            MediaStore.runIdleMaintenance(resolver);
        } finally {
            ui.dropShellPermissionIdentity();
        }
    }

    public static File delete(File file) throws IOException {
        executeShellCommand("rm -rf " + file.getAbsolutePath());
        assertFalse(exists(file));
        return file;
    }

    public static File touch(File file) throws IOException {
        executeShellCommand("mkdir -p " + file.getParentFile().getAbsolutePath());
        executeShellCommand("touch " + file.getAbsolutePath());
        assertTrue(exists(file));
        return file;
    }

    public static boolean exists(File file) throws IOException {
        final String path = file.getAbsolutePath();
        return executeShellCommand("ls -la " + path).contains(path);
    }

    private static String executeShellCommand(String command) throws IOException {
        Log.v(TAG, "$ " + command);
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command.toString());
        BufferedReader br = null;
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());) {
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String str = null;
            StringBuilder out = new StringBuilder();
            while ((str = br.readLine()) != null) {
                Log.v(TAG, "> " + str);
                out.append(str);
            }
            return out.toString();
        } finally {
            if (br != null) {
                br.close();
            }
        }
    }
}
