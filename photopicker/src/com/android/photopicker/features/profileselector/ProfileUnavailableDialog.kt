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

package com.android.photopicker.features.profileselector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserProfile.DisabledReason
import com.android.photopicker.core.user.UserProfile.DisabledReason.CROSS_PROFILE_NOT_ALLOWED
import com.android.photopicker.core.user.UserProfile.DisabledReason.QUIET_MODE

/* Size of the spacer between dialog elements. */
private val MEASUREMENT_DIALOG_SPACER_SIZE = 24.dp

/* Size of the padding around the edge of the dialog. */
private val MEASUREMENT_DIALOG_PADDING = 24.dp

/**
 * Show an error dialog for a profile that is unavailable.
 *
 * The dialog will generate the correct error title and message based on the list of disabled
 * reasons present in the [UserProfile]. If more than one reason exists, it will show the highest
 * priority error message (as determined by its own internal logic).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProfileUnavailableDialog(
    onDismissRequest: () -> Unit,
    profile: UserProfile,
) {

    val (dialogTitle, dialogMessage) =
        with(profile.disabledReasons) {
            when {
                // These disabled reason checks are in order of error message priority. Since only
                // one dialog can be shown for a profile, these checks are ordered by display
                // priority. As soon as one is found, the rest will be ignored.
                contains(CROSS_PROFILE_NOT_ALLOWED) ->
                    getDialogContentForReason(
                        CROSS_PROFILE_NOT_ALLOWED,
                        profile.label ?: getLabelForProfile(profile)
                    )
                contains(QUIET_MODE) ->
                    getDialogContentForReason(
                        QUIET_MODE,
                        profile.label ?: getLabelForProfile(profile)
                    )

                // If a prioritized reason isn't found, generate dialog content for the first
                // reason in the set.
                else ->
                    getDialogContentForReason(first(), profile.label ?: getLabelForProfile(profile))
            }
        }

    // Now that the dialog's content is known, create and show the dialog.
    ProfileUnavailableDialog(
        onDismissRequest = onDismissRequest,
        dialogTitle = dialogTitle,
        dialogMessage = dialogMessage
    )
}

/**
 * Generate a [Pair] of Dialog title and dialog message for the given profile and [DisabledReason].
 *
 * This method will fail to compile when new [DisabledReason] enum values are added, to ensure all
 * reasons have an associated Dialog title and message.
 *
 * @return [Pair] where the first element is the Dialog title, and the second value is the dialog
 *   message.
 */
@Composable
private fun getDialogContentForReason(
    reason: DisabledReason,
    profileLabel: String,
): Pair<String, String> {

    return when (reason) {
        CROSS_PROFILE_NOT_ALLOWED ->
            Pair(
                stringResource(R.string.photopicker_profile_blocked_by_admin_dialog_title),
                stringResource(R.string.photopicker_profile_blocked_by_admin_dialog_message)
            )
        QUIET_MODE ->
            Pair(
                stringResource(R.string.photopicker_profile_unavailable_dialog_title, profileLabel),
                stringResource(
                    R.string.photopicker_profile_unavailable_dialog_message,
                    profileLabel
                )
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileUnavailableDialog(
    onDismissRequest: () -> Unit,
    dialogTitle: String,
    dialogMessage: String
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
                Text(dialogTitle, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(MEASUREMENT_DIALOG_SPACER_SIZE))
                Text(dialogMessage, style = MaterialTheme.typography.bodyMedium)
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
