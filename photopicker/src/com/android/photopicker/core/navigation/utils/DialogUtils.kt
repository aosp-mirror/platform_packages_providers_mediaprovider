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

package com.android.photopicker.core.navigation.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Private to limit the visibility as this is a temporary function until b/281081905 is fixed */
@Composable private fun getActivityWindow(): Window? = LocalView.current.context.getActivityWindow()

/** Private to limit the visibility as this is a temporary function until b/281081905 is fixed */
private tailrec fun Context.getActivityWindow(): Window? =
    when (this) {
        is Activity -> window
        is ContextWrapper -> baseContext.getActivityWindow()
        else -> null
    }

/**
 * Workaround to set the Dialog's window parameters as the same as the parent activities window
 * attributes. This should be removed when b/281081905 is fixed and DialogProperties allows dialogs
 * to handle system windows correctly.
 *
 * In the event that Photopicker is not currently running in an activity, this has no effect.
 */
@Composable
fun SetDialogDestinationToEdgeToEdge() {
    val activityWindow = getActivityWindow()
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    val parentView = LocalView.current.parent as View
    SideEffect {
        if (activityWindow != null && dialogWindow != null) {
            val attributes = WindowManager.LayoutParams()
            attributes.copyFrom(activityWindow.attributes)
            attributes.type = dialogWindow.attributes.type
            dialogWindow.attributes = attributes
            parentView.layoutParams =
                FrameLayout.LayoutParams(
                    activityWindow.decorView.width,
                    activityWindow.decorView.height
                )
        }
    }
}
