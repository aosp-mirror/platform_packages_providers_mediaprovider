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

package com.android.photopicker.features.preparemedia

import android.media.ApplicationMediaCapabilities
import android.media.MediaFeature.HdrType
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import com.android.photopicker.data.PICKER_SEGMENT
import com.android.photopicker.data.PICKER_TRANSCODED_SEGMENT
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Transcoder] */
@SmallTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
@RunWith(AndroidJUnit4::class)
class TranscoderTest {

    private val testTranscoder = TranscoderImpl()

    @Test
    fun testToPickerTranscodedUri() {
        val testUri =
            Uri.EMPTY.buildUpon()
                .apply {
                    scheme("content")
                    authority("media")
                    appendPath(PICKER_SEGMENT)
                    appendPath("user_id")
                    appendPath("a")
                    appendPath("media")
                    appendPath("video_id")
                }
                .build()
        val expectedUri =
            Uri.EMPTY.buildUpon()
                .apply {
                    scheme("content")
                    authority("media")
                    appendPath(PICKER_TRANSCODED_SEGMENT)
                    appendPath("user_id")
                    appendPath("a")
                    appendPath("media")
                    appendPath("video_id")
                }
                .build()

        assertWithMessage("Expected media URI to be converted to picker transcoded URI")
            .that(Transcoder.toTranscodedUri(testUri))
            .isEqualTo(expectedUri)
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenDurationOverLimit() {
        val testMediaCapabilities = ApplicationMediaCapabilities.Builder().build()
        val testMediaFormat = MediaFormat().apply { setLong(MediaFormat.KEY_DURATION, 90_000_000L) }

        assertWithMessage("Duration is over transcode limit")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenMediaFormatIsAudio() {
        val testMediaCapabilities = ApplicationMediaCapabilities.Builder().build()
        val testMediaFormat =
            MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC) }

        assertWithMessage("Audio do not need to be transcoded")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenHevcAndAppNotSpecifySupport() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addUnsupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
            }

        assertWithMessage("Not to transcode when App does not specify HEVC as supported")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenHevcAndAppSpecifySupport() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
            }

        assertWithMessage("Do not need to transcode when App support HEVC")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnTrue_whenDolbyVisionAndAppCanNotHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addUnsupportedHdrType(HdrType.DOLBY_VISION)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }

        assertWithMessage("Need to transcode when App cannot handle Dolby Vision")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isTrue()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenDolbyVisionAndAppCanHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addSupportedHdrType(HdrType.DOLBY_VISION)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }

        assertWithMessage("Do not need to transcode when App can handle Dolby Vision")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnTrue_wheHlgAndAppCanNotHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addUnsupportedHdrType(HdrType.HLG)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }

        assertWithMessage("Need to transcode when App cannot handle HLG")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isTrue()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenHlgAndAppCanHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addSupportedHdrType(HdrType.HLG)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }

        assertWithMessage("Do not need to transcode when App can handle HLG")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnTrue_wheHdr10AndAppCanNotHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addUnsupportedHdrType(HdrType.HDR10)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }

        assertWithMessage("Need to transcode when App cannot handle HDR10")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isTrue()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenHdr10AndAppCanHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addSupportedHdrType(HdrType.HDR10)
                .addSupportedHdrType(HdrType.HDR10_PLUS)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }

        assertWithMessage("Do not need to transcode when App can handle HDR10")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }

    @Test
    fun testIsTranscodeRequired_returnTrue_wheHdr10PlusAndAppCanNotHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addUnsupportedHdrType(HdrType.HDR10_PLUS)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }

        assertWithMessage("Need to transcode when App cannot handle HDR10+")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isTrue()
    }

    @Test
    fun testIsTranscodeRequired_returnFalse_whenHdr10PlusAndAppCanHandle() {
        val testMediaCapabilities =
            ApplicationMediaCapabilities.Builder()
                .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .addSupportedHdrType(HdrType.HDR10)
                .addSupportedHdrType(HdrType.HDR10_PLUS)
                .build()
        val testMediaFormat =
            MediaFormat().apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }

        assertWithMessage("Do not need to transcode when App can handle HDR10+")
            .that(testTranscoder.isTranscodeRequired(testMediaFormat, testMediaCapabilities))
            .isFalse()
    }
}
