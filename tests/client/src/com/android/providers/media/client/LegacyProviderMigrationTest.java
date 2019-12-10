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

package com.android.providers.media.client;

import static android.provider.MediaStore.rewriteToLegacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.DownloadColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assume;
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

/**
 * Verify that we preserve information from the old "legacy" provider from
 * before we migrated into a Mainline module.
 * <p>
 * Specifically, values like {@link BaseColumns#_ID} and user edits like
 * {@link MediaColumns#IS_FAVORITE} should be retained.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyProviderMigrationTest {
    private static final String TAG = "LegacyTest";

    // TODO: expand test to cover secondary storage devices
    private String mVolumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;

    private Uri mExternalAudio;
    private Uri mExternalVideo;
    private Uri mExternalImages;
    private Uri mExternalDownloads;

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Using volume " + mVolumeName);
        mExternalAudio = MediaStore.Audio.Media.getContentUri(mVolumeName);
        mExternalVideo = MediaStore.Video.Media.getContentUri(mVolumeName);
        mExternalImages = MediaStore.Images.Media.getContentUri(mVolumeName);
        mExternalDownloads = MediaStore.Downloads.getContentUri(mVolumeName);
    }

    @Test
    public void testLegacy_Pending() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".png");
        values.put(MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        values.put(MediaColumns.IS_PENDING, String.valueOf(1));
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Trashed() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".png");
        values.put(MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        values.put(MediaColumns.IS_TRASHED, String.valueOf(1));
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Favorite() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".png");
        values.put(MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        values.put(MediaColumns.IS_FAVORITE, String.valueOf(1));
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Orphaned() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".png");
        values.put(MediaColumns.MIME_TYPE, "image/png");
        values.putNull(MediaColumns.OWNER_PACKAGE_NAME);
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Audio() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".mp3");
        values.put(MediaColumns.MIME_TYPE, "audio/mpeg");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        values.put(AudioColumns.BOOKMARK, String.valueOf(42));
        doLegacy(mExternalAudio, values);
    }

    @Test
    public void testLegacy_Video() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".mp4");
        values.put(MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        values.put(VideoColumns.BOOKMARK, String.valueOf(42));
        values.put(VideoColumns.TAGS, "My Tags");
        values.put(VideoColumns.CATEGORY, "My Category");
        doLegacy(mExternalVideo, values);
    }

    @Test
    public void testLegacy_Image() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".png");
        values.put(MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Download() throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".iso");
        values.put(MediaColumns.MIME_TYPE, "application/x-iso9660-image");
        values.put(DownloadColumns.DOWNLOAD_URI, "http://example.com/download");
        values.put(DownloadColumns.REFERER_URI, "http://example.com/referer");
        doLegacy(mExternalDownloads, values);
    }

    private void doLegacy(Uri collectionUri, ContentValues values) throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();

        final ProviderInfo legacyProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY_LEGACY, 0);
        final ProviderInfo modernProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY, 0);

        // Only continue if we have both providers to test against
        Assume.assumeNotNull(legacyProvider);
        Assume.assumeNotNull(modernProvider);

        // Create a well-known entry in legacy provider, and write data into
        // place to ensure the file is created on disk
        final Uri legacyUri;
        final File legacyFile;
        try (ContentProviderClient legacy = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY_LEGACY)) {
            legacyUri = rewriteToLegacy(legacy.insert(rewriteToLegacy(collectionUri), values));

            try (Cursor cursor = legacy.query(legacyUri, null, null, null)) {
                assertTrue(cursor.moveToFirst());
                copyFromCursorToContentValues(MediaColumns._ID, cursor, values);
                copyFromCursorToContentValues(MediaColumns.DATA, cursor, values);
                copyFromCursorToContentValues(MediaColumns.DATE_ADDED, cursor, values);
            }

            try (ParcelFileDescriptor pfd = legacy.openFile(legacyUri, "rw")) {
            }

            legacyFile = new File(values.getAsString(MediaColumns.DATA));
        }

        // Clear data on the modern provider so that the initial scan recovers
        // metadata from the legacy provider
        executeShellCommand("pm clear " + modernProvider.applicationInfo.packageName, ui);

        // And make sure that we've restarted after clearing data above
        MediaStore.suicide(context);

        // And force a scan to confirm upgraded data survives
        MediaStore.scanVolume(context, legacyFile);

        // Confirm that details from legacy provider have migrated
        try (ContentProviderClient modern = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            final Bundle extras = new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    MediaColumns.DATA + "=?");
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[] { legacyFile.getAbsolutePath() });
            extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE);

            try (Cursor cursor = modern.query(collectionUri, null, extras, null)) {
                assertTrue(cursor.moveToFirst());
                final ContentValues actualValues = new ContentValues();
                for (String key : values.keySet()) {
                    actualValues.put(key, cursor.getString(cursor.getColumnIndexOrThrow(key)));
                }
                assertEquals(values, actualValues);
            }
        }
    }

    public static String executeShellCommand(String command, UiAutomation uiAutomation)
            throws IOException {
        Log.v(TAG, "$ " + command);
        ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(command.toString());
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

    public static void copyFromCursorToContentValues(@NonNull String column, @NonNull Cursor cursor,
            @NonNull ContentValues values) {
        final int index = cursor.getColumnIndex(column);
        if (index != -1) {
            if (cursor.isNull(index)) {
                values.putNull(column);
            } else {
                values.put(column, cursor.getString(index));
            }
        }
    }
}
