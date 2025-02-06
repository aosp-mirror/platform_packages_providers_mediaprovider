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

package com.android.photopicker.tests.core.events

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.UserProperties
import android.net.Uri
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.events.dispatchPhotopickerExpansionStateChangedEvent
import com.android.photopicker.core.events.dispatchReportPhotopickerApiInfoEvent
import com.android.photopicker.core.events.dispatchReportPhotopickerMediaItemStatusEvent
import com.android.photopicker.core.events.dispatchReportPhotopickerSessionInfoEvent
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.search.SearchFeature
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock

/** Unit tests for the telemetry event dispatchers */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DispatchersTest {
    private val sessionId = generatePickerSessionId()
    private val packageUid = 12345

    private val photopickerConfiguration =
        TestPhotopickerConfiguration.build {
            action(value = "")
            sessionId(value = sessionId)
            callingPackageUid(value = packageUid)
            runtimeEnv(value = PhotopickerRuntimeEnv.EMBEDDED)
            mimeTypes(arrayListOf("image/jpeg"))
        }
    private val mediaItemAlbum =
        Group.Album(
            id = "",
            pickerId = 0L,
            authority = "",
            dateTakenMillisLong = 0L,
            displayName = "",
            coverUri = Uri.EMPTY,
            coverMediaSource = MediaSource.LOCAL,
        )
    private val mediaItem =
        Media.Image(
            mediaId = "",
            pickerId = 0L,
            index = 9999,
            authority = "",
            mediaSource = MediaSource.LOCAL,
            mediaUri = Uri.EMPTY,
            glideLoadableUri = Uri.EMPTY,
            dateTakenMillisLong = 0L,
            sizeInBytes = 0,
            mimeType = "image/jpeg",
            standardMimeTypeExtension = 0,
            selectionSource = Telemetry.MediaLocation.MAIN_GRID,
            mediaItemAlbum = mediaItemAlbum,
        )

    private val lazyDataService: Lazy<DataService> = Lazy { TestDataServiceImpl() }

    private lateinit var lazyEvents: Lazy<Events>
    private lateinit var eventsDispatched: MutableList<Event>
    private lateinit var lazyUserMonitor: Lazy<UserMonitor>
    private lateinit var lazyMediaSelection: Lazy<Selection<Media>>
    private lateinit var lazyFeatureManager: Lazy<FeatureManager>

    private fun setup(testScope: TestScope) {
        val backgroundScope = testScope.backgroundScope
        val testScheduler = testScope.testScheduler

        val photopickerConfigurationStateFlow =
            provideTestConfigurationFlow(
                scope = backgroundScope,
                defaultConfiguration = photopickerConfiguration,
            )
        val featureManager =
            FeatureManager(
                configuration = photopickerConfigurationStateFlow,
                scope = backgroundScope,
                prefetchDataService = TestPrefetchDataService(),
            )
        lazyFeatureManager = Lazy { featureManager }

        val events =
            Events(
                scope = backgroundScope,
                configuration = photopickerConfigurationStateFlow,
                featureManager = featureManager,
            )
        lazyEvents = Lazy { events }

        eventsDispatched = mutableListOf()
        backgroundScope.launch { events.flow.toList(eventsDispatched) }

        val mockContext = mock(Context::class.java)

        val mockUserManager = mock(UserManager::class.java)
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }

        if (SdkLevel.isAtLeastV()) {
            whenever(mockUserManager.getUserProperties(any(UserHandle::class.java))) {
                UserProperties.Builder().build()
            }
            whenever(mockUserManager.getUserBadge()) {
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .resources
                    .getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(mockUserManager.getProfileLabel()) { "label" }
        }

        whenever(mockContext.packageManager) { mock(PackageManager::class.java) }
        whenever(mockContext.contentResolver) { mock(ContentResolver::class.java) }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }

        // Get primaryUserHandle: UserHandle
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(/* primary user id */ 0)
        parcel1.setDataPosition(0)
        val primaryUserHandle = UserHandle(parcel1)
        parcel1.recycle()

        val userMonitor =
            UserMonitor(
                context = mockContext,
                configuration = photopickerConfigurationStateFlow,
                scope = backgroundScope,
                dispatcher = StandardTestDispatcher(testScheduler),
                processOwnerUserHandle = primaryUserHandle,
            )
        lazyUserMonitor = Lazy { userMonitor }

        val selection =
            SelectionImpl(
                scope = backgroundScope,
                configuration = photopickerConfigurationStateFlow,
                preSelectedMedia = lazyDataService.get().preSelectionMediaData,
            )
        lazyMediaSelection = Lazy { selection }
    }

    @Test
    fun testDispatchPhotopickerExpansionStateChangedEvent_isExpanded() = runTest {
        // Setup
        setup(testScope = this)

        val expectedEvent =
            Event.LogPhotopickerUIEvent(
                dispatcherToken = FeatureToken.CORE.token,
                sessionId = sessionId,
                packageUid = packageUid,
                uiEvent = Telemetry.UiEvent.EXPAND_PICKER,
            )

        // Action
        dispatchPhotopickerExpansionStateChangedEvent(
            coroutineScope = backgroundScope,
            lazyEvents = lazyEvents,
            photopickerConfiguration = photopickerConfiguration,
            isExpanded = true,
        )
        advanceTimeBy(delayTimeMillis = 50)

        // Assert
        assertThat(eventsDispatched).contains(expectedEvent)
    }

    @Test
    fun testDispatchPhotopickerExpansionStateChangedEvent_isCollapsed() = runTest {
        // Setup
        setup(testScope = this)

        val expectedEvent =
            Event.LogPhotopickerUIEvent(
                dispatcherToken = FeatureToken.CORE.token,
                sessionId = sessionId,
                packageUid = packageUid,
                uiEvent = Telemetry.UiEvent.COLLAPSE_PICKER,
            )

        // Action
        dispatchPhotopickerExpansionStateChangedEvent(
            coroutineScope = backgroundScope,
            lazyEvents = lazyEvents,
            photopickerConfiguration = photopickerConfiguration,
            isExpanded = false,
        )
        advanceTimeBy(delayTimeMillis = 50)

        // Assert
        assertThat(eventsDispatched).contains(expectedEvent)
    }

    @Test
    fun testDispatchReportPhotopickerMediaItemStatusEvent_selected() = runTest {
        // Setup
        setup(testScope = this)

        val expectedEvent =
            Event.ReportPhotopickerMediaItemStatus(
                dispatcherToken = FeatureToken.CORE.token,
                sessionId = sessionId,
                mediaStatus = Telemetry.MediaStatus.SELECTED,
                selectionSource = Telemetry.MediaLocation.MAIN_GRID,
                itemPosition = 9999,
                selectedAlbum = mediaItemAlbum,
                mediaType = Telemetry.MediaType.PHOTO,
                cloudOnly = false,
                pickerSize = Telemetry.PickerSize.UNSET_PICKER_SIZE,
            )

        // Action
        dispatchReportPhotopickerMediaItemStatusEvent(
            coroutineScope = backgroundScope,
            lazyEvents = lazyEvents,
            photopickerConfiguration = photopickerConfiguration,
            mediaItem = mediaItem,
            mediaStatus = Telemetry.MediaStatus.SELECTED,
        )
        advanceTimeBy(delayTimeMillis = 50)

        // Assert
        assertThat(eventsDispatched).contains(expectedEvent)
    }

    @Test
    fun testDispatchReportPhotopickerMediaItemStatusEvent_unselected() = runTest {
        // Setup
        setup(testScope = this)

        val expectedEvent =
            Event.ReportPhotopickerMediaItemStatus(
                dispatcherToken = FeatureToken.CORE.token,
                sessionId = sessionId,
                mediaStatus = Telemetry.MediaStatus.UNSELECTED,
                selectionSource = Telemetry.MediaLocation.MAIN_GRID,
                itemPosition = 9999,
                selectedAlbum = mediaItemAlbum,
                mediaType = Telemetry.MediaType.PHOTO,
                cloudOnly = false,
                pickerSize = Telemetry.PickerSize.UNSET_PICKER_SIZE,
            )

        // Action
        dispatchReportPhotopickerMediaItemStatusEvent(
            coroutineScope = backgroundScope,
            lazyEvents = lazyEvents,
            photopickerConfiguration = photopickerConfiguration,
            mediaItem = mediaItem,
            mediaStatus = Telemetry.MediaStatus.UNSELECTED,
        )
        advanceTimeBy(delayTimeMillis = 50)

        // Assert
        assertThat(eventsDispatched).contains(expectedEvent)
    }

    @Test
    fun testDispatchReportPhotopickerSessionInfoEvent() = runTest {
        // Setup
        setup(testScope = this)

        val pickerStatus = Telemetry.PickerStatus.OPENED
        val pickerCloseMethod = Telemetry.PickerCloseMethod.SWIPE_DOWN

        val expectedEvent =
            Event.ReportPhotopickerSessionInfo(
                dispatcherToken = FeatureToken.CORE.token,
                sessionId = sessionId,
                packageUid = packageUid,
                pickerSelection = Telemetry.PickerSelection.SINGLE,
                cloudProviderUid = -1,
                userProfile = Telemetry.UserProfile.PERSONAL,
                pickerStatus = pickerStatus,
                pickedItemsCount = 0,
                pickedItemsSize = 0,
                profileSwitchButtonVisible = false,
                pickerMode = Telemetry.PickerMode.EMBEDDED_PICKER,
                pickerCloseMethod = pickerCloseMethod,
            )

        // Action
        dispatchReportPhotopickerSessionInfoEvent(
            coroutineScope = backgroundScope,
            lazyEvents = lazyEvents,
            photopickerConfiguration = photopickerConfiguration,
            lazyDataService = lazyDataService,
            lazyUserMonitor = lazyUserMonitor,
            lazyMediaSelection = lazyMediaSelection,
            pickerStatus = pickerStatus,
            pickerCloseMethod = pickerCloseMethod,
        )
        advanceTimeBy(delayTimeMillis = 50)

        // Assert
        assertThat(eventsDispatched).contains(expectedEvent)
    }

    @Test
    fun testDispatchReportPhotopickerApiInfoEvent() = runTest {
        // Setup
        setup(testScope = this)

        val pickerIntentAction = Telemetry.PickerIntentAction.ACTION_PICK_IMAGES
        val cloudSearch = lazyFeatureManager.get().isFeatureEnabled(SearchFeature::class.java)

        val expectedEvent =
            Event.ReportPhotopickerApiInfo(
                dispatcherToken = FeatureToken.CORE.token,
                sessionId = sessionId,
                pickerIntentAction = pickerIntentAction,
                pickerSize = Telemetry.PickerSize.COLLAPSED,
                mediaFilter = Telemetry.MediaType.PHOTO,
                maxPickedItemsCount = 1,
                selectedTab = Telemetry.SelectedTab.UNSET_SELECTED_TAB,
                selectedAlbum = Telemetry.SelectedAlbum.UNSET_SELECTED_ALBUM,
                isOrderedSelectionSet = false,
                isAccentColorSet = false,
                isDefaultTabSet = false,
                isCloudSearchEnabled = cloudSearch,
                isLocalSearchEnabled = false,
            )

        // Action
        dispatchReportPhotopickerApiInfoEvent(
            coroutineScope = backgroundScope,
            lazyEvents = lazyEvents,
            photopickerConfiguration = photopickerConfiguration,
            pickerIntentAction = pickerIntentAction,
            lazyFeatureManager = lazyFeatureManager,
        )
        advanceTimeBy(delayTimeMillis = 50)

        // Assert
        assertThat(eventsDispatched).contains(expectedEvent)
    }
}
