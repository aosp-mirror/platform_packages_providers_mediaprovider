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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.test.mock.MockContentResolver
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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.PhotopickerApp
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.testEmbeddedPhotopickerConfiguration
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
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.features.snackbar.SnackbarFeature
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.inject.TestOptions
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
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

    @Inject lateinit var events: Events
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var userHandle: UserHandle
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var embeddedLifecycle: EmbeddedLifecycle
    @Inject lateinit var databaseManager: DatabaseManager
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10

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
    fun testNavigationBarIsNotDisplayedInEmbeddedWhenCollapsed() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val albumsGridNavButtonLabel =
                resources.getString(R.string.photopicker_albums_nav_button_label)

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                    LocalEmbeddedState provides testEmbeddedStateCollapsed,
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle,
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
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
    fun testNavigationBarIsDisplayedInEmbeddedWhenExpanded() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val albumsGridNavButtonLabel =
                resources.getString(R.string.photopicker_albums_nav_button_label)

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                    LocalEmbeddedState provides testEmbeddedStateExpanded,
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle,
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
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
    fun testSwipeLeftToNavigateDisabledInEmbeddedWhenCollapsed() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val mediaItemString = resources.getString(R.string.photopicker_media_item)

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                    LocalEmbeddedState provides testEmbeddedStateCollapsed,
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle,
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodesWithContentDescription(mediaItemString)
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to be disabled")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    fun testSwipeLeftToAlbumWorksInEmbeddedWhenExpanded() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val mediaItemString = resources.getString(R.string.photopicker_media_item)

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                    LocalEmbeddedState provides testEmbeddedStateExpanded,
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle,
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodesWithContentDescription(mediaItemString)
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }

    @Test
    fun testProfileSelectorIsNotDisplayedInEmbeddedWhenCollapsed() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                    LocalEmbeddedState provides testEmbeddedStateCollapsed,
                ) {
                    callEmbeddedPhotopickerMain(
                        embeddedLifecycle = embeddedLifecycle,
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
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
                            .getString(R.string.photopicker_profile_switch_button_description)
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
                        LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                        LocalEmbeddedState provides testEmbeddedStateExpanded,
                    ) {
                        callEmbeddedPhotopickerMain(
                            embeddedLifecycle = embeddedLifecycle,
                            featureManager = featureManager,
                            selection = selection,
                            events = events,
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
                            .getString(R.string.photopicker_profile_switch_button_description)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testSnackbarIsAlwaysEnabledInEmbedded() {

        assertWithMessage("SnackbarFeature is not always enabled for action pick image")
            .that(SnackbarFeature.Registration.isEnabled(testEmbeddedPhotopickerConfiguration))
            .isEqualTo(true)
    }

    @Test
    fun testSnackbarDisplaysOnEvent() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                    LocalEmbeddedState provides testEmbeddedStateCollapsed,
                    LocalFeatureManager provides featureManager,
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalEmbeddedLifecycle provides embeddedLifecycle,
                    LocalViewModelStoreOwner provides embeddedLifecycle,
                    LocalOnBackPressedDispatcherOwner provides embeddedLifecycle,
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config = testEmbeddedPhotopickerConfiguration
                    ) {
                        PhotopickerApp(disruptiveDataNotification = flow { emit(0) })
                    }
                }
            }

            // Advance the UI clock manually to control for the fade animations on the snackbar.
            composeTestRule.mainClock.autoAdvance = false

            val TEST_MESSAGE = "This is a test message"
            events.dispatch(Event.ShowSnackbarMessage(FeatureToken.CORE.token, TEST_MESSAGE))
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
            .that(OverflowMenuFeature.Registration.isEnabled(testEmbeddedPhotopickerConfiguration))
            .isEqualTo(false)
    }

    @Test
    fun testPreviewDisabledInEmbedded() {

        assertWithMessage("Expected PreviewFeature to be disabled in embedded runtime")
            .that(PreviewFeature.Registration.isEnabled(testEmbeddedPhotopickerConfiguration))
            .isEqualTo(false)
    }
}
