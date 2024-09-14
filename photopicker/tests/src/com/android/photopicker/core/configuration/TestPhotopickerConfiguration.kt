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
    TestPhotopickerConfiguration.build {
        action("")
        selectionLimit(1)
        sessionId(testSessionId)
    }

/** A [PhotopickerConfiguration] that allows selection of multiple (50 in this case) items. */
val MULTI_SELECT_CONFIG =
    TestPhotopickerConfiguration.build {
        action("")
        selectionLimit(50)
        sessionId(testSessionId)
    }

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
    TestPhotopickerConfiguration.build {
        action("TEST_ACTION")
        intent(Intent("TEST_ACTION"))
        sessionId(testSessionId)
    }

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize
 * [MediaStore.ACTION_PICK_IMAGES] intent action.
 */
val testActionPickImagesConfiguration: PhotopickerConfiguration =
    TestPhotopickerConfiguration.build {
        action(MediaStore.ACTION_PICK_IMAGES)
        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
        sessionId(testSessionId)
    }

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize [Intent.GET_CONTENT]
 * intent action.
 */
val testGetContentConfiguration: PhotopickerConfiguration =
    TestPhotopickerConfiguration.build {
        action(Intent.ACTION_GET_CONTENT)
        intent(Intent(Intent.ACTION_GET_CONTENT))
        sessionId(testSessionId)
    }

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize
 * [MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP] intent action.
 */
val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
    TestPhotopickerConfiguration.build {
        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
        callingPackage(TEST_CALLING_PACKAGE)
        callingPackageUid(TEST_CALLING_UID)
        callingPackageLabel(TEST_CALLING_PACKAGE_LABEL)
        sessionId(testSessionId)
    }

/**
 * A [PhotopickerConfiguration] that can be used for codepaths that utilize
 * [PhotopickerRuntimeEnv.EMBEDDED]
 */
val testEmbeddedPhotopickerConfiguration: PhotopickerConfiguration =
    TestPhotopickerConfiguration.build {
        action("")
        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
        sessionId(testSessionId)
    }

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

class TestPhotopickerConfiguration {
    companion object {
        /**
         * Create a new [PhotopickerConfiguration]
         *
         * @return [PhotopickerConfiguration] with the applied properties.
         */
        inline fun build(block: Builder.() -> Unit) = Builder().apply(block).build()
    }

    class Builder {
        private var action: String = ""
        private var intent: Intent? = null
        private var selectionLimit: Int = DEFAULT_SELECTION_LIMIT
        private var callingPackage: String? = null
        private var callingPackageUid: Int? = null
        private var callingPackageLabel: String? = null
        private var runtimeEnv: PhotopickerRuntimeEnv = PhotopickerRuntimeEnv.ACTIVITY
        private var sessionId: Int = generatePickerSessionId()
        private var flags: PhotopickerFlags = PhotopickerFlags()

        fun action(value: String) = apply { this.action = value }

        fun intent(value: Intent) = apply { this.intent = value }

        fun selectionLimit(value: Int) = apply { this.selectionLimit = value }

        fun callingPackage(value: String) = apply { this.callingPackage = value }

        fun callingPackageUid(value: Int) = apply { this.callingPackageUid = value }

        fun callingPackageLabel(value: String) = apply { this.callingPackageLabel = value }

        fun runtimeEnv(value: PhotopickerRuntimeEnv) = apply { this.runtimeEnv = value }

        fun sessionId(value: Int) = apply { this.sessionId = value }

        fun flags(value: PhotopickerFlags) = apply { this.flags = value }

        fun build(): PhotopickerConfiguration {
            return PhotopickerConfiguration(
                action = action,
                intent = intent,
                selectionLimit = selectionLimit,
                callingPackage = callingPackage,
                callingPackageUid = callingPackageUid,
                callingPackageLabel = callingPackageLabel,
                runtimeEnv = runtimeEnv,
                sessionId = sessionId,
                flags = flags
            )
        }
    }
}
