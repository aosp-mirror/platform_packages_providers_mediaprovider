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
import android.database.ContentObserver
import android.net.Uri
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.tests.utils.mockito.nonNullableAny
import com.android.photopicker.tests.utils.mockito.nonNullableEq
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataServiceImplTest {

    private lateinit var testContentProvider: TestMediaProvider
    private lateinit var testContentResolver: ContentResolver
    private lateinit var notificationService: TestNotificationServiceImpl
    private lateinit var mediaProviderClient: MediaProviderClient
    private val userProfilePrimary: UserProfile = UserProfile(
        identifier = 0,
        profileType = UserProfile.ProfileType.PRIMARY
    )
    private val userProfileManaged: UserProfile = UserProfile(
            identifier = 10,
            profileType = UserProfile.ProfileType.MANAGED
    )
    private lateinit var userStatus: UserStatus


    @Before
    fun setup() {
        testContentProvider = TestMediaProvider()
        testContentResolver = ContentResolver.wrap(testContentProvider)
        notificationService = TestNotificationServiceImpl()
        mediaProviderClient = MediaProviderClient()
        userStatus = UserStatus(
            activeUserProfile = userProfilePrimary,
            allProfiles = listOf(userProfilePrimary),
            activeContentResolver = testContentResolver
        )
    }

    @Test
    fun testAvailableContentProviderFlow() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        // The first emission will be an empty string. The next emission will happen once Media
        // Provider responds with the result of available providers.
        assertThat(emissions.count()).isEqualTo(2)
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        assertThat(emissions.get(1).count()).isEqualTo(1)
        assertThat(emissions.get(1).get(0).authority)
                .isEqualTo(testContentProvider.providers[0].authority)
    }

    @Test
    fun testInitialAllowedProvider() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
            userStatus = userStatusFlow,
            scope = this.backgroundScope,
            notificationService = notificationService,
            mediaProviderClient = mediaProviderClient,
            dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        // The first emission will be an empty string. The next emission will happen once Media
        // Provider responds with the result of available providers.
        assertThat(emissions.count()).isEqualTo(2)
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        assertThat(emissions.get(1).count()).isEqualTo(1)
        assertThat(emissions.get(1).get(0).authority)
                .isEqualTo(testContentProvider.providers[0].authority)
    }

    @Test
    fun testUpdateAvailableProviders() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
            userStatus = userStatusFlow,
            scope = this.backgroundScope,
            notificationService = notificationService,
            mediaProviderClient = mediaProviderClient,
            dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(2)
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

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

        assertThat(emissions.count()).isEqualTo(3)

        // The first emission will be an empty list.
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        // The next emission will happen once Media Provider responds with the result of
        // available providers at the time of init.
        assertThat(emissions.get(1).count()).isEqualTo(1)
        assertThat(emissions.get(1).get(0).authority)
                .isEqualTo("test_authority")

        // The next emission happens when a change notification is dispatched.
        assertThat(emissions.get(2).count()).isEqualTo(2)
        assertThat(emissions.get(2).get(0).authority)
                .isEqualTo(testContentProvider.providers[0].authority)
        assertThat(emissions.get(2).get(1).authority)
                .isEqualTo(testContentProvider.providers[1].authority)
    }

    @Test
    fun testAvailableProvidersWhenUserChanges() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(2)
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        // A new user becomes active.
        userStatusFlow.update { userStatus: UserStatus ->
            userStatus.copy (
                allProfiles = listOf(userProfilePrimary, userProfileManaged)
            )
        }

        advanceTimeBy(100)

        // Since the active user did not change, no change should be observed in available
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // The active user changes
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers = mutableListOf(
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

        userStatusFlow.update { userStatus: UserStatus ->
            userStatus.copy (
                activeUserProfile = userProfileManaged,
                activeContentResolver = updatedContentResolver
            )
        }

        advanceTimeBy(100)

        // Since the active user has changed, this should trigger a refetch of the active providers.
        assertThat(emissions.count()).isEqualTo(3)

        // The first emission will be an empty list.
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        // The next emission will happen once Media Provider responds with the result of
        // available providers at the time of init. This will be the last emission from the previous
        // content provider.
        assertThat(emissions.get(1).count()).isEqualTo(1)
        assertThat(emissions.get(1).get(0).authority)
                .isEqualTo(testContentProvider.providers[0].authority)

        // The next emission happens when a change in active user is observed. This last emission
        // should come from the updated content provider.
        assertThat(emissions.get(2).count()).isEqualTo(2)
        assertThat(emissions.get(2).get(0).authority)
                .isEqualTo(updatedContentProvider.providers[0].authority)
        assertThat(emissions.get(2).get(1).authority)
                .isEqualTo(updatedContentProvider.providers[1].authority)
    }

    @Test
    fun testAvailableProviderContentObserverWhenUserChanges() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        val mockNotificationService = mock(NotificationService::class.java)

        val dataService: DataService = DataServiceImpl(
            userStatus = userStatusFlow,
            scope = this.backgroundScope,
            notificationService = mockNotificationService,
            mediaProviderClient = mediaProviderClient,
            dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        // Verify initial available provider emissions.
        assertThat(emissions.count()).isEqualTo(2)
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        val defaultContentObserver: ContentObserver = object : ContentObserver(/* handler */ null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { }
        }
        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(testContentResolver),
                nonNullableEq(Uri.parse(
                        "content://media/picker_internal/v2/available_providers/update")),
                ArgumentMatchers.eq(true),
                nonNullableAny<ContentObserver>(ContentObserver::class.java, defaultContentObserver)
            )

        // Change the active user
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers = mutableListOf(
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

        userStatusFlow.update { userStatus: UserStatus ->
            userStatus.copy (
                activeUserProfile = userProfileManaged,
                activeContentResolver = updatedContentResolver
            )
        }

        advanceTimeBy(100)

        verify(mockNotificationService)
            .unregisterContentObserverCallback(
                nonNullableEq(testContentResolver),
                nonNullableAny<ContentObserver>(ContentObserver::class.java, defaultContentObserver)
            )

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(updatedContentResolver),
                nonNullableEq(Uri.parse(
                        "content://media/picker_internal/v2/available_providers/update")),
                ArgumentMatchers.eq(true),
                nonNullableAny<ContentObserver>(ContentObserver::class.java, defaultContentObserver)
            )
    }

    @Test
    fun testMediaPagingSourceInvalidation() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)

        val dataService: DataService = DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler)
        )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(emissions)
        }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(2)
        assertThat(emissions.get(0)).isEqualTo(emptyList<Provider>())

        val firstMediaPagingSource: PagingSource<MediaPageKey, Media> =
                dataService.mediaPagingSource()
        assertThat(firstMediaPagingSource.invalid).isFalse()

        // The active user changes
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers = mutableListOf(
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

        userStatusFlow.update { userStatus: UserStatus ->
            userStatus.copy (
                    activeContentResolver = updatedContentResolver
            )
        }

        advanceTimeBy(1000)

        // Since the active user has changed, this should trigger a refetch of the active providers.
        assertThat(emissions.count()).isEqualTo(3)

        // Check that the previously created MediaPagingSource has been invalidated.
        assertThat(firstMediaPagingSource.invalid).isTrue()

        // Check that the new MediaPagingSource instance is still valid.
        val secondMediaPagingSource: PagingSource<MediaPageKey, Media> =
                dataService.mediaPagingSource()
        assertThat(secondMediaPagingSource.invalid).isFalse()
    }
}