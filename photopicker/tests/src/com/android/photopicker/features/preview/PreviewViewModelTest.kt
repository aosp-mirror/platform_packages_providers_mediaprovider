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

package com.android.photopicker.features.preview

import android.content.ContentProvider
import android.content.ContentResolver.EXTRA_SIZE
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_BUFFERING
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_COMPLETED
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_PERMANENT_FAILURE
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED
import android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_STATE_CALLBACK
import android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER
import android.provider.ICloudMediaSurfaceController
import android.provider.ICloudMediaSurfaceStateChangedCallback
import android.test.mock.MockContentResolver
import android.view.Surface
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.utils.mockito.capture
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.nonNullableEq
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.isNull
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockContentProvider: ContentProvider
    @Mock lateinit var mockController: ICloudMediaSurfaceController.Stub
    @Captor lateinit var controllerBundle: ArgumentCaptor<Bundle>

    private lateinit var mockContentResolver: MockContentResolver

    private val USER_HANDLE_PRIMARY: UserHandle
    private val USER_ID_PRIMARY: Int = 0

    init {
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)
    }

    val TEST_MEDIA_IMAGE =
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
                        authority(MockContentProviderWrapper.AUTHORITY)
                        path("id")
                    }
                    .build(),
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "image/png",
            standardMimeTypeExtension = 1,
        )

    val TEST_MEDIA_VIDEO =
        Media.Video(
            mediaId = "video_id",
            pickerId = 987654321L,
            authority = MockContentProviderWrapper.AUTHORITY,
            mediaSource = MediaSource.LOCAL,
            mediaUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("video_id")
                    }
                    .build(),
            glideLoadableUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("video_id")
                    }
                    .build(),
            dateTakenMillisLong = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) * 1000,
            sizeInBytes = 1000L,
            mimeType = "video/mp4",
            standardMimeTypeExtension = 1,
            duration = 10000,
        )

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) {
            InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo()
        }
        mockContentResolver = MockContentResolver(mockContext)
        val provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)

        // Stubs for UserMonitor
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.contentResolver) { mockContentResolver }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }

        // Stubs for creating the RemoteSurfaceController
        whenever(
            mockContentProvider.call(
                /*authority= */ nonNullableEq(MockContentProviderWrapper.AUTHORITY),
                /*method=*/ nonNullableEq(METHOD_CREATE_SURFACE_CONTROLLER),
                /*arg=*/ isNull(),
                /*extras=*/ capture(controllerBundle),
            )
        ) {
            bundleOf(EXTRA_SURFACE_CONTROLLER to mockController)
        }
    }

    /** Ensures the view model can toggle items in the session selection. */
    @Test
    fun testToggleInSelectionUpdatesSelection() {

        runTest {
            val selection = Selection<Media>(scope = this.backgroundScope)

            val viewModel =
                PreviewViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            viewModel.toggleInSelection(TEST_MEDIA_IMAGE)

            // Wait for selection update.
            advanceTimeBy(100)

            assertWithMessage("Selection did not contain expected item")
                .that(selection.snapshot())
                .contains(TEST_MEDIA_IMAGE)

            // Toggle the item out of the selection
            viewModel.toggleInSelection(TEST_MEDIA_IMAGE)

            advanceTimeBy(100)

            assertWithMessage("Selection contains unexpected item")
                .that(selection.snapshot())
                .doesNotContain(TEST_MEDIA_IMAGE)
        }
    }

    /** Ensures the selection is not snapshotted until requested. */
    @Test
    fun testSnapshotSelection() {

        runTest {
            val selection = Selection<Media>(scope = this.backgroundScope, setOf(TEST_MEDIA_IMAGE))

            val viewModel =
                PreviewViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            var snapshot = viewModel.selectionSnapshot.first()

            assertWithMessage("Selection snapshot did not match expected")
                .that(snapshot)
                .isEqualTo(emptySet<Media>())

            viewModel.takeNewSelectionSnapshot()

            // Wait for snapshot
            advanceTimeBy(100)

            snapshot = viewModel.selectionSnapshot.first()

            assertWithMessage("Selection snapshot did not match expected")
                .that(snapshot)
                .isEqualTo(setOf(TEST_MEDIA_IMAGE))
        }
    }

    /** Ensures the creation parameters of remote surface controllers. */
    @Test
    fun testRemotePreviewControllerCreation() {

        runTest {
            val selection = Selection<Media>(scope = this.backgroundScope, setOf(TEST_MEDIA_IMAGE))
            val viewModel =
                PreviewViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            val controller =
                viewModel.getControllerForAuthority(MockContentProviderWrapper.AUTHORITY)

            assertWithMessage("Returned controller was not expected to be null")
                .that(controller)
                .isNotNull()

            verify(mockContentProvider)
                .call(
                    /*authority=*/ anyString(),
                    /*method=*/ nonNullableEq(METHOD_CREATE_SURFACE_CONTROLLER),
                    /*arg=*/ isNull(),
                    /*extras=*/ any(Bundle::class.java),
                )

            val bundle = controllerBundle.getValue()
            assertWithMessage("SurfaceStateChangedCallback was not provided")
                .that(bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK))
                .isNotNull()
            assertWithMessage("Surface controller was not looped by default")
                // Default value from bundle is false so this fails if it wasn't set
                .that(bundle.getBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED, false))
                .isTrue()
            assertWithMessage("Surface controller was not muted by default")
                // Default value from bundle is false so this fails if it wasn't set
                .that(bundle.getBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, false))
                .isTrue()
        }
    }

    /** Ensures that remote preview controllers are cached for authorities. */
    @Test
    fun testRemotePreviewControllersAreCached() {

        runTest {
            val selection = Selection<Media>(scope = this.backgroundScope, setOf(TEST_MEDIA_IMAGE))
            val viewModel =
                PreviewViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            val controller =
                viewModel.getControllerForAuthority(MockContentProviderWrapper.AUTHORITY)
            val controllerTwo =
                viewModel.getControllerForAuthority(MockContentProviderWrapper.AUTHORITY)

            assertWithMessage("Returned controller was not expected to be null")
                .that(controller)
                .isNotNull()

            assertWithMessage("Returned controller was not expected to be null")
                .that(controllerTwo)
                .isNotNull()

            assertWithMessage("Expected both controller instances to be the same")
                .that(controller)
                .isEqualTo(controllerTwo)

            verify(mockContentProvider, times(1))
                .call(
                    /*authority=*/ anyString(),
                    /*method=*/ nonNullableEq(METHOD_CREATE_SURFACE_CONTROLLER),
                    /*arg=*/ isNull(),
                    /*extras=*/ any(Bundle::class.java),
                )
        }
    }

    /** Ensures that remote preview controllers are destroyed when the view model is cleared. */
    @Test
    fun testRemotePreviewControllersAreDestroyed() {

        runTest {
            // Setup a proxy to call the mocked controller, since IBinder uses onTransact under the
            // hood and that is more complicated to verify.
            val controllerProxy =
                object : ICloudMediaSurfaceController.Stub() {

                    override fun onSurfaceCreated(
                        surfaceId: Int,
                        surface: Surface,
                        mediaId: String
                    ) {}

                    override fun onSurfaceChanged(
                        surfaceId: Int,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {}

                    override fun onSurfaceDestroyed(surfaceId: Int) {}
                    override fun onMediaPlay(surfaceId: Int) {}
                    override fun onMediaPause(surfaceId: Int) {}
                    override fun onMediaSeekTo(surfaceId: Int, timestampMillis: Long) {}
                    override fun onConfigChange(bundle: Bundle) {}
                    override fun onDestroy() {
                        mockController.onDestroy()
                    }
                    override fun onPlayerCreate() {}
                    override fun onPlayerRelease() {}
                }

            whenever(
                mockContentProvider.call(
                    /*authority= */ nonNullableEq(MockContentProviderWrapper.AUTHORITY),
                    /*method=*/ nonNullableEq(METHOD_CREATE_SURFACE_CONTROLLER),
                    /*arg=*/ isNull(),
                    /*extras=*/ capture(controllerBundle),
                )
            ) {
                bundleOf(EXTRA_SURFACE_CONTROLLER to controllerProxy)
            }
            val selection = Selection<Media>(scope = this.backgroundScope, setOf(TEST_MEDIA_IMAGE))
            val viewModel =
                PreviewViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            viewModel.getControllerForAuthority(MockContentProviderWrapper.AUTHORITY)

            viewModel.callOnCleared()
            verify(mockController).onDestroy()
        }
    }

    /** Ensures that surface playback updates are emitted. */
    @Test
    fun testRemotePreviewSurfaceStateChangedCallbackEmitsUpdates() {

        runTest {
            val selection = Selection<Media>(scope = this.backgroundScope, setOf(TEST_MEDIA_IMAGE))
            val viewModel =
                PreviewViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            viewModel.getControllerForAuthority(MockContentProviderWrapper.AUTHORITY)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            val emissions = mutableListOf<PlaybackInfo>()
            backgroundScope.launch {
                viewModel
                    .getPlaybackInfoForPlayer(
                        surfaceId = 1,
                        video = TEST_MEDIA_VIDEO,
                    )
                    .toList(emissions)
            }

            callback.setPlaybackState(
                1,
                PLAYBACK_STATE_MEDIA_SIZE_CHANGED,
                bundleOf(EXTRA_SIZE to Point(100, 200))
            )
            advanceTimeBy(100)

            val mediaSizeChangedInfo = emissions.removeFirst()
            assertWithMessage("MEDIA_SIZE_CHANGED emitted state was invalid")
                .that(mediaSizeChangedInfo.state)
                .isEqualTo(PlaybackState.MEDIA_SIZE_CHANGED)
            assertWithMessage("MEDIA_SIZE_CHANGED emitted state was invalid")
                .that(mediaSizeChangedInfo.surfaceId)
                .isEqualTo(1)
            assertWithMessage("MEDIA_SIZE_CHANGED emitted state was invalid")
                .that(mediaSizeChangedInfo.authority)
                .isEqualTo(MockContentProviderWrapper.AUTHORITY)
            assertWithMessage("MEDIA_SIZE_CHANGED emitted state was invalid")
                .that(
                    mediaSizeChangedInfo.playbackStateInfo?.getParcelable(
                        EXTRA_SIZE,
                        Point::class.java
                    )
                )
                .isEqualTo(Point(100, 200))

            callback.setPlaybackState(1, PLAYBACK_STATE_BUFFERING, null)
            advanceTimeBy(100)
            assertWithMessage("BUFFERING emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.BUFFERING,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )

            callback.setPlaybackState(1, PLAYBACK_STATE_READY, null)
            advanceTimeBy(100)
            assertWithMessage("READY emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.READY,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )

            callback.setPlaybackState(1, PLAYBACK_STATE_STARTED, null)
            advanceTimeBy(100)
            assertWithMessage("STARTED emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.STARTED,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )

            callback.setPlaybackState(1, PLAYBACK_STATE_PAUSED, null)
            advanceTimeBy(100)
            assertWithMessage("PAUSED emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.PAUSED,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )

            callback.setPlaybackState(1, PLAYBACK_STATE_COMPLETED, null)
            advanceTimeBy(100)
            assertWithMessage("COMPLETED emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.COMPLETED,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )

            callback.setPlaybackState(1, PLAYBACK_STATE_ERROR_PERMANENT_FAILURE, null)
            advanceTimeBy(100)
            assertWithMessage("ERROR_PERMANENT_FAILURE emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.ERROR_PERMANENT_FAILURE,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )

            callback.setPlaybackState(1, PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE, null)
            advanceTimeBy(100)
            assertWithMessage("ERROR_RETRIABLE_FAILURE emitted state was invalid")
                .that(emissions.removeFirst())
                .isEqualTo(
                    PlaybackInfo(
                        state = PlaybackState.ERROR_RETRIABLE_FAILURE,
                        surfaceId = 1,
                        authority = MockContentProviderWrapper.AUTHORITY
                    )
                )
        }
    }

    /**
     * Extension function that will create new [ViewModelStore], add view model into it using
     * [ViewModelProvider] and then call [ViewModelStore.clear], that will cause
     * [ViewModel.onCleared] to be called
     */
    private fun ViewModel.callOnCleared() {
        val viewModelStore = ViewModelStore()
        val viewModelProvider =
            ViewModelProvider(
                viewModelStore,
                object : ViewModelProvider.Factory {

                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        this@callOnCleared as T
                }
            )
        viewModelProvider.get(this@callOnCleared::class.java)
        viewModelStore.clear() // To call clear() in ViewModel
    }
}
