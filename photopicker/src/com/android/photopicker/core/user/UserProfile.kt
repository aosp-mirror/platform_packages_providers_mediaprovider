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

/**
 * Photopicker's Internal representation of a UserProfile, derived from [UserHandle]
 *
 * @property identifier unique identifier for this profile.
 * @property profileType the internal type that Photopicker considers this profile
 * @property enabled if the profile is currently enabled to for use in Photopicker.
 */
data class UserProfile(
    val identifier: Int,
    val profileType: ProfileType = ProfileType.UNKNOWN,
    val enabled: Boolean = true,
) {

    enum class ProfileType {
        PRIMARY, // For parent // base user profiles
        MANAGED, // For Managed (aka Work) Profiles
        UNKNOWN, // Default
    }
}
