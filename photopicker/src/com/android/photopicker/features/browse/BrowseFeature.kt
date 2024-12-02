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

package com.android.photopicker.features.browse

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.photopicker.R
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv.ACTIVITY
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
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
import com.android.photopicker.features.overflowmenu.OverflowMenuItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Feature class for the Photopicker's browse functionality.
 *
 * This feature adds the Browse option to the overflow menu when the session is in
 * ACTION_GET_CONTENT.
 */
class BrowseFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerBrowseFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            // Browse is only available for ACTION_GET_CONTENT when in the activity runtime env
            return config.action == Intent.ACTION_GET_CONTENT && config.runtimeEnv == ACTIVITY
        }

        override fun build(featureManager: FeatureManager) = BrowseFeature()
    }

    override val token = FeatureToken.BROWSE.token

    override val eventsConsumed = setOf<RegisteredEventClass>()

    override val eventsProduced = setOf<RegisteredEventClass>(Event.BrowseToDocumentsUi::class.java)

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.OVERFLOW_MENU_ITEMS, Priority.HIGH.priority))
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf()
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.OVERFLOW_MENU_ITEMS -> {
                val clickAction = params as? LocationParams.WithClickAction
                val scope = rememberCoroutineScope()
                val events = LocalEvents.current
                OverflowMenuItem(
                    label = stringResource(R.string.photopicker_overflow_browse),
                    onClick = {
                        scope.launch {
                            events.dispatch(Event.BrowseToDocumentsUi(dispatcherToken = token))
                        }
                        clickAction?.onClick()
                    },
                )
            }
            else -> {}
        }
    }
}
