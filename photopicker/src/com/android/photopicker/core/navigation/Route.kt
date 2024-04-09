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

package com.android.photopicker.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink

/** A definition of a navigable route inside of the Photopicker navigation graph. */
interface Route {

    /**
     * The id of the route. This should be unique to the application, and should in most cases be an
     * [PhotopickerDestinations] enum to prevent collisions.
     *
     * UI elements will use this string to tell the [NavController] to navigate to this route.
     */
    val route: String

    /**
     * The priority with which the application should consider this route as the initial route to
     * use on application start. The highest priority is chosen at application start up, and then
     * this value has no further effect.
     *
     * If a route should not be considered to be the initial route it should use [Priority.LAST]
     */
    val initialRoutePriority: Int

    /**
     * Named navigation arguments that can be accessed by this route's composable. If nothing is
     * required, this should return an empty list.
     */
    val arguments: List<NamedNavArgument>

    /**
     * A list of navigation deep links to associate with this Route. If nothing is required, this
     * should return an empty list.
     */
    val deepLinks: List<NavDeepLink>

    /**
     * Whether this route should be rendered as a [androidx.compose.ui.window.Dialog]. This is
     * suitable only when this route represents a separate screen in your app that needs its own
     * lifecycle and saved state, independent of any other destination in your navigation graph. For
     * use cases such as [AlertDialog], you should use those APIs directly in the composable
     * destination that wants to show that dialog.
     */
    val isDialog: Boolean

    /**
     * In the event [isDialog] is set to true, these are the [DialogProperties] that will be passed
     * to the [androidx.compose.ui.window.Dialog] instance.
     */
    val dialogProperties: DialogProperties?

    /** Animation callback to define enter transitions for destination in this NavGraph */
    val enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)?

    /** Animation callback to define exit transitions for destination in this NavGraph */
    val exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)?

    /** Animation callback to define pop enter transitions for destination in this NavGraph */
    val popEnterTransition:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)?

    /** Animation callback to define pop exit transitions for destination in this NavGraph */
    val popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)?

    /**
     * The composable content for this Route.
     *
     * If this route is a Dialog, this is the composable content that will be hosted inside of the
     * [androidx.compose.ui.window.Dialog]
     *
     * This content is attached to the compose UI tree, and shares all of the same contexts as the
     * rest of the compose tree. (i.e. can access the LocalCompositionProvider to obtain the
     * [FeatureManager])
     *
     * @param navBackStackEntry the navBackStackEntry is provided for this Route for checking
     *   navigation arguments, or other navigation related tasks.
     */
    @Composable fun composable(navBackStackEntry: NavBackStackEntry?): Unit
}
