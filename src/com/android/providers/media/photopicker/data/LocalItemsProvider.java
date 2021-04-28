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

package com.android.providers.media.photopicker.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;

/**
 * Provides local image and video items from {@link MediaStore} collection to the Photo Picker.
 * <p>
 * This class is responsible for fetching data from {@link MediaStore} collection and
 * providing the data to the data model for Photo Picker.
 * This class will obtain information about images and videos stored on the device by querying
 * {@link MediaStore} database.
 * <p>
 * This class *only* provides data on the images and videos that are stored on external storage.
 *
 */
public class LocalItemsProvider {

    /**
     * Returns a {@link Cursor} to all images/videos that are scanned by {@link MediaStore}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by lastModified.
     *
     * @return {@link Cursor} to all images/videos on external storage that are scanned by
     * {@link MediaStore}, or {@code null} if there are no such images/videos.
     * The Cursor for each item would contain the following columns in their relative order:
     * id: {@link MediaColumns#_ID} id for the image/video item,
     * path: {@link MediaColumns#DATA} path of the image/video item,
     * mime_type: {@link MediaColumns#MIME_TYPE} Mime type of the image/video item,
     * is_favorite {@link MediaColumns#IS_FAVORITE} column value of the image/video item.
     *
     */
     @Nullable
     public Cursor getItems() {
        Cursor res = null;
        return res;
    }

    /**
     * Returns a {@link Cursor} to all images/videos that are scanned by {@link MediaStore}
     * based on the param passed for {@code mimeType}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by lastModified.
     *
     * @param mimeType the type of item, only images or videos is an acceptable mimeType here.
     *                 Any other mimeType than image/video returns all images and videos by default.
     *
     * @return {@link Cursor} to all images/videos on external storage that are scanned by
     * {@link MediaStore} based on {@code mimeType}, or {@code null} if there are no such
     * images/videos.
     * The Cursor for each item would contain the following columns in their relative order:
     * id: {@link MediaColumns#_ID} id for the image/video item,
     * path: {@link MediaColumns#DATA} path of the image/video item,
     * mime_type: {@link MediaColumns#MIME_TYPE} Mime type of the image/video item,
     * is_favorite {@link MediaColumns#IS_FAVORITE} column value of the image/video item.
     *
     */
    @Nullable
    public Cursor getItems(@NonNull String mimeType) {
        Cursor res = null;
        return res;
    }
}
