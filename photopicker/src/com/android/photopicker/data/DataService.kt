/*
 * Copyright 2024 The Android Open Source Project
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
import androidx.paging.PagingSource
import com.android.photopicker.data.model.CloudMediaProviderDetails
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides data to the Photo Picker UI. Typically, for paged data, this class provides a
 * [PagingSource] instance and for unpaged data, it provides a [StateFlow].
 *
 * It's implementation should ideally observe data changes and emit updates when possible.
 */
interface DataService {

    companion object {
        val TAG: String = "PhotopickerDataService"
    }

    /** A [StateFlow] with the active content resolver. */
    val activeContentResolver: StateFlow<ContentResolver>

    /** A [StateFlow] with a list of available [Provider]-s. */
    val availableProviders: StateFlow<List<Provider>>

    /** Count of all preGranted media for the current package and userID. */
    val preGrantedMediaCount: StateFlow<Int?>

    /** Data for preSelection media */
    val preSelectionMediaData: StateFlow<List<Media>?>

    /**
     * A [Channel] that emits a [Unit] when a disruptive data change is observed in the backend. The
     * UI can treat this emission as a signal to reset the UI.
     */
    val disruptiveDataUpdateChannel: Channel<Unit>

    /**
     * @param album This method creates and returns a paging source for media of the given album.
     * @return an instance of [PagingSource].
     */
    fun albumMediaPagingSource(album: Album): PagingSource<MediaPageKey, Media>

    /** @return an instance of [PagingSource]. */
    fun albumPagingSource(): PagingSource<MediaPageKey, Album>

    /**
     * @param authority The authority of the [Provider]. See [availableProviders] to get the
     *   authority of the available providers.
     * @return A [StateFlow] with the details of the provider. It returns [null] if the requested
     *   provider is not longer available.
     */
    fun cloudMediaProviderDetails(authority: String): StateFlow<CloudMediaProviderDetails?>

    /** @return a new instance of [PagingSource]. */
    fun mediaPagingSource(): PagingSource<MediaPageKey, Media>

    /**
     * @param currentSelection set of items that have been selected by the user in the current
     *   session.
     * @param currentDeselection set of items that are pre-granted and have been de-selected by the
     *   user.
     * @return a new instance of [PagingSource].
     */
    fun previewMediaPagingSource(
        currentSelection: Set<Media>,
        currentDeselection: Set<Media>,
    ): PagingSource<MediaPageKey, Media>

    /**
     * Ensures that the available providers cache is up to date and returns the latest available
     * providers.
     */
    suspend fun ensureProviders()

    /** Returns all allowed providers for the given user. */
    fun getAllAllowedProviders(): List<Provider>

    /**
     * Sends a refresh media notification to the data source. This signal tells the data source to
     * refresh its cache.
     */
    suspend fun refreshMedia()

    /**
     * @param album This method sends a refresh notification for the media of the given
     *   [Group.Album] to the data source. This signal tells the data source to refresh its cache.
     */
    suspend fun refreshAlbumMedia(album: Album)

    /**
     * @param A [Provider] object
     * @return The [CollectionInfo] of the given [Provider].
     */
    suspend fun getCollectionInfo(provider: Provider): CollectionInfo

    /** Refreshes the [preGrantedMediaCount] with the latest value in the data source. */
    fun refreshPreGrantedItemsCount()

    /** Refreshes the [preSelectionMediaData] with the latest value as per the input URIs. */
    fun fetchMediaDataForUris(uris: List<Uri>)
}
