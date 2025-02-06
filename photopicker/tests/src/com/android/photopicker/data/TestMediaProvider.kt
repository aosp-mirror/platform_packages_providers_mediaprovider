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
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
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

val DEFAULT_SEARCH_SUGGESTIONS: List<SearchSuggestion> =
    listOf(
        SearchSuggestion(
            mediaSetId = null,
            authority = null,
            type = SearchSuggestionType.HISTORY,
            displayText = "Text",
            icon = null,
        ),
        SearchSuggestion(
            mediaSetId = "media-set-id-1",
            authority = "cloud.provider",
            type = SearchSuggestionType.FACE,
            displayText = null,
            icon = Icon(Uri.parse("content://cloud.provider/1234"), MediaSource.LOCAL),
        ),
        SearchSuggestion(
            mediaSetId = "media-set-id-1",
            authority = "local-provider",
            type = SearchSuggestionType.TEXT,
            displayText = "Text",
            icon = null,
        ),
    )

val DEFAULT_CATEGORY: Group.Category =
    createCategory(CategoryType.PEOPLE_AND_PETS, DEFAULT_PROVIDERS[0].authority)

val DEFAULT_CATEGORIES_AND_ALBUMS: List<Group> =
    listOf(
        createAlbum("Favorites"),
        createAlbum("Downloads"),
        DEFAULT_CATEGORY,
        createAlbum("CloudAlbum"),
    )

val DEFAULT_MEDIA_SETS: List<Group.MediaSet> =
    listOf(createMediaSet("1"), createMediaSet("2"), createMediaSet("3"))

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
        authority = DEFAULT_PROVIDERS[0].authority,
        dateTakenMillisLong = Long.MAX_VALUE,
        displayName = albumId,
        coverUri = Uri.parse("content://test_authority/$albumId"),
        coverMediaSource = DEFAULT_PROVIDERS[0].mediaSource,
    )
}

fun createCategory(type: CategoryType, authority: String): Group.Category {
    return Group.Category(
        id = "test_id_" + type.name,
        pickerId = 0,
        authority = authority,
        displayName = type.name,
        categoryType = type,
        icons = listOf(Icon(Uri.parse("content://test_authority/id"), MediaSource.LOCAL)),
        isLeafCategory = true,
    )
}

fun createMediaSet(mediaSetId: String): Group.MediaSet {
    return Group.MediaSet(
        id = mediaSetId,
        pickerId = mediaSetId.hashCode().toLong(),
        authority = DEFAULT_PROVIDERS[0].authority,
        displayName = mediaSetId,
        icon = Icon(Uri.parse("content://test_authority/$mediaSetId"), MediaSource.LOCAL),
    )
}

