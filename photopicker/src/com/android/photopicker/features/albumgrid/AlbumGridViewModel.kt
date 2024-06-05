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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromAlbum
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The view model for the primary album grid.
 *
 * This view model collects the data from [DataService] and caches it in its scope so that loaded
 * data is saved between navigations so that the composable can maintain list positions when
 * navigating back and forth between routes.
 */
@HiltViewModel
class AlbumGridViewModel
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
    private val ALBUM_GRID_PAGE_SIZE = 50

    // Keep up to 10 pages loaded in memory before unloading pages.
    private val ALBUM_GRID_MAX_ITEMS_IN_MEMORY = ALBUM_GRID_PAGE_SIZE * 10

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing media for the album
     * represented by [albumId].
     */
    fun getAlbumMedia(album: Group.Album): Flow<PagingData<MediaGridItem>> {
        val pagerForAlbumMedia =
            Pager(
                PagingConfig(
                    pageSize = ALBUM_GRID_PAGE_SIZE,
                    maxSize = ALBUM_GRID_MAX_ITEMS_IN_MEMORY,
                ),
            ) {
                // pagingSource
                dataService.albumMediaPagingSource(album)
            }

        /** Export the data from the pager and prepare it for use in the [AlbumMediaGrid] */
        val albumMedia =
            pagerForAlbumMedia.flow
                .toMediaGridItemFromMedia()
                .insertMonthSeparators()
                // After the load and transformations, cache the data in the viewModelScope.
                // This ensures that the list position and state will be remembered by the MediaGrid
                // when navigating back to the AlbumGrid route.
                .cachedIn(scope)

        return albumMedia
    }

    /**
     * Returns [PagingData] of type [MediaGridItem] as a [Flow] containing data for user's albums.
     */
    fun getAlbums(): Flow<PagingData<MediaGridItem>> {
        val pagerForAlbums =
            Pager(
                PagingConfig(
                    pageSize = ALBUM_GRID_PAGE_SIZE,
                    maxSize = ALBUM_GRID_MAX_ITEMS_IN_MEMORY,
                ),
            ) {
                dataService.albumPagingSource()
            }

        /** Export the data from the pager and prepare it for use in the [AlbumGrid] */
        val albums =
            pagerForAlbums.flow
                .toMediaGridItemFromAlbum()
                // After the load and transformations, cache the data in the viewModelScope.
                // This ensures that the list position and state will be remembered by the MediaGrid
                // when navigating back to the AlbumGrid route.
                .cachedIn(scope)
        return albums
    }

    /**
     * Click handler that is called when items in the grid are clicked. Selection updates are made
     * in the viewModelScope to ensure they aren't cancelled if the user navigates away from the
     * AlbumMediaGrid composable.
     */
    fun handleAlbumMediaGridItemSelection(item: Media) {
        scope.launch { selection.toggle(item) }
    }
}
