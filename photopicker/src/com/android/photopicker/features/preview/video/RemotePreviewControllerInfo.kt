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

import android.content.ContentProviderClient

/**
 * Container that holds a [RemoteSurfaceController], and it's corresponding [ContentProviderClient].
 *
 * @property authority The authority of the client and controller.
 * @property client an active [ContentProviderClient] that is held until video playback is finished
 *   to prevent the remote rendering process from being frozen by the OS.
 * @property controller [RemoteSurfaceController] from the [CloudMediaProvider]
 */
data class RemotePreviewControllerInfo(
    val authority: String,
    val client: ContentProviderClient,
    val controller: RemoteSurfaceController,
)
