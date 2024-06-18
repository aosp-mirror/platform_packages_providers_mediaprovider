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

package com.android.photopicker.features.simpleuifeature

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.navigation.Route

/** Test [PhotopickerUiFeature] that renders a simple string to [Location.COMPOSE_TOP] */
open class SimpleUiFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "SimpleUiFeature"
        override fun isEnabled(config: PhotopickerConfiguration) = true
        override fun build(featureManager: FeatureManager) = SimpleUiFeature()

        val UI_STRING = "I'm a simple string, from a SimpleUiFeature"
        val SIMPLE_ROUTE = "simple"
    }

    /** Compose Location callback from feature framework */
    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.COMPOSE_TOP, Priority.REGISTRATION_ORDER.priority))
    }

    /** Navigation registration callback from feature framework */
    override fun registerNavigationRoutes(): Set<Route> {
        return setOf(
            object : Route {
                override val route = SIMPLE_ROUTE
                override val initialRoutePriority = Priority.MEDIUM.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = false
                override val dialogProperties = null
                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null
                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    simpleRoute()
                }
            },
        )
    }

    /* Feature framework compose-at-location callback */
    @Composable
    override fun compose(location: Location) {

        when (location) {
            Location.COMPOSE_TOP -> composeTop()
            else -> {}
        }
    }

    /* Private composable used for the [Location.COMPOSE_TOP] location */
    @Composable
    private fun composeTop() {
        Text(UI_STRING)
    }

    /* Composes the [SIMPLE_ROUTE] */
    @Composable
    private fun simpleRoute() {
        Text(UI_STRING)
    }
}
