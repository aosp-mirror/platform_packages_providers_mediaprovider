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

/**
 * Hold the details of a [CloudMediaProvider] including the sync state of the Provider.
 */
data class CloudMediaProviderDetails(
        val authority: String,

        /** This is the name of the account linked with Photopicker */
        val accountName: String?,

        /**
         * A collection represents a library of media.
         * Collection ID uniquely identifies a library of media.
         * Latest collection ID represents the latest known collection ID of the [Provider].
         */
        val latestCollectionID: String?,

        /**
         * The generation number tells us about the current version of the collection.
         * It monotonically increases with each media change in the library.
         */
        val latestGenerationNumber: Long,

        /**
         * The latest collection ID that the Picker DB is fully synced with.
         * @see latestCollectionID
         */
        val lastSyncedCollectionID: String?,

        /**
         * The latest generation number that the Picker DB is fully synced to.
         * @see latestGenerationNumber
         */
        val lastSyncedGenerationNumber: Long,

        /**
         * Pre-calculates whether Picker is fully in sync with the library or not.
         */
        val isSynced: Boolean
)