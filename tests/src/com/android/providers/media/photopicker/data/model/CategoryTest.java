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

import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_CAMERA;
import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_DEFAULT;
import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_DOWNLOADS;
import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_FAVORITES;
import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_SCREENSHOTS;
import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_VIDEOS;
import static com.android.providers.media.photopicker.data.model.Category.CategoryColumns;
import static com.android.providers.media.photopicker.data.model.Category.CategoryType;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.data.ItemsProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CategoryTest {

    @Test
    public void testConstructor() {
        final int itemCount = 10;
        final String categoryName = "Album";
        final String coverId = "52";
        final String categoryType = CATEGORY_SCREENSHOTS;
        final Cursor cursor = generateCursorForCategory(categoryName, coverId, itemCount,
                categoryType);
        cursor.moveToFirst();

        final Category category = new Category(cursor, UserId.CURRENT_USER);

        assertThat(category.getCategoryName(/* context= */ null)).isEqualTo(categoryName);
        assertThat(category.getItemCount()).isEqualTo(itemCount);
        assertThat(category.getCoverUri()).isEqualTo(ItemsProvider.getItemsUri(coverId,
                        /* authority */ null, UserId.CURRENT_USER));
        assertThat(category.getCategoryType()).isEqualTo(categoryType);
    }

    /**
     * If the {@code category} is not in {@link Category#CATEGORIES}, return {@code null}.
     */
    @Test
    public void testGetCategoryName_notInList_returnNull() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String categoryName = Category.getCategoryName(context, CATEGORY_DEFAULT);

        assertThat(categoryName).isNull();
    }

    @Test
    public void testGetCategoryName_camera() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String categoryName = Category.getCategoryName(context, CATEGORY_CAMERA);

        assertThat(categoryName).isEqualTo("Camera");
    }

    @Test
    public void testGetCategoryName_downloads() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String categoryName = Category.getCategoryName(context, CATEGORY_DOWNLOADS);

        assertThat(categoryName).isEqualTo("Downloads");
    }

    @Test
    public void testGetCategoryName_favorites() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String categoryName = Category.getCategoryName(context, CATEGORY_FAVORITES);

        assertThat(categoryName).isEqualTo("Favorites");
    }

    @Test
    public void testGetCategoryName_screenshots() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String categoryName = Category.getCategoryName(context, CATEGORY_SCREENSHOTS);

        assertThat(categoryName).isEqualTo("Screenshots");
    }

    @Test
    public void testGetCategoryName_videos() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String categoryName = Category.getCategoryName(context, CATEGORY_VIDEOS);

        assertThat(categoryName).isEqualTo("Videos");
    }

    @Test
    public void testGetDefaultCategory() {
        final Category category = Category.getDefaultCategory();

        assertThat(category.getCategoryType()).isEqualTo(CATEGORY_DEFAULT);
    }

    @Test
    public void testOrderOfCategories() {
        final List<String> categoryList = Category.CATEGORIES_LIST;

        // Favorites, Camera, Videos, ScreenShots, Downloads
        assertThat(categoryList.get(0)).isEqualTo(CATEGORY_FAVORITES);
        assertThat(categoryList.get(1)).isEqualTo(CATEGORY_CAMERA);
        assertThat(categoryList.get(2)).isEqualTo(CATEGORY_VIDEOS);
        assertThat(categoryList.get(3)).isEqualTo(CATEGORY_SCREENSHOTS);
        assertThat(categoryList.get(4)).isEqualTo(CATEGORY_DOWNLOADS);
    }

    private static Cursor generateCursorForCategory(String categoryName, String coverId,
            int itemCount, @CategoryType String categoryType) {
        final MatrixCursor cursor = new MatrixCursor(CategoryColumns.getAllColumns());
        cursor.addRow(new Object[] {categoryName, coverId, itemCount, categoryType});
        return cursor;
    }
}
