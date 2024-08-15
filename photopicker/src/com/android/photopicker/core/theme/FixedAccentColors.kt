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

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Provider for the compose tree */
val LocalFixedAccentColors =
    compositionLocalOf<FixedAccentColors> { error("No LocalFixedAccentColors provided") }

/**
 * This fills a gap in the compose Material3 implementation colors roles spec where the fixed accent
 * color roles are not specified in the primary generated color scheme.
 *
 * @See https://m3.material.io/styles/color/roles
 *
 * TODO(b/348616038): Remove this implementation when the roles are present in the MaterialTheme
 */
data class FixedAccentColors
private constructor(
    val primaryFixed: Color,
    val onPrimaryFixed: Color,
    val secondaryFixed: Color,
    val onSecondaryFixed: Color,
    val tertiaryFixed: Color,
    val onTertiaryFixed: Color,
    val primaryFixedDim: Color,
    val secondaryFixedDim: Color,
    val tertiaryFixedDim: Color,
) {

    companion object {
        /**
         * Builds a [FixedAccentColors] by mapping the fixed colors to their corresponding color
         * tokens
         */
        fun build(lightColors: ColorScheme, darkColors: ColorScheme): FixedAccentColors {

            return FixedAccentColors(
                primaryFixed = lightColors.primaryContainer,
                onPrimaryFixed = lightColors.onPrimaryContainer,
                secondaryFixed = lightColors.secondaryContainer,
                onSecondaryFixed = lightColors.onSecondaryContainer,
                tertiaryFixed = lightColors.tertiaryContainer,
                onTertiaryFixed = lightColors.onTertiaryContainer,
                primaryFixedDim = darkColors.primary,
                secondaryFixedDim = darkColors.secondary,
                tertiaryFixedDim = darkColors.tertiary
            )
        }
    }
}
