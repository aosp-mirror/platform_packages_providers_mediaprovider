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
 * This is an in-memory implementation of [DeviceConfigProxy] which can be used for classes under
 * test that depend on [DeviceConfig] values. All flags are kept in memory, and the actual device
 * state is not leaked into the test.
 *
 * @param initialFlagStore An optional initial flag store value to initialize the proxy with.
 */
class TestDeviceConfigProxyImpl(
    initialFlagStore: MutableMap<String, MutableMap<String, String>>? = null
) : DeviceConfigProxy {

    private val flagStore: MutableMap<String, MutableMap<String, String>> =
        initialFlagStore ?: mutableMapOf()

    private val observers:
        MutableMap<String, MutableList<DeviceConfig.OnPropertiesChangedListener>> =
        mutableMapOf()

    override fun addOnPropertiesChangedListener(
        namespace: String,
        executor: Executor, // Unused in test implementation but required in the API
        listener: DeviceConfig.OnPropertiesChangedListener,
    ) {
        if (!observers.contains(namespace)) {
            observers.put(namespace, mutableListOf())
        }
        observers.get(namespace)?.add(listener)
    }

    override fun removeOnPropertiesChangedListener(
        listener: DeviceConfig.OnPropertiesChangedListener
    ) {

        // The listener could be listening to more than one namespace, so iterate all namespaces.
        for (list in observers.values) {

            // Iterate all the listeners in each namespace
            for (callback in list) {
                if (callback == listener) {
                    list.remove(callback)
                }
            }
        }
    }

    override fun <T> getFlag(namespace: String, key: String, defaultValue: T): T {
        val rawValue: String? = flagStore.get(namespace)?.get(key)

        // The casts below are definitely safe casts, Use of the "as?" ensures the type
        // and in the case it cannot be cast to the type, instead default back to the provided
        // default value which is known to match the correct type.
        // As a result, we silence the unchecked cast compiler warnings in the block below.
        return when (defaultValue) {
            is Boolean -> {
                @Suppress("UNCHECKED_CAST") return (rawValue?.toBoolean() as? T) ?: defaultValue
            }
            is String -> {
                @Suppress("UNCHECKED_CAST") return (rawValue as? T) ?: defaultValue
            }
            else -> defaultValue
        }
    }
    /**
     * Returns this [DeviceConfigProxy] implementation to an empty state. Drops all known
     * namespaces, flags values. Drops all known listeners.
     *
     * @return [this] so the method can be chained
     */
    fun reset(): TestDeviceConfigProxyImpl {
        flagStore.clear()
        observers.clear()
        return this
    }

    /**
     * Set the flag value.
     *
     * @param namespace the flag's namespace
     * @param key the name of the flag to set
     * @param value the value of this flag
     * @return [this] so that this method can be chained.
     */
    fun setFlag(namespace: String, key: String, value: String): TestDeviceConfigProxyImpl {
        ensureNamespace(namespace)
        flagStore.get(namespace)?.put(key, value)
        notifyKeyChanged(namespace, key, value)
        return this
    }

    /**
     * Set the flag value.
     *
     * @param namespace the flag's namespace
     * @param key the name of the flag to set
     * @param value the value of this flag
     * @return [this] so that this method can be chained.
     */
    fun setFlag(namespace: String, key: String, value: Boolean): TestDeviceConfigProxyImpl {
        ensureNamespace(namespace)
        flagStore.get(namespace)?.put(key, "$value")
        notifyKeyChanged(namespace, key, "$value")
        return this
    }

    /** Runs callbacks for any listeners listening to changes to the namespace. */
    private fun notifyKeyChanged(namespace: String, key: String, value: String) {

        val observersToNotify = observers.get(namespace) ?: emptyList()

        val properties = DeviceConfig.Properties.Builder(namespace).setString(key, value).build()

        for (listener in observersToNotify) {
            listener.onPropertiesChanged(properties)
        }
    }

    /** Ensures that the given namespace exists in the current Flag store. */
    private fun ensureNamespace(namespace: String) {
        if (!flagStore.contains(namespace)) {
            flagStore.put(namespace, mutableMapOf())
        }
    }
}
