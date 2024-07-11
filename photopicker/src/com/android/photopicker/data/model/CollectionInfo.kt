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

package com.android.photopicker.data.model

import android.content.Intent
import kotlin.collections.HashMap
import kotlin.collections.Map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Contains the collection info of a given Provider. */
data class CollectionInfo(
    val authority: String,
    val collectionId: String? = null,
    val accountName: String? = null,
    val accountConfigurationIntent: Intent? = null
)

/**
 * A Utility class that tracks and updates the currently known Collection Info for the given
 * Providers.
 */
class CollectionInfoState {
    private val providerCollectionInfo: HashMap<Provider, CollectionInfo> = HashMap()
    private val mutex = Mutex()

    suspend fun clear() {
        mutex.withLock { providerCollectionInfo.clear() }
    }

    suspend fun updateCollectionInfo(
        availableProviders: List<Provider>,
        collectionInfo: List<CollectionInfo>
    ) {
        val availableProviderAuthorities: Map<String, Provider> =
            availableProviders.map { it.authority to it }.toMap()
        mutex.withLock {
            providerCollectionInfo.clear()
            collectionInfo.forEach {
                if (availableProviderAuthorities.containsKey(it.authority)) {
                    providerCollectionInfo.put(availableProviderAuthorities.get(it.authority)!!, it)
                }
            }
        }
    }

    suspend fun getCollectionInfo(provider: Provider): CollectionInfo {
        mutex.withLock {
            return providerCollectionInfo.getOrDefault(provider, CollectionInfo(provider.authority))
        }
    }
}
