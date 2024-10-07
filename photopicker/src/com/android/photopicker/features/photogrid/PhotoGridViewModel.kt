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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken.PHOTO_GRID
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
    private val events: Events,
    private val bannerManager: BannerManager,
) : ViewModel() {

    companion object {
        val TAG: String = "PhotoGridViewModel"
    }

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

    /**
     * If initialized, it contains a cold flow of [PagingData] that can be displayed on the
     * [PhotoGrid]. Otherwise, this points to null. See [getData] for initializing this flow.
     */
    private var _data: Flow<PagingData<MediaGridItem>>? = null

    /**
     * If initialized, it contains the last known recent section's cell count. The count can change
     * when the [MainActivity] or the [PhotoGrid] is recreated. See [getData] for initializing this
     * flow.
     */
    private var _recentsCellCount: Int? = null

    /**
     * Export paging data from the pager and prepare it for use in the [MediaGrid]. Also cache the
     * [_data] and [_recentsCellCount] for reuse if the activity gets recreated.
     */
    fun getData(recentsCellCount: Int): Flow<PagingData<MediaGridItem>> {
        return if (
            _recentsCellCount != null && _recentsCellCount!! == recentsCellCount && _data != null
        ) {
            Log.d(
                TAG,
                "Media grid data flow is already initialized with the correct recents " +
                    "cell count: " +
                    recentsCellCount
            )
            _data!!
        } else {
            Log.d(
                TAG,
                "Media grid data flow is not initialized with the correct recents " +
                    "cell count" +
                    recentsCellCount
            )
            _recentsCellCount = recentsCellCount
            val data: Flow<PagingData<MediaGridItem>> =
                pager.flow
                    .toMediaGridItemFromMedia()
                    .insertMonthSeparators(recentsCellCount)
                    // After the load and transformations, cache the data in the viewModelScope.
                    // This ensures that the list position and state will be remembered by the
                    // MediaGrid
                    // when navigating back to the PhotoGrid route.
                    .cachedIn(scope)
            _data = data
            data
        }
    }

    /** Export the [Banner] flow from BannerManager to the UI */
    val banners = bannerManager.flow

    /**
     * Dismissal handler from the UI to mark a particular banner as dismissed by the user. This call
     * is handed off to the bannerManager to persist any relevant dismissal state.
     *
     * Afterwards, refreshBanners is called to check for any new Banners from [BannerManager].
     */
    fun markBannerAsDismissed(banner: BannerDefinitions) {
        scope.launch {
            bannerManager.markBannerAsDismissed(banner)
            bannerManager.refreshBanners()
        }
    }

    /**
     * Click handler that is called when items in the grid are clicked. Selection updates are made
     * in the viewModelScope to ensure they aren't canceled if the user navigates away from the
     * PhotoGrid composable.
     */
    fun handleGridItemSelection(
        item: Media,
        selectionLimitExceededMessage: String,
    ) {
        // Update the selectable values in the received media object.
        val updatedMediaItem =
            Media.withSelectable(
                item, /* selectionSource */
                Telemetry.MediaLocation.MAIN_GRID, /* album */
                null
            )
        scope.launch {
            val result = selection.toggle(updatedMediaItem)
            if (result == FAILURE_SELECTION_LIMIT_EXCEEDED) {
                scope.launch {
                    events.dispatch(
                        Event.ShowSnackbarMessage(PHOTO_GRID.token, selectionLimitExceededMessage)
                    )
                }
            }
        }
    }
}
