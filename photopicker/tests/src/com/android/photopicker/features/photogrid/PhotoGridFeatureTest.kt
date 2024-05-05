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

package com.android.photopicker.features.photogrid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class PhotoGridFeatureTest : PhotopickerFeatureBaseTest() {

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
     * PhotoGrid uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider

    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        hiltRule.inject()

        // Stub out the content resolver for Glide
        provider = MockContentProviderWrapper(mockContentProvider)
        contentResolver = ContentResolver.wrap(provider)

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        // Stubs for UserMonitor
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        whenever(mockContext.contentResolver) { contentResolver }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.packageName) { "com.android.photopicker" }

        // Recursively return the same mockContext for all user packages to keep the stubing simple.
        whenever(
            mockContext.createPackageContextAsUser(
                anyString(),
                anyInt(),
                any(UserHandle::class.java)
            )
        ) {
            mockContext
        }
    }

    @Test
    fun testPhotoGridIsAlwaysEnabled() {

        val configOne = PhotopickerConfiguration(action = "TEST_ACTION")
        assertWithMessage("PhotoGridFeature is not always enabled for TEST_ACTION")
            .that(PhotoGridFeature.Registration.isEnabled(configOne))
            .isEqualTo(true)

        val configTwo = PhotopickerConfiguration(action = MediaStore.ACTION_PICK_IMAGES)
        assertWithMessage("PhotoGridFeature is not always enabled")
            .that(PhotoGridFeature.Registration.isEnabled(configTwo))
            .isEqualTo(true)

        val configThree = PhotopickerConfiguration(action = Intent.ACTION_GET_CONTENT)
        assertWithMessage("PhotoGridFeature is not always enabled")
            .that(PhotoGridFeature.Registration.isEnabled(configThree))
            .isEqualTo(true)
    }

    @Test
    fun testPhotoGridIsTheInitialRoute() {

        // Explicitly create a new feature manager that uses the same production feature
        // registrations to ensure this test will fail if the default production behavior changes.
        val featureManager =
            FeatureManager(
                registeredFeatures = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                scope = testBackgroundScope,
                configuration = provideTestConfigurationFlow(scope = testBackgroundScope)
            )

        mainScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceUntilIdle()

            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Initial route is not the PhotoGridFeature")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }
    }

    @Test
    fun testPhotosCanBeSelected() {

        val resources = getTestableContext().getResources()
        val mediaItemString = resources.getString(R.string.photopicker_media_item)
        val selectedString = resources.getString(R.string.photopicker_item_selected)

        mainScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            assertWithMessage("Expected selection to initially be empty.")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodesWithContentDescription(mediaItemString)
                .onFirst()
                .performClick()

            // Wait for PhotoGridViewModel to modify Selection
            advanceTimeBy(100)

            // Ensure the selected semantics got applied to the selected node.
            composeTestRule.waitUntilAtLeastOneExists(hasContentDescription(selectedString))
            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }
    }

    @Test
    fun testPhotosAreDisplayed() {

        val resources = getTestableContext().getResources()
        val mediaItemString = resources.getString(R.string.photopicker_media_item)

        mainScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodesWithContentDescription(mediaItemString)
                .onFirst()
                .assert(hasClickAction())
                .assertIsDisplayed()
        }
    }
}
