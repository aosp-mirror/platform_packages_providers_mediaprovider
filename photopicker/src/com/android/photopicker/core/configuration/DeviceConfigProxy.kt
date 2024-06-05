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
import java.util.concurrent.Executor

/**
 * A supplier interface for [DeviceConfig]. This allows for isolating the device state during tests
 * by providing an alternate implementation to the [ConfigurationManager].
 *
 * For production / runtime usage that wraps the real [DeviceConfig], see [DeviceConfigProxyImpl]
 */
interface DeviceConfigProxy {

    /**
     * Register a listener to receive callbacks when flags in the backing [DeviceConfig] supplier
     * change.
     *
     * @param namespace The namespace to attach this listener to.
     * @param executor An executor for the listener to be run on. If being called from kotlin,
     *   consider providing a `[CoroutineDispatcher].asExecutor`
     * @param listener the implementation of [DeviceConfig.OnPropertiesChangedListener] to register.
     */
    fun addOnPropertiesChangedListener(
        namespace: String,
        executor: Executor,
        listener: DeviceConfig.OnPropertiesChangedListener,
    )

    /** Remove the provided listener from receiving future device config flag updates. */
    fun removeOnPropertiesChangedListener(listener: DeviceConfig.OnPropertiesChangedListener)

    /**
     * Fetch a flag from the backing [DeviceConfig] supplier.
     *
     * This method assumes the caller knows the correct type of the flag they are fetching, and will
     * expect a flag type that matches the same type as the defaultValue.
     *
     * This wraps the various "getFlagOfType" methods that exist in device config into one method.
     *
     * In the event the flag is unset, the provided defaultValue will be returned. If the flag type
     * is incorrect, the provided defaultValue will be returned.
     */
    fun <T> getFlag(namespace: String, key: String, defaultValue: T): T
}
