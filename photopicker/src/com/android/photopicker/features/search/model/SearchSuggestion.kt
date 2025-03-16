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

import android.net.Uri
import com.android.photopicker.util.hashCodeOf

/**
 * A data class that holds a Search Suggestion. Search suggestions could be suggestions shown to the
 * user in zero state (when the user has not typed anything) or the suggestions shown to the user as
 * they type a search text.
 */
data class SearchSuggestion(
    /* Unique identifier for the search suggestions in cloud media providers. Not all suggestions
     * have a unique media set id, for instance, history suggestions might be search text
     * suggestions that don't have a media set ID. */
    val mediaSetId: String?,
    /* The authority of the source ContentProvider that provided this Search Suggestion. */
    val authority: String?,
    /* Display text could be null sometimes for instance, if the suggestion type is a face */
    val displayText: String?,
    val type: SearchSuggestionType,
    /* Unwrapped URI of the icon shown to the user along with the suggestion. If this is null,
    please fallback to default icons based on the [SearchSuggestionType] of the suggestion. */
    val iconUri: Uri?,
) {
    init {
        require(type != SearchSuggestionType.FACE || iconUri != null) {
            "Icon cannot be null for FACE type search suggestion"
        }
        require(type == SearchSuggestionType.FACE || displayText != null) {
            "Display text cannot be null except for FACE type search suggestion"
        }
    }

    override fun hashCode(): Int = hashCodeOf(mediaSetId, authority, displayText, type)

    override fun equals(other: Any?): Boolean {
        return other is SearchSuggestion &&
            other.mediaSetId == mediaSetId &&
            other.authority == authority &&
            other.displayText == displayText &&
            other.type == type
    }
}
