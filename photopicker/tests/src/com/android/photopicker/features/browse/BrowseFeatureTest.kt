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

package com.android.photopicker.features.browse

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class BrowseFeatureTest : PhotopickerFeatureBaseTest() {

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

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()

    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var events: Lazy<Events>
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)

        val testIntent = Intent(Intent.ACTION_GET_CONTENT)
        configurationManager.get().setIntent(testIntent)
    }

    @Test
    fun testBrowseEnabledInConfigurations() {
        assertWithMessage("BrowseFeature is enabled (ACTION_PICK_IMAGES)")
            .that(
                BrowseFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("BrowseFeature is not always enabled (ACTION_GET_CONTENT)")
            .that(
                BrowseFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("BrowseFeature is enabled (USER_SELECT_FOR_APP)")
            .that(
                BrowseFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("BrowseFeature is enabled in Embedded configurations")
            .that(
                BrowseFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun testBrowseOverflowMenuItemIsDisplayed() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            assertWithMessage("OverflowMenuFeature is not enabled")
                .that(featureManager.get().isFeatureEnabled(OverflowMenuFeature::class.java))
                .isTrue()

            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_menu_description)
                    )
                )
                .performClick()

            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_browse)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testBrowseOverflowMenuItemDispatchedEvent() =
        testScope.runTest {
            val eventDispatches = mutableListOf<Event>()
            backgroundScope.launch { events.get().flow.toList(eventDispatches) }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            assertWithMessage("OverflowMenuFeature is not enabled")
                .that(featureManager.get().isFeatureEnabled(OverflowMenuFeature::class.java))
                .isTrue()

            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_menu_description)
                    )
                )
                .performClick()

            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_browse)
                    )
                )
                .performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Expected BrowseToDocumentsUI event to be dispatched")
                .that(eventDispatches)
                .contains(Event.BrowseToDocumentsUi(FeatureToken.BROWSE.token))
        }
}
