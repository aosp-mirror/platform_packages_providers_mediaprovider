/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.categorygrid

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
import com.android.photopicker.core.animations.springDefaultEffectOffset
import com.android.photopicker.core.configuration.PhotopickerConfiguration
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
import com.android.photopicker.core.navigation.PhotopickerDestinations.ALBUM_MEDIA_GRID
import com.android.photopicker.core.navigation.PhotopickerDestinations.CATEGORY_GRID
import com.android.photopicker.core.navigation.PhotopickerDestinations.PHOTO_GRID
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.data.model.Group
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/**
 * Feature class for the Photopicker's main category grid.
 *
 * This feature adds the [CategoryGrid] route and [MediaSet] route.
 */
class CategoryGridFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotoPickerCategoryGridFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ) = config.flags.PICKER_SEARCH_ENABLED

        override fun build(featureManager: FeatureManager) = CategoryGridFeature()

        const val GROUP_KEY = "selected_group"
    }

    override val token = FeatureToken.CATEGORY_GRID.token

    /** Events consumed by the Category grid */
    override val eventsConsumed = emptySet<RegisteredEventClass>()

    /** Events produced by the Category grid */
    override val eventsProduced =
        setOf(
            Event.ShowSnackbarMessage::class.java,
            Event.LogPhotopickerUIEvent::class.java,
            Event.LogPhotopickerAlbumOpenedUIEvent::class.java,
        )

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.NAVIGATION_BAR_NAV_BUTTON, Priority.HIGHEST.priority))
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf(
            // The main grid of the user's category.
            object : Route {
                override val route = CATEGORY_GRID.route
                override val initialRoutePriority = Priority.HIGH.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = false
                override val dialogProperties = null

                /*
                Animations for CATEGORY_GRID
                - When navigating directly, content will slide IN from the left edge.
                - When navigating away, content will slide OUT towards the left edge.
                - When returning from the backstack, content will slide IN from the right edge.
                - When popping to another route on the backstack, content will slide OUT towards
                  the left edge.
                 */
                override val enterTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
                    {
                        if (initialState.destination.route == PHOTO_GRID.route) {
                            // Positive value to slide left-to-right
                            slideInHorizontally(animationSpec = springDefaultEffectOffset) { it }
                        } else {
                            // Negative value to slide right-to-left
                            // if previous route was not from PHOTO_GRID.
                            slideInHorizontally(animationSpec = springDefaultEffectOffset) { -it }
                        }
                    }
                override val exitTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
                    {
                        if (targetState.destination.route == PHOTO_GRID.route) {
                            // Positive value to slide left-to-right
                            slideOutHorizontally(animationSpec = springDefaultEffectOffset) { it }
                        } else {
                            // Negative value to slide right-to-left
                            // if target route is not PHOTO_GRID
                            slideOutHorizontally(animationSpec = springDefaultEffectOffset) { -it }
                        }
                    }
                override val popEnterTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
                    {
                        if (initialState.destination.route == PHOTO_GRID.route) {
                            // Positive value to slide left-to-right
                            slideInHorizontally(animationSpec = springDefaultEffectOffset) { it }
                        } else {
                            // Negative value to slide right-to-left
                            // if previous route was not from PHOTO_GRID.
                            slideInHorizontally(animationSpec = springDefaultEffectOffset) { -it }
                        }
                    }
                override val popExitTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
                    {
                        if (targetState.destination.route == PHOTO_GRID.route) {
                            // Positive value to slide left-to-right
                            slideOutHorizontally(animationSpec = springDefaultEffectOffset) { it }
                        } else {
                            // Negative value to slide right-to-left
                            // if target route is not PHOTO_GRID
                            slideOutHorizontally(animationSpec = springDefaultEffectOffset) { -it }
                        }
                    }

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    CategoryGrid()
                }
            },
            // Grid to show the album content for the album selected by the user.
            object : Route {
                override val route = ALBUM_MEDIA_GRID.route
                override val initialRoutePriority = Priority.MEDIUM.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = false
                override val dialogProperties = null

                /**
                 * Animations for CATEGORY_CONTENT_GRID are by default [EnterTransition.None] for
                 * entering into view and [ExitTransition.None] while exiting.
                 */
                override val enterTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
                    {
                        // Positive value to slide left-to-right
                        slideInHorizontally(animationSpec = springDefaultEffectOffset) { it }
                    }
                override val exitTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
                    {
                        slideOutHorizontally(animationSpec = springDefaultEffectOffset) { it }
                    }
                override val popEnterTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
                    {
                        slideInHorizontally(animationSpec = springDefaultEffectOffset) { it }
                    }
                override val popExitTransition:
                    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
                    {
                        slideOutHorizontally(animationSpec = springDefaultEffectOffset) { it }
                    }

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    val flow: StateFlow<Group.Album?> =
                        checkNotNull(
                            navBackStackEntry
                                ?.savedStateHandle
                                ?.getStateFlow<Group.Album?>(GROUP_KEY, null)
                        ) {
                            "Unable to get a savedStateHandle for album content grid"
                        }
                    AlbumMediaGrid(flow)
                }
            },
        )
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.NAVIGATION_BAR_NAV_BUTTON -> CategoryButton(modifier)
            else -> {}
        }
    }
}
