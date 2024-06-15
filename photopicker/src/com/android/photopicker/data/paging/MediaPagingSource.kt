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

import android.content.ContentResolver
import android.content.Intent
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * This [PagingSource] class is responsible to providing paginated media data from Picker Database
 * by serving requests from Paging library.
 *
 * It sources data from a [ContentProvider] called [MediaProvider].
 */
class MediaPagingSource(
    private val contentResolver: ContentResolver,
    private val availableProviders: List<Provider>,
    private val mediaProviderClient: MediaProviderClient,
    private val dispatcher: CoroutineDispatcher,
    private val intent: Intent?,
) : PagingSource<MediaPageKey, Media>() {
    companion object {
        val TAG: String = "PickerMediaPagingSource"
    }

    override suspend fun load(params: LoadParams<MediaPageKey>): LoadResult<MediaPageKey, Media> {
        // Switch to the background thread from the main thread using [withContext].
        return withContext(dispatcher) {
            val pageKey = params.key ?: MediaPageKey()
            val pageSize = params.loadSize

            try {
                if (availableProviders.isEmpty()) {
                    throw IllegalArgumentException("No available providers found.")
                }

                mediaProviderClient.fetchMedia(
                    pageKey,
                    pageSize,
                    contentResolver,
                    availableProviders,
                    intent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not fetch page from Media provider", e)
                LoadResult.Error(e)
            }
        }
    }

    override fun getRefreshKey(state: PagingState<MediaPageKey, Media>): MediaPageKey? = null
}
