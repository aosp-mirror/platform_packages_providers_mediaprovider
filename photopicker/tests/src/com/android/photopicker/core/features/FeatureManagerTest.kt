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

import android.content.Intent
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
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.features.alwaysdisabledfeature.AlwaysDisabledFeature
import com.android.photopicker.features.highpriorityuifeature.HighPriorityUiFeature
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import src.com.android.photopicker.features.test.prefetchfeature.PrefetchFeature

/** Unit tests for the [FeatureManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureManagerTest {

    // A test event that no features dispatch.
    data class TestEventDoNotUse(override val dispatcherToken: String) : Event

    @get:Rule val composeTestRule = createComposeRule()

    val testRegistrations =
        setOf(
            SimpleUiFeature.Registration,
            HighPriorityUiFeature.Registration,
            AlwaysDisabledFeature.Registration,
            PrefetchFeature.Registration,
        )

    val sessionId = generatePickerSessionId()

    @Composable
    private fun featureManagerTestUiComposeTop(
        featureManager: FeatureManager,
        maxSlots: Int? = null,
        params: LocationParams = LocationParams.None,
    ) {
        // Mark this node as COMPOSE_TOP
        Surface(modifier = Modifier.fillMaxSize().testTag("COMPOSE_TOP")) {
            featureManager.composeLocation(Location.COMPOSE_TOP, maxSlots, Modifier, params)
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
                    TestPrefetchDataService(),
                    testRegistrations,
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
                )

            // Expect only the [SimpleUiFeature], [HighPriorityUiFeature] and [PrefetchFeature]
            // to be enabled.
            assertThat(featureManager.enabledFeatures.size).isEqualTo(3)
            assertThat(featureManager.enabledFeatures.any { it is SimpleUiFeature }).isTrue()
            assertThat(featureManager.enabledFeatures.any { it is HighPriorityUiFeature }).isTrue()
            assertThat(featureManager.enabledFeatures.any { it is PrefetchFeature }).isTrue()
        }
    }

    /* Ensures feature manager correctly reports the size of its location registry. */
    @Test
    fun testGetSizeOfLocationInRegistry() = runTest {
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(scope = this.backgroundScope),
                this.backgroundScope,
                TestPrefetchDataService(),
                testRegistrations,
                /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
            )

        // All three features in testRegistrations use this location, but [AlwaysDisabledFeature]
        // is always disabled, so it won't ever be present in the location registry.
        assertThat(featureManager.getSizeOfLocationInRegistry(Location.COMPOSE_TOP)).isEqualTo(2)

        val featureManagerTwo =
            FeatureManager(
                provideTestConfigurationFlow(scope = this.backgroundScope),
                this.backgroundScope,
                TestPrefetchDataService(),
                /*registeredFeatures=*/ emptySet(),
                /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
            )
        assertThat(featureManagerTwo.getSizeOfLocationInRegistry(Location.COMPOSE_TOP)).isEqualTo(0)
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
                    TestPrefetchDataService(),
                    testRegistrations,
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
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
                    TestPrefetchDataService(),
                    testRegistrations,
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
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

                override fun isEnabled(
                    config: PhotopickerConfiguration,
                    deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
                ) = true

                override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
            }

        val configFlow =
            MutableStateFlow(
                TestPhotopickerConfiguration.build {
                    action("TEST_ACTION")
                    intent(Intent("TEST_ACTION"))
                    sessionId(1234)
                }
            )

        runTest {
            FeatureManager(
                configFlow.stateIn(backgroundScope, SharingStarted.Eagerly, configFlow.value),
                backgroundScope,
                TestPrefetchDataService(),
                setOf(mockRegistration),
                /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
            )

            advanceTimeBy(100) // Wait for initialization
            val updatedConfig =
                TestPhotopickerConfiguration.build {
                    action("SOME_OTHER_ACTION")
                    intent(Intent("SOME_OTHER_ACTION"))
                    sessionId(1234)
                }
            configFlow.update { updatedConfig }
            advanceTimeBy(100) // Wait for the update to reach the StateFlow

            // The feature should have received a call with the new configuration
            verify(mockSimpleUiFeature).onConfigurationChanged(updatedConfig)
        }
    }

    /* Ensures that the [FeatureManager] does crash non production configurations when
     * an event that needs to be consumed is not produced. */
    @Test
    fun testFeatureManagerConsumedEventsRequireProducerThrowsDebug() {
        // Mock out a feature and provide a fake registration that provides the mock.
        val mockSimpleUiFeature: SimpleUiFeature = mock(SimpleUiFeature::class.java)
        val mockRegistration =
            object : FeatureRegistration {
                override val TAG = "MockedFeature"

                override fun isEnabled(
                    config: PhotopickerConfiguration,
                    deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
                ) = true

                override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
            }

        val configFlow =
            MutableStateFlow(
                PhotopickerConfiguration(
                    action = "TEST",
                    deviceIsDebuggable = true,
                    sessionId = sessionId,
                )
            )

        whenever(mockSimpleUiFeature.eventsConsumed) { setOf(TestEventDoNotUse::class.java) }
        whenever(mockSimpleUiFeature.eventsProduced) { setOf<RegisteredEventClass>() }

        runTest {
            assertThrows(IllegalStateException::class.java) {
                FeatureManager(
                    configFlow.stateIn(backgroundScope, SharingStarted.Eagerly, configFlow.value),
                    backgroundScope,
                    TestPrefetchDataService(),
                    setOf(mockRegistration),
                )
            }
        }
    }

    /* Ensures that the [FeatureManager] does not crash production configurations when
     * an event that needs to be consumed is not produced. */
    @Test
    fun testFeatureManagerConsumedEventsRequireProducerDoesNotThrowProduction() {
        // Mock out a feature and provide a fake registration that provides the mock.
        val mockSimpleUiFeature: SimpleUiFeature = mock(SimpleUiFeature::class.java)
        val mockRegistration =
            object : FeatureRegistration {
                override val TAG = "MockedFeature"

                override fun isEnabled(
                    config: PhotopickerConfiguration,
                    deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
                ) = true

                override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
            }

        val configFlow =
            MutableStateFlow(
                PhotopickerConfiguration(
                    action = "TEST",
                    deviceIsDebuggable = false,
                    sessionId = sessionId,
                )
            )

        whenever(mockSimpleUiFeature.eventsConsumed) { setOf(TestEventDoNotUse::class.java) }
        whenever(mockSimpleUiFeature.eventsProduced) { setOf<RegisteredEventClass>() }

        runTest {
            try {
                FeatureManager(
                    configFlow.stateIn(backgroundScope, SharingStarted.Eagerly, configFlow.value),
                    backgroundScope,
                    TestPrefetchDataService(),
                    setOf(mockRegistration),
                )
            } catch (e: IllegalStateException) {
                fail("IllegalStateException was thrown in a production configuration.")
            }
        }
    }

    @Test
    fun testFeatureManagerComposeLocationWithLocationParams() {
        runTest {
            val featureManager =
                FeatureManager(
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    testRegistrations,
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
                )

            val deferred = CompletableDeferred<Boolean>()

            composeTestRule.setContent {
                featureManagerTestUiComposeTop(
                    featureManager,
                    null,
                    params = LocationParams.WithClickAction { deferred.complete(true) },
                )
            }

            composeTestRule
                .onNodeWithText(SimpleUiFeature.Registration.UI_STRING)
                .assertIsDisplayed()
                .performClick()

            val wasClicked = deferred.await()

            assertWithMessage("Expected WithClickAction to run, but it did not")
                .that(wasClicked)
                .isTrue()
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
                    TestPrefetchDataService(),
                    testRegistrations,
                    /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
                    /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
                )

            // Expect only the [SimpleUiFeature] and [HighPriorityUiFeature] to be enabled.
            assertThat(featureManager.isFeatureEnabled(SimpleUiFeature::class.java)).isTrue()
            assertThat(featureManager.isFeatureEnabled(HighPriorityUiFeature::class.java)).isTrue()
            assertThat(featureManager.isFeatureEnabled(AlwaysDisabledFeature::class.java)).isFalse()
        }
    }
}
