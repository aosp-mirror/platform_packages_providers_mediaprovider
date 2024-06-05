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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.user.UserProfile

/** Entry point for the profile selector. */
@Composable
fun ProfileSelector(
    modifier: Modifier = Modifier,
    viewModel: ProfileSelectorViewModel = hiltViewModel(),
) {

    // Collect selection to ensure this is recomposed when the selection is updated.
    val allProfiles by viewModel.allProfiles.collectAsStateWithLifecycle()

    // MutableState which defines which profile to use to display the [ProfileUnavailableDialog].
    // When this value is null, the dialog is hidden.
    var disabledDialogProfile: UserProfile? by remember { mutableStateOf(null) }
    disabledDialogProfile?.let {
        ProfileUnavailableDialog(
            onDismissRequest = { disabledDialogProfile = null },
            profile = it,
        )
    }

    // Ensure there is more than one available profile before creating all of the UI.
    if (allProfiles.size > 1) {
        val context = LocalContext.current
        val currentProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = modifier) {
            OutlinedIconButton(onClick = { expanded = !expanded }) {
                currentProfile.icon?.let {
                    Icon(
                        it,
                        contentDescription =
                            stringResource(R.string.photopicker_profile_switch_button_description)
                    )
                }
                // If the profile doesn't have an icon drawable set, then
                // generate one.
                ?: Icon(
                        getIconForProfile(currentProfile),
                        contentDescription =
                            stringResource(R.string.photopicker_profile_switch_button_description)
                    )
            }

            // DropdownMenu attaches to the element above it in the hierarchy, so this should stay
            // directly below the button that opens it.
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = !expanded },
            ) {
                for (profile in allProfiles) {

                    // The background color behind the text
                    Surface(
                        modifier = Modifier.widthIn(min = 200.dp),
                        color =
                            if (currentProfile == profile)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier.fillMaxWidth(),
                            // enabled = profile.enabled,
                            onClick = {
                                // Only request a switch if the profile is actually different.
                                if (currentProfile != profile) {

                                    if (profile.enabled) {
                                        viewModel.requestSwitchUser(
                                            context = context,
                                            requested = profile
                                        )
                                        // Close the profile switcher popup
                                        expanded = false
                                    } else {

                                        // Show the disabled profile dialog
                                        disabledDialogProfile = profile
                                        expanded = false
                                    }
                                }
                            },
                            text = { Text(profile.label ?: getLabelForProfile(profile)) },
                            leadingIcon = {
                                profile.icon?.let {
                                    Icon(
                                        it,
                                        contentDescription = null,
                                        tint =
                                            when (profile.enabled) {
                                                true -> MenuDefaults.itemColors().leadingIconColor
                                                false ->
                                                    MenuDefaults.itemColors()
                                                        .disabledLeadingIconColor
                                            }
                                    )
                                }
                                // If the profile doesn't have an icon drawable set, then
                                // generate one.
                                ?: Icon(
                                        getIconForProfile(profile),
                                        contentDescription = null,
                                        tint =
                                            when (profile.enabled) {
                                                true -> MenuDefaults.itemColors().leadingIconColor
                                                false ->
                                                    MenuDefaults.itemColors()
                                                        .disabledLeadingIconColor
                                            }
                                    )
                            },
                        )
                    }
                }
            }
        }
    } else {
        // Return a spacer which consumes the modifier so the space is still occupied, but is empty.
        Spacer(modifier)
    }
}

/**
 * Generates a display label for the provided profile.
 *
 * @param profile the profile!
 * @return a display safe & localized profile name
 */
@Composable
internal fun getLabelForProfile(profile: UserProfile): String {
    return when (profile.profileType) {
        UserProfile.ProfileType.PRIMARY ->
            stringResource(R.string.photopicker_profile_primary_label)
        UserProfile.ProfileType.MANAGED ->
            stringResource(R.string.photopicker_profile_managed_label)
        UserProfile.ProfileType.UNKNOWN ->
            stringResource(R.string.photopicker_profile_unknown_label)
    }
}

/**
 * Generates a display icon for the provided profile.
 *
 * @param profile the profile!
 * @return an icon [ImageVector] that represents the profile
 */
@Composable
internal fun getIconForProfile(profile: UserProfile): ImageVector {
    return when (profile.profileType) {
        UserProfile.ProfileType.PRIMARY -> Icons.Filled.AccountCircle
        UserProfile.ProfileType.MANAGED -> Icons.Filled.Work
        UserProfile.ProfileType.UNKNOWN -> Icons.Filled.Person
    }
}
