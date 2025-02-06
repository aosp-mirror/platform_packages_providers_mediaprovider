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

package com.android.photopicker.features.categorygrid.data

import android.os.CancellationSignal
import androidx.paging.PagingSource
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey

/**
 * Powers UI with data for the category feature which includes the category grid, media sets grid,
 * and media set content grid. This class owns the responsibility to:
 * - fetch categories, albums, media sets and media on demand
 * - cache data if required
 * - keep track of data updates in the data source
 * - detect and refresh stale data
 */
interface CategoryDataService {
    companion object {
        const val TAG: String = "PhotopickerSearchCategoryService"
    }

    /**
     * Creates a paging source that can load categories and albums.
     *
     * @param category the parent [Category]. If the parent category is null then the method returns
     *   root categories.
     * @param cancellationSignal An optional [CancellationSignal] that can be marked as cancelled
     *   when the query results are no longer required.
     * @return The [PagingSource] that fetches a page using [GroupPageKey]. A page in the paging
     *   source contains a [List] of [Group.Category] items.
     */
    fun getCategories(
        parentCategory: Group.Category? = null,
        cancellationSignal: CancellationSignal? = null,
    ): PagingSource<GroupPageKey, Group>

    /**
     * Creates a paging source that can load media sets. Leaf categories contain [MediaSet]-s. A
     * [MediaSet] can only contain media items.
     *
     * @param category the parent [Category].
     * @param cancellationSignal An optional [CancellationSignal] that can be marked as cancelled
     *   when the query results are no longer required.
     * @return The [PagingSource] that fetches a page using [GroupPageKey]. A page in the paging
     *   source contains a [List] of [Group.MediaSet] items.
     */
    fun getMediaSets(
        category: Group.Category,
        cancellationSignal: CancellationSignal? = null,
    ): PagingSource<GroupPageKey, Group.MediaSet>

    /**
     * Creates a paging source that can load media items of a media set.
     *
     * @param mediaSet the parent [MediaSet].
     * @param cancellationSignal An optional [CancellationSignal] that can be marked as cancelled
     *   when the query results are no longer required.
     * @return The [PagingSource] that fetches a page using [MediaPageKey]. A page in the paging
     *   source contains a [List] of [Media] items.
     */
    fun getMediaSetContents(
        mediaSet: Group.MediaSet,
        cancellationSignal: CancellationSignal? = null,
    ): PagingSource<MediaPageKey, Media>
}
