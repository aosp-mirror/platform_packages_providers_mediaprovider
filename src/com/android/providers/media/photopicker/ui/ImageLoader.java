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

import static com.bumptech.glide.load.resource.bitmap.Downsampler.PREFERRED_COLOR_SPACE;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.Option;
import com.bumptech.glide.load.PreferredColorSpace;
import com.bumptech.glide.load.resource.gif.GifDrawable;
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
    private static final RequestOptions THUMBNAIL_OPTION =
            RequestOptions.option(THUMBNAIL_REQUEST, /* enableThumbnail */ true);
    private final Context mContext;
    private final PreferredColorSpace mPreferredColorSpace;

    public ImageLoader(Context context) {
        mContext = context;

        final boolean isScreenWideColorGamut =
                mContext.getResources().getConfiguration().isScreenWideColorGamut();
        mPreferredColorSpace =
                isScreenWideColorGamut ? PreferredColorSpace.DISPLAY_P3 : PreferredColorSpace.SRGB;
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
        loadWithGlide(getBitmapRequestBuilder(category.getCoverUri()), THUMBNAIL_OPTION,
                /* signature */ null, imageView);
    }

    /**
     * Load the thumbnail of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item      the photo item
     * @param imageView the imageView shows the thumbnail
     */
    public void loadPhotoThumbnail(@NonNull Item item, @NonNull ImageView imageView) {
        // Always show all thumbnails as bitmap images instead of drawables
        // This is to ensure that we do not animate any thumbnail (for eg GIF)
        // TODO(b/194285082): Use drawable instead of bitmap, as it saves memory.
        loadWithGlide(getBitmapRequestBuilder(item.getContentUri()), THUMBNAIL_OPTION,
                getGlideSignature(item, /* prefix */ ""), imageView);
    }

    /**
     * Load the image of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item      the photo item
     * @param imageView the imageView shows the image
     */
    public void loadImagePreview(@NonNull Item item, @NonNull ImageView imageView)  {
        if (item.isGif()) {
            loadWithGlide(getGifRequestBuilder(item.getContentUri()), /* requestOptions */ null,
                    getGlideSignature(item, /* prefix */ ""), imageView);
            return;
        }

        if (item.isAnimatedWebp()) {
            loadAnimatedWebpPreview(item, imageView);
            return;
        }

        // Preview as bitmap image for all other image types
        loadWithGlide(getBitmapRequestBuilder(item.getContentUri()), /* requestOptions */ null,
                getGlideSignature(item, /* prefix */ ""), imageView);
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
        loadWithGlide(getDrawableRequestBuilder(drawable == null ? uri : drawable),
                /* requestOptions */ null, getGlideSignature(item, /* prefix */ ""), imageView);
    }

    /**
     * Loads the image from first frame of the given video item
     */
    public void loadImageFromVideoForPreview(@NonNull Item item, @NonNull ImageView imageView) {
        loadWithGlide(getBitmapRequestBuilder(item.getContentUri()),
                new RequestOptions().frame(1000), getGlideSignature(item, "Preview"), imageView);
    }

    private ObjectKey getGlideSignature(Item item, String prefix) {
        // TODO(b/224725723): Remove media store version from key once MP ids are stable.
        return new ObjectKey(
                MediaStore.getVersion(mContext) + prefix + item.getContentUri().toString() +
                        item.getGenerationModified());
    }

    private RequestBuilder<Bitmap> getBitmapRequestBuilder(Uri uri) {
        return Glide.with(mContext)
                .asBitmap()
                .load(uri);
    }

    private RequestBuilder<GifDrawable> getGifRequestBuilder(Uri uri) {
        return Glide.with(mContext)
                .asGif()
                .load(uri);
    }

    private RequestBuilder<Drawable> getDrawableRequestBuilder(Object model) {
        return Glide.with(mContext)
                .load(model);
    }

    private <T> void loadWithGlide(RequestBuilder<T> requestBuilder,
            @Nullable RequestOptions requestOptions, @Nullable ObjectKey signature,
            ImageView imageView) {
        RequestBuilder<T> newRequestBuilder = requestBuilder.clone();

        final RequestOptions requestOptionsWithPreferredColorSpace;
        if (requestOptions != null) {
            requestOptionsWithPreferredColorSpace = requestOptions.clone();
        } else {
            requestOptionsWithPreferredColorSpace = new RequestOptions();
        }
        requestOptionsWithPreferredColorSpace.set(PREFERRED_COLOR_SPACE, mPreferredColorSpace);

        newRequestBuilder = newRequestBuilder.apply(requestOptionsWithPreferredColorSpace);

        if (signature != null) {
            newRequestBuilder = newRequestBuilder.signature(signature);
        }

        newRequestBuilder.into(imageView);
    }
}
