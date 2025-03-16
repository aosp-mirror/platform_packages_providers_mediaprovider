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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.android.photopicker.util.TouchSlopDetector

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
    borderWidth: Dp = 1.dp,
): Modifier {
    val backgroundModifier = drawBehind {
        drawCircle(color, size.width / 2f, center = Offset(size.width / 2f, size.height / 2f))
        if (!borderColor.isUnspecified) {
            drawCircle(
                borderColor,
                size.width / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = borderWidth.roundToPx().toFloat()),
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
                (newDiameter - currentHeight) / 2,
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
    host: SurfaceControlViewHost,
): Modifier {
    return this then
        transferTouchesToSurfaceControlViewHost(state = state, isExpanded = isExpanded, host = host)
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
 * Transfer necessary touch events to host on runtime in Embedded Photopicker.
 *
 * This custom modifier has been explicitly applied to four different components - the navigation
 * bar, Album media grid's empty state, Photos grid's empty state and media grid.
 *
 * Todo(b/368021407): Touches should also be transferred into the empty spaces left within the
 * embedded
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
    host: SurfaceControlViewHost,
): Modifier {

    val pointerInputModifier =
        pointerInput(Unit) {
            awaitEachGesture {
                val touchSlop = viewConfiguration.touchSlop
                val touchSlopDetector = TouchSlopDetector(Orientation.Vertical)

                // This needs to run in the [PointerEventPass.Initial] to ensure that the event
                // can be handled in the parent, rather than the child.
                //
                // This touch handler is a parent of the touch handler the grid is using to monitor
                // clicks & scroll, so these touches are processed in the first pass, and if they
                // aren't transferred to the host they will be processed by the grid in the
                // [PointerEventPass.Main]
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val pointerId = down.id

                // Now that a down exists set up a loop which processes the touch input and
                // evaluates if it should be sent to the host.
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val dragEvent = event.changes.firstOrNull { it.id == pointerId }

                    // If the dragEvent cannot be found for the pointer, or is consumed elsewhere
                    // cancel this gesture.
                    val canceled = dragEvent?.isConsumed ?: true

                    val postSlopOffset =
                        if (dragEvent != null)
                            touchSlopDetector.addPointerInputChange(dragEvent, touchSlop)
                        else Offset.Unspecified

                    // Once pastTouchSlop check to see if the touch meets the conditions to be
                    // transferred to the host.
                    if (postSlopOffset.isSpecified) {

                        val isGridCollapsed =
                            state != null && isExpanded != null && !isExpanded.value
                        val isGridExpanded = state != null && isExpanded != null && isExpanded.value

                        val shouldTransferToHost =
                            when {

                                // When this isn't attached to a grid, all vertical gestures should
                                // be transferred.
                                state == null -> true

                                // If the grid is collapsed and vertical touchSlop has been passed,
                                // touches should be transferred.
                                isGridCollapsed -> true

                                // If the grid isExpanded, scrolled to the first item and the
                                // gesture
                                // direction was up (to collapse the Photopicker)
                                isGridExpanded &&
                                    (state.firstVisibleItemIndex == 0 &&
                                        state.firstVisibleItemScrollOffset == 0 &&
                                        postSlopOffset.y > 0F) -> true

                                // Otherwise don't transfer
                                else -> false
                            }

                        if (shouldTransferToHost) {
                            // TODO(b/356671436): Use V API when available
                            @Suppress("DEPRECATION") host.transferTouchGestureToHost()
                        }
                    }
                } while (
                    // Continue monitoring this event if it hasn't been consumed elsewhere.
                    !canceled &&

                        // Only monitor the event if it is a finger touching the screen or mouse
                        // button is being pressed.
                        event.changes.any { it.pressed }
                )
            }
        }

    return this then pointerInputModifier
}
