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

import android.util.Log
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
 */
class EmbeddedStateManager {
    companion object {
        const val TAG: String = "PhotopickerEmbeddedStateManager"
    }

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

    /** Assembles an initial state upon embedded photopicker session launch. */
    private fun generateInitialEmbeddedState(): EmbeddedState {
        val initialEmbeddedState = EmbeddedState()
        Log.d(TAG, "Initial embedded state: $initialEmbeddedState")
        return initialEmbeddedState
    }

    /** Sets the current expanded or collapsed state of the embedded photopicker. */
    fun setIsExpanded(isExpanded: Boolean) {
        Log.d(TAG, "Expanded state updated to $isExpanded")
        _state.update { it.copy(isExpanded = isExpanded) }
    }
}
