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

package com.android.photopicker.data.model

import android.net.Uri
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Media] data models */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaTest {

    /** Write to parcel as a [Media.Image], read back as a [Media.Image] */
    @Test
    fun testMediaImageIsParcelable() {

        val testImage =
            Media.Image(
                mediaId = "image_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        val parcel = Parcel.obtain()
        testImage.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultImage = Media.Image.createFromParcel(parcel)
        assertWithMessage("Image was different when parcelled")
            .that(resultImage)
            .isEqualTo(testImage)

        parcel.recycle()
    }

    /** Write to parcel as a [Media.Video], read back as a [Media.Video] */
    @Test
    fun testMediaVideoIsParcelable() {

        val testVideo =
            Media.Video(
                mediaId = "video_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        val parcel = Parcel.obtain()
        testVideo.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultVideo = Media.Video.createFromParcel(parcel)
        assertWithMessage("Video was different when parcelled")
            .that(resultVideo)
            .isEqualTo(testVideo)

        parcel.recycle()
    }

    @Test
    fun testImageHashCodeIsPredictable() {

        val testImage =
            Media.Image(
                mediaId = "image_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        val testImage2 =
            Media.Image(
                mediaId = "image_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        assertWithMessage("Different hashCode received for same input")
            .that(testImage.hashCode())
            .isEqualTo(testImage2.hashCode())
    }

    @Test
    fun testImageEquals() {

        val testImage =
            Media.Image(
                mediaId = "image_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        val testImage2 =
            Media.Image(
                mediaId = "image_id",
                pickerId = 987654321L, // intentionally different as this field is ignored by equals
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        assertWithMessage("Expected images to be equal").that(testImage).isEqualTo(testImage2)
    }

    @Test
    fun testImageNotEquals() {

        val testImage =
            Media.Image(
                mediaId = "image_id_2", // intentionally different id
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        val testImage2 =
            Media.Image(
                mediaId = "image_id",
                pickerId = 987654321L, // intentionally different as this field is ignored by equals
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("image_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("image_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
            )

        assertWithMessage("Expected images to be equal").that(testImage).isNotEqualTo(testImage2)
    }

    @Test
    fun testVideoHashCodeIsPredictable() {

        val testVideo =
            Media.Video(
                mediaId = "video_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        val testVideo2 =
            Media.Video(
                mediaId = "video_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                // Hashcode should not change with different timestamps
                dateTakenMillisLong = 123456789L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        assertWithMessage("Different hashCode received for same input")
            .that(testVideo.hashCode())
            .isEqualTo(testVideo2.hashCode())
    }

    @Test
    fun testVideoEquals() {

        val testVideo =
            Media.Video(
                mediaId = "video_id",
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        val testVideo2 =
            Media.Video(
                mediaId = "video_id",
                pickerId = 987654321L, // intentionally different as this field is ignored by equals
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        assertWithMessage("Expected videos to be equal").that(testVideo).isEqualTo(testVideo2)
    }

    @Test
    fun testVideoNotEquals() {

        val testVideo =
            Media.Video(
                mediaId = "video_id_12345", // intentionally different id
                pickerId = 123456789L,
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        val testVideo2 =
            Media.Video(
                mediaId = "video_id",
                pickerId = 987654321L, // intentionally different as this field is ignored by equals
                authority = "authority",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("video_id")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("video_id")
                        }
                        .build(),
                dateTakenMillisLong = 987654321L,
                sizeInBytes = 1000L,
                mimeType = "video/mp4",
                standardMimeTypeExtension = 1,
                duration = 123456,
            )

        assertWithMessage("Expected videos to be equal").that(testVideo).isNotEqualTo(testVideo2)
    }
}
