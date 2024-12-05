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
import com.android.photopicker.core.events.generatePickerSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Helper function to generate a [StateFlow] that mimics the flow emitted by the
 * [ConfigurationManager]. This flow immediately emits the provided [PhotopickerConfiguration] or a
 * default test configuration if none is provided.
 */
fun provideTestConfigurationFlow(
    scope: CoroutineScope,
    defaultConfiguration: PhotopickerConfiguration = TestPhotopickerConfiguration.default(),
): StateFlow<PhotopickerConfiguration> {

    return flow { emit(defaultConfiguration) }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = defaultConfiguration)
}

/** Builder for a [PhotopickerConfiguration] to use in Tests. */
class TestPhotopickerConfiguration {
    companion object {
        /**
         * Create a new [PhotopickerConfiguration]
         *
         * @return [PhotopickerConfiguration] with the applied properties.
         */
        inline fun build(block: Builder.() -> Unit) = Builder().apply(block).build()

        /**
         * Create a new [PhotopickerConfiguration].
         *
         * @return [PhotopickerConfiguration] with default properties.
         */
        fun default() = Builder().build()
    }

    /** Internal Builder implementation. Callers should use [TestPhotopickerConfiguration.build]. */
    class Builder {
        private var action: String = ""
        private var intent: Intent? = null
        private var selectionLimit: Int = DEFAULT_SELECTION_LIMIT
        private var pickImagesInOrder: Boolean = false
        private var callingPackage: String? = null
        private var callingPackageUid: Int? = null
        private var callingPackageLabel: String? = null
        private var runtimeEnv: PhotopickerRuntimeEnv = PhotopickerRuntimeEnv.ACTIVITY
        private var sessionId: Int = generatePickerSessionId()
        private var flags: PhotopickerFlags = PhotopickerFlags()
        private var mimeTypes: ArrayList<String> = arrayListOf("image/*", "video/*")

        fun action(value: String) = apply { this.action = value }

        fun intent(value: Intent?) = apply { this.intent = value }

        fun selectionLimit(value: Int) = apply { this.selectionLimit = value }

        fun pickImagesInOrder(value: Boolean) = apply { this.pickImagesInOrder = value }

        fun callingPackage(value: String) = apply { this.callingPackage = value }

        fun callingPackageUid(value: Int) = apply { this.callingPackageUid = value }

        fun callingPackageLabel(value: String) = apply { this.callingPackageLabel = value }

        fun runtimeEnv(value: PhotopickerRuntimeEnv) = apply { this.runtimeEnv = value }

        fun sessionId(value: Int) = apply { this.sessionId = value }

        fun flags(value: PhotopickerFlags) = apply { this.flags = value }

        fun mimeTypes(value: ArrayList<String>) = apply { this.mimeTypes = value }

        fun build(): PhotopickerConfiguration {
            return PhotopickerConfiguration(
                action = action,
                intent = intent,
                selectionLimit = selectionLimit,
                pickImagesInOrder = pickImagesInOrder,
                callingPackage = callingPackage,
                callingPackageUid = callingPackageUid,
                callingPackageLabel = callingPackageLabel,
                runtimeEnv = runtimeEnv,
                sessionId = sessionId,
                flags = flags,
                mimeTypes = mimeTypes,
            )
        }
    }
}
