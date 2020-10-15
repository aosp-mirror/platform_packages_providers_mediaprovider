/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.Playlists;
import android.provider.MediaStore.MediaColumns;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.client.PerformanceTest.Timer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class PlaylistPerformanceTest {
    private static final Uri AUDIO_URI = MediaStore.Audio.Media
            .getContentUri(VOLUME_EXTERNAL_PRIMARY);
    private static final Uri PLAYLISTS_URI = Playlists
            .getContentUri(VOLUME_EXTERNAL_PRIMARY);

    private Context mContext;
    private ContentResolver mContentResolver;

    private String mRelativePath;
    private File mTestDir;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mContentResolver = mContext.getContentResolver();

        mRelativePath = Environment.DIRECTORY_MUSIC + "/test_" + System.nanoTime();
        mTestDir = new File(Environment.getExternalStorageDirectory(), mRelativePath);
        assertTrue(mTestDir.mkdirs());
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContentsAndDir(mTestDir);
    }

    @Test
    public void testBulkInsertPlaylistMembers() throws Exception {
        final int expected = 200;
        final long[] memberIds = new long[expected];
        for (int i = 0; i < expected; i++) {
            memberIds[i] = (createAudio(i));
        }

        final Uri playlistUri = createPlaylist();
        final Uri membersUri = Playlists.Members
                .getContentUri(VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(playlistUri));

        final ContentValues[] valuesArray = createValuesForMembers(memberIds);
        final Timer addMembers = new Timer("bulk insert " + expected);
        addMembers.start();
        final int actual = mContentResolver.bulkInsert(membersUri, valuesArray);
        addMembers.stop();
        addMembers.dumpResults();
        assertEquals(expected, actual);
    }

    @Test
    public void testDeletePlaylistMembers() throws Exception {
        final int expected = 200;
        final long[] memberIds = new long[expected];
        for (int i = 0; i < expected; i++) {
            memberIds[i] = (createAudio(i));
        }

        final Uri playlistUri = createPlaylist();
        final Uri membersUri = Playlists.Members
                .getContentUri(VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(playlistUri));

        final ContentValues[] valuesArray = createValuesForMembers(memberIds);
        assertEquals(expected, mContentResolver.bulkInsert(membersUri, valuesArray));

        final Timer deleteMembers = new Timer("delete member " + expected);
        final String[] whereArgs = new String[] { "0" };
        int actual = 0;
        for (int i = 0; i < memberIds.length; i++) {
            whereArgs[0] = "" + memberIds[i];
            deleteMembers.start();
            actual += mContentResolver.delete(AUDIO_URI, MediaColumns._ID + "=?", whereArgs);
            deleteMembers.stop();
        }
        deleteMembers.dumpResults();
        assertEquals(expected, actual);
    }

    private @NonNull Uri createPlaylist() {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "Test_Playlist");
        values.put(MediaColumns.RELATIVE_PATH, mRelativePath);
        values.put(MediaColumns.MIME_TYPE, "audio/x-mpegurl");

        final Uri playlistUri = mContentResolver.insert(PLAYLISTS_URI, values);
        assertNotNull(playlistUri);
        return playlistUri;
    }

    private long createAudio(int num) throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "Test_Song_" + num);
        values.put(MediaColumns.RELATIVE_PATH, mRelativePath);
        values.put(MediaColumns.MIME_TYPE, "audio/mpeg");

        final Uri uri = mContentResolver.insert(AUDIO_URI, values);
        assertNotNull(uri);
        try (OutputStream ignore = mContentResolver.openOutputStream(uri)) {
        }
        return ContentUris.parseId(uri);
    }

    private @NonNull ContentValues[] createValuesForMembers(@NonNull long[] memberIds) {
        final ContentValues[] valuesArray = new ContentValues[memberIds.length];
        for (int i = 0; i < memberIds.length; i++) {
            final ContentValues values = new ContentValues();
            values.put(Playlists.Members.AUDIO_ID, memberIds[i]);
            valuesArray[i] = values;
        }
        return valuesArray;
    }
}

