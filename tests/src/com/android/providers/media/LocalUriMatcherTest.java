/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalUriMatcherTest {

    private LocalUriMatcher mMatcher = new LocalUriMatcher(MediaStore.AUTHORITY);
    private static final String CONTENT_SCHEME = "content";

    @Test
    public void testPublicUris() {
        assertMatchesPublic(LocalUriMatcher.PICKER, assembleTestUri(new String[] {"picker"}));
        assertMatchesPublic(
                LocalUriMatcher.PICKER_ID,
                assembleTestUri(new String[] {"picker", Integer.toString(1), Integer.toString(1)}));
        assertMatchesPublic(
                LocalUriMatcher.PICKER_ID,
                assembleTestUri(new String[] {"picker", "0", "anything", "media", "anything"}));

        assertMatchesPublic(LocalUriMatcher.CLI, assembleTestUri(new String[] {"cli"}));

        assertMatchesPublic(
                LocalUriMatcher.IMAGES_MEDIA,
                assembleTestUri(new String[] {"anything", "images", "media"}));
        assertMatchesPublic(
                LocalUriMatcher.IMAGES_MEDIA_ID,
                assembleTestUri(new String[] {"anything", "images", "media", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.IMAGES_MEDIA_ID_THUMBNAIL,
                assembleTestUri(new String[] {"anything", "images", "media", "1234", "thumbnail"}));
        assertMatchesPublic(
                LocalUriMatcher.IMAGES_THUMBNAILS,
                assembleTestUri(new String[] {"anything", "images", "thumbnails"}));
        assertMatchesPublic(
                LocalUriMatcher.IMAGES_THUMBNAILS_ID,
                assembleTestUri(new String[] {"anything", "images", "thumbnails", "1234"}));

        assertMatchesPublic(
                LocalUriMatcher.AUDIO_MEDIA,
                assembleTestUri(new String[] {"anything", "audio", "media"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_MEDIA_ID,
                assembleTestUri(new String[] {"anything", "audio", "media", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_MEDIA_ID_GENRES,
                assembleTestUri(new String[] {"anything", "audio", "media", "1234", "genres"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_MEDIA_ID_GENRES_ID,
                assembleTestUri(
                        new String[] {"anything", "audio", "media", "1234", "genres", "5678"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_GENRES,
                assembleTestUri(new String[] {"anything", "audio", "genres"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_GENRES_ID,
                assembleTestUri(new String[] {"anything", "audio", "genres", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_GENRES_ID_MEMBERS,
                assembleTestUri(new String[] {"anything", "audio", "genres", "1234", "members"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_GENRES_ALL_MEMBERS,
                assembleTestUri(new String[] {"anything", "audio", "genres", "all", "members"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_PLAYLISTS,
                assembleTestUri(new String[] {"anything", "audio", "playlists"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_PLAYLISTS_ID,
                assembleTestUri(new String[] {"anything", "audio", "playlists", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_PLAYLISTS_ID_MEMBERS,
                assembleTestUri(
                        new String[] {"anything", "audio", "playlists", "1234", "members"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_PLAYLISTS_ID_MEMBERS_ID,
                assembleTestUri(
                        new String[] {
                            "anything", "audio", "playlists", "1234", "members", "5678"
                        }));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ARTISTS,
                assembleTestUri(new String[] {"anything", "audio", "artists"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ARTISTS_ID,
                assembleTestUri(new String[] {"anything", "audio", "artists", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ARTISTS_ID_ALBUMS,
                assembleTestUri(new String[] {"anything", "audio", "artists", "1234", "albums"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ALBUMS,
                assembleTestUri(new String[] {"anything", "audio", "albums"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ALBUMS_ID,
                assembleTestUri(new String[] {"anything", "audio", "albums", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ALBUMART,
                assembleTestUri(new String[] {"anything", "audio", "albumart"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ALBUMART_ID,
                assembleTestUri(new String[] {"anything", "audio", "albumart", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.AUDIO_ALBUMART_FILE_ID,
                assembleTestUri(new String[] {"anything", "audio", "media", "1234", "albumart"}));

        assertMatchesPublic(
                LocalUriMatcher.VIDEO_MEDIA,
                assembleTestUri(new String[] {"anything", "video", "media"}));
        assertMatchesPublic(
                LocalUriMatcher.VIDEO_MEDIA_ID,
                assembleTestUri(new String[] {"anything", "video", "media", "1234"}));
        assertMatchesPublic(
                LocalUriMatcher.VIDEO_MEDIA_ID_THUMBNAIL,
                assembleTestUri(new String[] {"anything", "video", "media", "1234", "thumbnail"}));
        assertMatchesPublic(
                LocalUriMatcher.VIDEO_THUMBNAILS,
                assembleTestUri(new String[] {"anything", "video", "thumbnails"}));
        assertMatchesPublic(
                LocalUriMatcher.VIDEO_THUMBNAILS_ID,
                assembleTestUri(new String[] {"anything", "video", "thumbnails", "1234"}));

        assertMatchesPublic(
                LocalUriMatcher.MEDIA_SCANNER,
                assembleTestUri(new String[] {"anything", "media_scanner"}));

        assertMatchesPublic(
                LocalUriMatcher.FS_ID, assembleTestUri(new String[] {"anything", "fs_id"}));
        assertMatchesPublic(
                LocalUriMatcher.VERSION, assembleTestUri(new String[] {"anything", "version"}));

        assertMatchesPublic(
                LocalUriMatcher.FILES, assembleTestUri(new String[] {"anything", "file"}));
        assertMatchesPublic(
                LocalUriMatcher.FILES_ID,
                assembleTestUri(new String[] {"anything", "file", "1234"}));

        assertMatchesPublic(
                LocalUriMatcher.DOWNLOADS, assembleTestUri(new String[] {"anything", "downloads"}));
        assertMatchesPublic(
                LocalUriMatcher.DOWNLOADS_ID,
                assembleTestUri(new String[] {"anything", "downloads", "1234"}));

        assertThrows(
                IllegalStateException.class,
                () -> {
                    assertMatchesPublic(
                            LocalUriMatcher.PICKER_INTERNAL_MEDIA_ALL,
                            assembleTestUri(new String[] {"picker_internal", "media", "all"}));
                });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    assertMatchesPublic(
                            LocalUriMatcher.PICKER_INTERNAL_MEDIA_LOCAL,
                            assembleTestUri(new String[] {"picker_internal", "media", "local"}));
                });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    assertMatchesPublic(
                            LocalUriMatcher.PICKER_INTERNAL_ALBUMS_ALL,
                            assembleTestUri(new String[] {"picker_internal", "albums", "all"}));
                });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    assertMatchesPublic(
                            LocalUriMatcher.PICKER_INTERNAL_ALBUMS_LOCAL,
                            assembleTestUri(new String[] {"picker_internal", "albums", "local"}));
                });
    }

    @Test
    public void testHiddenUris() {
        assertMatchesHidden(LocalUriMatcher.PICKER, assembleTestUri(new String[] {"picker"}));
        assertMatchesHidden(
                LocalUriMatcher.PICKER_ID,
                assembleTestUri(new String[] {"picker", Integer.toString(1), Integer.toString(1)}));
        assertMatchesHidden(
                LocalUriMatcher.PICKER_ID,
                assembleTestUri(new String[] {"picker", "0", "anything", "media", "anything"}));

        assertMatchesHidden(LocalUriMatcher.CLI, assembleTestUri(new String[] {"cli"}));

        assertMatchesHidden(
                LocalUriMatcher.IMAGES_MEDIA,
                assembleTestUri(new String[] {"anything", "images", "media"}));
        assertMatchesHidden(
                LocalUriMatcher.IMAGES_MEDIA_ID,
                assembleTestUri(new String[] {"anything", "images", "media", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.IMAGES_MEDIA_ID_THUMBNAIL,
                assembleTestUri(new String[] {"anything", "images", "media", "1234", "thumbnail"}));
        assertMatchesHidden(
                LocalUriMatcher.IMAGES_THUMBNAILS,
                assembleTestUri(new String[] {"anything", "images", "thumbnails"}));
        assertMatchesHidden(
                LocalUriMatcher.IMAGES_THUMBNAILS_ID,
                assembleTestUri(new String[] {"anything", "images", "thumbnails", "1234"}));

        assertMatchesHidden(
                LocalUriMatcher.AUDIO_MEDIA,
                assembleTestUri(new String[] {"anything", "audio", "media"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_MEDIA_ID,
                assembleTestUri(new String[] {"anything", "audio", "media", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_MEDIA_ID_GENRES,
                assembleTestUri(new String[] {"anything", "audio", "media", "1234", "genres"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_MEDIA_ID_GENRES_ID,
                assembleTestUri(
                        new String[] {"anything", "audio", "media", "1234", "genres", "5678"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_GENRES,
                assembleTestUri(new String[] {"anything", "audio", "genres"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_GENRES_ID,
                assembleTestUri(new String[] {"anything", "audio", "genres", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_GENRES_ID_MEMBERS,
                assembleTestUri(new String[] {"anything", "audio", "genres", "1234", "members"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_GENRES_ALL_MEMBERS,
                assembleTestUri(new String[] {"anything", "audio", "genres", "all", "members"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_PLAYLISTS,
                assembleTestUri(new String[] {"anything", "audio", "playlists"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_PLAYLISTS_ID,
                assembleTestUri(new String[] {"anything", "audio", "playlists", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_PLAYLISTS_ID_MEMBERS,
                assembleTestUri(
                        new String[] {"anything", "audio", "playlists", "1234", "members"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_PLAYLISTS_ID_MEMBERS_ID,
                assembleTestUri(
                        new String[] {
                            "anything", "audio", "playlists", "1234", "members", "5678"
                        }));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ARTISTS,
                assembleTestUri(new String[] {"anything", "audio", "artists"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ARTISTS_ID,
                assembleTestUri(new String[] {"anything", "audio", "artists", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ARTISTS_ID_ALBUMS,
                assembleTestUri(new String[] {"anything", "audio", "artists", "1234", "albums"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ALBUMS,
                assembleTestUri(new String[] {"anything", "audio", "albums"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ALBUMS_ID,
                assembleTestUri(new String[] {"anything", "audio", "albums", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ALBUMART,
                assembleTestUri(new String[] {"anything", "audio", "albumart"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ALBUMART_ID,
                assembleTestUri(new String[] {"anything", "audio", "albumart", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.AUDIO_ALBUMART_FILE_ID,
                assembleTestUri(new String[] {"anything", "audio", "media", "1234", "albumart"}));

        assertMatchesHidden(
                LocalUriMatcher.VIDEO_MEDIA,
                assembleTestUri(new String[] {"anything", "video", "media"}));
        assertMatchesHidden(
                LocalUriMatcher.VIDEO_MEDIA_ID,
                assembleTestUri(new String[] {"anything", "video", "media", "1234"}));
        assertMatchesHidden(
                LocalUriMatcher.VIDEO_MEDIA_ID_THUMBNAIL,
                assembleTestUri(new String[] {"anything", "video", "media", "1234", "thumbnail"}));
        assertMatchesHidden(
                LocalUriMatcher.VIDEO_THUMBNAILS,
                assembleTestUri(new String[] {"anything", "video", "thumbnails"}));
        assertMatchesHidden(
                LocalUriMatcher.VIDEO_THUMBNAILS_ID,
                assembleTestUri(new String[] {"anything", "video", "thumbnails", "1234"}));

        assertMatchesHidden(
                LocalUriMatcher.MEDIA_SCANNER,
                assembleTestUri(new String[] {"anything", "media_scanner"}));

        assertMatchesHidden(
                LocalUriMatcher.FS_ID, assembleTestUri(new String[] {"anything", "fs_id"}));
        assertMatchesHidden(
                LocalUriMatcher.VERSION, assembleTestUri(new String[] {"anything", "version"}));

        assertMatchesHidden(
                LocalUriMatcher.FILES, assembleTestUri(new String[] {"anything", "file"}));
        assertMatchesHidden(
                LocalUriMatcher.FILES_ID,
                assembleTestUri(new String[] {"anything", "file", "1234"}));

        assertMatchesHidden(
                LocalUriMatcher.DOWNLOADS, assembleTestUri(new String[] {"anything", "downloads"}));
        assertMatchesHidden(
                LocalUriMatcher.DOWNLOADS_ID,
                assembleTestUri(new String[] {"anything", "downloads", "1234"}));

        assertMatchesHidden(
                LocalUriMatcher.PICKER_INTERNAL_MEDIA_ALL,
                assembleTestUri(new String[] {"picker_internal", "media", "all"}));
        assertMatchesHidden(
                LocalUriMatcher.PICKER_INTERNAL_MEDIA_LOCAL,
                assembleTestUri(new String[] {"picker_internal", "media", "local"}));
        assertMatchesHidden(
                LocalUriMatcher.PICKER_INTERNAL_ALBUMS_ALL,
                assembleTestUri(new String[] {"picker_internal", "albums", "all"}));
        assertMatchesHidden(
                LocalUriMatcher.PICKER_INTERNAL_ALBUMS_LOCAL,
                assembleTestUri(new String[] {"picker_internal", "albums", "local"}));
    }

    private void assertMatchesHidden(int match, Uri uri) {
        assertEquals(match, mMatcher.matchUri(uri, true));
    }

    private void assertMatchesPublic(int match, Uri uri) {
        assertEquals(match, mMatcher.matchUri(uri, false));
    }

    private Uri assembleTestUri(String[] paths) {
        return assembleTestUri(CONTENT_SCHEME, MediaStore.AUTHORITY, paths);
    }

    private Uri assembleTestUri(String scheme, String authority, String[] paths) {
        final Uri.Builder builder = Uri.EMPTY.buildUpon();
        builder.scheme(scheme);
        builder.encodedAuthority(authority);
        for (String path : paths) {
            builder.appendPath(path);
        }
        return builder.build();
    }
}
