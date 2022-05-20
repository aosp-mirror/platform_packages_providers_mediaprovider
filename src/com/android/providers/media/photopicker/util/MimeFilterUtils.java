/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.util;

import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;

import android.content.Intent;

import androidx.annotation.Nullable;

/**
 * Utility class for various mime filter checks.
 */
public class MimeFilterUtils {

    /**
     * Checks if mime type filters set via {@link Intent#setType(String)} and
     * {@link Intent#EXTRA_MIME_TYPES} on the intent requires more than media items.
     *
     * @param intent the intent to check mimeType filters of
     */
    public static boolean requiresMoreThanMediaItems(Intent intent) {
        // EXTRA_MIME_TYPES has higher priority over getType() filter.
        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            return requiresMoreThanMediaItems(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        }

        // GET_CONTENT intent filter catches "images/*", "video/*" and "*/*" mimeTypes only
        // No need to check for other types like "audio/*" etc as they never reach PhotoPicker
        return intent.getType().equals("*/*");
    }

    /**
     * Checks if the given string is an image or video mime type
     */
    public static boolean isMimeTypeMedia(@Nullable String mimeType) {
        return isImageMimeType(mimeType) || isVideoMimeType(mimeType);
    }

    /**
     * Extracts relevant mime type filter for the given intent
     */
    public static String getMimeTypeFilter(Intent intent) {
        final String mimeType = intent.getType();
        if (MimeFilterUtils.isMimeTypeMedia(mimeType)) {
            return mimeType;
        }
        return null;
    }

    private static boolean requiresMoreThanMediaItems(String[] mimeTypeFilters) {
        // no filters imply that we should show non-media files as well
        if (mimeTypeFilters == null || mimeTypeFilters.length == 0) {
            return true;
        }

        for (String mimeTypeFilter : mimeTypeFilters) {
            if (!MimeFilterUtils.isMimeTypeMedia(mimeTypeFilter)) {
                return true;
            }
        }

        return false;
    }
}
