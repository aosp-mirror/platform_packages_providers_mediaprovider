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

package src.com.android.photopicker.features.categorygrid.data

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
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.DataService
import com.android.photopicker.data.DataServiceImpl
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.TestNotificationServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.categorygrid.data.CategoryDataService
import com.android.photopicker.features.categorygrid.data.CategoryDataServiceImpl
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
class CategoryDataServiceImplTest {

    companion object {
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

        private val mediaSetsUpdateUri =
            Uri.parse("content://media/picker_internal/v2/media_sets/update")

        private val mediaSetContentUpdateUri =
            Uri.parse("content://media/picker_internal/v2/media_set_contents/update")
    }

    private lateinit var testFeatureManager: FeatureManager
    private lateinit var testContentProvider: TestMediaProvider
    private lateinit var testContentResolver: ContentResolver
    private lateinit var notificationService: TestNotificationServiceImpl
    private lateinit var mediaProviderClient: MediaProviderClient
    private lateinit var mockContext: Context
    private lateinit var mockPackageManager: PackageManager
    private lateinit var events: Events
    private lateinit var userStatusFlow: MutableStateFlow<UserStatus>

    @Before
    fun setup() {
        val scope = TestScope()
        testContentProvider = TestMediaProvider()
        testContentResolver = ContentResolver.wrap(testContentProvider)
        notificationService = TestNotificationServiceImpl()
        mediaProviderClient = MediaProviderClient()
        mockContext = mock(Context::class.java)
        mockPackageManager = mock(PackageManager::class.java)
        val userStatus =
            UserStatus(
                activeUserProfile = userProfilePrimary,
                allProfiles = listOf(userProfilePrimary),
                activeContentResolver = testContentResolver,
            )
        testFeatureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = scope.backgroundScope),
                scope = scope,
                prefetchDataService = TestPrefetchDataService(),
                registeredFeatures = setOf(),
                coreEventsConsumed = setOf(),
                coreEventsProduced = setOf(),
            )
        userStatusFlow = MutableStateFlow(userStatus)
        events =
            Events(
                scope = scope.backgroundScope,
                provideTestConfigurationFlow(scope.backgroundScope),
                testFeatureManager,
            )
    }

    @Test
    fun testCategoryPagingSourceCacheReuse() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        advanceTimeBy(100)

        val cancellationSignal = CancellationSignal()
        val firstCategoryAndAlbumPagingSource: PagingSource<GroupPageKey, Group> =
            categoryDataService.getCategories()
        assertThat(firstCategoryAndAlbumPagingSource.invalid).isFalse()

        // Check that the older paging source was cached and is reused.
        val secondCategoryAndAlbumPagingSource: PagingSource<GroupPageKey, Group> =
            categoryDataService.getCategories(cancellationSignal = cancellationSignal)
        assertThat(secondCategoryAndAlbumPagingSource).isEqualTo(firstCategoryAndAlbumPagingSource)
        assertThat(cancellationSignal.isCanceled()).isFalse()

        firstCategoryAndAlbumPagingSource.invalidate()
        assertThat(cancellationSignal.isCanceled()).isTrue()
    }

    @Test
    fun testCategoryPagingSourceInvalidation() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        val cancellationSignal = CancellationSignal()
        val firstCategoryAndAlbumPagingSource: PagingSource<GroupPageKey, Group> =
            categoryDataService.getCategories(cancellationSignal = cancellationSignal)
        assertThat(firstCategoryAndAlbumPagingSource.invalid).isFalse()
        assertThat(cancellationSignal.isCanceled()).isFalse()

        updateActiveContentResolver()
        advanceTimeBy(1000)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // Check that the old PagingSource has been invalidated.
        assertThat(firstCategoryAndAlbumPagingSource.invalid).isTrue()

        // Check that the CancellationSignal has been marked as cancelled.
        assertThat(cancellationSignal.isCanceled()).isTrue()

        // Check that the new PagingSource instance is valid.
        val secondCategoryAndAlbumPagingSource: PagingSource<GroupPageKey, Group> =
            categoryDataService.getCategories()
        assertThat(secondCategoryAndAlbumPagingSource.invalid).isFalse()
    }

    @Test
    fun testMediaSetsPagingSourceCacheReuse() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        advanceTimeBy(100)

        val cancellationSignal = CancellationSignal()
        val firstMediaSetsPagingSource: PagingSource<GroupPageKey, Group.MediaSet> =
            categoryDataService.getMediaSets(testContentProvider.parentCategory)
        assertThat(firstMediaSetsPagingSource.invalid).isFalse()

        // Check that the older paging source was cached and is reused.
        val secondMediaSetsPagingSource: PagingSource<GroupPageKey, Group.MediaSet> =
            categoryDataService.getMediaSets(testContentProvider.parentCategory, cancellationSignal)
        assertThat(secondMediaSetsPagingSource).isEqualTo(firstMediaSetsPagingSource)
        assertThat(cancellationSignal.isCanceled()).isFalse()

        firstMediaSetsPagingSource.invalidate()
        assertThat(cancellationSignal.isCanceled()).isTrue()
    }

    @Test
    fun testMediaSetsUpdateNotification() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        advanceTimeBy(100)

        val firstMediaSetsPagingSource: PagingSource<GroupPageKey, Group.MediaSet> =
            categoryDataService.getMediaSets(testContentProvider.parentCategory)
        assertThat(firstMediaSetsPagingSource.invalid).isFalse()

        val updateUri: Uri =
            mediaSetsUpdateUri
                .buildUpon()
                .apply { appendPath(testContentProvider.parentCategory.id) }
                .build()

        // Dispatch update notification
        notificationService.dispatchChangeToObservers(updateUri)
        advanceTimeBy(100)

        // Check that the first media paging source was marked as invalid
        assertThat(firstMediaSetsPagingSource.invalid).isTrue()

        val secondMediaSetsPagingSource: PagingSource<GroupPageKey, Group.MediaSet> =
            categoryDataService.getMediaSets(testContentProvider.parentCategory)
        assertThat(secondMediaSetsPagingSource).isNotEqualTo(firstMediaSetsPagingSource)
        assertThat(secondMediaSetsPagingSource.invalid).isFalse()
    }

    @Test
    fun testMediaSetsPagingSourceInvalidation() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        val cancellationSignal = CancellationSignal()
        val firstMediaSetsPagingSource: PagingSource<GroupPageKey, Group.MediaSet> =
            categoryDataService.getMediaSets(testContentProvider.parentCategory, cancellationSignal)
        assertThat(firstMediaSetsPagingSource.invalid).isFalse()
        assertThat(cancellationSignal.isCanceled()).isFalse()

        updateActiveContentResolver()
        advanceTimeBy(1000)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // Check that the old PagingSource has been invalidated.
        assertThat(firstMediaSetsPagingSource.invalid).isTrue()

        // Check that the CancellationSignal has been marked as cancelled.
        assertThat(cancellationSignal.isCanceled()).isTrue()

        // Check that the new PagingSource instance is valid.
        val secondMediaSetsPagingSource: PagingSource<GroupPageKey, Group.MediaSet> =
            categoryDataService.getMediaSets(testContentProvider.parentCategory)
        assertThat(secondMediaSetsPagingSource.invalid).isFalse()
    }

    @Test
    fun testMediaSetContentsPagingSourceCacheReuse() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        advanceTimeBy(100)

        val cancellationSignal = CancellationSignal()
        val firstMediaSetContentsPagingSource: PagingSource<MediaPageKey, Media> =
            categoryDataService.getMediaSetContents(testContentProvider.mediaSets[0])
        assertThat(firstMediaSetContentsPagingSource.invalid).isFalse()

        // Check that the older paging source was cached and is reused.
        val secondMediaSetContentsPagingSource: PagingSource<MediaPageKey, Media> =
            categoryDataService.getMediaSetContents(
                testContentProvider.mediaSets[0].copy(),
                cancellationSignal,
            )
        assertThat(secondMediaSetContentsPagingSource).isEqualTo(firstMediaSetContentsPagingSource)
        assertThat(cancellationSignal.isCanceled()).isFalse()

        firstMediaSetContentsPagingSource.invalidate()
        assertThat(cancellationSignal.isCanceled()).isTrue()
    }

    @Test
    fun testMediaSetContentUpdateNotification() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        advanceTimeBy(100)

        val firstMediaSetContentsPagingSource: PagingSource<MediaPageKey, Media> =
            categoryDataService.getMediaSetContents(testContentProvider.mediaSets[0])
        assertThat(firstMediaSetContentsPagingSource.invalid).isFalse()

        val updateUri: Uri =
            mediaSetContentUpdateUri
                .buildUpon()
                .apply { appendPath(testContentProvider.mediaSets[0].id) }
                .build()
        // Dispatch update notification
        notificationService.dispatchChangeToObservers(updateUri)
        advanceTimeBy(100)

        // Check that the first media paging source was marked as invalid
        assertThat(firstMediaSetContentsPagingSource.invalid).isTrue()

        // Check that the new PagingSource instance is valid.
        val secondMediaSetContentsPagingSource: PagingSource<MediaPageKey, Media> =
            categoryDataService.getMediaSetContents(testContentProvider.mediaSets[0])
        assertThat(secondMediaSetContentsPagingSource.invalid).isFalse()
    }

    @Test
    fun testMediaSetContentsPagingSourceInvalidation() = runTest {
        val dataService = getDataService(this)
        val categoryDataService = getCategoryDataService(this, dataService)

        val emissions = mutableListOf<List<Provider>>()
        this.backgroundScope.launch { dataService.availableProviders.toList(emissions) }
        advanceTimeBy(100)

        assertThat(emissions.count()).isEqualTo(1)

        val cancellationSignal = CancellationSignal()
        val firstMediaSetContentsPagingSource: PagingSource<MediaPageKey, Media> =
            categoryDataService.getMediaSetContents(
                testContentProvider.mediaSets[0],
                cancellationSignal,
            )
        assertThat(firstMediaSetContentsPagingSource.invalid).isFalse()
        assertThat(cancellationSignal.isCanceled()).isFalse()

        updateActiveContentResolver()
        advanceTimeBy(1000)

        // Since the active user has changed, this should trigger a re-fetch of the active
        // providers.
        assertThat(emissions.count()).isEqualTo(2)

        // Check that the old PagingSource has been invalidated.
        assertThat(firstMediaSetContentsPagingSource.invalid).isTrue()

        // Check that the CancellationSignal has been marked as cancelled.
        assertThat(cancellationSignal.isCanceled()).isTrue()

        // Check that the new PagingSource instance is valid.
        val secondMediaSetContentsPagingSource: PagingSource<MediaPageKey, Media> =
            categoryDataService.getMediaSetContents(testContentProvider.mediaSets[0])
        assertThat(secondMediaSetContentsPagingSource.invalid).isFalse()
    }

    private fun getDataService(scope: TestScope): DataService {
        return DataServiceImpl(
            userStatus = userStatusFlow,
            scope = scope.backgroundScope,
            notificationService = notificationService,
            mediaProviderClient = mediaProviderClient,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            config = provideTestConfigurationFlow(scope.backgroundScope),
            featureManager = testFeatureManager,
            appContext = mockContext,
            events = events,
            processOwnerHandle = userProfilePrimary.handle,
        )
    }

    private fun getCategoryDataService(
        scope: TestScope,
        dataService: DataService,
    ): CategoryDataService {
        return CategoryDataServiceImpl(
            dataService = dataService,
            config = provideTestConfigurationFlow(scope.backgroundScope),
            scope = scope.backgroundScope,
            notificationService = notificationService,
            mediaProviderClient = mediaProviderClient,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            events = events,
        )
    }

    private fun updateActiveContentResolver() {
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
    }
}
