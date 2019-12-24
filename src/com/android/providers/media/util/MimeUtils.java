/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import android.content.ClipDescription;
import android.mtp.MtpConstants;
import android.provider.MediaStore.Files.FileColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

public class MimeUtils {
    /**
     * Resolve the MIME type of the given file, returning
     * {@code application/octet-stream} if the type cannot be determined.
     */
    public static @NonNull String resolveMimeType(@NonNull File file) {
        final String extension = FileUtils.extractFileExtension(file.getPath());
        if (extension == null) return ClipDescription.MIMETYPE_UNKNOWN;

        final String mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
        if (mimeType == null) return ClipDescription.MIMETYPE_UNKNOWN;

        return mimeType;
    }

    /**
     * Resolve the {@link FileColumns#MEDIA_TYPE} of the given MIME type. This
     * carefully checks for more specific types before generic ones, such as
     * treating {@code audio/mpegurl} as a playlist instead of an audio file.
     */
    public static int resolveMediaType(@NonNull String mimeType) {
        if (isPlaylistMimeType(mimeType)) {
            return FileColumns.MEDIA_TYPE_PLAYLIST;
        } else if (isSubtitleMimeType(mimeType)) {
            return FileColumns.MEDIA_TYPE_SUBTITLE;
        } else if (isAudioMimeType(mimeType)) {
            return FileColumns.MEDIA_TYPE_AUDIO;
        } else if (isVideoMimeType(mimeType)) {
            return FileColumns.MEDIA_TYPE_VIDEO;
        } else if (isImageMimeType(mimeType)) {
            return FileColumns.MEDIA_TYPE_IMAGE;
        } else {
            return FileColumns.MEDIA_TYPE_NONE;
        }
    }

    /**
     * Resolve the {@link FileColumns#FORMAT} of the given MIME type. Note that
     * since this column isn't public API, we're okay only getting very rough
     * values in place, and it's not worthwhile to build out complex matching.
     */
    public static int resolveFormatCode(@Nullable String mimeType) {
        switch (resolveMediaType(mimeType)) {
            case FileColumns.MEDIA_TYPE_AUDIO:
                return MtpConstants.FORMAT_UNDEFINED_AUDIO;
            case FileColumns.MEDIA_TYPE_VIDEO:
                return MtpConstants.FORMAT_UNDEFINED_VIDEO;
            case FileColumns.MEDIA_TYPE_IMAGE:
                return MtpConstants.FORMAT_DEFINED;
            default:
                return MtpConstants.FORMAT_UNDEFINED;
        }
    }

    public static @NonNull String extractPrimaryType(@NonNull String mimeType) {
        final int slash = mimeType.indexOf('/');
        if (slash == -1) {
            throw new IllegalArgumentException();
        }
        return mimeType.substring(0, slash);
    }

    public static boolean isAudioMimeType(@Nullable String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("audio/");
    }

    public static boolean isVideoMimeType(@Nullable String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("video/");
    }

    public static boolean isImageMimeType(@Nullable String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/");
    }

    public static boolean isPlaylistMimeType(@Nullable String mimeType) {
        if (mimeType == null) return false;
        switch (mimeType) {
            case "application/vnd.apple.mpegurl":
            case "application/vnd.ms-wpl":
            case "application/x-extension-smpl":
            case "application/x-mpegurl":
            case "audio/mpegurl":
            case "audio/x-mpegurl":
            case "audio/x-scpls":
                return true;
            default:
                return false;
        }
    }

    public static boolean isSubtitleMimeType(@Nullable String mimeType) {
        if (mimeType == null) return false;
        switch (mimeType) {
            case "application/lrc":
            case "application/smil+xml":
            case "application/ttml+xml":
            case "application/x-extension-cap":
            case "application/x-extension-srt":
            case "application/x-extension-sub":
            case "application/x-extension-vtt":
            case "text/vtt":
                return true;
            default:
                return false;
        }
    }
}
