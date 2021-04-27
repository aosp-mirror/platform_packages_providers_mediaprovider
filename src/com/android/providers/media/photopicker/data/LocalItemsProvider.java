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
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
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

    private static final String IMAGES_VIDEOS_WHERE_CLAUSE = "( " +
            FileColumns.MEDIA_TYPE + " = " + FileColumns.MEDIA_TYPE_IMAGE + " OR "
            + FileColumns.MEDIA_TYPE + " = " + FileColumns.MEDIA_TYPE_VIDEO + " )";

    private final Context mContext;

    public LocalItemsProvider(Context context) {
        mContext = context;
    }

    /**
     * Returns a {@link Cursor} to all images/videos that are scanned by {@link MediaStore}
     * based on the param passed for {@code mimeType}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by lastModified.
     *
     * @param mimeType the mime type of item, only {@code image/*} or {@code video/*} is an
     *                 acceptable mimeType here. Any other mimeType than image/video throws error.
     *                 {@code null} returns all images/videos that are scanned by
     *                 {@link MediaStore}.
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
     * @throws IllegalArgumentException thrown if any mimeType other than {@code image/*} or
     * {@code video/*} is passed.
     *
     */
    @Nullable
    public Cursor getItems(@Nullable String mimeType) throws IllegalArgumentException {
        if (mimeType == null) {
            return queryMediaStore(null);
        }
        if (isMimeTypeImageVideo(mimeType)) {
            return queryMediaStore(replaceMatchAnyChar(mimeType));
        }
        throw new IllegalArgumentException("LocalItemsProvider does not support the given"
                + " mimeType: " + mimeType);
    }

    @Nullable
    private Cursor queryMediaStore(@Nullable String mimeType) {
        final Uri contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);

        // TODO: This can be extracted to another class/interface as the consumer of this getItems
        // needs to be aware of what the Cursor has returned and how to access it. Instead of
        // hardcoding projection values in both the layers.
        final String[] projection = new String[] {
                MediaColumns._ID, MediaColumns.DATA, MediaColumns.MIME_TYPE,
                MediaColumns.IS_FAVORITE };

        String selection = IMAGES_VIDEOS_WHERE_CLAUSE;
        String[] selectionArgs = null;

        if (mimeType != null) {
            selection += " AND " + MediaColumns.MIME_TYPE + " LIKE ? ";
            selectionArgs = new String[] {mimeType};
        }

        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            Bundle extras = new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
            return client.query(contentUri, projection, extras, null);
        } catch (RemoteException ignored) {
            // Do nothing, return null.
        }
        return null;
    }

    private boolean isMimeTypeImageVideo(@NonNull String mimeType) {
        return isImageMimeType(mimeType) || isVideoMimeType(mimeType);
    }

    private String replaceMatchAnyChar(@NonNull String mimeType) {
        return mimeType.replace('*', '%');
    }
}
