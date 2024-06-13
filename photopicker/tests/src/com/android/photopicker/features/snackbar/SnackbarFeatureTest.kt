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

package com.android.photopicker.features.snackbar

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.test.mock.MockContentResolver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.testActionPickImagesConfiguration
import com.android.photopicker.core.configuration.testGetContentConfiguration
import com.android.photopicker.core.configuration.testUserSelectImagesForAppConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken.CORE
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
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
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class SnackbarFeatureTest : PhotopickerFeatureBaseTest() {

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

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    lateinit var mockContentResolver: ContentResolver

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var configurationManager: ConfigurationManager
    @Inject lateinit var events: Events

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }
        mockContentResolver = MockContentResolver(mockContext)

        setupTestForUserMonitor(
            mockContext,
            mockUserManager,
            mockContentResolver,
            mockPackageManager
        )
    }

    @Test
    fun testSnackbarIsAlwaysEnabled() {

        assertWithMessage("SnackbarFeature is not always enabled for action pick image")
            .that(SnackbarFeature.Registration.isEnabled(testActionPickImagesConfiguration))
            .isEqualTo(true)

        assertWithMessage("SnackbarFeature is not always enabled for get content")
            .that(SnackbarFeature.Registration.isEnabled(testGetContentConfiguration))
            .isEqualTo(true)

        assertWithMessage("SnackbarFeature is not always enabled for user select images")
            .that(SnackbarFeature.Registration.isEnabled(testUserSelectImagesForAppConfiguration))
            .isEqualTo(true)
    }

    @Test
    fun testSnackbarDisplaysOnEvent() =
        mainScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalNavController provides createNavController(),
                ) {
                    LocalFeatureManager.current.composeLocation(
                        Location.SNACK_BAR,
                        maxSlots = 1,
                    )
                }
            }

            // Advance the UI clock manually to control for the fade animations on the snackbar.
            composeTestRule.mainClock.autoAdvance = false

            val TEST_MESSAGE = "This is a test message"
            events.dispatch(Event.ShowSnackbarMessage(CORE.token, TEST_MESSAGE))
            advanceTimeBy(500)

            // Advance ui clock to allow fade in
            composeTestRule.mainClock.advanceTimeBy(2000L)
            composeTestRule.onNode(hasText(TEST_MESSAGE)).assertIsDisplayed()

            // Advance ui clock to allow fade out
            composeTestRule.mainClock.advanceTimeBy(10_000L)
            composeTestRule.onNode(hasText(TEST_MESSAGE)).assertIsNotDisplayed()
        }
}
