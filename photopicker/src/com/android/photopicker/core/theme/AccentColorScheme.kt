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

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified

/** CompositionLocal used to pass [AccentColorScheme] down the tree. */
val CustomAccentColorScheme =
    staticCompositionLocalOf<AccentColorScheme> {
        throw IllegalStateException(
            "No CustomAccentColorScheme configured."
        )
    }

/**
 * The custom color scheme to represent the accent color input in the [ACTION_PICK_IMAGES] intent
 * used for key UI elements in photo picker and also the related text colors used in the modified
 * elements.
 */
class AccentColorScheme(accentColorHelper: AccentColorHelper) {
    private val accentColor = accentColorHelper.getAccentColor()
    private val textColorForAccentComponents = accentColorHelper.getTextColorForAccentComponents()

    /**
     * Returns the accent color which has been passed as an input in the picker intent.
     *
     * If the accent color is not present or is invalid the this value will be [Color.Unspecified]
     * by default.
     */
    fun getAccentColorIfDefinedOrElse(fallbackColor: Color): Color {
        return when (accentColor.isUnspecified) {
            true -> fallbackColor
            false -> accentColor
        }
    }

    /**
     * Returns the appropriate text color for components using the accent color as the background
     * which has been passed as an input in the picker intent.
     *
     * This is helpful in maintaining the readability of the component.
     *
     * If the accent color is not present or is invalid the this value will be [Color.Unspecified]
     * be default.
     */
    fun getTextColorForAccentComponentsIfDefinedOrElse(fallbackColor: Color): Color {
        return when (textColorForAccentComponents.isUnspecified) {
            true -> fallbackColor
            false -> textColorForAccentComponents
        }
    }
}
