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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.Random;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MiniThumbFile;
import android.media.ThumbnailUtil;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.Images.Thumbnails;
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
    static final int PRIORITY_HIGHEST = 0;
    private static final String[] THUMB_PROJECTION = new String[] {
        BaseColumns._ID // 0
    };

    ContentResolver mCr;
    String mPath;
    long mRequestTime = System.currentTimeMillis();
    int mPriority;
    Uri mUri;
    boolean mIsVideo;
    long mOrigId;
    boolean mDone;
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
                return;
            }
        }

        // If we can't retrieve the thumbnail, first check if there is one
        // embedded in the EXIF data. If not, or it's not big enough,
        // decompress the full size image.
        Bitmap bitmap = null;

        if (mPath != null) {
            if (mIsVideo) {
                bitmap = ThumbnailUtil.createVideoThumbnail(mPath);
            } else {
                bitmap = createThumbnailFromEXIF();
                if (bitmap == null) {
                    bitmap = createThumbnailFromUri();
                }
            }
        }

        // make a new magic number since things are out of sync
        do {
            magic = mRandom.nextLong();
        } while (magic == 0);

        if (bitmap != null) {
            byte [] data = ThumbnailUtil.miniThumbData(bitmap);

            // We may consider retire this proprietary format, after all it's size is only
            // 128 x 128 at most, which is still reasonable to be stored in database.
            // Gallery application can use the MINI_THUMB_MAGIC value to determine if it's
            // time to query and fetch by using Cursor.getBlob
            miniThumbFile.saveMiniThumbToFile(data, mOrigId, magic);

            ContentValues values = new ContentValues();
            // both video/images table use the same column name "mini_thumb_magic"
            values.put(ImageColumns.MINI_THUMB_MAGIC, magic);
            mCr.update(mUri, values, null, null);
        } else {
            Log.w(TAG, "can't create bitmap for thumbnail.");
        }
    }

    // The fallback case is to decode the original photo to thumbnail size,
    // then encode it as a JPEG. We return the thumbnail Bitmap in order to
    // create the minithumb from it.
    private Bitmap createThumbnailFromUri() {
        Bitmap bitmap = ThumbnailUtil.makeBitmap(ThumbnailUtil.THUMBNAIL_TARGET_SIZE,
                ThumbnailUtil.THUMBNAIL_MAX_NUM_PIXELS, mUri,
                mCr);
        if (bitmap != null) {
            storeThumbnail(bitmap);
        } else {
            bitmap = ThumbnailUtil.makeBitmap(ThumbnailUtil.MINI_THUMB_TARGET_SIZE,
                    ThumbnailUtil.MINI_THUMB_MAX_NUM_PIXELS, mUri,
                    mCr);
        }
        return bitmap;
    }

    // If the photo has an EXIF thumbnail and it's big enough, extract it and
    // save that JPEG as the large thumbnail without re-encoding it. We still
    // have to decompress it though, in order to generate the minithumb.
    private Bitmap createThumbnailFromEXIF() {
        if (mPath == null) return null;

        try {
            ExifInterface exif = new ExifInterface(mPath);
            byte [] thumbData = exif.getThumbnail();
            if (thumbData == null) return null;
            // Sniff the size of the EXIF thumbnail before decoding it. Photos
            // from the device will pass, but images that are side loaded from
            // other cameras may not.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(thumbData, 0, thumbData.length, options);
            int width = options.outWidth;
            int height = options.outHeight;
            if (width >= ThumbnailUtil.THUMBNAIL_TARGET_SIZE
                    && height >= ThumbnailUtil.THUMBNAIL_TARGET_SIZE) {

                // We do not check the return value of storeThumbnail because
                // we should return the mini thumb even if the storing fails.
                storeThumbnail(thumbData, width, height);
                // this is used for *encoding* the minithumb, so
                // we don't want to dither or convert to 565 here.
                //
                // Decode with a scaling factor
                // to match MINI_THUMB_TARGET_SIZE closely
                // which will produce much better scaling quality
                // and is significantly faster.
                options.inSampleSize =
                        ThumbnailUtil.computeSampleSize(options,
                        ThumbnailUtil.MINI_THUMB_TARGET_SIZE,
                        ThumbnailUtil.MINI_THUMB_MAX_NUM_PIXELS);
                options.inDither = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inJustDecodeBounds = false;
                return BitmapFactory.decodeByteArray(
                        thumbData, 0, thumbData.length, options);
            }
        }
        catch (IOException ex) {
            Log.w(TAG, ex);
        }
        return null;
    }

    /**
     * Store a given thumbnail in the database.
     */
    private Bitmap storeThumbnail(Bitmap thumb) {
        if (thumb == null) return null;
        try {
            Uri uri = getThumbnailUri(thumb.getWidth(), thumb.getHeight());
            if (uri == null) {
                return thumb;
            }
            OutputStream thumbOut = mCr.openOutputStream(uri);
            thumb.compress(Bitmap.CompressFormat.JPEG, 60, thumbOut);
            thumbOut.close();
            return thumb;
        } catch (Exception ex) {
            Log.e(TAG, "Unable to store thumbnail", ex);
            return thumb;
        }
    }

    /**
     * Store a JPEG thumbnail from the EXIF header in the database.
     */
    private boolean storeThumbnail(byte[] jpegThumbnail, int width, int height) {
        if (jpegThumbnail == null) return false;

        Uri uri = getThumbnailUri(width, height);
        if (uri == null) {
            return false;
        }
        try {
            OutputStream thumbOut = mCr.openOutputStream(uri);
            thumbOut.write(jpegThumbnail);
            thumbOut.close();
            return true;
        } catch (FileNotFoundException ex) {
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Look up thumbnail uri by given imageId, it will be automatically created if it's not created
     * yet. Most of the time imageId is identical to thumbId, but it's not always true.
     * @param req
     * @param width
     * @param height
     * @return Uri Thumbnail uri
     */
    private Uri getThumbnailUri(int width, int height) {
        Uri thumbUri = Images.Thumbnails.EXTERNAL_CONTENT_URI;
        ContentResolver cr = mCr;
        Cursor c = cr.query(thumbUri, THUMB_PROJECTION,
              Thumbnails.IMAGE_ID + "=?",
              new String[]{String.valueOf(mOrigId)}, null);
        try {
            if (c.moveToNext()) {
                return ContentUris.withAppendedId(thumbUri, c.getLong(0));
            }
        } finally {
            c.close();
        }

        ContentValues values = new ContentValues(4);
        values.put(Thumbnails.KIND, Thumbnails.MINI_KIND);
        values.put(Thumbnails.IMAGE_ID, mOrigId);
        values.put(Thumbnails.HEIGHT, height);
        values.put(Thumbnails.WIDTH, width);
        try {
            return mCr.insert(thumbUri, values);
        } catch (Exception ex) {
            Log.w(TAG, ex);
            return null;
        }
    }
}
