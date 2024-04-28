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
 * Holds metadata for a group of media items.
 */
sealed interface Group {
        /** Unique identifier for this group */
        val id: String

        /**
         * Holds metadata for a album item. It is a type of a [Group] object because it represents a
         * collection of media items.
         */
        data class Album(
                /** This is the ID provided by the [Provider] of this data */
                override val id: String,

                /** This is the Picker ID auto-generated in Picker DB */
                val pickerId: Long,
                val authority: String,
                val dateTakenMillisLong: Long,
                val displayName: String,
                val coverUri: Uri,
                val coverMediaSource: MediaSource,
        ) : Group
}