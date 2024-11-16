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

package com.android.photopicker.util

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.absoluteValue
import kotlin.math.sign

/** A utility class to handle calculating pointer touchSlop for the provided [Orientation]. */
class TouchSlopDetector(val orientation: Orientation? = null) {

    fun Offset.mainAxis() = if (orientation == Orientation.Horizontal) x else y

    fun Offset.crossAxis() = if (orientation == Orientation.Horizontal) y else x

    /** The accumulation of drag deltas in this detector. */
    private var totalPositionChange: Offset = Offset.Zero

    /**
     * Adds [dragEvent] to this detector. If the accumulated position changes crosses the touch slop
     * provided by [touchSlop], this method will return the post slop offset, that is the total
     * accumulated delta change minus the touch slop value, otherwise this will return
     * [Offset.Unspecified].
     */
    fun addPointerInputChange(dragEvent: PointerInputChange, touchSlop: Float): Offset {
        val currentPosition = dragEvent.position
        val previousPosition = dragEvent.previousPosition
        val positionChange = currentPosition - previousPosition
        totalPositionChange += positionChange

        val inDirection =
            if (orientation == null) {
                totalPositionChange.getDistance()
            } else {
                totalPositionChange.mainAxis().absoluteValue
            }

        val hasCrossedSlop = inDirection >= touchSlop

        return if (hasCrossedSlop) {
            calculatePostSlopOffset(touchSlop)
        } else {
            Offset.Unspecified
        }
    }

    /** Resets the accumulator associated with this detector. */
    fun reset() {
        totalPositionChange = Offset.Zero
    }

    /**
     * Calculates the offset beyond the [touchSlop] for events passed to this detector.
     *
     * NOTE: Before calling this there should be a check if the slop has been crossed, if the slop
     * has not been crossed this value will be inaccurate.
     */
    private fun calculatePostSlopOffset(touchSlop: Float): Offset {
        return if (orientation == null) {
            val touchSlopOffset =
                totalPositionChange / totalPositionChange.getDistance() * touchSlop
            // update postSlopOffset
            totalPositionChange - touchSlopOffset
        } else {
            val finalMainAxisChange =
                totalPositionChange.mainAxis() - (sign(totalPositionChange.mainAxis()) * touchSlop)
            val finalCrossAxisChange = totalPositionChange.crossAxis()
            if (orientation == Orientation.Horizontal) {
                Offset(finalMainAxisChange, finalCrossAxisChange)
            } else {
                Offset(finalCrossAxisChange, finalMainAxisChange)
            }
        }
    }
}
