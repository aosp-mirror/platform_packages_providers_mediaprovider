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
import android.content.Intent
import android.provider.MediaStore
import androidx.paging.PagingSource.LoadResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.IllegalIntentExtraException
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.extensions.getPhotopickerMimeTypes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

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
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0)),
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
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
        val providers: List<Provider> =
            mutableListOf(
                Provider(authority = "local_authority", mediaSource = MediaSource.LOCAL, uid = 0),
                Provider(authority = "cloud_authority", mediaSource = MediaSource.REMOTE, uid = 1),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 2
                ),
            )
        val mimeTypes: List<String> = mutableListOf("image/gif", "video/*")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver,
            intent = intent,
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(mimeTypes)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
    }

    @Test
    fun testRefreshLocalOnlyMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val providers: List<Provider> =
            mutableListOf(
                Provider(authority = "local_authority", mediaSource = MediaSource.LOCAL, uid = 0),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 1
                ),
            )
        val mimeTypes: List<String> = mutableListOf("image/gif", "video/*")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        mediaProviderClient.refreshMedia(
            providers = providers,
            resolver = testContentResolver,
            intent = intent,
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()

        // TODO(b/340246010): Currently, we trigger sync for all available providers. This is
        //  because UI is responsible for triggering syncs which is sometimes required to enable
        //  providers. This should be changed to triggering syncs for specific providers once the
        //  backend takes responsibility for the sync triggers.
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", true))
            .isFalse()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(mimeTypes)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
    }

    @Test
    fun testRefreshAlbumMedia() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        val mediaProviderClient = MediaProviderClient()
        val albumId = "album_id"
        val albumAuthority = "album_authority"
        val providers: List<Provider> =
            mutableListOf(
                Provider(authority = "local_authority", mediaSource = MediaSource.LOCAL, uid = 0),
                Provider(
                    authority = "hypothetical_local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 1
                ),
            )
        val mimeTypes: List<String> = mutableListOf("image/gif", "video/*")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        mediaProviderClient.refreshAlbumMedia(
            albumId = albumId,
            albumAuthority = albumAuthority,
            providers = providers,
            resolver = testContentResolver,
            intent = intent,
        )

        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getBoolean("is_local_only", false))
            .isTrue()
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("album_id"))
            .isEqualTo(albumId)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("album_authority"))
            .isEqualTo(albumAuthority)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getStringArrayList("mime_types"))
            .isEqualTo(mimeTypes)
        assertThat(testContentProvider.lastRefreshMediaRequest?.getString("intent_action"))
            .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
    }

    @Test
    fun testFetchAlbumPage() = runTest {
        val mediaProviderClient = MediaProviderClient()

        val albumLoadResult: LoadResult<MediaPageKey, Group.Album> =
            mediaProviderClient.fetchAlbums(
                pageKey = MediaPageKey(),
                pageSize = 5,
                contentResolver = testContentResolver,
                availableProviders = listOf(Provider("provider", MediaSource.LOCAL, 0)),
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
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
                availableProviders = listOf(Provider(albumAuthority, MediaSource.LOCAL, 0)),
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
            )

        assertThat(mediaLoadResult is LoadResult.Page).isTrue()

        val albumMedia: List<Media> = (mediaLoadResult as LoadResult.Page).data

        val expectedAlbumMedia = testContentProvider.albumMedia.get(albumId) ?: emptyList()
        assertThat(albumMedia.count()).isEqualTo(expectedAlbumMedia.count())
        for (index in albumMedia.indices) {
            assertThat(albumMedia[index]).isEqualTo(expectedAlbumMedia[index])
        }
    }

    @Test
    fun testGetMimeTypeFromIntentActionPickImages() {
        val mimeTypes: List<String> = mutableListOf("image/*", "video/mp4", "image/gif")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mimeTypes)
    }

    @Test
    fun testGetInvalidMimeTypeFromIntentActionPickImages() {
        val mimeTypes: List<String> = mutableListOf("image/*", "application/binary", "image/gif")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        assertThrows(IllegalIntentExtraException::class.java) { intent.getPhotopickerMimeTypes() }
    }

    @Test
    fun testGetMimeTypeFromIntentActionGetContent() {
        val mimeTypes: List<String> = mutableListOf("image/*", "video/mp4", "image/gif")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mimeTypes)
    }

    @Test
    fun testGetInvalidMimeTypeFromIntentActionGetContent() {
        val mimeTypes: List<String> = mutableListOf("image/*", "application/binary", "image/gif")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isNull()
    }

    @Test
    fun testGetTypeFromIntent() {
        val mimeType: String = "image/gif"
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.setType(mimeType)

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mutableListOf(mimeType))
    }

    @Test
    fun testGetInvalidTypeFromIntent() {
        val mimeType: String = "application/binary"
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.setType(mimeType)

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isNull()
    }
}
