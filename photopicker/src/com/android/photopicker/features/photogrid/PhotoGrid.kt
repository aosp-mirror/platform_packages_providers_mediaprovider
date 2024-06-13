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

package com.android.photopicker.features.photogrid

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.navigation.PhotopickerDestinations.PHOTO_GRID
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.extensions.navigateToPhotoGrid
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.features.albumgrid.AlbumGridFeature
import com.android.photopicker.features.navigationbar.NavigationBarButton
import com.android.photopicker.features.preview.PreviewFeature

/**
 * Primary composable for drawing the main PhotoGrid on [PhotopickerDestinations.PHOTO_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 */
@Composable
fun PhotoGrid(viewModel: PhotoGridViewModel = obtainViewModel()) {
    val navController = LocalNavController.current
    val items = viewModel.data.collectAsLazyPagingItems()
    val featureManager = LocalFeatureManager.current
    val isPreviewEnabled = remember { featureManager.isFeatureEnabled(PreviewFeature::class.java) }

    val state = rememberLazyGridState()

    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    /* Use the expanded layout any time the Width is Medium or larger. */
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val selectionLimitExceededMessage =
        stringResource(R.string.photopicker_selection_limit_exceeded_snackbar, selectionLimit)

    Column(
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        // This may need some additional fine tuning by looking at a certain
                        // distance in dragAmount, but initial testing suggested this worked
                        // pretty well as is.
                        if (dragAmount < 0) {
                            // Negative is a left swipe
                            if (featureManager.isFeatureEnabled(AlbumGridFeature::class.java))
                                navController.navigateToAlbumGrid()
                        }
                    }
                )
            }
    ) {
        mediaGrid(
            items = items,
            isExpandedScreen = isExpandedScreen,
            selection = selection,
            onItemClick = { item ->
                if (item is MediaGridItem.MediaItem) {
                    viewModel.handleGridItemSelection(
                        item = item.media,
                        selectionLimitExceededMessage = selectionLimitExceededMessage
                    )
                }
            },
            onItemLongPress = { item ->
                // If the [PreviewFeature] is enabled, launch the preview route.
                if (isPreviewEnabled) {
                    if (item is MediaGridItem.MediaItem)
                        navController.navigateToPreviewMedia(item.media)
                }
            },
            state = state,
        )
    }
}

/**
 * The navigation button for the main photo grid. Composable for
 * [Location.NAVIGATION_BAR_NAV_BUTTON]
 */
@Composable
fun PhotoGridNavButton(modifier: Modifier) {
    val navController = LocalNavController.current

    NavigationBarButton(
        onClick = navController::navigateToPhotoGrid,
        modifier = modifier,
        isCurrentRoute = { route -> route == PHOTO_GRID.route },
    ) {
        Text(stringResource(R.string.photopicker_photos_nav_button_label))
    }
}
