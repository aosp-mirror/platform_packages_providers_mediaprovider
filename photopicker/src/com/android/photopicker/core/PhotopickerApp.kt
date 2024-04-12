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

package com.android.photopicker.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.compose.rememberNavController
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerNavGraph

/**
 * This is an entrypoint of the Photopicker Compose UI. This is called from the MainActivity and is
 * the top-most [@Composable] in the activity application. This should not be called except inside
 * an Activity's [setContent] block.
 *
 * @param onDismissRequest handler for when the BottomSheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotopickerAppWithBottomSheet(onDismissRequest: () -> Unit) {

    // Initialize and remember the NavController. This needs to be provided before the call to
    // the NavigationGraph, so this is done at the top.
    val navController = rememberNavController()
    val state = rememberModalBottomSheetState()

    // Provide the NavController to the rest of the Compose stack.
    CompositionLocalProvider(LocalNavController provides navController) {
        Column(
            modifier =
                // Apply WindowInsets to this wrapping column to prevent the Bottom Sheet
                // from drawing over the system bars.
                Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Vertical)
                )
        ) {
            ModalBottomSheet(
                sheetState = state,
                onDismissRequest = onDismissRequest,
                scrimColor = Color.Transparent,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                windowInsets = WindowInsets.systemBars,
            ) {
                Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.BottomEnd) {
                    PhotopickerMain()
                    LocalFeatureManager.current.composeLocation(
                        Location.SELECTION_BAR,
                        maxSlots = 1,
                        modifier =
                            // SELECTION_BAR needs to be drawn over the UI inside of the BottomSheet
                            // A negative y offset will move it from the bottom of the content
                            // to the bottom of the onscreen BottomSheet.
                            Modifier.offset {
                                IntOffset(x = 0, y = -state.requireOffset().toInt())
                            },
                    )
                }
            }
        }
    }
}

/**
 * This is an entrypoint of the Photopicker Compose UI. This is called from a hosting View and is
 * the top-most [@Composable] in the view based application. This should not be called by any
 * Activity code, and should only be called inside of the ComposeView [setContent] block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotopickerApp() {

    // Initialize and remember the NavController. This needs to be provided before the call to
    // the NavigationGraph, so this is done at the top.
    val navController = rememberNavController()

    // Provide the NavController to the rest of the Compose stack.
    CompositionLocalProvider(LocalNavController provides navController) { PhotopickerMain() }
}

/**
 * This is the shared entrypoint for the Photopicker compose-UI. Composables above this function
 * must provide the required dependencies to the compose UI before calling this entrypoint.
 *
 * It is presumed after this composable the compose UI can either be running inside of a wrapped
 * View or an Activity lifecycle.
 *
 * By this entrypoint, the expected CompositionLocals should already exist:
 * - LocalFeatureManager
 * - LocalNavController
 * - LocalPhotopickerConfiguration
 * - LocalSelection
 * - PhotopickerTheme
 */
@Composable
fun PhotopickerMain() {

    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            // The navigation bar is drawn above the navigation graph
            LocalFeatureManager.current.composeLocation(
                Location.NAVIGATION_BAR,
                maxSlots = 1,
                modifier = Modifier,
            )
            // Initialize the navigation graph.
            PhotopickerNavGraph()
        }
    }
}
