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

package com.android.photopicker.core.configuration

import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

/** Unit tests for the [ConfigurationManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationManagerTest {

    // Isolate the test device by providing a test wrapper around device config so that the
    // tests can control the flag values that are returned.
    val deviceConfigProxy = TestDeviceConfigProxyImpl()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        deviceConfigProxy.reset()
    }

    /** Ensures that the [ConfigurationManager] emits its current configuration. */
    @Test
    fun testEmitsConfiguration() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )

            // Expect the default configuration with an action matching the test action.
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            backgroundScope.launch {
                val reportedConfiguration = configurationManager.configuration.first()
                assertThat(reportedConfiguration).isEqualTo(expectedConfiguration)
            }
        }
    }

    /**
     * Ensures that the [ConfigurationManager] emits updated configuration when device flags are
     * changed.
     */
    @Test
    fun testConfigurationEmitsFlagChanges() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            // Expect the default configuration with an action matching the test action.
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            // wait for ConfigurationManager to register a listener
            advanceTimeBy(100)

            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
                false
            )

            // wait for debounce
            advanceTimeBy(1100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last())
                .isEqualTo(
                    expectedConfiguration.copy(
                        flags = PhotopickerFlags(CLOUD_MEDIA_ENABLED = false)
                    )
                )
        }
    }

    /**
     * Ensures that the [ConfigurationManager] batches configuration changes that are made within a
     * short period of time.
     */
    @Test
    fun testConfigurationChangesAreBatched() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            // Expect the default configuration with an action matching the test action.
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            // wait for ConfigurationManager to register a listener
            advanceTimeBy(100)

            // Quickly set three flags in succession to their inverse-default values.
            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
                !FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
            )
            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
                "testallowlist"
            )
            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_PRIVATE_SPACE_ENABLED.first,
                !FEATURE_PRIVATE_SPACE_ENABLED.second,
            )

            // wait for debounce
            advanceTimeBy(1100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last())
                .isEqualTo(
                    expectedConfiguration.copy(
                        flags =
                            PhotopickerFlags(
                                CLOUD_ALLOWED_PROVIDERS = "testallowlist",
                                CLOUD_MEDIA_ENABLED = !FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
                                PRIVATE_SPACE_ENABLED = !FEATURE_PRIVATE_SPACE_ENABLED.second,
                            )
                    )
                )
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected action.
     */
    @Test
    fun testSetIntentUpdatesConfiguration() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(Intent("TEST_ACTION"))
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo("TEST_ACTION")
            assertThat(emissions.last().intent).isNotNull()
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected selection limit.
     */
    @Test
    fun testSetIntentSetsSelectionLimit() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().intent).isNotNull()
            assertThat(emissions.last().selectionLimit)
                .isEqualTo(MediaStore.getPickImagesMaxLimit())
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected selection limit.
     */
    @Test
    fun testSetIntentSetsSelectionLimitThrowsOnIllegalConfiguration() {

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intent)
            }
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected selection limit.
     */
    @Test
    fun testSetIntentSetsSelectionLimitThrowsOnIllegalRange() {

        val intentTooHigh =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                // One higher than the limit so we are outside the range
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit() + 1)

        val intentTooLow =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                // One higher than the limit so we are outside the range
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "")

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intentTooHigh)
            }
            advanceTimeBy(100)
            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intentTooLow)
            }
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        }
    }
}
