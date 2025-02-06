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
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import androidx.paging.PagingSource
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.data.SEARCH_RESULTS_UPDATE_URI
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.search.model.SearchRequest
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.UserSearchStateInfo
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    companion object {
        // Timeout for receiving suggestions from the data source in milli seconds.
        private const val SUGGESTIONS_TIMEOUT: Long = 3000
    }

    // An internal lock to allow thread-safe updates to the search request and results cache.
    private val searchResultsPagingSourceMutex = Mutex()

    // Cache that contains a search request to search request id map.
    private val searchRequestIdMap: MutableMap<SearchRequest, Int> = mutableMapOf()

    // Cache that contains a search request id to [SearchResultsPagingSource] map.
    private val searchResultsPagingSources: MutableMap<Int, PagingSource<MediaPageKey, Media>> =
        mutableMapOf()

    // Callback flow that listens to changes in search results and emits the search request id when
    // change is observed.
    private var searchResultsUpdateCallbackFlow: Flow<Int>? = null

    // Saves the current job that collects the [searchResultsUpdateCallbackFlow].
    // Cancel this job when there is a change in the current profile's content resolver.
    private var searchResultsUpdateCollectJob: Job? = null

    // Internal mutable flow of the current user's search state info.
    private val _userSearchStateInfo: MutableStateFlow<UserSearchStateInfo> =
        MutableStateFlow(UserSearchStateInfo(null))

    override val userSearchStateInfo: StateFlow<UserSearchStateInfo> = _userSearchStateInfo

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

                _userSearchStateInfo.update { fetchSearchStateInfo() }
            }
        }

        scope.launch(dispatcher) {
            // Only observe the changes in the active content resolver
            dataService.activeContentResolver.collect { activeContentResolver: ContentResolver ->
                Log.d(SearchDataService.TAG, "Active content resolver has changed.")

                // Stop collecting search results updates from previously initialized callback flow.
                searchResultsUpdateCollectJob?.cancel()
                searchResultsUpdateCallbackFlow = initSearchResultsUpdateFlow(activeContentResolver)

                searchResultsUpdateCollectJob =
                    scope.launch(dispatcher) {
                        searchResultsUpdateCallbackFlow?.collect { searchRequestId: Int ->
                            Log.d(
                                SearchDataService.TAG,
                                "Search results update notification " +
                                    "received for search request id $searchRequestId ",
                            )
                            searchResultsPagingSourceMutex.withLock {
                                searchResultsPagingSources[searchRequestId]?.invalidate()
                            }
                        }
                    }
            }
        }
    }

    /**
     * Try to get a list fo search suggestions from Media Provider in the background thread with a
     * time limit.
     */
    override suspend fun getSearchSuggestions(
        prefix: String,
        limit: Int,
        cancellationSignal: CancellationSignal?,
    ): List<SearchSuggestion> {
        // Switch to a background thread.
        return withContext(dispatcher) {
            try {
                // Apply a timeout on getSearchSuggestions API
                withTimeout(SUGGESTIONS_TIMEOUT) {
                    mediaProviderClient.fetchSearchSuggestions(
                        resolver = dataService.activeContentResolver.value,
                        prefix = prefix,
                        limit = limit,
                        historyLimit = 3,
                        availableProviders = dataService.availableProviders.value,
                        cancellationSignal = cancellationSignal,
                    )
                }
            } catch (e: TimeoutException) {
                Log.w(SearchDataService.TAG, "Search suggestions timed out for prefix $prefix", e)

                cancellationSignal?.cancel()
                emptyList<SearchSuggestion>()
            } catch (e: RuntimeException) {
                Log.w(
                    SearchDataService.TAG,
                    "An error occurred while fetching search suggestions for prefix $prefix",
                    e,
                )

                cancellationSignal?.cancel()
                emptyList<SearchSuggestion>()
            }
        }
    }

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
                        !searchResultsPagingSources[searchRequestId]!!.invalid
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
                            events = events,
                        )

                    // Ensure that sync is cancelled when the paging source gets invalidated.
                    searchResultsPagingSource.registerInvalidatedCallback {
                        cancellationSignal.cancel()
                    }

                    Log.d(
                        SearchDataService.TAG,
                        "Created a search results paging source that queries $availableProviders " +
                            "for search request id $searchRequestId",
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
                events = events,
            )
        }
    }

    /**
     * Checks if this is a new search request in the current session.
     * 1. If this is a new search requests, [MediaProvider] is notified with the new search request
     *    and it creates and returns a search request id.
     * 2. If this is not a new search request, previously cached search request id is returned.
     *
     * In both scenarios, this notifies the backend to refresh search results cache for the given
     * search request id.
     */
    private suspend fun getSearchRequestId(
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

                val searchRequestId = searchRequestIdMap[searchRequest]!!

                try {
                    // Ensure search results data in data source is ready for the search query.
                    mediaProviderClient.ensureSearchResults(
                        searchRequest,
                        searchRequestId,
                        availableProviders,
                        contentResolver,
                        config,
                    )
                } catch (e: RuntimeException) {
                    Log.e(SearchDataService.TAG, "Could not ensure search results", e)
                }

                searchRequestId
            } else {
                Log.d(
                    SearchDataService.TAG,
                    "Search request id is not available for search request $searchRequest. " +
                        "Creating a new search request id.",
                )

                val newSearchRequestId =
                    mediaProviderClient.createSearchRequest(
                        searchRequest,
                        availableProviders,
                        contentResolver,
                        config,
                    )

                searchRequestIdMap[searchRequest] = newSearchRequestId
                newSearchRequestId
            }
        }
    }

    /** Get search state info for the current user. */
    private suspend fun fetchSearchStateInfo(): UserSearchStateInfo {
        val contentResolver: ContentResolver = dataService.activeContentResolver.value
        val searchProviderAuthorities: List<String>? =
            mediaProviderClient.fetchSearchProviderAuthorities(
                contentResolver,
                dataService.availableProviders.value,
            )
        val userSearchStateInfo = UserSearchStateInfo(searchProviderAuthorities)
        Log.d(
            SearchDataService.TAG,
            "Available search providers for current user $searchProviderAuthorities. " +
                "Search state is ${userSearchStateInfo.state}",
        )
        return userSearchStateInfo
    }

    /**
     * Creates a callback flow that emits search request id when an update in search results is
     * observed using [ContentObserver] notifications.
     */
    private fun initSearchResultsUpdateFlow(resolver: ContentResolver): Flow<Int> = callbackFlow {
        val observer =
            object : ContentObserver(/* handler */ null) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // Verify that search request id is present in the URI
                    if (
                        uri?.pathSegments?.size == (1 + SEARCH_RESULTS_UPDATE_URI.pathSegments.size)
                    ) {
                        val searchRequestId: Int =
                            Integer.parseInt(uri.pathSegments[uri.pathSegments.size - 1] ?: "-1")
                        trySend(searchRequestId)
                    }
                }
            }

        // Register the content observer callback.
        notificationService.registerContentObserverCallback(
            resolver,
            SEARCH_RESULTS_UPDATE_URI,
            /* notifyForDescendants */ true,
            observer,
        )

        // Unregister when the flow is closed.
        awaitClose { notificationService.unregisterContentObserverCallback(resolver, observer) }
    }
}
