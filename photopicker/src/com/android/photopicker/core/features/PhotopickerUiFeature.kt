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

import androidx.compose.runtime.Composable
import com.android.photopicker.core.navigation.Route

/**
 * All Features that wish to add composables to the UI must implement this interface.
 *
 * It provides hooks for registering composables into the Feature framework's Location tree, and
 * calls it's composable hook during runtime.
 */
interface PhotopickerUiFeature : PhotopickerFeature {

    /**
     * This is called during feature initialization. The FeatureManager will request a list of UI
     * [Location]s that this feature would like to receive compose calls for, and the priority with
     * which the feature should be considered for composing its UI.
     *
     * The default priority is [Priority.Default] but should be any non-negative Integer.
     *
     * In the case of a default priority, Features will have their compose functions called in the
     * order of registration in the [FeatureManager.KNOWN_FEATURE_REGISTRATIONS]
     *
     * This method does not have a default implementation, every [PhotopickerUiFeature] must
     * implement this themselves.
     */
    fun registerLocations(): List<Pair<Location, Int>>

    /**
     * The primary compose hook for rendering composables into the UI for a feature.
     *
     * The location being composed is passed as an argument, and it's expected that the feature
     * correctly maps the correct composable to the incoming location.
     *
     * It is strongly encouraged to use a when block to handle this top level compose call, and
     * correctly map the feature's composables to the location such as:
     * ```
     *
     * when(location) {
     *
     *  Location.THE_BEACH -> composeTheBeach()
     *  Location.OUTER_SPACE -> composeOuterSpace()
     *  else -> {}
     * }
     *
     * ```
     *
     * Also note the else block to handle all other locations that this UI feature does not care
     * about. It will not be called to compose for Locations not in its provided registry, but the
     * compiler will complain if all outcomes are not covered, so this is an easy way to prevent new
     * locations from requiring changes in all existing features.
     *
     * Warning: All code that executes inside of this method runs on the [MainThread] aka the UI
     * thread. Do not do expensive operations in this method, and follow all best practices for
     * normal @Composable functions. (Such as offloading background work via Coroutines.)
     */
    @Composable fun compose(location: Location): Unit

    /**
     * This is called when the [NavHost] is composed for all enabled [PhotopickerUiFeature] to
     * assemble the navigable routes.
     *
     * @return A set of [Route] to add to the navigation graph, or an empty set if this feature
     *   exposes no routes.
     */
    fun registerNavigationRoutes(): Set<Route> = emptySet<Route>()
}
