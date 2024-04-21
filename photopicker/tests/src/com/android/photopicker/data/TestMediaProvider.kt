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

package src.com.android.photopicker.data

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.test.mock.MockContentProvider
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import java.util.UUID

/**
 * A test utility that provides implementation for some MediaProvider queries.
 *
 * This will be used to wrap [ContentResolver] to intercept calls to it and re-route them to the
 * internal mock this class holds.
 *
 * All not overridden / unimplemented operations will throw [UnsupportedOperationException].
 */

val DEFAULT_PROVIDERS: List<Provider> = listOf(
    Provider(
        authority = "test_authority",
        mediaSource = MediaSource.LOCAL,
        uid = 0
    )
)

val DEFAULT_MEDIA: List<Media> = listOf(
    createMediaImage(10),
    createMediaImage(11),
    createMediaImage(12),
    createMediaImage(13),
    createMediaImage(14),
)

fun createMediaImage(pickerId: Long): Media {
    return Media.Image(
        mediaId = UUID.randomUUID().toString(),
        pickerId = pickerId,
        authority = "authority",
        uri = Uri.parse("content://media/picker/authority/media/$pickerId"),
        dateTakenMillisLong = Long.MAX_VALUE,
        sizeInBytes = 10,
        mimeType = "image/*",
        standardMimeTypeExtension = 0
    )
}

class TestMediaProvider(
    var providers: List<Provider> = DEFAULT_PROVIDERS,
    var media: List<Media> = DEFAULT_MEDIA
) : MockContentProvider() {
    var lastRefreshMediaRequest: Bundle? = null

    override fun query (
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?
    ): Cursor? {
        return when (uri.lastPathSegment) {
            "available_providers" -> getAvailableProviders()
            "media" -> getMedia()
            else -> throw UnsupportedOperationException("Could not recognize uri $uri")
        }
    }

    override fun call (
            authority: String,
            method: String,
            arg: String?,
            extras: Bundle?
    ): Bundle? {
        return when (method) {
            "picker_media_init" -> {
                initMedia(extras)
                null
            }
            else -> throw UnsupportedOperationException("Could not recognize method $method")
        }
    }

    /**
     * Returns a [Cursor] with the providers currently in the [providers] list.
     */
    private fun getAvailableProviders(): Cursor {
        val cursor = MatrixCursor(arrayOf(
            MediaProviderClient.AvailableProviderResponse.AUTHORITY.key,
            MediaProviderClient.AvailableProviderResponse.MEDIA_SOURCE.key,
            MediaProviderClient.AvailableProviderResponse.UID.key
        ))
        providers.forEach {
            provider ->
                cursor.addRow(
                    arrayOf(
                        provider.authority,
                        provider.mediaSource.name,
                        provider.uid.toString()
                    )
                )
        }
        return cursor
    }

    private fun getMedia(): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                MediaProviderClient.MediaResponse.MEDIA_ID.key,
                MediaProviderClient.MediaResponse.PICKER_ID.key,
                MediaProviderClient.MediaResponse.AUTHORITY.key,
                MediaProviderClient.MediaResponse.URI.key,
                MediaProviderClient.MediaResponse.DATE_TAKEN.key,
                MediaProviderClient.MediaResponse.SIZE.key,
                MediaProviderClient.MediaResponse.MIME_TYPE.key,
                MediaProviderClient.MediaResponse.STANDARD_MIME_TYPE_EXT.key,
                MediaProviderClient.MediaResponse.DURATION.key,
            )
        )
        media.forEach {
            media ->
                cursor.addRow(
                    arrayOf(
                        media.mediaId,
                        media.pickerId.toString(),
                        media.authority,
                        media.uri.toString(),
                        media.dateTakenMillisLong.toString(),
                        media.sizeInBytes.toString(),
                        media.mimeType,
                        media.standardMimeTypeExtension.toString(),
                        if (media is Media.Video) media.duration else "0"
                    )
                )
        }
        return cursor
    }

    private fun initMedia(extras: Bundle?) {
        lastRefreshMediaRequest = extras
    }
}