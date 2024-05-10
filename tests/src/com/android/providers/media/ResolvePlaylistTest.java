/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class ResolvePlaylistTest {
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

        mModern = new ModernMediaScanner(mIsolatedContext);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContents(mDir);
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
                new String[] { MediaStore.Files.FileColumns._ID },
                MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
                MediaStore.MediaColumns.DISPLAY_NAME
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
                MediaStore.MediaColumns.DISPLAY_NAME
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
                MediaStore.MediaColumns.DISPLAY_NAME
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
    public void testBrokenPlaylistM3u() throws Exception {
        final File music = new File(mDir, "Music");
        music.mkdirs();
        stage(R.raw.test_audio, new File(music, "001.mp3"));
        stage(R.raw.test_audio, new File(music, "002.mp3"));
        stage(R.raw.test_audio, new File(music, "003.mp3"));
        stage(R.raw.test_audio, new File(music, "004.mp3"));
        stage(R.raw.test_audio, new File(music, "005.mp3"));
        stage(R.raw.test_broken_m3u, new File(music, "test_broken.m3u"));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        final long playlistId;
        try (Cursor cursor = mIsolatedContext.getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[] { MediaStore.Files.FileColumns._ID },
                MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = mIsolatedResolver.query(membersUri, new String[] {
            MediaStore.MediaColumns.DISPLAY_NAME
        }, null, null, null)) {
            assertEquals(0, cursor.getCount());
        }
    }

    @Test
    public void testPlaylistDeletion() throws Exception {
        final File music = new File(mDir, "Music");
        music.mkdirs();
        stage(R.raw.test_audio, new File(music, "001.mp3"));
        stage(R.raw.test_audio, new File(music, "002.mp3"));
        stage(R.raw.test_audio, new File(music, "003.mp3"));
        stage(R.raw.test_audio, new File(music, "004.mp3"));
        stage(R.raw.test_audio, new File(music, "005.mp3"));
        stage(R.raw.test_m3u, new File(music, "test.m3u"));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        final Uri playlistUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI;
        final long playlistId;
        try (Cursor cursor = mIsolatedContext.getContentResolver().query(playlistUri,
                new String[] { MediaStore.Files.FileColumns._ID }, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final int count = mIsolatedContext.getContentResolver().delete(
                ContentUris.withAppendedId(playlistUri, playlistId), null);
        assertEquals(1, count);

        MediaStore.waitForIdle(mIsolatedResolver);

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = mIsolatedResolver.query(membersUri, null, null, null)) {
            assertEquals(0, cursor.getCount());
        }
    }

    @Test
    public void testPlaylistMembersDeletion() throws Exception {
        final File music = new File(mDir, "Music");
        music.mkdirs();
        stage(R.raw.test_audio, new File(music, "001.mp3"));
        stage(R.raw.test_audio, new File(music, "002.mp3"));
        stage(R.raw.test_audio, new File(music, "003.mp3"));
        stage(R.raw.test_audio, new File(music, "004.mp3"));
        stage(R.raw.test_audio, new File(music, "005.mp3"));
        stage(R.raw.test_m3u, new File(music, "test.m3u"));

        mModern.scanDirectory(mDir, REASON_UNKNOWN);

        final Uri playlistUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI;
        final long playlistId;
        try (Cursor cursor = mIsolatedContext.getContentResolver().query(playlistUri,
                new String[] { MediaStore.Files.FileColumns._ID }, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final int count = mIsolatedContext.getContentResolver().delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null);
        assertEquals(5, count);

        MediaStore.waitForIdle(mIsolatedResolver);

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = mIsolatedResolver.query(membersUri, null, null, null)) {
            assertEquals(0, cursor.getCount());
        }
    }

}
