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

package com.android.photopicker.features.cloudmedia

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.testActionPickImagesConfiguration
import com.android.photopicker.core.configuration.testGetContentConfiguration
import com.android.photopicker.core.configuration.testUserSelectImagesForAppConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.mockito.whenever
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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MediaPreloaderTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    @BindValue @Main val mainScope: TestScope = TestScope(testDispatcher)
    @BindValue @Background var testBackgroundScope: CoroutineScope = mainScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = mainScope.backgroundScope

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
    @Inject lateinit var configurationManager: ConfigurationManager
    @Inject lateinit var events: Lazy<Events>

    val mediaToPreload = MutableSharedFlow<Set<Media>>()

    val DATA =
        buildList<Media>() {
            for (i in 1..20) {
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
                        mediaUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme(ContentResolver.SCHEME_CONTENT)
                                    authority(MockContentProviderWrapper.AUTHORITY)
                                    path("$i")
                                }
                                .build(),
                        glideLoadableUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme(ContentResolver.SCHEME_CONTENT)
                                    authority(MockContentProviderWrapper.AUTHORITY)
                                    path("$i")
                                }
                                .build(),
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "image/png",
                        standardMimeTypeExtension = 1,
                    )
                )
            }
        }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        hiltRule.inject()

        val testIntent =
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
            }
        configurationManager.setIntent(testIntent)

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
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @Test
    fun testMediaPreloaderIsEnabled() {

        assertWithMessage("MediaPreloader is not always enabled for action pick image")
            .that(CloudMediaFeature.Registration.isEnabled(testActionPickImagesConfiguration))
            .isEqualTo(true)

        assertWithMessage("MediaPreloader is not always enabled for get content")
            .that(CloudMediaFeature.Registration.isEnabled(testGetContentConfiguration))
            .isEqualTo(true)

        assertWithMessage("MediaPreloader should not be enabled for user select images")
            .that(CloudMediaFeature.Registration.isEnabled(testUserSelectImagesForAppConfiguration))
            .isEqualTo(false)
    }

    @Test
    fun testMediaPreloaderCompletesDeferredWhenSuccessful() =
        mainScope.runTest {
            var preloadDeferred = CompletableDeferred<Boolean>()

            composeTestRule.setContent {
                featureManager
                    .get()
                    .composeLocation(
                        location = Location.MEDIA_PRELOADER,
                        modifier = Modifier,
                        params =
                            object : LocationParams.WithMediaPreloader {
                                override fun obtainDeferred(): CompletableDeferred<Boolean> {
                                    return preloadDeferred
                                }

                                override val preloadMedia = mediaToPreload
                            }
                    )
            }
            composeTestRule.waitForIdle()

            selection.get().addAll(DATA)
            advanceTimeBy(100)

            mediaToPreload.emit(selection.get().snapshot())

            val preloadResult = preloadDeferred.await()

            assertWithMessage("Expected preload result to be true, the preload must have failed.")
                .that(preloadResult)
                .isTrue()
        }

    @Test
    fun testMediaPreloaderShowsLoadingDialog() =
        mainScope.runTest {
            val resources = getTestableContext().getResources()
            val loadingDialogTitle =
                resources.getString(R.string.photopicker_preloading_dialog_title)

            var preloadDeferred = CompletableDeferred<Boolean>()
            composeTestRule.setContent {
                featureManager
                    .get()
                    .composeLocation(
                        location = Location.MEDIA_PRELOADER,
                        modifier = Modifier,
                        params =
                            object : LocationParams.WithMediaPreloader {
                                override fun obtainDeferred(): CompletableDeferred<Boolean> {
                                    return preloadDeferred
                                }

                                override val preloadMedia = mediaToPreload
                            }
                    )
            }
            composeTestRule.waitForIdle()

            selection.get().addAll(DATA)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            mediaToPreload.emit(selection.get().snapshot())
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
    fun testMediaPreloaderCancelPreloadFromLoadingDialog() =
        mainScope.runTest {
            val resources = getTestableContext().getResources()
            val loadingDialogTitle =
                resources.getString(R.string.photopicker_preloading_dialog_title)

            var preloadDeferred = CompletableDeferred<Boolean>()
            composeTestRule.setContent {
                featureManager
                    .get()
                    .composeLocation(
                        location = Location.MEDIA_PRELOADER,
                        modifier = Modifier,
                        params =
                            object : LocationParams.WithMediaPreloader {
                                override fun obtainDeferred(): CompletableDeferred<Boolean> {
                                    return preloadDeferred
                                }

                                override val preloadMedia = mediaToPreload
                            }
                    )
            }
            composeTestRule.waitForIdle()

            selection.get().addAll(DATA)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            mediaToPreload.emit(selection.get().snapshot())
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
    fun testMediaPreloaderLoadsRemoteMedia() =
        mainScope.runTest {
            var preloadDeferred = CompletableDeferred<Boolean>()
            composeTestRule.setContent {
                featureManager
                    .get()
                    .composeLocation(
                        location = Location.MEDIA_PRELOADER,
                        modifier = Modifier,
                        params =
                            object : LocationParams.WithMediaPreloader {
                                override fun obtainDeferred(): CompletableDeferred<Boolean> {
                                    return preloadDeferred
                                }

                                override val preloadMedia = mediaToPreload
                            }
                    )
            }
            composeTestRule.waitForIdle()

            selection.get().addAll(DATA)
            advanceTimeBy(100)

            mediaToPreload.emit(selection.get().snapshot())
            advanceTimeBy(100)

            advanceTimeBy(100)

            // Should receive a total of 5 calls. (20 Media items and 50% are MediaSource.REMOTE)
            verify(mockContentProvider, times(10)).openTypedAssetFile(any(), any(), any())
        }

    @Test
    fun testMediaPreloaderFailureShowsErrorDialog() =
        mainScope.runTest {
            var preloadDeferred = CompletableDeferred<Boolean>()
            val resources = getTestableContext().getResources()
            val errorDialogTitle =
                resources.getString(R.string.photopicker_preloading_dialog_error_title)
            val errorDialogMessage =
                resources.getString(R.string.photopicker_preloading_dialog_error_message)

            whenever(mockContentProvider.openTypedAssetFile(any(), any(), any()))
                .thenThrow(FileNotFoundException())

            composeTestRule.setContent {
                featureManager
                    .get()
                    .composeLocation(
                        location = Location.MEDIA_PRELOADER,
                        modifier = Modifier,
                        params =
                            object : LocationParams.WithMediaPreloader {
                                override fun obtainDeferred(): CompletableDeferred<Boolean> {
                                    return preloadDeferred
                                }

                                override val preloadMedia = mediaToPreload
                            }
                    )
            }
            composeTestRule.waitForIdle()

            selection.get().addAll(DATA)
            advanceTimeBy(100)

            mediaToPreload.emit(selection.get().snapshot())
            advanceTimeBy(500)
            composeTestRule.waitForIdle()

            composeTestRule.waitUntilExactlyOneExists(hasText(errorDialogTitle))
            composeTestRule.onNode(hasText(errorDialogTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(errorDialogMessage)).assertIsDisplayed()
        }
}
