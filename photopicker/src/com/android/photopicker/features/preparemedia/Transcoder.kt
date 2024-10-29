/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.preparemedia

import android.content.Context
import android.media.ApplicationMediaCapabilities
import android.net.Uri
import com.android.photopicker.data.PICKER_SEGMENT
import com.android.photopicker.data.PICKER_TRANSCODED_SEGMENT
import com.android.photopicker.data.model.Media

/** Provides methods to help video transcode. */
interface Transcoder {

    /**
     * Checks if a transcode is required for the given video.
     *
     * @param context The context.
     * @param mediaCapabilities The application media capabilities.
     * @param video The video to check.
     */
    fun isTranscodeRequired(
        context: Context,
        mediaCapabilities: ApplicationMediaCapabilities?,
        video: Media.Video,
    ): Boolean

    companion object {

        /**
         * Converts a picker provider URI to a transcoded URI.
         *
         * @param pickerUri The picker provider URI.
         * @return The transcoded URI.
         * @throws IllegalArgumentException If the picker provider URI is not valid.
         */
        fun toTranscodedUri(pickerUri: Uri): Uri {
            val segments = pickerUri.pathSegments

            require(segments.size == 5) { "Unexpected picker provider URI: $pickerUri" }

            val builder = Uri.Builder().scheme(pickerUri.scheme).authority(pickerUri.authority)

            for (i in segments.indices) {
                if (PICKER_SEGMENT == segments[i]) {
                    // Replace picker segment with picker transcoded segment.
                    builder.appendPath(PICKER_TRANSCODED_SEGMENT)
                } else {
                    builder.appendPath(segments[i])
                }
            }

            return builder.build()
        }
    }
}
