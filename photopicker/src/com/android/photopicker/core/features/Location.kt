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

package com.android.photopicker.core.features

/**
 * Enumation class of all UI Locations in Photopicker. These are the locations that are usable by
 * [PhotopickerUiFeature]s to register to compose UI.
 *
 * See the [FeatureManager#composeLocation] API to request composition of a location by the
 * currently active list of features.
 *
 * Features may add additional locations to this enumeration group to expose feature specific
 * locations to allow other features to compose into their UI tree.
 *
 * Note: When adding new locations, please group them by feature area, alphabetically.
 */
enum class Location {

    // keep-sorted start
    COMPOSE_TOP, // UI feature entrypoint at the top of the compose UI tree.
    MEDIA_PREPARER, // Where the MEDIA_PREPARER is attached to the compose tree.
    NAVIGATION_BAR, // Where the navigation bar should be drawn (when it is active).
    NAVIGATION_BAR_NAV_BUTTON, // Where the navigation bar draws navigation buttons.
    OVERFLOW_MENU, // The overflow menu anchor
    OVERFLOW_MENU_ITEMS, // Options inside the overflow menu
    PROFILE_SELECTOR, // Where the profile switcher button is drawn
    SEARCH_BAR, // Where the search bar would be drawn
    SELECTION_BAR, // Where the selection bar should be drawn (when it is active).
    SELECTION_BAR_SECONDARY_ACTION, // Where the extra button is drawn on the selection bar.
    SNACK_BAR, // Where the [Event.ShowSnackbarMessage] toasts will appear.
    // keep-sorted end
}
