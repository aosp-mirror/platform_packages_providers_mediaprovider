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

package com.android.photopicker.core.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavHostController

/**
 * Provider for fetching the [NavHostController] inside of composables.
 *
 * This uses [compositionLocalOf] (rather than a static one) in case of a configuration change which
 * would result in a value change here as the UI is recomposed.
 */
val LocalNavController =
    compositionLocalOf<NavHostController> { error("No NavHostController provided") }
