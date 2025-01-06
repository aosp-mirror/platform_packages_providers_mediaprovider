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

package com.android.photopicker.features.search

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.os.UserHandle
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.DataService
import com.android.photopicker.data.DataServiceImpl
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.TestNotificationServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.search.data.SearchDataServiceImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.Mockito.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchDataServiceImplTest {

    companion object {
        private val searchMediaUpdateUri =
            Uri.parse("content://media/picker_internal/v2/search_media/update")

        private fun createUserHandle(userId: Int = 0): UserHandle {
            val parcel = Parcel.obtain()
            parcel.writeInt(userId)
            parcel.setDataPosition(0)
            val userHandle = UserHandle(parcel)
            parcel.recycle()
            return userHandle
        }

        private val userProfilePrimary: UserProfile =
            UserProfile(handle = createUserHandle(0), profileType = UserProfile.ProfileType.PRIMARY)
    }

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
    fun testSearchPagingSourceInvalidation() = runTest {
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

        val searchDataService: SearchDataService =
            SearchDataServiceImpl(
                dataService = dataService,
                userStatus = userStatusFlow,
                photopickerConfiguration = provideTestConfigurationFlow(this.backgroundScope),
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                events = events,
            )

        val searchText: String = "search_query"
        val cancellationSignal = CancellationSignal()

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        val firstSearchResultsPagingSource: PagingSource<MediaPageKey, Media> =
            searchDataService.getSearchResults(searchText = searchText, cancellationSignal)
        assertThat(firstSearchResultsPagingSource.invalid).isFalse()
        assertThat(cancellationSignal.isCanceled()).isFalse()

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

        // Check that the old PagingSource has been invalidated.
        assertThat(firstSearchResultsPagingSource.invalid).isTrue()

        // Check that the CancellationSignal has been marked as cancelled.
        assertThat(cancellationSignal.isCanceled()).isTrue()

        // Check that the new PagingSource instance is valid.
        val secondSearchResultsPagingSource: PagingSource<MediaPageKey, Media> =
            searchDataService.getSearchResults(searchText)
        assertThat(secondSearchResultsPagingSource.invalid).isFalse()
    }

    @Test
    fun testOnUpdateSearchResultsNotification() = runTest {
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

        val searchDataService: SearchDataService =
            SearchDataServiceImpl(
                dataService = dataService,
                userStatus = userStatusFlow,
                photopickerConfiguration = provideTestConfigurationFlow(this.backgroundScope),
                scope = this.backgroundScope,
                notificationService = notificationService,
                mediaProviderClient = mediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                events = events,
            )

        advanceTimeBy(100)

        val searchText: String = "search_query"
        val cancellationSignal = CancellationSignal()
        val firstSearchResultsPagingSource: PagingSource<MediaPageKey, Media> =
            searchDataService.getSearchResults(searchText = searchText, cancellationSignal)
        assertThat(firstSearchResultsPagingSource.invalid).isFalse()

        val searchResultsUpdateUri: Uri =
            searchMediaUpdateUri
                .buildUpon()
                .apply { appendPath(testContentProvider.searchRequestId.toString()) }
                .build()
        // Send an update notification
        notificationService.dispatchChangeToObservers(searchResultsUpdateUri)
        advanceTimeBy(100)

        // Check that the first media paging source was marked as invalid
        assertThat(firstSearchResultsPagingSource.invalid).isTrue()

        // Check that the a new PagingSource instance was created which is still valid
        val secondSearchResultsPagingSource: PagingSource<MediaPageKey, Media> =
            searchDataService.getSearchResults(searchText = searchText, cancellationSignal)
        assertThat(secondSearchResultsPagingSource).isNotEqualTo(firstSearchResultsPagingSource)
        assertThat(secondSearchResultsPagingSource.invalid).isFalse()
    }
}
