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

import android.os.Bundle

/**
 * Data class wrapper around a PlaybackState callback from a Remote preview controller.
 *
 * @property state [PlaybackState] enum that represents the returned state int
 * @property surfaceId the relevant surfaceId this PlaybackInfo refers to
 * @property authority the authority of the surfaceId
 * @property playbackStateInfo the (optionally) included Bundle in the state info.
 */
data class PlaybackInfo(
    val state: PlaybackState,
    val surfaceId: Int,
    val authority: String,
    val playbackStateInfo: Bundle? = null,
)
