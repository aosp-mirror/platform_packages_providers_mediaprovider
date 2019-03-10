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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import com.android.providers.media.MediaProvider;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;
import com.android.providers.media.tests.R;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class ModernMediaScannerTest {

    // TODO: scan directory-vs-files and confirm identical results
    // TODO: confirm scan adding, changing, removing file
    // TODO: confirm scan adding, removing .nomedia files

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
    public void testIsDirectoryHidden() throws Exception {
        for (String prefix : new String[] {
                "/storage/emulated/0",
                "/storage/0000-0000"
        }) {
            assertFalse(isDirectoryHidden(new File(prefix)));
            assertFalse(isDirectoryHidden(new File(prefix + "/Android/")));
            assertFalse(isDirectoryHidden(new File(prefix + "/Android/sandbox/")));

            assertTrue(isDirectoryHidden(new File(prefix + "/.hidden/")));
            assertTrue(isDirectoryHidden(new File(prefix + "/Android/data/")));
            assertTrue(isDirectoryHidden(new File(prefix + "/Android/obb/")));
        }
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

    private void doPlaylist(int res, String name) throws Exception {
        Assume.assumeTrue(MediaProvider.ENABLE_MODERN_SCANNER);

        final File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        dir.mkdirs();
        FileUtils.deleteContents(dir);

        stage(R.raw.test_audio, new File(dir, "001.mp3"));
        stage(R.raw.test_audio, new File(dir, "002.mp3"));
        stage(R.raw.test_audio, new File(dir, "003.mp3"));
        stage(res, new File(dir, name));

        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern");
        final ModernMediaScanner modern = new ModernMediaScanner(isolatedContext);
        modern.scanDirectory(dir);

        // We should see a new playlist with all three items as members
        final long playlistId;
        try (Cursor cursor = isolatedContext.getContentResolver().query(
                MediaStore.Files.EXTERNAL_CONTENT_URI, new String[] { FileColumns._ID },
                FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_PLAYLIST, null, null)) {
            assertTrue(cursor.moveToFirst());
            playlistId = cursor.getLong(0);
        }

        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId);
        try (Cursor cursor = isolatedContext.getContentResolver().query(membersUri, new String[] {
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
        new File(dir, "002.mp3").delete();
        new File(dir, name).setLastModified(10L);
        modern.scanDirectory(dir);

        try (Cursor cursor = isolatedContext.getContentResolver().query(membersUri, new String[] {
                MediaColumns.DISPLAY_NAME
        }, null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("001.mp3", cursor.getString(0));
            cursor.moveToNext();
            assertEquals("003.mp3", cursor.getString(0));
        }
    }
}
