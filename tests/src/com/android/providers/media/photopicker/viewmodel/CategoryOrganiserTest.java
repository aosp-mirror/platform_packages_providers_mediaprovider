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

package com.android.providers.media.photopicker.viewmodel;

import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

import static com.google.common.truth.Truth.assertThat;

import android.provider.CloudMediaProviderContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.util.CategoryOrganiserUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test to ensure that the CategoryOrganiser reorders categories in the required way.
 */
@RunWith(AndroidJUnit4.class)
public class CategoryOrganiserTest {

    @Test
    public void test_categoryOrder_meetsRequirements() {
        List<Category> inputCategoryList = new ArrayList() {
            {
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        "TestCategory1",
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        "TestCategory2",
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        "TestCategory3",
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
            }
        };

        // Expected list contains all the categories in the input list but in the required order,
        // the order of custom categories is maintained.
        List<Category> expectedCategoryList = new ArrayList() {
            {
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS,
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        "TestCategory1",
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        "TestCategory2",
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
                add(new Category(
                        "TestCategory3",
                        LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true));
            }
        };

        // perform reorder.
        CategoryOrganiserUtils.getReorganisedCategoryList(inputCategoryList);

        assertThat(inputCategoryList).isNotNull();
        assertThat(inputCategoryList.size()).isEqualTo(expectedCategoryList.size());
        for (int itr = 0; itr < inputCategoryList.size(); itr++) {
            assertThat(inputCategoryList.get(itr).getId()).isEqualTo(
                    expectedCategoryList.get(itr).getId());
        }
    }
}
