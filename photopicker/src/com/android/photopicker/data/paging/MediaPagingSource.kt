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
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * This [PagingSource] class is responsible to providing paginated media data from Picker Database
 * by serving requests from Paging library.
 *
 * It sources data from a [ContentProvider] called [MediaProvider].
 */
class MediaPagingSource(
    private val contentResolver: ContentResolver,
    private val availableProviders: List<Provider>,
    private val mediaProviderClient: MediaProviderClient,
    private val dispatcher: CoroutineDispatcher,
    private val configuration: PhotopickerConfiguration,
    private val events: Events,
    private val isPreviewSession: Boolean = false,
    private val currentSelection: List<String> = emptyList(),
    private val currentDeSelection: List<String> = emptyList(),
) : PagingSource<MediaPageKey, Media>() {
    companion object {
        val TAG: String = "PickerMediaPagingSource"
    }

    override suspend fun load(params: LoadParams<MediaPageKey>): LoadResult<MediaPageKey, Media> {
        val pageKey = params.key ?: MediaPageKey()
        val pageSize = params.loadSize
        // Switch to the background thread from the main thread using [withContext].
        val mediaFetchResult =
            withContext(dispatcher) {
                try {
                    if (availableProviders.isEmpty()) {
                        throw IllegalArgumentException("No available providers found.")
                    }
                    if (isPreviewSession) {
                        mediaProviderClient.fetchPreviewMedia(
                            pageKey,
                            pageSize,
                            contentResolver,
                            availableProviders,
                            configuration,
                            currentSelection,
                            currentDeSelection,
                            // only true for first page or refreshes.
                            /* isFirstPage */ (params.key == null),
                        )
                    } else {
                        mediaProviderClient.fetchMedia(
                            pageKey,
                            pageSize,
                            contentResolver,
                            availableProviders,
                            configuration,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not fetch page from Media provider", e)
                    LoadResult.Error(e)
                }
            }

        if (mediaFetchResult is LoadResult.Page) {
            // Dispatch a pageInfo event to log paging details for fetching media items
            // Keeping page number as 0 for all dispatched events for now for simplicity
            events.dispatch(
                Event.LogPhotopickerPageInfo(
                    FeatureToken.CORE.token,
                    configuration.sessionId,
                    /* pageNumber */ 0,
                    pageSize,
                )
            )

            Log.d(TAG, "Received ${mediaFetchResult.data.size} media items from the data source.")
        }
        return mediaFetchResult
    }

    override fun getRefreshKey(state: PagingState<MediaPageKey, Media>): MediaPageKey? = null
}
