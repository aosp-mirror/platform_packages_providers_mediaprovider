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

import android.annotation.Nullable;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;

/**
 * The base class that is responsible for obtaining data from all providers and
 * merge the data together and provide it to ViewModel.
 */
public class ItemsProvider {
    private Context mContext;
    private LocalItemsProvider mLocalItemsProvider;

    public ItemsProvider(Context context) {
        mContext = context;
        mLocalItemsProvider = new LocalItemsProvider(mContext);
    }

    /**
     * Returns a {@link Cursor} to all images/videos that are provided by {@link LocalItemsProvider}
     *
     * <p>
     * Note: By default the returned {@link Cursor} sorts by {@link MediaColumns#DATE_TAKEN}.
     *
     * @param category the category of items to return, {@link Category.CategoryType} are supported.
     *                 {@code null} defaults to {@link Category#CATEGORY_DEFAULT} which returns
     *                 items from all categories.
     * @param offset the offset after which to return items.
     * @param limit the limit of number of items to return.
     * @param mimeType the mime type of item, only {@code image/*} or {@code video/*} is an
     *                 acceptable mimeType here. Any other mimeType than image/video throws error.
     *                 {@code null} returns all images/videos that are scanned by
     *                 {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get items as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}.
     *
     * @return {@link Cursor} to all images/videos on external storage that are scanned by
     * {@link MediaStore} based on params passed, or {@code null} if there are no such
     * images/videos. The Cursor for each item would contain {@link Item.ItemColumns}
     *
     * @throws IllegalArgumentException thrown if unsupported values for {@code mimeType},
     * {@code category} is passed.
     * @throws IllegalStateException thrown if unsupported value for {@code userId} is passed.
     */
    @Nullable
    public Cursor getItems(@Nullable String category, int offset, int limit,
            @Nullable String mimeType, @Nullable UserId userId) throws IllegalArgumentException,
            IllegalStateException {
        return mLocalItemsProvider.getItems(category, offset, limit, mimeType, userId);
    }

    /**
     * Returns a {@link Cursor} containing basic information (as columns:
     * {@link Category.CategoryColumns}) for non-empty categories.
     * A {@link Category} is a collection of items (images/videos) that are put into different
     * buckets based on various criteria as defined in {@link Category.CategoryType}.
     * This includes a list of constant categories for LocalItemsProvider: {@link Category} contains
     * a constant list of local categories supported in v0.
     *
     * @param userId the {@link UserId} of the user to get categories as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}.
     *
     * @return {@link Cursor} for each category would contain the following columns in
     * their relative order:
     * categoryName: {@link Category.CategoryColumns#NAME} The name of the category,
     * categoryCoverUri: {@link Category.CategoryColumns#COVER_URI} The Uri for the cover of
     *                   the category. By default this will be the most recent image/video in that
     *                   category,
     * categoryNumberOfItems: {@link Category.CategoryColumns#NUMBER_OF_ITEMS} number of image/video
     *                        items in the category,
     *
     * @throws IllegalStateException thrown if unsupported value for {@code userId} is passed.
     */
    @Nullable
    public Cursor getCategories(@Nullable UserId userId) {
        return mLocalItemsProvider.getCategories(userId);
    }
}
