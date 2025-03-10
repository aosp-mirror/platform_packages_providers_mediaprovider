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

package com.android.photopicker.features.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
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
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.data.model.Media
import kotlinx.coroutines.Deferred

/**
 * Feature class for the Photopicker's media preview.
 *
 * This feature adds the [PREVIEW_MEDIA] and [PREVIEW_SELECTION] routes to the application.
 */
class PreviewFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerPreviewFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ) = config.runtimeEnv != PhotopickerRuntimeEnv.EMBEDDED

        override fun build(featureManager: FeatureManager) = PreviewFeature()

        val PREVIEW_MEDIA_KEY = "preview_media"
    }

    override val token = FeatureToken.PREVIEW.token

    /** Events consumed by the Preview page */
    override val eventsConsumed = emptySet<RegisteredEventClass>()

    /** Events produced by the Preview page */
    override val eventsProduced =
        setOf<RegisteredEventClass>(
            Event.LogPhotopickerUIEvent::class.java,
            Event.LogPhotopickerPreviewInfo::class.java,
        )

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.SELECTION_BAR_SECONDARY_ACTION, Priority.HIGH.priority))
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf(
            object : Route {
                override val route = PhotopickerDestinations.PREVIEW_SELECTION.route
                override val initialRoutePriority = Priority.LAST.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = true
                override val dialogProperties =
                    DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = true,
                    )

                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    PreviewSelection()
                }
            },
            object : Route {
                override val route = PhotopickerDestinations.PREVIEW_MEDIA.route
                override val initialRoutePriority = Priority.LAST.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = true
                override val dialogProperties =
                    DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = true,
                    )

                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    val flow =
                        checkNotNull(
                            navBackStackEntry
                                ?.savedStateHandle
                                ?.getStateFlow<Media?>(PREVIEW_MEDIA_KEY, null)
                        ) {
                            "Unable to get a savedStateHandle for preview media"
                        }
                    PreviewSelection(previewItemFlow = flow)
                }
            },
        )
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.SELECTION_BAR_SECONDARY_ACTION -> PreviewSelectionButton(modifier)
            else -> {}
        }
    }
}
