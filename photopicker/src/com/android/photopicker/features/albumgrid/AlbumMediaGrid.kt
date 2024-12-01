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

import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.StarOutline
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
import androidx.paging.PagingData
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
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.Group
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.extensions.transferTouchesToHostInEmbedded
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.util.LocalLocalizationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Primary composable for drawing the Album content grid on
 * [PhotopickerDestinations.ALBUM_MEDIA_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 * @param flow - stateflow holding the album for which the media needs to be presented.
 */
@Composable
fun AlbumMediaGrid(
    flow: StateFlow<Group.Album?>,
    viewModel: AlbumGridViewModel = obtainViewModel(),
) {
    val albumState by flow.collectAsStateWithLifecycle(initialValue = null)
    val album = albumState

    Column(modifier = Modifier.fillMaxSize()) {
        when (album) {
            null -> {}
            else -> {
                val albumItems = remember(album) { viewModel.getAlbumMedia(album) }
                AlbumMediaGrid(album = album, albumItems = albumItems)
            }
        }
    }
}

/** Initialises all the states and media source required to load media for the input [album]. */
@Composable
private fun AlbumMediaGrid(
    album: Group.Album,
    albumItems: Flow<PagingData<MediaGridItem>>,
    viewModel: AlbumGridViewModel = obtainViewModel(),
) {
    val featureManager = LocalFeatureManager.current
    val isPreviewEnabled = remember { featureManager.isFeatureEnabled(PreviewFeature::class.java) }

    val navController = LocalNavController.current

    val items = albumItems.collectAsLazyPagingItems()

    // Collect the selection to notify the mediaGrid of selection changes.
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val localizedSelectionLimit = LocalLocalizationHelper.current.getLocalizedCount(selectionLimit)
    val selectionLimitExceededMessage =
        stringResource(
            R.string.photopicker_selection_limit_exceeded_snackbar,
            localizedSelectionLimit,
        )
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current

    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val state = rememberLazyGridState()
    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val isExpanded = LocalEmbeddedState.current?.isExpanded ?: false

    val host = LocalEmbeddedState.current?.host
    // Container encapsulating the album title followed by the album content in the form of a
    // grid, the content also includes date and month separators.
    Column(modifier = Modifier.fillMaxSize()) {
        val isEmptyAndNoMorePages =
            items.itemCount == 0 &&
                items.loadState.source.append is LoadState.NotLoading &&
                items.loadState.source.append.endOfPaginationReached

        when {
            isEmptyAndNoMorePages -> {
                val localConfig = LocalConfiguration.current
                val emptyStatePadding =
                    remember(localConfig) { (localConfig.screenHeightDp * .20).dp }
                val (title, body, icon) = getEmptyStateContentForAlbum(album)
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
                    icon = icon,
                    title = title,
                    body = body,
                )
            }
            else -> {

                mediaGrid(
                    // Album content grid
                    items = items,
                    userScrollEnabled =
                        when (isEmbedded) {
                            true -> isExpanded
                            false -> true
                        },
                    isExpandedScreen = isExpandedScreen,
                    selection = selection,
                    onItemClick = { item ->
                        if (item is MediaGridItem.MediaItem) {
                            viewModel.handleAlbumMediaGridItemSelection(
                                item.media,
                                selectionLimitExceededMessage,
                                album,
                            )
                        }
                    },
                    onItemLongPress = { item ->
                        // If the [PreviewFeature] is enabled, launch the preview route.
                        if (isPreviewEnabled && item is MediaGridItem.MediaItem) {
                            // Dispatch UI event to log long pressing the media item
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
                            // Dispatch UI event to log entry into preview mode
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
                    },
                    state = state,
                )
                LaunchedEffect(Unit) {
                    // Dispatch UI event to log loading of album contents
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.PHOTO_GRID.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_ALBUM_CONTENTS,
                        )
                    )
                }
            }
        }
    }
}

/**
 * Matches the correct empty state title, message and icon to an album based on it's ID. If the
 * album's id is not explicitly handled, it will return a generic content for the empty state.
 *
 * @return a [Triple] that contains the [Title, Body, Icon] for the empty state.
 */
@Composable
private fun getEmptyStateContentForAlbum(album: Group.Album): Triple<String, String, ImageVector> {
    return when (album.id) {
        ALBUM_ID_FAVORITES ->
            Triple(
                stringResource(R.string.photopicker_favorites_empty_state_title),
                stringResource(R.string.photopicker_favorites_empty_state_body),
                Icons.Outlined.StarOutline,
            )
        ALBUM_ID_VIDEOS ->
            Triple(
                stringResource(R.string.photopicker_videos_empty_state_title),
                stringResource(R.string.photopicker_videos_empty_state_body),
                Icons.Outlined.PlayCircleOutline,
            )
        ALBUM_ID_CAMERA ->
            Triple(
                stringResource(R.string.photopicker_photos_empty_state_title),
                stringResource(R.string.photopicker_camera_empty_state_body),
                Icons.Outlined.PhotoCamera,
            )
        // Use the empty state messages of the main photo grid in all other cases.
        else ->
            Triple(
                stringResource(R.string.photopicker_photos_empty_state_title),
                stringResource(R.string.photopicker_photos_empty_state_body),
                Icons.Outlined.Image,
            )
    }
}
