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

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * State selector interface for passing state in which a composable has to be hidden to
 * [hideWhenState] in embedded photopicker.
 */
sealed interface StateSelector {
    // Indicates that the photopicker is running in an embedded environment.
    object Embedded : StateSelector

    // Indicates that the photopicker is running in an embedded environment and is currently
    // collapsed.
    object EmbeddedAndCollapsed : StateSelector

    // Used for applying animated visibility on features when the photopicker is running in the
    // embedded runtime.
    interface AnimatedVisibilityInEmbedded : StateSelector {
        val visible: Boolean
        val enter: EnterTransition
        val exit: ExitTransition
    }
}
