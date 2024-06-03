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

package com.android.photopicker.features.selectionbar

import androidx.compose.runtime.Composable
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.events.RegisteredEventClass
import androidx.compose.ui.Modifier
import com.android.photopicker.core.events.Event

/** Feature class for the Photopicker's selection bar. */
class SelectionBarFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerSelectionBarFeature"

        // The selection bar is only shown when in multi-select mode. For single select,
        // the activity ends as soon as the first Media is selected, so this feature is
        // disabled to prevent it's animation for playing when the selection changes.
        override fun isEnabled(config: PhotopickerConfiguration) = config.selectionLimit > 1
        override fun build(featureManager: FeatureManager) = SelectionBarFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.SELECTION_BAR, Priority.HIGH.priority))
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return emptySet()
    }

    override val token = FeatureToken.SELECTION_BAR.token

    /** Events consumed by the selection bar */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the selection bar */
    override val eventsProduced = setOf(Event.MediaSelectionConfirmed::class.java)

    @Composable
    override fun compose(location: Location, modifier: Modifier) {
        when (location) {
            Location.SELECTION_BAR -> SelectionBar(modifier)
            else -> {}
        }
    }
}
