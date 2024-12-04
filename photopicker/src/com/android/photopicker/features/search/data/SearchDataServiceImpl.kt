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

package com.android.photopicker.features.search.data

import android.content.ContentResolver
import android.os.CancellationSignal
import android.util.Log
import androidx.paging.PagingSource
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.search.model.SearchEnabledState
import com.android.photopicker.features.search.model.SearchRequest
import com.android.photopicker.features.search.model.SearchSuggestion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provides search feature data to the Photo Picker UI. The data comes from a [ContentProvider]
 * called [MediaProvider].
 *
 * Underlying data changes in [MediaProvider] are observed using [ContentObservers]. When a change
 * in data is observed, the data is re-fetched from the [MediaProvider] process and the new data is
 * emitted to the [StateFlows]-s.
 *
 * @param dataService Core Picker's data service that provides data related to core functionality.
 * @param userStatus A [StateFlow] with the current active user's details.
 * @param photopickerConfiguration A [StateFlow] that emits [PhotopickerConfiguration] changes.
 * @param scope The [CoroutineScope] the data flows will be shared in.
 * @param dispatcher A [CoroutineDispatcher] to run the coroutines in.
 * @param notificationService An instance of [NotificationService] responsible to listen to data
 *   change notifications.
 * @param mediaProviderClient An instance of [MediaProviderClient] responsible to get data from
 *   MediaProvider.
 * @param events Event bus for the current session.
 */
class SearchDataServiceImpl(
    private val dataService: DataService,
    private val userStatus: StateFlow<UserStatus>,
    private val photopickerConfiguration: StateFlow<PhotopickerConfiguration>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val notificationService: NotificationService,
    private val mediaProviderClient: MediaProviderClient,
    private val events: Events,
) : SearchDataService {
    // An internal lock to allow thread-safe updates to the search request and results cache.
    private val searchResultsPagingSourceMutex = Mutex()

    // Cache that contains a search request to search request id map.
    private val searchRequestIdMap: MutableMap<SearchRequest, Int> = mutableMapOf()

    // Cache that contains a search request id to [SearchResultsPagingSource] map.
    private val searchResultsPagingSources: MutableMap<Int, PagingSource<MediaPageKey, Media>> =
        mutableMapOf()

    init {
        // Listen to available provider changes and clear search cache when required.
        scope.launch(dispatcher) {
            dataService.availableProviders.collect { providers: List<Provider> ->
                Log.d(
                    SearchDataService.TAG,
                    "Available providers have changed to $providers. " +
                        "Clearing search results cache.",
                )

                searchResultsPagingSourceMutex.withLock {
                    searchResultsPagingSources.values.forEach { pagingSource ->
                        pagingSource.invalidate()
                    }

                    searchResultsPagingSources.clear()
                    searchRequestIdMap.clear()
                }
            }
        }
    }

    // TODO(b/381819838)
    override val isSearchEnabled: StateFlow<SearchEnabledState> =
        MutableStateFlow(SearchEnabledState.ENABLED)

    // TODO(b/381820020)
    override suspend fun getSearchSuggestions(
        prefix: String,
        limit: Int,
        cancellationSignal: CancellationSignal?,
    ): List<SearchSuggestion> = emptyList()

    /**
     * Returns an instance of [SearchResultsPagingSource] that can source search results for the
     * given search suggestions query.
     */
    override fun getSearchResults(
        suggestion: SearchSuggestion,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> {
        return getSearchResults(
            SearchRequest.SearchSuggestionRequest(suggestion),
            cancellationSignal,
        )
    }

    /**
     * Returns an instance of [SearchResultsPagingSource] that can source search results for the
     * given search text query.
     */
    override fun getSearchResults(
        searchText: String,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> {
        return getSearchResults(SearchRequest.SearchTextRequest(searchText), cancellationSignal)
    }

    /**
     * Returns an instance of [SearchResultsPagingSource] that can source search results for the
     * given search request.
     *
     * @param searchRequest Input search request.
     * @param inputCancellationSignal [CancellationSignal] received from the UI layer.
     */
    private fun getSearchResults(
        searchRequest: SearchRequest,
        inputCancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> = runBlocking {
        val availableProviders: List<Provider> = dataService.availableProviders.value
        val contentResolver: ContentResolver = dataService.activeContentResolver.value
        val config: PhotopickerConfiguration = photopickerConfiguration.value

        return@runBlocking try {
            val searchRequestId: Int =
                getSearchRequestId(searchRequest, availableProviders, contentResolver, config)

            searchResultsPagingSourceMutex.withLock {
                if (
                    searchResultsPagingSources.containsKey(searchRequestId) &&
                        searchResultsPagingSources[searchRequestId]!!.invalid
                ) {
                    Log.d(
                        SearchDataService.TAG,
                        "A valid paging source is available for search request id " +
                            "$searchRequestId. Not creating a new paging source.",
                    )

                    searchResultsPagingSources[searchRequestId]!!
                } else {
                    val cancellationSignal = inputCancellationSignal ?: CancellationSignal()

                    val searchResultsPagingSource =
                        SearchResultsPagingSource(
                            searchRequestId = searchRequestId,
                            contentResolver = contentResolver,
                            availableProviders = availableProviders,
                            mediaProviderClient = mediaProviderClient,
                            dispatcher = dispatcher,
                            configuration = config,
                            cancellationSignal = cancellationSignal,
                        )

                    // Ensure that sync is cancelled when the paging source gets invalidated.
                    searchResultsPagingSource.registerInvalidatedCallback {
                        cancellationSignal.cancel()
                    }

                    Log.d(
                        DataService.TAG,
                        "Created a search results paging source that queries $availableProviders",
                    )

                    searchResultsPagingSources[searchRequestId] = searchResultsPagingSource
                    searchResultsPagingSource
                }
            }
        } catch (e: RuntimeException) {
            Log.e(SearchDataService.TAG, "Could not create search results paging source", e)

            // Create a [SearchResultsPagingSource] object so that the load method can handle the
            // error in loading media items for null searchRequestId elegantly without crashing
            // the app.
            SearchResultsPagingSource(
                searchRequestId = null,
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mediaProviderClient,
                dispatcher = dispatcher,
                configuration = config,
                cancellationSignal = null,
            )
        }
    }

    /**
     * Checks if this is a new search request in the current session.
     * 1. If this is a new search requests, [MediaProvider] is notified with the new search request
     *    and it creates and returns a search request id.
     * 2. If this is not a new search request, previously caches search request id is returned.
     */
    private fun getSearchRequestId(
        searchRequest: SearchRequest,
        availableProviders: List<Provider>,
        contentResolver: ContentResolver,
        config: PhotopickerConfiguration,
    ): Int = runBlocking {
        searchResultsPagingSourceMutex.withLock {
            if (searchRequestIdMap.containsKey(searchRequest)) {
                Log.d(
                    SearchDataService.TAG,
                    "Search request id is available for search request $searchRequest. " +
                        "Not creating a new search request id.",
                )
                searchRequestIdMap[searchRequest]!!
            } else {
                mediaProviderClient.createSearchRequest(
                    searchRequest,
                    availableProviders,
                    contentResolver,
                    config,
                )
            }
        }
    }
}
