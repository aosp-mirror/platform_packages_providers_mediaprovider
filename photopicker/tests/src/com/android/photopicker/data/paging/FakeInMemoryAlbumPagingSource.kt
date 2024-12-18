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

package com.android.photopicker.data.paging

import android.net.Uri
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * This [FakeInMemoryMediaPagingSource] class is responsible to providing paginated media data from
 * Picker Database by serving requests from Paging library.
 *
 * It generates and returns its own fake data.
 */
class FakeInMemoryAlbumPagingSource
private constructor(
    val DATA_SIZE: Int = DEFAULT_SIZE,
    private val DATA_LIST: List<Group.Album>? = null
) : PagingSource<MediaPageKey, Group.Album>() {

    companion object {
        const val TEST_ALBUM_NAME_PREFIX = "AlbumNumber_"
        const val DEFAULT_SIZE = 10_000
    }

    constructor(dataSize: Int = 10_000) : this(dataSize, null)

    constructor(dataList: List<Group.Album>) : this(DEFAULT_SIZE, dataList)

    private val currentDateTime = LocalDateTime.now()

    // If a [DATA_LIST] was provided, use it, otherwise generate a list of the requested size.
    val DATA =
        DATA_LIST
            ?: buildList<Group.Album> {
                for (i in 1..DATA_SIZE) {
                    add(
                        Group.Album(
                            id = "$i",
                            pickerId = i.toLong(),
                            authority = "a",
                            displayName = TEST_ALBUM_NAME_PREFIX + "$i",
                            coverUri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority("a")
                                        path("$i")
                                    }
                                    .build(),
                            dateTakenMillisLong =
                                currentDateTime
                                    .minus(i.toLong(), ChronoUnit.DAYS)
                                    .toEpochSecond(ZoneOffset.UTC) * 1000,
                            coverMediaSource = MediaSource.LOCAL,
                        ),
                    )
                }
            }

    override suspend fun load(
        params: LoadParams<MediaPageKey>
    ): LoadResult<MediaPageKey, Group.Album> {

        // Handle a data size of 0 for the first page, and return an empty page with no further
        // keys.
        if (DATA.size == 0 && params.key == null) {
            return LoadResult.Page(
                data = emptyList(),
                nextKey = null,
                prevKey = null,
            )
        }

        // This is inefficient, but a reliable way to locate the record being requested by the
        // [MediaPageKey] without having to keep track of offsets.
        val startIndex =
            if (params.key == null) {
                0
            } else {
                DATA.indexOfFirst({ item -> item.pickerId == params.key?.pickerId ?: 1 })
            }

        // The list is zero-based, and loadSize isn't; so, offset by 1
        val endIndex = Math.min((startIndex + params.loadSize) - 1, DATA.lastIndex)

        // Item at start position doesn't exist, so this isn't a valid page.
        if (DATA.getOrNull(startIndex) == null) {
            return LoadResult.Invalid()
        }

        val pageData = DATA.slice(startIndex..endIndex)

        // Find the start of the next page and generate a Page key.
        val nextRow = DATA.getOrNull(endIndex + 1)
        val nextKey =
            if (nextRow == null) {
                null
            } else {
                MediaPageKey(
                    pickerId = nextRow.pickerId,
                    dateTakenMillis = nextRow.dateTakenMillisLong,
                )
            }

        // Find the start of the previous page and generate a Page key.
        val prevPageRow = DATA.getOrNull((startIndex) - params.loadSize)
        val prevKey =
            if (prevPageRow == null) {
                null
            } else {
                MediaPageKey(
                    pickerId = prevPageRow.pickerId,
                    dateTakenMillis = prevPageRow.dateTakenMillisLong,
                )
            }

        return LoadResult.Page(
            data = pageData,
            nextKey = nextKey,
            prevKey = prevKey,
        )
    }

    override fun getRefreshKey(state: PagingState<MediaPageKey, Group.Album>): MediaPageKey? {
        return state.anchorPosition?.let { null }
    }
}
