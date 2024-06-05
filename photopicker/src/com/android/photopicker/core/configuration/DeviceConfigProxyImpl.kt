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

import android.provider.DeviceConfig
import android.util.Log
import java.util.concurrent.Executor

/**
 * A thin wrapper around [DeviceConfig]. This allows for isolating the device state during tests by
 * providing an alternate implementation, however this implementation is intended to be used as the
 * default for runtime device config checks.
 */
class DeviceConfigProxyImpl : DeviceConfigProxy {

    override fun addOnPropertiesChangedListener(
        namespace: String,
        executor: Executor,
        listener: DeviceConfig.OnPropertiesChangedListener,
    ) {
        DeviceConfig.addOnPropertiesChangedListener(namespace, executor, listener)
    }

    override fun <T> getFlag(namespace: String, key: String, defaultValue: T): T {

        // The casts below are definitely safe casts, Use of the "as?" ensures the type
        // and in the case it cannot be cast to the type, instead default back to the provided
        // default value which is known to match the correct type.
        // As a result, we silence the unchecked cast compiler warnings in the block below.
        return when (defaultValue) {
            is Boolean ->
                @Suppress("UNCHECKED_CAST")
                (DeviceConfig.getBoolean(namespace, key, defaultValue) as? T) ?: defaultValue
            is String ->
                @Suppress("UNCHECKED_CAST")
                (DeviceConfig.getString(namespace, key, defaultValue as String) as? T)
                    ?: defaultValue
            // The expected type is not supported, so return the default.
            else -> {
                Log.w(
                    ConfigurationManager.TAG,
                    "Unexpected flag type was requested for $namespace/$key, and lookup is not" +
                        " supported. The default value of $defaultValue will be returned."
                )
                return defaultValue
            }
        }
    }

    override fun removeOnPropertiesChangedListener(
        listener: DeviceConfig.OnPropertiesChangedListener
    ) {
        DeviceConfig.removeOnPropertiesChangedListener(listener)
    }
}
