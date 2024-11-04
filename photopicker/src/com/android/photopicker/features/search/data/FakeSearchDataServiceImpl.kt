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

import android.net.Uri
import android.os.CancellationSignal
import androidx.paging.PagingSource
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.features.search.model.SearchEnabledState
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Placeholder for the actual [SearchDataService] implementation class. This class can be used to
 * unblock and test UI development till we have the actual implementation in ready.
 */
// TODO(b/361043596) Clean up once we have the implementation for [SearchDataService] class.
class FakeSearchDataServiceImpl(private val dataService: DataService) : SearchDataService {
    // Use the internal flow of type StateFlow<Map<UserProfile, Boolean>> which would cache
    // the result for all profiles, to populate this flow for the current profile.
    override val isSearchEnabled: StateFlow<SearchEnabledState> =
        MutableStateFlow(SearchEnabledState.ENABLED)

    /** Returns a few static suggestions to unblock UI development. */
    override suspend fun getSearchSuggestions(
        prefix: String,
        limit: Int,
        cancellationSignal: CancellationSignal?,
    ): List<SearchSuggestion> {
        if (prefix == "testempty") {
            return emptyList()
        }
        return listOf(
            SearchSuggestion("1", "authority", "France", SearchSuggestionType.LOCATION, null),
            SearchSuggestion("2", "authority", "Favorites", SearchSuggestionType.ALBUM, null),
            SearchSuggestion("2", "authority", "Videos", SearchSuggestionType.VIDEOS_ALBUM, null),
            SearchSuggestion(null, "authority", "france", SearchSuggestionType.HISTORY, null),
            SearchSuggestion(null, "authority", "paris", SearchSuggestionType.HISTORY, null),
            SearchSuggestion("3", "authority", "March", SearchSuggestionType.DATE, null),
            SearchSuggestion("4", "authority", "Emma", SearchSuggestionType.FACE, Uri.parse("xyz")),
            SearchSuggestion("5", "authority", "Bob", SearchSuggestionType.FACE, Uri.parse("xyz")),
            SearchSuggestion("6", "authority", "April", SearchSuggestionType.DATE, null),
            SearchSuggestion("7", "authority", null, SearchSuggestionType.FACE, Uri.parse("xyz")),
        )
    }

    /** Returns all media to unblock UI development. */
    override fun getSearchResults(suggestion: SearchSuggestion): PagingSource<MediaPageKey, Media> =
        dataService.mediaPagingSource()

    /** Returns all media to unblock UI development. */
    override fun getSearchResults(searchText: String): PagingSource<MediaPageKey, Media> =
        dataService.mediaPagingSource()
}
