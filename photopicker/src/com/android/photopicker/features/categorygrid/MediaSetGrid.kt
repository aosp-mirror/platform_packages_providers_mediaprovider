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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.components.EmptyState
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Group
import com.android.photopicker.extensions.navigateToMediaSetContentGrid
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW_FOR_MEDIASET_GRID = 3
/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED_FOR_MEDIASET_GRID = 4
/** The amount of padding to use around each cell in the mediaset grid. */
private val MEASUREMENT_HORIZONTAL_CELL_SPACING_MEDIASET_GRID = 1.dp

/**
 * Primary composable for drawing the main MediasetGrid on [PhotopickerDestinations.MEDIASET_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 */
@Composable
fun MediaSetGrid(
    flow: StateFlow<Group.Category?>,
    viewModel: CategoryGridViewModel = obtainViewModel(),
) {
    val categoryState by flow.collectAsStateWithLifecycle(initialValue = null)
    val category = categoryState
    when (category) {
        null -> {}
        else -> {
            val items = remember(category) { viewModel.getMediaSets(category) }
            val state = rememberLazyGridState()
            val navController = LocalNavController.current
            val scope = rememberCoroutineScope()
            val events = LocalEvents.current
            val configuration = LocalPhotopickerConfiguration.current

            val isEmbedded =
                LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
            val host = LocalEmbeddedState.current?.host
            // Use the expanded layout any time the Width is Medium or larger.
            val isExpandedScreen: Boolean =
                when (LocalWindowSizeClass.current.widthSizeClass) {
                    WindowWidthSizeClass.Medium -> true
                    WindowWidthSizeClass.Expanded -> true
                    else -> false
                }
            val mediaSetItems = items.collectAsLazyPagingItems()
            Column(modifier = Modifier.fillMaxSize()) {
                val isEmptyAndNoMorePages =
                    mediaSetItems.itemCount == 0 &&
                        mediaSetItems.loadState.source.append is LoadState.NotLoading &&
                        mediaSetItems.loadState.source.append.endOfPaginationReached
                when {
                    isEmptyAndNoMorePages -> {
                        val localConfig = LocalConfiguration.current
                        val emptyStatePadding =
                            remember(localConfig) { (localConfig.screenHeightDp * .20).dp }
                        val (title, body, icon) =
                            getEmptyStateContentForMediaset(category.categoryType)
                        EmptyState(
                            modifier =
                                if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                                    // In embedded no need to give extra top padding to make empty
                                    // state title and body clearly visible in collapse mode (small
                                    // view)
                                    Modifier.fillMaxWidth()
                                } else {
                                    // Provide 20% of screen height as empty space above
                                    Modifier.fillMaxWidth().padding(top = emptyStatePadding)
                                },
                            icon = icon,
                            title = title,
                            body = body,
                        )
                        LaunchedEffect(Unit) {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.CATEGORY_GRID.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.UI_LOADED_EMPTY_STATE,
                                )
                            )
                        }
                    }

                    else -> {
                        // Invoke the composable for MediasetGrid. OnClick uses the navController to
                        // navigate to the mediaset content for the mediaset that is selected by the
                        // user.
                        mediaGrid(
                            items = mediaSetItems,
                            onItemClick = { item ->
                                if (item is MediaGridItem.PersonMediaSetItem) {
                                    scope.launch {
                                        events.dispatch(
                                            Event.LogPhotopickerUIEvent(
                                                FeatureToken.CATEGORY_GRID.token,
                                                configuration.sessionId,
                                                configuration.callingPackageUid ?: -1,
                                                Telemetry.UiEvent.CATEGORY_PEOPLEPET_OPEN,
                                            )
                                        )
                                    }
                                    navController.navigateToMediaSetContentGrid(
                                        mediaSet = item.mediaSet
                                    )
                                } else if (item is MediaGridItem.MediaSetItem) {
                                    scope.launch {
                                        events.dispatch(
                                            Event.LogPhotopickerUIEvent(
                                                FeatureToken.CATEGORY_GRID.token,
                                                configuration.sessionId,
                                                configuration.callingPackageUid ?: -1,
                                                Telemetry.UiEvent.PICKER_CATEGORIES_INTERACTION,
                                            )
                                        )
                                    }
                                    navController.navigateToMediaSetContentGrid(
                                        mediaSet = item.mediaSet
                                    )
                                }
                            },
                            isExpandedScreen = isExpandedScreen,
                            columns =
                                when (isExpandedScreen) {
                                    true ->
                                        GridCells.Fixed(CELLS_PER_ROW_EXPANDED_FOR_MEDIASET_GRID)
                                    false -> GridCells.Fixed(CELLS_PER_ROW_FOR_MEDIASET_GRID)
                                },
                            selection = emptySet(),
                            gridCellPadding = MEASUREMENT_HORIZONTAL_CELL_SPACING_MEDIASET_GRID,
                            contentPadding =
                                PaddingValues(MEASUREMENT_HORIZONTAL_CELL_SPACING_MEDIASET_GRID),
                            state = state,
                        )
                        LaunchedEffect(Unit) {
                            // Dispatch UI event to log loading of category contents
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.CATEGORY_GRID.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.UI_LOADED_MEDIA_SETS,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Return a generic content for the empty state.
 *
 * @return a [Triple] that contains the [Title, Body, Icon] for the empty state.
 */
@Composable
private fun getEmptyStateContentForMediaset(
    categoryType: CategoryType
): Triple<String, String, ImageVector> {
    return if (categoryType == CategoryType.PEOPLE_AND_PETS) {
        Triple(
            stringResource(R.string.photopicker_people_category_empty_state_title),
            stringResource(R.string.photopicker_people_category_empty_state_body),
            Icons.Outlined.Group,
        )
    } else {
        Triple(
            stringResource(R.string.photopicker_photos_empty_state_title),
            stringResource(R.string.photopicker_photos_empty_state_body),
            Icons.Outlined.Group,
        )
    }
}
