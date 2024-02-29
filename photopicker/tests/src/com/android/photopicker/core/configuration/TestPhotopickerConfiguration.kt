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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * A [PhotopickerConfiguration] that can be used with most tests, that comes with sensible default
 * values.
 */
val testPhotopickerConfiguration: PhotopickerConfiguration =
    PhotopickerConfiguration(action = "TEST_ACTION")

/**
 * Helper function to generate a [StateFlow] that mimics the flow emitted by the
 * [ConfigurationManager]. This flow immediately emits the provided [PhotopickerConfiguration] or a
 * default test configuration if none is provided.
 */
fun provideTestConfigurationFlow(
    scope: CoroutineScope,
    defaultConfiguration: PhotopickerConfiguration = testPhotopickerConfiguration
): StateFlow<PhotopickerConfiguration> {

    return flow { emit(defaultConfiguration) }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = defaultConfiguration)
}
