/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.categorygrid

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.extensions.navigateToAlbumMediaGridForCategories
import com.android.photopicker.extensions.navigateToCategoryGrid
import com.android.photopicker.extensions.navigateToMediaSetGrid
import com.android.photopicker.extensions.navigateToPhotoGrid
import com.android.photopicker.features.navigationbar.NavigationBarButton
import com.android.photopicker.features.photogrid.PhotoGridFeature
import com.android.photopicker.features.search.SearchFeature
import kotlinx.coroutines.launch

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW_FOR_CATEGORY_GRID = 2

/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED_FOR_CATEGORY_GRID = 3

/** The amount of padding to use around each cell in the categories grid. */
private val MEASUREMENT_HORIZONTAL_CELL_SPACING_CATEGORY_GRID = 16.dp

/**
 * Primary composable for drawing the main Category Grid on [PhotopickerDestinations.ALBUM_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 */
@Composable
fun CategoryGrid(viewModel: CategoryGridViewModel = obtainViewModel()) {
    val items = viewModel.getCategoriesAndAlbums().collectAsLazyPagingItems()
    val state = rememberLazyGridState()
    val navController = LocalNavController.current
    val featureManager = LocalFeatureManager.current
    val configuration = LocalPhotopickerConfiguration.current
    val events = LocalEvents.current
    val scope = rememberCoroutineScope()

    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val isExpanded = LocalEmbeddedState.current?.isExpanded ?: false

    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val previouslySelectedItem by viewModel.previouslySelectedItem.collectAsStateWithLifecycle()
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
                            if (featureManager.isFeatureEnabled(PhotoGridFeature::class.java)) {
                                navController.navigateToPhotoGrid()
                                // Dispatch UI event to indicate switching to photos tab
                                scope.launch {
                                    events.dispatch(
                                        Event.LogPhotopickerUIEvent(
                                            FeatureToken.CATEGORY_GRID.token,
                                            configuration.sessionId,
                                            configuration.callingPackageUid ?: -1,
                                            Telemetry.UiEvent.SWITCH_PICKER_TAB,
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }
    ) {
        // Invoke the composable for Category Grid. OnClick uses the navController to navigate to
        // the category content for the category that is selected by the user.
        mediaGrid(
            items = items,
            focusItem = previouslySelectedItem,
            onItemClick = { item ->
                if (item is MediaGridItem.AlbumItem) {
                    // Dispatch events to log album related details
                    scope.launch {
                        events.dispatch(
                            Event.LogPhotopickerAlbumOpenedUIEvent(
                                FeatureToken.CATEGORY_GRID.token,
                                configuration.sessionId,
                                configuration.callingPackageUid ?: -1,
                                item.album,
                            )
                        )
                        events.dispatch(
                            Event.LogPhotopickerUIEvent(
                                FeatureToken.CATEGORY_GRID.token,
                                configuration.sessionId,
                                configuration.callingPackageUid ?: -1,
                                Telemetry.UiEvent.PICKER_ALBUMS_INTERACTION,
                            )
                        )
                    }
                    viewModel.setPreviouslySelectedItem(item)
                    navController.navigateToAlbumMediaGridForCategories(album = item.album)
                } else if (item is MediaGridItem.CategoryItem) {
                    scope.launch {
                        events.dispatch(
                            Event.LogPhotopickerUIEvent(
                                FeatureToken.CATEGORY_GRID.token,
                                configuration.sessionId,
                                configuration.callingPackageUid ?: -1,
                                Telemetry.UiEvent.CATEGORY_MEDIA_SETS_OPEN,
                            )
                        )
                    }
                    viewModel.setPreviouslySelectedItem(item)
                    navController.navigateToMediaSetGrid(category = item.category)
                }
            },
            isExpandedScreen = isExpandedScreen,
            columns =
                when (isExpandedScreen) {
                    true -> GridCells.Fixed(CELLS_PER_ROW_EXPANDED_FOR_CATEGORY_GRID)
                    false -> GridCells.Fixed(CELLS_PER_ROW_FOR_CATEGORY_GRID)
                },
            selection = emptySet(),
            gridCellPadding = MEASUREMENT_HORIZONTAL_CELL_SPACING_CATEGORY_GRID,
            contentPadding = PaddingValues(MEASUREMENT_HORIZONTAL_CELL_SPACING_CATEGORY_GRID),
            state = state,
        )
        LaunchedEffect(Unit) {
            // Dispatch UI event to denote loading of media categories and albums
            scope.launch {
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.CATEGORY_GRID.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.UI_LOADED_CATEGORIES_AND_ALBUMS,
                    )
                )
            }
        }
    }
}

/**
 * The navigation button for the main category grid. Composable for
 * [Location.NAVIGATION_BAR_NAV_BUTTON]
 */
@Composable
fun CategoryButton(modifier: Modifier) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val sessionId = LocalPhotopickerConfiguration.current.sessionId
    val packageUid = LocalPhotopickerConfiguration.current.callingPackageUid ?: -1
    val featureManager = LocalFeatureManager.current
    val searchFeatureEnabled = featureManager.isFeatureEnabled(SearchFeature::class.java)

    NavigationBarButton(
        onClick = {
            // Dispatch UI event to denote switching to category tab
            scope.launch {
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.CATEGORY_GRID.token,
                        sessionId,
                        packageUid,
                        Telemetry.UiEvent.SWITCH_PICKER_TAB,
                    )
                )
            }
            navController.navigateToCategoryGrid()
        },
        modifier = modifier,
        isCurrentRoute = { route -> route == PhotopickerDestinations.ALBUM_GRID.route },
    ) {
        when {
            searchFeatureEnabled -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector =
                            ImageVector.vectorResource(R.drawable.photopicker_category_icon),
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.photopicker_categories_nav_button_label),
                        maxLines = 1, // Limit the text to a single line
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            else ->
                Text(
                    stringResource(R.string.photopicker_categories_nav_button_label),
                    maxLines = 1, // Limit the text to a single line
                    overflow = TextOverflow.Ellipsis,
                )
        }
    }
}
