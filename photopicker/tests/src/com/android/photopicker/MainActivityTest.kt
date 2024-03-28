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

package com.android.photopicker

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.Background
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.inject.PhotopickerTestModule
import com.google.common.truth.Truth.assertWithMessage
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** This test class will run Photopicker's actual MainActivity. */
@UninstallModules(
    ActivityModule::class,
)
@HiltAndroidTest
class MainActivityTest {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = MainActivity::class.java)

    val testDispatcher = StandardTestDispatcher()
    /** Overrides for ActivityModule */
    @BindValue @Main val mainScope: TestScope = TestScope(testDispatcher)
    @BindValue @Background var testBackgroundScope: CoroutineScope = mainScope.backgroundScope

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    @Inject lateinit var configurationManager: ConfigurationManager

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun testMainActivitySetsActivityAction() {
        mainScope.runTest {
            val expectedAction = "android.intent.action.MAIN"

            assertWithMessage("Expected configuration to contain an action")
                .that(configurationManager.configuration.first().action)
                .isEqualTo(expectedAction)
        }
    }
}
