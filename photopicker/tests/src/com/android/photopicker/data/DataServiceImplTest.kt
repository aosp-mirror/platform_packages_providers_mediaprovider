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

package com.android.photopicker.data

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import src.com.android.photopicker.data.TestMediaProvider
import src.com.android.photopicker.data.TestNotificationServiceImpl

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataServiceImplTest {

    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val notificationService = TestNotificationServiceImpl()
    private var userProfile: UserProfile = UserProfile(
        identifier = 0,
        profileType = UserProfile.ProfileType.PRIMARY
    )
    private var userStatus: UserStatus = UserStatus(
        activeUserProfile = userProfile,
        allProfiles = listOf(userProfile),
        activeContentResolver = ContentResolver.wrap(testContentProvider)
    )

    @Test
    fun testInitialAllowedProvider() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
            userStatus = userStatusFlow,
            scope = this.backgroundScope,
            notificationService = notificationService
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        // The first emission will happen once Media Provider responds with the result of available
        // providers.
        assertThat(emissions.count()).isEqualTo(1)
        assertThat(emissions.get(0).count()).isEqualTo(1)
        assertThat(emissions.get(0).get(0).authority)
            .isEqualTo(testContentProvider.providers.get(0).authority)
    }

    @Test
    fun testUpdateAvailableProviders() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
            userStatus = userStatusFlow,
            scope = this.backgroundScope,
            notificationService = notificationService
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        testContentProvider.providers = mutableListOf(
            Provider(
                authority = "local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 0
            ),
            Provider(
                authority = "cloud_authority",
                mediaSource = MediaSource.REMOTE,
                uid = 0
            ),
        )

        notificationService.dispatchChangeToObservers(
            Uri.parse("content://media/picker_internal/v2/available_providers/update"))

        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(2)

        // The first emission will happen once Media Provider responds with the result of
        // available providers.
        assertThat(emissions.get(0).count()).isEqualTo(1)
        assertThat(emissions.get(0).get(0).authority)
            .isEqualTo("test_authority")

        // The next emission happens when a change notification is dispatched.
        assertThat(emissions.get(1).count()).isEqualTo(2)
        assertThat(emissions.get(1).get(0).authority)
            .isEqualTo(testContentProvider.providers.get(0).authority)
        assertThat(emissions.get(1).get(1).authority)
            .isEqualTo(testContentProvider.providers.get(1).authority)
    }
}