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

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.StateSelector
import com.android.photopicker.core.animations.standardDecelerate
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.components.EmptyState
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.getCellsPerRow
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.hideWhenState
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.navigation.PhotopickerDestinations.PHOTO_GRID
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.extensions.navigateToCategoryGrid
import com.android.photopicker.extensions.navigateToPhotoGrid
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.extensions.transferTouchesToHostInEmbedded
import com.android.photopicker.features.albumgrid.AlbumGridFeature
import com.android.photopicker.features.categorygrid.CategoryGridFeature
import com.android.photopicker.features.navigationbar.NavigationBarButton
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.util.LocalLocalizationHelper
import kotlinx.coroutines.launch

private val MEASUREMENT_BANNER_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp)

// This is the number of rows we should include in the recents section at the top of the Photo Grid.
// The recents section does not contain any separators.
private val RECENTS_ROW_COUNT = 3

/**
 * Primary composable for drawing the main PhotoGrid on [PhotopickerDestinations.PHOTO_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 */
@Composable
fun PhotoGrid(viewModel: PhotoGridViewModel = obtainViewModel()) {
    val navController = LocalNavController.current
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

    val cellsPerRow = remember(isExpandedScreen) { getCellsPerRow(isExpandedScreen) }

    val items =
        viewModel
            .getData(/* recentsCellCount */ (cellsPerRow * RECENTS_ROW_COUNT))
            .collectAsLazyPagingItems()

    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val localizedSelectionLimit = LocalLocalizationHelper.current.getLocalizedCount(selectionLimit)

    val selectionLimitExceededMessage =
        stringResource(
            R.string.photopicker_selection_limit_exceeded_snackbar,
            localizedSelectionLimit,
        )

    val events = LocalEvents.current
    val scope = rememberCoroutineScope()
    val configuration = LocalPhotopickerConfiguration.current

    // Modifier applied when photo grid to album grid navigation is disabled
    val baseModifier = Modifier.fillMaxSize()
    // Modifier applied when photo grid to album grid navigation is enabled
    val modifierWithNavigation =
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, dragAmount ->
                    // This may need some additional fine tuning by looking at a certain
                    // distance in dragAmount, but initial testing suggested this worked
                    // pretty well as is.
                    if (dragAmount < 0) {
                        // Negative is a left swipe
                        if (featureManager.isFeatureEnabled(AlbumGridFeature::class.java)) {
                            // Dispatch UI event to indicate switching to albums tab
                            scope.launch {
                                events.dispatch(
                                    Event.LogPhotopickerUIEvent(
                                        FeatureToken.ALBUM_GRID.token,
                                        configuration.sessionId,
                                        configuration.callingPackageUid ?: -1,
                                        Telemetry.UiEvent.SWITCH_PICKER_TAB,
                                    )
                                )
                            }
                            navController.navigateToAlbumGrid()
                        } else if (
                            featureManager.isFeatureEnabled(CategoryGridFeature::class.java)
                        ) {
                            navController.navigateToCategoryGrid()
                        }
                    }
                }
            )
        }

    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val isExpanded = LocalEmbeddedState.current?.isExpanded ?: false
    val isEmbeddedAndCollapsed = isEmbedded && !isExpanded
    val host = LocalEmbeddedState.current?.host

    Column(
        modifier =
            when (isEmbeddedAndCollapsed) {
                true -> baseModifier
                false -> modifierWithNavigation
            }
    ) {
        val isEmptyAndNoMorePages =
            items.itemCount == 0 &&
                items.loadState.source.append is LoadState.NotLoading &&
                items.loadState.source.append.endOfPaginationReached

        when {
            isEmptyAndNoMorePages -> {
                val localConfig = LocalConfiguration.current
                val emptyStatePadding =
                    remember(localConfig) { (localConfig.screenHeightDp * .20).dp }
                EmptyState(
                    modifier =
                        if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                            // In embedded no need to give extra top padding to make empty
                            // state title and body clearly visible in collapse mode (small view)
                            Modifier.fillMaxWidth().transferTouchesToHostInEmbedded(host = host)
                        } else {
                            // Provide 20% of screen height as empty space above
                            Modifier.fillMaxWidth().padding(top = emptyStatePadding)
                        },
                    icon = Icons.Outlined.Image,
                    title = stringResource(R.string.photopicker_photos_empty_state_title),
                    body = stringResource(R.string.photopicker_photos_empty_state_body),
                )
            }
            else -> {

                // When the PhotoGrid is ready to show, also collect the latest banner
                // data from [BannerManager] so it can be placed inside of the mediaGrid's
                // scroll container.
                val currentBanner by viewModel.banners.collectAsStateWithLifecycle()

                mediaGrid(
                    items = items,
                    isExpandedScreen = isExpandedScreen,
                    userScrollEnabled =
                        when (isEmbedded) {
                            true -> isExpanded
                            false -> true
                        },
                    selection = selection,
                    bannerContent = {
                        hideWhenState(
                            selector =
                                object : StateSelector.AnimatedVisibilityInEmbedded {
                                    override val visible =
                                        LocalEmbeddedState.current?.isExpanded ?: false
                                    override val enter =
                                        expandVertically(animationSpec = standardDecelerate(300))
                                    override val exit =
                                        shrinkVertically(animationSpec = standardDecelerate(150))
                                }
                        ) {
                            AnimatedBannerWrapper(currentBanner)
                        }
                    },
                    onItemClick = { item ->
                        if (item is MediaGridItem.MediaItem) {
                            viewModel.handleGridItemSelection(
                                item = item.media,
                                selectionLimitExceededMessage = selectionLimitExceededMessage,
                            )
                            // Log user's interaction with picker's main grid(photo grid)
                            scope.launch {
                                events.dispatch(
                                    Event.LogPhotopickerUIEvent(
                                        FeatureToken.PHOTO_GRID.token,
                                        configuration.sessionId,
                                        configuration.callingPackageUid ?: -1,
                                        Telemetry.UiEvent.PICKER_MAIN_GRID_INTERACTION,
                                    )
                                )
                            }
                        }
                    },
                    onItemLongPress = { item ->
                        // If the [PreviewFeature] is enabled, launch the preview route.
                        if (isPreviewEnabled) {
                            // Log long pressing a media item in the photo grid
                            scope.launch {
                                events.dispatch(
                                    Event.LogPhotopickerUIEvent(
                                        FeatureToken.PREVIEW.token,
                                        configuration.sessionId,
                                        configuration.callingPackageUid ?: -1,
                                        Telemetry.UiEvent.PICKER_LONG_SELECT_MEDIA_ITEM,
                                    )
                                )
                            }
                            if (item is MediaGridItem.MediaItem) {
                                // Log entry into the photopicker preview mode
                                scope.launch {
                                    events.dispatch(
                                        Event.LogPhotopickerUIEvent(
                                            FeatureToken.PREVIEW.token,
                                            configuration.sessionId,
                                            configuration.callingPackageUid ?: -1,
                                            Telemetry.UiEvent.ENTER_PICKER_PREVIEW_MODE,
                                        )
                                    )
                                }
                                navController.navigateToPreviewMedia(item.media)
                            }
                        }
                    },
                    columns = GridCells.Fixed(cellsPerRow),
                    state = state,
                )
                LaunchedEffect(Unit) {
                    // Log loading of photos in the photo grid
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.PHOTO_GRID.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_PHOTOS,
                        )
                    )
                }
            }
        }
    }
}

