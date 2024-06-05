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

package com.android.photopicker.tests.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Define an implementation of ContentProvider that stubs out all methods
 */
class StubProvider : ContentProvider() {

    companion object {
        val AUTHORITY = "stubprovider"

        /**
         * Return a list of stubbed [Media] that match the AUTHORITY string of this provider. Each
         * Media item returned will be unique.
         *
         * @param count The number of media to generate and return.
         * @return
         */
        fun getTestMediaFromStubProvider(count: Int): List<Media> {

            val currentDateTime = LocalDateTime.now()
            return buildList<Media>() {
                for (i in 1..count) {
                    add(
                        Media.Image(
                            mediaId = "$i",
                            pickerId = i.toLong(),
                            authority = AUTHORITY,
                            mediaSource = MediaSource.LOCAL,
                            mediaUri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority(AUTHORITY)
                                        path("$i")
                                    }
                                    .build(),
                            glideLoadableUri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority(AUTHORITY)
                                        path("$i")
                                    }
                                    .build(),
                            dateTakenMillisLong =
                                currentDateTime
                                    .minus(i.toLong(), ChronoUnit.DAYS)
                                    .toEpochSecond(ZoneOffset.UTC) * 1000,
                            sizeInBytes = 1000L,
                            mimeType = "image/png",
                            standardMimeTypeExtension = 1,
                        )
                    )
                }
            }
        }
    }
    /*
     * Always return true, indicating that the
     * provider loaded correctly.
     */
    override fun onCreate(): Boolean = true

    /*
     * Return no type for MIME type
     */
    override fun getType(uri: Uri): String? = null

    /*
     * query() always returns no results
     *
     */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    /*
     * insert() always returns null (no URI)
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    /*
     * delete() always returns "no rows affected" (0)
     */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    /*
     * update() always returns "no rows affected" (0)
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
