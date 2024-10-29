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

package com.android.photopicker.features.preparemedia

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ApplicationMediaCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST
import com.android.photopicker.core.configuration.FEATURE_CLOUD_MEDIA_FEATURE_ENABLED
import com.android.photopicker.core.configuration.FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.NAMESPACE_MEDIAPROVIDER
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.PICKER_SEGMENT
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.preparemedia.MediaPreparerViewModel.Companion.PICKER_TRANSCODE_RESULT
import com.android.photopicker.features.preparemedia.TranscoderImpl.Companion.DURATION_LIMIT_MS
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.mockito.nonNullableAny
import com.android.photopicker.tests.utils.mockito.whenever
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
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
class MediaPreparerTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

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

    // Needed for UserMonitor
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var deviceConfig: DeviceConfigProxy
    @Inject lateinit var events: Lazy<Events>

    val mediaToPrepare = MutableSharedFlow<Set<Media>>()

    val VIDEO =
        Media.Video(
            mediaId = "0",
            pickerId = 0L,
            authority = "a",
            mediaSource = MediaSource.LOCAL,
            mediaUri = Uri.EMPTY,
            glideLoadableUri = Uri.EMPTY,
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "video/mp4",
            standardMimeTypeExtension = 1,
            duration = 45_000,
        )
    val DATA =
        buildList<Media>() {
            for (i in 1..20) {
                val uri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme(ContentResolver.SCHEME_CONTENT)
                            authority(MockContentProviderWrapper.AUTHORITY)
                            path("$i")
                        }
                        .build()
                add(
                    Media.Image(
                        mediaId = "$i",
                        pickerId = i.toLong(),
                        authority = "a",
                        // 50% of items should be remote items
                        mediaSource =
                            when (i % 2 == 0) {
                                true -> MediaSource.LOCAL
                                false -> MediaSource.REMOTE
                            },
                        mediaUri = uri,
                        glideLoadableUri = uri,
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "image/png",
                        standardMimeTypeExtension = 1,
                    )
                )
            }
        }
    val LOCAL_IMAGE_DATA =
        buildList<Media>() {
            for (i in 1..20) {
                val uri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme(ContentResolver.SCHEME_CONTENT)
                            authority(MockContentProviderWrapper.AUTHORITY)
                            path("$i")
                        }
                        .build()
                add(
                    Media.Image(
                        mediaId = "$i",
                        pickerId = i.toLong(),
                        authority = "a",
                        mediaSource = MediaSource.LOCAL,
                        mediaUri = uri,
                        glideLoadableUri = uri,
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "image/png",
                        standardMimeTypeExtension = 1,
                    )
                )
            }
        }
    val VIDEO_DATA =
        buildList<Media> {
            for (i in 1..10) {
                val uri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme(ContentResolver.SCHEME_CONTENT)
                            authority(MockContentProviderWrapper.AUTHORITY)
                            appendPath(PICKER_SEGMENT)
                            appendPath("user_id")
                            appendPath("a")
                            appendPath("media")
                            appendPath("$i")
                        }
                        .build()
                add(
                    Media.Video(
                        mediaId = "$i",
                        pickerId = i.toLong(),
                        authority = "a",
                        // 50% of items should be remote items
                        mediaSource =
                            when (i % 2 == 0) {
                                true -> MediaSource.LOCAL
                                false -> MediaSource.REMOTE
                            },
                        mediaUri = uri,
                        glideLoadableUri = uri,
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "video/mp4",
                        standardMimeTypeExtension = 1,
                        duration = 45_000,
                    )
                )
            }
        }
    val LOCAL_VIDEO_DATA_WITH_HALF_OUT_OF_DURATION_LIMITATION =
        buildList<Media> {
            for (i in 1..10) {
                val uri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme(ContentResolver.SCHEME_CONTENT)
                            authority(MockContentProviderWrapper.AUTHORITY)
                            appendPath(PICKER_SEGMENT)
                            appendPath("user_id")
                            appendPath("a")
                            appendPath("media")
                            appendPath("$i")
                        }
                        .build()
                add(
                    Media.Video(
                        mediaId = "$i",
                        pickerId = i.toLong(),
                        authority = "a",
                        mediaSource = MediaSource.LOCAL,
                        mediaUri = uri,
                        glideLoadableUri = uri,
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "video/mp4",
                        standardMimeTypeExtension = 1,
                        // 50% of items should be out of duration limit
                        duration =
                            when (i % 2 == 0) {
                                true -> 90_000
                                false -> 45_000
                            },
                    )
                )
            }
        }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        hiltRule.inject()

        val testDeviceConfigProxy =
            checkNotNull(deviceConfig as? TestDeviceConfigProxyImpl) {
                "Expected a TestDeviceConfigProxy"
            }

        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
            true,
        )
        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.first,
            true,
        )
        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
            "com.android.test.cloudpicker",
        )

        val testIntent =
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val testMediaCapabilities = ApplicationMediaCapabilities.Builder().build()
                    putExtra(MediaStore.EXTRA_MEDIA_CAPABILITIES, testMediaCapabilities)
                }
            }
        configurationManager.get().setIntent(testIntent)

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
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        // Return success flag when transcoding is requested.
        whenever(mockContentProvider.call(any(), any(), any(), any())) {
            Bundle().apply { putBoolean(PICKER_TRANSCODE_RESULT, true) }
        }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @Test
    fun testMediaPreparerCompletesDeferredWhenSuccessful() =
        testScope.runTest {
            val prepareDeferred = CompletableDeferred<PrepareMediaResult>()
            initialMediaPreparer(prepareDeferred)

            selection.get().addAll(DATA)
            advanceTimeBy(100)

            val snapshot = selection.get().snapshot()
            mediaToPrepare.emit(snapshot)

            val prepareResult = prepareDeferred.await()

            assertWithMessage("Expected prepare result to be true, the prepare must have failed.")
                .that(prepareResult is PrepareMediaResult.PreparedMedia)
                .isTrue()

            val preparedMedia = (prepareResult as? PrepareMediaResult.PreparedMedia)?.preparedMedia
            assertWithMessage("Expected prepared media size was not same as selected")
                .that(snapshot.size == preparedMedia?.size)
                .isTrue()
        }

    @Test
    fun testMediaPreparerCompletesDeferredWhenPreparationNotRequired() =
        testScope.runTest {
            val prepareDeferred = CompletableDeferred<PrepareMediaResult>()
            initialMediaPreparer(prepareDeferred)

            // Local images do not need to be preloaded and transcoded.
            selection.get().addAll(LOCAL_IMAGE_DATA)
            advanceTimeBy(100)

            val snapshot = selection.get().snapshot()
            mediaToPrepare.emit(snapshot)

            val prepareResult = prepareDeferred.await()

            assertWithMessage("Expected prepare result to be true, the prepare must have failed.")
                .that(prepareResult is PrepareMediaResult.PreparedMedia)
                .isTrue()

            val preparedMedia = (prepareResult as? PrepareMediaResult.PreparedMedia)?.preparedMedia
            assertWithMessage("Expected prepared media size was not same as selected")
                .that(snapshot.size == preparedMedia?.size)
                .isTrue()
        }

    @Test
    fun testMediaPreparerShowsPreparingDialog() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val loadingDialogTitle =
                resources.getString(R.string.photopicker_preloading_dialog_title)

            initialMediaPreparer(CompletableDeferred())

            selection.get().addAll(DATA)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            mediaToPrepare.emit(selection.get().snapshot())
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.waitUntilExactlyOneExists(hasText(loadingDialogTitle))
            composeTestRule.onNode(hasText(loadingDialogTitle)).assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(android.R.string.cancel)))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }

    @Test
    fun testMediaPreparerCancelPrepareFromPreparingDialog() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val loadingDialogTitle =
                resources.getString(R.string.photopicker_preloading_dialog_title)

            initialMediaPreparer(CompletableDeferred())

            selection.get().addAll(DATA)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            mediaToPrepare.emit(selection.get().snapshot())
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.waitUntilExactlyOneExists(hasText(loadingDialogTitle))
            composeTestRule.onNode(hasText(loadingDialogTitle)).assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(android.R.string.cancel)))
                .assert(hasClickAction())
                .assertIsDisplayed()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(loadingDialogTitle)).assertIsNotDisplayed()
        }

    @Test
    fun testMediaPreparerLoadsRemoteMedia() =
        testScope.runTest {
            initialMediaPreparer(CompletableDeferred())

            selection.get().addAll(DATA)
            advanceTimeBy(100)

            mediaToPrepare.emit(selection.get().snapshot())
            advanceTimeBy(100)

            advanceTimeBy(100)

            // Should receive a total of 5 calls. (20 Media items and 50% are MediaSource.REMOTE)
            verify(mockContentProvider, times(10)).openTypedAssetFile(any(), any(), any())
        }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_TRANSCODING)
    @Test
    fun testMediaPreparerTranscodeMedia() =
        testScope.runTest {
            initialMediaPreparer(CompletableDeferred())

            // Use mock Transcoder to bypass checks require extracting info from videos.
            val viewModel = composeTestRule.activity.viewModels<MediaPreparerViewModel>().value
            val mockTranscoder = mock(Transcoder::class.java)
            whenever(
                mockTranscoder.isTranscodeRequired(
                    nonNullableAny(Context::class.java, mockContext),
                    any(),
                    nonNullableAny(Media.Video::class.java, VIDEO),
                )
            ) {
                true
            }
            viewModel.transcoder = mockTranscoder
            composeTestRule.waitForIdle()

            selection.get().addAll(VIDEO_DATA)
            advanceTimeBy(100)

            mediaToPrepare.emit(selection.get().snapshot())
            advanceTimeBy(100)

            // Buffer time for the busy checking delay. (10 videos)
            advanceTimeBy(1000)

            // Should receive a total of 5 calls. (10 videos and 50% are MediaSource.REMOTE)
            verify(mockContentProvider, times(5)).openTypedAssetFile(any(), any(), any())
            // Should receive a total of 10 calls. (10 videos and all their durations are in limit)
            verify(mockContentProvider, times(10)).call(any(), any(), any(), any())
        }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_TRANSCODING)
    @Test
    fun testMediaPreparerTranscodeMedia_notTranscodeOverDurationLimitVideos() =
        testScope.runTest {
            initialMediaPreparer(CompletableDeferred())

            // Use mock Transcoder to bypass checks require extracting info from videos.
            val viewModel = composeTestRule.activity.viewModels<MediaPreparerViewModel>().value
            val mockTranscoder = mock(Transcoder::class.java)
            whenever(
                mockTranscoder.isTranscodeRequired(
                    nonNullableAny(Context::class.java, mockContext),
                    any(),
                    nonNullableAny(Media.Video::class.java, VIDEO),
                )
            ) {
                val video = this.arguments[2] as Media.Video
                video.duration <= DURATION_LIMIT_MS
            }
            viewModel.transcoder = mockTranscoder
            composeTestRule.waitForIdle()

            selection.get().addAll(LOCAL_VIDEO_DATA_WITH_HALF_OUT_OF_DURATION_LIMITATION)
            advanceTimeBy(100)

            mediaToPrepare.emit(selection.get().snapshot())
            advanceTimeBy(100)

            // Buffer time for the busy checking delay. (10 videos)
            advanceTimeBy(1000)

            // Should receive a total of 5 calls. (10 videos and 50% are out of duration limit)
            verify(mockContentProvider, times(5)).call(any(), any(), any(), any())
        }

    @Test
    fun testMediaPreparerFailureShowsErrorDialog() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val errorDialogTitle =
                resources.getString(R.string.photopicker_preloading_dialog_error_title)
            val errorDialogMessage =
                resources.getString(R.string.photopicker_preloading_dialog_error_message)

            whenever(mockContentProvider.openTypedAssetFile(any(), any(), any()))
                .thenThrow(FileNotFoundException())

            initialMediaPreparer(CompletableDeferred())

            selection.get().addAll(DATA)
            advanceTimeBy(100)

            mediaToPrepare.emit(selection.get().snapshot())
            advanceTimeBy(500)
            composeTestRule.waitForIdle()

            composeTestRule.waitUntilExactlyOneExists(hasText(errorDialogTitle))
            composeTestRule.onNode(hasText(errorDialogTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(errorDialogMessage)).assertIsDisplayed()
        }

    private fun initialMediaPreparer(prepareDeferred: CompletableDeferred<PrepareMediaResult>) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    configurationManager.get().configuration.value,
                LocalEvents provides events.get(),
            ) {
                val params =
                    object : LocationParams.WithMediaPreparer {
                        override fun obtainDeferred(): CompletableDeferred<PrepareMediaResult> {
                            return prepareDeferred
                        }

                        override val prepareMedia = mediaToPrepare
                    }
                featureManager
                    .get()
                    .composeLocation(
                        location = Location.MEDIA_PREPARER,
                        modifier = Modifier,
                        params = params,
                    )
            }
        }
        composeTestRule.waitForIdle()
    }
}
