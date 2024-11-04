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

package com.android.photopicker.features.preparemedia

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.obtainViewModel
import kotlinx.coroutines.launch

/* Size of the spacer between dialog elements. */
private val MEASUREMENT_DIALOG_SPACER_SIZE = 24.dp

/* Size of the padding around the edge of the dialog. */
private val MEASUREMENT_DIALOG_PADDING = 24.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Attaches a [MediaPreparer] so that it can handle emissions in the
 * [LocationParams.WithMediaPreparer.prepareMedia] and display preparing dialogs to the user.
 *
 * This composable has three states:
 * - Empty (No prepare activity, no error state)
 * - Preparing (A prepare operation is currently running)
 * - Error (A preparing operation has failed)
 *
 * For the non empty states, the appropriate dialog is shown to the user. For an empty state, this
 * composable exists to attach the [MediaPreparerViewModel] so that it can monitor the event bus.
 */
fun MediaPreparer(
    // The incoming modifier is ignored, since no elements are actually added to
    // [Location.MEDIA_PREPARER], only floating dialogs that sit above the app.
    @Suppress("UNUSED_PARAMETER") modifier: Modifier,
    params: LocationParams,
    viewModel: MediaPreparerViewModel = obtainViewModel(),
) {

    // Data flow from the view model for which Dialog to display.
    val dialogData by viewModel.dialogData.collectAsStateWithLifecycle()

    // These must be set by the parent composable for the preparer to have any effect.
    val preparerParameters = params as? LocationParams.WithMediaPreparer

    val configuration = LocalPhotopickerConfiguration.current
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val context = LocalContext.current

    preparerParameters?.let {
        LaunchedEffect(params) {
            // Listen for emissions of media to prepare, and begin the prepare when requested.
            it.prepareMedia.collect { media ->
                // Dispatch UI event to log the beginning of media items preparing
                scope.launch {
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.CORE.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.PICKER_PRELOADING_START,
                        )
                    )
                }

                viewModel.startPrepare(media, it.obtainDeferred(), context)
            }
        }
    }
        // If no preparerParameters were passed to this location, there is no way to trigger
        // the preparer.
        ?: Log.w(
            PrepareMediaFeature.TAG,
            "MediaPreparer did not receive parameters from parent location," +
                "  the preparer will not be active.",
        )

    // Show a dialog or empty state based on which [PreparerDialogData] is present.
    when (val data = dialogData) {
        null -> Unit // Empty state, no dialog
        is PreparerDialogData.PreparingDialogData ->
            MediaPreparerPreparingDialog(
                dialogData = data,
                onDismissRequest = {
                    viewModel.cancelPrepare(preparerParameters?.obtainDeferred())
                    viewModel.hideAllDialogs()
                },
            )
        is PreparerDialogData.PreparingErrorDialog ->
            MediaPreparerErrorDialog(
                onDismissRequest = {
                    viewModel.cancelPrepare(preparerParameters?.obtainDeferred())
                    viewModel.hideAllDialogs()
                }
            )
    }
}

/**
 * This is the Preparing state dialog of the Preparer.
 *
 * This dialog shows a Preparing message and progress indicator to the user which updates as the
 * [MediaPreparerViewModel] emits updated [PreparerDialogData].
 *
 * The user can cancel the prepare operation to return to the previous screen. (This will also
 * prevent the Photopicker from being closed when the Media is ready.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPreparerPreparingDialog(
    onDismissRequest: () -> Unit,
    dialogData: PreparerDialogData.PreparingDialogData,
) {
    BasicAlertDialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.wrapContentWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(MEASUREMENT_DIALOG_PADDING)) {
                Text(
                    stringResource(R.string.photopicker_preloading_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.width(MEASUREMENT_DIALOG_SPACER_SIZE))
                    Text(
                        stringResource(
                            R.string.photopicker_preloading_progress_message,
                            dialogData.completed,
                            dialogData.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismissRequest) {
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
 * This is the Error state dialog of the Preparer.
 *
 * This dialog shows a generic Error message to the user which updates as the
 * [MediaPreparerViewModel] emits updated [PreparerDialogData].
 *
 * The user can dismiss the dialog to return to the previous screen.
 */
private fun MediaPreparerErrorDialog(onDismissRequest: () -> Unit) {

    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier.wrapContentWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(MEASUREMENT_DIALOG_PADDING)) {
                Text(
                    stringResource(R.string.photopicker_preloading_dialog_error_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Text(
                    stringResource(R.string.photopicker_preloading_dialog_error_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
