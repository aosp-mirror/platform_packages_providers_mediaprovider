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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.Group
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.features.preview.PreviewFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
    viewModel: AlbumGridViewModel = obtainViewModel()
) {
    val albumState by flow.collectAsStateWithLifecycle(initialValue = null)
    val album = albumState

    when (album) {
        null -> {}
        else -> {
            AlbumMediaGrid(albumItems = viewModel.getAlbumMedia(album))
        }
    }
}

/** Initialises all the states and media source required to load media for the input [album]. */
@Composable
private fun AlbumMediaGrid(
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
    val selectionLimitExceededMessage =
        stringResource(R.string.photopicker_selection_limit_exceeded_snackbar, selectionLimit)

    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val state = rememberLazyGridState()
    // Container encapsulating the album title followed by the album content in the form of a
    // grid, the content also includes date and month separators.
    Column(modifier = Modifier.fillMaxSize()) {
        mediaGrid(
            // Album content grid
            items = items,
            isExpandedScreen = isExpandedScreen,
            selection = selection,
            onItemClick = { item ->
                if (item is MediaGridItem.MediaItem) {
                    viewModel.handleAlbumMediaGridItemSelection(
                        item.media,
                        selectionLimitExceededMessage
                    )
                }
            },
            onItemLongPress = { item ->
                // If the [PreviewFeature] is enabled, launch the preview route.
                if (isPreviewEnabled && item is MediaGridItem.MediaItem) {
                    navController.navigateToPreviewMedia(item.media)
                }
            },
            state = state,
        )
    }
}
