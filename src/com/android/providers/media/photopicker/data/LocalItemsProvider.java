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

import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;

import android.annotation.NonNull;
import android.annotation.Nullable;
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

import java.util.ArrayList;
import java.util.List;

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

    private static final String IMAGES_VIDEOS_WHERE_CLAUSE = "( " +
            FileColumns.MEDIA_TYPE + " = " + FileColumns.MEDIA_TYPE_IMAGE + " OR "
            + FileColumns.MEDIA_TYPE + " = " + FileColumns.MEDIA_TYPE_VIDEO + " )";

    private final Context mContext;

    public LocalItemsProvider(Context context) {
        mContext = context;
    }

    /**
     * Returns a {@link Cursor} to all images/videos that are scanned by {@link MediaStore}
     * based on the param passed for {@code categoryType}, {@code offset}, {@code limit}
     * and {@code mimeType}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by latest {@link MediaColumns#DATE_TAKEN}.
     *
     * @param category the category of items to return, {@link Category.CategoryType} are supported.
     *                 {@code null} defaults to {@link Category#CATEGORY_DEFAULT} which returns
     *                 items from all categories.
     * @param offset the offset after which to return items. Does not respect non-positive
     *               values.
     * @param limit the limit of items to return. Does not respect non-positive values.
     * @param mimeType the mime type of item, only {@code image/*} or {@code video/*} is an
     *                 acceptable mimeType here. Any other mimeType than image/video throws error.
     *                 {@code null} returns all images/videos that are scanned by
     *                 {@link MediaStore}.
     *
     * @return {@link Cursor} to all images/videos on external storage that are scanned by
     * {@link MediaStore} based on params passed, or {@code null} if there are no such
     * images/videos. The Cursor for each item would contain {@link ItemColumns}
     *
     * @throws IllegalArgumentException thrown if unsupported mimeType or category is passed.
     *
     */
    @Nullable
    public Cursor getItems(@Nullable @Category.CategoryType String category, int offset, int limit,
            @Nullable String mimeType) throws IllegalArgumentException {
        // 1. Validate incoming params
        if (category != null && Category.isValidCategory(category)) {
            throw new IllegalArgumentException("LocalItemsProvider does not support the given"
                    + " category: " + category);
        }

        if (mimeType != null && !isMimeTypeImageVideo(mimeType)) {
            throw new IllegalArgumentException("LocalItemsProvider does not support the given"
                    + " mimeType: " + mimeType);
        }

        // 2. Create args to query MediaStore
        String selection = null;
        String[] selectionArgs = null;

        if (category != null && Category.getWhereClauseForCategory(category) != null) {
            selection = Category.getWhereClauseForCategory(category);
        }

        if (mimeType != null && isMimeTypeImageVideo(mimeType)) {
            if (selection != null) {
                selection += " AND ";
            } else {
                selection = "";
            }
            selection += MediaColumns.MIME_TYPE + " LIKE ? ";
            selectionArgs = new String[] {replaceMatchAnyChar(mimeType)};
        }

        final String[] projection = ItemColumns.ALL_COLUMNS_LIST.toArray(new String[0]);
        // 3. Query MediaStore and return
        return queryMediaStore(projection, selection, selectionArgs, offset, limit);
    }

    /**
     * Returns a {@link Cursor} to all non-empty categories in which images/videos (that are
     * scanned by {@link MediaStore}) are put in buckets based on certain criteria.
     * This includes a list of constant categories for LocalItemsProvider: {@link Category} contains
     * a constant list of local categories we have on-device and want to support for v0.
     *
     * The Cursor for each category would contain the following columns in their relative order:
     * categoryName: {@link CategoryColumns#NAME} The name of the category,
     * categoryCoverUri: {@link CategoryColumns#COVER_URI} The Uri for the cover of
     *                   the category. By default this will be the most recent image/video in that
     *                   category,
     * categoryNumberOfItems: {@link CategoryColumns#NUMBER_OF_ITEMS} number of image/video items
     *                        in the category,
     *
     */
    @Nullable
    public Cursor getCategories() {
        return buildCategoriesCursor(Category.CATEGORIES_LIST);
    }

    private Cursor buildCategoriesCursor(List<String> categories) {
        MatrixCursor c = new MatrixCursor(CategoryColumns.getAllColumns());

        for (String category: categories) {
            String[] categoryRow = getCategoryColumns(category);
            if (categoryRow != null) {
                c.addRow(categoryRow);
            }
        }

        return c;
    }

    private String[] getCategoryColumns(@Category.CategoryType String category)
            throws IllegalArgumentException {
        if (!Category.isValidCategory(category)) {
            throw new IllegalArgumentException("Category type not supported");
        }
        final String whereClause = Category.getWhereClauseForCategory(category);
        final String[] projection = new String[] {
                MediaColumns._ID
        };
        Cursor c = queryMediaStore(projection, whereClause, null, 0, 0);
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
    private Cursor queryMediaStore(@NonNull String[] projection, @Nullable String extraSelection,
            @Nullable String[] extraSelectionArgs, int offset, int limit) {
        final Uri contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);

        String selection = IMAGES_VIDEOS_WHERE_CLAUSE;
        String[] selectionArgs = null;

        if (extraSelection != null) {
            selection += " AND " + extraSelection;
        }
        if (extraSelectionArgs != null) {
            selectionArgs = extraSelectionArgs;
        }

        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            Bundle extras = new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
            extras.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    MediaColumns.DATE_TAKEN + " DESC");
            if (offset > 0) {
                extras.putInt(ContentResolver.QUERY_ARG_OFFSET, offset);
            }
            if (limit > 0) {
                extras.putString(ContentResolver.QUERY_ARG_LIMIT, String.valueOf(limit));
            }
            return client.query(contentUri, projection, extras, null);
        } catch (RemoteException ignored) {
            // Do nothing, return null.
        }
        return null;
    }

    private Uri getMediaStoreUriForItem(long id) {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id);
    }

    private boolean isMimeTypeImageVideo(@NonNull String mimeType) {
        return isImageMimeType(mimeType) || isVideoMimeType(mimeType);
    }

    private String replaceMatchAnyChar(@NonNull String mimeType) {
        return mimeType.replace('*', '%');
    }
}
