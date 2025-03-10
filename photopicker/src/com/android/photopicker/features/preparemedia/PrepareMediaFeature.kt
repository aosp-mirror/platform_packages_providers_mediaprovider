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

package com.android.photopicker.features.preparemedia

import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import kotlinx.coroutines.Deferred

/**
 * Feature class for the Photopicker's media preparation implementation.
 *
 * This feature adds the preparer for preparing media content before the Photopicker session ends.
 */
class PrepareMediaFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PrepareMediaFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            with(config) {
                if (runtimeEnv != PhotopickerRuntimeEnv.ACTIVITY) {
                    return false
                }

                // Media Prepare is not available in permission mode.
                if (action == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP) {
                    return false
                }

                val pickerTranscodingEnabled =
                    flags.PICKER_TRANSCODING_ENABLED && callingPackageMediaCapabilities != null
                return pickerTranscodingEnabled || CloudMediaFeature.isEnabled(this)
            }
        }

        override fun build(featureManager: FeatureManager) = PrepareMediaFeature()
    }

    override val token = FeatureToken.MEDIA_PREPARE.token

    override val eventsConsumed = setOf<RegisteredEventClass>()

    override val eventsProduced = setOf(Event.LogPhotopickerUIEvent::class.java)

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.MEDIA_PREPARER, Priority.HIGH.priority))
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf()
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.MEDIA_PREPARER -> MediaPreparer(modifier, params)
            else -> {}
        }
    }
}
