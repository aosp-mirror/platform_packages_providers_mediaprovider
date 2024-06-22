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

package com.android.photopicker.core.banners

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * A [PhotopickerDatabase] table related to the persisted state of an individual banner.
 *
 * This table uses a composite key of bannerId,uid to enforce uniqueness.
 *
 * @property bannerId The id of the banner the row is referring to.
 * @property uid the UID of the app the row is referring to. Zero (0) is used for "all apps /
 *   global"
 * @property dismissed Whether the banner has been dismissed by the user.
 */
@Entity(tableName = "banner_state", primaryKeys = ["bannerId", "uid"])
data class BannerState(
    val bannerId: String,
    val uid: Int,
    val dismissed: Boolean,
)

/** An interface to read and write rows from the [BannerState] table. */
@Dao
interface BannerStateDao {

    /**
     * Read a row for a specific banner / app combination.
     *
     * @param bannerId the Id of the banner
     * @param uid The UID of the app to check the state of this banner for. Zero(0) should be used
     *   for "global".
     * @return The row, if it exists. If it does not exist, null is returned instead.
     */
    @Query("SELECT * from banner_state WHERE bannerId=:bannerId AND uid = :uid")
    fun getBannerState(bannerId: String, uid: Int?): BannerState?

    /**
     * Write a row for a specific [BannerState].
     *
     * This is an upsert method that will first try to insert the row, but will update the existing
     * row on primary key conflict.
     *
     * @param bannerState The row to write to the database.
     */
    @Upsert fun setBannerState(bannerState: BannerState)
}
