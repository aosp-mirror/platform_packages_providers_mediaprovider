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
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.DownloadColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

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

    private ContentValues generateValues(int mediaType, String mimeType, String dirName) {
        final Context context = InstrumentationRegistry.getContext();

        final File dir = context.getSystemService(StorageManager.class)
                .getStorageVolume(MediaStore.Files.getContentUri(mVolumeName)).getDirectory();
        final File subDir = new File(dir, dirName);
        final File file = new File(subDir, "legacy" + System.nanoTime() + "."
                + MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType));

        final ContentValues values = new ContentValues();
        values.put(FileColumns.MEDIA_TYPE, mediaType);
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaColumns.VOLUME_NAME, mVolumeName);
        values.put(MediaColumns.DATE_ADDED, String.valueOf(System.currentTimeMillis() / 1_000));
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        return values;
    }

    @Test
    public void testLegacy_Pending() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        values.put(MediaColumns.IS_PENDING, String.valueOf(1));
        values.put(MediaColumns.DATE_EXPIRES, String.valueOf(System.currentTimeMillis() / 1_000));
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Trashed() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        values.put(MediaColumns.IS_TRASHED, String.valueOf(1));
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Favorite() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        values.put(MediaColumns.IS_FAVORITE, String.valueOf(1));
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Orphaned() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        values.putNull(MediaColumns.OWNER_PACKAGE_NAME);
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Audio() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_AUDIO,
                "audio/mpeg", Environment.DIRECTORY_MUSIC);
        values.put(AudioColumns.BOOKMARK, String.valueOf(42));
        doLegacy(mExternalAudio, values);
    }

    @Test
    public void testLegacy_Video() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_VIDEO,
                "video/mpeg", Environment.DIRECTORY_MOVIES);
        values.put(VideoColumns.BOOKMARK, String.valueOf(42));
        values.put(VideoColumns.TAGS, "My Tags");
        values.put(VideoColumns.CATEGORY, "My Category");
        doLegacy(mExternalVideo, values);
    }

    @Test
    public void testLegacy_Image() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_Download() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_NONE,
                "application/x-iso9660-image", Environment.DIRECTORY_DOWNLOADS);
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
            legacyFile = new File(values.getAsString(MediaColumns.DATA));

            // Remember our ID to check it later
            values.put(MediaColumns._ID, legacyUri.getLastPathSegment());

            // Drop media type from the columns we check, since it's implicitly
            // verified via the collection Uri
            values.remove(FileColumns.MEDIA_TYPE);
        }

        // Clear data on the modern provider so that the initial scan recovers
        // metadata from the legacy provider
        executeShellCommand("pm clear " + modernProvider.applicationInfo.packageName, ui);

        // And force a scan to confirm upgraded data survives
        MediaStore.waitForIdle(context.getContentResolver());
        MediaStore.scanVolume(context.getContentResolver(),
                MediaStore.getVolumeName(collectionUri));

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
