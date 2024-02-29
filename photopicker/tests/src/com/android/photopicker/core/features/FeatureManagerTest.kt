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

package com.android.photopicker.core.features

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.configuration.testPhotopickerConfiguration
import com.android.photopicker.features.alwaysdisabledfeature.AlwaysDisabledFeature
import com.android.photopicker.features.highpriorityuifeature.HighPriorityUiFeature
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/** Unit tests for the [FeatureManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureManagerTest {

    @get:Rule val composeTestRule = createComposeRule()

    val testRegistrations =
        setOf(
            SimpleUiFeature.Registration,
            HighPriorityUiFeature.Registration,
            AlwaysDisabledFeature.Registration,
        )

    @Composable
    private fun featureManagerTestUiComposeTop(
        featureManager: FeatureManager,
        maxSlots: Int? = null
    ) {

        // Mark this node as COMPOSE_TOP
        Surface(modifier = Modifier.fillMaxSize().testTag("COMPOSE_TOP")) {
            featureManager.composeLocation(Location.COMPOSE_TOP, maxSlots)
        }
    }

    /* Ensures feature registration is completed upon initialization. */
    @Test
    fun testRegisteredFeaturesCanBeEnabled() {

        runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    testRegistrations,
                )

            // Expect only the [SimpleUiFeature] and [HighPriorityUiFeature] to be enabled.
            assertThat(featureManager.enabledFeatures.size).isEqualTo(2)
            assertThat(featureManager.enabledFeatures.first() is SimpleUiFeature).isTrue()
            assertThat(featureManager.enabledFeatures.last() is HighPriorityUiFeature).isTrue()
        }
    }

    /* Ensures that the [FeatureManager] composes content for registered features, according
     * to priority. */
    @Test
    fun testFeaturesCanComposeAtRegisteredLocationsWithPriorities() {

        runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    testRegistrations,
                )

            composeTestRule.setContent { featureManagerTestUiComposeTop(featureManager) }

            composeTestRule
                .onNodeWithText(HighPriorityUiFeature.Registration.UI_STRING)
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(SimpleUiFeature.Registration.UI_STRING)
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(AlwaysDisabledFeature.Registration.UI_STRING)
                .assertDoesNotExist()

            // Ensure the ordering of the feature's composables is correct.
            composeTestRule
                .onNode(hasTestTag("COMPOSE_TOP"))
                .onChildren()
                .assertCountEquals(2)
                .let { children ->
                    children[0].assertTextEquals(HighPriorityUiFeature.Registration.UI_STRING)
                    children[1].assertTextEquals(SimpleUiFeature.Registration.UI_STRING)
                }
        }
    }

    /* Ensures that the [FeatureManager] composes content for registered features. */
    @Test
    fun testFeaturesCanComposeAtRegisteredLocationsWithLimitedSlots() {

        runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    testRegistrations,
                )

            composeTestRule.setContent {
                featureManagerTestUiComposeTop(featureManager, maxSlots = 1)
            }

            composeTestRule
                .onNodeWithText(HighPriorityUiFeature.Registration.UI_STRING)
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(SimpleUiFeature.Registration.UI_STRING)
                .assertDoesNotExist()
            composeTestRule
                .onNodeWithText(AlwaysDisabledFeature.Registration.UI_STRING)
                .assertDoesNotExist()

            composeTestRule.onNode(hasTestTag("COMPOSE_TOP")).onChildren().assertCountEquals(1)
        }
    }

    /* Ensures that the [FeatureManager] notifies enabled features of a pending configuration
     * change. */
    @Test
    fun testFeatureManagerOnConfigurationChanged() {

        // Mock out a feature and provide a fake registration that provides the mock.
        val mockSimpleUiFeature: SimpleUiFeature = mock(SimpleUiFeature::class.java)
        val mockRegistration =
            object : FeatureRegistration {
                override val TAG = "MockedFeature"
                override fun isEnabled(config: PhotopickerConfiguration) = true
                override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
            }

        val configFlow = MutableStateFlow(testPhotopickerConfiguration)

        runTest {
            FeatureManager(
                configFlow.stateIn(backgroundScope, SharingStarted.Eagerly, configFlow.value),
                backgroundScope,
                setOf(mockRegistration)
            )

            advanceTimeBy(100) // Wait for initialization
            configFlow.update { it.copy(action = "SOME_OTHER_ACTION") }
            advanceTimeBy(100) // Wait for the update to reach the StateFlow

            // The feature should have received a call with the new configuration
            verify(mockSimpleUiFeature)
                .onConfigurationChanged(
                    testPhotopickerConfiguration.copy(action = "SOME_OTHER_ACTION")
                )
        }
    }

    /* Ensure the isEnabled api can correctly return which features are enabled. */
    @Test
    fun testFeatureManagerIsFeatureEnabled() {

        runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    testRegistrations,
                )

            // Expect only the [SimpleUiFeature] and [HighPriorityUiFeature] to be enabled.
            assertThat(featureManager.isFeatureEnabled(SimpleUiFeature::class.java)).isTrue()
            assertThat(featureManager.isFeatureEnabled(HighPriorityUiFeature::class.java)).isTrue()
            assertThat(featureManager.isFeatureEnabled(AlwaysDisabledFeature::class.java)).isFalse()
        }
    }
}
