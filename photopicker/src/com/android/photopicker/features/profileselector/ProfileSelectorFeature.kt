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

package com.android.photopicker.features.profileselector

import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.Priority

/** Feature class for the Photopicker's Profile Selector button. */
class ProfileSelectorFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerProfileSelectorFeature"

        override fun isEnabled(config: PhotopickerConfiguration): Boolean {

            // Profile switching is not permitted in permission mode.
            if (MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(config.action)) {
                return false
            }

            return true
        }

        override fun build(featureManager: FeatureManager) = ProfileSelectorFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.PROFILE_SELECTOR, Priority.HIGH.priority))
    }

    override val token = FeatureToken.PROFILE_SELECTOR.token

    /** Events consumed by the ProfileSelector */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the ProfileSelector */
    override val eventsProduced =
        setOf<RegisteredEventClass>(Event.LogPhotopickerUIEvent::class.java)

    @Composable
    override fun compose(
        location: Location,
        modifier: Modifier,
        params: LocationParams,
    ) {
        when (location) {
            Location.PROFILE_SELECTOR -> ProfileSelector(modifier)
            else -> {}
        }
    }
}
