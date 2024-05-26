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

package com.android.photopicker.core.theme

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Holds parameters and utility methods for setting a custom picker accent color.
 *
 * Only valid input color codes with luminance greater than 0.6 will be set on major picker
 * components.
 *
 * This feature can only be used for photo picker opened in [MediaStore#ACTION_PICK_IMAGES] mode.
 */
class AccentColorHelper(
    intent: Intent?
) {
    private val DARK_TEXT_COLOR = "#000000"
    private val LIGHT_TEXT_COLOR = "#FFFFFF"

    private var accentColor = Color.Unspecified

    private var textColorForAccentComponents = Color.Unspecified

    init {
        intent?.let {
            val extras: Bundle? = intent.extras
            val inputColor = extras?.getLong(
                MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR,
                -1
            ) ?: -1

            if (inputColor > -1) { // if the accent color is present in extras.
                if (intent.action != MediaStore.ACTION_PICK_IMAGES) {
                    throw IllegalArgumentException(
                        "Accent color customisation is not " + "available for " + intent.action +
                                " action.",
                    )
                }
                accentColor = checkColorValidityAndGetColor(inputColor)

                // pickerAccentColor being equal to Color.Unspecified would mean that the color
                // passed as an input does not satisfy the validity tests for being an accent
                // color.
                if (accentColor != Color.Unspecified) {
                    textColorForAccentComponents = Color(
                        android.graphics.Color.parseColor(
                            if (isAccentColorBright(accentColor.luminance()))
                                DARK_TEXT_COLOR
                            else
                                LIGHT_TEXT_COLOR,
                        ),
                    )
                } else {
                    throw IllegalArgumentException("Color not valid for accent color")
                }
            }
        }
    }


    /**
     * Checks input color validity and returns the color without alpha component if valid, or -1.
     */
    private fun checkColorValidityAndGetColor(color: Long): Color {
        // Gives us the formatted color string where the mask gives us the color in the RRGGBB
        // format and the %06X gives zero-padded hex (6 characters long)
        val hexColor = String.format("#%06X", (0xFFFFFF.toLong() and color))
        val inputColor = android.graphics.Color.parseColor(hexColor)
        if (!isColorFeasibleForBothBackgrounds(Color(inputColor).luminance())) {
            return Color.Unspecified
        }
        return Color(inputColor)
    }


    /**
     * Returns true if the input color is within the range of [0.05 to 0.9] so that the color works
     * both on light and dark background. Range has been set by testing with different colors.
     */
    private fun isColorFeasibleForBothBackgrounds(luminance: Float): Boolean {
        return luminance >= 0.05 && luminance < 0.9
    }

    /**
     * Indicates if the accent color is bright (luminance >= 0.6).
     */
    private fun isAccentColorBright(accentColorLuminance: Float): Boolean =
        accentColorLuminance >= 0.6

    /**
     * Returns the accent color which has been passed as an input in the picker intent.
     *
     * Default value for this is [Color.Unspecified]
     */
    fun getAccentColor(): Color {
        return accentColor
    }

    /**
     * Returns the appropriate text color for components using the accent color as the background
     * which has been passed as an input in the picker intent.
     *
     * Default value for this is [Color.Unspecified]
     */
    fun getTextColorForAccentComponents(): Color {
        return textColorForAccentComponents
    }
}
