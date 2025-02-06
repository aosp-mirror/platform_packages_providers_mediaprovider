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

package com.android.photopicker.data.paging

import android.content.ContentResolver
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * This [PagingSource] class is responsible to providing paginated album data from Picker Database
 * by serving requests from Paging library.
 *
 * It sources data from a [ContentProvider] called [MediaProvider].
 */
class AlbumPagingSource(
    private val contentResolver: ContentResolver,
    private val availableProviders: List<Provider>,
    private val mediaProviderClient: MediaProviderClient,
    private val dispatcher: CoroutineDispatcher,
    private val configuration: PhotopickerConfiguration,
    private val events: Events,
) : PagingSource<MediaPageKey, Album>() {
    companion object {
        val TAG: String = "PickerAlbumPagingSource"
    }

    override suspend fun load(params: LoadParams<MediaPageKey>): LoadResult<MediaPageKey, Album> {
        val pageKey = params.key ?: MediaPageKey()
        val pageSize = params.loadSize
        // Switch to the background thread from the main thread using [withContext].
        val albumFetchResult =
            withContext(dispatcher) {
                try {
                    if (availableProviders.isEmpty()) {
                        throw IllegalArgumentException("No available providers found.")
                    }

                    mediaProviderClient.fetchAlbums(
                        pageKey,
                        pageSize,
                        contentResolver,
                        availableProviders,
                        configuration,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not fetch page from Media provider", e)
                    LoadResult.Error(e)
                }
            }

        if (albumFetchResult is LoadResult.Page) {
            // Dispatch a pageInfo event to log paging details for fetching albums
            // Keeping page number as 0 for all dispatched events for now for simplicity
            events.dispatch(
                Event.LogPhotopickerPageInfo(
                    FeatureToken.CORE.token,
                    configuration.sessionId,
                    /* pageNumber */ 0,
                    pageSize,
                )
            )

            Log.d(TAG, "Received ${albumFetchResult.data.size} album items from the data source.")
        }
        return albumFetchResult
    }

    override fun getRefreshKey(state: PagingState<MediaPageKey, Album>): MediaPageKey? = null
}
