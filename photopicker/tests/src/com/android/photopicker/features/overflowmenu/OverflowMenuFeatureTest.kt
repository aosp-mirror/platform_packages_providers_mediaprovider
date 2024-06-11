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

package com.android.photopicker.features.overflowmenu

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.test.mock.MockContentResolver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.configuration.testActionPickImagesConfiguration
import com.android.photopicker.core.configuration.testGetContentConfiguration
import com.android.photopicker.core.configuration.testUserSelectImagesForAppConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken.OVERFLOW_MENU
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
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
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class OverflowMenuFeatureTest : PhotopickerFeatureBaseTest() {

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

    val contentResolver: ContentResolver = MockContentResolver()

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @Test
    fun testOverflowMenuEnabledInConfigurations() {

        assertWithMessage("OverflowMenuFeature is not always enabled (ACTION_PICK_IMAGES)")
            .that(OverflowMenuFeature.Registration.isEnabled(testActionPickImagesConfiguration))
            .isEqualTo(true)

        assertWithMessage("OverflowMenuFeature is not always enabled (ACTION_GET_CONTENT)")
            .that(OverflowMenuFeature.Registration.isEnabled(testGetContentConfiguration))
            .isEqualTo(true)

        assertWithMessage("OverflowMenuFeature is not always enabled (USER_SELECT_FOR_APP)")
            .that(
                OverflowMenuFeature.Registration.isEnabled(testUserSelectImagesForAppConfiguration)
            )
            .isEqualTo(true)
    }

    @Test
    fun testOverflowMenuAnchorShownIfMenuItemsExist() =
        mainScope.runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    setOf(
                        SimpleUiFeature.Registration,
                        OverflowMenuFeature.Registration,
                    ),
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
                )

            val events =
                Events(
                    this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalEvents provides events,
                ) {
                    featureManager.composeLocation(Location.OVERFLOW_MENU)
                }
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_menu_description)
                    )
                )
                .assert(hasClickAction())
                .assertIsDisplayed()
        }

    @Test
    fun testOverflowMenuAnchorHiddenWhenNoMenuItemsExist() =
        mainScope.runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    setOf(
                        OverflowMenuFeature.Registration,
                    ),
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
                )

            val events =
                Events(
                    this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalEvents provides events,
                ) {
                    featureManager.composeLocation(Location.OVERFLOW_MENU)
                }
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_menu_description)
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    fun testOverflowMenuIsHiddenAfterItemSelected() =
        mainScope.runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    setOf(
                        SimpleUiFeature.Registration,
                        OverflowMenuFeature.Registration,
                    ),
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
                )
            val events =
                Events(
                    this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalEvents provides events,
                ) {
                    featureManager.composeLocation(Location.OVERFLOW_MENU)
                }
            }
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
                .onNode(hasText(SimpleUiFeature.Registration.BUTTON_LABEL))
                .assertIsDisplayed()
                .performClick()

            advanceTimeBy(100)

            composeTestRule
                .onNode(hasText(SimpleUiFeature.Registration.BUTTON_LABEL))
                .assertIsNotDisplayed()
        }
}
