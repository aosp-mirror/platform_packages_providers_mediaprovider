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

/** Holds search state info for all user profiles on the device. */
data class GlobalSearchStateInfo(
    // Map of all available profiles to the provider authorities that have search feature
    // enabled. If no providers have search enabled, tha value in map should be an empty string.
    // If the information is unknown for a given profile, the value in map should be null.
    val providersWithSearchEnabled: Map<Int, List<String>?>,
    val currentUserId: Int,
) {
    val state: GlobalSearchState =
        when {
            // Check if search is enabled in current profile
            providersWithSearchEnabled[currentUserId]?.isNotEmpty() ?: false ->
                GlobalSearchState.ENABLED

            // Check if search is enabled in any other profile
            providersWithSearchEnabled.values.any { providers ->
                providers?.isNotEmpty() ?: false
            } -> GlobalSearchState.ENABLED_IN_OTHER_PROFILES_ONLY

            // Check if there is missing information
            providersWithSearchEnabled.values.any { providers -> providers == null } ->
                GlobalSearchState.UNKNOWN

            // If we have all information and search is not enabled in any profile,
            // search is disabled.
            else -> GlobalSearchState.DISABLED
        }
}
