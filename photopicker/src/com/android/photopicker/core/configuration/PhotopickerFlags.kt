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

package com.android.photopicker.core.configuration

// Flag namespace for mediaprovider
val NAMESPACE_MEDIAPROVIDER = "mediaprovider"

// Cloud feature flags, and their default values.
val FEATURE_CLOUD_MEDIA_FEATURE_ENABLED = Pair("cloud_media_feature_enabled", true)
val FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST = Pair("allowed_cloud_providers", "")
val FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST = Pair("cloud_media_enforce_provider_allowlist", true)

// Private space feature flags, and their default values.
val FEATURE_PRIVATE_SPACE_ENABLED = Pair("private_space_feature_enabled", true)

// Permissions feature flags, and their default values.
val FEATURE_PICKER_CHOICE_MANAGED_SELECTION = Pair("picker_choice_managed_selection_enabled", true)

/** Data object that represents flag values in [DeviceConfig]. */
data class PhotopickerFlags(
    val CLOUD_ALLOWED_PROVIDERS: String = FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.second,
    val CLOUD_ENFORCE_PROVIDER_ALLOWLIST: Boolean = FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.second,
    val CLOUD_MEDIA_ENABLED: Boolean = FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
    val PRIVATE_SPACE_ENABLED: Boolean = FEATURE_PRIVATE_SPACE_ENABLED.second,
    val MANAGED_SELECTION_ENABLED: Boolean = FEATURE_PICKER_CHOICE_MANAGED_SELECTION.second
)
