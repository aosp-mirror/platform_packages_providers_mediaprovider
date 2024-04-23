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

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import androidx.paging.PagingSource.LoadResult
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider

/**
 * A client class that is reponsible for holding logic required to interact with [MediaProvider].
 *
 * It typically fetches data from [MediaProvider] using content queries and call methods.
 */
open class MediaProviderClient {
    companion object {
        private const val TAG = "MediaProviderClient"
        private const val MEDIA_INIT_CALL_METHOD: String = "picker_media_init"
        private const val EXTRA_LOCAL_ONLY = "is_local_only"
    }

    /** Contains all optional and mandatory keys required to make a Media query */
    private enum class MediaQuery(val key: String) {
        PICKER_ID("picker_id"),
        DATE_TAKEN("date_taken_millis"),
        PAGE_SIZE("page_size"),
        PROVIDERS("providers"),
    }

    /** Contains all optional and mandatory keys for data in the Available Providers query
     * response.
     */
    enum class AvailableProviderResponse(val key: String) {
        AUTHORITY("authority"),
        MEDIA_SOURCE("media_source"),
        UID("uid"),
    }

    /** Contains all optional and mandatory keys for data in the Media query response. */
    enum class MediaResponse(val key: String) {
        MEDIA_ID("id"),
        PICKER_ID("picker_id"),
        AUTHORITY("authority"),
        MEDIA_SOURCE("media_source"),
        MEDIA_URI("wrapped_uri"),
        LOADABLE_URI("unwrapped_uri"),
        DATE_TAKEN("date_taken_millis"),
        SIZE("size_bytes"),
        MIME_TYPE("mime_type"),
        STANDARD_MIME_TYPE_EXT("standard_mime_type_extension"),
        DURATION("duration_millis"),
    }

    /** Contains all optional and mandatory keys for data in the Media query response extras. */
    enum class MediaResponseExtras(val key: String) {
        PREV_PAGE_ID("prev_page_picker_id"),
        PREV_PAGE_DATE_TAKEN("prev_page_date_taken"),
        NEXT_PAGE_ID("next_page_picker_id"),
        NEXT_PAGE_DATE_TAKEN("next_page_date_taken"),
    }

    /** Contains all optional and mandatory keys for data in the Media query response. */
    enum class AlbumResponse(val key: String) {
        ALBUM_ID("album_id"),
        PICKER_ID("picker_id"),
        AUTHORITY("authority"),
        DATE_TAKEN("date_taken_millis"),
        ALBUM_NAME("display_name"),
        UNWRAPPED_COVER_URI("unwrapped_cover_uri"),
        COVER_MEDIA_SOURCE("media_source")
    }

