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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A helper class for localization tasks
 *
 * @property locale The locale to use for localization. Defaults to the device's default locale.
 */
data class LocalizationHelper(private val locale: Locale = Locale.getDefault()) {

    private val numberFormat = NumberFormat.getInstance(locale)

    /**
     * Returns a localized string representation of the given count.
     *
     * @param count The count to format.
     * @return The localized string representation of the count.
     */
    fun getLocalizedCount(count: Int): String {
        return numberFormat.format(count)
    }

    /**
     * Returns a localized date and time formatter.
     *
     * @param dateStyle The style of the date format (e.g., DateFormat.MEDIUM).
     * @param timeStyle The style of the time format (e.g., DateFormat.SHORT).
     * @return A DateFormat instance with the specified styles and locale.
     */
    fun getLocalizedDateTimeFormatter(dateStyle: Int, timeStyle: Int): DateFormat {
        return SimpleDateFormat.getDateTimeInstance(dateStyle, timeStyle, locale)
    }
}

/**
 * Provides a [LocalizationHelper] instance that is remembered and updated when the locale changes.
 */
@Composable
fun rememberLocalizationHelper(): LocalizationHelper {
    val currentLocale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
    return remember(currentLocale) { LocalizationHelper(locale = currentLocale) }
}
