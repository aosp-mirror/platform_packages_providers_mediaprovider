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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState

/**
 * Composable that hides the content when the state of the photopicker matches the selector.
 *
 * @param selector The selector for the state of the photopicker to hide the [content] in.
 * @param content The composable to be hidden.
 */
@Composable
fun hideWhenState(selector: StateSelector, content: @Composable () -> Unit) {
    val isEmbedded: Boolean =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val isExpanded: Boolean = LocalEmbeddedState.current?.isExpanded ?: false

    // For a composable to be hidden in a given state, call the composable when the photopicker is
    // not in the selected state.
    when (selector) {
        is StateSelector.Embedded -> {
            if (!isEmbedded) content()
        }
        is StateSelector.EmbeddedAndCollapsed -> {
            // Content is displayed when the runtime environment is not embedded
            // or when it's embedded and the picker is expanded.
            if (!isEmbedded || isExpanded) content()
        }
        is StateSelector.AnimatedVisibilityInEmbedded -> {
            if (isEmbedded) {
                AnimatedVisibility(
                    visible = selector.visible,
                    enter = selector.enter,
                    exit = selector.exit,
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}
