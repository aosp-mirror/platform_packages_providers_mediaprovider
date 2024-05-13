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
import android.provider.DeviceConfig
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * This class is responsible for providing the current configuration for the running Photopicker
 * Activity.
 *
 * See [PhotopickerConfiguration] for details about all the various pieces that make up the session
 * configuration.
 *
 * Provides a long-living [StateFlow] that emits the currently known configuration. Configuration
 * changes may take up to 1000 ms to be emitted after processing. This delay is intentional, trying
 * to batch any changes to configuration together, as it is anticipated that configuration changes
 * will cause lots of re-calculation of downstream state.
 *
 * @property scope The [CoroutineScope] the configuration flow will be shared in.
 * @property dispatcher [CoroutineDispatcher] context that the DeviceConfig listener will execute
 *   in.
 * @property deviceConfigProxy This is provided to the ConfigurationManager to better support
 *   testing various device flags, without relying on the device's actual flags at test time.
 */
class ConfigurationManager(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val deviceConfigProxy: DeviceConfigProxy,
) {

    companion object {
        val TAG: String = "PhotopickerConfigurationManager"
        val DEBOUNCE_DELAY = 1000L
    }

    /*
     * Internal [PhotopickerConfiguration] flow. When the configuration changes, this is what should
     * be updated to ensure all listeners are notified.
     *
     * Note: Updating this is expensive and should be avoided (or batched, if possible).
     * This will cause a recalculation of the active features and will likely result in the UI
     * being re-composed from the top of the tree.
     */
    private val _configuration: MutableStateFlow<PhotopickerConfiguration> =
        MutableStateFlow(generateInitialConfiguration())

    /* Exposes the current configuration used by Photopicker. */
    val configuration: StateFlow<PhotopickerConfiguration> =
        _configuration.stateIn(
            scope,
            // This is shared Eagerly so that it is always up to date to prevent multiple emissions
            // when the first subscriber joins. Configuration updates are expensive, so always
            // keeping this flow current is very important.
            SharingStarted.Eagerly,
            initialValue = _configuration.value
        )

    /**
     * Setup a [DeviceConfig.OnPropertiesChangedListener] to receive DeviceConfig changes to flags
     * at runtime.
     */
    val deviceConfigProxyChanges =
        callbackFlow<PhotopickerFlags> {
            val callback =
                object : DeviceConfig.OnPropertiesChangedListener {
                    override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
                        trySend(getFlagsFromDeviceConfig())
                    }
                }

            deviceConfigProxy.addOnPropertiesChangedListener(
                NAMESPACE_MEDIAPROVIDER,
                dispatcher.asExecutor(),
                callback,
            )

            awaitClose {
                Log.d(TAG, "Unregistering listeners from DeviceConfig")
                deviceConfigProxy.removeOnPropertiesChangedListener(callback)
            }
        }

    init {
        scope.launch(dispatcher) {
            // Batch any updates for 1000ms to catch multiple flags being updated at the
            // same time, since the downstream configuration changes are expensive.
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            deviceConfigProxyChanges.debounce(DEBOUNCE_DELAY).collect { flags ->
                Log.d(TAG, "Configuration is being updated! Received updated Device flags: $flags")
                _configuration.update { it.copy(flags = flags) }
            }
        }
    }

    /**
     * Sets the current intent & action Photopicker is running under.
     *
     * Since [ConfigurationManager] is bound to the [ActivityRetainedComponent] it does not have a
     * reference to the currently running Activity (if there is one.). This allows the activity to
     * set the current Intent externally once the activity is available.
     *
     * If Photopicker is running inside of an activity, it's important that this method is called
     * before the FeatureManager is started to prevent the feature manager being re-initialized.
     */
    fun setIntent(intent: Intent?) {
        Log.d(TAG, "New intent received: $intent : Configuration will now update.")
        _configuration.update { it.copy(action = intent?.getAction() ?: "", intent = intent) }
    }

    /** Assembles an initial configuration upon activity launch. */
    private fun generateInitialConfiguration(): PhotopickerConfiguration {
        val config =
            PhotopickerConfiguration(
                action = "",
                flags = getFlagsFromDeviceConfig(),
            )

        Log.d(TAG, "Startup configuration: $config")
        return config
    }

    /**
     * Creates a new [PhotopickerFlags] object from the current [DeviceConfig] flag state.
     *
     * Any flags expected to be present in the [PhotopickerConfiguration] should be checked and
     * explicitly set here.
     *
     * @return a [PhotopickerFlags] object representative of the current device flag state.
     */
    private fun getFlagsFromDeviceConfig(): PhotopickerFlags {
        return PhotopickerFlags(
            CLOUD_ALLOWED_PROVIDERS =
                deviceConfigProxy.getFlag(
                    NAMESPACE_MEDIAPROVIDER,
                    /* key= */ FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
                    /* defaultValue= */ FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.second
                ),
            CLOUD_ENFORCE_PROVIDER_ALLOWLIST =
                deviceConfigProxy.getFlag(
                    NAMESPACE_MEDIAPROVIDER,
                    /* key= */ FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.first,
                    /* defaultValue= */ FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.second
                ),
            CLOUD_MEDIA_ENABLED =
                deviceConfigProxy.getFlag(
                    NAMESPACE_MEDIAPROVIDER,
                    /* key= */ FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
                    /* defaultValue= */ FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
                ),
            PRIVATE_SPACE_ENABLED =
                deviceConfigProxy.getFlag(
                    NAMESPACE_MEDIAPROVIDER,
                    /* key= */ FEATURE_PRIVATE_SPACE_ENABLED.first,
                    /* defaultValue= */ FEATURE_PRIVATE_SPACE_ENABLED.second,
                ),
            MANAGED_SELECTION_ENABLED =
                deviceConfigProxy.getFlag(
                    NAMESPACE_MEDIAPROVIDER,
                    /* key= */ FEATURE_PICKER_CHOICE_MANAGED_SELECTION.first,
                    /* defaultValue= */ FEATURE_PICKER_CHOICE_MANAGED_SELECTION.second,
                ),
        )
    }
}
