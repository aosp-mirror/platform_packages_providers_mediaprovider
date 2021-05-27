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

package com.android.providers.media.photopicker.ui;

import android.content.Context;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.util.Size;
import android.widget.ImageView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

import java.io.IOException;


/**
 * A class to assist with loading and managing the Images (i.e. thumbnails and preview) associated
 * with item.
 */
public class ImageLoader {

    private static final String TAG = "ImageLoader";
    private final Context mContext;

    public ImageLoader(Context context) {
        mContext = context;
    }

    public void loadThumbanial(Item item, ImageView imageView) {
        int thumbSize = getThumbSize();
        final Size size = new Size(thumbSize, thumbSize);
        try {
            Bitmap bitmap = mContext.getContentResolver().loadThumbnail(item.getContentUri(),
                    size, null);
            imageView.setImageDrawable(new BitmapDrawable(mContext.getResources(), bitmap));
        } catch (IOException ex) {
            Log.d(TAG, "Loading icon failed", ex);
            imageView.setImageDrawable(null);
        }
    }

    public void loadImagePreview(Item item, ImageView imageView) {
       // TODO(b/185801129): Use Glide for image loading
       // TODO(b/185801129): Load image in background thread. Loading the image blocks loading the
       // layout now.
        try {
            imageView.setImageBitmap(ImageDecoder.decodeBitmap(ImageDecoder.createSource(
                    mContext.getContentResolver(), item.getContentUri())));
        } catch (IOException e) {
            Log.d(TAG, "Failed loading image for uri " + item.getContentUri(), e);
            imageView.setImageBitmap(null);
        }
    }

    private int getThumbSize() {
        return mContext.getResources().getDimensionPixelSize(R.dimen.picker_photo_size);
    }
}
