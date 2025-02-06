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

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.platform.LocalContext
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.theme.typography.TypeScaleTokens
import com.android.photopicker.core.theme.typography.TypefaceNames
import com.android.photopicker.core.theme.typography.TypefaceTokens
import com.android.photopicker.core.theme.typography.TypographyTokens
import com.android.photopicker.core.theme.typography.photopickerTypography

/**
 * This composable generates all the theme related elements and creates the wrapping [MaterialTheme]
 * composable.
 *
 * Additionally, the PhotopickerTheme also creates a provider for [WindowSizeClass] allowing
 * composables downstream to react to WindowSize changes.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun PhotopickerTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    config: PhotopickerConfiguration,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val accentColorHelper = AccentColorHelper(config.accentColor ?: -1)

    // If a custom accent color hasn't been set, use a dynamic theme for colors
    val accentColorIsNotSpecified = remember { accentColorHelper.getAccentColor().isUnspecified }

    // Assemble Light & Dark themes, both color sets are needed to generate the [FixedAccentColors].
    val darkTheme = remember {
        when (accentColorIsNotSpecified) {
            true ->
                if (SdkLevel.isAtLeastS()) dynamicDarkColorScheme(context) else darkColorScheme()
            false -> darkColorScheme()
        }
    }

    val lightTheme = remember {
        when (accentColorIsNotSpecified) {
            true ->
                if (SdkLevel.isAtLeastS()) dynamicLightColorScheme(context) else lightColorScheme()
            false -> lightColorScheme()
        }
    }

    // Choose which colorScheme to use based on if the device is in dark mode or not.
    val colorScheme =
        remember(isDarkTheme) {
            when (isDarkTheme) {
                true ->
                    if (accentColorHelper.getAccentColor().isUnspecified) {
                        darkTheme
                    } else {
                        // When an accent color has been specified, set primary and onPrimary
                        // in the theme to use the accent color.
                        darkTheme.copy(
                            primary = accentColorHelper.getAccentColor(),
                            onPrimary = accentColorHelper.getTextColorForAccentComponents(),
                        )
                    }
                false ->
                    if (accentColorHelper.getAccentColor().isUnspecified) {
                        lightTheme
                    } else {
                        lightTheme.copy(
                            // When an accent color has been specified, set primary and onPrimary
                            // in the theme to use the accent color.
                            primary = accentColorHelper.getAccentColor(),
                            onPrimary = accentColorHelper.getTextColorForAccentComponents(),
                        )
                    }
            }
        }
    val fixedAccentColors =
        FixedAccentColors.build(lightColors = lightTheme, darkColors = darkTheme)

    // Generate the typography for the theme based on context.
    val typefaceNames = remember(context) { TypefaceNames.get(context) }
    val typography =
        remember(typefaceNames) {
            photopickerTypography(TypographyTokens(TypeScaleTokens(TypefaceTokens(typefaceNames))))
        }

    // Calculate the current screen size
    val windowSizeClass: WindowSizeClass = calculateWindowSizeClass()

    if (
        config.flags.EXPRESSIVE_THEME_ENABLED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
    ) {
        MaterialExpressiveTheme(colorScheme = colorScheme, typography = typography) {
            CompositionLocalProvider(
                LocalWindowSizeClass provides windowSizeClass,
                LocalFixedAccentColors provides fixedAccentColors,
                CustomAccentColorScheme provides
                    AccentColorScheme(accentColorHelper = accentColorHelper),
            ) {
                content()
            }
        }
    } else {
        MaterialTheme(colorScheme = colorScheme, typography = typography) {
            CompositionLocalProvider(
                LocalWindowSizeClass provides windowSizeClass,
                LocalFixedAccentColors provides fixedAccentColors,
                CustomAccentColorScheme provides
                    AccentColorScheme(accentColorHelper = accentColorHelper),
            ) {
                content()
            }
        }
    }
}
