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

import static android.provider.MediaStore.VOLUME_EXTERNAL;
import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;
import static android.provider.MediaStore.VOLUME_INTERNAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.Playlists;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verify typical behaviors of {@link MediaStore.Audio.Playlists} from an
 * external client app. Exercises all supported playlist formats.
 */
@RunWith(Parameterized.class)
public class ClientPlaylistTest {
    private static final String TAG = "ClientPlaylistTest";

    // TODO: verify playlists relative paths are rewritten when contained music
    // files are moved/deleted, or when the playlist itself is moved

    // TODO: verify that missing playlist items are preserved

    private final Uri mExternalAudio = MediaStore.Audio.Media
            .getContentUri(VOLUME_EXTERNAL_PRIMARY);
    private final Uri mExternalPlaylists = MediaStore.Audio.Playlists
            .getContentUri(VOLUME_EXTERNAL_PRIMARY);

    private final ContentValues mValues = new ContentValues();

    private Context mContext;
    private ContentResolver mContentResolver;

    private long mRed;
    private long mGreen;
    private long mBlue;

    @Parameter(0)
    public String mMimeType;

    @Parameters
    public static Iterable<? extends Object> data() {
        return Arrays.asList(
                "audio/x-mpegurl",
                "audio/x-scpls",
                "application/vnd.ms-wpl",
                "application/xspf+xml");
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mContentResolver = mContext.getContentResolver();

        mRed = createAudio();
        mGreen = createAudio();
        mBlue = createAudio();

        Log.d(TAG, "Using MIME type " + mMimeType);
    }

    @After
    public void tearDown() throws Exception {
        mContentResolver.delete(ContentUris.withAppendedId(mExternalAudio, mRed), null);
        mContentResolver.delete(ContentUris.withAppendedId(mExternalAudio, mGreen), null);
        mContentResolver.delete(ContentUris.withAppendedId(mExternalAudio, mBlue), null);
    }

    @Test
    public void testAdd() throws Exception {
        mValues.clear();
        mValues.put(MediaColumns.DISPLAY_NAME, "Playlist " + System.nanoTime());
        mValues.put(MediaColumns.MIME_TYPE, mMimeType);

        final Uri playlistUri = mContentResolver.insert(mExternalPlaylists, mValues);
        final Uri membersUri = MediaStore.Audio.Playlists.Members
                .getContentUri(VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(playlistUri));

        final TestContentObserver obs = TestContentObserver.create(
                playlistUri, ContentResolver.NOTIFY_INSERT);
        // Inserting without ordering will always append
        mValues.clear();
        mValues.put(Playlists.Members.AUDIO_ID, mRed);
        Uri resultUri = mContentResolver.insert(membersUri, mValues);
        obs.waitForChange();
        assertEquals(ContentUris.withAppendedId(membersUri, 1), resultUri);

        mValues.put(Playlists.Members.AUDIO_ID, mGreen);
        resultUri = mContentResolver.insert(membersUri, mValues);
        obs.waitForChange();
        assertEquals(ContentUris.withAppendedId(membersUri, 2), resultUri);
        assertMembers(Arrays.asList(
                Pair.create(mRed, 1),
                Pair.create(mGreen, 2)), queryMembers(membersUri));

        // Inserting with ordering should be injected
        mValues.clear();
        mValues.put(Playlists.Members.AUDIO_ID, mBlue);
        mValues.put(Playlists.Members.PLAY_ORDER, 1);
        resultUri = mContentResolver.insert(membersUri, mValues);
        obs.waitForChange();
        assertEquals(ContentUris.withAppendedId(membersUri, 1), resultUri);
        assertMembers(Arrays.asList(
                Pair.create(mBlue, 1),
                Pair.create(mRed, 2),
                Pair.create(mGreen, 3)), queryMembers(membersUri));

        obs.unregister();
    }

