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

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.SwitchUserProfileResult
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.model.Media
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The view model for the Profile selector, it provides various [UserStatus] and [UserProfile] flows
 * to the compose UI, and handles switching profiles when a UI element is selected.
 */
@HiltViewModel
class ProfileSelectorViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    private val selection: Selection<Media>,
    private val userMonitor: UserMonitor,
) : ViewModel() {

    companion object {
        val TAG: String = ProfileSelectorFeature.TAG
    }

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    /** All of the profiles that are available to Photopicker */
    val allProfiles: StateFlow<List<UserProfile>> =
        userMonitor.userStatus
            .map { it.allProfiles }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                initialValue = userMonitor.userStatus.value.allProfiles
            )

    /** The current active profile */
    val selectedProfile: StateFlow<UserProfile> =
        userMonitor.userStatus
            .map { it.activeUserProfile }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                initialValue = userMonitor.userStatus.value.activeUserProfile
            )

    /**
     * Request for the profile to be changed to the provided profile.
     * This is not guaranteed to succeed (the profile could be disabled/unavailable etc)
     *
     * If it does succeed, this will also clear out any selected media since
     * media cannot be selected from multiple profiles simultaneously.
     */
    fun requestSwitchUser(context: Context, requested: UserProfile) {
        scope.launch {
            val result = userMonitor.requestSwitchActiveUserProfile(requested, context)
            if (result == SwitchUserProfileResult.SUCCESS) {
                // If the profile is actually changed, ensure the selection is cleared since
                // content cannot be chosen from multiple profiles simultaneously.
                selection.clear()
            }
        }
    }
}
