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

package com.android.photopicker.data

import android.content.ContentResolver
import android.database.Cursor
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider

/**
 * A client class that is reponsible for holding logic required to interact with [MediaProvider].
 *
 * It typically fetches data from [MediaProvider] using content queries and call methods.
 */
class MediaProviderClient {
    companion object {
        private const val AVAILABLE_PROVIDER_AUTHORITY_COLUMN: String = "authority"
        private const val AVAILABLE_PROVIDER_MEDIA_SOURCE_COLUMN: String = "media_source"
        private const val AVAILABLE_PROVIDER_UID_COLUMN: String = "uid"

        private val AVAILABLE_PROVIDER_PROJECTION: Array<String> = arrayOf(
            AVAILABLE_PROVIDER_AUTHORITY_COLUMN,
            AVAILABLE_PROVIDER_MEDIA_SOURCE_COLUMN,
            AVAILABLE_PROVIDER_UID_COLUMN,
        )

        /**
         * Fetch available providers from the Media Provider process.
         */
        fun fetchAvailableProviders(
            contentResolver: ContentResolver,
        ): List<Provider> {
            try {
                contentResolver.query(
                    AVAILABLE_PROVIDERS_URI,
                    AVAILABLE_PROVIDER_PROJECTION,
                    /* selection */ null,
                    /* cancellationSignal */ null // TODO
                ).use {
                    cursor -> return getListOfProviders(cursor!!)
                }
            } catch (e: RuntimeException) {
                throw RuntimeException("Could not fetch available providers", e)
            }
        }

        /**
         * Creates a list of [Provider] from the given [Cursor].
         */
        private fun getListOfProviders(
            cursor: Cursor
        ): List<Provider> {
            val result: MutableList<Provider> = mutableListOf<Provider>()
            if (cursor.moveToFirst()) {
                do {
                    result.add(
                        Provider(
                            cursor.getString(cursor.getColumnIndex(
                                AVAILABLE_PROVIDER_AUTHORITY_COLUMN)),
                            MediaSource.valueOf(
                                cursor.getString(cursor.getColumnIndex(
                                    AVAILABLE_PROVIDER_MEDIA_SOURCE_COLUMN))),
                            cursor.getInt(cursor.getColumnIndex(AVAILABLE_PROVIDER_UID_COLUMN)),
                        )
                    )
                } while (cursor.moveToNext())
            }

            return result
        }
    }
}