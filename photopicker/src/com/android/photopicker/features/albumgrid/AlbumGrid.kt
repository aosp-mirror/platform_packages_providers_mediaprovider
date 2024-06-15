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

package com.android.photopicker.features.albumgrid

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.extensions.navigateToAlbumMediaGrid
import com.android.photopicker.extensions.navigateToPhotoGrid
import com.android.photopicker.features.navigationbar.NavigationBarButton
import com.android.photopicker.features.photogrid.PhotoGridFeature

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW_FOR_ALBUM_GRID = 2

/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED_FOR_ALBUM_GRID = 3

/** The amount of padding to use around each cell in the albums grid. */
private val MEASUREMENT_HORIZONTAL_CELL_SPACING_ALBUM_GRID = 20.dp

/**
 * Primary composable for drawing the main AlbumGrid on [PhotopickerDestinations.ALBUM_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 */
@Composable
fun AlbumGrid(viewModel: AlbumGridViewModel = obtainViewModel()) {
    val items = viewModel.getAlbums().collectAsLazyPagingItems()
    val state = rememberLazyGridState()
    val navController = LocalNavController.current
    val featureManager = LocalFeatureManager.current

    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    Column(
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        // This may need some additional fine tuning by looking at a certain
                        // distance in dragAmount, but initial testing suggested this worked
                        // pretty well as is.
                        if (dragAmount > 0) {
                            // Positive is a right swipe
                            if (featureManager.isFeatureEnabled(PhotoGridFeature::class.java))
                                navController.navigateToPhotoGrid()
                        }
                    }
                )
            }
    ) {
        // Invoke the composable for AlbumsGrid. OnClick uses the navController to navigate to
        // the album content for the album that is selected by the user.
        mediaGrid(
            items = items,
            onItemClick = { item ->
                if (item is MediaGridItem.AlbumItem)
                    navController.navigateToAlbumMediaGrid(album = item.album)
            },
            isExpandedScreen = isExpandedScreen,
            columns =
                when (isExpandedScreen) {
                    true -> GridCells.Fixed(CELLS_PER_ROW_EXPANDED_FOR_ALBUM_GRID)
                    false -> GridCells.Fixed(CELLS_PER_ROW_FOR_ALBUM_GRID)
                },
            selection = emptySet(),
            gridCellPadding = MEASUREMENT_HORIZONTAL_CELL_SPACING_ALBUM_GRID,
            contentPadding = PaddingValues(MEASUREMENT_HORIZONTAL_CELL_SPACING_ALBUM_GRID),
            state = state,
        )
    }
}

/**
 * The navigation button for the main photo grid. Composable for
 * [Location.NAVIGATION_BAR_NAV_BUTTON]
 */
@Composable
fun AlbumGridNavButton(modifier: Modifier) {
    val navController = LocalNavController.current

    NavigationBarButton(
        onClick = navController::navigateToAlbumGrid,
        modifier = modifier,
        isCurrentRoute = { route -> route == PhotopickerDestinations.ALBUM_GRID.route },
    ) {
        Text(stringResource(R.string.photopicker_albums_nav_button_label))
    }
}
