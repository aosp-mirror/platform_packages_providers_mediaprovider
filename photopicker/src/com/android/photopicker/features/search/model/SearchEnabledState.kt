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

/** This represents the search enabled states the current profile could have. */
enum class SearchEnabledState {
    /* Search is enabled for the current profile */
    ENABLED,
    /* Search is disabled in the current profile but enabled in other profiles */
    ENABLED_IN_OTHER_PROFILES_ONLY,
    /* Search is disabled in all profiles */
    DISABLED,
    /* Either the state of the current profile is unknown, or the current profile has search
     * disabled and the state of other profile(s) is unknown. */
    UNKNOWN,
}
