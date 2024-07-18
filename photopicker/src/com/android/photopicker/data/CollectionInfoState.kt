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
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Provider
import kotlin.collections.HashMap
import kotlin.collections.Map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Utility class that tracks and updates the currently known Collection Info for the given
 * Providers.
 */
class CollectionInfoState(
    private val mediaProviderClient: MediaProviderClient,
    private val activeContentResolver: StateFlow<ContentResolver>,
    private val availableProviders: StateFlow<List<Provider>>
) {
    private val providerCollectionInfo: HashMap<Provider, CollectionInfo> = HashMap()
    private val mutex = Mutex()

    /** Clear the collection info cache. */
    suspend fun clear() {
        mutex.withLock { providerCollectionInfo.clear() }
    }

    /**
     * Clears the current collection info cache and updates it with the collection info list
     * provided in the parameters.
     *
     * @param collectionInfo List of the latest collection infos fetched from the data source.
     */
    suspend fun updateCollectionInfo(collectionInfo: List<CollectionInfo>) {
        val availableProviderAuthorities: Map<String, Provider> =
            availableProviders.value.map { it.authority to it }.toMap()
        mutex.withLock {
            providerCollectionInfo.clear()
            collectionInfo.forEach {
                if (availableProviderAuthorities.containsKey(it.authority)) {
                    providerCollectionInfo.put(
                        availableProviderAuthorities.getValue(it.authority),
                        it
                    )
                }
            }
        }
    }

    /**
     * Tries to fetch the collection info of the given provider from cache. If it is not available,
     * returns null.
     */
    suspend fun getCachedCollectionInfo(provider: Provider): CollectionInfo? {
        mutex.withLock {
            return providerCollectionInfo.get(provider)
        }
    }

    /**
     * Tries to fetch the collection info of the given provider from cache. If it is not available,
     * updates the collection info cache from the data source and again tries to fetch the
     * collection info from the updated cache and returns it.
     *
     * If it is still not available, returns a default collection info object with only the
     * authority set.
     */
    suspend fun getCollectionInfo(provider: Provider): CollectionInfo {
        var cachedCollectionInfo = getCachedCollectionInfo(provider)

        if (cachedCollectionInfo == null) {
            val collectionInfos =
                mediaProviderClient.fetchCollectionInfo(activeContentResolver.value)
            updateCollectionInfo(collectionInfos)

            cachedCollectionInfo = getCachedCollectionInfo(provider)
        }

        return cachedCollectionInfo ?: CollectionInfo(provider.authority)
    }
}
