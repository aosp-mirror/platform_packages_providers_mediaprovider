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

package com.android.photopicker.features.navigationbar

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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
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
class NavigationBarFeatureTest : PhotopickerFeatureBaseTest() {
    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()

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

    /* Ensures the NavigationBar is drawn with the production registered features. */
    @Test
    fun testNavigationBarProductionConfig() {
        assertWithMessage("NavigationBar is not always enabled for TEST_ACTION")
            .that(
                NavigationBarFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("NavigationBar is not always enabled")
            .that(
                NavigationBarFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("NavigationBar is not always enabled")
            .that(
                NavigationBarFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("NavigationBar is not always enabled")
            .that(
                NavigationBarFeature.Registration.isEnabled(
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

    /* Verify Navigation Bar contains tabs for both photos and albums grid.*/
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsVisibleWithFeatureTabs_searchFlagOff() {
        // Explicitly create a new feature manager that uses the same production feature
        // registrations to ensure this test will fail if the default production behavior changes.
        featureManager =
            FeatureManager(
                registeredFeatures = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                scope = testBackgroundScope,
                prefetchDataService = TestPrefetchDataService(),
                configuration = provideTestConfigurationFlow(scope = testBackgroundScope),
            )

        val photosGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_photos_nav_button_label)
        val albumsGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_albums_nav_button_label)

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

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
    }

    /* Verify Navigation Bar contains tabs for both photos and category grid.*/
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsVisibleWithFeatureTabs_searchFlagOn() {
        // Explicitly create a new feature manager that uses the same production feature
        // registrations to ensure this test will fail if the default production behavior changes.
        featureManager =
            FeatureManager(
                registeredFeatures = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                scope = testBackgroundScope,
                prefetchDataService = TestPrefetchDataService(),
                configuration = provideTestConfigurationFlow(scope = testBackgroundScope),
            )

        val photosGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_photos_nav_button_label)
        val categoryGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_categories_nav_button_label)

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

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
    }

    /* Verify Navigation Bar when search flag disabled contains tabs for both photos and albums grid.*/
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBar_withSearchFlagDisabled_IsVisibleWithFeatureTabs() {
        val photosGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_photos_nav_button_label)
        val albumsGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_albums_nav_button_label)

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

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
    }

    /* Verify Navigation Bar when search flag enabled contains tabs for both photos and category grid.*/
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBar_withSearchFlagEnabled_IsVisibleWithFeatureTabs() {
        val photosGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_photos_nav_button_label)
        val categoryGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_categories_nav_button_label)

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            composeTestRule.waitForIdle()

            // Photos Grid Nav Button and Albums Grid Nav Button
            composeTestRule
                .onNode(hasText(photosGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())

            composeTestRule
                .onNode(hasText(categoryGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
        }
    }
}
