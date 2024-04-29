/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_BUFFERING
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_COMPLETED
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_PERMANENT_FAILURE
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED

/**
 * Wrapper enum around the [CloudMediaProvider.CloudMediaSurfaceStateChangedCallback] state
 * integers.
 *
 * @property state the underlying value as defined by the API.
 */
enum class PlaybackState(val state: Int) {
    UNKNOWN(-1),
    BUFFERING(PLAYBACK_STATE_BUFFERING),
    READY(PLAYBACK_STATE_READY),
    STARTED(PLAYBACK_STATE_STARTED),
    PAUSED(PLAYBACK_STATE_PAUSED),
    COMPLETED(PLAYBACK_STATE_COMPLETED),
    MEDIA_SIZE_CHANGED(PLAYBACK_STATE_MEDIA_SIZE_CHANGED),
    ERROR_RETRIABLE_FAILURE(PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE),
    ERROR_PERMANENT_FAILURE(PLAYBACK_STATE_ERROR_PERMANENT_FAILURE);

    companion object {
        /**
         * @return Converts a [CloudMediaSurfaceStateChangedCallback] state int into the enum, or
         *   UNKNOWN if the value is not valid.
         */
        fun fromStateInt(value: Int): PlaybackState {
            return PlaybackState.entries.find { it.state == value } ?: UNKNOWN
        }
    }
}
