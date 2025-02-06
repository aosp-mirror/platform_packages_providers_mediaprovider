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

package com.android.photopicker.core

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.extensions.navigateToCategoryGrid
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.StubProvider
import com.android.photopicker.util.test.whenever
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
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations

/** This test class will run Photopicker's actual MainActivity. */
@UninstallModules(ApplicationModule::class, ActivityModule::class, EmbeddedServiceModule::class)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class)
class PhotopickerAppTest : PhotopickerFeatureBaseTest() {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()

    val testDispatcher = StandardTestDispatcher()

    /** Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var mockContext: Context
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var events: Lazy<Events>
    @Inject lateinit var dataService: Lazy<DataService>
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockContentProvider: ContentProvider

    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper

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
        configurationManager
            .get()
            .setIntent(
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                }
            )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testDataDisruptionResetsTheUi_searchFlagOff() {
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                    disruptiveDataFlow =
                        dataService.get().disruptiveDataUpdateChannel.receiveAsFlow().runningFold(
                            initial = 0
                        ) { prev, _ ->
                            prev + 1
                        },
                )
            }

            selection.get().addAll(StubProvider.getTestMediaFromStubProvider(count = 5))

            advanceTimeBy(100)

            assertWithMessage("Expected selection to contain items")
                .that(selection.get().snapshot().size)
                .isEqualTo(5)

            val startDestination = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected the starting destination to not be album grid")
                .that(startDestination)
                .isNotEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            composeTestRule.runOnUiThread { navController.navigateToAlbumGrid() }
            composeTestRule.waitForIdle()

            val albumRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected current route to be AlbumGrid")
                .that(albumRoute)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            val testDataService =
                checkNotNull(dataService.get() as? TestDataServiceImpl) {
                    "Expected a TestDataServiceImpl"
                }

            testDataService.sendDisruptiveDataUpdateNotification()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected selection to be empty")
                .that(selection.get().snapshot().size)
                .isEqualTo(0)

            val endRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected to return to start destination")
                .that(endRoute)
                .isEqualTo(startDestination)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testDataDisruptionResetsTheUi_searchFlagOn() {
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                    disruptiveDataFlow =
                        dataService.get().disruptiveDataUpdateChannel.receiveAsFlow().runningFold(
                            initial = 0
                        ) { prev, _ ->
                            prev + 1
                        },
                )
            }

            selection.get().addAll(StubProvider.getTestMediaFromStubProvider(count = 5))

            advanceTimeBy(100)

            assertWithMessage("Expected selection to contain items")
                .that(selection.get().snapshot().size)
                .isEqualTo(5)

            val startDestination = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected the starting destination to not be categories album grid")
                .that(startDestination)
                .isNotEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            composeTestRule.runOnUiThread { navController.navigateToCategoryGrid() }
            composeTestRule.waitForIdle()

            val albumRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected current route to be Category Album Grid")
                .that(albumRoute)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            val testDataService =
                checkNotNull(dataService.get() as? TestDataServiceImpl) {
                    "Expected a TestDataServiceImpl"
                }

            testDataService.sendDisruptiveDataUpdateNotification()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected selection to be empty")
                .that(selection.get().snapshot().size)
                .isEqualTo(0)

            val endRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected to return to start destination")
                .that(endRoute)
                .isEqualTo(startDestination)
        }
    }
}
