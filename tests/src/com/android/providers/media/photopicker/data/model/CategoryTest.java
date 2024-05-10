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

import static android.provider.CloudMediaProviderContract.AlbumColumns;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.data.ItemsProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CategoryTest {

    private static final String LOCAL_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";
    @Test
    public void testConstructor() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final int itemCount = 10;
        final String categoryName = "Album";
        final String coverId = "52";
        final String categoryId = "Album";
        final boolean categoryIsLocal = true;
        final Cursor cursor = generateCursorForCategory(categoryId, categoryName,
                coverId, itemCount, categoryIsLocal);
        cursor.moveToFirst();

        final Category category = Category.fromCursor(cursor, UserId.CURRENT_USER);

        assertThat(category.getDisplayName(context)).isEqualTo(categoryName);
        assertThat(category.getAuthority()).isEqualTo(LOCAL_PROVIDER_AUTHORITY);
        assertThat(category.isLocal()).isEqualTo(categoryIsLocal);
        assertThat(category.getItemCount()).isEqualTo(itemCount);
        assertThat(category.getCoverUri()).isEqualTo(ItemsProvider.getItemsUri(coverId,
                        /* authority */ LOCAL_PROVIDER_AUTHORITY, UserId.CURRENT_USER));
        assertThat(category.getId()).isEqualTo(categoryId);
    }

    private static Cursor generateCursorForCategory(String categoryId, String categoryName,
            String coverId, int itemCount, boolean isLocal) {
        final MatrixCursor cursor = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
        cursor.addRow(new Object[] {categoryId, 1, categoryName, coverId, itemCount,
                LOCAL_PROVIDER_AUTHORITY});
        Bundle extras = new Bundle();
        extras.putString(MediaStore.EXTRA_LOCAL_PROVIDER, LOCAL_PROVIDER_AUTHORITY);
        cursor.setExtras(extras);
        return cursor;
    }
}
