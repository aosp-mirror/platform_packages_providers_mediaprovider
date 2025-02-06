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

package com.android.photopicker.features.categorygrid.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import androidx.paging.PagingSource
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MEDIA_SETS_UPDATE_URI
import com.android.photopicker.data.MEDIA_SET_CONTENT_UPDATE_URI
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.categorygrid.paging.CategoryAndAlbumPagingSource
import com.android.photopicker.features.categorygrid.paging.MediaSetContentsPagingSource
import com.android.photopicker.features.categorygrid.paging.MediaSetsPagingSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provides category feature data to the Photo Picker UI. The data comes from a [ContentProvider]
 * called [MediaProvider].
 *
 * Underlying data changes in [MediaProvider] are observed using [ContentObservers]. When a change
 * in data is observed, the data is re-fetched from the [MediaProvider] process and the new data is
 * emitted to the [StateFlows]-s.
 *
 * @param dataService Core Picker's data service that provides data related to core functionality.
 * @param config A [StateFlow] that emits [PhotopickerConfiguration] changes.
 * @param scope The [CoroutineScope] the data flows will be shared in.
 * @param dispatcher A [CoroutineDispatcher] to run the coroutines in.
 * @param notificationService An instance of [NotificationService] responsible to listen to data
 *   change notifications.
 * @param mediaProviderClient An instance of [MediaProviderClient] responsible to get data from
 *   MediaProvider.
 * @param events Event bus for the current session.
 */
