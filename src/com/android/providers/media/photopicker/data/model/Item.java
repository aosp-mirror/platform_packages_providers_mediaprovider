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

import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO;

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorInt;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.util.DateTimeUtils;
import com.android.providers.media.util.MimeUtils;

/**
 * Base class representing one single entity/item in the PhotoPicker.
 */
public class Item {

    public static class ItemColumns {
        public static String ID = CloudMediaProviderContract.MediaColumns.ID;
        public static String MIME_TYPE = CloudMediaProviderContract.MediaColumns.MIME_TYPE;
        public static String DATE_TAKEN = CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS;
        // TODO(b/195009139): Remove after fully switching to picker db
        public static String DATE_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED;
        public static String GENERATION_MODIFIED =
                CloudMediaProviderContract.MediaColumns.SYNC_GENERATION;
        public static String DURATION = CloudMediaProviderContract.MediaColumns.DURATION_MILLIS;
        public static String SIZE = CloudMediaProviderContract.MediaColumns.SIZE_BYTES;
        public static String AUTHORITY = CloudMediaProviderContract.MediaColumns.AUTHORITY;
        public static String SPECIAL_FORMAT =
                CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION;

        public static final String[] ALL_COLUMNS = {
                ID,
                MIME_TYPE,
                DATE_TAKEN,
                DATE_MODIFIED,
                GENERATION_MODIFIED,
                DURATION,
                SPECIAL_FORMAT
        };

        // TODO(b/195009139): Remove after fully switching to picker db
        public static final String[] PROJECTION = {
            MediaStore.MediaColumns._ID + " AS " + ID,
            MediaStore.MediaColumns.MIME_TYPE + " AS " + MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN + " AS " + DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED + " AS " + DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED + " AS " + GENERATION_MODIFIED,
            MediaStore.MediaColumns.DURATION +  " AS " + DURATION,
            MediaStore.Files.FileColumns._SPECIAL_FORMAT +  " AS " + SPECIAL_FORMAT,
        };
    }

    private String mId;
    private long mDateTaken;
    private long mGenerationModified;
    private long mDuration;
    private String mMimeType;
    private Uri mUri;
    private boolean mIsImage;
    private boolean mIsVideo;
    private int mSpecialFormat;
    private boolean mIsDate;

    private Item() {}

    public Item(@NonNull Cursor cursor, @NonNull UserId userId) {
        updateFromCursor(cursor, userId);
    }

    @VisibleForTesting
    public Item(String id, String mimeType, long dateTaken, long generationModified, long duration,
            Uri uri, int specialFormat) {
        mId = id;
        mMimeType = mimeType;
        mDateTaken = dateTaken;
        mGenerationModified = generationModified;
        mDuration = duration;
        mUri = uri;
        mSpecialFormat = specialFormat;
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

    public boolean isGifOrAnimatedWebp() {
        return isGif() || isAnimatedWebp();
    }

    public boolean isGif() {
        return mSpecialFormat == _SPECIAL_FORMAT_GIF;
    }

    public boolean isAnimatedWebp() {
        return mSpecialFormat == _SPECIAL_FORMAT_ANIMATED_WEBP;
    }

    public boolean isMotionPhoto() {
        return mSpecialFormat == _SPECIAL_FORMAT_MOTION_PHOTO;
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

    public long getGenerationModified() {
        return mGenerationModified;
    }

    @VisibleForTesting
    public int getSpecialFormat() {
        return mSpecialFormat;
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
        mGenerationModified = getCursorLong(cursor, ItemColumns.GENERATION_MODIFIED);
        mDuration = getCursorLong(cursor, ItemColumns.DURATION);
        mSpecialFormat = getCursorInt(cursor, ItemColumns.SPECIAL_FORMAT);
        mUri = ItemsProvider.getItemsUri(mId, authority, userId);

        parseMimeType();
    }

    public String getContentDescription(@NonNull Context context) {
        if (isVideo()) {
            return context.getString(R.string.picker_video_item_content_desc,
                    DateTimeUtils.getDateTimeStringForContentDesc(getDateTaken()),
                    getDurationText());
        }

        final String itemType;
        if (isGif() || isAnimatedWebp()) {
            itemType = context.getString(R.string.picker_gif);
        } else if (isMotionPhoto()) {
            itemType = context.getString(R.string.picker_motion_photo);
        } else {
            itemType = context.getString(R.string.picker_photo);
        }

        return context.getString(R.string.picker_item_content_desc, itemType,
                DateTimeUtils.getDateTimeStringForContentDesc(getDateTaken()));
    }

    public String getDurationText() {
        if (mDuration == -1) {
            return "";
        }
        return DateUtils.formatElapsedTime(mDuration / 1000);
    }

    private void parseMimeType() {
        if (MimeUtils.isImageMimeType(mMimeType)) {
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
