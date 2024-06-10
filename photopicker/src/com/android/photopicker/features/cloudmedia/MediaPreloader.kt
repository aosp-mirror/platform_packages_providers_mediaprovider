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

package com.android.photopicker.features.cloudmedia

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.features.LocationParams

/* Size of the spacer between dialog elements. */
private val MEASUREMENT_DIALOG_SPACER_SIZE = 24.dp

/* Size of the padding around the edge of the dialog. */
private val MEASUREMENT_DIALOG_PADDING = 24.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Attaches a [MediaPreloader] so that it can handle emissions in the
 * [LocationParams.WithMediaPreloader.preloadMedia] and display preloading dialogs to the user.
 *
 * This composable has three states:
 * - Empty (No preload activity, no error state)
 * - Loading (A preload operation is currently running)
 * - Error (A preloading operation has failed)
 *
 * For the non empty states, the appropriate dialog is shown to the user. For an empty state, this
 * composable exists to attach the [MediaPreloaderViewModel] so that it can monitor the event bus.
 */
fun MediaPreloader(
    // The incoming modifier is ignored, since no elements are actually added to
    // [Location.MEDIA_PRELOADER], only floating dialogs that sit above the app.
    @Suppress("UNUSED_PARAMETER") modifier: Modifier,
    params: LocationParams,
    viewModel: MediaPreloaderViewModel = hiltViewModel(),
) {

    // Data flow from the view model for which Dialog to display.
    val dialogData by viewModel.dialogData.collectAsStateWithLifecycle()

    // These must be set by the parent composable for the preloader to have any effect.
    val preloaderParameters = params as? LocationParams.WithMediaPreloader

    preloaderParameters?.let {
        LaunchedEffect(params) {
            // Listen for emissions of media to preload, and begin the preload when requested.
            it.preloadMedia.collect { media -> viewModel.startPreload(media, it.obtainDeferred()) }
        }
    }
        // If no preloaderParameters were passed to this location, there is no way to trigger
        // the preloader.
        ?: Log.w(
            CloudMediaFeature.TAG,
            "MediaPreloader did not receive parameters from parent location," +
                "  the preloader will not be active."
        )

    // Show a dialog or empty state based on which [PreloaderDialogData] is present.
    when (val data = dialogData) {
        null -> Unit // Empty state, no dialog
        is PreloaderDialogData.PreloaderLoadingDialogData ->
            MediaPreloaderLoadingDialog(
                dialogData = data,
                onDismissRequest = {
                    viewModel.cancelPreload()
                    viewModel.hideAllDialogs()
                },
            )
        is PreloaderDialogData.PreloaderLoadingErrorDialog ->
            MediaPreloaderErrorDialog(
                onDismissRequest = {
                    viewModel.cancelPreload()
                    viewModel.hideAllDialogs()
                },
            )
    }
}

/**
 * This is the Loading state dialog of the Preloader.
 *
 * This dialog shows a Loading message and progress indicator to the user which updates as the
 * [MediaPreloaderViewModel] emits updated [PreloaderDialogData].
 *
 * The user can cancel the preload operation to return to the previous screen. (This will also
 * prevent the Photopicker from being closed when the Media is ready.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPreloaderLoadingDialog(
    onDismissRequest: () -> Unit,
    dialogData: PreloaderDialogData.PreloaderLoadingDialogData,
) {
    BasicAlertDialog(
        onDismissRequest = {},
    ) {
        Surface(
            modifier = Modifier.wrapContentWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(MEASUREMENT_DIALOG_PADDING)) {
                Text(
                    stringResource(R.string.photopicker_preloading_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.width(MEASUREMENT_DIALOG_SPACER_SIZE))
                    Text(
                        stringResource(
                            R.string.photopicker_preloading_progress_message,
                            dialogData.completed,
                            dialogData.total
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(
                        onClick = onDismissRequest,
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * This is the Error state dialog of the Preloader.
 *
 * This dialog shows a generic Error message to the user which updates as the
 * [MediaPreloaderViewModel] emits updated [PreloaderDialogData].
 *
 * The user can dismiss the dialog to return to the previous screen.
 */
private fun MediaPreloaderErrorDialog(
    onDismissRequest: () -> Unit,
) {

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Surface(
            modifier = Modifier.wrapContentWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(MEASUREMENT_DIALOG_PADDING)) {
                Text(
                    stringResource(R.string.photopicker_preloading_dialog_error_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Text(
                    stringResource(R.string.photopicker_preloading_dialog_error_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(
                        onClick = onDismissRequest,
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
