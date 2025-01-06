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
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.TestSearchDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    lateinit var selection: Selection<Media>
    lateinit var events: Events
    private val deviceConfigProxy = TestDeviceConfigProxyImpl()

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testfetchSuggestions_initialState_hasFaceSuggestionAsSeparateList() {
        runTest {
            provideSelectionEvents(this.backgroundScope)
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    generatePickerSessionId(),
                )
            val viewModel =
                SearchViewModel(
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    TestSearchDataServiceImpl(),
                    selection,
                    events,
                    configurationManager,
                )
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
            provideSelectionEvents(this.backgroundScope)
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    generatePickerSessionId(),
                )
            val viewModel =
                SearchViewModel(
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    TestSearchDataServiceImpl(),
                    selection,
                    events,
                    configurationManager,
                )
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
        provideSelectionEvents(this.backgroundScope)
        val configurationManager =
            ConfigurationManager(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                scope = this.backgroundScope,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                deviceConfigProxy,
                generatePickerSessionId(),
            )
        val viewModel =
            SearchViewModel(
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                TestSearchDataServiceImpl(),
                selection,
                events,
                configurationManager,
            )
        viewModel.performSearch("test") // Set a search state
        viewModel.clearSearch()
        assertWithMessage("Search state is not Inactive")
            .that(SearchState.Inactive)
            .isEqualTo(viewModel.searchState.value)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun performSearch_withSuggestion_searchStateIsActiveSuggestion() = runTest {
        provideSelectionEvents(this.backgroundScope)
        val configurationManager =
            ConfigurationManager(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                scope = this.backgroundScope,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                deviceConfigProxy,
                generatePickerSessionId(),
            )
        val viewModel =
            SearchViewModel(
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                TestSearchDataServiceImpl(),
                selection,
                events,
                configurationManager,
            )
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
        provideSelectionEvents(this.backgroundScope)
        val configurationManager =
            ConfigurationManager(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                scope = this.backgroundScope,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                deviceConfigProxy,
                generatePickerSessionId(),
            )
        val viewModel =
            SearchViewModel(
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                TestSearchDataServiceImpl(),
                selection,
                events,
                configurationManager,
            )
        val query = "test query"
        viewModel.performSearch(query)
        assertWithMessage("Search state is not Active for query search")
            .that(SearchState.Active.QuerySearch(query))
            .isEqualTo(viewModel.searchState.value)
    }

    private fun provideSelectionEvents(scope: CoroutineScope) {
        selection =
            SelectionImpl<Media>(
                scope = scope,
                configuration = provideTestConfigurationFlow(scope = scope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        val featureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = scope),
                scope = scope,
                prefetchDataService = TestPrefetchDataService(),
            )

        events =
            Events(
                scope = scope,
                provideTestConfigurationFlow(scope = scope),
                featureManager = featureManager,
            )
    }
}
