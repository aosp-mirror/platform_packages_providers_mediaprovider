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

package com.android.photopicker.features.overflowmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.photopicker.R
import com.android.photopicker.core.components.ElevationTokens
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import kotlinx.coroutines.launch

/**
 * Top of the OverflowMenu feature.
 *
 * This feature will create a "3-dot" icon button which when clicked will open an overflow menu of
 * additional action items. This feature does not directly register any items to the menu itself,
 * but instead is responsible for displaying the menu's anchor, and building / showing the menu when
 * it is required.
 *
 * Features wishing to add items to the overflow menu should declare content at the
 * [Location.OVERFLOW_MENU_ITEMS] when they are enabled to receive a call from the [FeatureManager]
 * to create an item in the menu.
 *
 * Additionally, features are responsible for their own click handlers on these menu items, and
 * should use the base composable [OverflowMenuItem] which wraps [DropdownMenuItem], rather than
 * trying to implement their own composable.
 */
@Composable
fun OverflowMenu(modifier: Modifier = Modifier) {

    // Only show the overflow menu anchor if there will actually be items to select.
    if (LocalFeatureManager.current.getSizeOfLocationInRegistry(Location.OVERFLOW_MENU_ITEMS) > 0) {
        var expanded by remember { mutableStateOf(false) }
        val events = LocalEvents.current
        val scope = rememberCoroutineScope()
        val configuration = LocalPhotopickerConfiguration.current

        // Wrapped in a box to consume anything in the incoming modifier.
        Box(modifier = modifier) {
            IconButton(
                onClick = {
                    expanded = !expanded
                    // Dispatch UI event to log interaction with picker menu
                    if (expanded) {
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.OVERFLOW_MENU.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.PICKER_MENU_CLICK
                                )
                            )
                        }
                    }
                }
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription =
                        stringResource(R.string.photopicker_overflow_menu_description)
                )
            }

            // DropdownMenu attaches to the element above it in the hierarchy, so this should stay
            // directly below the button that opens it.
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = !expanded },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = ElevationTokens.Level2,
            ) {
                LocalFeatureManager.current.composeLocation(
                    Location.OVERFLOW_MENU_ITEMS,
                    modifier = Modifier,
                    params = LocationParams.WithClickAction { expanded = !expanded },
                )
            }
        }
    } else {
        // FeatureManager reported a size of 0 for [Location.OVERFLOW_MENU_ITEMS], thus there is no
        // need to show the overflow anchor. In order to keep the layout stable, consume the
        // incoming modifier with a spacer element.
        Spacer(modifier)
    }
}

/**
 * Composable that features should use when adding options to the [OverflowMenuFeature].
 *
 * @param label The display label of the menu item.
 * @param onClick handler for when the option is selected by the user.
 */
@Composable
fun OverflowMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        modifier = Modifier.widthIn(min = 200.dp)
    )
}
