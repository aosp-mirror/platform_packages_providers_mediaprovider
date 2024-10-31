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
 * Data class that holds different types of search suggestions.
 *
 * This class encapsulates lists for history suggestions, face suggestions, and other suggestions.
 * It also provides a calculated property to get the total number of suggestions.
 *
 * @property history List of history they search suggestions.
 * @property face List of face type search suggestions.
 * @property other List of other types of search suggestions.
 */
data class SuggestionLists(
    val history: List<SearchSuggestion> = emptyList(),
    val face: List<SearchSuggestion> = emptyList(),
    val other: List<SearchSuggestion> = emptyList(),
) {
    val totalSuggestions: Int = history.size + face.size + other.size
}
