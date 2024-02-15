/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;

import com.android.providers.media.photopicker.data.model.Category;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reorders categories per requirements.
 */
public class CategoryOrganiserUtils {
    static final int DEFAULT_PRIORITY = 100;
    static Map<String, Integer> sCategoryPriorityMapping;

    /**
     * Rearranges categoryList in the required order: Favourites, camera, videos,
     * screenshots, downloads, ... cloud albums ordered by last modified time stamp.
     */
    public static void getReorganisedCategoryList(List<Category> categoryList) {
        // Items having the same priority will not be modified in order.
        categoryList.sort(new CategoryComparator());
    }

    private static void populateCategoryPriorityMapping() {

        // DO NOT ALTER THIS ORDER.
        // These priorities decide the order in which the categories will be displayed on UI.
        sCategoryPriorityMapping = new HashMap<String, Integer>() {
            {
                put(ALBUM_ID_FAVORITES, 0);
                put(ALBUM_ID_CAMERA, 1);
                put(ALBUM_ID_VIDEOS, 2);
                put(ALBUM_ID_SCREENSHOTS, 3);
                put(ALBUM_ID_DOWNLOADS, 4);
            }
        };
    }

    private static int getPriority(Category category) {
        if (sCategoryPriorityMapping == null) {
            populateCategoryPriorityMapping();
        }
        if (sCategoryPriorityMapping.containsKey(category.getId())) {
            return sCategoryPriorityMapping.get(category.getId());
        }
        return DEFAULT_PRIORITY;
    }

    static class CategoryComparator implements java.util.Comparator<Category> {
        @Override
        public int compare(Category category1, Category category2) {
            return getPriority(category1) - getPriority(category2);
        }
    }
}
