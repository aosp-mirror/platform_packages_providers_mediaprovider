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

package com.android.providers.media.client;

import static android.provider.MediaStore.VOLUME_EXTERNAL;
import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.createNewPublicVolume;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.deletePublicVolumes;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.mountPublicVolume;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.unmountPublicVolume;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class PublicVolumePlaylistTest {
    @BeforeClass
    public static void setUp() throws Exception {
        createNewPublicVolume();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        deletePublicVolumes();
    }

    /**
     * Test that playlist query doesn't return audio files of ejected volume.
     */
    @Test
    // TODO(b/180910871) fix side effects
    @Ignore
    public void testEjectedVolume() throws Exception {
        ContentValues values = new ContentValues();
        values.clear();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Playlist " + System.nanoTime());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl");

        Context context = InstrumentationRegistry.getTargetContext();
        ContentResolver contentResolver = context.getContentResolver();
        MediaStore.waitForIdle(contentResolver);

        final Uri externalPlaylists = MediaStore.Audio.Playlists
                .getContentUri(VOLUME_EXTERNAL_PRIMARY);
        final Uri playlist = contentResolver.insert(externalPlaylists, values);
        assertNotNull(playlist);
        // Use external uri for playlists to be able to add audio files from
        // different volumes
        final Uri members = MediaStore.Audio.Playlists.Members
                .getContentUri(VOLUME_EXTERNAL, ContentUris.parseId(playlist));

        mountPublicVolume();

        // Create audio files in both volumes and add them to playlist.
        for (String volumeName : MediaStore.getExternalVolumeNames(context)) {
            values.clear();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Song " + System.nanoTime());
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");

            final Uri audioUri = contentResolver.insert(
                    MediaStore.Audio.Media.getContentUri(volumeName), values);
            assertNotNull(audioUri);
            try (OutputStream out = contentResolver.openOutputStream(audioUri)) {
            }

            values.clear();
            values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, ContentUris.parseId(audioUri));
            assertThat(contentResolver.insert(members, values)).isNotNull();
        }

        final int volumeCount = MediaStore.getExternalVolumeNames(context).size();
        // Verify that we can see audio files from both volumes in the playlist.
        try (Cursor c = contentResolver.query(members, new String[] {
                MediaStore.Audio.Playlists.Members.AUDIO_ID}, Bundle.EMPTY, null)) {
            assertThat(c.getCount()).isEqualTo(volumeCount);
        }

        unmountPublicVolume();
        // Verify that we don't see audio file from the ejected volume.
        try (Cursor c = contentResolver.query(members, new String[] {
                MediaStore.Audio.Playlists.Members.AUDIO_ID}, Bundle.EMPTY, null)) {
            assertThat(c.getCount()).isEqualTo(volumeCount-1);
        }
    }
}

