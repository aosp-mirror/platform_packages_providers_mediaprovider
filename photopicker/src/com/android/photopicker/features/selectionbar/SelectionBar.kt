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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureToken.SELECTION_BAR
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.selection.LocalSelection
import java.text.NumberFormat
import kotlinx.coroutines.launch

/* The size of spacers between elements on the bar */
private val MEASUREMENT_SPACER_SIZE = 6.dp

/* The amount of padding between elements and the edge of the selection bar */
private val MEASUREMENT_BAR_PADDING = 12.dp

/**
 * The Photopicker selection bar that shows the actions related to the current selection of Media.
 * This composable does not provide a secondary action button directly, but instead exposes
 * [Location.SELECTION_BAR_SECONDARY_ACTION] for another feature to provide a secondary action that
 * is relevant to the selection bar. If not are provided, the space will be left empty.
 */
@Composable
fun SelectionBar(modifier: Modifier = Modifier) {

    // Collect selection to ensure this is recomposed when the selection is updated.
    val currentSelection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val visible = currentSelection.isNotEmpty()
    val events = LocalEvents.current
    val scope = rememberCoroutineScope()
    val numberFormatter = remember { NumberFormat.getInstance() }

    // The entire selection bar is hidden if the selection is empty, and
    // animates between visible states.
    AnimatedVisibility(
        // Pass through the modifier that is received for positioning offsets.
        modifier = modifier,
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            // TODO(b/323830032): Check which color goes here.
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MEASUREMENT_BAR_PADDING),
            ) {
                LocalFeatureManager.current.composeLocation(
                    Location.SELECTION_BAR_SECONDARY_ACTION,
                    maxSlots = 1, // Only accept one additional action.
                    modifier = Modifier
                )
                Spacer(modifier = Modifier.padding(MEASUREMENT_SPACER_SIZE))
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            events.dispatch(Event.MediaSelectionConfirmed(SELECTION_BAR.token))
                        }
                    },
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                ) {
                    Text(
                        stringResource(
                            // Label: Add (N)
                            R.string.photopicker_add_button_label,
                            numberFormatter.format(currentSelection.size),
                        )
                    )
                }
            }
        }
    }
}
