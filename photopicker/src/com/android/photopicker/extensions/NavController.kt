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
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.navigation.PhotopickerDestinations.PHOTO_GRID
import com.android.photopicker.core.navigation.PhotopickerDestinations.PREVIEW_MEDIA
import com.android.photopicker.core.navigation.PhotopickerDestinations.PREVIEW_SELECTION
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.albumgrid.AlbumGridFeature
import com.android.photopicker.features.preview.PreviewFeature

/**
 * Utility function for navigating to the [PhotopickerDestinations.PHOTO_GRID] route.
 *
 * This attempts to reclaim an existing BackStack entry, preserving any previous state that existed.
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

/** Utility function for navigating to the [PhotopickerDestinations.PREVIEW_SELECTION] route. */
fun NavController.navigateToPreviewSelection(navOptions: NavOptions? = null) {
    this.navigate(PREVIEW_SELECTION.route, navOptions)
}

/**
 * Utility function for navigating to the [PhotopickerDestinations.PREVIEW_MEDIA] route.
 *
 * Additionally, this adds the relevant media data to the BackStackEntry for the route to use to
 * avoid refetching it from the provider.
 *
 * @param media The media item that should be previewed in full resolution.
 */
fun NavController.navigateToPreviewMedia(
    media: Media,
    navOptions: NavOptions? = null,
) {
    this.navigate(PREVIEW_MEDIA.route, navOptions)
    // Media object must be parcellized and passed to the new route so it can be loaded.
    // This back stack entry is guaranteed to exist since it was just navigated to.
    this.getBackStackEntry(PREVIEW_MEDIA.route)
        .savedStateHandle
        .set(PreviewFeature.PREVIEW_MEDIA_KEY, media)
}

/**
 * Utility function for navigating to the [PhotopickerDestinations.ALBUM_GRID] route.
 *
 * This attempts to reclaim an existing BackStack entry, preserving any previous state that existed.
 *
 * If the route is not currently on the BackStack, then this will navigate directly.
 */
fun NavController.navigateToAlbumGrid(navOptions: NavOptions? = null) {
    // First, check to see if the destination is already the current route.
    if (this.currentDestination?.route == PhotopickerDestinations.ALBUM_GRID.route) {
        // Nothing to do. Return early to prevent navigation animations from triggering.
        return
    } else if (
    // Try to return to the entry that is already on the backstack, so the user's
    // previous state and scroll position is restored.
        !this.popBackStack(
            PhotopickerDestinations.ALBUM_GRID.route,
            /* inclusive= */ false,
            /* saveState = */ true,
        )
    ) {
        // Last resort; ALBUM_GRID isn't on the backstack, then navigate directly.
        this.navigate(PhotopickerDestinations.ALBUM_GRID.route, navOptions)
    }
}

/**
 * Utility function for navigating to the [PhotopickerDestinations.ALBUM_MEDIA_GRID] route.
 *
 * @param album The album for which the media needs to be displayed.
 */
fun NavController.navigateToAlbumMediaGrid(
    navOptions: NavOptions? = null,
    album: Group.Album,
) {
    this.navigate(PhotopickerDestinations.ALBUM_MEDIA_GRID.route, navOptions)

    // Album object must be parcellized and passed to the new route so it can be loaded.
    // This back stack entry is guaranteed to exist since it was just navigated to.
    this.getBackStackEntry(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)
        .savedStateHandle
        .set(AlbumGridFeature.ALBUM_KEY, album)
}
