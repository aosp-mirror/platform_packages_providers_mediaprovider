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
package com.android.photopicker.core.embedded

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.test.mock.MockContentResolver
import android.view.SurfaceControlViewHost
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.filters.SdkSuppress
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.PhotopickerApp
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.banners.BannerStateDao
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST
import com.android.photopicker.core.configuration.FEATURE_CLOUD_MEDIA_FEATURE_ENABLED
import com.android.photopicker.core.configuration.FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.NAMESPACE_MEDIAPROVIDER
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.features.snackbar.SnackbarFeature
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.inject.TestOptions
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.nonNullableEq
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EmbeddedFeaturesTest : EmbeddedPhotopickerFeatureBaseTest() {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module
    @InstallIn(SingletonComponent::class)
    class TestModule :
        PhotopickerTestModule(TestOptions.build { runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED) })

    val testDispatcher = StandardTestDispatcher()
    /* Overrides for EmbeddedServiceModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope
    @Inject @Main lateinit var mainDispatcher: CoroutineDispatcher
    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope
    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider
    @Mock lateinit var mockSurfaceControlViewHost: SurfaceControlViewHost
    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in collapsed mode
     */
    private lateinit var testEmbeddedStateWithHostInCollapsedState: EmbeddedState
    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in Expanded state
     */
    private lateinit var testEmbeddedStateWithHostInExpandedState: EmbeddedState

    @Inject lateinit var events: Lazy<Events>
    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var userHandle: UserHandle
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var embeddedLifecycle: Lazy<EmbeddedLifecycle>
    @Inject lateinit var databaseManager: DatabaseManager
    @Inject lateinit var dataService: Lazy<DataService>
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Inject lateinit var deviceConfig: DeviceConfigProxy
    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10
    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    init {
        // Create a UserHandle for a managed profile.
        val parcel = Parcel.obtain()
        parcel.writeInt(USER_ID_MANAGED)
        parcel.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel)
        parcel.recycle()
    }

    private val TEST_TAG_SELECTION_BAR = "selection_bar"
    private val MEDIA_ITEM =
        Media.Image(
            mediaId = "1",
            pickerId = 1L,
            authority = "a",
            mediaSource = MediaSource.LOCAL,
            mediaUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("media")
                        path("picker")
                        path("a")
                        path("1")
                    }
                    .build(),
            glideLoadableUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("1")
                    }
                    .build(),
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "image/png",
            standardMimeTypeExtension = 1,
        )
    private val localProvider =
        Provider(
            authority = "local_authority",
            mediaSource = MediaSource.LOCAL,
            uid = 1,
            displayName = "Local Provider",
        )
    private val cloudProvider =
        Provider(
            authority = "clout_authority",
            mediaSource = MediaSource.REMOTE,
            uid = 2,
            displayName = "Cloud Provider",
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
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsNotDisplayedInEmbeddedWhenCollapsed_searchFlagOff() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val albumsGridNavButtonLabel =
                resources.getString(R.string.photopicker_albums_nav_button_label)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(
                    hasAnyChild(hasText(photosGridNavButtonLabel)) and
                        hasAnyChild(hasText(albumsGridNavButtonLabel))
                )
                .assertIsNotDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsDisplayedInEmbeddedWhenExpanded_searchFlagOff() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val albumsGridNavButtonLabel =
                resources.getString(R.string.photopicker_albums_nav_button_label)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            // Photos Grid Nav Button and Albums Grid Nav Button
            composeTestRule
                .onNode(hasText(photosGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
            composeTestRule
                .onNode(hasText(albumsGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsDisplayedInEmbeddedWhenExpanded_searchFlagOn() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val categoryGridNavButtonLabel =
                resources.getString(R.string.photopicker_categories_nav_button_label)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            // Photos Grid Nav Button and Category Grid Nav Button
            composeTestRule
                .onNode(hasText(photosGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
            composeTestRule
                .onNode(hasText(categoryGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
        }

    @Test
    fun testSwipeLeftToNavigateDisabledInEmbeddedWhenCollapsed() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to be disabled")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToAlbumWorksInEmbeddedWhenExpanded_searchFlagOff() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToCategoryWorksInEmbeddedWhenExpanded_searchFlagOn() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to CategoryGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.CATEGORY_GRID.route)
        }

    @Test
    fun testProfileSelectorIsNotDisplayedInEmbeddedWhenCollapsed() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    fun testProfileSelectorIsDisplayedInEmbeddedWhenExpanded() =
        testScope.runTest {
            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }
            withContext(Dispatchers.Main) {
                composeTestRule.setContent {
                    CompositionLocalProvider(
                        LocalEmbeddedState provides testEmbeddedStateExpanded
                    ) {
                        callEmbeddedPhotopickerMain(
                            embeddedLifecycle = embeddedLifecycle.get(),
                            featureManager = featureManager.get(),
                            selection = selection.get(),
                            events = events.get(),
                        )
                    }
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testSnackbarIsAlwaysEnabledInEmbedded() {
        assertWithMessage("SnackbarFeature is not always enabled for action pick image")
            .that(
                SnackbarFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(true)
    }

    @Test
    fun testSnackbarDisplaysOnEvent() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                    LocalEmbeddedState provides testEmbeddedStateCollapsed,
                    LocalFeatureManager provides featureManager.get(),
                    LocalSelection provides selection.get(),
                    LocalEvents provides events.get(),
                    LocalEmbeddedLifecycle provides embeddedLifecycle.get(),
                    LocalViewModelStoreOwner provides embeddedLifecycle.get(),
                    LocalOnBackPressedDispatcherOwner provides embeddedLifecycle.get(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                            },
                    ) {
                        PhotopickerApp(
                            disruptiveDataNotification = flow { emit(0) },
                            onMediaSelectionConfirmed = {},
                        )
                    }
                }
            }
            // Advance the UI clock manually to control for the fade animations on the snackbar.
            composeTestRule.mainClock.autoAdvance = false
            val TEST_MESSAGE = "This is a test message"
            events.get().dispatch(Event.ShowSnackbarMessage(FeatureToken.CORE.token, TEST_MESSAGE))
            advanceTimeBy(500)
            // Advance ui clock to allow fade in
            composeTestRule.mainClock.advanceTimeBy(2000L)
            composeTestRule.onNode(hasText(TEST_MESSAGE)).assertIsDisplayed()
            // Advance ui clock to allow fade out
            composeTestRule.mainClock.advanceTimeBy(10_000L)
            composeTestRule.onNode(hasText(TEST_MESSAGE)).assertIsNotDisplayed()
        }

    @Test
    fun testOverflowMenuDisabledInEmbedded() {
        assertWithMessage("Expected OverflowMenuFeature to be disabled in embedded runtime")
            .that(
                OverflowMenuFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun testPreviewDisabledInEmbedded() {
        assertWithMessage("Expected PreviewFeature to be disabled in embedded runtime")
            .that(
                PreviewFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun testBannerHidden_embeddedMode_collapsedState() = runTest {
        configurationManager
            .get()
            .setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
        advanceTimeBy(1000)
        val resources = getTestableContext().getResources()
        val expectedPrivacyMessage =
            resources.getString(R.string.photopicker_privacy_explainer, "Test Package")
        composeTestRule.setContent {
            CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                callEmbeddedPhotopickerMain(
                    embeddedLifecycle = embeddedLifecycle.get(),
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
        }
        composeTestRule.waitForIdle()
        bannerManager.get().showBanner(BannerDefinitions.PRIVACY_EXPLAINER)
        advanceTimeBy(100)
        composeTestRule.onNodeWithText(expectedPrivacyMessage).assertIsNotDisplayed()
    }

    @Test
    fun testBannerShown_embeddedMode_expandedState() = runTest {
        configurationManager
            .get()
            .setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
        val resources = getTestableContext().getResources()
        val expectedPrivacyMessage =
            resources.getString(R.string.photopicker_privacy_explainer, "Test Package")
        composeTestRule.setContent {
            CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                callEmbeddedPhotopickerMain(
                    embeddedLifecycle = embeddedLifecycle.get(),
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
        }
        composeTestRule.waitForIdle()
        bannerManager.get().showBanner(BannerDefinitions.PRIVACY_EXPLAINER)
        advanceTimeBy(100)
        composeTestRule.onNodeWithText(expectedPrivacyMessage).assertIsDisplayed()
    }

    @Test
    fun testSwipeUpInCollapseMode_emptyPhotosGrid_transferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInCollapsedState =
            EmbeddedState(isExpanded = false, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeUp() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
        }
    }

    @Test
    fun testSwipeUpInExpandedMode_emptyPhotosGrid_transferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeUp() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
        }
    }

    @Test
    fun testSwipeDownInExpandedMode_emptyPhotosGrid_transferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeDown() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
        }
    }

    @Test
    fun testSwipeRightInExpandedMode_emptyPhotosGrid_notTransferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeRight() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
        }
    }

    @Test
    fun testPreviewDisabled_onLongPressMediaItem_photosGrid() = runTest {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                callEmbeddedPhotopickerMain(
                    embeddedLifecycle = embeddedLifecycle.get(),
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
        }

        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        composeTestRule
            .onAllNodes(
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )
            )
            .onFirst()
            .performTouchInput { longClick() }

        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        val route = navController.currentBackStackEntry?.destination?.route
        assertWithMessage("Expected preview to be disabled and the current route to be Photo grid.")
            .that(route)
            .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
    }

    @Test
    fun testCloudChooseProviderBannerIsNotVisibleInEmbedded() =
        testScope.runTest {
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

            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = "Test Package",
                )
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.allowedProviders = listOf(cloudProvider)
            testDataService.setAvailableProviders(listOf(localProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_title)
            val expectedMessage =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_message)
            bannerManager.get().refreshBanners()
            advanceTimeBy(100)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }

    @Test
    fun testCloudChooseAccountBannerIsNotVisibleInEmbedded() =
        testScope.runTest {
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

            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = "Test Package",
                )
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = null,
                    accountName = null,
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_account_title)
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_choose_account_message,
                    cloudProvider.displayName,
                )

            bannerManager.get().refreshBanners()
            advanceTimeBy(100)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }
}
