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

import android.annotation.StringDef;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.util.ArrayMap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Defines each category (which is group of items) for the photo picker.
 */
public class Category {

    /**
     * Photo Picker categorises images/videos into pre-defined buckets based on various criteria
     * (for example based on file path location items may be in {@link #CATEGORY_SCREENSHOTS} or
     * {@link #CATEGORY_CAMERA}, based on {@link FileColumns#MEDIA_TYPE}) items may be in
     * {@link #CATEGORY_VIDEOS}). This list is predefined for v0.
     *
     * TODO (b/187919236): Add Downloads/SDCard categories.
     */
    @StringDef(prefix = { "CATEGORY_" }, value = {
            CATEGORY_DEFAULT,
            CATEGORY_SCREENSHOTS,
            CATEGORY_CAMERA,
            CATEGORY_VIDEOS,
            CATEGORY_FAVORITES,
            CATEGORY_DOWNLOADS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CategoryType {}

    /**
     * Includes all images/videos on device that are scanned by {@link MediaStore}.
     */
    public static final String CATEGORY_DEFAULT = "default";

    /**
     * Includes images/videos that are present in the
     * {@link Environment#DIRECTORY_PICTURES}/{@link Environment#DIRECTORY_SCREENSHOTS} directory.
     *
     * TODO(b/192932740): Include media that contains {@link Environment#DIRECTORY_SCREENSHOTS}
     * in its relative_path.
     */
    public static final String CATEGORY_SCREENSHOTS = "Screenshots";
    private static final String SCREENSHOTS_WHERE_CLAUSE =
            MediaStore.MediaColumns.RELATIVE_PATH + " LIKE '" +
                    Environment.DIRECTORY_PICTURES + "/" +
                    Environment.DIRECTORY_SCREENSHOTS + "/%'";

    /**
     * Includes images/videos that are present in the {@link Environment#DIRECTORY_DCIM}/Camera
     * directory.
     */
    public static final String CATEGORY_CAMERA = "Camera";
    private static final String CAMERA_WHERE_CLAUSE =
            MediaStore.MediaColumns.RELATIVE_PATH + " LIKE '" +
                    Environment.DIRECTORY_DCIM + "/Camera/%'";

    /**
     * Includes videos only.
     */
    public static final String CATEGORY_VIDEOS = "Videos";
    private static final String VIDEOS_WHERE_CLAUSE = MediaStore.Files.FileColumns.MEDIA_TYPE +
            " = " + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

    /**
     * Includes images/videos that have {@link MediaStore.MediaColumns#IS_FAVORITE} set.
     */
    public static final String CATEGORY_FAVORITES = "Favorites";
    // TODO (b/188053832): Do not reveal implementation detail for is_favorite,
    // use MATCH_INCLUDE in queryArgs.
    private static final String FAVORITES_WHERE_CLAUSE =
            MediaStore.MediaColumns.IS_FAVORITE + " =1";

    /**
     * Includes images/videos that have {@link MediaStore.MediaColumns#IS_DOWNLOAD} set.
     */
    public static final String CATEGORY_DOWNLOADS = "Downloads";
    private static final String DOWNLOADS_WHERE_CLAUSE =
            MediaStore.MediaColumns.IS_DOWNLOAD + " =1";

    /**
     * Set of {@link Cursor} columns that refer to raw filesystem paths.
     */
    private static final ArrayMap<String, String> sCategoryWhereClause = new ArrayMap<>();

    static {
        sCategoryWhereClause.put(CATEGORY_SCREENSHOTS, SCREENSHOTS_WHERE_CLAUSE);
        sCategoryWhereClause.put(CATEGORY_CAMERA, CAMERA_WHERE_CLAUSE);
        sCategoryWhereClause.put(CATEGORY_VIDEOS, VIDEOS_WHERE_CLAUSE);
        sCategoryWhereClause.put(CATEGORY_FAVORITES, FAVORITES_WHERE_CLAUSE);
        sCategoryWhereClause.put(CATEGORY_DOWNLOADS, DOWNLOADS_WHERE_CLAUSE);
    }

    public static String getWhereClauseForCategory(@CategoryType String category) {
        return sCategoryWhereClause.get(category);
    }

    private static String[] CATEGORIES = {
            CATEGORY_SCREENSHOTS,
            CATEGORY_CAMERA,
            CATEGORY_VIDEOS,
            CATEGORY_FAVORITES,
            CATEGORY_DOWNLOADS
    };

    public static List<String> CATEGORIES_LIST = Collections.unmodifiableList(
            Arrays.asList(CATEGORIES));

    public static boolean isValidCategory(String category) {
        return CATEGORIES_LIST.contains(category);
    }

    /**
     * Defines category columns for each category
     */
    public static class CategoryColumns {
        public static String NAME = "name";
        public static String COVER_URI = "cover_uri";
        public static String NUMBER_OF_ITEMS = "number_of_items";

        public static String[] getAllColumns() {
            return new String[] {
                    NAME,
                    COVER_URI,
                    NUMBER_OF_ITEMS
            };
        }
    }
}
