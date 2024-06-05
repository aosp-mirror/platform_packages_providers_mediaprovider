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

package com.android.photopicker.features.navigationbar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.Priority

/** Feature class for the Photopicker's navigation bar. */
class NavigationBarFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerNavigationBarFeature"

        override fun isEnabled(config: PhotopickerConfiguration) = true

        override fun build(featureManager: FeatureManager) = NavigationBarFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.NAVIGATION_BAR, Priority.HIGH.priority))
    }

    override val token = FeatureToken.NAVIGATION_BAR.token

    /** Events consumed by the selection bar */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the selection bar */
    override val eventsProduced = setOf<RegisteredEventClass>()

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.NAVIGATION_BAR -> NavigationBar(modifier)
            else -> {}
        }
    }
}
