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
import android.net.Uri
import android.provider.MediaStore

/** Provides URI constants and helper functions. */
internal const val MEDIA_PROVIDER_AUTHORITY = MediaStore.AUTHORITY
private const val UPDATE_PATH_SEGMENT = "update"
private const val AVAILABLE_PROVIDERS_PATH_SEGMENT = "available_providers"
private const val COLLECTION_INFO_SEGMENT = "collection_info"
private const val MEDIA_PATH_SEGMENT = "media"
private const val ALBUM_PATH_SEGMENT = "album"
private const val MEDIA_GRANTS_COUNT_PATH_SEGMENT = "media_grants_count"
private const val PREVIEW_PATH_SEGMENT = "preview"
private const val PRE_SELECTION_URI_PATH_SEGMENT = "pre_selection"
private const val SEARCH_MEDIA_PATH_SEGMENT = "search_media"
private const val SEARCH_SUGGESTIONS_PATH_SEGMENT = "search_suggestions"

const val PICKER_SEGMENT = "picker"
const val PICKER_TRANSCODED_SEGMENT = "picker_transcoded"

private val pickerUri: Uri =
    Uri.Builder()
        .apply {
            scheme(ContentResolver.SCHEME_CONTENT)
            authority(MEDIA_PROVIDER_AUTHORITY)
            appendPath("picker_internal")
            appendPath("v2")
        }
        .build()

/** URI for available providers resource. */
val AVAILABLE_PROVIDERS_URI: Uri =
    pickerUri.buildUpon().apply { appendPath(AVAILABLE_PROVIDERS_PATH_SEGMENT) }.build()

/** URI for collection info resource. */
val COLLECTION_INFO_URI: Uri =
    pickerUri.buildUpon().apply { appendPath(COLLECTION_INFO_SEGMENT) }.build()

/** URI that receives [ContentProvider] change notifications for available provider updates. */
val AVAILABLE_PROVIDERS_CHANGE_NOTIFICATION_URI: Uri =
    pickerUri
        .buildUpon()
        .apply {
            appendPath(AVAILABLE_PROVIDERS_PATH_SEGMENT)
            appendPath(UPDATE_PATH_SEGMENT)
        }
        .build()

/** URI for media metadata. */
val MEDIA_URI: Uri = pickerUri.buildUpon().apply { appendPath(MEDIA_PATH_SEGMENT) }.build()

/** URI for media_grants table. */
val MEDIA_GRANTS_COUNT_URI: Uri =
    pickerUri.buildUpon().apply { appendPath(MEDIA_GRANTS_COUNT_PATH_SEGMENT) }.build()

/** URI for preview of media table. */
val MEDIA_PREVIEW_URI: Uri =
    pickerUri
        .buildUpon()
        .apply {
            appendPath(MEDIA_PATH_SEGMENT)
            appendPath(PREVIEW_PATH_SEGMENT)
        }
        .build()

/** URI for media table pre-selection items. */
val MEDIA_PRE_SELECTION_URI: Uri =
    pickerUri
        .buildUpon()
        .apply {
            appendPath(MEDIA_PATH_SEGMENT)
            appendPath(PRE_SELECTION_URI_PATH_SEGMENT)
        }
        .build()

/** URI that receives [ContentProvider] change notifications for media updates. */
val MEDIA_CHANGE_NOTIFICATION_URI: Uri =
    MEDIA_URI.buildUpon().apply { appendPath(UPDATE_PATH_SEGMENT) }.build()

/** URI for album metadata. */
val ALBUM_URI: Uri = pickerUri.buildUpon().apply { appendPath(ALBUM_PATH_SEGMENT) }.build()

/** URI that receives [ContentProvider] change notifications for album media updates. */
val ALBUM_CHANGE_NOTIFICATION_URI: Uri =
    ALBUM_URI.buildUpon().apply { appendPath(UPDATE_PATH_SEGMENT) }.build()

fun getAlbumMediaUri(albumId: String): Uri {
    return ALBUM_URI.buildUpon().apply { appendPath(albumId) }.build()
}

val SEARCH_SUGGESTIONS_URI: Uri =
    pickerUri.buildUpon().apply { appendPath(SEARCH_SUGGESTIONS_PATH_SEGMENT) }.build()

/** URI that receives [ContentProvider] change notifications for search result updates. */
val SEARCH_RESULTS_UPDATE_URI: Uri =
    pickerUri
        .buildUpon()
        .apply {
            appendPath(SEARCH_MEDIA_PATH_SEGMENT)
            appendPath(UPDATE_PATH_SEGMENT)
        }
        .build()

fun getSearchResultsMediaUri(searchRequestId: Int): Uri {
    return pickerUri
        .buildUpon()
        .apply {
            appendPath(SEARCH_MEDIA_PATH_SEGMENT)
            appendPath(searchRequestId.toString())
        }
        .build()
}
