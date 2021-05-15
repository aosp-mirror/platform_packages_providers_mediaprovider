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

import android.provider.MediaStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Base class representing one single entity/item (image/video) in the PhotoPicker.
 *
 */
public class Item {
    public static class ItemColumns {

        public static String ID = MediaStore.MediaColumns._ID;
        public static String MIME_TYPE = MediaStore.MediaColumns.MIME_TYPE;
        public static String DISPLAY_NAME = MediaStore.MediaColumns.DISPLAY_NAME;
        public static String VOLUME_NAME = MediaStore.MediaColumns.VOLUME_NAME;
        public static String DATE_TAKEN = MediaStore.MediaColumns.DATE_TAKEN;
        public static String USER_ID = MediaStore.Files.FileColumns._USER_ID;

        private static final String[] ALL_COLUMNS = {
                ItemColumns.ID,
                ItemColumns.MIME_TYPE,
                ItemColumns.DISPLAY_NAME,
                ItemColumns.VOLUME_NAME,
                ItemColumns.DATE_TAKEN,
                // TODO: Unable to query USER_ID
                // ItemColumns.USER_ID,
        };
        public static List<String> ALL_COLUMNS_LIST = Collections.unmodifiableList(
                Arrays.asList(ALL_COLUMNS));
    }
}