    /**
     * Fetch available [Provider]-s from the Media Provider process.
     */
    fun fetchAvailableProviders(
        contentResolver: ContentResolver,
    ): List<Provider> {
        try {
            contentResolver.query(
                AVAILABLE_PROVIDERS_URI,
                /* projection */ null,
                /* queryArgs */ null,
                /* cancellationSignal */ null // TODO
            ).use {
                cursor -> return getListOfProviders(cursor!!)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Could not fetch available providers", e)
        }
    }

    /**
     * Fetch a list of [Media] from MediaProvider for the given page key.
     */
    fun fetchMedia(
            pageKey: MediaPageKey,
            pageSize: Int,
            contentResolver: ContentResolver,
            availableProviders: List<Provider>,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle = bundleOf (
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.PAGE_SIZE.key to pageSize,
                MediaQuery.PROVIDERS.key to ArrayList<String>().apply {
                    availableProviders.forEach { provider ->
                        add(provider.authority)
                    }
                }
        )

        try {
            return contentResolver.query(
                    MEDIA_URI,
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null // TODO
            ).use {
                cursor -> cursor?.let {
                LoadResult.Page(
                        data = cursor.getListOfMedia(),
                        prevKey = cursor.getPrevPageKey(),
                        nextKey = cursor.getNextPageKey())
            } ?: throw IllegalStateException("Received a null response from Content Provider")
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Could not fetch media", e)
        }
    }

    /**
     * Fetch a list of [Group.Album] from MediaProvider for the given page key.
     */
    fun fetchAlbums(
            pageKey: MediaPageKey,
            pageSize: Int,
            contentResolver: ContentResolver,
            availableProviders: List<Provider>,
    ): LoadResult<MediaPageKey, Group.Album> {
        val input: Bundle = bundleOf (
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.PAGE_SIZE.key to pageSize,
                MediaQuery.PROVIDERS.key to ArrayList<String>().apply {
                    availableProviders.forEach {
                        provider -> add(provider.authority)
                    }
                }
        )

        try {
            return contentResolver.query(
                    ALBUM_URI,
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null // TODO
            ).use {
                cursor -> cursor?.let {
                LoadResult.Page(
                        data = cursor.getListOfAlbums(),
                        prevKey = cursor.getPrevPageKey(),
                        nextKey = cursor.getNextPageKey())
            } ?: throw IllegalStateException("Received a null response from Content Provider")
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Could not fetch media", e)
        }
    }

    /**
     * Send a refresh [Media] request to MediaProvider. This is a signal for MediaProvider to
     * refresh its cache, if required.
     */
    fun refreshMedia(providers: List<Provider>, resolver: ContentResolver) {
        if (providers.isEmpty()) {
            Log.e(TAG, "List of providers is empty. Ignoring refresh media request.")
            return
        }

        val extras = Bundle()
        val initLocalOnlyMedia: Boolean = providers.all { provider ->
            (provider.mediaSource == MediaSource.LOCAL)
        }
        extras.putBoolean(EXTRA_LOCAL_ONLY, initLocalOnlyMedia)
        refreshMedia(extras, resolver)
    }

    /**
     * Creates a list of [Provider] from the given [Cursor].
     */
    private fun getListOfProviders(
        cursor: Cursor
    ): List<Provider> {
        val result: MutableList<Provider> = mutableListOf<Provider>()
        if (cursor.moveToFirst()) {
            do {
                result.add(
                    Provider(
                        authority = cursor.getString(
                            cursor.getColumnIndexOrThrow(AvailableProviderResponse.AUTHORITY.key)),
                        mediaSource = MediaSource.valueOf(cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    AvailableProviderResponse.MEDIA_SOURCE.key))),
                        uid = cursor.getInt(
                            cursor.getColumnIndexOrThrow(AvailableProviderResponse.UID.key)),
                    )
                )
            } while (cursor.moveToNext())
        }

        return result
    }

    /**
     * Creates a list of [Media] from the given [Cursor].
     *
     * [Media] can be of type [Media.Image] or [Media.Video].
     */
    private fun Cursor.getListOfMedia(): List<Media> {
        val result: MutableList<Media> = mutableListOf<Media>()
        if (this.moveToFirst()) {
            do {
                val mediaId: String = getString(getColumnIndexOrThrow(MediaResponse.MEDIA_ID.key))
                val pickerId: Long = getLong(getColumnIndexOrThrow(MediaResponse.PICKER_ID.key))
                val authority: String = getString(
                        getColumnIndexOrThrow(MediaResponse.AUTHORITY.key))
                val mediaSource: MediaSource = MediaSource.valueOf(getString(
                        getColumnIndexOrThrow(MediaResponse.MEDIA_SOURCE.key)))
                val mediaUri: Uri = Uri.parse(getString(
                        getColumnIndexOrThrow(MediaResponse.MEDIA_URI.key)))
                val loadableUri: Uri = Uri.parse(getString(
                        getColumnIndexOrThrow(MediaResponse.LOADABLE_URI.key)))
                val dateTakenMillisLong: Long = getLong(
                        getColumnIndexOrThrow(MediaResponse.DATE_TAKEN.key))
                val sizeInBytes: Long = getLong(getColumnIndexOrThrow(MediaResponse.SIZE.key))
                val mimeType: String = getString(getColumnIndexOrThrow(MediaResponse.MIME_TYPE.key))
                val standardMimeTypeExtension: Int = getInt(
                        getColumnIndexOrThrow(MediaResponse.STANDARD_MIME_TYPE_EXT.key))

                if (mimeType.startsWith("image/")) {
                    result.add(
                        Media.Image(
                            mediaId = mediaId,
                            pickerId = pickerId,
                            authority = authority,
                            mediaSource = mediaSource,
                            mediaUri = mediaUri,
                            glideLoadableUri = loadableUri,
                            dateTakenMillisLong = dateTakenMillisLong,
                            sizeInBytes = sizeInBytes,
                            mimeType = mimeType,
                            standardMimeTypeExtension = standardMimeTypeExtension,
                        )
                    )
                } else if (mimeType.startsWith("video/")) {
                    result.add(
                        Media.Video(
                            mediaId = mediaId,
                            pickerId = pickerId,
                            authority = authority,
                            mediaSource = mediaSource,
                            mediaUri = mediaUri,
                            glideLoadableUri = loadableUri,
                            dateTakenMillisLong = dateTakenMillisLong,
                            sizeInBytes = sizeInBytes,
                            mimeType = mimeType,
                            standardMimeTypeExtension = standardMimeTypeExtension,
                            duration = getInt(getColumnIndexOrThrow(MediaResponse.DURATION.key)),
                        )
                    )
                } else {
                    throw UnsupportedOperationException("Could not recognize mime type $mimeType")
                }
            } while (moveToNext())
        }

        return result
    }

    /**
     * Extracts the previous page key from the given [Cursor]. In case the cursor contains the
     * contents of the first page, the previous page key will be null.
     */
    private fun Cursor.getPrevPageKey(): MediaPageKey? {
        val id: Long = extras.getLong(
                MediaResponseExtras.PREV_PAGE_ID.key,
                Long.MIN_VALUE
        )
        val date: Long = extras.getLong(
                MediaResponseExtras.PREV_PAGE_DATE_TAKEN.key,
                Long.MIN_VALUE
        )
        return if (date == Long.MIN_VALUE) {
            null
        } else {
            MediaPageKey(
                    pickerId = id,
                    dateTakenMillis = date
            )
        }
    }

    /**
     * Extracts the next page key from the given [Cursor]. In case the cursor contains the
     * contents of the last page, the next page key will be null.
     */
    private fun Cursor.getNextPageKey(): MediaPageKey? {
        val id: Long = extras.getLong(
                MediaResponseExtras.NEXT_PAGE_ID.key,
                Long.MIN_VALUE
        )
        val date: Long = extras.getLong(
                MediaResponseExtras.NEXT_PAGE_DATE_TAKEN.key,
                Long.MIN_VALUE
        )
        return if (date == Long.MIN_VALUE) {
            null
        } else {
            MediaPageKey(
                    pickerId = id,
                    dateTakenMillis = date
            )
        }
    }

    /**
     * Creates a list of [Group.Album]-s from the given [Cursor].
     */
    private fun Cursor.getListOfAlbums(): List<Group.Album> {
        val result: MutableList<Group.Album> = mutableListOf<Group.Album>()

        if (this.moveToFirst()) {
            do {
                val albumId = getString(getColumnIndexOrThrow(AlbumResponse.ALBUM_ID.key))
                result.add(
                    Group.Album(
                        id = albumId,
                        // This is a temporary solution till we cache album data in Picker DB
                        pickerId = albumId.hashCode().toLong(),
                        authority = getString(getColumnIndexOrThrow(AlbumResponse.AUTHORITY.key)),
                        dateTakenMillisLong = getLong(
                            getColumnIndexOrThrow(AlbumResponse.DATE_TAKEN.key)),
                        displayName = getString(
                            getColumnIndexOrThrow(AlbumResponse.ALBUM_NAME.key)),
                        coverUri = Uri.parse(getString(
                            getColumnIndexOrThrow(AlbumResponse.UNWRAPPED_COVER_URI.key))),
                        coverMediaSource = MediaSource.valueOf(getString(
                            getColumnIndexOrThrow(AlbumResponse.COVER_MEDIA_SOURCE.key)))
                    )
                )
            } while (moveToNext())
        }

        return result
    }

    /**
     * Send a refresh [Media] request to MediaProvider with the prepared input args. This is a
     * signal for MediaProvider to refresh its cache, if required.
     */
    private fun refreshMedia(extras: Bundle, contentResolver: ContentResolver) {
        try {
            contentResolver.call(
                MEDIA_PROVIDER_AUTHORITY,
                MEDIA_INIT_CALL_METHOD,
                /* arg */null,
                extras
            )
        } catch (e: RuntimeException) {
            throw RuntimeException("Could not send refresh media call to Media Provider $extras", e)
        }
    }
}