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

import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO;

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorInt;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.util.DateTimeUtils;
import com.android.providers.media.util.MimeUtils;

/**
 * Base class for representing a single media item (a picture, a video, etc.) in the PhotoPicker.
 */
public class Item {
    private String mId;
    private long mDateTaken;
    private long mGenerationModified;
    private long mDuration;
    private String mMimeType;
    private Uri mUri;
    private boolean mIsImage;
    private boolean mIsVideo;
    private int mSpecialFormat;

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

    public static Item fromCursor(@NonNull Cursor cursor, UserId userId) {
        return new Item(requireNonNull(cursor), userId);
    }

    /**
     * Update the item based on the cursor
     *
     * @param cursor the cursor to update the data
     * @param userId the user id to create an {@link Item} for
     */
    public void updateFromCursor(@NonNull Cursor cursor, @NonNull UserId userId) {
        final String authority = getCursorString(cursor, MediaColumns.AUTHORITY);
        mId = getCursorString(cursor, MediaColumns.ID);
        mMimeType = getCursorString(cursor, MediaColumns.MIME_TYPE);
        mDateTaken = getCursorLong(cursor, MediaColumns.DATE_TAKEN_MILLIS);
        mGenerationModified = getCursorLong(cursor, MediaColumns.SYNC_GENERATION);
        mDuration = getCursorLong(cursor, MediaColumns.DURATION_MILLIS);
        mSpecialFormat = getCursorInt(cursor, MediaColumns.STANDARD_MIME_TYPE_EXTENSION);
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
}
