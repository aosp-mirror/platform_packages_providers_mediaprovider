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

package com.android.photopicker.core.components

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.theme.PhotopickerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class EmptyStateTest {

    companion object {
        private val EMPTY_STATE_TEST_TITLE = "No photos yet"
        private val EMPTY_STATE_TEST_BODY = "Take some more photos!"
    }

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun testEmptyStateDisplaysTitleAndBody() {
        composeTestRule.setContent {
            CompositionLocalProvider(

                // Required for PhotopickerTheme
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                // PhotopickerTheme is needed for CustomAccentColor support
                PhotopickerTheme(
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Image,
                        title = EMPTY_STATE_TEST_TITLE,
                        body = EMPTY_STATE_TEST_BODY,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(EMPTY_STATE_TEST_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY_STATE_TEST_BODY).assertIsDisplayed()
    }
}
