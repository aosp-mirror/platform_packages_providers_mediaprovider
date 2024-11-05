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

package com.android.photopicker.features.preparemedia

import android.content.Context
import android.media.ApplicationMediaCapabilities
import android.media.MediaFeature.HdrType
import android.media.MediaFormat
import android.media.MediaFormat.COLOR_STANDARD_BT2020
import android.media.MediaFormat.COLOR_STANDARD_BT709
import android.media.MediaFormat.COLOR_TRANSFER_HLG
import android.media.MediaFormat.COLOR_TRANSFER_ST2084
import android.media.MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
import android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.MediaFormatUtil.createFormatFromMediaFormat
import androidx.media3.common.util.MediaFormatUtil.isVideoFormat
import androidx.media3.exoplayer.MediaExtractorCompat
import com.android.photopicker.data.model.Media

/** A class that help video transcode. */
class TranscoderImpl : Transcoder {

    override fun isTranscodeRequired(
        context: Context,
        mediaCapabilities: ApplicationMediaCapabilities?,
        video: Media.Video,
    ): Boolean {
        if (mediaCapabilities == null) {
            return false
        }

        if (video.duration > DURATION_LIMIT_MS) {
            Log.w(TAG, "Duration (${video.duration} ms) is over limit ($DURATION_LIMIT_MS).")
            return false
        }

        // Check if any video tracks need to be transcoded.
        val videoTrackMediaFormats = getVideoTrackMediaFormats(context, video)
        for (mediaFormat in videoTrackMediaFormats) {
            if (isTranscodeRequired(mediaFormat, mediaCapabilities)) {
                return true
            }
        }

        return false
    }

    /**
     * Gets the [MediaFormat]s of the video tracks in the given video.
     *
     * @param context The context.
     * @param video The video to check.
     * @return The [MediaFormat]s of the video tracks in the given video.
     */
    private fun getVideoTrackMediaFormats(context: Context, video: Media.Video): List<MediaFormat> {
        val mediaFormats = mutableListOf<MediaFormat>()

        try {
            val extractor = MediaExtractorCompat(context)
            extractor.setDataSource(video.mediaUri, 0)

            for (index in 0..<extractor.trackCount) {
                val mediaFormat = extractor.getTrackFormat(index)
                if (isVideoFormat(mediaFormat)) {
                    mediaFormats.add(mediaFormat)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MediaFormat of URI (${video.mediaUri}).", e)
        }

        return mediaFormats
    }

    /**
     * Checks if a transcode is required for the given [MediaFormat].
     *
     * @param mediaFormat The [MediaFormat] to check.
     * @return True if a transcode is required for the given [MediaFormat], false otherwise.
     */
    @VisibleForTesting
    fun isTranscodeRequired(
        mediaFormat: MediaFormat,
        mediaCapabilities: ApplicationMediaCapabilities,
    ): Boolean {
        val format = createFormatFromMediaFormat(mediaFormat)
        val mimeType = format.sampleMimeType
        val colorStandard = format.colorInfo?.colorSpace
        val colorTransfer = format.colorInfo?.colorTransfer

        with(mediaCapabilities) {
            if (isHevc(mimeType)) {
                // Not to transcode when App does not support HEVC, as it is indistinguishable
                // from the 'unset' case. Transcode for "HEVC -> AVC for SDR content" might not be
                // what the caller intended.

                if (isHlg10(colorStandard, colorTransfer) && !isHdrTypeSupported(HdrType.HLG)) {
                    return true
                }

                if (
                    isHdr10OrHdr10Plus(colorStandard, colorTransfer) &&
                        (!isHdrTypeSupported(HdrType.HDR10) ||
                            !isHdrTypeSupported(HdrType.HDR10_PLUS))
                ) {
                    return true
                }
            }

            if (
                isHdrDolbyVision(mimeType, colorStandard, colorTransfer) &&
                    !isHdrTypeSupported(HdrType.DOLBY_VISION)
            ) {
                return true
            }
        }

        return false
    }

    companion object {
        private const val TAG = "Transcoder"
        @VisibleForTesting const val DURATION_LIMIT_MS = 60_000L // 1 min

        /**
         * Checks if the mime type is HEVC.
         *
         * @param mimeType The mime type.
         * @return True if the mime type is HEVC, false otherwise.
         */
        private fun isHevc(mimeType: String?): Boolean {
            return MIMETYPE_VIDEO_HEVC.equals(mimeType, ignoreCase = true)
        }

        /**
         * Checks if the given parameters represent HLG.
         *
         * @param colorStandard The color standard.
         * @param colorTransfer The color transfer.
         * @return True if the parameters represent HLG, false otherwise.
         */
        private fun isHlg10(colorStandard: Int?, colorTransfer: Int?): Boolean {
            return (colorStandard == COLOR_STANDARD_BT709 ||
                colorStandard == COLOR_STANDARD_BT2020) && colorTransfer == COLOR_TRANSFER_HLG
        }

        /**
         * Checks if the given parameters represent HDR10 or HDR10+.
         *
         * @param colorStandard The color standard.
         * @param colorTransfer The color transfer.
         * @return True if the parameters represent HDR10 or HDR10+, false otherwise.
         */
        private fun isHdr10OrHdr10Plus(colorStandard: Int?, colorTransfer: Int?): Boolean {
            return colorStandard == COLOR_STANDARD_BT2020 && colorTransfer == COLOR_TRANSFER_ST2084
        }

        /**
         * Checks if the given parameters represent HDR Dolby Vision.
         *
         * @param mimeType The mime type.
         * @param colorStandard The color standard.
         * @param colorTransfer The color transfer.
         * @return True if the parameters represent HDR Dolby Vision, false otherwise.
         */
        private fun isHdrDolbyVision(
            mimeType: String?,
            colorStandard: Int?,
            colorTransfer: Int?,
        ): Boolean {
            return (MIMETYPE_VIDEO_DOLBY_VISION.equals(mimeType, ignoreCase = true)) &&
                COLOR_STANDARD_BT2020 == colorStandard &&
                (colorTransfer == COLOR_TRANSFER_ST2084 || colorTransfer == COLOR_TRANSFER_HLG)
        }
    }
}
