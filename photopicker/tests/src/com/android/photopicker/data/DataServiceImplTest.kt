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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Parcel
import android.os.UserHandle
import android.provider.CloudMediaProviderContract
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerFlags
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import com.android.photopicker.util.test.nonNullableAny
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataServiceImplTest {

    companion object {
        private fun createUserHandle(userId: Int = 0): UserHandle {
            val parcel = Parcel.obtain()
            parcel.writeInt(userId)
            parcel.setDataPosition(0)
            val userHandle = UserHandle(parcel)
            parcel.recycle()
            return userHandle
        }

        private val albumMediaUpdateUri =
            Uri.parse("content://media/picker_internal/v2/album/update")
        private val mediaUpdateUri = Uri.parse("content://media/picker_internal/v2/media/update")
        private val availableProvidersUpdateUri =
            Uri.parse("content://media/picker_internal/v2/available_providers/update")
        private val userProfilePrimary: UserProfile =
            UserProfile(handle = createUserHandle(0), profileType = UserProfile.ProfileType.PRIMARY)
        private val userProfileManaged: UserProfile =
            UserProfile(
                handle = createUserHandle(10),
                profileType = UserProfile.ProfileType.MANAGED,
            )
    }

    private val sessionId = generatePickerSessionId()

    private lateinit var testFeatureManager: FeatureManager
    private lateinit var testContentProvider: TestMediaProvider
    private lateinit var testContentResolver: ContentResolver
    private lateinit var notificationService: TestNotificationServiceImpl
    private lateinit var mediaProviderClient: MediaProviderClient
    private lateinit var userStatus: UserStatus
    private lateinit var mockContext: Context
    private lateinit var mockPackageManager: PackageManager
    private lateinit var events: Events

    @Before
    fun setup() {
        val scope = TestScope()
        testContentProvider = TestMediaProvider()
        testContentResolver = ContentResolver.wrap(testContentProvider)
        notificationService = TestNotificationServiceImpl()
        mediaProviderClient = MediaProviderClient()
        mockContext = mock(Context::class.java)
        mockPackageManager = mock(PackageManager::class.java)
        userStatus =
            UserStatus(
                activeUserProfile = userProfilePrimary,
                allProfiles = listOf(userProfilePrimary),
                activeContentResolver = testContentResolver,
            )
        testFeatureManager =
            FeatureManager(
                provideTestConfigurationFlow(scope = scope.backgroundScope),
                scope,
                TestPrefetchDataService(),
                setOf(CloudMediaFeature.Registration),
                setOf<RegisteredEventClass>(),
                setOf<RegisteredEventClass>(),
            )
    }

    @Test
    fun testInitialAllowedProvider() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)
        assertThat(emissions.get(0).count()).isEqualTo(1)
        assertThat(emissions.get(0).get(0).authority)
            .isEqualTo(testContentProvider.providers[0].authority)
    }

    @Test
    fun testUpdateAvailableProviders() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        testFeatureManager =
            FeatureManager(
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        PhotopickerConfiguration(
                            action = "TEST_ACTION",
                            sessionId = sessionId,
                            flags =
                                PhotopickerFlags(
                                    CLOUD_MEDIA_ENABLED = true,
                                    CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                                ),
                        ),
                ),
                this.backgroundScope,
                TestPrefetchDataService(),
                setOf(CloudMediaFeature.Registration),
                setOf<RegisteredEventClass>(),
                setOf<RegisteredEventClass>(),
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        this.backgroundScope,
                        defaultConfiguration =
                            PhotopickerConfiguration(
                                action = "TEST_ACTION",
                                sessionId = sessionId,
                                flags =
                                    PhotopickerFlags(
                                        CLOUD_MEDIA_ENABLED = true,
                                        CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                                    ),
                            ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        testContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        notificationService.dispatchChangeToObservers(availableProvidersUpdateUri)

        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(2)

        // The first emission will happen once Media Provider responds with the result of
        // available providers at the time of init.
        assertThat(emissions.get(0).count()).isEqualTo(1)
        assertThat(emissions.get(0).get(0).authority).isEqualTo("test_authority")

        // The next emission happens when a change notification is dispatched.
        assertThat(emissions.get(1).count()).isEqualTo(2)
        assertThat(emissions.get(1).get(0).authority)
            .isEqualTo(testContentProvider.providers[0].authority)
        assertThat(emissions.get(1).get(1).authority)
            .isEqualTo(testContentProvider.providers[1].authority)
    }

    @Test
    fun testAvailableProvidersCloudMediaFeatureDisabled() = runTest {
        testContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        val scope = TestScope()
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(scope = scope.backgroundScope),
                scope,
                TestPrefetchDataService(),
                setOf(), // Don't register CloudMediaFeature
                setOf<RegisteredEventClass>(),
                setOf<RegisteredEventClass>(),
            )
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                featureManager,
            )
        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    MutableStateFlow(
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                    ),
                appContext = mockContext,
                featureManager = featureManager,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        // The first emission will happen once Media Provider responds with the result of
        // available providers at the time of init. Check that the provider with MediaSource.REMOTE
        // is not part of the available providers.
        assertThat(emissions.get(0).count()).isEqualTo(1)
        assertThat(emissions.get(0).get(0).authority).isEqualTo("local_authority")
    }

    @Test
    fun testAvailableProvidersWhenUserChanges() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        testFeatureManager =
            FeatureManager(
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        PhotopickerConfiguration(
                            action = "TEST_ACTION",
                            sessionId = sessionId,
                            flags =
                                PhotopickerFlags(
                                    CLOUD_MEDIA_ENABLED = true,
                                    CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                                ),
                        ),
                ),
                this.backgroundScope,
                TestPrefetchDataService(),
                setOf(CloudMediaFeature.Registration),
                setOf<RegisteredEventClass>(),
                setOf<RegisteredEventClass>(),
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        this.backgroundScope,
                        PhotopickerConfiguration(
                            action = "TEST_ACTION",
                            sessionId = sessionId,
                            flags =
                                PhotopickerFlags(
                                    CLOUD_MEDIA_ENABLED = true,
                                    CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                                ),
                        ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        // A new user becomes active.
        userStatusFlow.update {
            it.copy(allProfiles = listOf(userProfilePrimary, userProfileManaged))
        }

        advanceTimeBy(100)

        // Since the active user did not change, no change should be observed in available
        // providers.
        assertThat(emissions.count()).isEqualTo(1)

        // The active user changes
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        userStatusFlow.update {
            it.copy(
                activeUserProfile = userProfileManaged,
                activeContentResolver = updatedContentResolver,
            )
        }

        advanceTimeBy(100)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // The first emission will happen once Media Provider responds with the result of
        // available providers at the time of init. This will be the last emission from the previous
        // content provider.
        assertThat(emissions.get(0).count()).isEqualTo(1)
        assertThat(emissions.get(0).get(0).authority)
            .isEqualTo(testContentProvider.providers[0].authority)

        // The next emission happens when a change in active user is observed. This last emission
        // should come from the updated content provider.
        assertThat(emissions.get(1).count()).isEqualTo(2)
        assertThat(emissions.get(1).get(0).authority)
            .isEqualTo(updatedContentProvider.providers[0].authority)
        assertThat(emissions.get(1).get(1).authority)
            .isEqualTo(updatedContentProvider.providers[1].authority)
    }

    @Test
    fun testContentObserverRegistrationWhenUserChanges() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        val mockNotificationService = mock(NotificationService::class.java)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = mockNotificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        // Verify initial available provider emissions.
        assertThat(emissions.count()).isEqualTo(1)

        val defaultContentObserver: ContentObserver =
            object : ContentObserver(/* handler */ null) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {}
            }

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(testContentResolver),
                nonNullableEq(availableProvidersUpdateUri),
                ArgumentMatchers.eq(true),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(testContentResolver),
                nonNullableEq(mediaUpdateUri),
                ArgumentMatchers.eq(true),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(testContentResolver),
                nonNullableEq(albumMediaUpdateUri),
                ArgumentMatchers.eq(true),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )

        // Change the active user
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        userStatusFlow.update {
            it.copy(
                activeUserProfile = userProfileManaged,
                activeContentResolver = updatedContentResolver,
            )
        }

        advanceTimeBy(100)

        verify(mockNotificationService, times(3))
            .unregisterContentObserverCallback(
                nonNullableEq(testContentResolver),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(updatedContentResolver),
                nonNullableEq(availableProvidersUpdateUri),
                ArgumentMatchers.eq(true),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(updatedContentResolver),
                nonNullableEq(mediaUpdateUri),
                ArgumentMatchers.eq(true),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )

        verify(mockNotificationService)
            .registerContentObserverCallback(
                nonNullableEq(updatedContentResolver),
                nonNullableEq(albumMediaUpdateUri),
                ArgumentMatchers.eq(true),
                nonNullableAny(ContentObserver::class.java, defaultContentObserver),
            )
    }

    @Test
    fun testMediaPagingSourceInvalidation() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        val firstMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.mediaPagingSource()
        assertThat(firstMediaPagingSource.invalid).isFalse()

        // The active user changes
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        userStatusFlow.update { it.copy(activeContentResolver = updatedContentResolver) }

        advanceTimeBy(1000)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // Check that the previously created MediaPagingSource has been invalidated.
        assertThat(firstMediaPagingSource.invalid).isTrue()

        // Check that the new MediaPagingSource instance is still valid.
        val secondMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.mediaPagingSource()
        assertThat(secondMediaPagingSource.invalid).isFalse()
    }

    @Test
    fun testAlbumPagingSourceInvalidation() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        // Check initial available provider emissions
        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        val firstAlbumPagingSource: PagingSource<MediaPageKey, Group.Album> =
            dataService.albumPagingSource()
        assertThat(firstAlbumPagingSource.invalid).isFalse()

        // The active user changes
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        userStatusFlow.update { it.copy(activeContentResolver = updatedContentResolver) }
        advanceTimeBy(100)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // Check that the previously created MediaPagingSource has been invalidated.
        assertThat(firstAlbumPagingSource.invalid).isTrue()

        // Check that the new MediaPagingSource instance is still valid.
        val secondAlbumPagingSource: PagingSource<MediaPageKey, Group.Album> =
            dataService.albumPagingSource()
        assertThat(secondAlbumPagingSource.invalid).isFalse()
    }

    @Test
    fun testAlbumMediaPagingSourceCacheUpdates() = runTest {
        testContentProvider.lastRefreshMediaRequest = null
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )
        advanceTimeBy(100)

        // Fetch album media the first time
        val albumId = testContentProvider.albumMedia.keys.first()
        val album =
            Group.Album(
                id = albumId,
                pickerId = Long.MAX_VALUE,
                authority = testContentProvider.providers[0].authority,
                dateTakenMillisLong = Long.MAX_VALUE,
                displayName = "album",
                coverUri = Uri.parse("content://media/picker/authority/media/${Long.MAX_VALUE}"),
                coverMediaSource = testContentProvider.providers[0].mediaSource,
            )

        val firstAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)

        // Check the album media paging source is valid
        assertThat(firstAlbumMediaPagingSource.invalid).isFalse()

        // Check that a cache refresh request was received
        val albumMediaRefreshRequestExtras = testContentProvider.lastRefreshMediaRequest
        assertThat(albumMediaRefreshRequestExtras).isNotNull()

        // Fetch the album media again
        val secondAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)

        // Check the previous album media source was reused because it was not marked as invalid.
        assertThat(secondAlbumMediaPagingSource.invalid).isFalse()
        assertThat(secondAlbumMediaPagingSource).isEqualTo(firstAlbumMediaPagingSource)

        // Check that a cache refresh request was not received the second time
        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest)
            .isEqualTo(albumMediaRefreshRequestExtras)

        // Mark the paging source as invalid
        secondAlbumMediaPagingSource.invalidate()

        // Fetch the album media again
        val thirdAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)

        // Check the previous album media source was not reused because it was invalidated.
        assertThat(secondAlbumMediaPagingSource.invalid).isTrue()
        assertThat(thirdAlbumMediaPagingSource.invalid).isFalse()
        assertThat(thirdAlbumMediaPagingSource).isNotEqualTo(secondAlbumMediaPagingSource)

        // Check that a cache refresh request was not received the third time either
        assertThat(testContentProvider.lastRefreshMediaRequest).isNotNull()
        assertThat(testContentProvider.lastRefreshMediaRequest)
            .isEqualTo(albumMediaRefreshRequestExtras)
    }

    @Test
    fun testAlbumMediaPagingSourceInvalidation() = runTest {
        val userStatusFlow: MutableStateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        // Check initial available provider emissions
        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        // Fetch album media the first time
        val albumId = testContentProvider.albumMedia.keys.first()
        val album =
            Group.Album(
                id = albumId,
                pickerId = Long.MAX_VALUE,
                authority = testContentProvider.providers[0].authority,
                dateTakenMillisLong = Long.MAX_VALUE,
                displayName = "album",
                coverUri = Uri.parse("content://media/picker/authority/media/${Long.MAX_VALUE}"),
                coverMediaSource = testContentProvider.providers[0].mediaSource,
            )

        val firstAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)

        // Check the album media paging source is valid
        assertThat(firstAlbumMediaPagingSource.invalid).isFalse()

        // Check that a cache refresh request was received
        val firstAlbumMediaRefreshRequest = testContentProvider.lastRefreshMediaRequest
        assertThat(firstAlbumMediaRefreshRequest).isNotNull()

        // The active user changes
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = testContentProvider.providers[0].authority,
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        userStatusFlow.update { it.copy(activeContentResolver = updatedContentResolver) }
        advanceTimeBy(100)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // Fetch the album media again
        val secondAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)

        // Check that previous album media source was marked as invalid.
        assertThat(firstAlbumMediaPagingSource.invalid).isTrue()
        assertThat(secondAlbumMediaPagingSource.invalid).isFalse()
        assertThat(secondAlbumMediaPagingSource).isNotEqualTo(firstAlbumMediaPagingSource)

        // Check that a cache refresh request was received again because the album media paging
        // source cache was cleared.
        val secondAlbumMediaRefreshRequest = testContentProvider.lastRefreshMediaRequest
        assertThat(secondAlbumMediaPagingSource).isNotNull()
        assertThat(secondAlbumMediaPagingSource).isNotEqualTo(secondAlbumMediaRefreshRequest)
    }

    @Test
    fun testOnUpdateMediaNotification() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )
        advanceTimeBy(100)

        val firstMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.mediaPagingSource()
        assertThat(firstMediaPagingSource.invalid).isFalse()

        // Check that a cache refresh request was received
        val firstMediaRefreshRequest = testContentProvider.lastRefreshMediaRequest
        assertThat(firstMediaRefreshRequest).isNotNull()

        // Send a media update notification
        notificationService.dispatchChangeToObservers(mediaUpdateUri)
        advanceTimeBy(100)

        // Check that the first media paging source was marked as invalid
        assertThat(firstMediaPagingSource.invalid).isTrue()

        // Check that the a new PagingSource instance was created which is still valid
        val secondMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.mediaPagingSource()
        assertThat(secondMediaPagingSource).isNotEqualTo(firstMediaPagingSource)
        assertThat(secondMediaPagingSource.invalid).isFalse()

        // Check that a cache update request was not received a second time
        val lastMediaRefreshRequest = testContentProvider.lastRefreshMediaRequest
        assertThat(lastMediaRefreshRequest).isEqualTo(firstMediaRefreshRequest)
    }

    @Test
    fun testOnUpdateAlbumMediaNotification() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )
        advanceTimeBy(100)

        // Fetch album media the first time
        val album =
            Group.Album(
                id = testContentProvider.albumMedia.keys.first(),
                pickerId = Long.MAX_VALUE,
                authority = testContentProvider.providers[0].authority,
                dateTakenMillisLong = Long.MAX_VALUE,
                displayName = "album",
                coverUri = Uri.parse("content://media/picker/authority/media/${Long.MAX_VALUE}"),
                coverMediaSource = testContentProvider.providers[0].mediaSource,
            )

        val firstAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)

        // Check the album media paging source is valid
        assertThat(firstAlbumMediaPagingSource.invalid).isFalse()

        // Check that a cache refresh request was received
        val firstAlbumMediaRefreshRequest = testContentProvider.lastRefreshMediaRequest
        assertThat(firstAlbumMediaRefreshRequest).isNotNull()

        // Send a media update notification
        val albumUpdateUri: Uri =
            albumMediaUpdateUri
                .buildUpon()
                .apply {
                    appendPath(album.authority)
                    appendPath(album.id)
                }
                .build()

        notificationService.dispatchChangeToObservers(albumUpdateUri)
        advanceTimeBy(100)

        // Check that the first media paging source was marked as invalid
        assertThat(firstAlbumMediaPagingSource.invalid).isTrue()

        // Check that the a new PagingSource instance was created which is still valid
        val secondAlbumMediaPagingSource: PagingSource<MediaPageKey, Media> =
            dataService.albumMediaPagingSource(album)
        assertThat(secondAlbumMediaPagingSource).isNotEqualTo(firstAlbumMediaPagingSource)
        assertThat(secondAlbumMediaPagingSource.invalid).isFalse()

        // Check that a cache update request was not received a second time
        val lastAlbumMediaRefreshRequest = testContentProvider.lastRefreshMediaRequest
        assertThat(lastAlbumMediaRefreshRequest).isEqualTo(firstAlbumMediaRefreshRequest)
    }

    @Test
    fun testDisruptiveDataUpdate() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        testContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                )
            )

        testFeatureManager =
            FeatureManager(
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        PhotopickerConfiguration(
                            action = "TEST_ACTION",
                            sessionId = sessionId,
                            flags =
                                PhotopickerFlags(
                                    CLOUD_MEDIA_ENABLED = true,
                                    CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                                ),
                        ),
                ),
                this.backgroundScope,
                TestPrefetchDataService(),
                setOf(CloudMediaFeature.Registration),
                setOf<RegisteredEventClass>(),
                setOf<RegisteredEventClass>(),
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        this.backgroundScope,
                        defaultConfiguration =
                            PhotopickerConfiguration(
                                action = "TEST_ACTION",
                                sessionId = sessionId,
                                flags =
                                    PhotopickerFlags(
                                        CLOUD_MEDIA_ENABLED = true,
                                        CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                                    ),
                            ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val availableProviderEmissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(availableProviderEmissions)
        }

        val disruptiveDataUpdateEmissions = mutableListOf<Unit>()
        this.backgroundScope.launch {
            dataService.disruptiveDataUpdateChannel
                .consumeAsFlow()
                .toList(disruptiveDataUpdateEmissions)
        }

        advanceTimeBy(100)

        // Verify init state
        assertThat(availableProviderEmissions.count()).isEqualTo(1)
        assertThat(disruptiveDataUpdateEmissions.count()).isEqualTo(0)

        // Update the available providers
        testContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                ),
                Provider(
                    authority = "cloud_authority",
                    mediaSource = MediaSource.REMOTE,
                    uid = 0,
                    displayName = "",
                ),
            )

        notificationService.dispatchChangeToObservers(availableProvidersUpdateUri)
        advanceTimeBy(100)

        // Verify updated state. Since the new set of available providers is a superset of the
        // previously available providers, this update is not a disruptive data update.
        assertThat(availableProviderEmissions.count()).isEqualTo(2)
        assertThat(disruptiveDataUpdateEmissions.count()).isEqualTo(0)

        // Update the available providers again
        testContentProvider.providers =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                )
            )

        notificationService.dispatchChangeToObservers(availableProvidersUpdateUri)
        advanceTimeBy(100)

        // Verify updated state. Since the new set of available providers is NOT a superset of the
        // previously available providers, this update is a disruptive data update.
        assertThat(availableProviderEmissions.count()).isEqualTo(3)
        assertThat(disruptiveDataUpdateEmissions.count()).isEqualTo(1)
    }

    @Test
    fun testCollectionInfoUpdate() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config = provideTestConfigurationFlow(this.backgroundScope),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val availableProviderEmissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch {
            dataService.availableProviders.toList(availableProviderEmissions)
        }

        advanceTimeBy(100)

        // Verify init state
        assertThat(availableProviderEmissions.count()).isEqualTo(1)
        val collectionInfo = dataService.getCollectionInfo(testContentProvider.providers[0])
        val expectedCollectionInfo =
            collectionInfo.copy(collectionId = "2", accountName = "new@account.name")

        // Update the collection info of the available provider
        testContentProvider.collectionInfos = listOf(expectedCollectionInfo)

        // Send a change notification to the UI
        notificationService.dispatchChangeToObservers(availableProvidersUpdateUri)
        advanceTimeBy(100)

        // Verify that since the available providers did not change, a new value was not emitted.
        assertThat(availableProviderEmissions.count()).isEqualTo(1)

        // Verify that the collection info has been updated.
        val updatedCollectionInfo = dataService.getCollectionInfo(testContentProvider.providers[0])
        assertThat(updatedCollectionInfo).isEqualTo(expectedCollectionInfo)
    }

    @Test
    fun testGetAllAllowedProviders() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )
        val cloudProvider1 =
            Provider(
                "cloud_primary",
                MediaSource.REMOTE,
                /* uid */ 0,
                /* displayName */ "primary cloud provider",
            )
        val cloudProvider2 =
            Provider(
                "cloud_secondary",
                MediaSource.REMOTE,
                /* uid */ 1,
                /* displayName */ "secondary cloud provider",
            )
        val resolveInfo1 = createResolveInfo(cloudProvider1)
        val resolveInfo2 = createResolveInfo(cloudProvider2)

        whenever(mockContext.getPackageManager()) { mockPackageManager }
        whenever(mockPackageManager.queryIntentContentProvidersAsUser(any(), anyInt(), any())) {
            listOf(resolveInfo1, resolveInfo2)
        }

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            PhotopickerConfiguration(
                                action = "test_action",
                                flags =
                                    PhotopickerFlags(
                                        CLOUD_ALLOWED_PROVIDERS =
                                            arrayOf(
                                                cloudProvider1.authority,
                                                cloudProvider2.authority,
                                            ),
                                        CLOUD_ENFORCE_PROVIDER_ALLOWLIST = true,
                                    ),
                                sessionId = sessionId,
                            ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val actualAllAllowedProviders = dataService.getAllAllowedProviders()
        assertThat(actualAllAllowedProviders.count()).isEqualTo(2)
        assertThat(actualAllAllowedProviders[0].authority).isEqualTo(cloudProvider1.authority)
        assertThat(actualAllAllowedProviders[1].authority).isEqualTo(cloudProvider2.authority)
    }

    @Test
    fun testGetAllAllowedProvidersWhenAllowlistIsEnforced() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )
        val cloudProvider1 =
            Provider(
                "cloud_primary",
                MediaSource.REMOTE,
                /* uid */ 0,
                /* displayName */ "primary cloud provider",
            )
        val cloudProvider2 =
            Provider(
                "cloud_secondary",
                MediaSource.REMOTE,
                /* uid */ 1,
                /* displayName */ "secondary cloud provider",
            )
        val resolveInfo1 = createResolveInfo(cloudProvider1)
        val resolveInfo2 = createResolveInfo(cloudProvider2)

        whenever(mockContext.getPackageManager()) { mockPackageManager }
        whenever(mockPackageManager.queryIntentContentProvidersAsUser(any(), anyInt(), any())) {
            listOf(resolveInfo1, resolveInfo2)
        }

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            PhotopickerConfiguration(
                                action = "test_action",
                                flags =
                                    PhotopickerFlags(
                                        CLOUD_ALLOWED_PROVIDERS = arrayOf(cloudProvider1.authority),
                                        CLOUD_ENFORCE_PROVIDER_ALLOWLIST = true,
                                    ),
                                sessionId = sessionId,
                            ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val actualAllAllowedProviders = dataService.getAllAllowedProviders()
        assertThat(actualAllAllowedProviders.count()).isEqualTo(1)
        assertThat(actualAllAllowedProviders[0].authority).isEqualTo(cloudProvider1.authority)
    }

    @Test
    fun testGetAllAllowedProvidersWhenDeviceHasLimitedProviders() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )
        val cloudProvider1 =
            Provider(
                "cloud_primary",
                MediaSource.REMOTE,
                /* uid */ 0,
                /* displayName */ "primary cloud provider",
            )
        val cloudProvider2 =
            Provider(
                "cloud_secondary",
                MediaSource.REMOTE,
                /* uid */ 1,
                /* displayName */ "secondary cloud provider",
            )
        val resolveInfo2 = createResolveInfo(cloudProvider2)

        whenever(mockContext.getPackageManager()) { mockPackageManager }
        whenever(mockPackageManager.queryIntentContentProvidersAsUser(any(), anyInt(), any())) {
            listOf(resolveInfo2)
        }

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            PhotopickerConfiguration(
                                action = "test_action",
                                flags =
                                    PhotopickerFlags(
                                        CLOUD_ALLOWED_PROVIDERS =
                                            arrayOf(
                                                cloudProvider1.authority,
                                                cloudProvider2.authority,
                                            ),
                                        CLOUD_ENFORCE_PROVIDER_ALLOWLIST = true,
                                    ),
                                sessionId = sessionId,
                            ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val actualAllAllowedProviders = dataService.getAllAllowedProviders()
        assertThat(actualAllAllowedProviders.count()).isEqualTo(1)
        assertThat(actualAllAllowedProviders[0].authority).isEqualTo(cloudProvider2.authority)
    }

    @Test
    fun testGetAllAllowedProvidersWhenAllowlistIsNotEnforced() = runTest {
        val userStatusFlow: StateFlow<UserStatus> = MutableStateFlow(userStatus)
        events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope),
                testFeatureManager,
            )
        val cloudProvider1 =
            Provider(
                "cloud_primary",
                MediaSource.REMOTE,
                /* uid */ 0,
                /* displayName */ "primary cloud provider",
            )
        val cloudProvider2 =
            Provider(
                "cloud_secondary",
                MediaSource.REMOTE,
                /* uid */ 1,
                /* displayName */ "secondary cloud provider",
            )
        val resolveInfo1 = createResolveInfo(cloudProvider1)
        val resolveInfo2 = createResolveInfo(cloudProvider2)

        whenever(mockContext.getPackageManager()) { mockPackageManager }
        whenever(mockPackageManager.queryIntentContentProvidersAsUser(any(), anyInt(), any())) {
            listOf(resolveInfo1, resolveInfo2)
        }

        val dataService: DataService =
            DataServiceImpl(
                userStatus = userStatusFlow,
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                config =
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            PhotopickerConfiguration(
                                action = "test_action",
                                flags =
                                    PhotopickerFlags(
                                        CLOUD_ALLOWED_PROVIDERS = arrayOf(),
                                        CLOUD_ENFORCE_PROVIDER_ALLOWLIST = false,
                                    ),
                                sessionId = sessionId,
                            ),
                    ),
                featureManager = testFeatureManager,
                appContext = mockContext,
                events = events,
                processOwnerHandle = userProfilePrimary.handle,
            )

        val actualAllAllowedProviders = dataService.getAllAllowedProviders()
        assertThat(actualAllAllowedProviders.count()).isEqualTo(2)
        assertThat(actualAllAllowedProviders[0].authority).isEqualTo(cloudProvider1.authority)
        assertThat(actualAllAllowedProviders[1].authority).isEqualTo(cloudProvider2.authority)
    }

    private fun createResolveInfo(provider: Provider): ResolveInfo {
        val resolveInfo = ResolveInfo()
        resolveInfo.nonLocalizedLabel = provider.displayName
        resolveInfo.providerInfo = ProviderInfo()
        resolveInfo.providerInfo.authority = provider.authority
        resolveInfo.providerInfo.packageName = provider.authority
        resolveInfo.providerInfo.readPermission =
            CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION
        return resolveInfo
    }
}
