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

package com.android.photopicker.data

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.test.mock.MockContentProvider
import androidx.core.os.bundleOf
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import java.util.UUID
import java.util.stream.Collectors

/**
 * A test utility that provides implementation for some MediaProvider queries.
 *
 * This will be used to wrap [ContentResolver] to intercept calls to it and re-route them to the
 * internal mock this class holds.
 *
 * All not overridden / unimplemented operations will throw [UnsupportedOperationException].
 */
val DEFAULT_PROVIDERS: List<Provider> =
    listOf(
        Provider(
            authority = "test_authority",
            mediaSource = MediaSource.LOCAL,
            uid = 0,
            displayName = "Test app",
        )
    )

val DEFAULT_COLLECTION_INFO: List<CollectionInfo> =
    listOf(
        CollectionInfo(
            authority = "test_authority",
            collectionId = "1",
            accountName = "default@test.com",
        )
    )

val DEFAULT_MEDIA: List<Media> =
    listOf(
        createMediaImage(10),
        createMediaImage(11),
        createMediaImage(12),
        createMediaImage(13),
        createMediaImage(14),
    )

val DEFAULT_ALBUMS: List<Group.Album> =
    listOf(createAlbum("Favorites"), createAlbum("Downloads"), createAlbum("CloudAlbum"))

val DEFAULT_ALBUM_NAME = "album_id"

val DEFAULT_ALBUM_MEDIA: Map<String, List<Media>> = mapOf(DEFAULT_ALBUM_NAME to DEFAULT_MEDIA)

val DEFAULT_SEARCH_REQUEST_ID: Int = 100

fun createMediaImage(pickerId: Long): Media {
    return Media.Image(
        mediaId = UUID.randomUUID().toString(),
        pickerId = pickerId,
        authority = "authority",
        mediaSource = MediaSource.LOCAL,
        mediaUri = Uri.parse("content://media/picker/authority/media/$pickerId"),
        glideLoadableUri = Uri.parse("content://authority/media/$pickerId"),
        dateTakenMillisLong = Long.MAX_VALUE,
        sizeInBytes = 10,
        mimeType = "image/*",
        standardMimeTypeExtension = 0,
    )
}

fun createAlbum(albumId: String): Group.Album {
    return Group.Album(
        id = albumId,
        pickerId = albumId.hashCode().toLong(),
        authority = "authority",
        dateTakenMillisLong = Long.MAX_VALUE,
        displayName = albumId,
        coverUri = Uri.parse("content://media/picker/authority/media/$albumId"),
        coverMediaSource = MediaSource.LOCAL,
    )
}

