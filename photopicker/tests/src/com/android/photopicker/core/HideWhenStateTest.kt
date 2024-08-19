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

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.testEmbeddedPhotopickerConfiguration
import com.android.photopicker.core.configuration.testPhotopickerConfiguration
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.embedded.testEmbeddedStateCollapsed
import com.android.photopicker.core.embedded.testEmbeddedStateExpanded
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [hideWhenState] composable. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HideWhenStateTest {

    private val TEST_COMPOSABLE_TAG = "test_composable"

    @get:Rule val composeTestRule = createComposeRule()

    @Composable
    private fun mockComposable() {
        Text(
            modifier = Modifier.testTag(TEST_COMPOSABLE_TAG),
            text = "composable for testing",
        )
    }

    @Test
    fun testShowsContentWhenRuntimeIsActivityWithSelectorEmbedded() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testPhotopickerConfiguration
            ) {
                hideWhenState(StateSelector.Embedded) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsDisplayed()
    }

    @Test
    fun testShowsContentWhenRuntimeIsActivityWithSelectorEmbeddedAndCollapsed() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testPhotopickerConfiguration
            ) {
                hideWhenState(StateSelector.EmbeddedAndCollapsed) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsDisplayed()
    }

    @Test
    fun testHidesContentWhenRuntimeIsEmbeddedStateIsExpandedSelectorIsEmbedded() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                LocalEmbeddedState provides testEmbeddedStateExpanded,
            ) {
                hideWhenState(StateSelector.Embedded) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsNotDisplayed()
    }

    @Test
    fun testHidesContentWhenRuntimeIsEmbeddedStateIsCollapsedSelectorIsEmbedded() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                LocalEmbeddedState provides testEmbeddedStateCollapsed,
            ) {
                hideWhenState(StateSelector.Embedded) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsNotDisplayed()
    }

    @Test
    fun testShowsContentWhenRuntimeIsEmbeddedStateIsExpandedSelectorIsEmbeddedAndCollapsed() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                LocalEmbeddedState provides testEmbeddedStateExpanded,
            ) {
                hideWhenState(StateSelector.EmbeddedAndCollapsed) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsDisplayed()
    }

    @Test
    fun testHidesContentWhenRuntimeIsEmbeddedStateIsCollapsedSelectorIsEmbeddedAndCollapsed() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testEmbeddedPhotopickerConfiguration,
                LocalEmbeddedState provides testEmbeddedStateCollapsed,
            ) {
                hideWhenState(StateSelector.EmbeddedAndCollapsed) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsNotDisplayed()
    }
}
