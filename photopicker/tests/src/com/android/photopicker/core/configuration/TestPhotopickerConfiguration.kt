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
import com.android.photopicker.core.events.generatePickerSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

val testSessionId = generatePickerSessionId()

/** A [PhotopickerConfiguration] that allows selection of only a single item. */
val SINGLE_SELECT_CONFIG =
    PhotopickerConfiguration(action = "", selectionLimit = 1, sessionId = testSessionId)

/** A [PhotopickerConfiguration] that allows selection of multiple (50 in this case) items. */
val MULTI_SELECT_CONFIG =
    PhotopickerConfiguration(action = "", selectionLimit = 50, sessionId = testSessionId)

/** A test package name used in test photopicker configurations. */
val TEST_CALLING_PACKAGE = "com.example.test"

/** A test calling uid used in test photopicker configurations. */
val TEST_CALLING_UID = 1234

/** A test package label used in test photopicker configurations. */
val TEST_CALLING_PACKAGE_LABEL = "test_app"

/**
 * A [PhotopickerConfiguration] that can be used with most tests, that comes with sensible default
 * values.
 */
val testPhotopickerConfiguration: PhotopickerConfiguration =
    PhotopickerConfiguration(
        action = "TEST_ACTION",
        intent = Intent("TEST_ACTION"),
        sessionId = testSessionId
    )

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize
 * [MediaStore.ACTION_PICK_IMAGES] intent action.
 */
val testActionPickImagesConfiguration: PhotopickerConfiguration =
    PhotopickerConfiguration(
        action = MediaStore.ACTION_PICK_IMAGES,
        intent = Intent(MediaStore.ACTION_PICK_IMAGES),
        sessionId = testSessionId
    )

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize [Intent.GET_CONTENT]
 * intent action.
 */
val testGetContentConfiguration: PhotopickerConfiguration =
    PhotopickerConfiguration(
        action = Intent.ACTION_GET_CONTENT,
        intent = Intent(Intent.ACTION_GET_CONTENT),
        sessionId = testSessionId
    )

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize
 * [MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP] intent action.
 */
val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
    PhotopickerConfiguration(
        action = MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
        intent = Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP),
        callingPackage = TEST_CALLING_PACKAGE,
        callingPackageUid = TEST_CALLING_UID,
        callingPackageLabel = TEST_CALLING_PACKAGE_LABEL,
        sessionId = testSessionId
    )

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize
 * [PhotopickerRuntimeEnv.EMBEDDED]
 */
val testEmbeddedPhotopickerConfiguration: PhotopickerConfiguration =
    PhotopickerConfiguration(
        action = "",
        runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
        sessionId = testSessionId
    )

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
