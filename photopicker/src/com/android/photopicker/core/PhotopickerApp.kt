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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerNavGraph

/**
 * This is the top of the Compose UI node tree. This is called from the MainActivity and is the
 * top-most [@Composable] in the application. This should not be called except inside an Activity's
 * [setContent] block.
 */
@Composable
fun PhotopickerApp() {

    // Initialize and remember the NavController. This needs to be provided before the call to
    // the NavigationGraph, so this is done at the top.
    val navController = rememberNavController()

    // Provide the NavController to the rest of the Compose stack.
    CompositionLocalProvider(LocalNavController provides navController) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text("Hello World from Photopicker!")

                // Initialize the navigation graph.
                PhotopickerNavGraph()
            }
        }
    }
}
