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

package com.android.photopicker.features.alwaysdisabledfeature

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.Priority

/**
 * Test [PhotopickerUiFeature] that is always disabled, no matter what it tries, but always tries to
 * render it's message to [Location.COMPOSE_TOP], to no avail.
 */
class AlwaysDisabledFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "AlwaysDisabledFeature"

        override fun isEnabled(config: PhotopickerConfiguration) = false

        override fun build(featureManager: FeatureManager) = AlwaysDisabledFeature()

        val UI_STRING = "Can anyone hear me? :("
    }

    override val token = TAG

    /** Events consumed by the Photo grid */
    override val eventsConsumed = emptySet<RegisteredEventClass>()

    /** Events produced by the Photo grid */
    override val eventsProduced = emptySet<RegisteredEventClass>()

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.COMPOSE_TOP, Priority.REGISTRATION_ORDER.priority))
    }

    @Composable
    override fun compose(
        location: Location,
        modifier: Modifier,
        params: LocationParams,
    ) {
        when (location) {
            Location.COMPOSE_TOP -> composeTop()
            else -> {}
        }
    }

    @Composable
    private fun composeTop() {
        Text(UI_STRING)
    }
}
