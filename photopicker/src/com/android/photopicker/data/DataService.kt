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

import androidx.paging.PagingSource
import com.android.photopicker.data.model.CloudMediaProviderDetails
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides data to the Photo Picker UI. Typically, for paged data, this class provides a
 * [PagingSource] instance and for unpaged data, it provides a [StateFlow].
 *
 * It's implementation should ideally observe data changes and emit updates when possible.
 */
interface DataService {

    companion object {
        val TAG: String = "PhotopickerDataService"
    }

    /** A [StateFlow] with a list of available [Provider]-s. */
    val availableProviders: StateFlow<List<Provider>>

    /** @return an instance of [PagingSource]. */
    fun albumContentPagingSource(albumId: String): PagingSource<MediaPageKey, Media>

    /** @return an instance of [PagingSource]. */
    fun albumPagingSource(): PagingSource<MediaPageKey, Album>

    /**
     * @param authority The authority of the [Provider]. See [availableProviders] to get the
     *   authority of the available providers.
     * @return A [StateFlow] with the details of the provider. It returns [null] if the requested
     *   provider is not longer available.
     */
    fun cloudMediaProviderDetails(authority: String): StateFlow<CloudMediaProviderDetails?>

    /**
     * @return a new instance of [PagingSource].
     */
    fun mediaPagingSource(): PagingSource<MediaPageKey, Media>
}
