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
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A test implementation of [DataService] that provides fake, in memory paging sources that isolate
 * device state from the test by providing fake data that is not backed by real media.
 *
 * Tests can override the size of data or the actual list of data itself by injecting the data
 * service class into the test and changing the corresponding values.
 */
class TestDataServiceImpl() : DataService {

    // Overrides for MediaPagingSource
    var mediaSetSize: Int = FakeInMemoryMediaPagingSource.DEFAULT_SIZE
    var mediaList: List<Media>? = null

    // Overrides for AlbumPagingSource
    var albumSetSize: Int = FakeInMemoryAlbumPagingSource.DEFAULT_SIZE
    var albumsList: List<Album>? = null

    // Overrides for AlbumMediaPagingSource
    var albumMediaSetSize: Int = FakeInMemoryMediaPagingSource.DEFAULT_SIZE
    var albumMediaList: List<Media>? = null

    val _availableProviders = MutableStateFlow<List<Provider>>(emptyList())
    override val availableProviders: StateFlow<List<Provider>> = _availableProviders

    var allowedProviders: List<Provider> = emptyList()

    val collectionInfo: HashMap<Provider, CollectionInfo> = HashMap()

    private var _preGrantsCount = MutableStateFlow(/* default value */ 0)

    fun setAvailableProviders(newProviders: List<Provider>) {
        _availableProviders.update { newProviders }
    }

    override val activeContentResolver: StateFlow<ContentResolver>
        get() = TODO("Not yet implemented")

    override val preGrantedMediaCount: StateFlow<Int> = _preGrantsCount
    override val preSelectionMediaData: StateFlow<List<Media>?> =
        MutableStateFlow(ArrayList<Media>())

    fun setInitPreGrantsCount(count: Int) {
        _preGrantsCount.update { count }
    }

    override fun albumMediaPagingSource(album: Album): PagingSource<MediaPageKey, Media> {
        return albumMediaList?.let { FakeInMemoryMediaPagingSource(it) }
            ?: FakeInMemoryMediaPagingSource(albumMediaSetSize)
    }

    override fun albumPagingSource(): PagingSource<MediaPageKey, Album> {
        return albumsList?.let { FakeInMemoryAlbumPagingSource(it) }
            ?: FakeInMemoryAlbumPagingSource(albumSetSize)
    }

    override fun cloudMediaProviderDetails(
        authority: String
    ): StateFlow<CloudMediaProviderDetails?> =
        throw NotImplementedError("This method is not implemented yet.")

    override fun mediaPagingSource(): PagingSource<MediaPageKey, Media> {
        return mediaList?.let { FakeInMemoryMediaPagingSource(it) }
            ?: FakeInMemoryMediaPagingSource(mediaSetSize)
    }

    override fun previewMediaPagingSource(
        currentSelection: Set<Media>,
        currentDeselection: Set<Media>,
    ): PagingSource<MediaPageKey, Media> {
        // re-using the media source, modify as per future test usage.
        return mediaList?.let { FakeInMemoryMediaPagingSource(it) }
            ?: FakeInMemoryMediaPagingSource(mediaSetSize)
    }

    override suspend fun refreshMedia() =
        throw NotImplementedError("This method is not implemented yet.")

    override suspend fun refreshAlbumMedia(album: Album) =
        throw NotImplementedError("This method is not implemented yet.")

    override val disruptiveDataUpdateChannel = Channel<Unit>(CONFLATED)

    suspend fun sendDisruptiveDataUpdateNotification() {
        disruptiveDataUpdateChannel.send(Unit)
    }

    override suspend fun getCollectionInfo(provider: Provider): CollectionInfo =
        collectionInfo.getOrElse(provider, { CollectionInfo(provider.authority) })

    override suspend fun ensureProviders() {}

    override fun getAllAllowedProviders(): List<Provider> = allowedProviders

    override fun refreshPreGrantedItemsCount() {
        // no_op
    }

    override fun fetchMediaDataForUris(uris: List<Uri>) {
        // no-op
    }
}
