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

import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.embedded.EmbeddedStateManager
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.embedded.testEmbeddedStateCollapsed
import com.android.photopicker.core.embedded.testEmbeddedStateExpanded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [hideWhenState] composable. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HideWhenStateTest {

    private val TEST_COMPOSABLE_TAG = "test_composable"
    private val ENTER_ANIMATION =
        expandVertically(animationSpec = tween(durationMillis = 500)) +
            fadeIn(animationSpec = tween(durationMillis = 750))
    private val EXIT_ANIMATION =
        shrinkVertically(animationSpec = tween(durationMillis = 500)) + fadeOut()

    @get:Rule val composeTestRule = createComposeRule()

    @Composable
    private fun mockComposable() {
        Text(modifier = Modifier.testTag(TEST_COMPOSABLE_TAG), text = "composable for testing")
    }

    @Test
    fun testShowsContentWhenRuntimeIsActivityWithSelectorEmbedded() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
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
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
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
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
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
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
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
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
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
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateCollapsed,
            ) {
                hideWhenState(StateSelector.EmbeddedAndCollapsed) { mockComposable() }
            }
        }

        val content = composeTestRule.onNode(hasTestTag(TEST_COMPOSABLE_TAG))
        content.assertIsNotDisplayed()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testAnimatedVisibilityWhenRuntimeIsEmbeddedStateIsAnimatedVisibilityInEmbedded() = runTest {
        // EmbeddedStateManager is used because we need to update the expanded state
        val embeddedStateManagerTest = EmbeddedStateManager()
        composeTestRule.setContent {
            val embeddedState by embeddedStateManagerTest.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides embeddedState,
            ) {
                hideWhenState(
                    selector =
                        object : StateSelector.AnimatedVisibilityInEmbedded {
                            override val visible = LocalEmbeddedState.current?.isExpanded ?: false
                            override val enter = ENTER_ANIMATION
                            override val exit = EXIT_ANIMATION
                        }
                ) {
                    mockComposable()
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_COMPOSABLE_TAG).assertIsNotDisplayed()

        async { embeddedStateManagerTest.setIsExpanded(true) }.await()
        advanceTimeBy(500)
        composeTestRule.onNodeWithTag(TEST_COMPOSABLE_TAG).assertIsDisplayed()
    }

    @Test
    fun testDirectVisibilityWhenRuntimeIsActivityStateIsAnimatedVisibilityInEmbedded() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                hideWhenState(
                    selector =
                        object : StateSelector.AnimatedVisibilityInEmbedded {
                            // overriding the following values has no affect on the visibility of
                            // the composable because it is in Activity runtime env.
                            override val visible = false
                            override val enter = ENTER_ANIMATION
                            override val exit = EXIT_ANIMATION
                        }
                ) {
                    mockComposable()
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_COMPOSABLE_TAG).assertIsDisplayed()
    }
}
