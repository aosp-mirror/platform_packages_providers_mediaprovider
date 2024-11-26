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

import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import com.android.photopicker.R
import com.android.photopicker.core.StateSelector
import com.android.photopicker.core.animations.standardDecelerate
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.hideWhenState
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.data.model.Group
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.features.albumgrid.AlbumGridFeature
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.features.profileselector.ProfileSelectorFeature
import com.android.photopicker.features.search.SearchFeature

/* Navigation bar button measurements */
private val MEASUREMENT_ICON_BUTTON_WIDTH = 48.dp
private val MEASUREMENT_ICON_BUTTON_OUTSIDE_PADDING = 4.dp

/* Distance between two navigation buttons */
private val MEASUREMENT_SPACER_SIZE = 8.dp

/* Padding values around the edges of the NavigationBar */
private val MEASUREMENT_EDGE_PADDING = 4.dp
private val MEASUREMENT_TOP_PADDING = 8.dp
private val MEASUREMENT_BOT_PADDING = 24.dp

/* Minimum height for the NavigationBar */
private val MEASUREMENT_MIN_HEIGHT = 48.dp

/**
 * Top of the NavigationBar feature.
 *
 * Since NavigationBar doesn't register any routes its top composable is drawn at
 * [Location.NAVIGATION_BAR] which begins here.
 *
 * This composable provides a full width row for the navigation bar and calls the feature manager to
 * provide [NavigationBarButton]s for the row.
 *
 * If the search feature is enabled [Location.SEARCH_BAR] is drawn above the [NavigationBarButtons]
 * at the top.
 *
 * Additionally, the composable also calls for the [PROFILE_SELECTOR] and [OVERFLOW_MENU] locations.
 */
@Composable
fun NavigationBar(modifier: Modifier = Modifier, params: LocationParams) {

    val navController = LocalNavController.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val featureManager = LocalFeatureManager.current
    val searchFeatureEnabled = featureManager.isFeatureEnabled(SearchFeature::class.java)

    Row(
        modifier =
            modifier
                .padding(
                    start = MEASUREMENT_EDGE_PADDING,
                    end = MEASUREMENT_EDGE_PADDING,
                    top = MEASUREMENT_TOP_PADDING,
                    bottom = MEASUREMENT_BOT_PADDING,
                )
                .heightIn(min = MEASUREMENT_MIN_HEIGHT),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        when {

            // When inside an album display the album title and a back button,
            // instead of the normal navigation bar contents.
            currentRoute == PhotopickerDestinations.ALBUM_MEDIA_GRID.route ->
                NavigationBarForAlbum(modifier)

            // When search feature is enabled then display search bar along with profile selector,
            // overflow menu and the navigation buttons below it.
            searchFeatureEnabled -> NavigationBarWithSearch(modifier, params)

            // For all other routes, show the profile selector and the navigation buttons
            else -> BasicNavigationBar(modifier)
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
    buttonContent: @Composable () -> Unit,
) {
    val navController = LocalNavController.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors =
            if (isCurrentRoute(currentRoute ?: "")) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor =
                        CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                            /* fallback */ MaterialTheme.colorScheme.primary
                        ),
                    contentColor =
                        CustomAccentColorScheme.current
                            .getTextColorForAccentComponentsIfDefinedOrElse(
                                /* fallback */ MaterialTheme.colorScheme.onPrimary
                            ),
                )
            } else {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
    ) {
        buttonContent()
    }
}

/**
 * Creates and positions any navigation buttons that have been registered for the
 * [NAVIGATION_BAR_NAV_BUTTON] location. Accepts a maximum of two buttons.
 */
@Composable
private fun NavigationBarButtons(modifier: Modifier) {
    Row(
        // Consume the incoming modifier to get the correct positioning.
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            // Layout in individual buttons in a row, and space them evenly.
            horizontalArrangement =
                Arrangement.spacedBy(
                    MEASUREMENT_SPACER_SIZE,
                    alignment = Alignment.CenterHorizontally,
                )
        ) {
            val featureManager = LocalFeatureManager.current
            val searchFeatureEnabled = featureManager.isFeatureEnabled(SearchFeature::class.java)
            // Buttons are provided by registered features, so request for the features to fill
            // this content.
            LocalFeatureManager.current.composeLocation(
                Location.NAVIGATION_BAR_NAV_BUTTON,
                maxSlots = 2,
                modifier =
                    if (searchFeatureEnabled) {
                        Modifier.weight(1f)
                    } else {
                        Modifier // No modifier needed when search not enabled
                    },
            )
        }
    }
}

