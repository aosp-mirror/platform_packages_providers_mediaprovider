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

package com.android.photopicker.features.categorygrid.paging

import android.content.ContentResolver
import android.os.CancellationSignal
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * This [PagingSource] class is responsible to providing paginated media items in media set Picker
 * Backend by serving requests from Paging library. It sources data from a [ContentProvider] called
 * [MediaProvider].
 */
class MediaSetContentsPagingSource(
    val contentResolver: ContentResolver,
    private val parentMediaSet: Group.MediaSet,
    private val mediaProviderClient: MediaProviderClient,
    private val dispatcher: CoroutineDispatcher,
    private val configuration: PhotopickerConfiguration,
    private val events: Events,
    private val cancellationSignal: CancellationSignal?,
) : PagingSource<MediaPageKey, Media>() {
    companion object {
        val TAG: String = "PickerMediaSetContentPagingSource"
    }

    override suspend fun load(params: LoadParams<MediaPageKey>): LoadResult<MediaPageKey, Media> {
        val pageKey = params.key ?: MediaPageKey()
        val pageSize = params.loadSize
        // Switch to the background thread from the main thread using [withContext].
        val result =
            withContext(dispatcher) {
                try {
                    mediaProviderClient.fetchMediaSetContents(
                        pageKey,
                        pageSize,
                        contentResolver,
                        parentMediaSet,
                        configuration,
                        cancellationSignal,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not fetch page from Media provider", e)
                    LoadResult.Error(e)
                }
            }

        if (result is LoadResult.Page) {
            Log.d(TAG, "Received ${result.data.size} media set contents from the data source.")
        }

        return result
    }

    override fun getRefreshKey(state: PagingState<MediaPageKey, Media>): MediaPageKey? = null
}
