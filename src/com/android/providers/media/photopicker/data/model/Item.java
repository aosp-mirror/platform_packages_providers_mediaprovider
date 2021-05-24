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

import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.util.MimeUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Base class representing one single entity/item in the PhotoPicker.
 */
public class Item {

    public static class ItemColumns {
        public static String ID = MediaStore.MediaColumns._ID;
        public static String MIME_TYPE = MediaStore.MediaColumns.MIME_TYPE;
        public static String DISPLAY_NAME = MediaStore.MediaColumns.DISPLAY_NAME;
        public static String VOLUME_NAME = MediaStore.MediaColumns.VOLUME_NAME;
        public static String DATE_TAKEN = MediaStore.MediaColumns.DATE_TAKEN;
        public static String DURATION = MediaStore.MediaColumns.DURATION;
        public static String USER_ID = MediaStore.Files.FileColumns._USER_ID;

        private static final String[] ALL_COLUMNS = {
                ID,
                MIME_TYPE,
                DISPLAY_NAME,
                VOLUME_NAME,
                DATE_TAKEN,
                DURATION,
                // TODO: Unable to query USER_ID
                // ItemColumns.USER_ID,
        };
        public static List<String> ALL_COLUMNS_LIST = Collections.unmodifiableList(
                Arrays.asList(ALL_COLUMNS));
    }

    private long mId;
    private long mDateTaken;
    private String mDisplayName;
    private int mDuration;
    private String mMimeType;
    private String mVolumeName;
    private Uri mUri;

    private Item() {}

    public Item(@NonNull Cursor cursor) {
        updateFromCursor(cursor);
    }

    public long getId() {
        return mId;
    }

    public boolean isPhoto() {
        return MimeUtils.isImageMimeType(mMimeType);
    }

    public Uri getContentUri() {
        return mUri;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public int getDuration() {
        return mDuration;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public long getDateTaken() {
        return mDateTaken;
    }

    public String getVolumeName() {
        return mVolumeName;
    }

    public static Item fromCursor(Cursor cursor) {
        assert(cursor != null);
        final Item info = new Item(cursor);
        return info;
    }

    /**
     * Update the item based on the cursor
     * @param cursor the cursor to update the data
     */
    public void updateFromCursor(@NonNull Cursor cursor) {
        mId = getCursorLong(cursor, ItemColumns.ID);
        mMimeType = getCursorString(cursor, ItemColumns.MIME_TYPE);
        mDisplayName = getCursorString(cursor, ItemColumns.DISPLAY_NAME);
        mDateTaken = getCursorLong(cursor, ItemColumns.DATE_TAKEN);
        mVolumeName = getCursorString(cursor, ItemColumns.VOLUME_NAME);
        mDuration = getCursorInt(cursor, ItemColumns.DURATION);

        // TODO (b/188867567): Currently, we only has local data source,
        //  get the uri from provider
        mUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, mId);
    }

    @Nullable
    private static String getCursorString(Cursor cursor, String columnName) {
        if (cursor == null) {
            return null;
        }
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getString(index) : null;
    }

    /**
     * Missing or null values are returned as -1.
     */
    private static long getCursorLong(Cursor cursor, String columnName) {
        if (cursor == null) {
            return -1;
        }

        final int index = cursor.getColumnIndex(columnName);
        if (index == -1) {
            return -1;
        }

        final String value = cursor.getString(index);
        if (value == null) {
            return -1;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Missing or null values are returned as 0.
     */
    private static int getCursorInt(Cursor cursor, String columnName) {
        if (cursor == null) {
            return 0;
        }

        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getInt(index) : 0;
    }
}
