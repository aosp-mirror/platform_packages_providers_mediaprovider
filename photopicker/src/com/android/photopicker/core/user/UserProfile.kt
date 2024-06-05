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

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Photopicker's Internal representation of a UserProfile, derived from [UserHandle]
 *
 * @property identifier unique identifier for this profile.
 * @property handle the [UserHandle] associated with this profile.
 * @property profileType the internal type that Photopicker considers this profile
 * @property disabledReasons a [Set] of [DisabledReason] for why the profile cannot become the
 *   active profile.
 * @property enabled if the profile is currently enabled to for use in Photopicker.
 */
data class UserProfile(
    val identifier: Int,
    val icon: ImageBitmap? = null,
    val label: String? = null,
    val profileType: ProfileType = ProfileType.UNKNOWN,
    val disabledReasons: Set<DisabledReason> = emptySet(),
) {

    /** A custom equals operator to not consider the value of the Icon field when it is not null */
    override fun equals(other: Any?): Boolean {
        if (other !is UserProfile) return false
        if (icon != null && other.icon == null) return false
        if (icon == null && other.icon != null) return false
        return identifier == other.identifier &&
            label == other.label &&
            profileType == other.profileType &&
            disabledReasons == other.disabledReasons
    }

    /** A profile is considered enabled if it has no reason to be disabled! :) */
    val enabled = disabledReasons.isEmpty()

    // These Profile Types are only needed for devices on pre Android V to map the
    // appropriate display resources to the type of profile. After Android V these
    // resources are provided by the [UserManager] and so any other profiles that get
    // added after Android V will show up as UNKNOWN.
    enum class ProfileType {
        PRIMARY, // For parent // base user profiles
        MANAGED, // For Managed (aka Work) Profiles
        UNKNOWN, // Default
    }

    /**
     * Why a particular profile cannot be enabled. (For determining specific error messages and
     * priority of messages)
     */
    enum class DisabledReason {
        CROSS_PROFILE_NOT_ALLOWED,
        QUIET_MODE,
    }
}
