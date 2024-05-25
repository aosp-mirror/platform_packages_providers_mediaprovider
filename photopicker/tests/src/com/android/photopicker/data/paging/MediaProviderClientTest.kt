/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.data.paging

import android.content.ContentResolver
import androidx.paging.PagingSource.LoadResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import com.android.photopicker.data.TestMediaProvider

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaProviderClientTest {
    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val testContentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)

    @Test
    fun testFetchAvailableProviders() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val availableProviders: List<Provider> =
            mediaProviderClient.fetchAvailableProviders(contentResolver = testContentResolver)

        assertThat(availableProviders.count()).isEqualTo(testContentProvider.providers.count())
        for (index in availableProviders.indices) {
            assertThat(availableProviders[index]).isEqualTo(testContentProvider.providers[index])
        }
    }

    @Test
    fun testFetchMediaPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val mediaLoadResult: LoadResult<MediaPageKey, Media> =
            mediaProviderClient.fetchMedia(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0))
            )

        assertThat(mediaLoadResult is LoadResult.Page).isTrue()

        val media: List<Media> = (mediaLoadResult as LoadResult.Page).data

        assertThat(media.count()).isEqualTo(testContentProvider.media.count())
        for (index in media.indices) {
            assertThat(media[index]).isEqualTo(testContentProvider.media[index])
        }
    }

    @Test
    fun testRefreshCloudMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> = mutableListOf(
            Provider(
                authority = "local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 0
            ),
            Provider(
                authority = "cloud_authority",
                mediaSource = MediaSource.REMOTE,
                uid = 1
            ),
            Provider(
                authority = "hypothetical_local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 2
            ),
        )

        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
    }

    @Test
    fun testRefreshLocalOnlyMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> = mutableListOf(
            Provider(
                authority = "local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 0
            ),
            Provider(
                authority = "hypothetical_local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 1
            ),
        )

        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()

        // TODO(b/340246010): Currently, we trigger sync for all available providers. This is
        //  because UI is responsible for triggering syncs which is sometimes required to enable
        //  providers. This should be changed to triggering syncs for specific providers once the
        //  backend takes responsibility for the sync triggers.
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
    }

    @Test
    fun testRefreshAlbumMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val albumId = "album_id"
        val albumAuthority = "album_authority"
        val providers: List<Provider> = mutableListOf(
                Provider(
                        authority = "local_authority",
                        mediaSource = MediaSource.LOCAL,
                        uid = 0
                ),
                Provider(
                        authority = "hypothetical_local_authority",
                        mediaSource = MediaSource.LOCAL,
                        uid = 1
                ),
        )

        mediaProviderClient.refreshAlbumMedia(
                albumId = albumId,
                albumAuthority = albumAuthority,
                providers = providers,
                resolver = testContentResolver
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", false))
                .isTrue()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("album_id"))
                .isEqualTo(albumId)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("album_authority"))
                .isEqualTo(albumAuthority)
    }

    @Test
    fun testFetchAlbumPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val albumLoadResult: LoadResult<MediaPageKey, Group.Album> =
            mediaProviderClient.fetchAlbums(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0))
            )

        assertThat(albumLoadResult is LoadResult.Page).isTrue()

        val albums: List<Group.Album> = (albumLoadResult as LoadResult.Page).data

        assertThat(albums.count()).isEqualTo(testContentProvider.albums.count())
        for (index in albums.indices) {
            assertThat(albums[index]).isEqualTo(testContentProvider.albums[index])
        }
    }

    @Test
    fun testFetchAlbumMediaPage() = runTest {
        val mediaProviderClient = MediaProviderClient()
        val albumId = testContentProvider.albumMedia.keys.elementAt(0)
        val albumAuthority = "authority"

        val mediaLoadResult: LoadResult<MediaPageKey, Media> =
            mediaProviderClient.fetchAlbumMedia(
                albumId = albumId,
                albumAuthority = albumAuthority,
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider(albumAuthority, MediaSource.LOCAL, 0))
            )

        assertThat(mediaLoadResult is LoadResult.Page).isTrue()

        val albumMedia: List<Media> = (mediaLoadResult as LoadResult.Page).data

        val expectedAlbumMedia = testContentProvider.albumMedia.get(albumId)
                ?: emptyList()
        assertThat(albumMedia.count()).isEqualTo(expectedAlbumMedia.count())
        for (index in albumMedia.indices) {
            assertThat(albumMedia[index]).isEqualTo(expectedAlbumMedia[index])
        }
    }
}