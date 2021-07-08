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
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Category.CategoryColumns;
import com.android.providers.media.photopicker.data.model.Item.ItemColumns;
import com.android.providers.media.photopicker.data.model.UserId;

import java.util.List;

/**
 * Provides image and video items from {@link MediaStore} collection to the Photo Picker.
 */
public class ItemsProvider {

    private static final String IMAGES_VIDEOS_WHERE_CLAUSE = "( " +
            FileColumns.MEDIA_TYPE + " = " + FileColumns.MEDIA_TYPE_IMAGE + " OR "
            + FileColumns.MEDIA_TYPE + " = " + FileColumns.MEDIA_TYPE_VIDEO + " )";

    private final Context mContext;

    public ItemsProvider(Context context) {
        mContext = context;
    }

    /**
     * Returns a {@link Cursor} to all images/videos based on the param passed for
     * {@code categoryType}, {@code offset}, {@code limit}, {@code mimeType} and {@code userId}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by latest date taken.
     *
     * @param category the category of items to return, {@link Category.CategoryType} are supported.
     *                 {@code null} defaults to {@link Category#CATEGORY_DEFAULT} which returns
     *                 items from all categories.
     * @param offset the offset after which to return items.
     * @param limit the limit of number of items to return.
     * @param mimeType the mime type of item. {@code null} returns all images/videos that are
     *                 scanned by {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get items as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}
     *
     * @return {@link Cursor} to images/videos on external storage that are scanned by
     * {@link MediaStore}. The returned cursor is filtered based on params passed, it {@code null}
     * if there are no such images/videos. The Cursor for each item contains {@link ItemColumns}
     *
     * @throws IllegalArgumentException thrown if unsupported values for {@code category} is passed.
     * @throws IllegalStateException thrown if unsupported value for {@code userId} is passed.
     *
     */
    @Nullable
    public Cursor getItems(@Nullable @Category.CategoryType String category, int offset,
            int limit, @Nullable String mimeType, @Nullable UserId userId) throws
            IllegalArgumentException, IllegalStateException {
        if (userId == null) {
            userId = UserId.CURRENT_USER;
        }

        return getItemsInternal(category, offset, limit, mimeType, userId);
    }

    @Nullable
    private Cursor getItemsInternal(@Nullable @Category.CategoryType String category,
            int offset, int limit, @Nullable String mimeType, @NonNull UserId userId) throws
            IllegalArgumentException, IllegalStateException {
        // Validate incoming params
        if (category != null && Category.isValidCategory(category)) {
            throw new IllegalArgumentException("ItemsProvider does not support the given "
                    + "category: " + category);
        }

        final String[] projection = ItemColumns.ALL_COLUMNS_LIST.toArray(new String[0]);
        return query(projection, category, mimeType, offset, limit, userId);
    }

    /**
     * Returns a {@link Cursor} to all non-empty categories in which images/videos are categorised.
     * This includes:
     * * A constant list of local categories for on-device images/videos: {@link Category}
     * * Albums provided by selected cloud provider
     *
     * @param mimeType the mime type of item. {@code null} returns all images/videos that are
     *                 scanned by {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get categories as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}.
     *
     * @return {@link Cursor} for each category would contain the following columns in
     * their relative order:
     * categoryName: {@link CategoryColumns#NAME} The name of the category,
     * categoryCoverUri: {@link CategoryColumns#COVER_URI} The Uri for the cover of
     *                   the category. By default this will be the most recent image/video in that
     *                   category,
     * categoryNumberOfItems: {@link CategoryColumns#NUMBER_OF_ITEMS} number of image/video items
     *                        in the category,
     */
    @Nullable
    public Cursor getCategories(@Nullable String mimeType, @Nullable UserId userId) {
        if (userId == null) {
            userId = UserId.CURRENT_USER;
        }
        return buildCategoriesCursor(Category.CATEGORIES_LIST, mimeType, userId);
    }

    private Cursor buildCategoriesCursor(List<String> categories, @Nullable String mimeType,
            @NonNull UserId userId) {
        MatrixCursor c = new MatrixCursor(CategoryColumns.getAllColumns());

        for (String category: categories) {
            String[] categoryRow = getCategoryColumns(category, mimeType, userId);
            if (categoryRow != null) {
                c.addRow(categoryRow);
            }
        }

        return c;
    }

    private String[] getCategoryColumns(@Category.CategoryType String category,
            @Nullable String mimeType, @NonNull UserId userId) throws IllegalArgumentException,
            IllegalStateException {
        if (!Category.isValidCategory(category)) {
            throw new IllegalArgumentException("Category type not supported");
        }

        final String[] projection = new String[] { MediaColumns._ID };
        Cursor c = query(projection, category, mimeType, 0, -1, userId);
        // Send null if the cursor is null or cursor size is empty
        if (c == null || !c.moveToFirst()) {
            return null;
        }

        return new String[] {
                category,
                String.valueOf(getMediaStoreUriForItem(c.getLong(0))),
                String.valueOf(c.getCount())
        };
    }

    @Nullable
    private Cursor query(@NonNull String[] projection,
            @Nullable @Category.CategoryType String category, @Nullable String mimeType, int offset,
            int limit, @NonNull UserId userId) throws IllegalStateException {
        String selection = IMAGES_VIDEOS_WHERE_CLAUSE;
        String[] selectionArgs = null;

        if (category != null && Category.getWhereClauseForCategory(category) != null) {
            selection += " AND (" + Category.getWhereClauseForCategory(category) + ")";
        }

        if (mimeType != null) {
            selection += " AND (" + MediaColumns.MIME_TYPE + " LIKE ? )";
            selectionArgs = new String[] {replaceMatchAnyChar(mimeType)};
        }
        return queryMediaStore(projection, selection, selectionArgs, offset, limit, userId);
    }

    @Nullable
    private Cursor queryMediaStore(@NonNull String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs, int offset,
            int limit, @NonNull UserId userId) throws IllegalStateException {
        final Uri contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (ContentProviderClient client = userId.getContentResolver(mContext)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            Bundle extras = new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
            // DATE_TAKEN is time in milliseconds, whereas DATE_MODIFIED is time in seconds.
            // Sort by DATE_MODIFIED if DATE_TAKEN is NULL
            extras.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "COALESCE(" + MediaColumns.DATE_TAKEN + "," + MediaColumns.DATE_MODIFIED +
                    "* 1000) DESC");
            extras.putInt(ContentResolver.QUERY_ARG_OFFSET, offset);
            if (limit != -1) {
                extras.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
            }

            return client.query(contentUri, projection, extras, null);
        } catch (RemoteException ignored) {
            // Do nothing, return null.
        }
        return null;
    }

    private static Uri getMediaStoreUriForItem(long id) {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id);
    }

    private static String replaceMatchAnyChar(@NonNull String mimeType) {
        return mimeType.replace('*', '%');
    }

    public static Uri getItemsUri(long id, UserId userId) {
        final Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id);
        if (userId.equals(UserId.CURRENT_USER)) {
            return uri;
        } else {
            return ContentProvider.createContentUriForUser(uri, userId.getUserHandle());
        }
    }
}
