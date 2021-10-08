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

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.util.MimeUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Base class representing one single entity/item in the PhotoPicker.
 */
public class Item {

    public static class ItemColumns {
        public static String ID = CloudMediaProviderContract.MediaColumns.ID;
        public static String MIME_TYPE = CloudMediaProviderContract.MediaColumns.MIME_TYPE;
        public static String DATE_TAKEN = CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MS;
        // TODO(b/195009139): Remove after fully switching to picker db
        public static String DATE_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED;
        public static String DURATION = CloudMediaProviderContract.MediaColumns.DURATION_MS;
        public static String SIZE = CloudMediaProviderContract.MediaColumns.SIZE_BYTES;
        public static String AUTHORITY = CloudMediaProviderContract.MediaColumns.AUTHORITY;

        public static final String[] ALL_COLUMNS = {
                ID,
                MIME_TYPE,
                DATE_TAKEN,
                DATE_MODIFIED,
                DURATION,
        };

        // TODO(b/195009139): Remove after fully switching to picker db
        public static final String[] PROJECTION = {
            MediaStore.MediaColumns._ID + " AS " + ID,
            MediaStore.MediaColumns.MIME_TYPE + " AS " + MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN + " AS " + DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED + " AS " + DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION +  " AS " + DURATION,
        };
    }

    private static final String MIME_TYPE_GIF = "image/gif";

    private String mId;
    private long mDateTaken;
    private long mDuration;
    private String mMimeType;
    private Uri mUri;
    private boolean mIsImage;
    private boolean mIsVideo;
    private boolean mIsGif;
    private boolean mIsDate;

    private Item() {}

    public Item(@NonNull Cursor cursor, @NonNull UserId userId) {
        updateFromCursor(cursor, userId);
    }

    @VisibleForTesting
    public Item(String id, String mimeType, long dateTaken, long duration, Uri uri) {
        mId = id;
        mMimeType = mimeType;
        mDateTaken = dateTaken;
        mDuration = duration;
        mUri = uri;
        parseMimeType();
    }

    public String getId() {
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

    public Uri getContentUri() {
        return mUri;
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

    public static Item fromCursor(Cursor cursor, UserId userId) {
        assert(cursor != null);
        final Item item = new Item(cursor, userId);
        return item;
    }

    /**
     * Return the date item. If dateTaken is 0, it is a recent item.
     * @param dateTaken the time of date taken. The unit is in milliseconds
     *                  since January 1, 1970 00:00:00.0 UTC.
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
     *
     * @param cursor the cursor to update the data
     * @param userId the user id to create an {@link Item} for
     */
    public void updateFromCursor(@NonNull Cursor cursor, @NonNull UserId userId) {
        final String authority = extractAuthority(cursor);
        mId = getCursorString(cursor, ItemColumns.ID);
        mMimeType = getCursorString(cursor, ItemColumns.MIME_TYPE);
        mDateTaken = getCursorLong(cursor, ItemColumns.DATE_TAKEN);
        if (mDateTaken < 0) {
            // Convert DATE_MODIFIED to millis
            mDateTaken = getCursorLong(cursor, ItemColumns.DATE_MODIFIED) * 1000;
        }
        mDuration = getCursorLong(cursor, ItemColumns.DURATION);

        // TODO (b/188867567): Currently, we only has local data source,
        //  get the uri from provider
        mUri = ItemsProvider.getItemsUri(mId, authority, userId);

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

    /**
     * Compares this item with given {@code anotherItem} by comparing
     * {@link Item#getDateTaken()} value. When {@link Item#getDateTaken()} is
     * same, Items are compared based on {@link Item#getId}.
     */
    public int compareTo(Item anotherItem) {
        if (mDateTaken > anotherItem.getDateTaken()) {
            return 1;
        } else if (mDateTaken < anotherItem.getDateTaken()) {
            return -1;
        } else {
            return mId.compareTo(anotherItem.getId());
        }
    }

    private String extractAuthority(Cursor cursor) {
        final String authority = getCursorString(cursor, ItemColumns.AUTHORITY);
        if (authority == null) {
            final Bundle bundle = cursor.getExtras();
            return bundle.getString(ItemColumns.AUTHORITY);
        }
        return authority;
    }
}
