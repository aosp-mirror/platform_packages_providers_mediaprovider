/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.features.photogrid

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserProperties
import android.net.Uri
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerManagerImpl
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.database.DatabaseManagerTestImpl
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken.PHOTO_GRID
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGridViewModelTest {

    private val USER_ID_PRIMARY: Int = 0
    private val USER_HANDLE_PRIMARY: UserHandle
    private val PLATFORM_PROVIDED_PROFILE_LABEL = "Platform Label"
    private val deviceConfigProxy = TestDeviceConfigProxyImpl()
    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockContentResolver: ContentResolver

    init {
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)
        parcel1.recycle()
    }

    val mediaItem =
        Media.Image(
            mediaId = "id",
            pickerId = 1000L,
            authority = "a",
            mediaSource = MediaSource.LOCAL,
            mediaUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("media")
                        path("picker")
                        path("a")
                        path("id")
                    }
                    .build(),
            glideLoadableUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("id")
                    }
                    .build(),
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "image/png",
            standardMimeTypeExtension = 1,
        )
    val updatedMediaItem =
        mediaItem.copy(mediaItemAlbum = null, selectionSource = Telemetry.MediaLocation.MAIN_GRID)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        deviceConfigProxy.reset()
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()

        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.contentResolver) { mockContentResolver }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }

        // Initial setup state: Two profiles (Personal/Work), both enabled
        whenever(mockUserManager.userProfiles) { listOf(USER_HANDLE_PRIMARY) }

        // Default responses for relevant UserManager apis
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_PRIMARY)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_PRIMARY)) { false }

        val mockResolveInfo = mock(ResolveInfo::class.java)
        whenever(mockResolveInfo.isCrossProfileIntentForwarderActivity()) { true }
        whenever(mockPackageManager.queryIntentActivities(any(Intent::class.java), anyInt())) {
            listOf(mockResolveInfo)
        }

        if (SdkLevel.isAtLeastV()) {
            whenever(mockUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(mockUserManager.getProfileLabel()) { PLATFORM_PROVIDED_PROFILE_LABEL }
            whenever(mockUserManager.getUserProperties(USER_HANDLE_PRIMARY)) {
                UserProperties.Builder().build()
            }
        }
    }

    @Test
    fun testPhotoGridItemClickedUpdatesSelection() {

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = this.backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            val featureManager =
                FeatureManager(
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    scope = this.backgroundScope,
                )

            val events =
                Events(
                    scope = this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager = featureManager,
                )

            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    generatePickerSessionId(),
                )

            val userMonitor =
                UserMonitor(
                    mockContext,
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            TestPhotopickerConfiguration.build {
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ),
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY,
                )

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = DatabaseManagerTestImpl(),
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
                )

            val viewModel =
                PhotoGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestDataServiceImpl(),
                    events,
                    bannerManager,
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            viewModel.handleGridItemSelection(mediaItem, "")

            // Wait for selection update.
            advanceTimeBy(100)

            // The selected media item gets updated with the Selectable interface values
            assertWithMessage("Selection did not contain expected item")
                .that(selection.snapshot())
                .contains(updatedMediaItem)

            // Toggle the item out of the selection
            viewModel.handleGridItemSelection(mediaItem, "")

            advanceTimeBy(100)

            assertWithMessage("Selection contains unexpected item")
                .that(selection.snapshot())
                .doesNotContain(updatedMediaItem)
        }
    }

    @Test
    fun testShowsToastWhenSelectionFull() {

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = this.backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = this.backgroundScope,
                            defaultConfiguration =
                                TestPhotopickerConfiguration.build {
                                    action("TEST_ACTION")
                                    intent(null)
                                    selectionLimit(0)
                                },
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            val featureManager =
                FeatureManager(
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    scope = this.backgroundScope,
                    coreEventsConsumed = setOf<RegisteredEventClass>(),
                    coreEventsProduced = setOf<RegisteredEventClass>(),
                )

            val events =
                Events(
                    scope = this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager = featureManager,
                )

            val eventsDispatched = mutableListOf<Event>()
            backgroundScope.launch { events.flow.toList(eventsDispatched) }

            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    generatePickerSessionId(),
                )

            val userMonitor =
                UserMonitor(
                    mockContext,
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            TestPhotopickerConfiguration.build {
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ),
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY,
                )

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = DatabaseManagerTestImpl(),
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
                )

            val viewModel =
                PhotoGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestDataServiceImpl(),
                    events,
                    bannerManager,
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            val errorMessage = "test"
            viewModel.handleGridItemSelection(mediaItem, errorMessage)
            // Wait for selection update.
            advanceTimeBy(100)

            assertWithMessage("Snackbar event was not dispatched when selection failed")
                .that(eventsDispatched)
                .contains(Event.ShowSnackbarMessage(PHOTO_GRID.token, errorMessage))
        }
    }

    @Test
    fun testPhotoGridBannerDismissedHandler() {

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = this.backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            val featureManager =
                FeatureManager(
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    scope = this.backgroundScope,
                )

            val events =
                Events(
                    scope = this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager = featureManager,
                )

            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    generatePickerSessionId(),
                )

            val userMonitor =
                UserMonitor(
                    mockContext,
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            TestPhotopickerConfiguration.build {
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ),
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY,
                )

            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
                )

            val viewModel =
                PhotoGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestDataServiceImpl(),
                    events,
                    bannerManager,
                )

            viewModel.markBannerAsDismissed(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT)
            advanceTimeBy(100)
            verify(databaseManager.bannerState)
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id,
                        uid = 0,
                        dismissed = true,
                    )
                )
        }
    }
}