class CategoryDataServiceImpl(
    private val dataService: DataService,
    private val config: StateFlow<PhotopickerConfiguration>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val notificationService: NotificationService,
    private val mediaProviderClient: MediaProviderClient,
    private val events: Events,
) : CategoryDataService {
    private val cachedPagingSourceMutex = Mutex()
    private var rootCategoryAndAlbumPagingSource: PagingSource<GroupPageKey, Group>? = null
    private val childCategoryPagingSources:
        MutableMap<Group.Category, PagingSource<GroupPageKey, Group>> =
        mutableMapOf()
    private val mediaSetPagingSources:
        MutableMap<Group.Category, PagingSource<GroupPageKey, Group.MediaSet>> =
        mutableMapOf()
    private val mediaSetContentPagingSources:
        MutableMap<Group.MediaSet, PagingSource<MediaPageKey, Media>> =
        mutableMapOf()
    // Callback flow that listens to changes in media set content changes and emits the mediaset id
    // when a change is observed.
    private var mediaSetContentUpdateCallbackFlow: Flow<String>? = null

    // Saves the current job that collects the [mediaSetContentUpdateCallbackFlow].
    // Cancel this job when there is a change in the current profile's content resolver.
    private var mediaSetContentUpdateCollectJob: Job? = null

    // Callback flow that listens to changes in media sets and emits the category id when
    // a change is observed.
    private var mediaSetsUpdateCallbackFlow: Flow<String>? = null

    // Saves the current job that collects the [mediaSetsUpdateCallbackFlow].
    // Cancel this job when there is a change in the current profile's content resolver.
    private var mediaSetsUpdateCollectJob: Job? = null

    init {
        // Listen to available provider changes and clear category cache when required.
        scope.launch(dispatcher) {
            dataService.availableProviders.collect { providers: List<Provider> ->
                Log.d(
                    CategoryDataService.TAG,
                    "Available providers have changed to $providers. " +
                        "Clearing category results cache.",
                )

                cachedPagingSourceMutex.withLock {
                    rootCategoryAndAlbumPagingSource?.invalidate()
                    childCategoryPagingSources.values.forEach { pagingSource ->
                        pagingSource.invalidate()
                    }
                    childCategoryPagingSources.clear()

                    mediaSetPagingSources.values.forEach { pagingSource ->
                        pagingSource.invalidate()
                    }
                    mediaSetPagingSources.clear()

                    mediaSetContentPagingSources.values.forEach { pagingSource ->
                        pagingSource.invalidate()
                    }
                    mediaSetContentPagingSources.clear()
                }
            }
        }

        scope.launch(dispatcher) {
            dataService.activeContentResolver.collect { activeContentResolver: ContentResolver ->
                Log.d(CategoryDataService.TAG, "Active content resolver has changed")

                // Stop collecting media sets from previously initialised callback flow
                mediaSetsUpdateCollectJob?.cancel()
                mediaSetsUpdateCallbackFlow = initMediaSetsCallbackFlow(activeContentResolver)

                mediaSetsUpdateCollectJob =
                    scope.launch(dispatcher) {
                        mediaSetsUpdateCallbackFlow?.collect { categoryId: String ->
                            Log.d(
                                CategoryDataService.TAG,
                                "MediaSets update notification " +
                                    "received for category id " +
                                    categoryId,
                            )
                            cachedPagingSourceMutex.withLock {
                                getMediaSetPagingSourceForCategoryId(categoryId)?.invalidate()
                            }
                        }
                    }
                scope.launch(dispatcher) {
                    // Stop collecting media set content from previously initialised callback flow
                    mediaSetContentUpdateCollectJob?.cancel()
                    mediaSetContentUpdateCallbackFlow =
                        initMediaSetContentCallbackFlow(activeContentResolver)

                    mediaSetContentUpdateCollectJob =
                        scope.launch(dispatcher) {
                            mediaSetContentUpdateCallbackFlow?.collect { mediaSetId ->
                                Log.d(
                                    CategoryDataService.TAG,
                                    "MediaSet content update notification " +
                                        "received for mediaset id " +
                                        mediaSetId,
                                )
                                cachedPagingSourceMutex.withLock {
                                    getMediaSetPagingSourceForMediaSetId(mediaSetId)?.invalidate()
                                }
                            }
                        }
                }
            }
        }
    }

    private fun initMediaSetsCallbackFlow(resolver: ContentResolver): Flow<String> = callbackFlow {
        val observer =
            object : ContentObserver(/* handler */ null) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // Verify that the categoryId is present in the uri
                    if (uri?.pathSegments?.size == (MEDIA_SETS_UPDATE_URI.pathSegments.size + 1)) {
                        val categoryId: String = uri.lastPathSegment ?: "-1"
                        trySend(categoryId)
                    }
                }
            }

        // Register the content observer callback.
        notificationService.registerContentObserverCallback(
            resolver,
            MEDIA_SETS_UPDATE_URI,
            /*notifyForDescendants*/ true,
            observer,
        )

        // Unregister when the flow is closed.
        awaitClose { notificationService.unregisterContentObserverCallback(resolver, observer) }
    }

    private fun getMediaSetPagingSourceForCategoryId(
        categoryId: String
    ): PagingSource<GroupPageKey, Group.MediaSet>? {
        for (category in mediaSetPagingSources.keys) {
            if (category.id == categoryId) {
                return mediaSetPagingSources[category]
            }
        }
        return null
    }

    private fun initMediaSetContentCallbackFlow(resolver: ContentResolver): Flow<String> =
        callbackFlow {
            val observer =
                object : ContentObserver(/*handler*/ null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        // Verify that the mediaSetId is present in the uri
                        if (
                            uri?.pathSegments?.size ==
                                (MEDIA_SET_CONTENT_UPDATE_URI.pathSegments.size + 1)
                        ) {
                            val mediaSetId: String = uri.lastPathSegment ?: "-1"
                            trySend(mediaSetId)
                        }
                    }
                }
            // Register the content observer callback.
            notificationService.registerContentObserverCallback(
                resolver,
                MEDIA_SET_CONTENT_UPDATE_URI,
                /*notifyForDescendants*/ true,
                observer,
            )

            // Unregister when the flow is closed.
            awaitClose { notificationService.unregisterContentObserverCallback(resolver, observer) }
        }

    private fun getMediaSetPagingSourceForMediaSetId(
        mediaSetId: String
    ): PagingSource<MediaPageKey, Media>? {
        for (mediaSet in mediaSetContentPagingSources.keys) {
            if (mediaSet.id == mediaSetId) {
                return mediaSetContentPagingSources[mediaSet]
            }
        }
        return null
    }

    override fun getCategories(
        parentCategory: Group.Category?,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<GroupPageKey, Group> = runBlocking {
        return@runBlocking cachedPagingSourceMutex.withLock {
            return@withLock when {
                parentCategory == null &&
                    rootCategoryAndAlbumPagingSource != null &&
                    !rootCategoryAndAlbumPagingSource!!.invalid -> {
                    Log.d(
                        CategoryDataService.TAG,
                        "A valid paging source is available for root categories and albums. " +
                            "Not creating a new paging source.",
                    )

                    val pagingSource = rootCategoryAndAlbumPagingSource!!
                    // Register the new cancellation signal to be cancelled in the callback.
                    pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }
                    pagingSource
                }

                parentCategory != null &&
                    childCategoryPagingSources.containsKey(parentCategory) &&
                    !childCategoryPagingSources[parentCategory]!!.invalid -> {
                    Log.d(
                        CategoryDataService.TAG,
                        "A valid paging source is available for category ${parentCategory.categoryType}. " +
                            "Not creating a new paging source.",
                    )

                    val pagingSource = childCategoryPagingSources[parentCategory]!!
                    // Register the new cancellation signal to be cancelled in the callback.
                    pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }
                    pagingSource
                }

                else -> {
                    val availableProviders: List<Provider> = dataService.availableProviders.value
                    val contentResolver: ContentResolver = dataService.activeContentResolver.value
                    val pagingSource =
                        CategoryAndAlbumPagingSource(
                            contentResolver = contentResolver,
                            availableProviders = availableProviders,
                            parentCategoryId = parentCategory?.id,
                            mediaProviderClient = mediaProviderClient,
                            dispatcher = dispatcher,
                            configuration = config.value,
                            events = events,
                            cancellationSignal = cancellationSignal,
                        )
                    // Ensure that cancellation get propagated to the data source when the paging
                    // source is invalidated.
                    pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }

                    Log.v(
                        CategoryDataService.TAG,
                        "Created a category paging source that queries $availableProviders for " +
                            "parent category id $parentCategory",
                    )

                    // Update paging source cache.
                    when {
                        parentCategory == null -> rootCategoryAndAlbumPagingSource = pagingSource
                        else -> childCategoryPagingSources[parentCategory] = pagingSource
                    }

                    pagingSource
                }
            }
        }
    }

    override fun getMediaSets(
        category: Group.Category,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<GroupPageKey, Group.MediaSet> = runBlocking {
        return@runBlocking cachedPagingSourceMutex.withLock {
            if (
                mediaSetPagingSources.containsKey(category) &&
                    !mediaSetPagingSources[category]!!.invalid
            ) {
                Log.d(
                    CategoryDataService.TAG,
                    "A valid paging source is available for media sets ${category.categoryType}. " +
                        "Not creating a new paging source.",
                )
                val pagingSource = mediaSetPagingSources[category]!!
                // Register the new cancellation signal to be cancelled in the callback.
                pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }
                pagingSource
            } else {
                refreshMediaSets(category)

                val availableProviders: List<Provider> = dataService.availableProviders.value
                val contentResolver: ContentResolver = dataService.activeContentResolver.value
                val pagingSource =
                    MediaSetsPagingSource(
                        contentResolver = contentResolver,
                        availableProviders = availableProviders,
                        parentCategory = category,
                        mediaProviderClient = mediaProviderClient,
                        dispatcher = dispatcher,
                        configuration = config.value,
                        events = events,
                        cancellationSignal = cancellationSignal,
                    )
                // Ensure that cancellation get propagated to the data source when the paging source
                // is invalidated.
                pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }

                Log.v(
                    CategoryDataService.TAG,
                    "Created a media source paging source that queries $availableProviders for " +
                        "parent category id $category",
                )

                mediaSetPagingSources[category] = pagingSource
                pagingSource
            }
        }
    }

    override fun getMediaSetContents(
        mediaSet: Group.MediaSet,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> = runBlocking {
        return@runBlocking cachedPagingSourceMutex.withLock {
            if (
                mediaSetContentPagingSources.containsKey(mediaSet) &&
                    !mediaSetContentPagingSources[mediaSet]!!.invalid
            ) {
                Log.d(
                    CategoryDataService.TAG,
                    "A valid paging source is available for media set content ${mediaSet.id}. " +
                        "Not creating a new paging source.",
                )
                val pagingSource = mediaSetContentPagingSources[mediaSet]!!
                // Register the new cancellation signal to be cancelled in the callback.
                pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }
                pagingSource
            } else {
                refreshMediaSetContents(mediaSet)

                val availableProviders: List<Provider> = dataService.availableProviders.value
                val contentResolver: ContentResolver = dataService.activeContentResolver.value
                val pagingSource =
                    MediaSetContentsPagingSource(
                        contentResolver = contentResolver,
                        parentMediaSet = mediaSet,
                        mediaProviderClient = mediaProviderClient,
                        dispatcher = dispatcher,
                        configuration = config.value,
                        events = events,
                        cancellationSignal = cancellationSignal,
                    )
                // Ensure that cancellation get propagated to the data source when the paging source
                // is invalidated.
                pagingSource.registerInvalidatedCallback { cancellationSignal?.cancel() }

                Log.v(
                    CategoryDataService.TAG,
                    "Created a media source paging source that queries $availableProviders for " +
                        "parent media set id ${mediaSet.id}",
                )

                mediaSetContentPagingSources[mediaSet] = pagingSource
                pagingSource
            }
        }
    }

    private suspend fun refreshMediaSets(category: Group.Category) {
        val providers = dataService.availableProviders.value
        val contentResolver = dataService.activeContentResolver.value
        val isCategoryProviderAvailable =
            providers.any { provider -> provider.authority == category.authority }

        if (isCategoryProviderAvailable) {
            Log.d(
                CategoryDataService.TAG,
                "Sending media sets refresh request to the data source" +
                    " for parent category ${category.categoryType}",
            )

            mediaProviderClient.refreshMediaSets(contentResolver, category, config.value, providers)
        } else {
            Log.e(
                CategoryDataService.TAG,
                "Available providers $providers " +
                    "does not contain category authority ${category.authority}. " +
                    "Skip sending refresh media sets request.",
            )
        }
    }

    private suspend fun refreshMediaSetContents(mediaSet: Group.MediaSet) {
        val providers = dataService.availableProviders.value
        val contentResolver = dataService.activeContentResolver.value
        val isMediaSetProviderAvailable =
            providers.any { provider -> provider.authority == mediaSet.authority }

        if (isMediaSetProviderAvailable) {
            Log.d(
                CategoryDataService.TAG,
                "Sending media set contents refresh request to the data source" +
                    " for parent media set ${mediaSet.id}",
            )

            mediaProviderClient.refreshMediaSetContents(
                contentResolver,
                mediaSet,
                config.value,
                providers,
            )
        } else {
            Log.e(
                CategoryDataService.TAG,
                "Available providers $providers " +
                    "does not contain media set authority ${mediaSet.authority}. " +
                    "Skip sending refresh media set contents request.",
            )
        }
    }
}
