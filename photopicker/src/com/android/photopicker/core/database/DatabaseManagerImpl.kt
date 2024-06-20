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

package com.android.photopicker.core.database

import android.content.Context
import com.android.photopicker.PhotopickerApplication
import com.android.photopicker.core.banners.BannerStateDao

/**
 * This is a prod implementation that relies on an actual backing database.
 *
 * @param appContext The application context required to connect to the database.
 */
class DatabaseManagerImpl(appContext: Context) : DatabaseManager {

    /**
     * A running database connection to the [PhotopickerDatabase]. This is a wrapper that the room
     * library puts around the database to manage connection pooling, and read/write access
     */
    private val database: PhotopickerDatabase

    init {
        // The [PhotopickerDatabase] instance is created during Application#onCreate
        // so a reference of it can be fetched from the application.
        val application = appContext as? PhotopickerApplication
        checkNotNull(application) {
            "PhotopickerApplication context was not provided to DatabaseManager"
        }
        database = application.database
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> acquireDao(daoClass: Class<T>): T {
        with(daoClass) {
            return when {
                isAssignableFrom(BannerStateDao::class.java) -> database.bannerStateDao() as T
                else ->
                    throw IllegalArgumentException(
                        "Cannot acquire ${daoClass.simpleName} from DatabaseManagerImpl"
                    )
            }
        }
    }
}
