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
import androidx.paging.PagingSource.LoadResult
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import java.util.ArrayList

/**
 * A client class that is reponsible for holding logic required to interact with [MediaProvider].
 *
 * It typically fetches data from [MediaProvider] using content queries and call methods.
 */
open class MediaProviderClient {
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
        URI("uri"),
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
     * Fetch a list of [Media] from MediaProvider for the given page key.
     */
    fun fetchMedia(
        pageKey: MediaPageKey,
        pageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle = Bundle().apply {
            putLong(MediaQuery.PICKER_ID.key, pageKey.pickerId)
            putLong(MediaQuery.DATE_TAKEN.key, pageKey.dateTakenMillis)
            putInt(MediaQuery.PAGE_SIZE.key, pageSize)
            putStringArrayList(MediaQuery.PROVIDERS.key, ArrayList<String>().apply {
                availableProviders.forEach {
                    provider -> add(provider.authority)
                }
            })
        }

        try {
            return contentResolver.query(
                MEDIA_URI,
                /* projection */ null,
                input,
                /* cancellationSignal */ null // TODO
            ).use {
                cursor -> cursor?.let {
                     LoadResult.Page(
                        data = getListOfMedia(cursor),
                        prevKey = getPrevKey(cursor),
                        nextKey = getNextKey(cursor))
                } ?: throw IllegalStateException("Received a null response from Content Provider")
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Could not fetch media", e)
        }
    }

    /**
     * Creates a list of [Media] from the given [Cursor].
     *
     * [Media] can be of type [Media.Image] or [Media.Video].
     */
    private fun getListOfMedia(
        cursor: Cursor
    ): List<Media> {
        val result: MutableList<Media> = mutableListOf<Media>()
        if (cursor.moveToFirst()) {
            do {
                val mediaId: String = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaResponse.MEDIA_ID.key))
                val pickerId: Long = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaResponse.PICKER_ID.key))
                val authority: String = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaResponse.AUTHORITY.key))
                val uri: Uri = Uri.parse(cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaResponse.URI.key)))
                val dateTakenMillisLong: Long = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaResponse.DATE_TAKEN.key))
                val sizeInBytes: Long = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaResponse.SIZE.key))
                val mimeType: String = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaResponse.MIME_TYPE.key))
                val standardMimeTypeExtension: Int = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaResponse.STANDARD_MIME_TYPE_EXT.key))

                if (mimeType.startsWith("image/")) {
                    result.add(
                        Media.Image(
                            mediaId = mediaId,
                            pickerId = pickerId,
                            authority = authority,
                            uri = uri,
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
                            uri = uri,
                            dateTakenMillisLong = dateTakenMillisLong,
                            sizeInBytes = sizeInBytes,
                            mimeType = mimeType,
                            standardMimeTypeExtension = standardMimeTypeExtension,
                            duration = cursor.getInt(
                                cursor.getColumnIndexOrThrow(MediaResponse.DURATION.key)),
                        )
                    )
                } else {
                    throw UnsupportedOperationException("Could not recognize mime type $mimeType")
                }
            } while (cursor.moveToNext())
        }

        return result
    }

    /**
     * Extracts the previous page key from the given [Cursor]. In case the cursor contains the
     * contents of the first page, the previous page key will be null.
     */
    private fun getPrevKey(
        cursor: Cursor
    ): MediaPageKey? {
        val id: Long = cursor.extras.getLong(
                MediaResponseExtras.PREV_PAGE_ID.key,
                Long.MIN_VALUE
        )
        val date: Long = cursor.extras.getLong(
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
    private fun getNextKey(
        cursor: Cursor
    ): MediaPageKey? {
        val id: Long = cursor.extras.getLong(
                MediaResponseExtras.NEXT_PAGE_ID.key,
                Long.MIN_VALUE
        )
        val date: Long = cursor.extras.getLong(
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
}