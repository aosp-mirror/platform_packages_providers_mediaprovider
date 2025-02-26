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

import android.provider.CloudMediaProviderContract

/**
 * This enum holds all valid values of a search suggestion type. Search suggestion types could be
 * decided by the cloud media provider or by the photo picker backend.
 */
enum class SearchSuggestionType(val key: String) {
    TEXT(CloudMediaProviderContract.SEARCH_SUGGESTION_TEXT),
    FACE(CloudMediaProviderContract.SEARCH_SUGGESTION_FACE),
    LOCATION(CloudMediaProviderContract.SEARCH_SUGGESTION_LOCATION),
    DATE(CloudMediaProviderContract.SEARCH_SUGGESTION_DATE),
    /* Suggestion saved in history. History suggestions could be based on searches that are
     * triggered by selecting a CMP suggestion or by entering search text. */
    HISTORY(CloudMediaProviderContract.SEARCH_SUGGESTION_HISTORY),
    /* Suggestion for the Screenshots album */
    SCREENSHOTS_ALBUM(CloudMediaProviderContract.SEARCH_SUGGESTION_SCREENSHOTS_ALBUM),
    /* Suggestion for the Favorites album */
    FAVORITES_ALBUM(CloudMediaProviderContract.SEARCH_SUGGESTION_FAVORITES_ALBUM),
    /* Suggestion for the Videos album */
    VIDEOS_ALBUM(CloudMediaProviderContract.SEARCH_SUGGESTION_VIDEOS_ALBUM),
    /* All other albums */
    ALBUM(CloudMediaProviderContract.SEARCH_SUGGESTION_ALBUM),
}

/** A map of all Key -> [SearchSuggestionType] available. */
val KeyToSearchSuggestionType: Map<String, SearchSuggestionType> =
    SearchSuggestionType.entries.associateBy { enum -> enum.key }
