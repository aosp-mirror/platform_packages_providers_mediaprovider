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

import androidx.room.Database
import androidx.room.RoomDatabase
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.banners.BannerStateDao

/**
 * A [Room] database for persisting data.
 *
 * Add new @Entity classes to the [entities] mapping, and increment the schema version. Any new @Dao
 * interfaces need to be added to this abstract class so that the Room library will generate a
 * matching implementation.
 *
 * A schema will be generated in packages/providers/MediaProvider/photopicker/schemas when
 * Photopicker is compiled, and be sure to commit any schema changes to source control for managing
 * migrations between versions.
 */
@Database(entities = [BannerState::class], version = 1)
abstract class PhotopickerDatabase : RoomDatabase() {
    abstract fun bannerStateDao(): BannerStateDao
}
