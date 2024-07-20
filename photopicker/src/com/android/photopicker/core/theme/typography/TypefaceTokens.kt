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

@file:OptIn(ExperimentalTextApi::class)

package com.android.photopicker.core.theme.typography

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Holds a Brand and Plain font family definition for Photopicker's compose typography */
internal class TypefaceTokens(typefaceNames: TypefaceNames) {
    companion object {
        val WeightMedium = FontWeight.Medium
        val WeightRegular = FontWeight.Normal
    }

    private val brandFont = DeviceFontFamilyName(typefaceNames.brand)
    private val plainFont = DeviceFontFamilyName(typefaceNames.plain)

    val brand =
        FontFamily(
            Font(brandFont, weight = WeightMedium),
            Font(brandFont, weight = WeightRegular),
        )
    val plain =
        FontFamily(
            Font(plainFont, weight = WeightMedium),
            Font(plainFont, weight = WeightRegular),
        )
}

/**
 * Resolves the correct typeface name to use for Photopicker.
 *
 * The typeface used is defined in frameworks/base/core/res/values/config.xml to use the config set
 * by the platform & oem so that Photopicker's typefaces match what the system's UI is using.
 *
 * If nothing is set in the platform, it will default to "sans-serif".
 */
internal data class TypefaceNames
private constructor(
    val brand: String,
    val plain: String,
) {
    /** Maps a config property to a TypefaceTokens */
    private enum class Config(val configName: String, val default: String) {
        Brand("config_headlineFontFamily", "sans-serif"),
        Plain("config_bodyFontFamily", "sans-serif"),
    }

    companion object {
        fun get(context: Context): TypefaceNames {
            return TypefaceNames(
                brand = getTypefaceName(context, Config.Brand),
                plain = getTypefaceName(context, Config.Plain),
            )
        }

        private fun getTypefaceName(context: Context, config: Config): String {
            val name =
                context
                    .getString(
                        context.resources.getIdentifier(config.configName, "string", "android")
                    )
                    .takeIf { it.isNotEmpty() } ?: config.default
            return name
        }
    }
}
