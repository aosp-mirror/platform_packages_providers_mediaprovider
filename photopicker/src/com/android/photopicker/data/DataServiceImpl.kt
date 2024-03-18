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

import android.database.ContentObserver
import android.net.Uri
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.CloudMediaProviderDetails
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.AlbumContentPagingSource
import com.android.photopicker.data.paging.AlbumPagingSource
import com.android.photopicker.data.paging.MediaPagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Provides data to the Photo Picker UI. The data comes from a [ContentProvider] called
 * [MediaProvider].
 *
 * Underlying data changes in [MediaProvider] are observed using [ContentObservers]. When a change
 * in data is observed, the data is re-fetched from the [MediaProvider] process and the new data
 * is emitted to the [StateFlows]-s.
 */
class DataServiceImpl(
    private val userStatus: StateFlow<UserStatus>,
    private val scope: CoroutineScope,
    private val notificationService: NotificationService,
) : DataService {
    companion object {
        const val FLOW_TIMEOUT_MILLI_SECONDS: Long = 5000
    }

    /**
     * Internal callback flow that fetches data from MediaProvider and emits updates when a change
     * is detected using [ContentObserver].
     */
    private val availableProvidersCallbackFlow: Flow<List<Provider>> = callbackFlow<Unit> {
        // Define a callback that tries sending a [Unit] in the [Channel].
        val observer = object : ContentObserver(/* handler */ null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }

        // Register the content observer callback.
        notificationService.registerContentObserverCallback(
            userStatus.value.activeContentResolver,
            AVAILABLE_PROVIDERS_CHANGE_NOTIFICATION_URI,
            /* notifyForDescendants */ true,
            observer
        )

        // Unregister when the flow is closed.
        awaitClose {
            notificationService.unregisterContentObserverCallback(
                userStatus.value.activeContentResolver,
                observer
            )
        }
    }.map {
        // Fetch the available providers again when a change is detected.
        MediaProviderClient.fetchAvailableProviders(userStatus.value.activeContentResolver)
    }

    /**
     * Create a state flow from the callback flow [availableProvidersCallbackFlow]. The state flow
     * helps retain and provide immediate access to the last emitted value.
     *
     * The producer block remains active for some time after the last observer stops collecting.
     * This helps retain the flow through transient changes like activity recreation due to
     * config changes.
     *
     * Note that [StateFlow] automatically filters out subsequent repetitions of the same value.
     */
    override val availableProviders: StateFlow<List<Provider>> by lazy {
        availableProvidersCallbackFlow.stateIn(
            scope,
            SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MILLI_SECONDS),
            MediaProviderClient.fetchAvailableProviders(userStatus.value.activeContentResolver)
        )
    }

    override fun albumContentPagingSource(
        albumId: String
    ): AlbumContentPagingSource =
        throw NotImplementedError("This method is not implemented yet.")

    override fun albumPagingSource(): AlbumPagingSource =
        throw NotImplementedError("This method is not implemented yet.")

    override fun cloudMediaProviderDetails(
            authority: String
    ): StateFlow<CloudMediaProviderDetails?> =
            throw NotImplementedError("This method is not implemented yet.")

    override fun mediaPagingSource(): MediaPagingSource =
        throw NotImplementedError("This method is not implemented yet.")
}