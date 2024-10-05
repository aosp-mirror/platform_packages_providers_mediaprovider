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

import android.os.CancellationSignal
import androidx.paging.PagingSource
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.features.search.model.SearchEnabledState
import com.android.photopicker.features.search.model.SearchSuggestion
import kotlinx.coroutines.flow.StateFlow

/**
 * Powers UI with data for the search feature. This class owns the responsibility to:
 * - fetch data on demand
 * - cache data if required
 * - keep track of data updates in the data source
 * - detect and refresh stale data
 */
interface SearchDataService {
    companion object {
        const val TAG: String = "PhotopickerSearchDataService"
    }

    /**
     * A [StateFlow] that emits a value when current profile changes or search config in the data
     * source changes. It hold that value of the current profile's search enabled state
     * [SearchEnabledState].
     */
    val isSearchEnabled: StateFlow<SearchEnabledState>

    /**
     * Get search suggestions for the user in zero state and as the user is typing.
     *
     * @param prefix The search text typed so far by the user. If the user is in zero-state (has not
     *   typed anything), the prefix will be null.
     * @param limit Maximum number of search suggestions.
     * @param cancellationSignal used to indicate that the fetch suggestions operation should be
     *   cancelled. If the user has cleared the search text or the prefix has changed, UI layer can
     *   choose to stop the get suggestions operation to save resources.
     * @return A list of [SearchSuggestion]-s.
     */
    suspend fun getSearchSuggestions(
        prefix: String,
        limit: Int = 200,
        cancellationSignal: CancellationSignal? = null,
    ): List<SearchSuggestion>

    /**
     * Get search results for a search suggestion. This method should be used when the user searches
     * for an item by selecting a search suggestion.
     *
     * @param suggestion The search suggestion the user selected.
     * @return The [PagingSource] that fetches a page using [MediaPageKey]. A page in the paging
     *   source contains a [List] of [Media] items.
     */
    fun getSearchResults(suggestion: SearchSuggestion): PagingSource<MediaPageKey, Media>

    /**
     * Get search results for a search text query. This method should be used when the user searches
     * for an item by entering something in the search bar.
     *
     * @param searchText The search text that the user entered.
     * @return The [PagingSource] that fetches a page using [MediaPageKey]. A page in the paging
     *   source contains a [List] of [Media] items.
     */
    fun getSearchResults(searchText: String): PagingSource<MediaPageKey, Media>
}
