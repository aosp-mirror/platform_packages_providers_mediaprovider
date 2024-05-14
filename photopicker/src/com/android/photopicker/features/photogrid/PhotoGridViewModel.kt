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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The view model for the primary Photo grid.
 *
 * This view model collects the data from [DataService] and caches it in its scope so that loaded
 * data is saved between navigations so that the composable can maintain list positions when
 * navigating back and forth between routes.
 */
@HiltViewModel
class PhotoGridViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    private val selection: Selection<Media>,
    private val dataService: DataService,
) : ViewModel() {

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    // Request Media in batches of 50 items
    private val PHOTO_GRID_PAGE_SIZE = 50

    // The size of the initial load when no pages are loaded. Ensures there is enough content
    // to cover small scrolls.
    private val PHOTO_GRID_INITIAL_LOAD_SIZE = PHOTO_GRID_PAGE_SIZE * 3

    // How far from the edge of loaded content before fetching the next page
    private val PHOTO_GRID_PREFETCH_DISTANCE = PHOTO_GRID_PAGE_SIZE * 2

    // Keep up to 10 pages loaded in memory before unloading pages.
    private val PHOTO_GRID_MAX_ITEMS_IN_MEMORY = PHOTO_GRID_PAGE_SIZE * 10

    val pagingConfig =
        PagingConfig(
            pageSize = PHOTO_GRID_PAGE_SIZE,
            maxSize = PHOTO_GRID_MAX_ITEMS_IN_MEMORY,
            initialLoadSize = PHOTO_GRID_INITIAL_LOAD_SIZE,
            prefetchDistance = PHOTO_GRID_PREFETCH_DISTANCE,
        )

    val pager =
        Pager(
            PagingConfig(pageSize = PHOTO_GRID_PAGE_SIZE, maxSize = PHOTO_GRID_MAX_ITEMS_IN_MEMORY)
        ) {
            dataService.mediaPagingSource()
        }

    /** Export the data from the pager and prepare it for use in the [MediaGrid] */
    val data =
        pager.flow
            .toMediaGridItemFromMedia()
            .insertMonthSeparators()
            // After the load and transformations, cache the data in the viewModelScope.
            // This ensures that the list position and state will be remembered by the MediaGrid
            // when navigating back to the PhotoGrid route.
            .cachedIn(scope)

    /**
     * Click handler that is called when items in the grid are clicked. Selection updates are made
     * in the viewModelScope to ensure they aren't cancelled if the user navigates away from the
     * PhotoGrid composable.
     */
    fun handleGridItemSelection(item: Media) {
        scope.launch { selection.toggle(item) }
    }
}
