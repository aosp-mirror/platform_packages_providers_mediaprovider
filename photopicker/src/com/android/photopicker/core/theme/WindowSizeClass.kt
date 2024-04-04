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

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.WindowMetricsCalculator
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration

/**
 * Provider for the [WindowSizeClass] inside of composables. A [staticCompositionLocalOf] is used
 * here, due to the nature of WindowSizes; if the WindowSize is changing, recomposition is
 * unavoidable so there is no sense in keeping track of references that use this value.
 */
val LocalWindowSizeClass =
    staticCompositionLocalOf<WindowSizeClass> {
        throw IllegalStateException(
            "No WindowSizeClass configured. Make sure to use LocalWindowSizeClass in a Composable" +
                " surrounded by a PhotopickerTheme {}."
        )
    }

/**
 * Helper function which calculates a WindowSizeClass incorporating the window rect and
 * [LocalDensity].
 *
 * Note: This method observes [LocalPhotopickerConfiguration] and requires that to be provided
 * higher in the tree.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun calculateWindowSizeClass(): WindowSizeClass {

    // Observe PhotopickerConfiguration changes and recalculate the size class on each change.
    LocalPhotopickerConfiguration.current

    val context = LocalContext.current
    val density = LocalDensity.current
    val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
    val size = with(density) { metrics.bounds.toComposeRect().size.toDpSize() }
    return WindowSizeClass.calculateFromSize(size)
}
