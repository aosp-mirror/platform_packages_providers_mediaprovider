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

package com.android.providers.media.photopicker.data.model;

import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;

import android.provider.MediaStore;

public class ModelTestUtils {
    private static final String MIME_TYPE_JPEG = "image/jpeg";

    /**
     * Generate the {@link Item}
     * @param id the id
     * @param mimeType the mime type
     * @param dateTaken the time of date taken
     * @param generationModified the generation number associated with the media
     * @param duration the duration
     * @return the Item
     */
    public static Item generateItem(String id, String mimeType, long dateTaken,
            long generationModified, long duration) {
        return new Item(id, mimeType, dateTaken, generationModified, duration,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, Long.parseLong(id)),
                _SPECIAL_FORMAT_NONE);
    }

    /**
     * Generate the {@link Item}
     * @param id the id
     * @param mimeType the mime type
     * @param dateTaken the time of date taken
     * @param generationModified the generation number associated with the media
     * @param duration the duration
     * @param specialFormat the special format. See
     * {@link MediaStore.Files.FileColumns#_SPECIAL_FORMAT}
     * @return the Item
     */
    public static Item generateSpecialFormatItem(String id, String mimeType, long dateTaken,
            long generationModified, long duration, int specialFormat) {
        return new Item(id, mimeType, dateTaken, generationModified, duration,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, Long.parseLong(id)),
                specialFormat);
    }

    /** Generate the JPEG {@link Item} */
    public static Item generateJpegItem(String id, long dateTaken, long generationModified) {
        return generateItem(id, MIME_TYPE_JPEG, dateTaken, generationModified, /* duration */ 1000);
    }
}
