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

import com.android.photopicker.features.search.model.SearchSuggestion

/**
 * Class that implements cache for SearchSuggestions that stores a list of suggestions for a given
 * prefix
 */
class SearchSuggestionCache {
    private var cacheSuggestion: HashMap<String, List<SearchSuggestion>> = java.util.HashMap()

    /**
     * Retrieves suggestions for a given prefix from the cache.
     *
     * @param query The prefix to search for.
     * @return A list of suggestions for the prefix, or null if no suggestions are found.
     */
    fun getSuggestions(query: String): List<SearchSuggestion>? {
        return cacheSuggestion.get(query)
    }

    /**
     * Adds suggestions for a given prefix to the cache.
     *
     * @param query The prefix to add suggestions for.
     * @param suggestions The list of suggestions to add.
     */
    fun addSuggestions(query: String, suggestions: List<SearchSuggestion>) {
        cacheSuggestion.put(query, suggestions)
    }
}
