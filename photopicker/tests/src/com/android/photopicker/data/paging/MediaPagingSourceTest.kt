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
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.MediaPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import src.com.android.photopicker.data.TestMediaProvider

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MediaPagingSourceTest {
    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val contentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)
    private var userProfile: UserProfile = UserProfile(
        identifier = 0,
        profileType = UserProfile.ProfileType.PRIMARY
    )
    private var userStatus: StateFlow<UserStatus> = MutableStateFlow(
        UserStatus(
            activeUserProfile = userProfile,
            allProfiles = listOf(userProfile),
            activeContentResolver = contentResolver
        )
    )
    private val availableProviders: StateFlow<List<Provider>> = MutableStateFlow(emptyList())

    @Mock
    private lateinit var mockMediaProviderClient: MediaProviderClient

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testLoad() = runTest {
        val mediaPagingSource: MediaPagingSource = MediaPagingSource(
            userStatus,
            availableProviders,
            mockMediaProviderClient
        )

        val pageKey: MediaPageKey = MediaPageKey()
        val pageSize: Int = 10
        val params = LoadParams.Append<MediaPageKey>(
            key = pageKey,
            loadSize = pageSize,
            placeholdersEnabled = false
        )

        backgroundScope.launch {
            mediaPagingSource.load(params)
        }
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchMedia(
                pageKey,
                pageSize,
                contentResolver,
                emptyList()
            )
    }
}