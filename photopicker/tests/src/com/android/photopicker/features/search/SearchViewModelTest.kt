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

package com.android.photopicker.features.search

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.data.TestSearchDataServiceImpl
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testfetchSuggestions_initialState_hasFaceSuggestionAsSeparateList() {
        runTest {
            val viewModel =
                SearchViewModel(this.backgroundScope, testDispatcher, TestSearchDataServiceImpl())
            viewModel.fetchSuggestions("")
            advanceTimeBy(1000)
            val suggestionLists = viewModel.suggestionLists.value
            assertWithMessage("Unexpected total suggestions list size not correct")
                .that(viewModel.suggestionLists.value.totalSuggestions)
                .isEqualTo(4)
            assertWithMessage("Unexpected history suggestions list size")
                .that(suggestionLists.history.size)
                .isEqualTo(1)
            assertWithMessage("Unexpected face suggestions list size")
                .that(suggestionLists.face.size)
                .isEqualTo(1)
            assertWithMessage("Unexpected other suggestions list size")
                .that(suggestionLists.other.size)
                .isEqualTo(2)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testfetchSuggestions_textQueryState_hasFaceSuggestionIntegratedList() {
        runTest {
            val viewModel =
                SearchViewModel(this.backgroundScope, testDispatcher, TestSearchDataServiceImpl())
            viewModel.fetchSuggestions("abc")
            advanceTimeBy(1000)
            val suggestionLists = viewModel.suggestionLists.value
            assertWithMessage("Unexpected total suggestions list size not correct")
                .that(suggestionLists.totalSuggestions)
                .isEqualTo(4)
            assertWithMessage("Unexpected history suggestions list size")
                .that(suggestionLists.history.size)
                .isEqualTo(1)
            assertWithMessage("Unexpected face suggestions list size")
                .that(suggestionLists.face.size)
                .isEqualTo(0)
            assertWithMessage("Unexpected other suggestions list size")
                .that(suggestionLists.other.size)
                .isEqualTo(3)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun clearSearch_searchState_isInactive() = runTest {
        val viewModel =
            SearchViewModel(this.backgroundScope, testDispatcher, TestSearchDataServiceImpl())
        viewModel.performSearch("test") // Set a search state
        viewModel.clearSearch()
        assertWithMessage("Search state is not Inactive")
            .that(SearchState.Inactive)
            .isEqualTo(viewModel.searchState.value)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun performSearch_withSuggestion_searchStateIsActiveSuggestion() = runTest {
        val viewModel =
            SearchViewModel(this.backgroundScope, testDispatcher, TestSearchDataServiceImpl())
        val suggestion =
            SearchSuggestion(
                "suggestion",
                authority = "",
                "text",
                type = SearchSuggestionType.TEXT,
                null,
            )
        viewModel.performSearch(suggestion)
        assertWithMessage("Search state is not Active for suggestion search")
            .that(SearchState.Active.SuggestionSearch(suggestion))
            .isEqualTo(viewModel.searchState.value)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun performSearch_withQuery_searchStateIsActiveQuery() = runTest {
        val viewModel =
            SearchViewModel(this.backgroundScope, testDispatcher, TestSearchDataServiceImpl())
        val query = "test query"
        viewModel.performSearch(query)
        assertWithMessage("Search state is not Active for query search")
            .that(SearchState.Active.QuerySearch(query))
            .isEqualTo(viewModel.searchState.value)
    }
}
