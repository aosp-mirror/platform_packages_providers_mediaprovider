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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.Background
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * ViewModel for handling search functionality.
 *
 * This ViewModel manages the search state, including the search term, suggestions, and whether a
 * search was triggered by a query or a suggestion.
 *
 * @param scopeOverride An optional CoroutineScope to be used instead of the default viewModelScope.
 * @param backgroundDispatcher A CoroutineDispatcher for running background tasks. This dispatcher
 *   is marked as `internal` and can be accessed by other classes within the same module.
 * @param searchDataService The service for fetching search suggestions.
 */
@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    @Background val backgroundDispatcher: CoroutineDispatcher,
    private val searchDataService: SearchDataService,
) : ViewModel() {

    companion object {
        const val HISTORY_SUGGESTION_MAX_LIMIT = 3
        const val FACE_SUGGESTION_MAX_LIMIT = 6
        const val ALL_SUGGESTION_MAX_LIMIT = 6
    }

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope = scopeOverride ?: this.viewModelScope

    private var searchJob: SearchJob? = null

    /**
     * Represents the current state of the search.
     *
     * It can be one of the following:
     * - **Inactive:** The initial state where no search is active.
     * - **Active.QuerySearch:** A search is active with a user-entered query.
     * - **Active.SuggestionSearch:** A search is active using a selected suggestion.
     */
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Inactive)
    val searchState: StateFlow<SearchState> = _searchState

    /**
     * Holds the current state of search suggestions list.
     *
     * This `StateFlow` emits updates whenever the list of search suggestions changes. It provides
     * various types of suggestions (e.g., history, face, others).
     */
    private val _suggestionLists = MutableStateFlow(SuggestionLists())
    val suggestionLists: StateFlow<SuggestionLists> = _suggestionLists

    private val suggestionCache = SearchSuggestionCache()

    init {
        fetchSuggestions("")
    }

    /**
     * Updates the search term and fetches new suggestions.
     *
     * This function cancels any current search job and starts search as a new job.
     *
     * @param query The new search term.
     */
    fun fetchSuggestions(query: String) {
        searchJob?.cancel()

        val cachedSuggestion: List<SearchSuggestion>? = suggestionCache.getSuggestions(query)
        when (cachedSuggestion != null) {
            true -> {
                getSuggestionTypeLists(cachedSuggestion, query.isEmpty())
            }

            else -> {
                searchJob = SearchJob(scope)
                searchJob?.let { job ->
                    job.startSearch() {
                        withContext(backgroundDispatcher) {
                            val newSuggestions =
                                searchDataService.getSearchSuggestions(
                                    query,
                                    cancellationSignal = job.cancellationSignal,
                                )
                            val refactoredSuggestionList: List<SearchSuggestion> =
                                getSuggestionTypeLists(newSuggestions, query.isEmpty())
                            suggestionCache.addSuggestions(query, refactoredSuggestionList)
                        }
                    }
                }
            }
        }
    }

    /** Sets Inactive search state where no search result is active */
    fun clearSearch() {
        _searchState.value = SearchState.Inactive
    }

    /**
     * Initiates a search based on a selected search suggestion.
     *
     * @param suggestion The `SearchSuggestion` selected by the user.
     */
    fun performSearch(suggestion: SearchSuggestion) {
        _searchState.value = SearchState.Active.SuggestionSearch(suggestion)
    }

    /**
     * Initiates a search based on a user-provided query string.
     *
     * @param query The search query entered by the user.
     */
    fun performSearch(query: String) {
        _searchState.value = SearchState.Active.QuerySearch(query)
    }

    /**
     * Method that updates the list for each type of suggestion from the suggestions result and
     * returns a trimmed list of search suggestions to show on UI
     *
     * @param suggestions The original list of `SearchSuggestion` objects.
     * @param isZeroSearchState A boolean value indicating if the search query is empty.
     */
    private fun getSuggestionTypeLists(
        suggestions: List<SearchSuggestion>,
        isZeroSearchState: Boolean,
    ): List<SearchSuggestion> {
        val history = mutableListOf<SearchSuggestion>()
        val face = mutableListOf<SearchSuggestion>()
        val other = mutableListOf<SearchSuggestion>()
        val result = mutableListOf<SearchSuggestion>()
        var (historyCount, faceCount, otherCount) = listOf(0, 0, 0)

        for (suggestion in suggestions) {
            when (suggestion.type) {
                SearchSuggestionType.HISTORY ->
                    if (historyCount++ < HISTORY_SUGGESTION_MAX_LIMIT) {
                        history.add(suggestion)
                        result.add(suggestion)
                    }
                SearchSuggestionType.FACE ->
                    if (isZeroSearchState) {
                        if (faceCount++ < FACE_SUGGESTION_MAX_LIMIT) {
                            face.add(suggestion)
                            result.add(suggestion)
                        }
                    } else {
                        if (otherCount++ < ALL_SUGGESTION_MAX_LIMIT) {
                            other.add(suggestion)
                            result.add(suggestion)
                        }
                    }
                else ->
                    if (otherCount++ < ALL_SUGGESTION_MAX_LIMIT) {
                        other.add(suggestion)
                        result.add(suggestion)
                    }
            }
            if (
                historyCount >= HISTORY_SUGGESTION_MAX_LIMIT &&
                    faceCount >= FACE_SUGGESTION_MAX_LIMIT &&
                    otherCount >= ALL_SUGGESTION_MAX_LIMIT
            )
                break // Early exit
        }
        _suggestionLists.value = SuggestionLists(history, face, other)
        return result
    }
}

/** Represents the different states of the search functionality. */
sealed class SearchState {
    object Inactive : SearchState()

    sealed class Active : SearchState() {
        data class QuerySearch(val query: String) : Active()

        data class SuggestionSearch(val suggestion: SearchSuggestion) : Active()
    }
}
