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

import com.android.photopicker.core.banners.BannerStateDao
import org.mockito.Mockito.mock

/**
 * This is a test implementation of [DatabaseManager] that will isolate the device state and mock
 * out any database interactions.
 */
class DatabaseManagerTestImpl() : DatabaseManager {

    val bannerState = mock(BannerStateDao::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun <T> acquireDao(daoClass: Class<T>): T {
        with(daoClass) {
            return when {
                isAssignableFrom(BannerStateDao::class.java) -> bannerState as T
                else ->
                    throw IllegalArgumentException(
                        "Cannot acquire ${daoClass.simpleName} from DatabaseManagerImpl"
                    )
            }
        }
    }
}
