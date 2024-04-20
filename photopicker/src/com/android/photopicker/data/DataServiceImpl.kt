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
import android.database.ContentObserver
import android.net.Uri
import android.util.Log
import androidx.paging.PagingSource
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.CloudMediaProviderDetails
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.MediaPagingSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provides data to the Photo Picker UI. The data comes from a [ContentProvider] called
 * [MediaProvider].
 *
 * Underlying data changes in [MediaProvider] are observed using [ContentObservers]. When a change
 * in data is observed, the data is re-fetched from the [MediaProvider] process and the new data
 * is emitted to the [StateFlows]-s.
 *
 * @param userStatus A [StateFlow] with the current active user's details.
 * @param scope The [CoroutineScope] the data flows will be shared in.
 * @param notificationService An instance of [NotificationService] responsible to listen to data
 * change notifications.
 * @param mediaProviderClient An instance of [MediaProviderClient] responsible to get data from
 * MediaProvider.
 */
class DataServiceImpl(
    private val userStatus: StateFlow<UserStatus>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val notificationService: NotificationService,
    private val mediaProviderClient: MediaProviderClient,
) : DataService {
    private val _activeContentResolver = MutableStateFlow<ContentResolver>(
            userStatus.value.activeContentResolver)

    private var mediaPagingSource: MediaPagingSource? = null
    // An internal lock to allow thread-safe updates to the media paging source
    private val mediaPagingSourceMutex = Mutex()


    /**
     * Callback flow that listens to changes in the available providers and emits updated list of
     * available providers.
     */
    private var availableProviderCallbackFlow: Flow<List<Provider>>? = null

    /**
     * Saves the current job that collects the [availableProviderCallbackFlow].
     * Cancel this job when there is a change in the [availableProviderCallbackFlow]
     */
    private var availableProviderCollectJob: Job? = null

    /**
     * Internal [StateFlow] that emits when the [availableProviderCallbackFlow] emits a new list of
     * providers. The [availableProviderCallbackFlow] can change if the active user in a session has
     * changed.
     *
     * The initial value of this flow is an empty list to avoid an IPC to fetch the actual value
     * from Media Provider from the main thread.
     */
    private val _availableProviders: MutableStateFlow<List<Provider>> =
        MutableStateFlow(emptyList())

    /**
     * Create an immutable state flow from the callback flow [_availableProviders]. The state flow
     * helps retain and provide immediate access to the last emitted value.
     *
     * The producer block remains active for some time after the last observer stops collecting.
     * This helps retain the flow through transient changes like activity recreation due to
     * config changes.
     *
     * Note that [StateFlow] automatically filters out subsequent repetitions of the same value.
     */
    override val availableProviders: StateFlow<List<Provider>> =
        _availableProviders.stateIn(
            scope,
            SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MILLI_SECONDS),
            _availableProviders.value
        )

    companion object {
        const val FLOW_TIMEOUT_MILLI_SECONDS: Long = 5000
    }

    init {
        scope.launch(dispatcher) {
            availableProviders.collect { providers: List<Provider> ->
                // Send refresh media request after the available providers are available.
                refreshMedia(providers)
                mediaPagingSourceMutex.withLock {
                    mediaPagingSource?.invalidate()
                }
            }
        }

        scope.launch(dispatcher) {
            // Only observe the changes in the active content resolver
            _activeContentResolver.collect { activeContentResolver: ContentResolver ->
                Log.d(DataService.TAG, "Active content resolver has changed.")

                // Stop collecting available providers from previously initialized callback flow.
                availableProviderCollectJob?.cancel()
                availableProviderCallbackFlow = initAvailableProvidersFlow(activeContentResolver)

                availableProviderCollectJob = scope.launch(dispatcher) {
                    availableProviderCallbackFlow?.collect { providers: List<Provider> ->
                        _availableProviders.update { providers }
                    }
                }
            }
        }

        scope.launch(dispatcher) {
            userStatus.collect { userStatusValue: UserStatus ->
                _activeContentResolver.update { userStatusValue.activeContentResolver }
            }
        }
    }

    /**
     * Creates a callback flow that listens to changes in the available providers using
     * [ContentObserver] and emits updated list of available providers.
     */
    private fun initAvailableProvidersFlow(resolver: ContentResolver): Flow<List<Provider>> =
            callbackFlow<Unit> {
        // Define a callback that tries sending a [Unit] in the [Channel].
        val observer = object : ContentObserver(/* handler */ null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }

        // Register the content observer callback.
        notificationService.registerContentObserverCallback(
            resolver,
            AVAILABLE_PROVIDERS_CHANGE_NOTIFICATION_URI,
            /* notifyForDescendants */ true,
            observer
        )

        // Trigger the first fetch of available providers.
        trySend(Unit)

        // Unregister when the flow is closed.
        awaitClose {
            notificationService.unregisterContentObserverCallback(
                resolver,
                observer
            )
        }
    }.map {
        // Fetch the available providers again when a change is detected.
        mediaProviderClient.fetchAvailableProviders(resolver)
    }

    override fun albumContentPagingSource(albumId: String): PagingSource<MediaPageKey, Media> =
        throw NotImplementedError("This method is not implemented yet.")

    override fun albumPagingSource(): PagingSource<MediaPageKey, Album> =
        throw NotImplementedError("This method is not implemented yet.")

    override fun cloudMediaProviderDetails(
        authority: String
    ): StateFlow<CloudMediaProviderDetails?> =
        throw NotImplementedError("This method is not implemented yet.")

    override fun mediaPagingSource(): PagingSource<MediaPageKey, Media> = runBlocking {
        mediaPagingSourceMutex.withLock {
            val availableProviders: List<Provider> = availableProviders.value
            val contentResolver: ContentResolver = _activeContentResolver.value

            Log.v(DataService.TAG, "Created a paging source that queries $availableProviders")

            val immutableMediaPagingSource = MediaPagingSource(
                    contentResolver,
                    availableProviders,
                    mediaProviderClient
            )

            mediaPagingSource = immutableMediaPagingSource
            immutableMediaPagingSource
        }
    }

    override suspend fun refreshMedia() {
        val availableProviders: List<Provider> = availableProviders.value
        refreshMedia(availableProviders)
    }

    private fun refreshMedia(
        availableProviders: List<Provider>
    ) {
        if (availableProviders.isNotEmpty()) {
            mediaProviderClient.refreshMedia(
                    availableProviders,
                    _activeContentResolver.value
            )
        } else {
            Log.w(DataService.TAG,
                    "Cannot refresh media when there are no providers available")
        }
    }

    override suspend fun refreshAlbumMedia(albumId: String, providerAuthority: String) =
        throw NotImplementedError("This method is not implemented yet.")
}
