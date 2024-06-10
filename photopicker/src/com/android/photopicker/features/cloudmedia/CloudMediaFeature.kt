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

package com.android.photopicker.features.cloudmedia

import android.provider.MediaStore
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
import com.android.photopicker.core.navigation.Route

/**
 * Feature class for the Photopicker's cloud media implementation.
 *
 * This feature adds the Cloud media preloader for preloading off-device content before the
 * Photopicker session ends.
 */
class CloudMediaFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerCloudMediaFeature"

        override fun isEnabled(config: PhotopickerConfiguration): Boolean {

            // Cloud media is not available in permission mode.
            if (config.action == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP) return false

            return true
        }

        override fun build(featureManager: FeatureManager) = CloudMediaFeature()
    }

    override val token = FeatureToken.CLOUD_MEDIA.token

    /** Events consumed by Cloud Media */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the Cloud Media */
    override val eventsProduced = setOf<RegisteredEventClass>()

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(
            Pair(Location.MEDIA_PRELOADER, Priority.HIGH.priority),
        )
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf()
    }

    @Composable
    override fun compose(
        location: Location,
        modifier: Modifier,
        params: LocationParams,
    ) {
        when (location) {
            Location.MEDIA_PRELOADER -> MediaPreloader(modifier, params)
            else -> {}
        }
    }
}
