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

package com.android.photopicker.data

import android.net.Uri
import android.os.CancellationSignal
import androidx.paging.PagingSource
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.search.model.SearchEnabledState
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A test implementation of [SearchDataService] that provides fake search suggestions and results.
 */
class TestSearchDataServiceImpl() : SearchDataService {

    var mediaSetSize: Int = FakeInMemoryMediaPagingSource.DEFAULT_SIZE
    var mediaList: List<Media>? = null

    override val isSearchEnabled: StateFlow<SearchEnabledState> =
        MutableStateFlow(SearchEnabledState.ENABLED)

    override suspend fun getSearchSuggestions(
        prefix: String,
        limit: Int,
        cancellationSignal: CancellationSignal?,
    ): List<SearchSuggestion> {
        return listOf(
            SearchSuggestion("1", "authority", "France", SearchSuggestionType.LOCATION, null),
            SearchSuggestion("2", "authority", "Favorites", SearchSuggestionType.ALBUM, null),
            SearchSuggestion("3", "authority", "Emma", SearchSuggestionType.FACE, Uri.parse("xyz")),
            SearchSuggestion(null, "authority", "paris", SearchSuggestionType.HISTORY, null),
        )
    }

    override fun getSearchResults(
        suggestion: SearchSuggestion,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> {
        return mediaList?.let { FakeInMemoryMediaPagingSource(it) }
            ?: FakeInMemoryMediaPagingSource(mediaSetSize)
    }

    override fun getSearchResults(
        searchText: String,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> {
        return mediaList?.let { FakeInMemoryMediaPagingSource(it) }
            ?: FakeInMemoryMediaPagingSource(mediaSetSize)
    }
}
