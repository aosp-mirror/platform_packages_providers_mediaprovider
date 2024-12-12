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

package com.android.photopicker.features.search.model

import com.android.photopicker.util.hashCodeOf

sealed interface SearchRequest {
    data class SearchTextRequest(val searchText: String) : SearchRequest {
        override fun hashCode(): Int = hashCodeOf(searchText)

        override fun equals(other: Any?): Boolean {
            return other is SearchTextRequest && other.searchText == searchText
        }
    }

    data class SearchSuggestionRequest(val suggestion: SearchSuggestion) : SearchRequest {
        override fun hashCode(): Int = hashCodeOf(suggestion)

        override fun equals(other: Any?): Boolean {
            return other is SearchSuggestionRequest && other.suggestion == suggestion
        }
    }
}