/**
 * Composable that provides Navigation Bar when inside an album that displays the album title and a
 * back button
 *
 * @param modifier Modifier used to configure the layout of the navigation bar.
 */
@Composable
private fun NavigationBarForAlbum(modifier: Modifier) {
    val navController = LocalNavController.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    Row(modifier = modifier.fillMaxWidth()) {
        val flow =
            navBackStackEntry
                ?.savedStateHandle
                ?.getStateFlow<Group.Album?>(AlbumGridFeature.ALBUM_KEY, null)
        val album = flow?.value
        when (album) {
            null -> {}
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // back button
                    IconButton(
                        modifier =
                            Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH)
                                .padding(horizontal = MEASUREMENT_ICON_BUTTON_OUTSIDE_PADDING),
                        onClick = { navController.navigateToAlbumGrid() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // For accessibility
                            contentDescription = stringResource(R.string.photopicker_back_option),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // Album name
                    Text(
                        text = album.displayName,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                        // Traversal index -1 forces TalkBack to focus on the album title first.
                        modifier = Modifier.semantics { traversalIndex = -1f },
                    )
                }
            }
        }
        val featureManager = LocalFeatureManager.current
        val overFlowMenuEnabled =
            remember(featureManager) {
                featureManager.isFeatureEnabled(OverflowMenuFeature::class.java)
            }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            if (overFlowMenuEnabled) {
                featureManager.composeLocation(
                    Location.OVERFLOW_MENU,
                    modifier = Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH),
                )
            } else {
                Spacer(Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH))
            }
        }
    }
}

/**
 * A composable function that displays a Navigation Bar with an integrated search bar which is
 * called when the search feature is enabled.
 *
 * This Navigation Bar also includes [PROFILE_SELECTOR] and [OVERFLOW_MENU]
 *
 * Navigation buttons are positioned below the search bar.
 */
@Composable
private fun NavigationBarWithSearch(modifier: Modifier, params: LocationParams) {
    val featureManager = LocalFeatureManager.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            featureManager.composeLocation(
                Location.SEARCH_BAR,
                maxSlots = 1,
                modifier = Modifier.weight(1f),
                params,
            )
            featureManager.composeLocation(
                Location.PROFILE_SELECTOR,
                maxSlots = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
            val overFlowMenuEnabled =
                remember(featureManager) {
                    featureManager.isFeatureEnabled(OverflowMenuFeature::class.java)
                }
            if (
                overFlowMenuEnabled &&
                    LocalFeatureManager.current.getSizeOfLocationInRegistry(
                        Location.OVERFLOW_MENU_ITEMS
                    ) > 0
            ) {
                Row(modifier = Modifier, horizontalArrangement = Arrangement.End) {
                    featureManager.composeLocation(
                        Location.OVERFLOW_MENU,
                        modifier = Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH),
                    )
                }
            }
        }
        NavigationBarButtons(Modifier.padding(start = 8.dp, end = 8.dp))
    }
}

/**
 * A composable function that displays a default Navigation Bar. This includes a [PROFILE_SELECTOR]
 * and [OVERFLOW_MENU] along with navigation buttons.
 */
@Composable
private fun BasicNavigationBar(modifier: Modifier) {
    val featureManager = LocalFeatureManager.current
    val profileSelectorEnabled =
        remember(featureManager) {
            featureManager.isFeatureEnabled(ProfileSelectorFeature::class.java)
        }
    val overFlowMenuEnabled =
        remember(featureManager) {
            featureManager.isFeatureEnabled(OverflowMenuFeature::class.java)
        }
    Row(modifier = modifier.fillMaxWidth()) {
        if (profileSelectorEnabled) {
            featureManager.composeLocation(
                Location.PROFILE_SELECTOR,
                maxSlots = 1,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        } else {
            Spacer(
                Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH)
                    .padding(start = MEASUREMENT_ICON_BUTTON_OUTSIDE_PADDING)
                    .weight(1f)
            )
        }
        hideWhenState(
            selector =
                object : StateSelector.AnimatedVisibilityInEmbedded {
                    override val visible = LocalEmbeddedState.current?.isExpanded ?: false
                    override val enter = expandVertically(animationSpec = standardDecelerate(150))
                    override val exit = shrinkVertically(animationSpec = standardDecelerate(100))
                }
        ) {
            NavigationBarButtons(Modifier)
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            if (overFlowMenuEnabled) {
                featureManager.composeLocation(
                    Location.OVERFLOW_MENU,
                    modifier = Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH),
                )
            } else {
                Spacer(Modifier.width(MEASUREMENT_ICON_BUTTON_WIDTH))
            }
        }
    }
}
