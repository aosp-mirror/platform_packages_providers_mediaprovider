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

package com.android.photopicker.features.albumgrid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
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
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@UninstallModules(
    ActivityModule::class,
    EmbeddedServiceModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class AlbumGridFeatureTest : PhotopickerFeatureBaseTest() {

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

    // Needed for UserMonitor
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService

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
    }

    @Test
    fun testAlbumGridIsAlwaysEnabled() {
        assertWithMessage("AlbumGridFeature is not always enabled for TEST_ACTION")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                )
            )
            .isEqualTo(true)
    }

    @Test
    fun testNavigateAlbumGridAndAlbumsAreVisible() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule
                .onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1"))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }

    @Test
    fun testAlbumsCanBeSelected() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()

            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be albummediagrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)
        }

    @Test
    fun testSwipeLeftToNavigateToPhotoGrid() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1")).performTouchInput {
                swipeRight()
            }
            composeTestRule.waitForIdle()

            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    fun testAlbumMediaShowsEmptyStateWhenEmpty() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    fun testEmptyStateContentForFavorites() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testDataService.albumsList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_FAVORITES,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Favorites",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val testAlbumDisplayName = "Favorites"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_favorites_empty_state_title))
                )
                .assertIsDisplayed()

            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_favorites_empty_state_body))
                )
                .assertIsDisplayed()
        }
    }

    @Test
    fun testEmptyStateContentForVideos() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testDataService.albumsList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_VIDEOS,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Videos",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = "Videos"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    fun testEmptyStateContentForCamera() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testDataService.albumsList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_CAMERA,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Camera",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = "Camera"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_camera_empty_state_body)))
                .assertIsDisplayed()
        }
    }
}