class TestMediaProvider(
    var providers: List<Provider> = DEFAULT_PROVIDERS,
    var collectionInfos: List<CollectionInfo> = DEFAULT_COLLECTION_INFO,
    var media: List<Media> = DEFAULT_MEDIA,
    var albums: List<Group.Album> = DEFAULT_ALBUMS,
    var albumMedia: Map<String, List<Media>> = DEFAULT_ALBUM_MEDIA,
    var searchRequestId: Int = DEFAULT_SEARCH_REQUEST_ID,
    var searchSuggestions: List<SearchSuggestion> = DEFAULT_SEARCH_SUGGESTIONS,
    var searchProviders: List<Provider>? = DEFAULT_PROVIDERS,
    var parentCategory: Group.Category = DEFAULT_CATEGORY,
    var categoriesAndAlbums: List<Group> = DEFAULT_CATEGORIES_AND_ALBUMS,
    var mediaSets: List<Group.MediaSet> = DEFAULT_MEDIA_SETS,
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
            AVAILABLE_PROVIDERS_PATH_SEGMENT -> getAvailableProviders()
            COLLECTION_INFO_SEGMENT -> getCollectionInfo()
            MEDIA_PATH_SEGMENT -> getMedia()
            ALBUM_PATH_SEGMENT -> getAlbums()
            MEDIA_GRANTS_COUNT_PATH_SEGMENT -> fetchMediaGrantsCount()
            PRE_SELECTION_URI_PATH_SEGMENT -> fetchFilteredMedia(queryArgs)
            SEARCH_SUGGESTIONS_PATH_SEGMENT -> getSearchSuggestions()
            CATEGORIES_PATH_SEGMENT -> getCategoriesAndAlbums()
            MEDIA_SETS_PATH_SEGMENT -> getMediaSets()
            MEDIA_SET_CONTENTS_PATH_SEGMENT -> getMedia()
            else -> {
                val pathSegments: MutableList<String> = uri.getPathSegments()
                if (pathSegments.size == 4 && pathSegments[2].equals(ALBUM_PATH_SEGMENT)) {
                    // Album media query
                    return getAlbumMedia(pathSegments[3])
                } else if (
                    pathSegments.size == 4 && pathSegments[2].equals(SEARCH_MEDIA_PATH_SEGMENT)
                ) {
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
            MediaProviderClient.MEDIA_INIT_CALL_METHOD -> {
                initMedia(extras)
                null
            }
            MediaProviderClient.SEARCH_REQUEST_INIT_CALL_METHOD -> {
                bundleOf(MediaProviderClient.SEARCH_REQUEST_ID to searchRequestId)
            }
            MediaProviderClient.GET_SEARCH_PROVIDERS_CALL_METHOD ->
                bundleOf(
                    MediaProviderClient.SEARCH_PROVIDER_AUTHORITIES to
                        if (searchProviders == null) null
                        else
                            arrayListOf<String>().apply {
                                searchProviders?.map { it.authority }?.toCollection(this)
                            }
                )
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

    private fun getSearchSuggestions(): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.SearchSuggestionsResponse.AUTHORITY.key,
                    MediaProviderClient.SearchSuggestionsResponse.MEDIA_SET_ID.key,
                    MediaProviderClient.SearchSuggestionsResponse.SEARCH_TEXT.key,
                    MediaProviderClient.SearchSuggestionsResponse.COVER_MEDIA_URI.key,
                    MediaProviderClient.SearchSuggestionsResponse.SUGGESTION_TYPE.key,
                )
            )

        searchSuggestions.forEach { suggestion ->
            cursor.addRow(
                arrayOf(
                    suggestion.authority,
                    suggestion.mediaSetId,
                    suggestion.displayText,
                    suggestion.icon,
                    suggestion.type.key,
                )
            )
        }
        return cursor
    }

    private fun getCategoriesAndAlbums(): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.GroupResponse.MEDIA_GROUP.key,
                    MediaProviderClient.GroupResponse.GROUP_ID.key,
                    MediaProviderClient.GroupResponse.PICKER_ID.key,
                    MediaProviderClient.GroupResponse.DISPLAY_NAME.key,
                    MediaProviderClient.GroupResponse.AUTHORITY.key,
                    MediaProviderClient.GroupResponse.UNWRAPPED_COVER_URI.key,
                    MediaProviderClient.GroupResponse.ADDITIONAL_UNWRAPPED_COVER_URI_1.key,
                    MediaProviderClient.GroupResponse.ADDITIONAL_UNWRAPPED_COVER_URI_2.key,
                    MediaProviderClient.GroupResponse.ADDITIONAL_UNWRAPPED_COVER_URI_3.key,
                    MediaProviderClient.GroupResponse.CATEGORY_TYPE.key,
                    MediaProviderClient.GroupResponse.IS_LEAF_CATEGORY.key,
                )
            )
        categoriesAndAlbums.forEach { group ->
            when (group) {
                is Group.Album ->
                    cursor.addRow(
                        arrayOf(
                            MediaProviderClient.GroupType.ALBUM.name,
                            group.id,
                            group.pickerId.toString(),
                            group.displayName,
                            group.authority,
                            group.coverUri.toString(),
                            /* additional uri */ null,
                            /* additional uri */ null,
                            /* additional uri */ null,
                            /* category type */ null,
                            /* is leaf category */ null,
                        )
                    )
                is Group.Category ->
                    cursor.addRow(
                        arrayOf(
                            MediaProviderClient.GroupType.CATEGORY.name,
                            group.id,
                            group.pickerId.toString(),
                            group.displayName,
                            group.authority,
                            group.icons.getOrNull(0)?.getLoadableUri()?.toString(),
                            group.icons.getOrNull(1)?.getLoadableUri()?.toString(),
                            group.icons.getOrNull(2)?.getLoadableUri()?.toString(),
                            group.icons.getOrNull(3)?.getLoadableUri()?.toString(),
                            group.categoryType.key,
                            if (group.isLeafCategory) 1 else null,
                        )
                    )
                else -> {}
            }
        }
        return cursor
    }

    private fun getMediaSets(): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaProviderClient.GroupResponse.GROUP_ID.key,
                    MediaProviderClient.GroupResponse.PICKER_ID.key,
                    MediaProviderClient.GroupResponse.DISPLAY_NAME.key,
                    MediaProviderClient.GroupResponse.AUTHORITY.key,
                    MediaProviderClient.GroupResponse.UNWRAPPED_COVER_URI.key,
                )
            )
        mediaSets.forEach {
            cursor.addRow(
                arrayOf(
                    it.id,
                    it.pickerId.toString(),
                    it.displayName,
                    it.authority,
                    it.icon.getLoadableUri().toString(),
                )
            )
        }
        return cursor
    }
}
