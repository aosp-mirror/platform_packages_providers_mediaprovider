/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.inject

import android.os.UserHandle
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.user.UserMonitor

/**
 * A set of options to provide to the [PhotopickerTestModule] that will alter the values that are
 * injected into the test environment.
 *
 * @See PhotopickerTestModule which consumes these options.
 */
class TestOptions
private constructor(
    val runtimeEnv: PhotopickerRuntimeEnv,
    val processOwnerHandle: UserHandle,
    val registeredFeatures: Set<FeatureRegistration>,
) {

    companion object {
        /**
         * Create a new set of [TestOptions]
         *
         * @return [TestOptions] with the applied properties.
         */
        inline fun build(block: Builder.() -> Unit) = Builder().apply(block).build()
    }

    /**
     * Builder for the [TestOptions] class.
     *
     * This class can be manually constructed, but it is recommended to use the [TestOptions.build]
     * method.
     */
    class Builder {

        var runtimeEnv: PhotopickerRuntimeEnv = PhotopickerRuntimeEnv.ACTIVITY
        var processOwnerHandle: UserHandle = UserHandle.of(0)
        var registeredFeatures: Set<FeatureRegistration> =
            FeatureManager.KNOWN_FEATURE_REGISTRATIONS

        /**
         * Sets the [PhotopickerRuntimeEnv] that will be provided to the [ConfigurationManager] in
         * the test environment.
         *
         * @param runtimeEnv
         */
        fun runtimeEnv(runtimeEnv: PhotopickerRuntimeEnv) = apply { this.runtimeEnv = runtimeEnv }

        /**
         * Sets the [UserHandle] that will be provided to [UserMonitor] to use as the launching
         * profile.
         *
         * @param handle
         * @See [UserMonitor.launchingProfile]
         */
        fun processOwnerHandle(handle: UserHandle) = apply { this.processOwnerHandle = handle }

        /**
         * Set of [FeatureRegistration] that [FeatureManager] will use during the test suite when
         * deciding which features to initialize.
         *
         * NOTE: It is still up to the individual features to decide if they are enabled or not,
         * this simply sets which set of [PhotopickerFeature] that the FeatureManager will attempt
         * to enable when it initializes.
         *
         * By default (if this is unset), the production set is used.
         *
         * @param features
         */
        fun registeredFeatures(features: Set<FeatureRegistration>) = apply {
            this.registeredFeatures = features
        }

        /** @return the assembled [TestOptions] object. */
        fun build() =
            TestOptions(
                runtimeEnv = runtimeEnv,
                processOwnerHandle = processOwnerHandle,
                registeredFeatures = registeredFeatures
            )
    }
}
