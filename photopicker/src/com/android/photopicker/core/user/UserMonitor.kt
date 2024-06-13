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

package com.android.photopicker.core.user

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.extensions.requireSystemService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Provides a long-living [StateFlow] that represents the current application's [UserStatus]. This
 * class also provides methods to switch the current active profile.
 *
 * This is provided as a part of Core and will be lazily initialized to prevent it from being
 * created before it is needed, but it will live as a singleton for the life of the activity once it
 * has been initialized.
 *
 * Will emit a value immediately of the current list available [UserProfile] as well as the current
 * Active profile. (Initialized as the Profile of the user that owns the process the [Activity] is
 * running in.)
 *
 * Additionally, this class registers a [BroadcastReceiver] on behalf of the activity to subscribe
 * to profile changes as they happen on the device, although those are subject to delivery delays
 * depending on how busy the device currently is (and if Photopicker is currently in the
 * foreground).
 *
 * @property context The context of the Activity this UserMonitor is provided in.
 * @property scope The [CoroutineScope] that the BroadcastReceiver will listen in.
 * @property dispatcher [CoroutineDispatcher] scope that the BroadcastReceiver will listen in.
 * @property intent The activity's intent for determining CrossProfile support.
 */
class UserMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val processOwnerUserHandle: UserHandle,
) {

    companion object {
        val TAG: String = "PhotopickerUserMonitor"
    }

    private val userManager: UserManager = context.requireSystemService()
    private val packageManager: PackageManager = context.getPackageManager()

    /**
     * Internal state flow that the external flow is derived from. When making state changes, this
     * is the flow that should be updated.
     */
    private val _userStatus: MutableStateFlow<UserStatus> =
        MutableStateFlow(
            UserStatus(
                activeUserProfile = getUserProfileFromHandle(processOwnerUserHandle),
                allProfiles = userManager.getUserProfiles().map(::getUserProfileFromHandle)
            )
        )

    /**
     * This flow exposes the current internal [UserStatus], and replays the most recent value for
     * new subscribers.
     */
    val userStatus: StateFlow<UserStatus> =
        _userStatus.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            initialValue = _userStatus.value
        )

    /** Setup a BroadcastReceiver to receive broadcasts for profile availability changes */
    private val profileChanges =
        callbackFlow<Intent> {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(unused: Context, intent: Intent) {
                        Log.d(TAG, "Received profile changed: $intent")
                        trySend(intent)
                    }
                }
            val intentFilter = IntentFilter()

            // It's ok if these intents send duplicate broadcasts, the resulting state is only
            // updated & emitted if something actually changed. (Meaning duplicate broadcasts will
            // not cause subscribers to be notified, although there is a marginal cost to parse the
            // profile state again)
            intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)

            if (SdkLevel.isAtLeastS()) {
                // On S+ devices use the broader profile listners to capture other types of
                // profiles.
                intentFilter.addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
                intentFilter.addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            }

            /*
             TODO(b/303779617)
             This broadcast receiver should be launched in the parent profile of the user since
             child profiles do not receive these broadcasts.
            */
            if (SdkLevel.isAtLeastT()) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // manually set the flags since [Context.RECEIVER_NOT_EXPORTED] doesn't exist pre
                // Sdk33
                context.registerReceiver(receiver, intentFilter, /* flags=*/ 0x4)
            }

            awaitClose {
                Log.d(TAG, """BroadcastReceiver was closed, unregistering.""")
                context.unregisterReceiver(receiver)
            }
        }

    init {
        // Begin to collect emissions from the BroadcastReceiver. Started in the init block
        // to ensure only one collection is ever started. This collection is launched in the
        // injected scope with the injected dispatcher.
        scope.launch(dispatcher) { profileChanges.collect { handleProfileChangeBroadcast(it) } }
    }

    /**
     * Attempt to switch the Active [UserProfile] to a known profile that matches the passed
     * [UserProfile].
     *
     * This is not guaranteed to succeed. The target profile type may be disabled, not exist or
     * already be active. If the profile switch is successful, [UserMonitor] will emit a new
     * [UserStatus] with the updated state.
     *
     * @return The [SwitchProfileResult] of the requested change.
     */
    suspend fun requestSwitchActiveUserProfile(requested: UserProfile): SwitchUserProfileResult {

        // Attempt to find the requested profile amongst the profiles known.
        val profile: UserProfile? =
            _userStatus.value.allProfiles.find { it.identifier == requested.identifier }

        profile?.let {

            // Only allow the switch if a profile is currently enabled.
            if (profile.enabled) {
                _userStatus.update { it.copy(activeUserProfile = profile) }
                return SwitchUserProfileResult.SUCCESS
            }

            return SwitchUserProfileResult.FAILED_PROFILE_DISABLED
        }

        return SwitchUserProfileResult.FAILED_UNKNOWN_PROFILE
    }

    /**
     * Handler for the incoming BroadcastReceiver emissions representing a profile state change.
     *
     * This handler will check the currently known profiles in the current user state and emit an
     * updated user status value.
     */
    private suspend fun handleProfileChangeBroadcast(intent: Intent) {

        val handle: UserHandle? = getUserHandleFromIntent(intent)

        handle?.let {
            Log.d(
                TAG,
                "Received a profile update for ${handle.getIdentifier()} from intent $intent"
            )

            // Assemble a new UserProfile from the updated UserHandle.
            val profile = getUserProfileFromHandle(handle)

            // Generate a new list of profiles to in preparation for an update.
            val newProfilesList: List<UserProfile> =
                listOf(
                    // Copy the current list but remove the matching profile
                    *_userStatus.value.allProfiles
                        .filterNot { it.identifier == profile.identifier }
                        .toTypedArray(),
                    // Replace the matching profile with the updated one.
                    profile
                )

            // Check and see if the profile we just updated is still enabled, and if it is the
            // current active profile
            if (
                !profile.enabled &&
                    profile.identifier == _userStatus.value.activeUserProfile.identifier
            ) {
                Log.i(
                    TAG,
                    "The active profile is no longer enabled, transitioning back to the process" +
                        " owner's profile."
                )

                // The current profile is disabled, we need to transition back to the process
                // owner's
                // profile.
                val processOwnerProfile =
                    newProfilesList.find { it.identifier == processOwnerUserHandle.getIdentifier() }

                processOwnerProfile?.let {
                    // Update userStatus with the updated list of UserProfiles.
                    _userStatus.update {
                        it.copy(
                            activeUserProfile = processOwnerProfile,
                            allProfiles = newProfilesList
                        )
                    }
                }

                // This is potentially a problematic state, the current profile is disabled,
                // and attempting to find the process owner's profile was unsuccessful.
                ?: run {
                        Log.w(
                            TAG,
                            "Could not find the process owner's profile to switch to when the" +
                                " active profile was disabled."
                        )

                        // Still attempt to update the list of profiles.
                        _userStatus.update { it.copy(allProfiles = newProfilesList) }
                    }
            } else {

                // Update userStatus with the updated list of UserProfiles.
                _userStatus.update { it.copy(allProfiles = newProfilesList) }
            }
        }
        // If the incoming Intent does not include a UserHandle, there is nothing to update,
        // but Log a warning to help with debugging.
        ?: run {
                Log.w(
                    TAG,
                    "Received intent: $intent but could not find matching UserHandle. Ignoring."
                )
            }
    }

    /**
     * Determines if the current handle supports CrossProfile content sharing.
     *
     * @return Whether CrossProfile content sharing is supported in this handle.
     */
    private fun getIsCrossProfileAllowedForHandle(
        @Suppress("UNUSED_PARAMETER") handle: UserHandle
    ): Boolean {
        // TODO(b/303779617): Implement cross profile handling logic.
        return true
    }

    /**
     * Assemble a [UserProfile] from a provided [UserHandle]
     *
     * @return A UserProfile that corresponds to the UserHandle.
     */
    private fun getUserProfileFromHandle(handle: UserHandle): UserProfile {

        val isParentProfile = userManager.getProfileParent(handle) == null
        val isManaged = userManager.isManagedProfile(handle.getIdentifier())
        val isQuietModeEnabled = userManager.isQuietModeEnabled(handle)
        var isCrossProfileSupported = getIsCrossProfileAllowedForHandle(handle)

        return UserProfile(
            identifier = handle.getIdentifier(),
            profileType =
                // Profiles that do not have a parent are considered the primary profile
                if (isParentProfile) UserProfile.ProfileType.PRIMARY
                else if (isManaged) UserProfile.ProfileType.MANAGED
                // TODO(b/303779617): Correctly identify private space.
                else UserProfile.ProfileType.UNKNOWN,

            // A profile is only enabled if it's not in quiet mode AND the content be can shared
            // with the process owner's profile.
            enabled = !isQuietModeEnabled && isCrossProfileSupported,
        )
    }

    /**
     * Attempts to extract a user handle from the provided intent, using the [Intent.EXTRA_USER]
     * key.
     *
     * @return the nullable UserHandle if the handle isn't provided, or if the object in
     *   [Intent.EXTRA_USER] isn't a [UserHandle]
     */
    private suspend fun getUserHandleFromIntent(intent: Intent): UserHandle? {

        if (SdkLevel.isAtLeastT())
        // Use the type-safe API when it's available.
        return intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
        else
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle
    }
}
