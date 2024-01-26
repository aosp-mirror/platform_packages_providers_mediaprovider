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
import android.graphics.drawable.Drawable;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.data.glide.GlideLoadable;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.Option;
import com.bumptech.glide.load.PreferredColorSpace;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;

import java.util.Optional;

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
    private static final String PREVIEW_PREFIX = "preview_";

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
    public void loadAlbumThumbnail(@NonNull Category category, @NonNull ImageView imageView,
            int defaultThumbnailRes, @NonNull ImageView defaultIcon) {
        // Always show all thumbnails as bitmap images instead of drawables
        // This is to ensure that we do not animate any thumbnail (for eg GIF)
        // TODO(b/194285082): Use drawable instead of bitmap, as it saves memory.
        if (category.getCoverUri() != null || defaultThumbnailRes == -1) {
            defaultIcon.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);

            loadWithGlide(getBitmapRequestBuilder(category.toGlideLoadable()), THUMBNAIL_OPTION,
                    /* signature */ null, imageView);
        } else {
            imageView.setVisibility(View.INVISIBLE);
            defaultIcon.setVisibility(View.VISIBLE);

            loadWithGlide(getDrawableRequestBuilder(mContext.getDrawable(defaultThumbnailRes)),
                    THUMBNAIL_OPTION,  /* signature */ null, defaultIcon);
        }
    }

    /**
     * Load the thumbnail of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item      the photo item
     * @param imageView the imageView shows the thumbnail
     */
    public void loadPhotoThumbnail(@NonNull Item item, @NonNull ImageView imageView) {
        final GlideLoadable loadable = item.toGlideLoadable();
        // Always show all thumbnails as bitmap images instead of drawables
        // This is to ensure that we do not animate any thumbnail (for eg GIF)
        // TODO(b/194285082): Use drawable instead of bitmap, as it saves memory.
        loadWithGlide(getBitmapRequestBuilder(loadable), THUMBNAIL_OPTION,
                getGlideSignature(loadable, /* prefix */ null), imageView);
    }

    /**
     * Load the image of the photo item {@code item} and set it on the {@code imageView}
     *
     * @param item      the photo item
     * @param imageView the imageView shows the image
     */
    public void loadImagePreview(@NonNull Item item, @NonNull ImageView imageView)  {
        final GlideLoadable loadable = item.toGlideLoadable();
        if (item.isGif()) {
            loadWithGlide(
                    getGifRequestBuilder(loadable),
                    /* requestOptions */ null,
                    getGlideSignature(loadable, /* prefix= */ PREVIEW_PREFIX),
                    imageView);
            return;
        }

        if (item.isAnimatedWebp()) {
            loadWithGlide(
                    getDrawableRequestBuilder(loadable),
                    /* requestOptions */ null,
                    getGlideSignature(loadable, PREVIEW_PREFIX),
                    imageView);
            return;
        }

        // Preview as bitmap image for all other image types
        loadWithGlide(
                getBitmapRequestBuilder(loadable),
                /* requestOptions */ null,
                getGlideSignature(loadable, /* prefix= */ PREVIEW_PREFIX),
                imageView);
    }

    /**
     * Loads the image from first frame of the given video item
     */
    public void loadImageFromVideoForPreview(@NonNull Item item, @NonNull ImageView imageView) {
        final GlideLoadable loadable = item.toGlideLoadable();
        loadWithGlide(
                getBitmapRequestBuilder(loadable),
                new RequestOptions().frame(1000),
                getGlideSignature(loadable, /* prefix= */ PREVIEW_PREFIX),
                imageView);
    }

    private ObjectKey getGlideSignature(GlideLoadable loadable, @Nullable String prefix) {
        // TODO(b/224725723): Remove media store version from key once MP ids are
        // stable.
        return loadable.getLoadableSignature(
                /* prefix= */ MediaStore.getVersion(mContext)
                        + Optional.ofNullable(prefix).orElse(""));
    }

    private RequestBuilder<Bitmap> getBitmapRequestBuilder(GlideLoadable loadable) {
        return Glide.with(mContext)
                .asBitmap()
                .load(loadable);
    }

    private RequestBuilder<GifDrawable> getGifRequestBuilder(GlideLoadable loadable) {
        return Glide.with(mContext)
                .asGif()
                .load(loadable);
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
