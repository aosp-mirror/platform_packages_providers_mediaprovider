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
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.data.ItemsProvider;
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
        public static String DATE_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED;
        public static String DURATION = MediaStore.MediaColumns.DURATION;

        private static final String[] ALL_COLUMNS = {
                ID,
                MIME_TYPE,
                DISPLAY_NAME,
                VOLUME_NAME,
                DATE_TAKEN,
                DATE_MODIFIED,
                DURATION,
        };
        public static List<String> ALL_COLUMNS_LIST = Collections.unmodifiableList(
                Arrays.asList(ALL_COLUMNS));
    }

    private static final String MIME_TYPE_GIF = "image/gif";

    private long mId;
    private long mDateTaken;
    private long mDuration;
    private String mDisplayName;
    private String mMimeType;
    private String mVolumeName;
    private Uri mUri;
    private boolean mIsImage;
    private boolean mIsVideo;
    private boolean mIsGif;
    private boolean mIsDate;
    private boolean mIsMessage;

    private Item() {}

    public Item(@NonNull Cursor cursor, @NonNull UserId userId) {
        updateFromCursor(cursor, userId);
    }

    @VisibleForTesting
    public Item(long id, String mimeType, String displayName, String volumeName, long dateTaken,
            long duration, Uri uri) {
        mId = id;
        mMimeType = mimeType;
        mDisplayName = displayName;
        mVolumeName = volumeName;
        mDateTaken = dateTaken;
        mDuration = duration;
        mUri = uri;
        parseMimeType();
    }

    public long getId() {
        return mId;
    }

    public boolean isImage() {
        return mIsImage;
    }

    public boolean isVideo() {
        return mIsVideo;
    }

    public boolean isGif() {
        return mIsGif;
    }

    public boolean isDate() {
        return mIsDate;
    }

    public boolean isMessage() {
        return mIsMessage;
    }

    public Uri getContentUri() {
        return mUri;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public long getDuration() {
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

    public static Item fromCursor(Cursor cursor, UserId userId) {
        assert(cursor != null);
        final Item item = new Item(cursor, userId);
        return item;
    }

    /**
     * Return a message item.
     */
    public static Item createMessageItem() {
        final Item item = new Item();
        item.mIsMessage = true;
        return item;
    }

    /**
     * Return the date item. If dateTaken is 0, it is a recent item.
     * @param dateTaken the time of date taken. The unit is in milliseconds since
     *                  January 1, 1970 00:00:00.0 UTC.
     * @return the item with date type
     */
    public static Item createDateItem(long dateTaken) {
        final Item item = new Item();
        item.mIsDate = true;
        item.mDateTaken = dateTaken;
        return item;
    }

    /**
     * Update the item based on the cursor
     * @param cursor the cursor to update the data
     */
    public void updateFromCursor(@NonNull Cursor cursor, @NonNull UserId userId) {
        mId = getCursorLong(cursor, ItemColumns.ID);
        mMimeType = getCursorString(cursor, ItemColumns.MIME_TYPE);
        mDisplayName = getCursorString(cursor, ItemColumns.DISPLAY_NAME);
        mDateTaken = getCursorLong(cursor, ItemColumns.DATE_TAKEN);
        if (mDateTaken < 0) {
            // Convert DATE_MODIFIED to millis
            mDateTaken = getCursorLong(cursor, ItemColumns.DATE_MODIFIED) * 1000;
        }
        mVolumeName = getCursorString(cursor, ItemColumns.VOLUME_NAME);
        mDuration = getCursorLong(cursor, ItemColumns.DURATION);

        // TODO (b/188867567): Currently, we only has local data source,
        //  get the uri from provider
        mUri = ItemsProvider.getItemsUri(mId, userId);

        parseMimeType();
    }

    private void parseMimeType() {
        if (MIME_TYPE_GIF.equalsIgnoreCase(mMimeType)) {
            mIsGif = true;
        } else if (MimeUtils.isImageMimeType(mMimeType)) {
            mIsImage = true;
        } else if (MimeUtils.isVideoMimeType(mMimeType)) {
            mIsVideo = true;
        }
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
}
