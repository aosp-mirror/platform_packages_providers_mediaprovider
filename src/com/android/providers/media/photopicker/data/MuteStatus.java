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

package com.android.providers.media.photopicker.data;

/**
 * Tracks the status of volume mute request from the user.
 */
public final class MuteStatus {
    /**
     * Always start video preview with volume off
     */
    private boolean mIsVolumeMuted = true;

    public MuteStatus() {};

    /**
     * Sets the volume to mute/unmute
     * @param isVolumeMuted - {@code true} if the volume state should be set to mute.
     *                        {@code false} otherwise.
     */
    public void setVolumeMuted(boolean isVolumeMuted) {
        mIsVolumeMuted = isVolumeMuted;
    }

    /**
     * @return {@code isVolumeMuted}
     */
    public boolean isVolumeMuted() {
        return mIsVolumeMuted;
    }
}
