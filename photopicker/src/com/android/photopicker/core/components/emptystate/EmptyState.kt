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

package com.android.photopicker.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.photopicker.core.theme.CustomAccentColorScheme

private val MEASUREMENT_ICON_CONTAINER_SIZE = 56.dp
private val MEASUREMENT_ICON_SIZE = 24.dp
private val MEASUREMENT_ICON_TITLE_SPACER = 16.dp
private val MEASUREMENT_TITLE_BODY_SPACER = 8.dp
private val MEASUREMENT_EMPTY_STATE_HORIZONTAL_MARGIN = 16.dp
private val MEASUREMENT_MAX_WIDTH = 320.dp

/**
 * Displays a message that indicates the current screen has no content to display.
 *
 * @param Modifier that will be applied to the root element.
 * @param icon Icon that will be prominently displayed to fill the empty space.
 * @param title The title that will be displayed below the icon.
 * @param body The body message that will be displayed below the title.
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    body: String,
) {
    Column(
        // Consume the incoming modifier for positioning.
        modifier = modifier.padding(horizontal = MEASUREMENT_EMPTY_STATE_HORIZONTAL_MARGIN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(MEASUREMENT_ICON_CONTAINER_SIZE)
        ) {
            Box {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(MEASUREMENT_ICON_SIZE),
                    tint =
                        CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                            /* fallback */ MaterialTheme.colorScheme.primary
                        ),
                )
            }
        }
        Column(
            modifier = Modifier.widthIn(max = MEASUREMENT_MAX_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.size(MEASUREMENT_ICON_TITLE_SPACER))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(MEASUREMENT_TITLE_BODY_SPACER))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
