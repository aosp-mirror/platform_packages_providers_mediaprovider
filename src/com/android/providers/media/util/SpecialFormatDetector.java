/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.provider.MediaStore;

/**
 * Class to detect and return special format for a media file.
 */
public class SpecialFormatDetector {
    /**
     * Place holder method to return special format for a file
     */
    public static int detect() {
        // TODO (b/202396821): Add implementation for GIF and MOTION_PHOTO detection
        return MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;
    }
}
