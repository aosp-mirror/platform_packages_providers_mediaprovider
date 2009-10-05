/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.providers.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Random;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MiniThumbFile;
import android.media.ThumbnailUtil;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Images.ImageColumns;
import android.util.Log;

/**
 * Instances of this class are created and put in a queue to be executed sequentially to see if
 * it needs to (re)generate the thumbnails.
 */
class MediaThumbRequest {
    private static final String TAG = "MediaThumbRequest";
    static final int PRIORITY_LOW = 20;
    static final int PRIORITY_NORMAL = 10;
    static final int PRIORITY_HIGH = 5;
    static final int PRIORITY_CRITICAL = 0;
    static enum State {WAIT, DONE, CANCEL}
    private static final String[] THUMB_PROJECTION = new String[] {
        BaseColumns._ID // 0
    };

    ContentResolver mCr;
    String mPath;
    long mRequestTime = System.currentTimeMillis();
    int mCallingPid = Binder.getCallingPid();
    int mPriority;
    Uri mUri;
    boolean mIsVideo;
    long mOrigId;
    State mState = State.WAIT;
    long mMagic;

    private BitmapFactory.Options sBitmapOptions = new BitmapFactory.Options();
    private final Random mRandom = new Random();

    static Comparator<MediaThumbRequest> getComparator() {
        return new Comparator<MediaThumbRequest>() {
            public int compare(MediaThumbRequest r1, MediaThumbRequest r2) {
                if (r1.mPriority != r2.mPriority) {
                    return r1.mPriority < r2.mPriority ? -1 : 1;
                }
                return r1.mRequestTime == r2.mRequestTime ? 0 :
                        r1.mRequestTime < r2.mRequestTime ? -1 : 1;
            }
        };
    }

    MediaThumbRequest(ContentResolver cr, String path, Uri uri, int priority) {
        mCr = cr;
        mPath = path;
        mPriority = priority;
        mUri = uri;
        mIsVideo = "video".equals(uri.getPathSegments().get(1));
        mOrigId = ContentUris.parseId(uri);
    }

    /**
     * Check if the corresponding thumbnail and mini-thumb have been created
     * for the given uri. This method creates both of them if they do not
     * exist yet or have been changed since last check. After thumbnails are
     * created, MINI_KIND thumbnail is stored in JPEG file and MICRO_KIND
     * thumbnail is stored in a random access file (MiniThumbFile).
     *
     * @throws IOException
     */
    void execute() throws IOException {
        MiniThumbFile miniThumbFile = MiniThumbFile.instance(mUri);
        long magic = mMagic;
        if (magic != 0) {
            long fileMagic = miniThumbFile.getMagic(mOrigId);
            if (fileMagic == magic) {
                // signature is identical. skip this item!
                // Log.v(TAG, "signature is identical. skip this item!");
                if (mIsVideo) return;

                Cursor c = mCr.query(Images.Thumbnails.EXTERNAL_CONTENT_URI,
                        new String[] {MediaColumns._ID}, "image_id = " + mOrigId, null, null);
                ParcelFileDescriptor pfd = null;
                try {
                    if (c != null && c.moveToFirst()) {
                        pfd = mCr.openFileDescriptor(
                                Images.Thumbnails.EXTERNAL_CONTENT_URI.buildUpon()
                                .appendPath(c.getString(0)).build(), "r");
                    }
                } finally {
                    if (c != null) c.close();
                    if (pfd != null) {
                        pfd.close();
                        return;
                    }
                }
            }
        }

        // If we can't retrieve the thumbnail, first check if there is one
        // embedded in the EXIF data. If not, or it's not big enough,
        // decompress the full size image.
        Bitmap bitmap = null;

        if (mPath != null) {
            if (mIsVideo) {
                bitmap = ThumbnailUtil.createVideoThumbnail(mPath);
                if (bitmap != null) {
                    bitmap = ThumbnailUtil.extractMiniThumb(bitmap,
                    ThumbnailUtil.MINI_THUMB_TARGET_SIZE,
                    ThumbnailUtil.MINI_THUMB_TARGET_SIZE, ThumbnailUtil.RECYCLE_INPUT);
                }
            } else {
                bitmap = ThumbnailUtil.createImageThumbnail(mCr, mPath, mUri, mOrigId,
                        Images.Thumbnails.MICRO_KIND, true);
            }
        }

        // make a new magic number since things are out of sync
        do {
            magic = mRandom.nextLong();
        } while (magic == 0);

        if (bitmap != null) {
            ByteArrayOutputStream miniOutStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, miniOutStream);
            bitmap.recycle();
            byte [] data = null;

            try {
                miniOutStream.close();
                data = miniOutStream.toByteArray();
            } catch (java.io.IOException ex) {
                Log.e(TAG, "got exception ex " + ex);
            }

            // We may consider retire this proprietary format, after all it's size is only
            // 128 x 128 at most, which is still reasonable to be stored in database.
            // Gallery application can use the MINI_THUMB_MAGIC value to determine if it's
            // time to query and fetch by using Cursor.getBlob
            if (data != null) {
                miniThumbFile.saveMiniThumbToFile(data, mOrigId, magic);
                ContentValues values = new ContentValues();
                // both video/images table use the same column name "mini_thumb_magic"
                values.put(ImageColumns.MINI_THUMB_MAGIC, magic);
                mCr.update(mUri, values, null, null);
            }
        } else {
            Log.w(TAG, "can't create bitmap for thumbnail.");
        }
    }
}
