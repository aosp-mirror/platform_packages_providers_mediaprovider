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

package com.android.photopicker.core.embedded

import android.view.SurfaceControlViewHost
import androidx.appcompat.app.AppCompatDelegate

/**
 * Data object that represents the state of Photopicker and hold the instance of
 * [SurfaceControlViewHost] in embedded runtime.
 *
 * @param host the Instance of [SurfaceControlViewHost]
 * @property isExpanded true if photopicker is expanded/full-view, false if collapsed/half-view.
 */
data class EmbeddedState(
    val isExpanded: Boolean = false,
    val isDarkTheme: Boolean =
        AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES,
    val recomposeToggle: Boolean = false,
    val host: SurfaceControlViewHost? = null,
)
