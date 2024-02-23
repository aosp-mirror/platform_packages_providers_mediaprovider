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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the main PhotopickerApp composable. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PhotopickerAppTest {

    @get:Rule val composeTestRule = createComposeRule()

    /** Unit test for the top level [PhotopickerApp] composable */
    @Test
    fun testPhotopickerApp() {

        composeTestRule.setContent {

            // Stub FeatureManager. This is usually injected above this composable and provided
            // via [LocalFeatureManager].
            val featureManager =
                FeatureManager(
                    PhotopickerConfiguration(action = "TEST_ACTION"),
                    TestScope(),
                    emptySet()
                )

            // Normally, this is provided in the activity's setContent block since this is
            // injected into the activity.
            CompositionLocalProvider(LocalFeatureManager provides featureManager) {
                PhotopickerApp()
            }
        }

        composeTestRule.onNodeWithText("Hello World from Photopicker!").assertIsDisplayed()
    }
}
