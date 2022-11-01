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

package com.android.providers.media.photopicker.data.glide;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.ImageHeaderParserUtils;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.ExifOrientationStream;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Custom {@link DataFetcher} to fetch a {@link InputStream} for a thumbnail from a cloud
 * media provider.
 */
public class PickerThumbnailFetcher implements DataFetcher<InputStream> {

    private static final String TAG = "PickerThumbnailFetcher";
    private final Context mContext;
    private final Uri mModel;
    private final int mWidth;
    private final int mHeight;
    private final boolean mIsThumbRequest;

    PickerThumbnailFetcher(Context context, Uri model, int width, int height,
            boolean isThumbRequest) {
        mContext = context;
        mModel = model;
        mWidth = width;
        mHeight = height;
        mIsThumbRequest = isThumbRequest;
    }

    @Override
    public void loadData(Priority priority, DataCallback<? super InputStream> callback) {
        ContentResolver contentResolver = mContext.getContentResolver();
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(mWidth, mHeight));
        opts.putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true);

        if (mIsThumbRequest) {
            opts.putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true);
        }

        try (AssetFileDescriptor afd = contentResolver.openTypedAssetFileDescriptor(mModel,
                /* mimeType */ "image/*", opts, /* cancellationSignal */ null)) {
            if (afd == null) {
                final String err = "Failed to load data for " + mModel;
                callback.onLoadFailed(new FileNotFoundException(err));
                return;
            }

            final InputStream inputStream;
            if (mIsThumbRequest) {
                inputStream = getOrientationInputStream(afd);
            } else {
                // We don't need to handle orientation for preview requests. Glide load takes care
                // of loading the image in the right orientation.
                inputStream = afd.createInputStream();
            }
            callback.onDataReady(inputStream);
        } catch (IOException e) {
            callback.onLoadFailed(e);
        }
    }

    private InputStream getOrientationInputStream(AssetFileDescriptor afd) throws IOException {
        InputStream inputStream = afd.createInputStream();

        int orientation = -1;
        if (inputStream != null) {
            try {
                orientation = ImageHeaderParserUtils.getOrientation(
                        Glide.get(mContext).getRegistry().getImageHeaderParsers(), inputStream,
                        Glide.get(mContext).getArrayPool());
            } catch (IOException | NullPointerException ignored) {
                Log.d(TAG, "Unable to fetch orientation for " + mModel, ignored);
            }
        }

        if (orientation != -1) {
            inputStream = new ExifOrientationStream(inputStream, orientation);
        }
        return inputStream;
    }

    @Override
    public void cleanup() {
        // Intentionally empty only because we're not opening an InputStream or another I/O
        // resource.
    }

    @Override
    public void cancel() {
        // Intentionally empty.
    }

    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @Override
    public DataSource getDataSource() {
        return DataSource.LOCAL;
    }
}
