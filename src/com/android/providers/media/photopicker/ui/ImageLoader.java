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
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.Option;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;

/**
 * A class to assist with loading and managing the Images (i.e. thumbnails and preview) associated
 * with item.
 */
public class ImageLoader {

    public static final Option<Boolean> THUMBNAIL_REQUEST =
            Option.memory(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, false);
    private static final String TAG = "ImageLoader";
    private final Context mContext;

    public ImageLoader(Context context) {
        mContext = context;
    }

    /**
     * Load the thumbnail of the {@code category} and set it on the {@code imageView}
     *
     * @param category  the album
     * @param imageView the imageView shows the thumbnail
     */
    public void loadAlbumThumbnail(@NonNull Category category, @NonNull ImageView imageView) {
        // Always show all thumbnails as bitmap images instead of drawables
        // This is to ensure that we do not animate any thumbnail (for eg GIF)
        // TODO(b/194285082): Use drawable instead of bitmap, as it saves memory.
        Glide.with(mContext)
                .asBitmap()
                .load(category.getCoverUri())
                .apply(RequestOptions.option(THUMBNAIL_REQUEST, true))
                .into(imageView);
    }

    /**
     * Load the thumbnail of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item      the photo item
     * @param imageView the imageView shows the thumbnail
     */
    public void loadPhotoThumbnail(@NonNull Item item, @NonNull ImageView imageView) {
        Uri uri = item.getContentUri();
        // Always show all thumbnails as bitmap images instead of drawables
        // This is to ensure that we do not animate any thumbnail (for eg GIF)
        // TODO(b/194285082): Use drawable instead of bitmap, as it saves memory.
        Glide.with(mContext)
                .asBitmap()
                .load(uri)
                .signature(getGlideSignature(item, /* prefix */ ""))
                .apply(RequestOptions.option(THUMBNAIL_REQUEST, true))
                .into(imageView);
    }

    /**
     * Load the image of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item      the photo item
     * @param imageView the imageView shows the image
     */
    public void loadImagePreview(@NonNull Item item, @NonNull ImageView imageView)  {
        if (item.isGif()) {
            Glide.with(mContext)
                    .asGif()
                    .load(item.getContentUri())
                    .signature(getGlideSignature(item, /* prefix */ ""))
                    .into(imageView);
            return;
        }

        if (item.isAnimatedWebp()) {
            loadAnimatedWebpPreview(item, imageView);
            return;
        }

        // Preview as bitmap image for all other image types
        Glide.with(mContext)
                .asBitmap()
                .load(item.getContentUri())
                .signature(getGlideSignature(item, /* prefix */ ""))
                .into(imageView);
    }

    private void loadAnimatedWebpPreview(@NonNull Item item, @NonNull ImageView imageView) {
        final Uri uri = item.getContentUri();
        final ImageDecoder.Source source = ImageDecoder.createSource(mContext.getContentResolver(),
                uri);
        Drawable drawable = null;
        try {
            drawable = ImageDecoder.decodeDrawable(source);
        } catch (Exception e) {
            Log.d(TAG, "Failed to decode drawable for uri: " + uri, e);
        }

        // If we failed to decode drawable for a source using ImageDecoder, then try using uri
        // directly. Glide will show static image for an animated webp. That is okay as we tried our
        // best to load animated webp but couldn't, and we anyway show the GIF badge in preview.
        Glide.with(mContext)
                .load(drawable == null ? uri : drawable)
                .signature(getGlideSignature(item, /* prefix */ ""))
                .into(imageView);
    }

    /**
     * Loads the image from first frame of the given video item
     */
    public void loadImageFromVideoForPreview(@NonNull Item item, @NonNull ImageView imageView) {
        Glide.with(mContext)
                .asBitmap()
                .load(item.getContentUri())
                .apply(new RequestOptions().frame(1000))
                .signature(getGlideSignature(item, "Preview"))
                .into(imageView);
    }

    private ObjectKey getGlideSignature(Item item, String prefix) {
        // TODO(b/224725723): Remove media store version from key once MP ids are stable.
        return new ObjectKey(
                MediaStore.getVersion(mContext) + prefix + item.getContentUri().toString() +
                        item.getGenerationModified());
    }
}
