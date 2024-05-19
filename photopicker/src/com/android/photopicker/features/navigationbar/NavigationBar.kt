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

package com.android.photopicker.features.navigationbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations

/* Distance between two navigation buttons */
private val MEASUREMENT_SPACER_SIZE = 6.dp

private val NAV_BAR_ENABLED_ROUTES = setOf(
    PhotopickerDestinations.ALBUM_GRID.route,
    PhotopickerDestinations.PHOTO_GRID.route,
    )

/**
 * Top of the NavigationBar feature.
 *
 * Since NavigationBar doesn't register any routes its top composable is drawn at
 * [Location.NAVIGATION_BAR] which begins here.
 *
 * This composable provides a full width row for the navigation bar and calls the feature manager to
 * provide [NavigationBarButton]-s for the row.
 */
@Composable
fun NavigationBar(modifier: Modifier) {
    // The navigation bar hides itself for certain routes
    val navController = LocalNavController.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    if (currentRoute in NAV_BAR_ENABLED_ROUTES) {
        Row(
            // Consume the incoming modifier
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Buttons are provided by registered features, so request for the features to fill this
            // content.
            LocalFeatureManager.current.composeLocation(
                Location.NAVIGATION_BAR_NAV_BUTTON,
                maxSlots = 2,
                modifier = Modifier.padding(MEASUREMENT_SPACER_SIZE)
            )
        }
    }
}

/**
 * Composable that can be used to build a consistent button for the [NavigationBarFeature]
 *
 * @param onClick the handler to run when the button is clicked.
 * @param modifier A modifier which is applied directly to the button. This should be the modifier
 *   that is passed via the Location compose call.
 * @param isCurrentRoute a function which receives the current
 *   [NavController.currentDestination.route] and returns true if that route matches the route this
 *   button represents.
 * @param buttonContent A composable to render as the button's content. Should most likely be a
 *   string label.
 */
@Composable
fun NavigationBarButton(
    onClick: () -> Unit,
    modifier: Modifier,
    isCurrentRoute: (String) -> Boolean,
    buttonContent: @Composable () -> Unit
) {
    val navController = LocalNavController.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors =
            if (isCurrentRoute(currentRoute ?: ""))
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            else ButtonDefaults.filledTonalButtonColors()
    ) {
        buttonContent()
    }
}
