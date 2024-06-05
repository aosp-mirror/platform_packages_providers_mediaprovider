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

package com.android.photopicker.features.data.paging

import android.content.ContentResolver
import androidx.paging.PagingSource.LoadParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.AlbumMediaPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import com.android.photopicker.data.TestMediaProvider

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumMediaPagingSourceTest {
    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val contentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)
    private val availableProviders: List<Provider> = listOf(Provider("auth", MediaSource.LOCAL, 0))

    @Mock
    private lateinit var mockMediaProviderClient: MediaProviderClient

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testLoad() = runTest {
        val albumId = "test-album-id"
        val albumAuthority = availableProviders[0].authority
        val albumMediaPagingSource = AlbumMediaPagingSource(
            albumId = albumId,
            albumAuthority = albumAuthority,
            contentResolver = contentResolver,
            availableProviders = availableProviders,
            mediaProviderClient = mockMediaProviderClient,
            dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val pageKey = MediaPageKey()
        val pageSize = 10
        val params = LoadParams.Append<MediaPageKey>(
            key = pageKey,
            loadSize = pageSize,
            placeholdersEnabled = false
        )

        backgroundScope.launch {
            albumMediaPagingSource.load(params)
        }
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchAlbumMedia(
                albumId,
                albumAuthority,
                pageKey,
                pageSize,
                contentResolver,
                availableProviders
            )
    }
}