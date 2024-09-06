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

package com.android.photopicker.features.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.photopicker.R

/* Size of the spacer between dialog elements. */
private val MEASUREMENT_ERROR_DIALOG_SPACER_SIZE = 24.dp

/* Size of the padding around the edge of the dialog. */
private val MEASUREMENT_ERROR_DIALOG_PADDING = 16.dp

/**
 * Creates an error dialog for the ERROR_RETRIABLE_FAILURE error state. This error state is reached
 * when the remote preview provider is unable to play the video (most likely related to a connection
 * issue), but the user can attempt to play the video again.
 *
 * @param onDismissRequest Action to take when the dialog is dismissed, most likely via a back
 *   navigation, or by clicking outside of the dialog.
 * @param onRetry Action to take when the user clicks the "Retry" button on the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetriableErrorDialog(
    onDismissRequest: () -> Unit,
    onRetry: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Surface(
            modifier = Modifier.wrapContentWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(MEASUREMENT_ERROR_DIALOG_PADDING)) {
                Text(
                    stringResource(R.string.photopicker_preview_dialog_error_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_ERROR_DIALOG_SPACER_SIZE))
                Text(
                    stringResource(R.string.photopicker_preview_dialog_error_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(MEASUREMENT_ERROR_DIALOG_SPACER_SIZE))
                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    onClick = onRetry,
                ) {
                    Text(
                        stringResource(R.string.photopicker_preview_dialog_error_retry_button_label)
                    )
                }
            }
        }
    }
}
