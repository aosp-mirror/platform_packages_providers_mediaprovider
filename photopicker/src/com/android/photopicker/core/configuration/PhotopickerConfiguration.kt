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

/**
 * Data object that represents a possible configuration state of the Photopicker.
 *
 * @property action the [Intent#getAction] that Photopicker is currently serving.
 * @property flags a snapshot of the relevant flags in [DeviceConfig]. These are not live values.
 */
data class PhotopickerConfiguration(
    val action: String,
    val flags: PhotopickerFlags = PhotopickerFlags(),
)