/**
 * A container that animates its size to show the banner if one is defined. It also handles the
 * banner's onDismiss action by sending the dismissal to the [PhotoGridViewModel].
 *
 * @param currentBanner The current banner that [BannerManager] is exposing.
 */
@Composable
private fun AnimatedBannerWrapper(
    currentBanner: Banner?,
    viewModel: PhotoGridViewModel = obtainViewModel(),
) {
    Box(modifier = Modifier.animateContentSize()) {
        currentBanner?.let {
            Banner(
                it,
                modifier = Modifier.padding(MEASUREMENT_BANNER_PADDING),
                onDismiss = {
                    val declaration = it.declaration

                    // Coerce the type back to [BannerDefinitions]
                    // so that it can be dismissed.
                    if (declaration is BannerDefinitions) {
                        viewModel.markBannerAsDismissed(declaration)
                    }
                },
            )
        }
    }
}

/**
 * The navigation button for the main photo grid. Composable for
 * [Location.NAVIGATION_BAR_NAV_BUTTON]
 */
@Composable
fun PhotoGridNavButton(modifier: Modifier) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current
    val contentDescriptionString = stringResource(R.string.photopicker_photos_nav_button_label)
    val featureManager = LocalFeatureManager.current
    val categoryFeatureEnabled = featureManager.isFeatureEnabled(CategoryGridFeature::class.java)

    NavigationBarButton(
        onClick = {
            // Log switching tab to the photos tab
            scope.launch {
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.PHOTO_GRID.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.SWITCH_PICKER_TAB,
                    )
                )
            }
            navController.navigateToPhotoGrid()
        },
        modifier = modifier.semantics { contentDescription = contentDescriptionString },
        isCurrentRoute = { route -> route == PHOTO_GRID.route },
    ) {
        when (categoryFeatureEnabled) {
            true -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoAlbum,
                        contentDescription =
                            stringResource(R.string.photopicker_photos_nav_button_label),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.photopicker_photos_nav_button_label))
                }
            }
            false -> Text(stringResource(R.string.photopicker_photos_nav_button_label))
        }
    }
}
