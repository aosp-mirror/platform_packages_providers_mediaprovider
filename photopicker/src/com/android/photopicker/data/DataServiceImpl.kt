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
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.model.CloudMediaProviderDetails
import com.android.photopicker.data.paging.AlbumContentPagingSource
import com.android.photopicker.data.paging.AlbumPagingSource
import com.android.photopicker.data.paging.MediaPagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections.emptyList

/**
 * Provides data to the Photo Picker UI. The data comes from a [ContentProvider] called
 * [MediaProvider].
 *
 * Underlying data changes in [MediaProvider] are observed using [ContentObservers]. When a change
 * in data is observed, the data is re-fetched from the [MediaProvider] process and the new data
 * is emitted to the [StateFlows]-s.
 *
 * This class depends on [FeatureManager] to provide the info about which feature set is currently
 * enabled. This information helps with building the [ContentProvider] queries to fetch data from
 * the [MediaProvider] process.
 */
class DataServiceImpl(
    private val context: Context,
    private val featureManager: FeatureManager,
    private val scope: CoroutineScope,
    private val userMonitor: UserMonitor,
) : DataService {
    override val availableProviders: StateFlow<List<Provider>> = MutableStateFlow(emptyList())

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