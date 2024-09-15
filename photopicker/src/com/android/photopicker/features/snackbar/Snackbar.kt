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

package com.android.photopicker.features.snackbar

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun Snackbar(modifier: Modifier) {

    /** SnackbarHost api for launching Snackbars */
    val snackbarHostState = remember { SnackbarHostState() }

    val snackbarEvents = LocalEvents.current.flow
    /*
     * Snackbars need to be opened in a separate scope from the launched effect to avoid blocking
     * the collection during suspend
     */
    val scope = rememberCoroutineScope()

    LaunchedEffect(snackbarHostState) {
        snackbarEvents.collect {
            when (it) {
                is Event.ShowSnackbarMessage -> {
                    // Only enqueue a new snackbar if its message does not match the current snackbar
                    // to ensure that duplicate events are suppressed.
                    if (snackbarHostState.currentSnackbarData?.visuals?.message != it.message) {
                        scope.launch { snackbarHostState.showSnackbar(it.message) }
                    }
                }
                else -> {}
            }
        }
    }

    SnackbarHost(snackbarHostState, modifier = modifier)
}
