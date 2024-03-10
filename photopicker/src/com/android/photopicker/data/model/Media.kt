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

package com.android.photopicker.data.model

import android.net.Uri

/**
 * Holds metadata for a type of media item like [Image] or [Video].
 */
sealed interface Media {
        /** This is the ID that provider has shared with Picker */
        val mediaId: String

        /** This is the Picker ID auto-generated in Picker DB */
        val pickerId: Long
        val authority: String
        val uri: Uri
        val dateTakenMillisLong: Long
        val sizeInBytes: Long
        val mimeType: String
        val standardMimeTypeExtension: Int

        /**
         * Holds metadata for an image item.
         */
        data class Image(
                override val mediaId: String,
                override val pickerId: Long,
                override val authority: String,
                override val uri: Uri,
                override val dateTakenMillisLong: Long,
                override val sizeInBytes: Long,
                override val mimeType: String,
                override val standardMimeTypeExtension: Int,
        ) : Media

        /**
         * Holds metadata for a video item.
         */
        data class Video(
                override val mediaId: String,
                override val pickerId: Long,
                override val authority: String,
                override val uri: Uri,
                override val dateTakenMillisLong: Long,
                override val sizeInBytes: Long,
                override val mimeType: String,
                override val standardMimeTypeExtension: Int,

                val duration: Int,
        ) : Media
}