class TestMediaProvider(
    var providers: List<Provider> = DEFAULT_PROVIDERS,
    var collectionInfos: List<CollectionInfo> = DEFAULT_COLLECTION_INFO,
    var media: List<Media> = DEFAULT_MEDIA,
    var albums: List<Group.Album> = DEFAULT_ALBUMS,
    var albumMedia: Map<String, List<Media>> = DEFAULT_ALBUM_MEDIA,
    var searchRequestId: Int = DEFAULT_SEARCH_REQUEST_ID,
) : MockContentProvider() {
    var lastRefreshMediaRequest: Bundle? = null
    var TEST_GRANTS_COUNT = 2

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        return when (uri.lastPathSegment) {
            "available_providers" -> getAvailableProviders()
            "collection_info" -> getCollectionInfo()
            "media" -> getMedia()
            "album" -> getAlbums()
            "media_grants_count" -> fetchMediaGrantsCount()
            "pre_selection" -> fetchFilteredMedia(queryArgs)
            else -> {
                val pathSegments: MutableList<String> = uri.getPathSegments()
                if (pathSegments.size == 4 && pathSegments[2].equals("album")) {
                    // Album media query
                    return getAlbumMedia(pathSegments[3])
                } else if (pathSegments.size == 4 && pathSegments[2].equals("search_media")) {
                    // Search results media query
                    return getMedia()
                } else {
                    throw UnsupportedOperationException("Could not recognize uri $uri")
                }
            }
        }
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            "picker_media_init" -> {
                initMedia(extras)
                null
            }
            "picker_internal_search_media_init" -> {
                bundleOf(MediaProviderClient.SEARCH_REQUEST_ID to searchRequestId)
            }
            else -> throw UnsupportedOperationException("Could not recognize method $method")
        }
    }

    /** Returns a [Cursor] with the providers currently in the [providers] list. */
    private fun getAvailableProviders(): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.AvailableProviderResponse.AUTHORITY.key,
                    MediaProviderClient.AvailableProviderResponse.MEDIA_SOURCE.key,
                    MediaProviderClient.AvailableProviderResponse.UID.key,
                    MediaProviderClient.AvailableProviderResponse.DISPLAY_NAME.key,
                )
            )
        providers.forEach { provider ->
            cursor.addRow(
                arrayOf(
                    provider.authority,
                    provider.mediaSource.name,
                    provider.uid.toString(),
                    provider.displayName,
                )
            )
        }
        return cursor
    }

    private fun getCollectionInfo(): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.CollectionInfoResponse.AUTHORITY.key,
                    MediaProviderClient.CollectionInfoResponse.COLLECTION_ID.key,
                    MediaProviderClient.CollectionInfoResponse.ACCOUNT_NAME.key,
                )
            )
        cursor.setExtras(Bundle())
        collectionInfos.forEach { collectionInfo ->
            cursor.addRow(
                arrayOf(
                    collectionInfo.authority,
                    collectionInfo.collectionId,
                    collectionInfo.accountName,
                )
            )
            cursor
                .getExtras()
                .putParcelable(collectionInfo.authority, collectionInfo.accountConfigurationIntent)
        }
        return cursor
    }

    private fun getMedia(mediaItems: List<Media> = media): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.MediaResponse.MEDIA_ID.key,
                    MediaProviderClient.MediaResponse.PICKER_ID.key,
                    MediaProviderClient.MediaResponse.AUTHORITY.key,
                    MediaProviderClient.MediaResponse.MEDIA_SOURCE.key,
                    MediaProviderClient.MediaResponse.MEDIA_URI.key,
                    MediaProviderClient.MediaResponse.LOADABLE_URI.key,
                    MediaProviderClient.MediaResponse.DATE_TAKEN.key,
                    MediaProviderClient.MediaResponse.SIZE.key,
                    MediaProviderClient.MediaResponse.MIME_TYPE.key,
                    MediaProviderClient.MediaResponse.STANDARD_MIME_TYPE_EXT.key,
                    MediaProviderClient.MediaResponse.DURATION.key,
                    MediaProviderClient.MediaResponse.IS_PRE_GRANTED.key,
                )
            )
        mediaItems.forEach { mediaItem ->
            cursor.addRow(
                arrayOf(
                    mediaItem.mediaId,
                    mediaItem.pickerId.toString(),
                    mediaItem.authority,
                    mediaItem.mediaSource.toString(),
                    mediaItem.mediaUri.toString(),
                    mediaItem.glideLoadableUri.toString(),
                    mediaItem.dateTakenMillisLong.toString(),
                    mediaItem.sizeInBytes.toString(),
                    mediaItem.mimeType,
                    mediaItem.standardMimeTypeExtension.toString(),
                    if (mediaItem is Media.Video) mediaItem.duration else "0",
                    if (mediaItem.isPreGranted) 1 else 0,
                )
            )
        }
        return cursor
    }

    private fun fetchFilteredMedia(queryArgs: Bundle?, mediaItems: List<Media> = media): Cursor {
        val ids =
            queryArgs
                ?.getStringArrayList("pre_selection_uris")
                ?.stream()
                ?.map { it -> Uri.parse(it).lastPathSegment }
                ?.collect(Collectors.toList())
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.MediaResponse.MEDIA_ID.key,
                    MediaProviderClient.MediaResponse.PICKER_ID.key,
                    MediaProviderClient.MediaResponse.AUTHORITY.key,
                    MediaProviderClient.MediaResponse.MEDIA_SOURCE.key,
                    MediaProviderClient.MediaResponse.MEDIA_URI.key,
                    MediaProviderClient.MediaResponse.LOADABLE_URI.key,
                    MediaProviderClient.MediaResponse.DATE_TAKEN.key,
                    MediaProviderClient.MediaResponse.SIZE.key,
                    MediaProviderClient.MediaResponse.MIME_TYPE.key,
                    MediaProviderClient.MediaResponse.STANDARD_MIME_TYPE_EXT.key,
                    MediaProviderClient.MediaResponse.DURATION.key,
                    MediaProviderClient.MediaResponse.IS_PRE_GRANTED.key,
                )
            )
        mediaItems.forEach { mediaItem ->
            if (ids != null) {
                if (mediaItem.mediaId in ids) {
                    cursor.addRow(
                        arrayOf(
                            mediaItem.mediaId,
                            mediaItem.pickerId.toString(),
                            mediaItem.authority,
                            mediaItem.mediaSource.toString(),
                            mediaItem.mediaUri.toString(),
                            mediaItem.glideLoadableUri.toString(),
                            mediaItem.dateTakenMillisLong.toString(),
                            mediaItem.sizeInBytes.toString(),
                            mediaItem.mimeType,
                            mediaItem.standardMimeTypeExtension.toString(),
                            if (mediaItem is Media.Video) mediaItem.duration else "0",
                            if (mediaItem.isPreGranted) 1 else 0,
                        )
                    )
                }
            }
        }
        return cursor
    }

    private fun getAlbums(): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.AlbumResponse.ALBUM_ID.key,
                    MediaProviderClient.AlbumResponse.PICKER_ID.key,
                    MediaProviderClient.AlbumResponse.AUTHORITY.key,
                    MediaProviderClient.AlbumResponse.DATE_TAKEN.key,
                    MediaProviderClient.AlbumResponse.ALBUM_NAME.key,
                    MediaProviderClient.AlbumResponse.UNWRAPPED_COVER_URI.key,
                    MediaProviderClient.AlbumResponse.COVER_MEDIA_SOURCE.key,
                )
            )
        albums.forEach { album ->
            cursor.addRow(
                arrayOf(
                    album.id,
                    album.pickerId.toString(),
                    album.authority,
                    album.dateTakenMillisLong.toString(),
                    album.displayName,
                    album.coverUri.toString(),
                    album.coverMediaSource.toString(),
                )
            )
        }
        return cursor
    }

    private fun fetchMediaGrantsCount(): Cursor {
        val cursor = MatrixCursor(arrayOf("grants_count"))
        cursor.addRow(arrayOf(TEST_GRANTS_COUNT))
        return cursor
    }

    private fun getAlbumMedia(albumId: String): Cursor? {
        return getMedia(albumMedia.getOrDefault(albumId, emptyList()))
    }

    private fun initMedia(extras: Bundle?) {
        lastRefreshMediaRequest = extras
    }
}
