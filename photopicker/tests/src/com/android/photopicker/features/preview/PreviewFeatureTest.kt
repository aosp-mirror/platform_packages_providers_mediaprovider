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

package com.android.photopicker.features.preview

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_PERMANENT_FAILURE
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE
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
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.view.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.PhotopickerMain
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.GrantsAwareSelectionImpl
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.extensions.navigateToPreviewSelection
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.capture
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.isNull
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
// TODO(b/340770526) Fix tests that can't access [ICloudMediaSurfaceController] on R & S.
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PreviewFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider

    // Needed for UserMonitor in PreviewViewModel
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    // Needed for Preview
    lateinit var controllerProxy: ICloudMediaSurfaceController.Stub
    @Mock lateinit var mockCloudMediaSurfaceController: ICloudMediaSurfaceController.Stub
    @Captor lateinit var controllerBundle: ArgumentCaptor<Bundle>

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    val TEST_MEDIA_IMAGE =
        Media.Image(
            mediaId = "image_id",
            pickerId = 123456789L,
            authority = MockContentProviderWrapper.AUTHORITY,
            mediaSource = MediaSource.LOCAL,
            mediaUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("media")
                        path("picker")
                        path("a")
                        path("image_id")
                    }
                    .build(),
            glideLoadableUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("image_id")
                    }
                    .build(),
            dateTakenMillisLong = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) * 1000,
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

        hiltRule.inject()

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

        // Stub out the content resolver for Glide
        val mockContentResolver = MockContentResolver(mockContext)
        provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)
        contentResolver = mockContentResolver

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)

        // Setup a proxy to call the mocked controller, since IBinder uses onTransact under the hood
        // and that is more complicated to verify.
        controllerProxy =
            object : ICloudMediaSurfaceController.Stub() {

                override fun onSurfaceCreated(surfaceId: Int, surface: Surface, mediaId: String) {
                    mockCloudMediaSurfaceController.onSurfaceCreated(surfaceId, surface, mediaId)
                }

                override fun onSurfaceChanged(
                    surfaceId: Int,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    mockCloudMediaSurfaceController.onSurfaceChanged(
                        surfaceId,
                        format,
                        width,
                        height,
                    )
                }

                override fun onSurfaceDestroyed(surfaceId: Int) {
                    mockCloudMediaSurfaceController.onSurfaceDestroyed(surfaceId)
                }

                override fun onMediaPlay(surfaceId: Int) {
                    mockCloudMediaSurfaceController.onMediaPlay(surfaceId)
                }

                override fun onMediaPause(surfaceId: Int) {
                    mockCloudMediaSurfaceController.onMediaPause(surfaceId)
                }

                override fun onMediaSeekTo(surfaceId: Int, timestampMillis: Long) {
                    mockCloudMediaSurfaceController.onMediaSeekTo(surfaceId, timestampMillis)
                }

                override fun onConfigChange(bundle: Bundle) {
                    mockCloudMediaSurfaceController.onConfigChange(bundle)
                }

                override fun onDestroy() {
                    mockCloudMediaSurfaceController.onDestroy()
                }

                override fun onPlayerCreate() {
                    mockCloudMediaSurfaceController.onPlayerCreate()
                }

                override fun onPlayerRelease() {
                    mockCloudMediaSurfaceController.onPlayerRelease()
                }
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
    }

    /** Ensures that the PreviewMedia route can be navigated to with an Image payload. */
    @Test
    fun testNavigateToPreviewImage() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_IMAGE)
            })

            assertWithMessage("Expected route to be preview/media")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PREVIEW_MEDIA.route)

            val previewMedia: Media? =
                navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.get(PreviewFeature.PREVIEW_MEDIA_KEY)

            assertWithMessage("Expected backstack entry to have a media item")
                .that(previewMedia)
                .isNotNull()

            assertWithMessage("Expected media to be the selected media")
                .that(previewMedia)
                .isEqualTo(TEST_MEDIA_IMAGE)
        }

    /** Ensures that the PreviewMedia route navigate back button. */
    @Test
    fun testNavigateBack() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            val initialRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Unable to find initial route").that(initialRoute).isNotNull()

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_IMAGE)
            })

            assertWithMessage("Expected route to be preview/media")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PREVIEW_MEDIA.route)

            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option))
                )
                .assert(hasClickAction())
                .performClick()
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be initial route")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(initialRoute)
        }

    @Test
    fun testNavigateToPreviewVideo() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            assertWithMessage("Expected route to be preview/media")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PREVIEW_MEDIA.route)

            val previewMedia: Media? =
                navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.get(PreviewFeature.PREVIEW_MEDIA_KEY)

            assertWithMessage("Expected backstack entry to have a media item")
                .that(previewMedia)
                .isNotNull()

            assertWithMessage("Expected media to be the selected media")
                .that(previewMedia)
                .isEqualTo(TEST_MEDIA_VIDEO)
        }

    /** Ensures the PreviewSelection route can be navigated to. */
    @Test
    fun testNavigateToPreviewSelection() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            selection.add(TEST_MEDIA_IMAGE)
            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToPreviewSelection() })
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be preview/selection")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PREVIEW_SELECTION.route)
        }

    /**
     * Ensures the PreviewSelection select and deselect actions correctly toggle the item in the
     * selection.
     */
    @Test
    fun testPreviewSelectionActions() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            selection.add(TEST_MEDIA_IMAGE)
            advanceTimeBy(100)

            val resources = getTestableContext().getResources()
            val selectButtonLabel =
                resources.getString(
                    R.string.photopicker_select_button_label,
                    selection.snapshot().size,
                )
            val deselectButtonLabel =
                resources.getString(
                    R.string.photopicker_deselect_button_label,
                    selection.snapshot().size,
                )

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToPreviewSelection() })

            // Wait for the flows to resolve and the UI to update.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Expected route to be preview/media")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PREVIEW_SELECTION.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            composeTestRule
                .onNode(hasText(deselectButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            // Allow selection to update
            advanceTimeBy(100)
            assertWithMessage("Selection contained an unexpected item")
                .that(selection.snapshot())
                .doesNotContain(TEST_MEDIA_IMAGE)

            composeTestRule
                .onNode(hasText(selectButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            // Allow selection to update
            advanceTimeBy(100)
            assertWithMessage("Selection did not contain an expected item")
                .that(selection.snapshot())
                .contains(TEST_MEDIA_IMAGE)
        }

    /**
     * Ensures the PreviewSelection select and deselect actions are not displayed when the selection
     * is grants aware.
     */
    @Test
    fun testPreviewSelectionActionsWithGrantsAwareSelection() =
        testScope.runTest {
            composeTestRule.setContent {
                val testPhotoPickerConfiguration =
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                val selection =
                    GrantsAwareSelectionImpl<Media>(
                        backgroundScope,
                        null,
                        MutableStateFlow(testPhotoPickerConfiguration),
                        TestDataServiceImpl().preGrantedMediaCount,
                    )
                val navController = createNavController()
                val disruptiveFlow = flow { emit(0) }
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    CompositionLocalProvider(
                        LocalFeatureManager provides featureManager,
                        LocalSelection provides selection,
                        LocalPhotopickerConfiguration provides testPhotoPickerConfiguration,
                        LocalNavController provides navController,
                        LocalEvents provides events,
                    ) {
                        PhotopickerTheme(config = testPhotoPickerConfiguration) {
                            PhotopickerMain(disruptiveDataNotification = disruptiveFlow)
                        }
                    }
                }
            }

            selection.clear()
            // Add an item to make the preview option visible
            selection.add(TEST_MEDIA_IMAGE)
            advanceTimeBy(100)

            // Verify that the select all and de-select all option is not available for
            // grantsAwareSelection.
            val resources = getTestableContext().getResources()
            val selectButtonLabel =
                resources.getString(
                    R.string.photopicker_select_button_label,
                    selection.snapshot().size,
                )
            val deselectButtonLabel =
                resources.getString(
                    R.string.photopicker_deselect_button_label,
                    selection.snapshot().size,
                )

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToPreviewSelection() })

            // Wait for the flows to resolve and the UI to update.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Expected route to be preview/media")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PREVIEW_SELECTION.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(deselectButtonLabel)).assertIsNotDisplayed()

            composeTestRule.onNode(hasText(selectButtonLabel)).assertIsNotDisplayed()

            // Allow selection to update
            advanceTimeBy(100)
            assertWithMessage("Selection did not contain an expected item")
                .that(selection.snapshot())
                .contains(TEST_MEDIA_IMAGE)
        }

    @Test
    fun testPreviewSelectInSingleSelect() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            val initialRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("initial route was null").that(initialRoute).isNotNull()

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            val resources = getTestableContext().getResources()
            val buttonLabel = resources.getString(R.string.photopicker_select_current_button_label)

            composeTestRule
                .onNode(hasText(buttonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            composeTestRule.waitForIdle()

            // Allow selection to update
            advanceTimeBy(100)
            assertWithMessage("Expected route to be the initial route")
                .that(selection.snapshot())
                .contains(TEST_MEDIA_VIDEO)
        }

    @Test
    fun testPreviewDoneNavigatesBack() =
        testScope.runTest {

            // Ensure multi select
            configurationManager
                .get()
                .setIntent(
                    Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                        putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                    }
                )

            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                Column(modifier = Modifier.defaultMinSize(minHeight = 100.dp, minWidth = 100.dp)) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            val initialRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("initial route was null").that(initialRoute).isNotNull()

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToPreviewSelection() })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            val resources = getTestableContext().getResources()
            val buttonLabel = resources.getString(R.string.photopicker_done_button_label)

            composeTestRule
                .onNode(hasText(buttonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            composeTestRule.waitForIdle()

            // Allow selection to update
            advanceTimeBy(100)
            assertWithMessage("Expected route to be the initial route")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(initialRoute)
        }

    /** Ensures the VideoUi creates a RemoteSurfaceController */
    @Test
    fun testVideoUiCreatesRemoteSurfaceController() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

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

    /** Ensures the VideoUi notifies of surfaceCreation */
    @Test
    fun testVideoUiNotifySurfaceCreated() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            assertWithMessage("SurfaceStateChangedCallback was not provided")
                .that(bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK))
                .isNotNull()

            verify(mockContentProvider)
                .call(
                    /*authority=*/ anyString(),
                    /*method=*/ nonNullableEq(METHOD_CREATE_SURFACE_CONTROLLER),
                    /*arg=*/ isNull(),
                    /*extras=*/ any(Bundle::class.java),
                )

            verify(mockCloudMediaSurfaceController)
                .onSurfaceCreated(anyInt(), any(Surface::class.java), anyString())
            verify(mockCloudMediaSurfaceController)
                .onSurfaceChanged(anyInt(), anyInt(), anyInt(), anyInt())
            verify(mockCloudMediaSurfaceController).onPlayerCreate()
        }

    /** Ensures the VideoUi attempts to play videos when the controller indicates it is ready. */
    @Test
    fun testVideoUiRequestsPlayWhenMediaReady() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_READY, null)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())
        }

    /** Ensures the VideoUi auto shows & hides the player controls. */
    @Test
    fun testVideoUiShowsAndHidesPlayerControls() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            val playButtonDescription =
                resources.getString(R.string.photopicker_video_play_button_description)

            val pauseButtonDescription =
                resources.getString(R.string.photopicker_video_pause_button_description)

            val muteButtonDescription =
                resources.getString(R.string.photopicker_video_mute_button_description)

            val unmuteButtonDescription =
                resources.getString(R.string.photopicker_video_unmute_button_description)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_READY, null)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_STARTED, null)
            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Pause is the button shown once the player begins playing.
            composeTestRule
                .onNode(hasContentDescription(pauseButtonDescription))
                .assertIsDisplayed()
                .assert(hasClickAction())

            // Unmute is the audio button shown once the player begins playing.
            composeTestRule
                .onNode(hasContentDescription(unmuteButtonDescription))
                .assertIsDisplayed()
                .assert(hasClickAction())

            composeTestRule.mainClock.autoAdvance = false
            // Wait enough time for the delay & the animation to end
            composeTestRule.mainClock.advanceTimeBy(10_000L)
            composeTestRule.waitForIdle()

            // Now the player controls should not be visible
            composeTestRule
                .onNode(hasContentDescription(pauseButtonDescription))
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(hasContentDescription(unmuteButtonDescription))
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(hasContentDescription(playButtonDescription))
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(hasContentDescription(muteButtonDescription))
                .assertIsNotDisplayed()
        }

    /** Ensures the VideoUi Play/Pause buttons work correctly. */
    @Test
    fun testVideoUiPlayPauseButtonOnClick() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            val playButtonDescription =
                resources.getString(R.string.photopicker_video_play_button_description)

            val pauseButtonDescription =
                resources.getString(R.string.photopicker_video_pause_button_description)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_READY, null)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_STARTED, null)
            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())

            clearInvocations(mockCloudMediaSurfaceController)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Pause is the button shown once the player begins playing.
            composeTestRule
                .onNode(hasContentDescription(pauseButtonDescription))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            advanceTimeBy(100)
            verify(mockCloudMediaSurfaceController).onMediaPause(anyInt())

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_PAUSED, null)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasContentDescription(playButtonDescription))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())
        }

    /** Ensures the VideoUi Mute/UnMute buttons work correctly. */
    @Test
    fun testVideoUiMuteButtonOnClick() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val muteButtonDescription =
                resources.getString(R.string.photopicker_video_mute_button_description)

            val unmuteButtonDescription =
                resources.getString(R.string.photopicker_video_unmute_button_description)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_READY, null)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_STARTED, null)
            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())

            clearInvocations(mockCloudMediaSurfaceController)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Pause is the button shown once the player begins playing.
            composeTestRule
                .onNode(hasContentDescription(unmuteButtonDescription))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            advanceTimeBy(100)
            verify(mockCloudMediaSurfaceController).onConfigChange(any(Bundle::class.java))

            clearInvocations(mockCloudMediaSurfaceController)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasContentDescription(muteButtonDescription))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            verify(mockCloudMediaSurfaceController).onConfigChange(any(Bundle::class.java))
        }

    /** Ensures the VideoUi shows an error dialog for temporary failures. */
    @Test
    fun testVideoUiRetriablePlaybackError() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            val retryButtonLabel =
                resources.getString(R.string.photopicker_preview_dialog_error_retry_button_label)
            val errorTitle = resources.getString(R.string.photopicker_preview_dialog_error_title)
            val errorMessage =
                resources.getString(R.string.photopicker_preview_dialog_error_message)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_READY, null)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_STARTED, null)
            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())

            clearInvocations(mockCloudMediaSurfaceController)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(
                /*surfaceId=*/ 1,
                PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE,
                null,
            )

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(errorTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(errorMessage)).assertIsDisplayed()
            composeTestRule
                .onNode(hasText(retryButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())

            composeTestRule.onNode(hasText(errorTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(errorMessage)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(retryButtonLabel)).assertIsNotDisplayed()
        }

    /** Ensures the VideoUi shows a snackbar for permanent failures. */
    @Test
    fun testVideoUiPermanentPlaybackError() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            val errorMessage =
                resources.getString(R.string.photopicker_preview_video_error_snackbar)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToPreviewMedia(TEST_MEDIA_VIDEO)
            })

            // This looks a little awkward, but is necessary. There are two flows that need
            // to be awaited, and a recomposition is required between them, so await idle twice
            // and advance the test clock twice.
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val bundle = controllerBundle.getValue()
            val binder = bundle.getBinder(EXTRA_SURFACE_STATE_CALLBACK)
            val callback = ICloudMediaSurfaceStateChangedCallback.Stub.asInterface(binder)

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_READY, null)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(/* surfaceId= */ 1, PLAYBACK_STATE_STARTED, null)
            verify(mockCloudMediaSurfaceController).onMediaPlay(anyInt())

            clearInvocations(mockCloudMediaSurfaceController)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            callback.setPlaybackState(
                /*surfaceId=*/ 1,
                PLAYBACK_STATE_ERROR_PERMANENT_FAILURE,
                null,
            )

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(errorMessage)).assertIsDisplayed()
        }
}
