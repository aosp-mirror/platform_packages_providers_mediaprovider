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

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass

/**
 * Primary composable for drawing the main PhotoGrid on [PhotopickerDestinations.PHOTO_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using hiltViewModel()
 */
@Composable
fun PhotoGrid(viewModel: PhotoGridViewModel = hiltViewModel()) {

    val items = viewModel.data.collectAsLazyPagingItems()

    val state = rememberLazyGridState()

    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    /* Use the expanded layout any time the Width is Medium or larger. */
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    mediaGrid(
        items = items,
        isExpandedScreen = isExpandedScreen,
        selection = selection,
        onItemClick = { item -> viewModel.handleGridItemSelection(item) },
        state = state,
    )
}
