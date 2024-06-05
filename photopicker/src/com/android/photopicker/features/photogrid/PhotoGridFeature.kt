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

package com.android.photopicker.features.photogrid

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
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
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.navigation.Route

/**
 * Feature class for the Photopicker's primary photo grid.
 *
 * This feature adds the [PHOTO_GRID] route to the application as a high priority initial route.
 */
class PhotoGridFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerPhotoGridFeature"

        override fun isEnabled(config: PhotopickerConfiguration) = true

        override fun build(featureManager: FeatureManager) = PhotoGridFeature()
    }

    override val token = FeatureToken.PHOTO_GRID.token

    /** Events consumed by the Photo grid */
    override val eventsConsumed = emptySet<RegisteredEventClass>()

    /** Events produced by the Photo grid */
    override val eventsProduced = setOf(Event.ShowSnackbarMessage::class.java)

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(
            Pair(Location.NAVIGATION_BAR_NAV_BUTTON, Priority.HIGH.priority),
        )
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf(
            // The main grid of the user's photos.
            object : Route {
                override val route = PhotopickerDestinations.PHOTO_GRID.route
                override val initialRoutePriority = Priority.HIGH.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = false
                override val dialogProperties = null

                /*
                Animations for PHOTO_GRID
                - When navigating directly, content will slide IN from the left edge.
                - When navigating away, content will slide OUT towards the left edge.
                - When returning from the backstack, content will slide IN from the right edge.
                - When popping to another route on the backstack, content will slide OUT towards
                  the left edge.
                 */
                override val enterTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
                    {
                        // Positive value to slide left-to-right
                        slideInHorizontally { -it }
                    }
                override val exitTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
                    {
                        // Negative value to slide right-to-left
                        slideOutHorizontally { -it }
                    }
                override val popEnterTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
                    {
                        // When returning from the backstack slide right-to-left
                        slideInHorizontally { -it }
                    }
                override val popExitTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
                    {
                        // When navigating to the backstack slide left-to-right
                        slideOutHorizontally { -it }
                    }

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    PhotoGrid()
                }
            },
        )
    }

    @Composable
    override fun compose(
        location: Location,
        modifier: Modifier,
        params: LocationParams,
    ) {
        when (location) {
            Location.NAVIGATION_BAR_NAV_BUTTON -> PhotoGridNavButton(modifier)
            else -> {}
        }
    }
}
