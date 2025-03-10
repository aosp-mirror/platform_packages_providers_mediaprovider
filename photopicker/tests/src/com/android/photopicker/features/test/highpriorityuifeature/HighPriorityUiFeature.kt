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

package com.android.photopicker.features.highpriorityuifeature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations.ALBUM_GRID
import com.android.photopicker.core.navigation.PhotopickerDestinations.PHOTO_GRID
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import kotlinx.coroutines.Deferred

/**
 * Test [PhotopickerUiFeature] that renders a simple string to [Location.COMPOSE_TOP] with the
 * utmost priority.
 */
class HighPriorityUiFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "HighPriorityUiFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ) = true

        override fun build(featureManager: FeatureManager) = HighPriorityUiFeature()

        val UI_STRING = "I'm super important."
        val START_ROUTE = "highpriority/start"
        val START_STRING = "I'm the start location."
        val DIALOG_ROUTE = "highpriority/dialog"
        val DIALOG_STRING = "I'm the dialog location."
    }

    override val token = TAG

    /** Only one banner is claimed */
    override val ownedBanners = setOf(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT)

    override suspend fun getBannerPriority(
        banner: BannerDefinitions,
        bannerState: BannerState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Int {
        // If the banner reports as being dismissed, don't show it.
        if (bannerState?.dismissed == true) {
            return Priority.DISABLED.priority
        }

        // Otherwise, show it with medium priority.
        return Priority.HIGH.priority
    }

    override suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Banner {
        return object : Banner {
            override val declaration = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT

            @Composable override fun buildTitle() = "Choose Account Title"

            @Composable override fun buildMessage() = "Choose Account Message"
        }
    }

    /** Events consumed by the Photo grid */
    override val eventsConsumed = emptySet<RegisteredEventClass>()

    /** Events produced by the Photo grid */
    override val eventsProduced = emptySet<RegisteredEventClass>()

    /** Compose Location callback from feature framework */
    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.COMPOSE_TOP, Priority.HIGH.priority))
    }

    /** Navigation registration callback from feature framework */
    override fun registerNavigationRoutes(): Set<Route> {
        return setOf(
            object : Route {
                override val route = START_ROUTE
                override val initialRoutePriority = Priority.HIGH.priority
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
                    start()
                }
            },
            object : Route {
                override val route = DIALOG_ROUTE
                override val initialRoutePriority = Priority.REGISTRATION_ORDER.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = true
                override val dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    dialog()
                }
            },

            // This is implemented for PhotopickerNavGraphTest
            object : Route {
                override val route = PHOTO_GRID.route
                override val initialRoutePriority = Priority.LAST.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = false
                override val dialogProperties = null
                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null

                @Composable override fun composable(navBackStackEntry: NavBackStackEntry?) {}
            },
            // This is implemented for PhotopickerNavGraphTest
            object : Route {
                override val route = ALBUM_GRID.route
                override val initialRoutePriority = Priority.LAST.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = false
                override val dialogProperties = null
                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null

                @Composable override fun composable(navBackStackEntry: NavBackStackEntry?) {}
            },
        )
    }

    /* Feature framework compose-at-location callback */
    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
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

    /** Composes a dialog location, with a button to navigate back to the [START_ROUTE] */
    @Composable
    private fun dialog() {
        val navController = LocalNavController.current
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text(DIALOG_STRING)
                Button(onClick = { navController.navigate(START_ROUTE) }) {
                    Text("navigate to start")
                }
            }
        }
    }

    /**
     * Composes the test start location, and if the [SimpleUiFeature] is enabled, also includes a
     * button that navigates to the [SimpleUiFeature.SIMPLE_ROUTE]
     */
    @Composable
    private fun start() {
        val featureManager = LocalFeatureManager.current
        val navController = LocalNavController.current
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text(START_STRING)

                Button(onClick = { navController.navigate(DIALOG_ROUTE) }) {
                    Text("navigate to dialog")
                }

                // Optionally add a navigation button if SimpleUiFeature is enabled.
                if (featureManager.isFeatureEnabled(SimpleUiFeature::class.java)) {
                    Button(onClick = { navController.navigate(SimpleUiFeature.SIMPLE_ROUTE) }) {
                        Text("navigate to simple ui")
                    }
                }
            }
        }
    }
}
