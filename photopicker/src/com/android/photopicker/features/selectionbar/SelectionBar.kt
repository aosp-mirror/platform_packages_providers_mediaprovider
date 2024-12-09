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

package com.android.photopicker.features.selectionbar

import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.animations.emphasizedAccelerate
import com.android.photopicker.core.animations.emphasizedDecelerate
import com.android.photopicker.core.components.ElevationTokens
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.util.LocalLocalizationHelper
import kotlinx.coroutines.launch

/* The size of spacers between elements on the bar */
private val MEASUREMENT_BUTTONS_SPACER_SIZE = 8.dp
private val MEASUREMENT_DESELECT_SPACER_SIZE = 4.dp

/* Corner radius of the selection bar */
private val MEASUREMENT_SELECTION_BAR_CORNER_SIZE = 100

/* The amount of padding between elements and the edge of the selection bar */
private val MEASUREMENT_BAR_PADDING = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/**
 * The Photopicker selection bar that shows the actions related to the current selection of Media.
 * This composable does not provide a secondary action button directly, but instead exposes
 * [Location.SELECTION_BAR_SECONDARY_ACTION] for another feature to provide a secondary action that
 * is relevant to the selection bar. If not are provided, the space will be left empty.
 */
@Composable
fun SelectionBar(modifier: Modifier = Modifier, params: LocationParams) {
    // Collect selection to ensure this is recomposed when the selection is updated.
    val selection = LocalSelection.current
    val currentSelection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    // For ACTION_USER_SELECT_IMAGES_FOR_APP selection bar should always be visible to allow users
    // the option to exit with zero selection i.e. revoking all grants.
    val visible =
        currentSelection.isNotEmpty() ||
            MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(
                LocalPhotopickerConfiguration.current.action
            )
    val configuration = LocalPhotopickerConfiguration.current
    val events = LocalEvents.current
    val scope = rememberCoroutineScope()
    val localizedCurrentSelectionSize =
        LocalLocalizationHelper.current.getLocalizedCount(currentSelection.size)
    // The entire selection bar is hidden if the selection is empty, and
    // animates between visible states.
    AnimatedVisibility(
        // Pass through the modifier that is received for positioning offsets.
        modifier = modifier,
        visible = visible,
        enter =
            slideInVertically(animationSpec = emphasizedDecelerate, initialOffsetY = { it * 2 }),
        exit = slideOutVertically(animationSpec = emphasizedAccelerate, targetOffsetY = { it * 2 }),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(CornerSize(MEASUREMENT_SELECTION_BAR_CORNER_SIZE)),
            shadowElevation = ElevationTokens.Level2,
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MEASUREMENT_BAR_PADDING),
            ) {

                // Deselect all button [Left side]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { selection.clear() } }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription =
                                stringResource(
                                    R.string.photopicker_clear_selection_button_description
                                ),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.size(MEASUREMENT_DESELECT_SPACER_SIZE))
                    val selectionSizeDescription =
                        stringResource(
                            R.string.photopicker_selection_size_description,
                            localizedCurrentSelectionSize,
                        )
                    Text(
                        "$localizedCurrentSelectionSize",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier =
                            Modifier.semantics { contentDescription = selectionSizeDescription },
                    )
                }

                // Primary and Secondary actions [Right side]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalFeatureManager.current.composeLocation(
                        Location.SELECTION_BAR_SECONDARY_ACTION,
                        maxSlots = 1, // Only accept one additional action.
                        modifier = Modifier,
                    )
                    Spacer(Modifier.size(MEASUREMENT_BUTTONS_SPACER_SIZE))
                    FilledTonalButton(
                        onClick = {
                            // Log clicking the picker Add media button
                            scope.launch {
                                events.dispatch(
                                    Event.LogPhotopickerUIEvent(
                                        FeatureToken.SELECTION_BAR.token,
                                        configuration.sessionId,
                                        configuration.callingPackageUid ?: -1,
                                        Telemetry.UiEvent.PICKER_CLICK_ADD_BUTTON,
                                    )
                                )
                            }
                            // The selection bar should receive a click handler from its parent
                            // to handle the primary button click.
                            val clickAction = params as? LocationParams.WithClickAction
                            clickAction?.onClick()
                        },
                        colors =
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
                            ),
                    ) {
                        Text(stringResource(R.string.photopicker_done_button_label))
                    }
                }
            }
        }
    }
}