    @Test
    public void testMove() throws Exception {
        final long playlistId = createPlaylist(mRed, mGreen, mBlue);
        Uri playlistUri = ContentUris.withAppendedId(
                MediaStore.Audio.Playlists.getContentUri(VOLUME_EXTERNAL), playlistId);
        final Uri membersUri = Playlists.Members.getContentUri(VOLUME_EXTERNAL_PRIMARY, playlistId);

        TestContentObserver obs = TestContentObserver.create(
                playlistUri, ContentResolver.NOTIFY_UPDATE);


        // Simple move forwards
        boolean result = Playlists.Members.moveItem(mContentResolver, playlistId, 0, 2);
        obs.waitForChange();
        assertTrue(result);
        assertMembers(Arrays.asList(
                Pair.create(mGreen, 1),
                Pair.create(mBlue, 2),
                Pair.create(mRed, 3)), queryMembers(membersUri));

        // Simple move backwards
        result = Playlists.Members.moveItem(mContentResolver, playlistId, 2, 0);
        obs.waitForChange();
        assertTrue(result);
        assertMembers(Arrays.asList(
                Pair.create(mRed, 1),
                Pair.create(mGreen, 2),
                Pair.create(mBlue, 3)), queryMembers(membersUri));

        playlistUri = ContentUris.withAppendedId(
                MediaStore.Audio.Playlists.getContentUri(VOLUME_EXTERNAL_PRIMARY), playlistId);
        obs = TestContentObserver.create(playlistUri, ContentResolver.NOTIFY_UPDATE);
        // Advanced moves using query args
        mValues.clear();
        mValues.put(Playlists.Members.PLAY_ORDER, 1);
        int count = mContentResolver.update(membersUri, mValues,
                Playlists.Members.PLAY_ORDER + "=?", new String[] { "2" });
        obs.waitForChange();
        assertEquals(1, count);
        assertMembers(Arrays.asList(
                Pair.create(mGreen, 1),
                Pair.create(mRed, 2),
                Pair.create(mBlue, 3)), queryMembers(membersUri));


        count = mContentResolver.update(membersUri, mValues,
                Playlists.Members.PLAY_ORDER + "=2", null);
        obs.waitForChange();
        assertEquals(1, count);
        assertMembers(Arrays.asList(
                Pair.create(mRed, 1),
                Pair.create(mGreen, 2),
                Pair.create(mBlue, 3)), queryMembers(membersUri));

        obs.unregister();
    }

    @Test
    public void testRemove() throws Exception {
        final long playlistId = createPlaylist(mRed, mGreen, mBlue);
        final Uri membersUri = Playlists.Members.getContentUri(VOLUME_EXTERNAL_PRIMARY, playlistId);

        final TestContentObserver obs = TestContentObserver.create(
                membersUri, ContentResolver.NOTIFY_DELETE);

        // Simple delete in middle, duplicates are okay
        int count = mContentResolver.delete(membersUri, Playlists.Members.PLAY_ORDER + "=?",
                new String[] { "2" });
        obs.waitForChange();
        assertEquals(count, 1);
        assertMembers(Arrays.asList(
                Pair.create(mRed, 1),
                Pair.create(mBlue, 2)), queryMembers(membersUri));

        count = mContentResolver.delete(membersUri, Playlists.Members.PLAY_ORDER + "=2", null);
        obs.waitForChange();
        assertEquals(count, 1);
        assertMembers(Arrays.asList(
                Pair.create(mRed, 1)), queryMembers(membersUri));

        obs.unregister();
    }

