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

package com.android.photopicker.core.features

import android.util.Log
import androidx.compose.runtime.Composable
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The core class in the feature framework, the FeatureManager manages the registration,
 * initialiation and compose calls for the compose UI.
 *
 * The feature manager is responsible for calling Features via the [PhotopickerFeature] interface
 * framework, for various lifecycles, as well as providing the APIs for callers to inspect feature
 * state, change configuration, and generate composable units for various UI [Location]s.
 *
 * @property configuration a collectable [StateFlow] of configuration changes
 * @property scope A CoroutineScope that PhotopickerConfiguration updates are collected in.
 * @property registeredFeatures A set of Registrations that correspond to (potentially) enabled
 *   features.
 */
class FeatureManager(
    private val configuration: StateFlow<PhotopickerConfiguration>,
    private val scope: CoroutineScope,
    // This is in the constructor to allow tests to swap in test features.
    private val registeredFeatures: Set<FeatureRegistration> =
        FeatureManager.KNOWN_FEATURE_REGISTRATIONS
) {
    companion object {
        val TAG: String = "PhotopickerFeatureManager"

        /*
         * The list of known [FeatureRegistration]s.
         * Any features that include their registration here, are subject to be enabled by the
         * [FeatureManager] when their [FeatureRegistration#isEnabled] returns true.
         */
        private val KNOWN_FEATURE_REGISTRATIONS: Set<FeatureRegistration> = setOf()
    }

    // The internal mutable set of enabled features.
    private val _enabledFeatures: MutableSet<PhotopickerFeature> = mutableSetOf()

    /* Returns an immutable copy rather than the actual set. */
    val enabledFeatures: Set<PhotopickerFeature>
        get() = _enabledFeatures.toSet()

    val enabledUiFeatures: Set<PhotopickerUiFeature>
        get() = _enabledFeatures.filterIsInstance<PhotopickerUiFeature>().toSet()

    /*
     * The location registry for [PhotopickerUiFeature].
     *
     * The key in this map is the UI [Location]
     * The value is a *always* a sorted "priority-descending" set of Pairs
     *
     * Each pair represents a Feature which would like to draw UI at this Location, and the Priority
     * with which it would like to do so.
     *
     * It is critical that the list always remains sorted to avoid drawing the wrong element for
     * Location with a limited number of slots. (And also drawing elements in the correct order)
     * As such, avoid using [MutableList#add] to add elements to this list, and instead use an
     * insertion which maintains the sorted nature of this list.
     *
     * The sorted dataset is maintained in a [TreeSet] providing O(log n) for common operations,
     * add, remove, and contains as these operations are performance critical during compose calls.
     *
     * For Features who set the default Location [Priority.REGISTRATION_ORDER] they will
     * be drawn in order of registration in the [FeatureManager.KNOWN_FEATURE_REGISTRATIONS].
     *
     */
    private val locationRegistry: HashMap<Location, TreeSet<Pair<PhotopickerUiFeature, Int>>> =
        HashMap()

    /* Instantiate a shared single instance of our custom priority sorter to save memory */
    private val priorityDescending: Comparator<Pair<Any, Int>> = PriorityDescendingComparator()

    init {
        initializeFeatureSet()

        // Begin collecting the PhotopickerConfiguration and update the feature configuration
        // accordingly.
        scope.launch {
            // Drop the first value here to prevent initializing twice.
            // (initializeFeatureSet will pick up the first value on its own.)
            configuration.drop(1).collect { onConfigurationChanged(it) }
        }
    }

    /**
     * Set a new configuration value in the FeatureManager.
     *
     * Warning: This is an expensive operation, and should be batched if multiple configuration
     * updates are expected in the near future.
     * 1. Notify all existing features of the pending configuration change,
     * 2. Wipe existing features
     * 3. Re-initialize Feature set with new configuration
     */
    private fun onConfigurationChanged(newConfig: PhotopickerConfiguration) {
        Log.d(TAG, """Configuration has changed, re-initializing. $newConfig""")

        // Notify all active features of the incoming config change.
        _enabledFeatures.forEach { it.onConfigurationChanged(newConfig) }

        // Drop all registrations and prepare to reinitialize.
        resetAllRegistrations()

        // Re-initialize.
        initializeFeatureSet(newConfig)
    }

    /** Drops all known registrations and returns to a pre-initialization state */
    private fun resetAllRegistrations() {
        _enabledFeatures.clear()
        locationRegistry.clear()
    }

    /**
     * For the provided set of [FeatureRegistration]s, attempt to initialize the runtime Feature set
     * with the current [PhotopickerConfiguration].
     *
     * @param config The configuration to use for initialization. Defaults to the current
     *   configuration.
     */
    private fun initializeFeatureSet(config: PhotopickerConfiguration = configuration.value) {
        Log.d(TAG, "Beginning feature initialization with config: ${configuration.value}")

        for (featureCompanion in registeredFeatures) {
            if (featureCompanion.isEnabled(config)) {
                val feature = featureCompanion.build(this)
                _enabledFeatures.add(feature)
                if (feature is PhotopickerUiFeature) registerLocationsForFeature(feature)
            }
        }
        Log.d(TAG, "Feature initialization complete.")
    }

    /**
     * Adds the [PhotopickerUiFeature]'s registered locations to the internal location registry.
     *
     * To minimize memory footprint, the location is only initialized if at least one feature has it
     * in its list of registeredLocations. This avoids the underlying registry carrying empty
     * [TreeSet]s for location that no feature wishes to use.
     *
     * The [TreeSet] that is initialized uses the local [PriorityDescendingComparator] to keep the
     * set of features at that location sorted by priority.
     */
    private fun registerLocationsForFeature(feature: PhotopickerUiFeature) {

        val locationPairs = feature.registerLocations()

        for ((first, second) in locationPairs) {

            // Try to add the feature to this location's registry.
            locationRegistry.get(first)?.add(Pair(feature, second))
            // If this is the first registration for this location, initialize the TreeSet.
            ?: locationRegistry.put(first, sortedSetOf(priorityDescending, Pair(feature, second)))
        }
    }

    /**
     * Whether or not a requested feature is enabled
     *
     * @param featureClass - The class of the feature (doesn't require an instance to be created)
     * @return true if the requested feature is enabled in the current session.
     */
    fun isFeatureEnabled(featureClass: Class<out PhotopickerFeature>): Boolean {
        return _enabledFeatures.any { it::class.java == featureClass }
    }

    /**
     * Calls all of the relevant compose methods for all enabled [PhotopickerUiFeature] that have
     * the [Location] in their registered locations, in their declared priority descending order.
     *
     * Features with a higher priority are composed first.
     *
     * This is the primary API for features to compose UI using the [Location] framework.
     *
     * This can result in an empty [Composable] if no features have the provided [Location] in their
     * list of registered locations.
     *
     * Note: Be careful where this is called in the UI tree. Calling this inside of a composable
     * that is reguarly re-composed will result in the entire subtree being re-composed, which can
     * impact performance.
     *
     * @param location The UI location that needs to be composed
     * @param maxSlots (Optional, default unlimited) The maximum number of features that can compose
     *   at this location. If set, this will call features in priority order until all slots of been
     *   exhausted.
     */
    @Composable
    fun composeLocation(location: Location, maxSlots: Int? = null) {
        Log.d(TAG, "Composing for $location")

        val featurePairs = locationRegistry.get(location)

        // There is no guarantee the [Location] exists in the registry, since it is initialized
        // lazily, its possible that features have not been registered.
        featurePairs?.let {
            for (feature in featurePairs.take(maxSlots ?: featurePairs.size)) {
                feature.first.compose(location)
            }
        }
    }
}
