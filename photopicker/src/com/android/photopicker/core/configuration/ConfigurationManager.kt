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
import android.os.Build
import android.provider.DeviceConfig
import android.util.Log
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.isUnspecified
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.theme.AccentColorHelper
import com.android.photopicker.extensions.getApplicationMediaCapabilities
import com.android.photopicker.extensions.getPhotopickerMimeTypes
import com.android.photopicker.extensions.getPhotopickerSelectionLimitOrDefault
import com.android.photopicker.extensions.getPickImagesInOrderEnabled
import com.android.photopicker.extensions.getPickImagesPreSelectedUris
import com.android.photopicker.extensions.getStartDestination
import com.android.providers.media.flags.Flags
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.updateAndGet
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
 * @property runtimeEnv The current [PhotopickerRuntimeEnv] environment, this value is used to
 *   create the initial [PhotopickerConfiguration], and should never be changed during subsequent
 *   configuration updates.
 * @property scope The [CoroutineScope] the configuration flow will be shared in.
 * @property dispatcher [CoroutineDispatcher] context that the DeviceConfig listener will execute
 *   in.
 * @property deviceConfigProxy This is provided to the ConfigurationManager to better support
 *   testing various device flags, without relying on the device's actual flags at test time.
 * @property sessionId A randomly generated integer to identify the current photopicker session
 */
