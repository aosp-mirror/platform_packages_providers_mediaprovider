/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;

interface ThumbTask extends Callable<File> {
}

class ImageThumbTask implements ThumbTask {
    final MediaProvider mp;
    final long imageId;
    final int kind;
    final CancellationSignal signal;

    final Uri uri;
    final Uri fullUri;
    final String selection;
    final String[] selectionArgs;

    ImageThumbTask(MediaProvider mp, long imageId, int kind, CancellationSignal signal) {
        this.mp = mp;
        this.imageId = imageId;
        this.kind = kind;
        this.signal = signal;

        uri = Images.Thumbnails.EXTERNAL_CONTENT_URI;
        fullUri = ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, imageId);
        selection = Images.Thumbnails.IMAGE_ID + "=? AND " + Images.Thumbnails.KIND + "=?";
        selectionArgs = new String[] { Long.toString(imageId), Integer.toString(kind) };
    }

    @Override
    public File call() throws IOException {
        try {
            // Try returning thumbnail if it already exists
            return mp.queryForDataFile(uri, selection, selectionArgs, signal);

        } catch (FileNotFoundException e) {
            // Generate the thumbnail and then return it
            final ContentValues values = new ContentValues();
            values.put(Images.Thumbnails.IMAGE_ID, imageId);
            values.put(Images.Thumbnails.KIND, kind);

            final Uri newUri = mp.insert(uri, values);
            final ContentResolver cr = mp.getContext().getContentResolver();
            try (OutputStream out = cr.openOutputStream(newUri)) {
                final File fullFile = mp.queryForDataFile(fullUri, signal);
                final Bitmap bitmap = ThumbnailUtils.createImageThumbnail(
                        fullFile.getAbsolutePath(), kind);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }

            return mp.queryForDataFile(uri, selection, selectionArgs, signal);
        }
    }
}

class VideoThumbTask implements ThumbTask {
    final MediaProvider mp;
    final long videoId;
    final int kind;
    final CancellationSignal signal;

    final Uri uri;
    final Uri fullUri;
    final String selection;
    final String[] selectionArgs;

    VideoThumbTask(MediaProvider mp, long videoId, int kind, CancellationSignal signal) {
        this.mp = mp;
        this.videoId = videoId;
        this.kind = kind;
        this.signal = signal;

        uri = Video.Thumbnails.EXTERNAL_CONTENT_URI;
        fullUri = ContentUris.withAppendedId(Video.Media.EXTERNAL_CONTENT_URI, videoId);
        selection = Video.Thumbnails.VIDEO_ID + "=? AND " + Video.Thumbnails.KIND + "=?";
        selectionArgs = new String[] { Long.toString(videoId), Integer.toString(kind) };
    }

    @Override
    public File call() throws IOException {
        try {
            // Try returning thumbnail if it already exists
            return mp.queryForDataFile(uri, selection, selectionArgs, signal);

        } catch (FileNotFoundException e) {
            // Generate the thumbnail and then return it
            final ContentValues values = new ContentValues();
            values.put(Video.Thumbnails.VIDEO_ID, videoId);
            values.put(Video.Thumbnails.KIND, kind);

            final Uri newUri = mp.insert(uri, values);
            final ContentResolver cr = mp.getContext().getContentResolver();
            try (OutputStream out = cr.openOutputStream(newUri)) {
                final File fullFile = mp.queryForDataFile(fullUri, signal);
                final Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(
                        fullFile.getAbsolutePath(), kind);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }

            return mp.queryForDataFile(uri, selection, selectionArgs, signal);
        }
    }
}
