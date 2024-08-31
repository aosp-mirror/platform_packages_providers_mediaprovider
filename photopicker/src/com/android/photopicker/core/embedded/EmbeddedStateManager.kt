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

import android.content.res.Configuration
import android.util.Log
import android.view.SurfaceControlViewHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * This class is responsible for providing the current state for the running session of the embedded
 * photopicker
 *
 * See [EmbeddedState] for details about all the various pieces that make up the session state.
 *
 * Provides a long-living [StateFlow] that emits the currently known state.
 *
 * @param host the Instance of [SurfaceControlViewHost] for the current session
 */
class EmbeddedStateManager(
    host: SurfaceControlViewHost? = null,
    private val themeNightMode: Int = Configuration.UI_MODE_NIGHT_UNDEFINED,
) {
    companion object {
        const val TAG: String = "PhotopickerEmbeddedStateManager"
    }

    private val _host = host

    /*
     * Internal [EmbeddedState] flow. When the embedded state changes, this is what should
     * be updated to ensure all listeners are notified.
     */
    private val _state: MutableStateFlow<EmbeddedState> =
        MutableStateFlow(generateInitialEmbeddedState())

    /**
     * Exposes the current state of the embedded session of the photopicker as a ReadOnly StateFlow.
     */
    val state: StateFlow<EmbeddedState> = _state

    private var _recomposeToggle = state.value.recomposeToggle

    /** Assembles an initial state upon embedded photopicker session launch. */
    private fun generateInitialEmbeddedState(): EmbeddedState {
        val initialEmbeddedState =
            when (themeNightMode) {
                Configuration.UI_MODE_NIGHT_YES -> EmbeddedState(isDarkTheme = true, host = _host)
                Configuration.UI_MODE_NIGHT_NO -> EmbeddedState(isDarkTheme = false, host = _host)
                else -> EmbeddedState(host = _host)
            }
        Log.d(TAG, "Initial embedded state: $initialEmbeddedState")
        return initialEmbeddedState
    }

    /**
     * Updates the expanded state of the embedded photopicker.
     *
     * @param isExpanded true if the photopicker is expanded (full-screen view), false if it is
     *   collapsed (half-screen view).
     */
    fun setIsExpanded(isExpanded: Boolean) {
        Log.d(TAG, "Expanded state updated to $isExpanded")
        _state.update { it.copy(isExpanded = isExpanded) }
    }

    /**
     * Sets the dark theme preference of the embedded photopicker
     *
     * @param isDarkTheme true to apply a dark theme, false for a light theme.
     */
    fun setIsDarkTheme(isDarkTheme: Boolean) {
        Log.d(TAG, "Dark theme state updated to $isDarkTheme")
        _state.update { it.copy(isDarkTheme = isDarkTheme) }
    }

    /**
     * Updates the [_recomposeToggle] causing the photopicker to recompose its UI, to respond to
     * change in config.
     */
    fun triggerRecompose() {
        _recomposeToggle = !_recomposeToggle
        Log.d(TAG, "Recompose toggle updated to $_recomposeToggle")
        _state.update { it.copy(recomposeToggle = _recomposeToggle) }
    }
}