    /**
     * Since playlist files are written on a specific storage device, they can
     * only contain media from that same storage device. This test verifies that
     * trying to cross the streams will fail.
     */
    @Test
    public void testVolumeName() throws Exception {
        mValues.clear();
        mValues.put(MediaColumns.DISPLAY_NAME, "Playlist " + System.nanoTime());
        mValues.put(MediaColumns.MIME_TYPE, mMimeType);

        final Uri playlist = mContentResolver.insert(mExternalPlaylists, mValues);
        final Uri members = MediaStore.Audio.Playlists.Members
                .getContentUri(VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(playlist));

        // Ensure that we've scanned internal storage to ensure that we have a
        // valid audio file
        MediaStore.scanVolume(mContentResolver, VOLUME_INTERNAL);

        final long internalId;
        try (Cursor c = mContentResolver.query(MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
                new String[] { BaseColumns._ID }, null, null)) {
            Assume.assumeTrue(c.moveToFirst());
            internalId = c.getLong(0);
        }

        try {
            mValues.clear();
            mValues.put(Playlists.Members.AUDIO_ID, internalId);
            mContentResolver.insert(members, mValues);
            fail();
        } catch (Exception expected) {
        }
    }

    public long createAudio() throws IOException {
        mValues.clear();
        mValues.put(MediaColumns.DISPLAY_NAME, "Song " + System.nanoTime());
        mValues.put(MediaColumns.MIME_TYPE, "audio/mpeg");

        final Uri uri = mContentResolver.insert(mExternalAudio, mValues);
        try (OutputStream out = mContentResolver.openOutputStream(uri)) {
        }
        return ContentUris.parseId(uri);
    }

    public long createPlaylist(long... memberIds) throws IOException {
        mValues.clear();
        mValues.put(MediaColumns.DISPLAY_NAME, "Playlist " + System.nanoTime());
        mValues.put(MediaColumns.MIME_TYPE, mMimeType);
        final TestContentObserver obs = TestContentObserver.create(
                mExternalPlaylists,ContentResolver.NOTIFY_INSERT);

        final Uri playlist = mContentResolver.insert(mExternalPlaylists, mValues);
        obs.waitForChange();
        obs.unregister();
        final Uri members = MediaStore.Audio.Playlists.Members
                .getContentUri(VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(playlist));

        List<Pair<Long, Integer>> expected = new ArrayList<>();
        for (int i = 0; i < memberIds.length; i++) {
            final long memberId = memberIds[i];
            mValues.clear();
            mValues.put(Playlists.Members.AUDIO_ID, memberId);
            mContentResolver.insert(members, mValues);
            expected.add(Pair.create(memberId, i + 1));
        }

        assertMembers(expected, queryMembers(members));
        return ContentUris.parseId(playlist);
    }

    private void assertMembers(List<Pair<Long, Integer>> expected,
            List<Pair<Long, Integer>> actual) {
        assertEquals(expected.toString(), actual.toString());
    }

    private List<Pair<Long, Integer>> queryMembers(Uri uri) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                Playlists.Members.PLAY_ORDER + " ASC");

        final List<Pair<Long, Integer>> res = new ArrayList<>();
        try (Cursor c = mContentResolver.query(uri, new String[] {
                Playlists.Members.AUDIO_ID, Playlists.Members.PLAY_ORDER
        }, queryArgs, null)) {
            while (c.moveToNext()) {
                res.add(Pair.create(c.getLong(0), c.getInt(1)));
            }
        }
        return res;
    }

    /**
     * Observer that will wait for a specific change event to be delivered.
     */
    public static class TestContentObserver extends ContentObserver {
        private final int flags;

        private CountDownLatch latch = new CountDownLatch(1);

        private TestContentObserver(int flags) {
            super(null);
            this.flags = flags;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int flags) {
            Log.v(TAG, String.format("onChange(%b, %s, %d)", selfChange, uri.toString(), flags));

            if (flags == this.flags) {
                latch.countDown();
            }
        }

        public static TestContentObserver create(Uri uri, int flags) {
            final TestContentObserver obs = new TestContentObserver(flags);
            InstrumentationRegistry.getContext().getContentResolver()
                .registerContentObserver(uri, true, obs);
            return obs;
        }

        public void waitForChange() {
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            latch = new CountDownLatch(1);
        }

        public void unregister() {
            InstrumentationRegistry.getContext().getContentResolver()
                .unregisterContentObserver(this);
        }
    }
}
