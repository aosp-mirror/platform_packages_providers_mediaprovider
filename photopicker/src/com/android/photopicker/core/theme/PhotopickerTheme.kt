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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.android.modules.utils.build.SdkLevel

/**
 * This composable generates all the theme related elements and creates the wrapping [MaterialTheme]
 * composable.
 *
 * Additionally, the PhotopickerTheme also creates a provider for [WindowSizeClass] allowing
 * composables downstream to react to WindowSize changes.
 */
@Composable
fun PhotopickerTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    intent: Intent?,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val accentColorHelper = AccentColorHelper(intent)

    val colorScheme =
        remember(isDarkTheme) {
            when {
                // When the accent color is available then the generic theme for the picker should
                // be the static baseline material theme. This will be used for any components that
                // are not highlighted with accent colors.
                (accentColorHelper.getAccentColor() != Color.Unspecified) -> {
                    if (isDarkTheme) {
                        darkColorScheme()
                    } else {
                        lightColorScheme()
                    }
                }
                else -> {
                    if (SdkLevel.isAtLeastS()) {
                        if (isDarkTheme) {
                            dynamicDarkColorScheme(context)
                        } else {
                            dynamicLightColorScheme(context)
                        }
                    } else {
                        null
                    }
                }
            }
        } ?: MaterialTheme.colorScheme

    // Calculate the current screen size
    val windowSizeClass: WindowSizeClass = calculateWindowSizeClass()

    MaterialTheme(colorScheme) {
        CompositionLocalProvider(
            LocalWindowSizeClass provides windowSizeClass,
            CustomAccentColorScheme provides AccentColorScheme(
                accentColorHelper = accentColorHelper
            ),
        ) {
            content()
        }
    }
}
