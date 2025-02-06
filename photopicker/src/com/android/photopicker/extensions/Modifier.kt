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
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
 * Transfer necessary touch events on scrollable objects like a grid or list to host at runtime in
 * Embedded Photopicker
 *
 * @param state the state of a scrollable object.
 * @param isExpanded the updates on current status of embedded photopicker
 * @param host the instance of [SurfaceControlViewHost]
 * @return a [Modifier] to transfer the touch gestures at runtime in Embedded photopicker
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun Modifier.transferScrollableTouchesToHostInEmbedded(
    state: ScrollableState,
    isExpanded: State<Boolean>,
    host: SurfaceControlViewHost,
): Modifier {
    return this then
        transferTouchesToSurfaceControlViewHost(state = state, isExpanded = isExpanded, host = host)
}

/**
 * Transfer necessary touch events occurred outside of scrollable objects to host on runtime in
 * Embedded Photopicker
 *
 * @param host the instance of [SurfaceControlViewHost]
 * @param pass the PointerEventPass where the gesture needs to be handled. The default
 *   [PointerEventPass] is set as [PointerEventPass.Initial].
 * @return a [Modifier] to transfer the touch gestures at runtime in Embedded photopicker
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun Modifier.transferTouchesToHostInEmbedded(
    host: SurfaceControlViewHost,
    pass: PointerEventPass = PointerEventPass.Initial,
): Modifier {
    return this then
        transferTouchesToSurfaceControlViewHost(
            state = null,
            isExpanded = null,
            host = host,
            pass = pass,
        )
}

/**
 * Transfer necessary touch events to host on runtime in Embedded Photopicker.
 *
 * This custom modifier has been explicitly applied to the box that wraps [PhotopickerMain]
 * composable and the [mediaGrid] composable that backs all media and group grids in Photopicker
 * like [PhotoGrid], [SearchResultsGrid] etc.
 *
 * @param state the state of the scrollable object like lazy grid or lazy list. If state is null
 *   means a scrollable object has not requested the custom modifier
 * @param isExpanded the updates on current status of embedded photopicker
 * @param host the instance of [SurfaceControlViewHost]
 * @param pass the PointerEventPass where the gesture needs to be handled.
 *
 * PointerInputChanges traverse though the UI tree in the following passes, in the same order:
 * 1. Initial: Down the tree from ancestor to descendant. Any touch gestures that need to be
 *    transferred to the host in the parent, before a child element consumes it, should be handled
 *    in this pass.
 * 2. Main: Up the tree from descendant to ancestor. This is the primary path where descendants will
 *    interact with PointerInputChanges before parents.
 * 3. Final: Down the tree from ancestor to descendant. Handling any unconsumed touch gestures after
 *    the Initial and Main pass should happen here.
 *
 * The default [PointerEventPass] is set as [PointerEventPass.Initial].
 *
 * @return a [Modifier] to transfer the touch gestures at runtime in Embedded photopicker
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun Modifier.transferTouchesToSurfaceControlViewHost(
    state: ScrollableState?,
    isExpanded: State<Boolean>?,
    host: SurfaceControlViewHost,
    pass: PointerEventPass = PointerEventPass.Initial,
): Modifier {

    val pointerInputModifier =
        pointerInput(Unit) {
            awaitEachGesture {
                val touchSlop = viewConfiguration.touchSlop
                val touchSlopDetector = TouchSlopDetector(Orientation.Vertical)

                // Wait for the first pointer touch.
                val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                val pointerId = down.id

                // Now that a down exists set up a loop which processes the touch input and
                // evaluates if it should be sent to the host.
                do {
                    // Check if the initial pointer input change was part of a drag gesture.
                    val event = awaitPointerEvent(pass = pass)
                    val dragEvent = event.changes.firstOrNull { it.id == pointerId }

                    // If the dragEvent cannot be found for the pointer, or is consumed elsewhere
                    // cancel this gesture.
                    val canceled = dragEvent?.isConsumed ?: true

                    // If the event is not a dragEvent or it was already consumed,
                    // stop handling the event.
                    if (canceled) break

                    val postSlopOffset =
                        touchSlopDetector.addPointerInputChange(dragEvent, touchSlop)

                    // Once pastTouchSlop check to see if the touch meets the conditions to be
                    // transferred to the host.
                    if (postSlopOffset.isSpecified) {

                        val isGridCollapsed =
                            state != null && isExpanded != null && !isExpanded.value
                        val isGridExpanded = state != null && isExpanded != null && isExpanded.value

                        val shouldTransferToHost =
                            when {

                                // When this isn't attached to a scrollable object, all vertical
                                // gestures should be transferred.
                                state == null -> true

                                // If the scrollable object is collapsed and vertical touchSlop has
                                // been passed, touches should be transferred.
                                isGridCollapsed -> true

                                // If the scrollable object isExpanded, scrolled to the first item
                                // and the gesture direction was up (to collapse the Photopicker)
                                isGridExpanded &&
                                    (!state.canScrollBackward && postSlopOffset.y > 0F) -> true

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
