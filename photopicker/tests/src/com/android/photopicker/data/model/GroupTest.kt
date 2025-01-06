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

package src.com.android.photopicker.data.model

import android.net.Uri
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.MediaSource
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Group] data models */
@SmallTest
@RunWith(AndroidJUnit4::class)
class GroupTest {
    /** Write to parcel as a [Group.Album], read back as a [Group.Album] */
    @Test
    fun testGroupAlbumIsParcelable() {
        val testAlbum =
            Group.Album(
                id = "album_id",
                pickerId = 123456789L,
                authority = "authority",
                dateTakenMillisLong = 123456789L,
                displayName = "album name",
                coverMediaSource = MediaSource.LOCAL,
                coverUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("authority")
                            path("image_id")
                        }
                        .build(),
            )

        val parcel = Parcel.obtain()
        testAlbum.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultAlbum = Group.Album.createFromParcel(parcel)
        assertWithMessage("Album was different when parcelled")
            .that(resultAlbum)
            .isEqualTo(testAlbum)

        parcel.recycle()
    }

    /** Write to parcel as a [Group.Category], read back as a [Group.Category] */
    @Test
    fun testGroupCategoryIsParcelable() {
        val testCategory =
            Group.Category(
                id = "category_id",
                pickerId = 123456789L,
                authority = "authority",
                displayName = "category name",
                categoryType = CategoryType.PEOPLE_AND_PETS,
                icons =
                    listOf(
                        Icon(
                            uri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority("authority")
                                        path("image1")
                                    }
                                    .build(),
                            mediaSource = MediaSource.LOCAL,
                        ),
                        Icon(
                            uri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority("authority")
                                        path("image2")
                                    }
                                    .build(),
                            mediaSource = MediaSource.REMOTE,
                        ),
                    ),
                isLeafCategory = true,
            )

        val parcel = Parcel.obtain()
        testCategory.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultCategory = Group.Category.createFromParcel(parcel)
        assertWithMessage("Category was different when parcelled")
            .that(resultCategory)
            .isEqualTo(testCategory)

        parcel.recycle()
    }

    /** Write to parcel as a [Group.MediaSet], read back as a [Group.MediaSet] */
    @Test
    fun testGroupMediaSetIsParcelable() {
        val testMediaSet =
            Group.MediaSet(
                id = "media_set_id",
                pickerId = 123456789L,
                authority = "authority",
                displayName = "media set name",
                icon =
                    Icon(
                        uri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority("authority")
                                    path("image1")
                                }
                                .build(),
                        mediaSource = MediaSource.LOCAL,
                    ),
            )

        val parcel = Parcel.obtain()
        testMediaSet.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultMediaSet = Group.MediaSet.createFromParcel(parcel)
        assertWithMessage("Media Set was different when parcelled")
            .that(resultMediaSet)
            .isEqualTo(testMediaSet)

        parcel.recycle()
    }
}
