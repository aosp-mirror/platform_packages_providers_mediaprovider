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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.UiAutomation;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.DownloadColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.test.filters.FlakyTest;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Truth;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Verify that we preserve information from the old "legacy" provider from
 * before we migrated into a Mainline module.
 * <p>
 * Specifically, values like {@link BaseColumns#_ID} and user edits like
 * {@link MediaColumns#IS_FAVORITE} should be retained.
 */
@RunWith(AndroidJUnit4.class)
@FlakyTest(bugId = 176977253)
public class LegacyProviderMigrationTest {
    private static final String TAG = "LegacyTest";

    // TODO: expand test to cover secondary storage devices
    private String mVolumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long POLLING_SLEEP_MILLIS = 100;

    /**
     * Number of media items to insert for {@link #testLegacy_Extreme()}.
     */
    private static final int EXTREME_COUNT = 10_000;

    private Uri mExternalAudio;
    private Uri mExternalVideo;
    private Uri mExternalImages;
    private Uri mExternalDownloads;
    private Uri mExternalPlaylists;

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Using volume " + mVolumeName);
        mExternalAudio = MediaStore.Audio.Media.getContentUri(mVolumeName);
        mExternalVideo = MediaStore.Video.Media.getContentUri(mVolumeName);
        mExternalImages = MediaStore.Images.Media.getContentUri(mVolumeName);
        mExternalDownloads = MediaStore.Downloads.getContentUri(mVolumeName);

        Uri playlists = MediaStore.Audio.Playlists.getContentUri(mVolumeName);
        mExternalPlaylists = playlists.buildUpon()
                .appendQueryParameter("silent", "true").build();
    }

    private ContentValues generateValues(int mediaType, String mimeType, String dirName)
            throws Exception {
        return generateValues(mediaType, mimeType, dirName, 0);
    }

    private ContentValues generateValues(int mediaType, String mimeType, String dirName, int resId)
            throws Exception {
        final Context context = InstrumentationRegistry.getContext();

        final File dir = context.getSystemService(StorageManager.class)
                .getStorageVolume(MediaStore.Files.getContentUri(mVolumeName)).getDirectory();
        final File subDir = new File(dir, dirName);
        File file = new File(subDir, "legacy" + System.nanoTime() + "."
                + MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType));

        if (resId != 0) {
            file = stageFile(resId, file.getAbsolutePath());
        }

        final ContentValues values = new ContentValues();
        values.put(FileColumns.MEDIA_TYPE, mediaType);
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaColumns.VOLUME_NAME, mVolumeName);
        values.put(MediaColumns.DATE_ADDED, String.valueOf(System.currentTimeMillis() / 1_000));
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        return values;
    }

    private static File stageFile(int resId, String path) throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        final File file = new File(path);
        try (InputStream in = context.getResources().openRawResource(resId);
             OutputStream out = new FileOutputStream(file)) {
            FileUtils.copy(in, out);
        }
        return file;
    }

    @Test
    public void testLegacy_Orientation() throws Exception {
        // Use an image file with orientation of 90 degrees
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/jpeg", Environment.DIRECTORY_PICTURES, R.raw.orientation_90);
        values.put(MediaColumns.ORIENTATION, String.valueOf(90));
        doLegacy(mExternalImages, values);
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
        values.put(VideoColumns.IS_PRIVATE, String.valueOf(1));
        doLegacy(mExternalVideo, values);
    }

    @Test
    public void testLegacy_Image() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        values.put(ImageColumns.IS_PRIVATE, String.valueOf(1));
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

    /**
     * Test that migrating from legacy database with volume_name=NULL doesn't
     * result in empty cursor when queried.
     */
    @Test
    public void testMigrateNullVolumeName() throws Exception {
        final ContentValues values = generateValues(FileColumns.MEDIA_TYPE_IMAGE,
                "image/png", Environment.DIRECTORY_PICTURES);
        values.remove(MediaColumns.VOLUME_NAME);
        doLegacy(mExternalImages, values);
    }

    @Test
    public void testLegacy_PlaylistMap() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();

        final ContentValues audios[] = new ContentValues[] {
                generateValues(FileColumns.MEDIA_TYPE_AUDIO, "audio/mpeg",
                        Environment.DIRECTORY_MUSIC),
                generateValues(FileColumns.MEDIA_TYPE_AUDIO, "audio/mpeg",
                        Environment.DIRECTORY_MUSIC),
        };

        final String playlistMimeType = "audio/mpegurl";
        final ContentValues playlist = generateValues(FileColumns.MEDIA_TYPE_PLAYLIST,
                playlistMimeType, "Playlists");
        final String playlistName = "LegacyPlaylistName_" + System.nanoTime();
        playlist.put(MediaStore.Audio.PlaylistsColumns.NAME, playlistName);
        File playlistFile = new File(playlist.getAsString(MediaColumns.DATA));

        playlistFile.delete();

        final ContentValues playlistMap = new ContentValues();
        playlistMap.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, 1);

        prepareProviders(context, ui);

        try (ContentProviderClient legacy = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY_LEGACY)) {

            // Step 1: Insert the playlist entry into the playlists table.
            final Uri playlistUri = rewriteToLegacy(legacy.insert(
                    rewriteToLegacy(mExternalPlaylists), playlist));
            long playlistId = ContentUris.parseId(playlistUri);
            final Uri playlistMemberUri = MediaStore.rewriteToLegacy(
                    MediaStore.Audio.Playlists.Members.getContentUri(mVolumeName, playlistId)
                            .buildUpon()
                            .appendQueryParameter("silent", "true").build());


            for (ContentValues values : audios) {
                // Step 2: Write the audio file to the legacy mediastore.
                final Uri audioUri =
                        rewriteToLegacy(legacy.insert(rewriteToLegacy(mExternalAudio), values));
                // Remember our ID to check it later
                values.put(MediaColumns._ID, audioUri.getLastPathSegment());


                long audioId = ContentUris.parseId(audioUri);
                playlistMap.put(MediaStore.Audio.Playlists.Members.PLAYLIST_ID, playlistId);
                playlistMap.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);

                // Step 3: Add a mapping to playlist members.
                legacy.insert(playlistMemberUri, playlistMap);
            }

            // Insert a stale row, We only have 3 items in the database. #4 is a stale row
            // and will be skipped from the playlist during the migration.
            playlistMap.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, 4);
            legacy.insert(playlistMemberUri, playlistMap);

        }

        // This will delete MediaProvider data and restarts MediaProvider, and mounts storage.
        clearProviders(context, ui);

        // Verify scan on DEMAND doesn't delete any virtual playlist files.
        MediaStore.scanFile(context.getContentResolver(),
                Environment.getExternalStorageDirectory());

        // Playlist files are created from playlist NAME
        final File musicDir = new File(context.getSystemService(StorageManager.class)
                .getStorageVolume(MediaStore.Files.getContentUri(mVolumeName)).getDirectory(),
                Environment.DIRECTORY_MUSIC);
        playlistFile = new File(musicDir, playlistName + "."
                + MimeTypeMap.getSingleton().getExtensionFromMimeType(playlistMimeType));
        // Wait for scan on MEDIA_MOUNTED to create "real" playlist files.
        pollForFile(playlistFile);

        // Scan again to verify updated playlist metadata
        MediaStore.scanFile(context.getContentResolver(), playlistFile);

        try (ContentProviderClient modern = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            long legacyPlaylistId =
                    playlistMap.getAsLong(MediaStore.Audio.Playlists.Members.PLAYLIST_ID);
            long legacyAudioId1 = audios[0].getAsLong(MediaColumns._ID);
            long legacyAudioId2 = audios[1].getAsLong(MediaColumns._ID);

            // Verify that playlist_id matches with legacy playlist_id
            {
                Uri playlists = MediaStore.Audio.Playlists.getContentUri(mVolumeName);
                final String[] project = {FileColumns._ID, MediaStore.Audio.PlaylistsColumns.NAME};

                try (Cursor cursor = modern.query(playlists, project, null, null, null)) {
                    boolean found = false;
                    while(cursor.moveToNext()) {
                        if (cursor.getLong(0) == legacyPlaylistId) {
                            found = true;
                            assertEquals(playlistName, cursor.getString(1));
                            break;
                        }
                    }
                    assertTrue(found);
                }
            }

            // Verify that playlist_members map matches legacy playlist_members map.
            {
                 Uri members = MediaStore.Audio.Playlists.Members.getContentUri(
                        mVolumeName, legacyPlaylistId);
                 final String[] project = { MediaStore.Audio.Playlists.Members.AUDIO_ID };

                 try (Cursor cursor = modern.query(members, project, null, null,
                         MediaStore.Audio.Playlists.Members.DEFAULT_SORT_ORDER)) {
                     assertTrue(cursor.moveToNext());
                     assertEquals(legacyAudioId1, cursor.getLong(0));
                     assertTrue(cursor.moveToNext());
                     assertEquals(legacyAudioId2, cursor.getLong(0));
                     assertFalse(cursor.moveToNext());
                 }
            }

            // Verify that migrated playlist audio_id refers to legacy audio file.
            {
                Uri modernAudioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(mVolumeName), legacyAudioId1);
                final String[] project = {FileColumns.DATA};

                try (Cursor cursor = modern.query(modernAudioUri, project, null, null, null)) {
                    assertTrue(cursor.moveToFirst());
                    assertEquals(audios[0].getAsString(MediaColumns.DATA), cursor.getString(0));
                }
            }
        }
    }

    private static void prepareProviders(Context context, UiAutomation ui) throws Exception {
        final ProviderInfo legacyProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY_LEGACY, 0);
        final ProviderInfo modernProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY, 0);

        // Only continue if we have both providers to test against
        Assume.assumeNotNull(legacyProvider);
        Assume.assumeNotNull(modernProvider);

        // Clear data on the legacy provider so that we create a database
        waitForMountedAndIdle(context.getContentResolver());
        executeShellCommand("sync", ui);
        executeShellCommand("pm clear " + legacyProvider.applicationInfo.packageName, ui);
        waitForMountedAndIdle(context.getContentResolver());
    }

    private static void clearProviders(Context context, UiAutomation ui) throws Exception {
        final ProviderInfo modernProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY, 0);

        // Clear data on the modern provider so that the initial scan recovers
        // metadata from the legacy provider
        waitForMountedAndIdle(context.getContentResolver());
        executeShellCommand("sync", ui);
        executeShellCommand("pm clear " + modernProvider.applicationInfo.packageName, ui);
        waitForMountedAndIdle(context.getContentResolver());
    }

    /**
     * Verify that a legacy database with thousands of media entries can be
     * successfully migrated.
     */
    @Test
    public void testLegacy_Extreme() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();

        prepareProviders(context, ui);

        // Create thousands of items in the legacy provider
        try (ContentProviderClient legacy = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY_LEGACY)) {
            // We're purposefully "silent" to avoid creating the raw file on
            // disk, since otherwise this test would take several minutes
            final Uri insertTarget = rewriteToLegacy(
                    mExternalImages.buildUpon().appendQueryParameter("silent", "true").build());

            final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            for (int i = 0; i < EXTREME_COUNT; i++) {
                ops.add(ContentProviderOperation.newInsert(insertTarget)
                        .withValues(generateValues(FileColumns.MEDIA_TYPE_IMAGE, "image/png",
                                Environment.DIRECTORY_PICTURES))
                        .build());

                if ((ops.size() > 1_000) || (i == (EXTREME_COUNT - 1))) {
                    Log.v(TAG, "Inserting items...");
                    legacy.applyBatch(MediaStore.AUTHORITY_LEGACY, ops);
                    ops.clear();
                }
            }
        }

        clearProviders(context, ui);

        // Confirm that details from legacy provider have migrated
        try (ContentProviderClient modern = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            try (Cursor cursor = modern.query(mExternalImages, null, null, null)) {
                Truth.assertThat(cursor.getCount()).isAtLeast(EXTREME_COUNT);
            }
        }
    }

    private void doLegacy(Uri collectionUri, ContentValues values) throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();

        prepareProviders(context, ui);

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

            // Drop raw path, since we may rename pending or trashed files
            values.remove(FileColumns.DATA);
        }

        // This will delete MediaProvider data and restarts MediaProvider, and mounts storage.
        clearProviders(context, ui);

        // Make sure we do not lose the ORIENTATION column after database migration
        // We check this column again after the scan
        if (values.getAsString(MediaColumns.ORIENTATION) != null) {
            assertOrientationColumn(collectionUri, values, context, legacyFile);
        }

        // And force a scan to confirm upgraded data survives
        MediaStore.scanVolume(context.getContentResolver(),
                MediaStore.getVolumeName(collectionUri));
        assertColumnsHaveExpectedValues(collectionUri, values, context, legacyFile);

    }

    private void assertOrientationColumn(Uri collectionUri, ContentValues originalValues,
            Context context, File legacyFile) throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.ORIENTATION, (String) originalValues.get(MediaColumns.ORIENTATION));
        assertColumnsHaveExpectedValues(collectionUri, values, context, legacyFile);
    }

    private void assertColumnsHaveExpectedValues(Uri collectionUri, ContentValues values,
            Context context, File legacyFile) throws Exception {
        // Confirm that details from legacy provider have migrated
        try (ContentProviderClient modern = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            final Bundle extras = new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    MediaColumns.DISPLAY_NAME + "=?");
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[] { legacyFile.getName() });
            extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE);

            try (Cursor cursor = pollForCursor(modern, collectionUri, extras)) {
                assertNotNull(cursor);
                assertTrue(cursor.moveToFirst());
                for (String key : values.keySet()) {
                    assertWithMessage("Checking key %s", key)
                            .that(cursor.getString(cursor.getColumnIndexOrThrow(key)))
                            .isEqualTo(values.get(key));
                }
            }
        }
    }

    private static void waitForMountedAndIdle(ContentResolver resolver) {
        // We purposefully perform these operations twice in this specific
        // order, since clearing the data on a package can asynchronously
        // perform a vold reset, which can make us think storage is ready and
        // mounted when it's moments away from being torn down.
        pollForExternalStorageState();
        MediaStore.waitForIdle(resolver);
        pollForExternalStorageState();
        MediaStore.waitForIdle(resolver);
    }

    private static Cursor pollForCursor(ContentProviderClient modern, Uri collectionUri,
            Bundle extras) throws Exception {
        Cursor cursor = null;
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
           try {
               cursor = modern.query(collectionUri, null, extras, null);
               return cursor;
           } catch (IllegalArgumentException e) {
               // try again
           }
            Log.v(TAG, "Waiting for..." + collectionUri);
            SystemClock.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for uri " + collectionUri);
        return cursor;
    }

    private static void pollForFile(File file) {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (file.exists()) return;

            Log.v(TAG, "Waiting for..." + file);
            SystemClock.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for file " + file);
    }

    private static void pollForExternalStorageState() {
        final File target = Environment.getExternalStorageDirectory();
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            try {
                if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(target))
                        && Os.statvfs(target.getAbsolutePath()).f_blocks > 0) {
                    return;
                }
            } catch (ErrnoException ignored) {
            }

            Log.v(TAG, "Waiting for external storage...");
            SystemClock.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }

    public static String executeShellCommand(String command, UiAutomation uiAutomation)
            throws IOException {
        int attempt = 0;
        while (attempt++ < 5) {
            try {
                return executeShellCommandInternal(command, uiAutomation);
            } catch (InterruptedIOException e) {
                // Hmm, we had trouble executing the shell command; the best we
                // can do is try again a few more times
                Log.v(TAG, "Trouble executing " + command + "; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    public static String executeShellCommandInternal(String command, UiAutomation uiAutomation)
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
