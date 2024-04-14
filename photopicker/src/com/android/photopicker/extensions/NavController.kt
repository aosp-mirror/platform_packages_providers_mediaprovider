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

package com.android.photopicker.extensions

import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.android.photopicker.core.navigation.PhotopickerDestinations.PHOTO_GRID

/**
 * Utility function for navigating to the [PhotopickerDestinations.PHOTO_GRID] route.
 *
 * This attempts to reclaim an existing BackStack entry, preserving any previous state that
 * existed.
 *
 * If the route is not currently on the BackStack, then this will navigate directly.
 */
fun NavController.navigateToPhotoGrid(navOptions: NavOptions? = null) {

    // First, check to see if the destination is already the current route.
    if (this.currentDestination?.route == PHOTO_GRID.route) {
        // Nothing to do. Return early to prevent navigation animations from triggering.
        return
    } else if (
        // Try to return to the entry that is already on the backstack, so the user's
        // previous state and scroll position is restored.
        !this.popBackStack(
            PHOTO_GRID.route,
            /* inclusive= */ false,
            /* saveState = */ false,
        )
    ) {

        // Last resort; PHOTO_GRID isn't on the backstack, then navigate directly.
        this.navigate(PHOTO_GRID.route, navOptions)
    }
}
