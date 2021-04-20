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

package com.android.providers.media.photopicker.data.model;

import android.annotation.IntDef;
import android.provider.MediaStore;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class Album {
    /**
     * Photo Picker categorises images/videos into these buckets called album types.
     *
     */
    @IntDef(flag = true, prefix = { "ALBUM_" }, value = {
            ALBUM_SCREENSHOTS,
            ALBUM_CAMERA,
            ALBUM_VIDEOS,
            ALBUM_FAVORITES,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AlbumType {}

    /**
     * Includes images that are present in the Screenshot folder.
     */
    static final int ALBUM_SCREENSHOTS = 1;

    /**
     * Includes images/videos that are present in the DCIM/Camera folder.
     */
    static final int ALBUM_CAMERA = 2;

    /**
     * Includes videos only.
     */
    static final int ALBUM_VIDEOS = 3;

    /**
     * Includes images/videos that have {@link MediaStore.MediaColumns#IS_FAVORITE} set.
     */
    static final int ALBUM_FAVORITES = 4;
}