class ConfigurationManager(
    private val runtimeEnv: PhotopickerRuntimeEnv,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val sessionId: Int,
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

    /* Exposes the current configuration used by Photopicker as a ReadOnly StateFlow. */
    val configuration: StateFlow<PhotopickerConfiguration> = _configuration

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
                _configuration.updateAndGet { it.copy(flags = flags) }
            }
        }
    }

    /**
     * Updates the [PhotopickerConfiguration] with the [EmbeddedPhotopickerFeatureInfo] that the
     * Embedded Photopicker is running with.
     *
     * Since [ConfigurationManager] is bound to the [EmbeddedServiceComponent], it does not have a
     * reference to the currently running Session (if there is one). This allows the session to set
     * the current FeatureInfo externally once the session is available.
     *
     * It's important that this method is called before the FeatureManager is started to prevent the
     * feature manager from being re-initialized.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun setEmbeddedPhotopickerFeatureInfo(featureInfo: EmbeddedPhotoPickerFeatureInfo) {
        Log.d(TAG, "New featureInfo received: $featureInfo : Configuration will now update.")

        val selectionLimit = featureInfo.maxSelectionLimit
        val mimeTypes = featureInfo.mimeTypes
        val preSelectedUris = featureInfo.preSelectedUris

        /**
         * Pick images in order is a combination of circumstances:
         * - selectionLimit mode must be multiselect (more than 1)
         * - The feature must be requested from the caller in the featureInfo
         */
        val pickImagesInOrder = featureInfo.isOrderedSelection && (selectionLimit > 1)

        /** Check if the accent color was set and is valid. */
        val accentColor =
            with(AccentColorHelper(featureInfo.accentColor)) {
                if (getAccentColor().isUnspecified) {
                    null
                } else {
                    inputColor
                }
            }

        // Use updateAndGet to ensure that the values are set before this method returns so that
        // the new configuration is immediately available to the new subscribers.
        _configuration.updateAndGet {
            it.copy(
                selectionLimit = selectionLimit,
                accentColor = accentColor,
                mimeTypes = mimeTypes.toCollection(ArrayList()),
                preSelectedUris = preSelectedUris.toCollection(ArrayList()),
                pickImagesInOrder = pickImagesInOrder,
            )
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
    fun setIntent(intent: Intent) {
        Log.d(TAG, "New intent received: $intent : Configuration will now update.")

        // Check for [MediaStore.EXTRA_PICK_IMAGES_MAX] and update the selection limit accordingly.
        val selectionLimit =
            intent.getPhotopickerSelectionLimitOrDefault(default = DEFAULT_SELECTION_LIMIT)

        // MimeTypes can explicitly be passed in the intent extras, so extract them if they exist
        // (and are actually a media mimetype that is supported). If nothing is in the intent,
        // just set what is already set in the current configuration.
        val mimeTypes = intent.getPhotopickerMimeTypes() ?: _configuration.value.mimeTypes

        /**
         * Pick images in order is a combination of circumstances:
         * - selectionLimit mode must be multiselect (more than 1)
         * - The extra must be requested from the caller in the intent
         */
        val pickImagesInOrder =
            intent.getPickImagesInOrderEnabled(default = false) && (selectionLimit > 1)

        /** Handle [MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB] extra if it's in the intent */
        val startDestination = intent.getStartDestination(default = PhotopickerDestinations.DEFAULT)

        /** Check if the accent color was set and is valid. */
        val accentColor =
            with(AccentColorHelper.withIntent(intent)) {
                if (getAccentColor().isUnspecified) {
                    null
                } else {
                    inputColor
                }
            }

        // get preSelection URIs from intent.
        val pickerPreSelectionUris = intent.getPickImagesPreSelectedUris()

        // Get application media capabilities from intent. While ApplicationMediaCapabilities
        // requires S+, we limit it use for transcoding to T+ for two reasons:
        // 1. Underlying Transformer doesn't support S and S v2 due to inaccessible audio services.
        // 2. HDR video, introduced in Android T, is where the need for transcoding comes from.
        // For details, see b/365988031.
        val applicationMediaCapabilities =
            if (SdkLevel.isAtLeastT()) {
                intent.getApplicationMediaCapabilities()
            } else {
                null
            }

        // Use updateAndGet to ensure the value is set before this method returns so the new
        // intent is immediately available to new subscribers.
        _configuration.updateAndGet {
            it.copy(
                action = intent.getAction() ?: "",
                intent = intent,
                selectionLimit = selectionLimit,
                accentColor = accentColor,
                mimeTypes = mimeTypes,
                pickImagesInOrder = pickImagesInOrder,
                startDestination = startDestination,
                preSelectedUris = pickerPreSelectionUris,
                callingPackageMediaCapabilities = applicationMediaCapabilities,
            )
        }
    }

    /**
     * Sets data in [PhotopickerConfiguration] about the current caller, and emit an updated
     * configuration.
     *
     * @param callingPackage the package name of the caller
     * @param callingPackageUid the uid of the caller
     * @param callingPackageLabel the display label of the caller
     */
    fun setCaller(callingPackage: String?, callingPackageUid: Int?, callingPackageLabel: String?) {
        Log.d(TAG, "Caller information updated : Configuration will now update.")
        _configuration.updateAndGet {
            it.copy(
                callingPackage = callingPackage,
                callingPackageUid = callingPackageUid,
                callingPackageLabel = callingPackageLabel,
            )
        }
    }

    /** Assembles an initial configuration upon activity launch. */
    private fun generateInitialConfiguration(): PhotopickerConfiguration {
        val config =
            PhotopickerConfiguration(
                runtimeEnv = runtimeEnv,
                action = "",
                flags = getFlagsFromDeviceConfig(),
                sessionId = sessionId,
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
                getAllowlistedPackages(
                    deviceConfigProxy.getFlag(
                        NAMESPACE_MEDIAPROVIDER,
                        /* key= */ FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
                        /* defaultValue= */ FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.second,
                    )
                ),
            CLOUD_ENFORCE_PROVIDER_ALLOWLIST =
                deviceConfigProxy.getFlag(
                    NAMESPACE_MEDIAPROVIDER,
                    /* key= */ FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.first,
                    /* defaultValue= */ FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.second,
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
            PICKER_SEARCH_ENABLED = Flags.enablePhotopickerSearch(),
            PICKER_TRANSCODING_ENABLED = Flags.enablePhotopickerTranscoding(),
        )
    }

    /**
     * BACKWARD COMPATIBILITY WORKAROUND Initially, instead of using package names when
     * allow-listing and setting the system default CloudMediaProviders we used authorities.
     *
     * This, however, introduced a vulnerability, so we switched to using package names. But, by
     * then, we had been allow-listing and setting default CMPs using authorities.
     *
     * Luckily for us, all of those CMPs had authorities in one the following formats:
     * "${package-name}.cloudprovider" or "${package-name}.picker", e.g. "com.hooli.android.photos"
     * package would implement a CMP with "com.hooli.android.photos.cloudpicker" authority.
     *
     * So, in order for the old allow-listings and defaults to work now, we try to extract package
     * names from authorities by removing the ".cloudprovider" and ".cloudpicker" suffixes.
     *
     * In the future, we'll need to be careful if package names of cloud media apps end with
     * "cloudprovider" or "cloudpicker".
     */
    private fun getAllowlistedPackages(allowedProvidersArray: Array<String>): Array<String> {
        return allowedProvidersArray
            .map {
                when {
                    it.endsWith(".cloudprovider") ->
                        it.substring(0, it.length - ".cloudprovider".length)
                    it.endsWith(".cloudpicker") ->
                        it.substring(0, it.length - ".cloudpicker".length)
                    else -> it
                }
            }
            .toTypedArray<String>()
    }
}
