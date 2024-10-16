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

import android.content.ContentResolver

/**
 * A representation of the current UserStatus for the PhotopickerApplication
 *
 * @property activeUserProfile The user profile currently selected by the user.
 * @property allProfiles A list of known profiles accessible to this user. Note: This list does not
 *   have a stable sort order, so do not index this list directly.
 * @property activeContentResolver The Content resolver for the active user profile.
 */
data class UserStatus(
    val activeUserProfile: UserProfile,
    val allProfiles: List<UserProfile>,
    val activeContentResolver: ContentResolver,
)
