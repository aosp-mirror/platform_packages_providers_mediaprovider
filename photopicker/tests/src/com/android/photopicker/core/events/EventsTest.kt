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

package com.android.photopicker.core.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.configuration.testPhotopickerConfiguration
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

/** Unit tests for [Events] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class EventsTest {

    private val mockSimpleUiFeature: SimpleUiFeature = mock(SimpleUiFeature::class.java)
    private val mockRegistration =
        object : FeatureRegistration {
            override val TAG = "MockedFeature"
            override fun isEnabled(config: PhotopickerConfiguration) = true
            override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
            val token = "MockedFeatureToken"
        }
    private val testRegistrations = setOf(mockRegistration)

    @Before
    fun setup() {

        whenever(mockSimpleUiFeature.eventsConsumed) {
            setOf(Event.MediaSelectionConfirmed::class.java)
        }
        whenever(mockSimpleUiFeature.eventsProduced) {
            setOf(Event.MediaSelectionConfirmed::class.java)
        }
        whenever(mockSimpleUiFeature.token) { mockRegistration.token }
    }

    @Test
    fun testDeliversEventsToCollectors() = runTest {
        val events =
            Events(
                scope = backgroundScope,
                provideTestConfigurationFlow(scope = backgroundScope),
                buildFeatureManagerWithFeatures(testRegistrations, backgroundScope)
            )

        val collectorOne = mutableListOf<Event>()
        val collectorTwo = mutableListOf<Event>()

        backgroundScope.launch { events.flow.toList(collectorOne) }
        backgroundScope.launch { events.flow.toList(collectorTwo) }

        val event = Event.MediaSelectionConfirmed(mockRegistration.token)

        events.dispatch(event)

        advanceTimeBy(100)

        assertWithMessage("Missing expected event in collector one")
            .that(collectorOne)
            .contains(event)
        assertWithMessage("Missing expected event in collector two")
            .that(collectorTwo)
            .contains(event)
    }

    @Test
    fun testEventDeliveryDoesNotReplay() = runTest {
        val events =
            Events(
                scope = backgroundScope,
                provideTestConfigurationFlow(scope = backgroundScope),
                buildFeatureManagerWithFeatures(testRegistrations, backgroundScope)
            )

        val collectorOne = mutableListOf<Event>()
        val collectorTwo = mutableListOf<Event>()

        backgroundScope.launch { events.flow.toList(collectorOne) }

        val event = Event.MediaSelectionConfirmed(mockRegistration.token)

        events.dispatch(event)
        advanceTimeBy(100)

        backgroundScope.launch { events.flow.toList(collectorTwo) }
        advanceTimeBy(100)

        assertWithMessage("Missing expected event in collector one")
            .that(collectorOne)
            .contains(event)
        assertWithMessage("Unexpected event in collector two")
            .that(collectorTwo)
            .doesNotContain(event)
    }

    @Test
    fun testBulkEventDelivery() = runTest {
        val events =
            Events(
                scope = backgroundScope,
                provideTestConfigurationFlow(scope = backgroundScope),
                buildFeatureManagerWithFeatures(testRegistrations, backgroundScope)
            )
        val collectorOne = mutableListOf<Event>()
        val collectorTwo = mutableListOf<Event>()

        backgroundScope.launch { events.flow.toList(collectorOne) }
        backgroundScope.launch { events.flow.toList(collectorTwo) }

        val event = Event.MediaSelectionConfirmed(mockRegistration.token)

        events.dispatch(event) // 1
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event) // 10
        advanceTimeBy(100)

        assertWithMessage("Missing expected event in collector one")
            .that(collectorOne.size)
            .isEqualTo(10)
        assertWithMessage("Missing expected event in collector two")
            .that(collectorTwo.size)
            .isEqualTo(10)
    }

    @Test
    fun testBulkEventDeliveryIsBuffered() = runTest {
        val events =
            Events(
                scope = backgroundScope,
                provideTestConfigurationFlow(scope = backgroundScope),
                buildFeatureManagerWithFeatures(testRegistrations, backgroundScope)
            )

        val collectorOne = mutableListOf<Event>()
        val collectorTwo = mutableListOf<Event>()

        val event = Event.MediaSelectionConfirmed(mockRegistration.token)

        events.dispatch(event) // 1
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event)
        events.dispatch(event) // 10

        backgroundScope.launch { events.flow.toList(collectorOne) }
        backgroundScope.launch { events.flow.toList(collectorTwo) }

        advanceTimeBy(100)

        assertWithMessage("Missing expected event in collector one")
            .that(collectorOne.size)
            .isEqualTo(10)
        assertWithMessage("Missing expected event in collector two")
            .that(collectorTwo.size)
            .isEqualTo(10)
    }

    @Test
    fun testEventThrowsUnregisteredEventOnDebug() = runTest {

        // Reset the event registry of the mock feature
        whenever(mockSimpleUiFeature.eventsProduced) { emptySet<RegisteredEventClass>() }
        whenever(mockSimpleUiFeature.eventsConsumed) { emptySet<RegisteredEventClass>() }

        val events =
            Events(
                scope = backgroundScope,
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    PhotopickerConfiguration(action = "TEST", deviceIsDebuggable = true)
                ),
                buildFeatureManagerWithFeatures(testRegistrations, backgroundScope)
            )

        val collector = mutableListOf<Event>()
        backgroundScope.launch { events.flow.toList(collector) }

        val event = Event.MediaSelectionConfirmed(mockRegistration.token)

        assertThrows(UnregisteredEventDispatchedException::class.java) {
            runBlocking { events.dispatch(event) }
        }
        assertWithMessage("Unregistered event was delivered").that(collector).isEmpty()
    }

    @Test
    fun testEventDoesNotThrowUnregisteredEventOnProd() = runTest {

        // Reset the event registry of the mock feature
        whenever(mockSimpleUiFeature.eventsProduced) { emptySet<RegisteredEventClass>() }
        whenever(mockSimpleUiFeature.eventsConsumed) { emptySet<RegisteredEventClass>() }

        val events =
            Events(
                scope = backgroundScope,
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    PhotopickerConfiguration(action = "TEST", deviceIsDebuggable = false)
                ),
                buildFeatureManagerWithFeatures(testRegistrations, backgroundScope)
            )

        val collector = mutableListOf<Event>()
        backgroundScope.launch { events.flow.toList(collector) }

        val event = Event.MediaSelectionConfirmed(mockRegistration.token)

        // This should not throw on production
        events.dispatch(event)

        advanceTimeBy(100)

        assertWithMessage("Unregistered event was not delivered, production config.")
            .that(collector)
            .contains(event)
    }
    /**
     * Builds a feature manager that is initialized with the given feature registrations and config.
     *
     * @param features the set of feature registrations
     * @param config the [PhotopickerConfiguration] provided to FeatureManager.
     */
    private fun buildFeatureManagerWithFeatures(
        features: Set<FeatureRegistration>,
        scope: CoroutineScope,
        config: PhotopickerConfiguration = testPhotopickerConfiguration,
    ): FeatureManager {
        return FeatureManager(
            configuration =
                provideTestConfigurationFlow(
                    scope = scope,
                    defaultConfiguration = config,
                ),
            scope = scope,
            registeredFeatures = features,
            /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
            /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
        )
    }
}
