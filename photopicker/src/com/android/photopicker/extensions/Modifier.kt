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

package com.android.photopicker.extensions

import android.os.Build
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * Draws circle with a solid [color] behind the content.
 *
 * @param color The color of the circle.
 * @param padding The padding to be applied externally to the circular shape. It determines the
 *   spacing between the edge of the circle and the content inside.
 * @param borderColor (optional) Color to draw a border around the edge of the circle. If
 *   Unspecified, a border will not be drawn.
 * @param borderWidth the width of the border
 * @return Combined [Modifier] that first draws the background circle and then centers the layout.
 */
fun Modifier.circleBackground(
    color: Color,
    padding: Dp,
    borderColor: Color = Color.Unspecified,
    borderWidth: Dp = 1.dp
): Modifier {
    val backgroundModifier = drawBehind {
        drawCircle(color, size.width / 2f, center = Offset(size.width / 2f, size.height / 2f))
        if (!borderColor.isUnspecified) {
            drawCircle(
                borderColor,
                size.width / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = borderWidth.roundToPx().toFloat())
            )
        }
    }

    val layoutModifier = layout { measurable, constraints ->
        // Adjust the constraints by the padding amount
        val adjustedConstraints = constraints.offset(-padding.roundToPx())

        // Measure the composable with the adjusted constraints
        val placeable = measurable.measure(adjustedConstraints)

        // Get the current max dimension to assign width=height
        val currentHeight = placeable.height
        val currentWidth = placeable.width
        val newDiameter = maxOf(currentHeight, currentWidth) + padding.roundToPx() * 2

        // Assign the dimension and the center position
        layout(newDiameter, newDiameter) {
            // Place the composable at the calculated position
            placeable.placeRelative(
                (newDiameter - currentWidth) / 2,
                (newDiameter - currentHeight) / 2
            )
        }
    }

    return this then backgroundModifier then layoutModifier
}

/**
 * Transfer necessary touch events occurred on Photos/Albums grid to host at runtime in Embedded
 * Photopicker
 *
 * @param state the state of Photos/albums grid. If state is null means Photos/Albums grid has not
 *   requested the custom modifier
 * @param isExpanded the updates on current status of embedded photopicker
 * @param host the instance of [SurfaceControlViewHost]
 * @return a [Modifier] to transfer the touch gestures at runtime in Embedded photopicker
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun Modifier.transferGridTouchesToHostInEmbedded(
    state: LazyGridState,
    isExpanded: State<Boolean>,
    host: SurfaceControlViewHost
): Modifier {
    return this then
        transferTouchesToSurfaceControlViewHost(
            state = state,
            isExpanded = isExpanded,
            host = host,
        )
}

/**
 * Transfer necessary touch events occurred outside of Photos/Albums grid to host on runtime in
 * Embedded Photopicker
 *
 * @param host the instance of [SurfaceControlViewHost]
 * @return a [Modifier] to transfer the touch gestures at runtime in Embedded photopicker
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun Modifier.transferTouchesToHostInEmbedded(host: SurfaceControlViewHost): Modifier {
    return this then
        transferTouchesToSurfaceControlViewHost(state = null, isExpanded = null, host = host)
}

/**
 * Transfer necessary touch events to host on runtime in Embedded Photopicker
 *
 * @param state the state of Photos/albums grid. If state is null means Photos/Albums grid has not
 *   requested the custom modifier
 * @param isExpanded the updates on current status of embedded photopicker
 * @param host the instance of [SurfaceControlViewHost]
 * @return a [Modifier] to transfer the touch gestures at runtime in Embedded photopicker
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun Modifier.transferTouchesToSurfaceControlViewHost(
    state: LazyGridState?,
    isExpanded: State<Boolean>?,
    host: SurfaceControlViewHost
): Modifier {

    /**
     * Initial y position when user touches the screen or when [PointerEventType.Press] is received
     */
    var initialY = 0F

    /**
     * Difference in Y position with respect to initialY as user starts scrolling on the screen, to
     * know the direction of the movement
     */
    var dy = 0F

    val pointerInputModifier =
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Suspend until next pointer event
                    val event: PointerEvent = awaitPointerEvent()
                    event.changes.forEach { change ->
                        if (state != null) {
                            when (event.type) {
                                PointerEventType.Press -> {
                                    // Set initial Y position when user touches the screen
                                    initialY = change.position.y
                                }
                                PointerEventType.Move -> {
                                    // Position difference with respect to initial position
                                    dy = change.position.y - initialY
                                }
                                PointerEventType.Release -> {
                                    // Resetting the position change for next touch event
                                    dy = 0F
                                }
                            }
                        }
                    }

                    // Todo(b/356790658) : Avoid recalculate these every time, just do it when
                    // argument changes
                    val isGridCollapsed = state != null && isExpanded != null && !isExpanded.value
                    val isGridExpanded = state != null && isExpanded != null && isExpanded.value

                    // Event is done being processed, make a decision about if this event should
                    // be transferred
                    val shouldTransferToHost =
                        when {

                            // Never transfer if the event type isn't move
                            event.type != PointerEventType.Move -> false

                            // Case for Not Grid attached modifiers
                            state == null -> true

                            // Case for grid attached when embedded is collapsed
                            isGridCollapsed && dy != 0F -> true

                            // Case for grid attached when embedded is expanded, and
                            // the lazy grid is at the top of its scroll container
                            isGridExpanded &&
                                (state.firstVisibleItemIndex == 0 &&
                                    state.firstVisibleItemScrollOffset == 0 &&
                                    dy > 0) -> true

                            // Otherwise don't transfer
                            else -> false
                        }

                    if (shouldTransferToHost) {
                        // TODO(b/356671436): Use V API when available
                        @Suppress("DEPRECATION") host.transferTouchGestureToHost()
                    }
                }
            }
        }
    return this then pointerInputModifier
}
