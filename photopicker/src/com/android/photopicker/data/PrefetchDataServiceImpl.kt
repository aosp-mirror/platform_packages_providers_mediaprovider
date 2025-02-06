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

import android.content.Context
import android.util.Log
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.features.search.model.GlobalSearchState
import com.android.photopicker.features.search.model.GlobalSearchStateInfo
import com.android.photopicker.util.mapOfDeferredWithTimeout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/** Implementation of [PrefetchDataService] that typically fetches data from MediaProvider. */
class PrefetchDataServiceImpl(
    val mediaProviderClient: MediaProviderClient,
    val userMonitor: UserMonitor,
    val context: Context,
    val dispatcher: CoroutineDispatcher,
    val scope: CoroutineScope,
) : PrefetchDataService {

    override suspend fun getGlobalSearchState(): GlobalSearchState {
        // Create a map of user id to lambda that fetches search provider authorities for that
        // user.
        val inputMap: Map<Int, suspend (MediaProviderClient) -> Any?> =
            userMonitor.userStatus.value.allProfiles
                .map { profile: UserProfile ->
                    val lambda: suspend (MediaProviderClient) -> Any? =
                        { mediaProviderClient: MediaProviderClient ->
                            mediaProviderClient.fetchSearchProviderAuthorities(
                                context
                                    .createPackageContextAsUser(
                                        context.packageName, /* flags */
                                        0,
                                        profile.handle,
                                    )
                                    .contentResolver
                            )
                        }
                    profile.identifier to lambda
                }
                .toMap()

        // Get a map of user id to Deferred task that fetches search provider authorities for
        // that user in parallel with a timeout.
        val deferredMap: Map<Int, Deferred<Any?>> =
            mapOfDeferredWithTimeout(
                inputMap = inputMap,
                input = mediaProviderClient,
                timeoutMillis = 100L,
                backgroundScope = scope,
                dispatcher = dispatcher,
            )

        // Await all the deferred tasks and create a map of user id to the search provider
        // authorities.
        @Suppress("UNCHECKED_CAST")
        val globalSearchProviders: Map<Int, List<String>?> =
            deferredMap
                .map {
                    val searchProviders: Any? = it.value.await()
                    it.key to if (searchProviders is List<*>?) searchProviders else null
                }
                .toMap() as Map<Int, List<String>?>

        val globalSearchStateInfo =
            GlobalSearchStateInfo(
                globalSearchProviders,
                userMonitor.userStatus.value.activeUserProfile.identifier,
            )
        Log.d(
            PrefetchDataService.TAG,
            "Global search providers available are $globalSearchProviders. " +
                "Search state is $globalSearchStateInfo.state",
        )
        return globalSearchStateInfo.state
    }
}
