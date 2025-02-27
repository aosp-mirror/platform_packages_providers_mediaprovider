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

import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.data.PrefetchDataService
import kotlinx.coroutines.Deferred

/**
 * This registration interface represents the registration contract between [FeatureManager] and
 * Individual feature implementors. It provides hooks for FeatureManager to check if a feature
 * should be considered enabled for a given Configuration state, and provides a factory to build an
 * instance of the feature. (Features should not try to instantiate themselves)
 *
 * For most features, implementing this as a Companion object (named Registration) on the Feature's
 * base Class should be the best pattern to follow:
 * ```
 * class MyPhotopickerFeature : PhotopickerFeature {
 *    companion object Registration { ... }
 * }
 * ```
 */
interface FeatureRegistration {

    /** Enforces that all features define a logging tag. This does not need to be unique. */
    val TAG: String

    /**
     * Called once when [FeatureManager] is initialized for a Photopicker session.
     *
     * If a feature needs to run relatively expensive operation before determining whether the
     * feature should be enabled in the current session or not, they should override this method and
     * return a map of prefetch data requests. A prefetch data request contains a key and a lambda
     * that fetches the required result.
     *
     * Please note that all prefetch requests will be timed out after a few hundred milliseconds so
     * please ensure that the prefetch operation does not take a long time to execute.
     *
     * Typically a prefetch request would fetch some information required from a different process.
     *
     * @param config An instance of [PhotopickerConfiguration].
     * @return A map of prefetch requests with a key from [PrefetchResultKey] and prefetch lambda as
     *   the value.
     */
    fun getPrefetchRequest(
        config: PhotopickerConfiguration
    ): Map<PrefetchResultKey, suspend (PrefetchDataService) -> Any?>? = null

    /**
     * Called everytime the [PhotopickerConfiguration] of the activity is changed. This will be
     * called infrequently, (usually just once) as is used by the [FeatureManager] to determine if
     * the base feature class should be instantiated in the current session, with the provided
     * configuration.
     *
     * In the event of a configuration change, this will be called again with the new configuration,
     * and will help determine if the feature should be kept in activity scope, or dereferenced.
     *
     * @param config An instance of [PhotopickerConfiguration].
     * @param deferredPrefetchResultsMap A map with the deferred results of prefetch results.
     * @return Whether the Feature that this FeatureRegistration represents should be enabled with
     *   the given configuration.
     */
    fun isEnabled(
        config: PhotopickerConfiguration,
        deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>> = mapOf(),
    ): Boolean = false

    /**
     * An exposed factory method for instantiating the registered feature. This is eventually called
     * by the [FeatureManager] during initialization, or possibly upon configuration change.
     *
     * @return an instance of the PhotopickerFeature
     */
    fun build(featureManager: FeatureManager): PhotopickerFeature
}
